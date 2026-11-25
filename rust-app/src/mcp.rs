//! MCP configuration, lifecycle and frozen tool bindings. IO is owned by dedicated workers.
mod http;
mod manager;
mod protocol;
mod stdio;

pub use manager::{
    ManagerEvent, McpBridge, ServerState, Snapshot, Status, TestReport, ToolBinding,
};
pub(crate) use protocol::legacy_server_id;
pub use protocol::{ServerConfig, normalize_configs, validate_config};
