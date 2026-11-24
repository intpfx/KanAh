//! Live's private identity and trusted-controller store. Never derive secrets from Bonjour IDs.
use crate::{Error, Result};
use ed25519_dalek::{Signature, VerifyingKey};
use rand::{rngs::OsRng, RngCore};
use std::{
    collections::BTreeMap,
    fs::{self, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::Mutex,
    time::{Duration, Instant},
};

struct State {
    seed: [u8; 32],
    controllers: BTreeMap<Vec<u8>, [u8; 32]>,
    window: Option<Instant>,
    attempts: u8,
}
pub struct PairingStore {
    path: Option<PathBuf>,
    state: Mutex<State>,
}
impl std::fmt::Debug for PairingStore {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PairingStore")
            .field("persistent", &self.path.is_some())
            .finish_non_exhaustive()
    }
}
impl PairingStore {
    pub fn ephemeral() -> Self {
        let mut seed = [0; 32];
        OsRng.fill_bytes(&mut seed);
        Self {
            path: None,
            state: Mutex::new(State {
                seed,
                controllers: BTreeMap::new(),
                window: None,
                attempts: 0,
            }),
        }
    }
    pub fn load(dir: &Path) -> Result<Self> {
        if !dir.is_absolute() {
            return Err(Error::Protocol(
                "identity directory must be absolute".into(),
            ));
        }
        fs::create_dir_all(dir)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(dir, fs::Permissions::from_mode(0o700))?;
        }
        let path = dir.join("companion-identity-v1");
        let mut result = Self::ephemeral();
        result.path = Some(path.clone());
        match fs::File::open(&path) {
            Ok(file) => {
                let mut body = String::new();
                file.take(65537).read_to_string(&mut body)?;
                if body.len() > 65536 {
                    return Err(Error::Protocol("identity store too large".into()));
                }
                let mut lines = body.lines();
                let seed = hex::decode(lines.next().unwrap_or(""))
                    .map_err(|_| Error::Protocol("invalid identity seed".into()))?;
                let seed: [u8; 32] = seed
                    .try_into()
                    .map_err(|_| Error::Protocol("invalid identity seed size".into()))?;
                let mut state = result.state.lock().unwrap();
                state.seed = seed;
                for line in lines {
                    let (id, key) = line
                        .split_once(' ')
                        .ok_or_else(|| Error::Protocol("invalid controller record".into()))?;
                    let id = hex::decode(id)
                        .map_err(|_| Error::Protocol("invalid controller ID".into()))?;
                    let key: [u8; 32] = hex::decode(key)
                        .map_err(|_| Error::Protocol("invalid controller key".into()))?
                        .try_into()
                        .map_err(|_| Error::Protocol("invalid controller key size".into()))?;
                    validate_id(&id)?;
                    VerifyingKey::from_bytes(&key)
                        .map_err(|_| Error::Crypto("invalid controller public key".into()))?;
                    if state.controllers.insert(id, key).is_some() {
                        return Err(Error::Protocol("duplicate controller".into()));
                    }
                }
                if state.controllers.len() > 32 {
                    return Err(Error::Protocol("too many controllers".into()));
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                result.persist(&result.state.lock().unwrap())?;
            }
            Err(e) => return Err(e.into()),
        }
        Ok(result)
    }
    fn persist(&self, state: &State) -> Result<()> {
        let Some(path) = &self.path else {
            return Ok(());
        };
        let mut body = format!("{}\n", hex::encode(state.seed));
        for (id, key) in &state.controllers {
            body.push_str(&format!("{} {}\n", hex::encode(id), hex::encode(key)));
        }
        let pending = path.with_extension(format!("pending-{}", uuid::Uuid::new_v4()));
        let outcome = (|| -> Result<()> {
            let mut options = OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            let mut file = options.open(&pending)?;
            file.write_all(body.as_bytes())?;
            file.sync_all()?;
            fs::rename(&pending, path)?;
            fs::File::open(path.parent().unwrap())?.sync_all()?;
            Ok(())
        })();
        if outcome.is_err() {
            let _ = fs::remove_file(pending);
        }
        outcome
    }
    pub fn seed(&self) -> [u8; 32] {
        self.state.lock().unwrap().seed
    }
    pub fn open_window(&self, seconds: u32) {
        let mut s = self.state.lock().unwrap();
        s.window = Some(Instant::now() + Duration::from_secs(seconds.min(300) as u64));
        s.attempts = 0;
    }
    pub fn close_window(&self) {
        self.state.lock().unwrap().window = None;
    }
    pub fn window_open(&self) -> bool {
        self.state
            .lock()
            .unwrap()
            .window
            .is_some_and(|end| Instant::now() < end)
    }
    pub fn pairing_allowed(&self) -> bool {
        let s = self.state.lock().unwrap();
        s.attempts < 10 && s.window.is_some_and(|end| Instant::now() < end)
    }
    pub fn begin_attempt(&self) -> Result<()> {
        let mut s = self.state.lock().unwrap();
        if s.attempts >= 10 || !s.window.is_some_and(|end| Instant::now() < end) {
            return Err(Error::Protocol("pairing window closed".into()));
        }
        s.attempts += 1;
        Ok(())
    }
    pub fn add(&self, id: &[u8], key: [u8; 32]) -> Result<()> {
        validate_id(id)?;
        let mut s = self.state.lock().unwrap();
        if !s.window.is_some_and(|end| Instant::now() < end) {
            return Err(Error::Protocol("pairing window closed".into()));
        }
        if s.controllers.len() >= 32 && !s.controllers.contains_key(id) {
            return Err(Error::Protocol("controller limit reached".into()));
        }
        if s.controllers.get(id).is_some_and(|old| old != &key) {
            return Err(Error::Protocol("controller identity already paired".into()));
        }
        let old = s.controllers.insert(id.to_vec(), key);
        if let Err(e) = self.persist(&s) {
            match old {
                Some(v) => {
                    s.controllers.insert(id.to_vec(), v);
                }
                None => {
                    s.controllers.remove(id);
                }
            }
            return Err(e);
        }
        Ok(())
    }
    pub fn verify(&self, id: &[u8], message: &[u8], signature: &[u8]) -> Result<()> {
        let s = self.state.lock().unwrap();
        let key = s
            .controllers
            .get(id)
            .ok_or_else(|| Error::Crypto("unpaired controller".into()))?;
        verify_signature(key, message, signature)
    }
    pub fn forget(&self) -> Result<()> {
        let mut s = self.state.lock().unwrap();
        let previous = std::mem::take(&mut s.controllers);
        s.window = None;
        if let Err(e) = self.persist(&s) {
            s.controllers = previous;
            return Err(e);
        }
        Ok(())
    }
}
pub fn validate_id(id: &[u8]) -> Result<()> {
    if id.is_empty() || id.len() > 128 {
        return Err(Error::Protocol("invalid controller identifier size".into()));
    }
    Ok(())
}
pub fn verify_signature(key: &[u8; 32], message: &[u8], signature: &[u8]) -> Result<()> {
    let key =
        VerifyingKey::from_bytes(key).map_err(|_| Error::Crypto("invalid Ed25519 key".into()))?;
    let signature = Signature::from_slice(signature)
        .map_err(|_| Error::Crypto("invalid Ed25519 signature".into()))?;
    key.verify_strict(message, &signature)
        .map_err(|_| Error::Crypto("controller signature rejected".into()))
}
#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    #[test]
    fn private_identity_and_controllers_survive_restart() {
        let dir = std::env::temp_dir().join(format!("live-atv-test-{}", uuid::Uuid::new_v4()));
        let store = PairingStore::load(&dir).unwrap();
        let seed = store.seed();
        let key = SigningKey::from_bytes(&[12; 32]);
        assert!(store
            .add(b"iphone", key.verifying_key().to_bytes())
            .is_err());
        store.open_window(120);
        store
            .add(b"iphone", key.verifying_key().to_bytes())
            .unwrap();
        store.close_window();
        let loaded = PairingStore::load(&dir).unwrap();
        assert_eq!(seed, loaded.seed());
        assert!(!loaded.pairing_allowed());
        let signature = key.sign(b"handshake").to_bytes();
        assert!(loaded.verify(b"iphone", b"handshake", &signature).is_ok());
        assert!(loaded.verify(b"unknown", b"handshake", &signature).is_err());
        assert!(loaded.verify(b"iphone", b"tampered", &signature).is_err());
        loaded.forget().unwrap();
        assert!(PairingStore::load(&dir)
            .unwrap()
            .verify(b"iphone", b"handshake", &signature)
            .is_err());
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn window_expires_and_limits_attempts() {
        let store = PairingStore::ephemeral();
        assert!(!store.pairing_allowed());
        store.open_window(0);
        assert!(store.begin_attempt().is_err());
        store.open_window(120);
        for _ in 0..10 {
            store.begin_attempt().unwrap();
        }
        assert!(!store.pairing_allowed());
        assert!(store.begin_attempt().is_err());
    }
    #[test]
    fn corrupt_identity_is_never_silently_replaced() {
        let dir = std::env::temp_dir().join(format!("live-atv-test-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("companion-identity-v1"), "broken").unwrap();
        assert!(PairingStore::load(&dir).is_err());
        assert_eq!(
            fs::read_to_string(dir.join("companion-identity-v1")).unwrap(),
            "broken"
        );
        fs::remove_dir_all(dir).unwrap();
    }
}
