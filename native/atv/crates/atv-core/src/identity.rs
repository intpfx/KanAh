//! Live supplies public identifiers from application preferences and private keys from PairingStore.
//! Fallbacks are process-local; no machine UUID, hardware MAC, or home-directory files are read.
use crate::{pairing::PairingStore, server::AtvConfig};
use sha2::{Digest, Sha256};
use std::sync::Arc;
use uuid::Uuid;

pub const DEFAULT_FALLBACK_NAME: &str = "Live TV";
#[derive(Clone)]
pub struct DeviceIdentity {
    pub name: String,
    pub device_id: String,
    pub mac_bytes: [u8; 6],
    pub server_identifier: String,
    pub unique_id: String,
    pub private_key: [u8; 32],
    pub pairing: Arc<PairingStore>,
}
impl std::fmt::Debug for DeviceIdentity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DeviceIdentity")
            .field("name", &self.name)
            .field("device_id", &self.device_id)
            .field("server_identifier", &self.server_identifier)
            .finish_non_exhaustive()
    }
}
impl DeviceIdentity {
    pub fn detect() -> Self {
        let server_identifier = Uuid::new_v4().to_string().to_uppercase();
        let (device_id, mac_bytes) = detect_mac_address(&server_identifier);
        let pairing = Arc::new(PairingStore::ephemeral());
        Self {
            name: detect_computer_name(),
            unique_id: server_identifier.replace('-', ""),
            server_identifier,
            device_id,
            mac_bytes,
            private_key: pairing.seed(),
            pairing,
        }
    }
    pub fn from_config(config: &AtvConfig) -> Self {
        let fallback = Self::detect();
        let name = if config.name.trim().is_empty() {
            fallback.name
        } else {
            config.name.clone()
        };
        let server_identifier = config
            .server_identifier
            .as_ref()
            .filter(|id| !id.trim().is_empty())
            .map(|id| id.trim().to_uppercase())
            .unwrap_or(fallback.server_identifier);
        let (device_id, mac_bytes) = match config
            .device_id
            .as_ref()
            .and_then(|id| parse_mac_address(id))
        {
            Some(bytes) => (format_mac(&bytes), bytes),
            None => detect_mac_address(&server_identifier),
        };
        let pairing = config.pairing.clone().unwrap_or(fallback.pairing);
        Self {
            name,
            device_id,
            mac_bytes,
            unique_id: server_identifier.replace('-', ""),
            server_identifier,
            private_key: config.private_key.unwrap_or_else(|| pairing.seed()),
            pairing,
        }
    }
}
pub fn detect_computer_name() -> String {
    DEFAULT_FALLBACK_NAME.into()
}
pub fn detect_mac_address(server_uuid: &str) -> (String, [u8; 6]) {
    let hash = Sha256::digest([b"atv-mac-seed-v1:".as_slice(), server_uuid.as_bytes()].concat());
    let mut bytes = [0; 6];
    bytes.copy_from_slice(&hash[..6]);
    bytes[0] = (bytes[0] | 2) & 0xfe;
    (format_mac(&bytes), bytes)
}
pub fn parse_mac_address(input: &str) -> Option<[u8; 6]> {
    let clean = input.replace([':', '-'], "");
    // Reject non-ASCII before indexing byte offsets.
    if clean.len() != 12 || !clean.is_ascii() {
        return None;
    }
    let mut bytes = [0; 6];
    for (i, byte) in bytes.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&clean[i * 2..i * 2 + 2], 16).ok()?;
    }
    Some(bytes)
}
pub fn format_mac(bytes: &[u8; 6]) -> String {
    bytes
        .iter()
        .map(|b| format!("{b:02X}"))
        .collect::<Vec<_>>()
        .join(":")
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn mac_formats_are_supported_and_invalid_unicode_is_rejected() {
        let mac = [0x84, 0x2f, 0x57, 0x2e, 0x6c, 0xee];
        for text in ["84:2f:57:2e:6c:ee", "84-2F-57-2E-6C-EE", "842F572E6CEE"] {
            assert_eq!(parse_mac_address(text), Some(mac));
        }
        assert_eq!(format_mac(&mac), "84:2F:57:2E:6C:EE");
        assert!(parse_mac_address("1234567890é").is_none());
    }
    #[test]
    fn public_identifiers_do_not_determine_private_keys() {
        let first = DeviceIdentity::detect();
        let second = DeviceIdentity::detect();
        assert_ne!(first.private_key, second.private_key);
        assert_eq!(first.server_identifier.len(), 36);
        assert_eq!(first.unique_id.len(), 32);
    }
}
