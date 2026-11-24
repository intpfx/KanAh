//! Companion Link session: pairing, verification and the encrypted control
//! channel. Ported from `fake_atv.py`'s `CompanionSession`.

use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::Arc;

use crate::pairing::{validate_id, verify_signature, PairingStore};
use ed25519_dalek::{Signer, SigningKey};
use rand::rngs::OsRng;
use tokio::io::AsyncWriteExt;
use tokio::net::tcp::OwnedWriteHalf;
use tokio::sync::Mutex;
use tracing::{debug, info, warn};
use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret};

use crate::crypto::{hkdf_expand, CompanionCipher};
use crate::delegate::{AtvDelegate, EventKind, TouchPhase, TrackpadMode};
use crate::error::{Error, Result};
use crate::identity::DeviceIdentity;
use crate::inspector::InspectorHub;
use crate::opack::{self, Value};
use crate::registry::{ActiveConnectionTracker, DeviceRegistry};
use crate::srp::SrpServer;
use crate::tlv::{self, tags};
use crate::FrameType;

pub const DEVICE_MODEL: &str = "AppleTV14,1";
pub const MEDIA_CONTROL_VOLUME: u64 = 0x0100;

const SWIPE_MIN_DISTANCE: f64 = 150.0; // touchpad units; the surface is 1000x1000
const TAP_MAX_DISTANCE: f64 = 60.0; // below this a touch sequence counts as a tap

/// Fixed `altIRK` value from `fake_atv._pair_setup_m5`.
const ALT_IRK: [u8; 16] = [
    0x2d, 0x54, 0xe0, 0x7a, 0x88, 0x2a, 0x65, 0x6e, 0x11, 0xab, 0x82, 0x76, 0x2d, 0x27, 0x25, 0xc5,
];

fn fresh_srp(pin: u32) -> SrpServer {
    use rand::RngCore;
    let mut secret = [0; 32];
    OsRng.fill_bytes(&mut secret);
    SrpServer::new(pin, &secret)
}

pub type SharedWriter = Arc<Mutex<OwnedWriteHalf>>;

fn hid_command_name(command: u64) -> String {
    match command {
        1 => "up".into(),
        2 => "down".into(),
        3 => "left".into(),
        4 => "right".into(),
        5 => "menu".into(),
        6 => "select".into(),
        7 => "home".into(),
        8 => "volume_up".into(),
        9 => "volume_down".into(),
        10 => "siri".into(),
        11 => "screensaver".into(),
        12 => "sleep".into(),
        13 => "wake".into(),
        14 => "play_pause".into(),
        15 => "channel_up".into(),
        16 => "channel_down".into(),
        17 => "guide".into(),
        // iOS 26/27 repurposes the old PageUp/PageDown wire codes for the
        // Control Center remote's Mute and Power buttons.
        18 => "mute".into(),
        19 => "power".into(),
        other => format!("unknown:{other}"),
    }
}

/// One Companion connection's state machine.
pub struct CompanionSession {
    writer: SharedWriter,
    delegate: Arc<dyn AtvDelegate>,
    device_name: String,
    device_mac: [u8; 6],
    pin: u32,
    _mouse_mode: Arc<AtomicBool>,
    trackpad_mode: Arc<AtomicU8>,
    unique_id: Vec<u8>,
    signing_key: SigningKey,
    auth_pub: [u8; 32],
    verify_private: StaticSecret,
    verify_pub: X25519PublicKey,
    cipher: Option<CompanionCipher>,
    pairing: Arc<PairingStore>,
    setup_started: bool,
    setup_proof_verified: bool,
    verify_pending: Option<([u8; 32], [u8; 32])>,
    current_identifier: Option<String>,
    srp: SrpServer,
    remote_session_id: u32,
    session_established: Arc<AtomicBool>,
    touch_origin: Option<(f64, f64)>,
    touch_last: Option<(f64, f64)>,
    touch_moved: bool,
    select_pressed: Option<tokio::time::Instant>,
    volume: f64,
    muted: bool,
    system_status: u64,
    display_power_on: bool,
    inspector: Arc<InspectorHub>,
    registry: Arc<Mutex<DeviceRegistry>>,
    active_tracker: Arc<Mutex<ActiveConnectionTracker>>,
    conn_id: u64,
    peer_ip: String,
    device_id: Option<String>,
    timeout_deadline: Arc<Mutex<Option<tokio::time::Instant>>>,
}

impl CompanionSession {
    pub fn new(
        writer: SharedWriter,
        delegate: Arc<dyn AtvDelegate>,
        identity: &DeviceIdentity,
        pin: u32,
        mouse_mode: Arc<AtomicBool>,
        trackpad_mode: Arc<AtomicU8>,
        inspector: Arc<InspectorHub>,
        registry: Arc<Mutex<DeviceRegistry>>,
        active_tracker: Arc<Mutex<ActiveConnectionTracker>>,
        conn_id: u64,
        peer_ip: String,
        session_established: Arc<AtomicBool>,
    ) -> Self {
        let signing_key = SigningKey::from_bytes(&identity.private_key);
        let auth_pub = signing_key.verifying_key().to_bytes();
        let verify_private = StaticSecret::random_from_rng(OsRng);
        let verify_pub = X25519PublicKey::from(&verify_private);
        let remote_session_id: u32 = rand::random();
        let (initial_volume, initial_muted) = delegate.get_audio_state().unwrap_or((0.5, false));
        Self {
            writer,
            delegate,
            device_name: identity.name.clone(),
            device_mac: identity.mac_bytes,
            pin,
            _mouse_mode: mouse_mode,
            trackpad_mode,
            unique_id: identity.server_identifier.as_bytes().to_vec(),
            signing_key,
            auth_pub,
            verify_private,
            verify_pub,
            cipher: None,
            pairing: identity.pairing.clone(),
            setup_started: false,
            setup_proof_verified: false,
            verify_pending: None,
            current_identifier: None,
            srp: fresh_srp(pin),
            remote_session_id,
            session_established,
            touch_origin: None,
            touch_last: None,
            touch_moved: false,
            select_pressed: None,
            volume: initial_volume,
            muted: initial_muted,
            system_status: 3, // Awake
            display_power_on: true,
            inspector,
            registry,
            active_tracker,
            conn_id,
            peer_ip,
            device_id: None,
            timeout_deadline: Arc::new(Mutex::new(None)),
        }
    }

    pub fn is_established(&self) -> bool {
        self.session_established.load(Ordering::SeqCst)
    }

    pub fn timeout_deadline(&self) -> Arc<Mutex<Option<tokio::time::Instant>>> {
        self.timeout_deadline.clone()
    }

    pub fn device_id(&self) -> Option<String> {
        self.device_id.clone()
    }

