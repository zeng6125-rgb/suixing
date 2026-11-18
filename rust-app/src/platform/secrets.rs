//! Durable ciphertext types and all-or-nothing secret preparation. Plaintext is
//! deliberately not serializable through these DTOs.
use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, fmt};

const MAX_SECRET_BYTES: usize = 64 * 1024;
const MAX_FIELDS: usize = 1024;
const MAX_TOTAL_BYTES: usize = 1024 * 1024;

#[derive(Clone, Default, PartialEq, Eq, Serialize)]
#[serde(transparent)]
pub struct StoredSecret(String);

impl<'de> Deserialize<'de> for StoredSecret {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        Self::preserve_ciphertext(String::deserialize(d)?).map_err(serde::de::Error::custom)
    }
}
impl fmt::Debug for StoredSecret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(if self.0.is_empty() {
            "StoredSecret(empty)"
        } else {
            "StoredSecret(encrypted)"
        })
    }
}
impl StoredSecret {
    /// Keep unreadable old envelopes opaque; authenticate only when consuming.
    pub fn preserve_ciphertext(value: String) -> Result<Self, SecretError> {
        if value.is_empty() || value.starts_with("enc1:") && value.len() <= MAX_SECRET_BYTES * 2 + 5
        {
            Ok(Self(value))
        } else {
            Err(SecretError::InvalidCiphertext)
        }
    }
    pub fn from_ciphertext(value: String) -> Result<Self, SecretError> {
        if value.is_empty() {
            return Ok(Self(value));
        }
        let payload = value
            .strip_prefix("enc1:")
            .ok_or(SecretError::InvalidCiphertext)?;
        if payload.len() < 40
            || payload.len() > MAX_SECRET_BYTES * 2
            || payload.len() % 4 != 0
            || !payload
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || [b'+', b'/', b'='].contains(&b))
        {
            return Err(SecretError::InvalidCiphertext);
        }
        Ok(Self(value))
    }
    pub fn as_ciphertext(&self) -> &str {
        &self.0
    }
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
}

#[derive(Clone)]
pub enum SecretEdit {
    Keep,
    Clear,
    Set(String),
}
impl fmt::Debug for SecretEdit {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Keep => "Keep",
            Self::Clear => "Clear",
            Self::Set(_) => "Set([redacted])",
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SecretError {
    Unavailable,
    InvalidCiphertext,
    KeyUnavailable,
    EncryptionFailed,
    DecryptionFailed,
    BudgetExceeded,
}
impl fmt::Display for SecretError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Unavailable => "安全存储尚未初始化",
            Self::InvalidCiphertext => "凭据密文格式无效，原配置保留",
            Self::KeyUnavailable => "安全密钥不可用，请重新填写凭据",
            Self::EncryptionFailed => "安全加密失败，配置未保存",
            Self::DecryptionFailed => "凭据无法解密，请重新填写；原密文保留",
            Self::BudgetExceeded => "凭据超过安全存储预算，配置未保存",
        })
    }
}
impl std::error::Error for SecretError {}

pub trait SecretCipher: Send + Sync {
    fn encrypt(&self, plain: &str) -> Result<StoredSecret, SecretError>;
    fn decrypt(&self, stored: &StoredSecret) -> Result<String, SecretError>;
}

/// Uses AndroidKeyStore via JNI. A host build always reports unavailable.
pub struct SecretBox;
impl SecretCipher for SecretBox {
    fn encrypt(&self, plain: &str) -> Result<StoredSecret, SecretError> {
        if plain.len() > MAX_SECRET_BYTES {
            return Err(SecretError::BudgetExceeded);
        }
        if plain.is_empty() {
            return Ok(StoredSecret::default());
        }
        // Set receives plaintext, including whitespace or an enc1: prefix.
        // Existing envelopes are preserved only through StoredSecret and Keep.
        #[cfg(target_os = "android")]
        {
            super::android::encrypt_secret(plain)
        }
        #[cfg(not(target_os = "android"))]
        {
            Err(SecretError::Unavailable)
        }
    }
    fn decrypt(&self, stored: &StoredSecret) -> Result<String, SecretError> {
        if stored.is_empty() {
            return Ok(String::new());
        }
        #[cfg(target_os = "android")]
        {
            super::android::decrypt_secret(stored)
        }
        #[cfg(not(target_os = "android"))]
        {
            Err(SecretError::Unavailable)
        }
    }
}

#[derive(Clone, Default, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct StoredSettingsSecrets {
    pub api_key: StoredSecret,
    pub provider_keys: BTreeMap<String, StoredSecret>,
    pub search_api_key: StoredSecret,
    pub asr_api_key: StoredSecret,
    pub request_headers: BTreeMap<String, StoredSecret>,
}
#[derive(Clone, Default, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct StoredMcpSecrets {
    pub env: StoredSecret,
    pub headers: StoredSecret,
}

#[derive(Clone, Debug)]
pub enum SettingsSecretTarget {
    ApiKey,
    ProviderApiKey(String),
    SearchApiKey,
    AsrApiKey,
    RequestHeader(String),
}

#[derive(Clone, Debug)]
pub struct SettingsSecretEdits {
    pub api_key: SecretEdit,
    pub provider_keys: BTreeMap<String, SecretEdit>,
    pub search_api_key: SecretEdit,
    pub asr_api_key: SecretEdit,
    pub request_headers: BTreeMap<String, SecretEdit>,
}
impl Default for SettingsSecretEdits {
    fn default() -> Self {
        Self {
            api_key: SecretEdit::Keep,
            provider_keys: BTreeMap::new(),
            search_api_key: SecretEdit::Keep,
            asr_api_key: SecretEdit::Keep,
            request_headers: BTreeMap::new(),
        }
    }
}

/// All fields are encrypted before returning a candidate. The caller must commit
/// this candidate atomically with its config and only then publish runtime changes.
pub fn prepare_fields(
    cipher: &dyn SecretCipher,
    previous: &BTreeMap<String, StoredSecret>,
    edits: &BTreeMap<String, SecretEdit>,
) -> Result<BTreeMap<String, StoredSecret>, SecretError> {
    if previous.len() > MAX_FIELDS || edits.len() > MAX_FIELDS {
        return Err(SecretError::BudgetExceeded);
    }
    let mut candidate = previous.clone();
    for (name, edit) in edits {
        if name.is_empty() || name.len() > 256 {
            return Err(SecretError::BudgetExceeded);
        }
        match edit {
            SecretEdit::Keep => {}
            SecretEdit::Clear => {
                candidate.remove(name);
            }
            SecretEdit::Set(value) => {
                let encrypted = cipher.encrypt(value)?;
                if encrypted.is_empty() {
                    candidate.remove(name);
                } else {
                    candidate.insert(name.clone(), encrypted);
                }
            }
        }
    }
    let bytes = candidate.iter().try_fold(0usize, |sum, (name, value)| {
        sum.checked_add(name.len())
            .and_then(|n| n.checked_add(value.0.len()))
            .ok_or(SecretError::BudgetExceeded)
    })?;
    if candidate.len() > MAX_FIELDS || bytes > MAX_TOTAL_BYTES {
        return Err(SecretError::BudgetExceeded);
    }
    Ok(candidate)
}

pub fn prepare_secret(
    cipher: &dyn SecretCipher,
    previous: &StoredSecret,
    edit: &SecretEdit,
) -> Result<StoredSecret, SecretError> {
    match edit {
        SecretEdit::Keep => Ok(previous.clone()),
        SecretEdit::Clear => Ok(StoredSecret::default()),
        SecretEdit::Set(value) => cipher.encrypt(value),
    }
}
