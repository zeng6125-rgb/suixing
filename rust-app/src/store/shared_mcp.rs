use super::*;
use serde_json::{Map, Value};

pub(super) const MAX_BYTES: u64 = 1024 * 1024;
pub(super) type ExtraFields = BTreeMap<String, Map<String, Value>>;

pub(super) struct SharedMcp {
    pub(super) document: McpDocument,
    pub(super) unknown: ExtraFields,
}

#[derive(Deserialize, Serialize)]
struct ComposeServer {
    #[serde(flatten)]
    config: crate::mcp::ServerConfig,
    #[serde(default)]
    env: String,
    #[serde(default)]
    headers: String,
    #[serde(flatten)]
    unknown: Map<String, Value>,
}

pub(super) fn read(files: &Path) -> Result<Option<SharedMcp>, String> {
    optional_json(files, &files.join("mcp.json"), MAX_BYTES)?
        .map(decode)
        .transpose()
}

fn decode(rows: Vec<ComposeServer>) -> Result<SharedMcp, String> {
    let mut servers = Vec::with_capacity(rows.len());
    let mut secrets = BTreeMap::new();
    let mut unknown = BTreeMap::new();
    let take_ciphertext = |value: &mut String| {
        if value.starts_with("enc1:") {
            StoredSecret::preserve_ciphertext(std::mem::take(value)).map_err(|e| e.to_string())
        } else {
            Ok(StoredSecret::default())
        }
    };
    for mut row in rows {
        let config = &mut row.config;
        if config.id.is_empty() {
            config.id = crate::mcp::legacy_server_id(&config.name);
        }
        let secret = StoredMcpSecrets {
            env: take_ciphertext(&mut row.env)?,
            headers: take_ciphertext(&mut row.headers)?,
        };
        config.env = row.env;
        config.headers = row.headers;
        if secrets.insert(config.id.clone(), secret).is_some() {
            return Err("MCP 服务器身份重复".into());
        }
        unknown.insert(config.id.clone(), row.unknown);
        servers.push(row.config);
    }
    Ok(SharedMcp {
        document: McpDocument {
            version: VERSION,
            servers,
            secrets,
        },
        unknown,
    })
}

pub(super) fn write(
    files: &Path,
    servers: &[crate::mcp::ServerConfig],
    secrets: &mut BTreeMap<String, StoredMcpSecrets>,
    unknown: &ExtraFields,
) -> Result<(), CommitFailure> {
    let rows = encode(servers, secrets, unknown, &SecretBox)?;
    atomic_json(files, &files.join("mcp.json"), &rows, MAX_BYTES)
}

fn encode(
    servers: &[crate::mcp::ServerConfig],
    secrets: &mut BTreeMap<String, StoredMcpSecrets>,
    unknown: &ExtraFields,
    cipher: &dyn SecretCipher,
) -> Result<Vec<ComposeServer>, String> {
    servers
        .iter()
        .map(|server| {
            let secret = secrets.entry(server.id.clone()).or_default();
            // Existing legacy plaintext is usable in memory and encrypted on the next edit.
            if secret.env.is_empty() && !server.env.trim().is_empty() {
                secret.env = cipher.encrypt(&server.env).map_err(|e| e.to_string())?;
            }
            if secret.headers.is_empty() && !server.headers.trim().is_empty() {
                secret.headers = cipher.encrypt(&server.headers).map_err(|e| e.to_string())?;
            }
            Ok(ComposeServer {
                config: server.metadata(),
                env: secret.env.as_ciphertext().to_owned(),
                headers: secret.headers.as_ciphertext().to_owned(),
                unknown: unknown.get(&server.id).cloned().unwrap_or_default(),
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn compose_rows_keep_ciphertext_and_extra_fields_when_rust_edits_metadata() {
        let rows = serde_json::from_value(json!([{
            "name": "示例",
            "transport": "http",
            "url": "https://example.invalid/mcp",
            "headers": "enc1:retained-envelope",
            "future": {"mode": "keep"}
        }]))
        .unwrap();
        let mut shared = decode(rows).unwrap();
        let server = &mut shared.document.servers[0];
        assert!(server.enabled);
        server.name = "重命名".into();
        server.enabled = false;

        let rows = encode(
            &shared.document.servers,
            &mut shared.document.secrets,
            &shared.unknown,
            &SecretBox,
        )
        .unwrap();
        let wire = serde_json::to_value(rows).unwrap();
        assert_eq!(wire[0]["name"], "重命名");
        assert_eq!(wire[0]["enabled"], false);
        assert_eq!(wire[0]["headers"], "enc1:retained-envelope");
        assert_eq!(wire[0]["future"], json!({"mode": "keep"}));
    }

    #[test]
    fn compose_legacy_plaintext_is_readable_without_keystore() {
        let rows = serde_json::from_value(json!([{
            "name": "legacy",
            "command": "example-server",
            "env": "EXAMPLE=value"
        }]))
        .unwrap();
        let mut shared = decode(rows).unwrap();
        assert_eq!(shared.document.servers[0].transport, "stdio");
        assert_eq!(shared.document.servers[0].env, "EXAMPLE=value");
        assert!(
            encode(
                &shared.document.servers,
                &mut shared.document.secrets,
                &shared.unknown,
                &UnavailableCipher,
            )
            .is_err()
        );
    }

    struct UnavailableCipher;

    impl SecretCipher for UnavailableCipher {
        fn encrypt(&self, _: &str) -> Result<StoredSecret, crate::platform::secrets::SecretError> {
            Err(crate::platform::secrets::SecretError::Unavailable)
        }

        fn decrypt(
            &self,
            _: &StoredSecret,
        ) -> Result<String, crate::platform::secrets::SecretError> {
            Err(crate::platform::secrets::SecretError::Unavailable)
        }
    }
}