    async fn write_raw(&self, bytes: &[u8]) -> Result<()> {
        let mut writer = self.writer.lock().await;
        tokio::time::timeout(std::time::Duration::from_secs(10), async {
            writer.write_all(bytes).await?;
            writer.flush().await
        })
        .await
        .map_err(|_| Error::Protocol("frame write timeout".into()))??;
        Ok(())
    }

    async fn send_frame(&self, frame_type: FrameType, payload: &[u8]) -> Result<()> {
        let mut out = Vec::with_capacity(4 + payload.len());
        out.push(frame_type as u8);
        out.extend_from_slice(&(payload.len() as u32).to_be_bytes()[1..]);
        out.extend_from_slice(payload);
        self.write_raw(&out).await
    }

    async fn send_opack(&self, frame_type: FrameType, data: &Value) -> Result<()> {
        let payload = opack::pack(data);
        debug!(">> {:?} {}", frame_type, data);
        self.send_frame(frame_type, &payload).await
    }

    async fn send_encrypted_opack(&mut self, data: &Value) -> Result<()> {
        let cipher = self
            .cipher
            .as_mut()
            .ok_or_else(|| Error::Protocol("encryption is not enabled".into()))?;
        let plain = opack::pack(data);
        let mut header = Vec::with_capacity(4);
        header.push(FrameType::EOpack as u8);
        header.extend_from_slice(&((plain.len() + 16) as u32).to_be_bytes()[1..]);
        let encrypted = cipher.encrypt(&plain, Some(&header), None)?;
        debug!(">> E_OPACK {}", data);
        let mut out = header;
        out.extend_from_slice(&encrypted);
        self.write_raw(&out).await
    }

    async fn send_response(
        &mut self,
        xid: Option<u64>,
        content: Option<Value>,
        result_type: u64,
    ) -> Result<()> {
        let mut response = vec![
            Value::kv("_c", content.unwrap_or(Value::dict(vec![]))),
            Value::kv("_rT", Value::Int(result_type)),
            Value::kv("_t", Value::Int(3)),
        ];
        if let Some(identifier) = &self.current_identifier {
            response.push(Value::kv("_i", Value::str(identifier)));
        }
        if let Some(xid) = xid {
            response.push(Value::kv("_x", Value::Int(xid)));
        }
        self.send_encrypted_opack(&Value::Dict(response)).await
    }

    async fn send_event(
        &mut self,
        identifier: &str,
        xid: Option<u64>,
        content: Option<Value>,
    ) -> Result<()> {
        let mut event = vec![
            Value::kv("_i", Value::str(identifier)),
            Value::kv("_t", Value::Int(1)),
            Value::kv("_c", content.unwrap_or(Value::dict(vec![]))),
        ];
        if let Some(xid) = xid {
            event.push(Value::kv("_x", Value::Int(xid)));
        }
        self.send_encrypted_opack(&Value::Dict(event)).await
    }

    /// Advertise volume support using both modern and legacy wire formats.
    async fn send_media_capabilities(&mut self, xid: Option<u64>) -> Result<()> {
        self.send_event(
            "MediaControlStatus",
            xid,
            Some(Value::dict(vec![Value::kv(
                "MediaControlFlags",
                Value::Int(MEDIA_CONTROL_VOLUME),
            )])),
        )
        .await?;
        self.send_event(
            "_iMC",
            xid,
            Some(Value::dict(vec![Value::kv(
                "_mcF",
                Value::Int(MEDIA_CONTROL_VOLUME),
            )])),
        )
        .await?;
        self.delegate
            .on_event(EventKind::Capabilities, "volume,mute,power");
        Ok(())
    }

    /// Re-publish the volume capability flag after a state change so iOS
    /// refreshes its volume HUD (it issues GetVolume in response).
    async fn push_media_state(&mut self, xid: Option<u64>) -> Result<()> {
        self.send_event(
            "MediaControlStatus",
            xid,
            Some(Value::dict(vec![Value::kv(
                "MediaControlFlags",
                Value::Int(MEDIA_CONTROL_VOLUME),
            )])),
        )
        .await?;
        self.send_event(
            "_iMC",
            xid,
            Some(Value::dict(vec![Value::kv(
                "_mcF",
                Value::Int(MEDIA_CONTROL_VOLUME),
            )])),
        )
        .await
    }

    /// Publish current power state using both known Companion event names.
    async fn send_power_state(&mut self, xid: Option<u64>) -> Result<()> {
        let content = Value::dict(vec![Value::kv("state", Value::Int(self.system_status))]);
        self.send_event("SystemStatus", xid, Some(content.clone()))
            .await?;
        self.send_event("TVSystemStatus", xid, Some(content))
            .await?;
        Ok(())
    }

    pub async fn handle_frame(
        &mut self,
        frame_type: FrameType,
        payload: &[u8],
        header: &[u8],
    ) -> Result<()> {
        let mut payload = payload.to_vec();
        if frame_type == FrameType::EOpack {
            match self.cipher.as_mut() {
                Some(cipher) => {
                    payload = cipher.decrypt(&payload, Some(header), None)?;
                }
                None => {
                    warn!("encrypted frame before verification");
                    return Ok(());
                }
            }
        }

        if payload.is_empty() {
            return Ok(());
        }

        let (data, remaining) = opack::unpack(&payload)?;
        if !remaining.is_empty() {
            warn!("trailing OPACK bytes: {:02x?}", remaining);
        }

        match frame_type {
            FrameType::PsStart | FrameType::PsNext | FrameType::PvStart | FrameType::PvNext => {
                self.handle_auth(frame_type, &data).await
            }
            FrameType::EOpack => {
                debug!("<< E_OPACK {}", data);
                self.handle_control(&data).await
            }
            other => {
                debug!("{:?} {}", other, data);
                Ok(())
            }
        }
    }

    async fn handle_auth(&mut self, frame_type: FrameType, data: &Value) -> Result<()> {
        let pairing_data = tlv::read(data.get("_pd").and_then(Value::as_bytes).unwrap_or(b""));
        let seq = match pairing_data.get(&tags::SEQ_NO) {
            Some(value) if value.len() == 1 => value[0],
            _ => return Err(Error::Protocol("invalid authentication sequence".into())),
        };
        info!("<< {:?} seq={}", frame_type, seq);

        match (frame_type, seq) {
            (FrameType::PsStart, 1) => self.pair_setup_m1().await,
            (FrameType::PsNext, 3) => self.pair_setup_m3(&pairing_data).await,
            (FrameType::PsNext, 5) => self.pair_setup_m5(&pairing_data).await,
            (FrameType::PvStart, 1) => self.pair_verify_m1(&pairing_data).await,
            (FrameType::PvNext, 3) => self.pair_verify_m3(&pairing_data).await,
            _ => {
                warn!("unsupported auth step: frame={:?} seq={}", frame_type, seq);
                Ok(())
            }
        }
    }

    async fn pair_setup_m1(&mut self) -> Result<()> {
        self.pairing.begin_attempt()?;
        self.setup_started = true;
        self.setup_proof_verified = false;
        self.srp = fresh_srp(self.pin);
        let tlv_data = tlv::write(&[
            (tags::SEQ_NO, &[0x02]),
            (tags::SALT, self.srp.salt()),
            (tags::PUBLIC_KEY, &self.srp.public_key()),
            (27, &[0x01]),
        ]);
        info!("pairing requested within local pairing window");
        self.inspector.emit("auth", "{\"step\":\"pair_setup_m1\"}");
        self.send_opack(
            FrameType::PsNext,
            &Value::dict(vec![
                Value::kv("_pd", Value::Bytes(tlv_data)),
                Value::kv("_pwTy", Value::Int(1)),
            ]),
        )
        .await
    }

    async fn pair_setup_m3(
        &mut self,
        pairing_data: &std::collections::BTreeMap<u8, Vec<u8>>,
    ) -> Result<()> {
        if !self.setup_started || !self.pairing.window_open() {
            return Err(Error::Protocol("pair setup out of order or closed".into()));
        }
        let public_key = pairing_data
            .get(&tags::PUBLIC_KEY)
            .ok_or_else(|| Error::Protocol("missing client public key".into()))?;
        self.srp.process(public_key)?;
        let proof_ok = pairing_data
            .get(&tags::PROOF)
            .map(|p| self.srp.verify_proof(p))
            .unwrap_or(false);
        self.inspector.emit(
            "auth",
            &format!("{{\"step\":\"pair_setup_m3\",\"verified\":{proof_ok}}}"),
        );
        self.setup_proof_verified = proof_ok;
        let tlv_data = if proof_ok {
            tlv::write(&[
                (tags::SEQ_NO, &[0x04]),
                (tags::PROOF, &self.srp.key_proof_hash()?),
            ])
        } else {
            tlv::write(&[(tags::SEQ_NO, &[0x04]), (tags::ERROR, &[0x02])])
        };
        self.send_opack(
            FrameType::PsNext,
            &Value::dict(vec![Value::kv("_pd", Value::Bytes(tlv_data))]),
        )
        .await
    }

    async fn pair_setup_m5(
        &mut self,
        pairing_data: &std::collections::BTreeMap<u8, Vec<u8>>,
    ) -> Result<()> {
        if !self.setup_proof_verified || !self.pairing.window_open() {
            return Err(Error::Protocol(
                "pair setup proof not verified or window closed".into(),
            ));
        }
        self.setup_proof_verified = false;
        self.setup_started = false;
        let shared = self.srp.session_key()?.to_vec();
        let session_key = hkdf_expand(
            "Pair-Setup-Encrypt-Salt",
            "Pair-Setup-Encrypt-Info",
            &shared,
        );
        let mut chacha = CompanionCipher::new(&session_key, &session_key, 8)?;
        let encrypted = pairing_data
            .get(&tags::ENCRYPTED_DATA)
            .ok_or_else(|| Error::Protocol("missing encrypted data".into()))?;
        let decrypted = chacha.decrypt(encrypted, None, Some(b"PS-Msg05"))?;
        let client_tlv = tlv::read(&decrypted);
        debug!("paired client: {} TLV entries", client_tlv.len());
        let client_id = client_tlv
            .get(&tags::IDENTIFIER)
            .ok_or_else(|| Error::Protocol("missing controller identifier".into()))?;
        validate_id(client_id)?;
        let client_key: [u8; 32] = client_tlv
            .get(&tags::PUBLIC_KEY)
            .ok_or_else(|| Error::Protocol("missing controller signing key".into()))?
            .as_slice()
            .try_into()
            .map_err(|_| Error::Protocol("controller key must be 32 bytes".into()))?;
        let client_signature = client_tlv
            .get(&tags::SIGNATURE)
            .ok_or_else(|| Error::Protocol("missing controller signature".into()))?;
        let controller_x = hkdf_expand(
            "Pair-Setup-Controller-Sign-Salt",
            "Pair-Setup-Controller-Sign-Info",
            &shared,
        );
        let controller_info = [controller_x.as_slice(), client_id.as_slice(), &client_key].concat();
        verify_signature(&client_key, &controller_info, client_signature)?;
        self.pairing.add(client_id, client_key)?;

        let accessory_x = hkdf_expand(
            "Pair-Setup-Accessory-Sign-Salt",
            "Pair-Setup-Accessory-Sign-Info",
            &shared,
        );
        let mut device_info = Vec::with_capacity(32 + self.unique_id.len() + 32);
        device_info.extend_from_slice(&accessory_x);
        device_info.extend_from_slice(&self.unique_id);
        device_info.extend_from_slice(&self.auth_pub);
        let signature = self.signing_key.sign(&device_info);

        let extra = Value::dict(vec![
            Value::kv("altIRK", Value::Bytes(ALT_IRK.to_vec())),
            Value::kv(
                "accountID",
                Value::str(&uuid::Uuid::new_v4().to_string().to_uppercase()),
            ),
            Value::kv("model", Value::str(DEVICE_MODEL)),
            Value::kv("wifiMAC", Value::Bytes(self.device_mac.to_vec())),
            Value::kv("name", Value::str(&self.device_name)),
            Value::kv("mac", Value::Bytes(self.device_mac.to_vec())),
        ]);
        let tlv_data = tlv::write(&[
            (tags::IDENTIFIER, &self.unique_id),
            (tags::PUBLIC_KEY, &self.auth_pub),
            (tags::SIGNATURE, &signature.to_bytes()),
            (17, &opack::pack(&extra)),
        ]);
        let mut chacha = CompanionCipher::new(&session_key, &session_key, 8)?;
        let encrypted = chacha.encrypt(&tlv_data, None, Some(b"PS-Msg06"))?;
        self.send_opack(
            FrameType::PsNext,
            &Value::dict(vec![Value::kv(
                "_pd",
                Value::Bytes(tlv::write(&[
                    (tags::SEQ_NO, &[0x06]),
                    (tags::ENCRYPTED_DATA, &encrypted),
                ])),
            )]),
        )
        .await?;
        info!("pairing complete");
        self.delegate.on_event(EventKind::Paired, "");
        self.inspector.emit("paired", "{}");
        // Fresh salt/verifier for the next pairing attempt.
        self.srp = fresh_srp(self.pin);
        Ok(())
    }

    async fn pair_verify_m1(
        &mut self,
        pairing_data: &std::collections::BTreeMap<u8, Vec<u8>>,
    ) -> Result<()> {
        self.cipher = None;
        self.verify_pending = None;
        self.verify_private = StaticSecret::random_from_rng(OsRng);
        self.verify_pub = X25519PublicKey::from(&self.verify_private);
        let server_pub = self.verify_pub.to_bytes();
        let client_pub_bytes = pairing_data
            .get(&tags::PUBLIC_KEY)
            .ok_or_else(|| Error::Protocol("missing client public key".into()))?;
        let client_pub: [u8; 32] = client_pub_bytes
            .as_slice()
            .try_into()
            .map_err(|_| Error::Protocol("client public key must be 32 bytes".into()))?;
        let shared = self
            .verify_private
            .diffie_hellman(&X25519PublicKey::from(client_pub));
        if !shared.was_contributory() {
            return Err(Error::Crypto("invalid X25519 shared secret".into()));
        }
        let session_key = hkdf_expand(
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
            shared.as_bytes(),
        );

        let mut info = Vec::with_capacity(32 + self.unique_id.len() + 32);
        info.extend_from_slice(&server_pub);
        info.extend_from_slice(&self.unique_id);
        info.extend_from_slice(&client_pub);
        let signature = self.signing_key.sign(&info);

        let mut chacha = CompanionCipher::new(&session_key, &session_key, 8)?;
        let encrypted = chacha.encrypt(
            &tlv::write(&[
                (tags::IDENTIFIER, &self.unique_id),
                (tags::SIGNATURE, &signature.to_bytes()),
            ]),
            None,
            Some(b"PV-Msg02"),
        )?;

        self.verify_pending = Some((client_pub, *shared.as_bytes()));

        self.inspector.emit("auth", "{\"step\":\"pair_verify_m1\"}");

        self.send_opack(
            FrameType::PvNext,
            &Value::dict(vec![Value::kv(
                "_pd",
                Value::Bytes(tlv::write(&[
                    (tags::SEQ_NO, &[0x02]),
                    (tags::PUBLIC_KEY, &server_pub),
                    (tags::ENCRYPTED_DATA, &encrypted),
                ])),
            )]),
        )
        .await
    }

    async fn pair_verify_m3(
        &mut self,
        pairing_data: &std::collections::BTreeMap<u8, Vec<u8>>,
    ) -> Result<()> {
        let (client_pub, shared) = self
            .verify_pending
            .take()
            .ok_or_else(|| Error::Protocol("pair verify out of order".into()))?;
        let key = hkdf_expand(
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
            &shared,
        );
        let encrypted = pairing_data
            .get(&tags::ENCRYPTED_DATA)
            .ok_or_else(|| Error::Protocol("missing verify encrypted data".into()))?;
        let plaintext =
            CompanionCipher::new(&key, &key, 8)?.decrypt(encrypted, None, Some(b"PV-Msg03"))?;
        let data = tlv::read(&plaintext);
        let id = data
            .get(&tags::IDENTIFIER)
            .ok_or_else(|| Error::Protocol("missing verify controller identifier".into()))?;
        let signature = data
            .get(&tags::SIGNATURE)
            .ok_or_else(|| Error::Protocol("missing verify controller signature".into()))?;
        let message = [
            client_pub.as_slice(),
            id.as_slice(),
            self.verify_pub.as_bytes(),
        ]
        .concat();
        self.pairing.verify(id, &message, signature)?;
        self.cipher = Some(CompanionCipher::new(
            &hkdf_expand("", "ServerEncrypt-main", &shared),
            &hkdf_expand("", "ClientEncrypt-main", &shared),
            12,
        )?);
        self.send_opack(
            FrameType::PvNext,
            &Value::dict(vec![Value::kv(
                "_pd",
                Value::Bytes(tlv::write(&[(tags::SEQ_NO, &[0x04])])),
            )]),
        )
        .await?;
        info!("verification complete, encrypted control channel enabled");
        self.delegate.on_event(EventKind::Verified, "");
        self.inspector.emit("verified", "{}");

        // Kick connections that do not send _sessionStart within 3.0s
        *self.timeout_deadline.lock().await =
            Some(tokio::time::Instant::now() + std::time::Duration::from_millis(3000));

        Ok(())
    }

    async fn handle_control(&mut self, data: &Value) -> Result<()> {
        self.delegate
            .on_event(EventKind::ControlMessage, &data.to_string());
        // iOS 27 sends `_systemInfo` with the identifier in the `model` key
        // instead of `_i`; accept both so the `_systemInfo` arm below fires.
        let identifier = data
            .get("_i")
            .or_else(|| data.get("model"))
            .and_then(Value::as_str)
            .map(str::to_string);
        self.current_identifier = identifier.clone();
        let xid = data.get("_x").and_then(Value::as_int);
        let content = data.get("_c").cloned().unwrap_or(Value::dict(vec![]));

        match identifier.as_deref() {
            Some("_sessionStart") => {
                self.session_established.store(true, Ordering::SeqCst);
                *self.timeout_deadline.lock().await = None;
                self.inspector.session_started();
                let local_sid = content
                    .get("_sid")
                    .or_else(|| content.get("sid"))
                    .and_then(Value::as_int)
                    .unwrap_or_else(|| rand::random::<u32>() as u64);
                let remote_sid = self.remote_session_id;
                self.send_response(
                    xid,
                    Some(Value::dict(vec![Value::kv(
                        "_sid",
                        Value::Int(remote_sid as u64),
                    )])),
                    0,
                )
                .await?;
                info!("<< _sessionStart: session established (local_sid={local_sid}, remote_sid={remote_sid})");
                self.delegate.on_event(
                    EventKind::SessionStarted,
                    &format!(
                        "local={} remote={} combined={}",
                        local_sid,
                        remote_sid,
                        ((remote_sid as u64) << 32) | local_sid
                    ),
                );
                self.inspector.emit(
                    "session_started",
                    &format!("{{\"local_sid\":{local_sid},\"remote_sid\":{remote_sid}}}"),
                );
            }
            Some("TVRCSessionStart") => {
                self.session_established.store(true, Ordering::SeqCst);
                *self.timeout_deadline.lock().await = None;
                self.inspector.session_started();
                let version = content
                    .get("ProtocolVersionKey")
                    .and_then(Value::as_str)
                    .unwrap_or("1.2")
                    .to_string();
                self.send_response(
                    xid,
                    Some(Value::dict(vec![Value::kv(
                        "ProtocolVersionKey",
                        Value::str(&version),
                    )])),
                    0,
                )
                .await?;
                // Current iOS versions make the volume/mute decision
                // immediately after session startup, so publish the flag
                // before subscriptions have necessarily arrived.
                self.send_media_capabilities(xid).await?;
                info!("<< TVRCSessionStart: remote ready (version={version})");
                self.delegate.on_event(EventKind::RemoteReady, "");
                self.inspector
                    .emit("remote_ready", &format!("{{\"version\":\"{version}\"}}"));
            }
            Some("_sessionStop") => {
                self.session_established.store(false, Ordering::SeqCst);
                self.inspector.session_stopped();
                self.send_response(xid, None, 0).await?;
                info!("<< _sessionStop: remote session closed");
                self.delegate
                    .on_event(EventKind::SessionStopped, &content.to_string());
                self.inspector.emit("session_stopped", "{}");
            }
            Some("_hidC") => {
                self.session_established.store(true, Ordering::SeqCst);
                *self.timeout_deadline.lock().await = None;
                self.handle_hid_command(&content, xid).await?;
            }
            Some("_touchStart") => {
                self.session_established.store(true, Ordering::SeqCst);
                *self.timeout_deadline.lock().await = None;
                self.send_response(
                    xid,
                    Some(Value::dict(vec![Value::kv("_i", Value::Int(1))])),
                    0,
                )
                .await?;
            }
            Some("_hidT") => {
                self.session_established.store(true, Ordering::SeqCst);
                *self.timeout_deadline.lock().await = None;
                self.send_response(xid, None, 0).await?;
                self.handle_touch(&content);
            }
            Some("_touchStop") | Some("_touchMove") | Some("_tiStop") => {
                self.session_established.store(true, Ordering::SeqCst);
                *self.timeout_deadline.lock().await = None;
                self.send_response(xid, None, 0).await?;
            }
            Some("_interest") => {
                // _interest itself is an event and does not need a response.
                // A real Apple TV pushes the current state for each newly
                // registered event.
                if let Some(Value::Array(events)) = content.get("_regEvents") {
                    let names: Vec<String> = events
                        .iter()
                        .filter_map(|e| e.as_str().map(str::to_string))
                        .collect();
                    for event_name in names {
                        match event_name.as_str() {
                            "MediaControlStatus" => {
                                self.send_event(
                                    &event_name,
                                    xid,
                                    Some(Value::dict(vec![Value::kv(
                                        "MediaControlFlags",
                                        Value::Int(MEDIA_CONTROL_VOLUME),
                                    )])),
                                )
                                .await?;
                            }
                            "NowPlayingInfo" => {
                                self.send_event(&event_name, xid, None).await?;
                            }
                            "SystemStatus" | "TVSystemStatus" => {
                                self.send_event(
                                    &event_name,
                                    xid,
                                    Some(Value::dict(vec![Value::kv(
                                        "state",
                                        Value::Int(self.system_status),
                                    )])),
                                )
                                .await?;
                            }
                            _ => {}
                        }
                    }
                }
            }
            Some("FetchMediaControlStatus") => {
                self.send_response(
                    xid,
                    Some(Value::dict(vec![Value::kv(
                        "MediaControlFlags",
                        Value::Int(MEDIA_CONTROL_VOLUME),
                    )])),
                    0,
                )
                .await?;
            }
            Some("FetchSiriRemoteInfo") | Some("FetchCurrentNowPlayingInfoEvent") => {
                self.send_response(xid, None, 0).await?;
            }
            Some("MediaControlCommand") | Some("_mcc") => {
                self.handle_media_control(&content, xid).await?;
            }
            Some("_tiStart") => {
                self.send_response(
                    xid,
                    Some(Value::dict(vec![Value::kv("_tiE", Value::Bool(false))])),
                    0,
                )
                .await?;
            }
            Some("FetchLaunchableApplicationsEvent") => {
                self.send_response(
                    xid,
                    Some(Value::dict(vec![
                        Value::kv("com.apple.TVAppStore", Value::str("App Store")),
                        Value::kv("com.apple.TVSettings", Value::str("Settings")),
                        Value::kv("com.apple.TVMusic", Value::str("Music")),
                        Value::kv("com.apple.TVMovies", Value::str("Movies")),
                        Value::kv("com.apple.TVWatchList", Value::str("TV")),
                        Value::kv("com.google.ios.youtube", Value::str("YouTube")),
                        Value::kv("com.netflix.Netflix", Value::str("Netflix")),
                    ])),
                    2,
                )
                .await?;
            }
            Some("FetchAttentionState") => {
                self.send_response(
                    xid,
                    Some(Value::dict(vec![Value::kv(
                        "state",
                        Value::Int(self.system_status),
                    )])),
                    0,
                )
                .await?;
            }
            Some("_launchApp") => {
                let bundle_id = content
                    .get("_bundleID")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_string();
                self.delegate.on_event(EventKind::LaunchApp, &bundle_id);
                self.inspector
                    .emit("launch_app", &format!("{{\"bundle_id\":\"{bundle_id}\"}}"));
                self.send_response(xid, None, 0).await?;
            }
            Some("_systemInfo") | Some("SystemInfo") => {
                let ids_id = content.get("_idsID").and_then(Value::as_str);
                let pub_id = content.get("_pubID").and_then(Value::as_str);
                let name = content.get("name").and_then(Value::as_str).unwrap_or("");
                let model = content.get("model").and_then(Value::as_str).unwrap_or("");

                let primary_id = ids_id.or(pub_id).unwrap_or(&self.peer_ip);
                let alias_id = if ids_id.is_some() { pub_id } else { None };

                let (session_id, is_renewed) = {
                    let mut reg = self.registry.lock().await;
                    reg.get_or_register(primary_id, alias_id, name, model)
                };
                self.remote_session_id = session_id;
                self.device_id = Some(primary_id.to_string());

                {
                    let mut tracker = self.active_tracker.lock().await;
                    tracker.set_device_id(self.conn_id, primary_id);
                }

                self.send_response(xid, None, 0).await?;
                info!("<< _systemInfo: replied and refreshed power state (session_id={session_id}, renewed={is_renewed})");
                self.send_power_state(None).await?;

                // Background probes from sharingd/rapportd have high bit set in xid (>= 0x80000000)
                // and never send _sessionStart. Real remote control (tvremoted) sends _sessionStart
                // within 10-20ms. If probe, set a short 1.0s timeout to release the channel.
                let is_background_probe = xid.map_or(false, |x| (x & 0x8000_0000) != 0);
                let timeout = if is_background_probe {
                    std::time::Duration::from_millis(1000)
                } else {
                    std::time::Duration::from_millis(2500)
                };
                *self.timeout_deadline.lock().await = Some(tokio::time::Instant::now() + timeout);
            }
            _ => {
                if let Some(id) = &identifier {
                    debug!("<< unhandled control message: {id} content={content}");
                }
                if xid.is_some() {
                    self.send_response(xid, None, 0).await?;
                }
            }
        }
        Ok(())
    }

    async fn handle_hid_command(&mut self, content: &Value, xid: Option<u64>) -> Result<()> {
        let command = content.get("_hidC").and_then(Value::as_int).unwrap_or(0);
        let state = content.get("_hBtS").and_then(Value::as_int).unwrap_or(0);
        let mut command_name = hid_command_name(command);
        self.send_response(xid, None, 0).await?;
        if command_name == "select" {
            if state == 1 {
                self.select_pressed
                    .get_or_insert_with(tokio::time::Instant::now);
            }
            if state == 2
                && self.select_pressed.take().is_some_and(|pressed| {
                    pressed.elapsed() >= std::time::Duration::from_millis(500)
                })
            {
                command_name = "select_long".into();
            }
        }

        if state == 2 {
            match command_name.as_str() {
                "volume_up" | "volume_down" => {
                    self.delegate.on_button(&command_name);
                    if let Some((vol, muted)) = self.delegate.get_audio_state() {
                        self.volume = vol;
                        self.muted = muted;
                    } else {
                        let step = 1.0 / 16.0;
                        if command_name == "volume_up" {
                            self.volume = (self.volume + step).min(1.0);
                        } else {
                            self.volume = (self.volume - step).max(0.0);
                        }
                        self.muted = false;
                        self.delegate.on_audio(self.volume, self.muted);
                    }
                    self.inspector.emit(
                        "audio_state",
                        &format!("{{\"volume\":{:.3},\"muted\":{}}}", self.volume, self.muted),
                    );
                    self.push_media_state(xid).await?;
                }
                "mute" => {
                    self.delegate.on_button(&command_name);
                    if let Some((vol, muted)) = self.delegate.get_audio_state() {
                        self.volume = vol;
                        self.muted = muted;
                    } else {
                        self.muted = !self.muted;
                        self.delegate.on_audio(self.volume, self.muted);
                    }
                    self.inspector.emit(
                        "audio_state",
                        &format!("{{\"volume\":{:.3},\"muted\":{}}}", self.volume, self.muted),
                    );
                    self.send_event(
                        "MediaControlStatus",
                        xid,
                        Some(Value::dict(vec![
                            Value::kv("MediaControlFlags", Value::Int(MEDIA_CONTROL_VOLUME)),
                            Value::kv("Muted", Value::Bool(self.muted)),
                        ])),
                    )
                    .await?;
                    self.send_event(
                        "_iMC",
                        xid,
                        Some(Value::dict(vec![Value::kv(
                            "_mcF",
                            Value::Int(MEDIA_CONTROL_VOLUME),
                        )])),
                    )
                    .await?;
                }
                "power" => {
                    self.display_power_on = !self.display_power_on;
                    self.delegate.on_event(
                        EventKind::PowerState,
                        if self.display_power_on { "on" } else { "off" },
                    );
                    self.inspector.emit(
                        "power_state",
                        &format!("{{\"display_on\":{}}}", self.display_power_on),
                    );
                    self.delegate.on_button(&command_name);
                }
                _ => {
                    self.delegate.on_button(&command_name);
                }
            }
            self.inspector
                .emit("button", &format!("{{\"name\":\"{command_name}\"}}"));
        }
        Ok(())
    }

    async fn handle_media_control(&mut self, content: &Value, xid: Option<u64>) -> Result<()> {
        let command = content
            .get("MediaControlCommand")
            .or_else(|| content.get("_mcc"))
            .and_then(Value::as_int);
        let mut response = Value::dict(vec![]);
        match command {
            // GetVolume: report 0 while muted so the phone's volume HUD shows
            // an empty bar — the only visual mute feedback this protocol has.
            Some(5) => {
                if let Some((vol, muted)) = self.delegate.get_audio_state() {
                    self.volume = vol;
                    self.muted = muted;
                }
                response = Value::dict(vec![Value::kv(
                    "_vol",
                    Value::Double(if self.muted { 0.0 } else { self.volume }),
                )]);
            }
            // SetVolume
            Some(6) => {
                if let Some(requested) = content.get("_vol").and_then(Value::as_float) {
                    self.volume = requested.clamp(0.0, 1.0);
                    // Raising the level from the phone lifts the mute.
                    if self.volume > 0.0 {
                        self.muted = false;
                    }
                    self.delegate.on_audio(self.volume, self.muted);
                    self.inspector.emit(
                        "audio_state",
                        &format!("{{\"volume\":{:.3},\"muted\":{}}}", self.volume, self.muted),
                    );
                }
            }
            // GetCaptionSettings
            Some(12) => {
                response = Value::dict(vec![Value::kv("_cse", Value::Bool(false))]);
            }
            _ => {}
        }
        self.send_response(xid, Some(response), 0).await
    }

    /// Translate touchpad gestures into directional button presses (direction mode),
    /// pointer-style deltas (mouse mode), or trajectory preview only (idle mode).
    fn handle_touch(&mut self, content: &Value) {
        let phase = content.get("_tPh").and_then(Value::as_int).unwrap_or(0);
        let x = content.get("_cx").and_then(Value::as_float);
        let y = content.get("_cy").and_then(Value::as_float);

        let mode_u8 = self.trackpad_mode.load(Ordering::SeqCst);
        let mode = TrackpadMode::from_u8(mode_u8);

        if phase == 1 {
            // touch began
            if let (Some(x), Some(y)) = (x, y) {
                self.touch_origin = Some((x, y));
                self.touch_last = Some((x, y));
                self.touch_moved = false;
                if mode == TrackpadMode::Mouse {
                    self.delegate.on_touch(0.0, 0.0, TouchPhase::Began);
                }
                self.inspector
                    .emit("touch", "{\"phase\":1,\"dx\":0.0,\"dy\":0.0}");
            }
            return;
        }
        let (Some(origin), Some(x), Some(y)) = (self.touch_origin, x, y) else {
            return;
        };
        let position = (x, y);

        if phase != 4 {
            // intermediate move
            if let Some(last) = self.touch_last {
                let dx = position.0 - last.0;
                let dy = position.1 - last.1;
                if dx != 0.0 || dy != 0.0 {
                    if mode == TrackpadMode::Mouse {
                        self.touch_moved = true;
                        self.delegate.on_touch(dx, dy, TouchPhase::Moved);
                    }
                    self.inspector.emit(
                        "touch",
                        &format!("{{\"phase\":2,\"dx\":{:.1},\"dy\":{:.1}}}", dx, dy),
                    );
                }
            }
            self.touch_last = Some(position);
            return;
        }

        // phase == 4: finger lifted
        self.touch_origin = None;
        self.touch_last = None;
        let dx = position.0 - origin.0;
        let dy = position.1 - origin.1;
        let distance = dx.abs().max(dy.abs());

        if mode == TrackpadMode::Mouse {
            self.delegate.on_touch(dx, dy, TouchPhase::Ended);
        }
        self.inspector.emit(
            "touch",
            &format!("{{\"phase\":4,\"dx\":{:.1},\"dy\":{:.1}}}", dx, dy),
        );

        // In Idle mode: only emit touch trajectory for real-time preview, do not trigger any actions
        if mode == TrackpadMode::Idle {
            return;
        }

        if mode == TrackpadMode::Mouse {
            if distance < TAP_MAX_DISTANCE {
                self.delegate.on_event(EventKind::MouseClick, "");
                self.inspector.emit("button", "{\"name\":\"click\"}");
            }
            return;
        }

        if distance < TAP_MAX_DISTANCE {
            self.delegate.on_button("select");
            self.inspector.emit("button", "{\"name\":\"select\"}");
            return;
        }

        if distance < SWIPE_MIN_DISTANCE {
            return;
        }
        let direction = if dx.abs() > dy.abs() {
            if dx > 0.0 {
                "right"
            } else {
                "left"
            }
        } else if dy > 0.0 {
            "down"
        } else {
            "up"
        };
        self.delegate.on_button(direction);
        self.inspector
            .emit("button", &format!("{{\"name\":\"{direction}\"}}"));
    }
}

impl Drop for CompanionSession {
    fn drop(&mut self) {
        if self.session_established.load(Ordering::SeqCst) {
            self.inspector.session_stopped();
        }
    }
}

#[cfg(test)]
mod live_security_tests {
    use super::*;
    struct Delegate;
    impl AtvDelegate for Delegate {}
    async fn session() -> (CompanionSession, tokio::net::TcpStream) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = tokio::net::TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (server, _) = listener.accept().await.unwrap();
        let (_, writer) = server.into_split();
        let identity = DeviceIdentity::detect();
        let established = Arc::new(AtomicBool::new(false));
        (
            CompanionSession::new(
                Arc::new(Mutex::new(writer)),
                Arc::new(Delegate),
                &identity,
                1234,
                Arc::new(AtomicBool::new(false)),
                Arc::new(AtomicU8::new(0)),
                InspectorHub::new(),
                Arc::new(Mutex::new(DeviceRegistry::new(
                    std::time::Duration::from_secs(60),
                ))),
                Arc::new(Mutex::new(ActiveConnectionTracker::new())),
                1,
                "127.0.0.1".into(),
                established,
            ),
            client,
        )
    }
    async fn verify_request(
        session: &mut CompanionSession,
        id: &[u8],
        key: &SigningKey,
        tamper: bool,
    ) -> std::collections::BTreeMap<u8, Vec<u8>> {
        let client_private = StaticSecret::random_from_rng(OsRng);
        let client_public = X25519PublicKey::from(&client_private);
        session
            .pair_verify_m1(&[(tags::PUBLIC_KEY, client_public.as_bytes().to_vec())].into())
            .await
            .unwrap();
        assert!(session.cipher.is_none(), "M1 must never enable controls");
        let message = [
            client_public.as_bytes().as_slice(),
            id,
            session.verify_pub.as_bytes(),
        ]
        .concat();
        let mut signature = key.sign(&message).to_bytes();
        if tamper {
            signature[0] ^= 1;
        }
        let shared = client_private.diffie_hellman(&session.verify_pub);
        let encrypt_key = hkdf_expand(
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
            shared.as_bytes(),
        );
        let payload = tlv::write(&[(tags::IDENTIFIER, id), (tags::SIGNATURE, &signature)]);
        let encrypted = CompanionCipher::new(&encrypt_key, &encrypt_key, 8)
            .unwrap()
            .encrypt(&payload, None, Some(b"PV-Msg03"))
            .unwrap();
        [(tags::ENCRYPTED_DATA, encrypted)].into()
    }
    #[tokio::test]
    async fn verify_requires_paired_identity_and_valid_signature() {
        let (mut session, _client) = session().await;
        let key = SigningKey::from_bytes(&[9; 32]);
        let unknown = verify_request(&mut session, b"phone", &key, false).await;
        assert!(session.pair_verify_m3(&unknown).await.is_err());
        assert!(session.cipher.is_none());
        session.pairing.open_window(120);
        session
            .pairing
            .add(b"phone", key.verifying_key().to_bytes())
            .unwrap();
        session.pairing.close_window();
        let tampered = verify_request(&mut session, b"phone", &key, true).await;
        assert!(session.pair_verify_m3(&tampered).await.is_err());
        assert!(session.cipher.is_none());
        let valid = verify_request(&mut session, b"phone", &key, false).await;
        session.pair_verify_m3(&valid).await.unwrap();
        assert!(session.cipher.is_some());
        assert!(
            session.pair_verify_m3(&valid).await.is_err(),
            "replayed M3 must fail"
        );
    }
    #[tokio::test]
    async fn setup_cannot_skip_pin_proof_or_local_window() {
        let (mut session, _client) = session().await;
        assert!(session.pair_setup_m1().await.is_err());
        session.pairing.open_window(120);
        session.pair_setup_m1().await.unwrap();
        assert!(session.pair_setup_m5(&Default::default()).await.is_err());
    }

    // Independent SRP client math; matches srptools' Apple 3072-bit SHA512 variant.
    fn client_srp(server: &SrpServer, pin: u32) -> (Vec<u8>, Vec<u8>, Vec<u8>) {
        use crate::srp::{int_to_bytes, PRIME_3072_HEX};
        use num_bigint::BigUint;
        use sha2::{Digest, Sha512};
        let n = BigUint::parse_bytes(PRIME_3072_HEX.as_bytes(), 16).unwrap();
        let g = BigUint::from(5u8);
        let hash = |v: &[u8]| BigUint::from_bytes_be(&Sha512::digest(v));
        let pad = |v: &BigUint| {
            let bytes = int_to_bytes(v);
            [vec![0; 384 - bytes.len()], bytes].concat()
        };
        let a = BigUint::from_bytes_be(&[3; 32]);
        let public = g.modpow(&a, &n);
        let b = BigUint::from_bytes_be(&server.public_key());
        let k = hash(&[int_to_bytes(&n), pad(&g)].concat());
        let x = hash(
            &[
                server.salt(),
                &Sha512::digest(format!("Pair-Setup:{pin}").as_bytes()),
            ]
            .concat(),
        );
        let u = hash(&[pad(&public), pad(&b)].concat());
        let base = (&b + &n - ((&k * g.modpow(&x, &n)) % &n)) % &n;
        let shared = base.modpow(&(a + u * x), &n);
        let key = Sha512::digest(int_to_bytes(&shared)).to_vec();
        let xor = int_to_bytes(&(hash(&int_to_bytes(&n)) ^ hash(&int_to_bytes(&g))));
        let proof = Sha512::digest(
            [
                xor,
                Sha512::digest(b"Pair-Setup").to_vec(),
                server.salt().to_vec(),
                int_to_bytes(&public),
                int_to_bytes(&b),
                key.clone(),
            ]
            .concat(),
        )
        .to_vec();
        (int_to_bytes(&public), proof, key)
    }
    async fn auth_frame(
        session: &mut CompanionSession,
        frame: FrameType,
        pairs: &[(u8, &[u8])],
    ) -> Result<()> {
        let payload = opack::pack(&Value::dict(vec![Value::kv(
            "_pd",
            Value::Bytes(tlv::write(pairs)),
        )]));
        let mut header = vec![frame as u8];
        header.extend_from_slice(&(payload.len() as u32).to_be_bytes()[1..]);
        session.handle_frame(frame, &payload, &header).await
    }
    #[tokio::test]
    async fn complete_pair_setup_verify_and_authenticated_button() {
        struct Recorder(std::sync::Mutex<Vec<String>>);
        impl AtvDelegate for Recorder {
            fn on_button(&self, name: &str) {
                self.0.lock().unwrap().push(name.into());
            }
        }
        let (mut session, _client) = session().await;
        let recorder = Arc::new(Recorder(std::sync::Mutex::new(Vec::new())));
        session.delegate = recorder.clone();
        session.pairing.open_window(120);
        auth_frame(&mut session, FrameType::PsStart, &[(tags::SEQ_NO, &[1])])
            .await
            .unwrap();
        let (public, proof, shared) = client_srp(&session.srp, 1234);
        auth_frame(
            &mut session,
            FrameType::PsNext,
            &[
                (tags::SEQ_NO, &[3]),
                (tags::PUBLIC_KEY, &public),
                (tags::PROOF, &proof),
            ],
        )
        .await
        .unwrap();
        assert!(session.setup_proof_verified);
        assert_eq!(session.srp.session_key().unwrap(), shared);
        let signing = SigningKey::from_bytes(&[77; 32]);
        let id = b"test-iPhone";
        let signing_pub = signing.verifying_key().to_bytes();
        let signing_x = hkdf_expand(
            "Pair-Setup-Controller-Sign-Salt",
            "Pair-Setup-Controller-Sign-Info",
            &shared,
        );
        let signature = signing.sign(&[signing_x.as_slice(), id.as_slice(), &signing_pub].concat());
        let pair_key = hkdf_expand(
            "Pair-Setup-Encrypt-Salt",
            "Pair-Setup-Encrypt-Info",
            &shared,
        );
        let plaintext = tlv::write(&[
            (tags::IDENTIFIER, id),
            (tags::PUBLIC_KEY, &signing_pub),
            (tags::SIGNATURE, &signature.to_bytes()),
        ]);
        let encrypted = CompanionCipher::new(&pair_key, &pair_key, 8)
            .unwrap()
            .encrypt(&plaintext, None, Some(b"PS-Msg05"))
            .unwrap();
        auth_frame(
            &mut session,
            FrameType::PsNext,
            &[(tags::SEQ_NO, &[5]), (tags::ENCRYPTED_DATA, &encrypted)],
        )
        .await
        .unwrap();
        session.pairing.close_window(); // Paired devices reconnect even when new pairing is disabled.
        let client_secret = StaticSecret::random_from_rng(OsRng);
        let client_pub = X25519PublicKey::from(&client_secret);
        auth_frame(
            &mut session,
            FrameType::PvStart,
            &[
                (tags::SEQ_NO, &[1]),
                (tags::PUBLIC_KEY, client_pub.as_bytes()),
            ],
        )
        .await
        .unwrap();
        assert!(session.cipher.is_none());
        let shared = client_secret.diffie_hellman(&session.verify_pub);
        let verify_key = hkdf_expand(
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
            shared.as_bytes(),
        );
        let signature = signing.sign(
            &[
                client_pub.as_bytes().as_slice(),
                id.as_slice(),
                session.verify_pub.as_bytes(),
            ]
            .concat(),
        );
        let encrypted = CompanionCipher::new(&verify_key, &verify_key, 8)
            .unwrap()
            .encrypt(
                &tlv::write(&[
                    (tags::IDENTIFIER, id),
                    (tags::SIGNATURE, &signature.to_bytes()),
                ]),
                None,
                Some(b"PV-Msg03"),
            )
            .unwrap();
        auth_frame(
            &mut session,
            FrameType::PvNext,
            &[(tags::SEQ_NO, &[3]), (tags::ENCRYPTED_DATA, &encrypted)],
        )
        .await
        .unwrap();
        let mut client_cipher = CompanionCipher::new(
            &hkdf_expand("", "ClientEncrypt-main", shared.as_bytes()),
            &hkdf_expand("", "ServerEncrypt-main", shared.as_bytes()),
            12,
        )
        .unwrap();
        let message = Value::dict(vec![
            Value::kv("_i", Value::str("_hidC")),
            Value::kv(
                "_c",
                Value::dict(vec![
                    Value::kv("_hidC", Value::Int(1)),
                    Value::kv("_hBtS", Value::Int(2)),
                ]),
            ),
        ]);
        let plaintext = opack::pack(&message);
        let mut header = vec![FrameType::EOpack as u8];
        header.extend_from_slice(&((plaintext.len() + 16) as u32).to_be_bytes()[1..]);
        let encrypted = client_cipher
            .encrypt(&plaintext, Some(&header), None)
            .unwrap();
        session
            .handle_frame(FrameType::EOpack, &encrypted, &header)
            .await
            .unwrap();
        assert_eq!(*recorder.0.lock().unwrap(), vec!["up"]);
        // A held physical select key maps to a global Android long press, once on release.
        session
            .handle_hid_command(
                &Value::dict(vec![
                    Value::kv("_hidC", Value::Int(6)),
                    Value::kv("_hBtS", Value::Int(1)),
                ]),
                None,
            )
            .await
            .unwrap();
        session.select_pressed =
            Some(tokio::time::Instant::now() - std::time::Duration::from_millis(600));
        session
            .handle_hid_command(
                &Value::dict(vec![
                    Value::kv("_hidC", Value::Int(6)),
                    Value::kv("_hBtS", Value::Int(2)),
                ]),
                None,
            )
            .await
            .unwrap();
        assert_eq!(*recorder.0.lock().unwrap(), vec!["up", "select_long"]);
    }
    #[tokio::test]
    async fn low_order_x25519_key_is_rejected() {
        let (mut session, _client) = session().await;
        assert!(session
            .pair_verify_m1(&[(tags::PUBLIC_KEY, vec![0; 32])].into())
            .await
            .is_err());
        assert!(session.cipher.is_none());
    }
}
