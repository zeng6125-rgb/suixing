//! Optional verified rootfs data; install/recovery policy never replays a prior
//! process or resumes an unowned partially executed operation after a crash.
use crate::{
    platform::permissions::OperationKey,
    shell_environment::{Backend, ShellEnvironment},
};
use serde::{Deserialize, Serialize};
use std::{
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
};
mod archive;
mod manager;
mod process;
pub use manager::Manager;
#[derive(Clone, Copy, Debug, Serialize, Deserialize, Eq, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Distro {
    Alpine,
    Debian,
}
impl Distro {
    pub fn id(self) -> &'static str {
        match self {
            Self::Alpine => "alpine",
            Self::Debian => "debian",
        }
    }
    pub fn backend(self) -> Backend {
        match self {
            Self::Alpine => Backend::Alpine,
            Self::Debian => Backend::Debian,
        }
    }
    pub fn spec(self) -> Spec {
        match self {
            Self::Alpine => Spec {
                version: "3.22.2",
                url: "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.2-aarch64.tar.gz",
                sha256: "6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489",
                download_bytes: 3_981_043,
                installed_bytes: 64 * 1024 * 1024,
                prefix: "",
                xz: false,
            },
            Self::Debian => Spec {
                version: "12 Bookworm",
                url: "https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-aarch64-pd-v4.17.3.tar.xz",
                sha256: "3a841a794ae5999b33e33b329582ed0379d4f54ca62c6ce5a8eb9cff5ef8900b",
                download_bytes: 42_912_980,
                installed_bytes: 384 * 1024 * 1024,
                prefix: "debian-bookworm-aarch64",
                xz: true,
            },
        }
    }
    pub fn tools_command(self) -> &'static str {
        match self {
            Self::Alpine => "apk update && apk add python3 py3-pip nodejs npm git ca-certificates",
            Self::Debian => {
                "apt-get update && apt-get install -y python3 python3-pip nodejs npm git ca-certificates"
            }
        }
    }
}
pub struct Spec {
    pub version: &'static str,
    pub url: &'static str,
    pub sha256: &'static str,
    pub download_bytes: u64,
    pub installed_bytes: u64,
    pub prefix: &'static str,
    pub xz: bool,
}
pub struct Config {
    pub environment: ShellEnvironment,
    pub operations_directory: PathBuf,
    pub require_foreground: bool,
}
impl Config {
    pub fn new(environment: ShellEnvironment, operations_directory: PathBuf) -> Self {
        Self {
            environment,
            operations_directory,
            require_foreground: cfg!(target_os = "android"),
        }
    }
}
#[derive(Clone, Debug, Eq, PartialEq, Hash)]
pub struct Key {
    pub process_token: String,
    pub id: String,
    pub generation: u64,
}
impl Key {
    pub fn validate(&self) -> Result<(), String> {
        if self.process_token.is_empty()
            || self.process_token.len() > 128
            || !self
                .process_token
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
        {
            return Err("Linux进程身份无效".into());
        }
        OperationKey {
            id: self.id.clone(),
            conversation_id: None,
            generation: self.generation,
        }
        .validate()
    }
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ServiceRow {
    pub key: Key,
    pub phase: Phase,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Snapshot {
    pub revision: u64,
    pub process_token: String,
    pub running: Vec<ServiceRow>,
    pub alive: bool,
}
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub enum Protection {
    #[default]
    Unavailable,
    Requested,
    Confirmed,
    Failed(String),
}
pub use manager::Subscription;
#[derive(Clone, Copy, Debug, Serialize, Deserialize, Eq, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    Claimed,
    Downloading,
    Verifying,
    Extracting,
    Checking,
    Publishing,
    Ready,
    Removing,
    Removed,
    Failed,
    Cancelled,
    Interrupted,
}
impl Phase {
    pub fn active(self) -> bool {
        matches!(
            self,
            Self::Claimed
                | Self::Downloading
                | Self::Verifying
                | Self::Extracting
                | Self::Checking
                | Self::Publishing
                | Self::Removing
        )
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Record {
    pub operation: OperationKeyData,
    pub distro: Distro,
    pub phase: Phase,
    pub bytes: u64,
    pub available: bool,
    pub error: Option<String>,
    pub published: bool,
    pub revision: u64,
    pub created_at: i64,
    pub cancel_requested: bool,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct OperationKeyData {
    pub id: String,
    pub conversation_id: Option<String>,
    pub generation: u64,
}
impl From<OperationKey> for OperationKeyData {
    fn from(key: OperationKey) -> Self {
        Self {
            id: key.id,
            conversation_id: key.conversation_id,
            generation: key.generation,
        }
    }
}
impl OperationKeyData {
    pub fn key(&self) -> OperationKey {
        OperationKey {
            id: self.id.clone(),
            conversation_id: self.conversation_id.clone(),
            generation: self.generation,
        }
    }
}
#[derive(Clone, Debug)]
pub struct Status {
    pub distro: Distro,
    pub available: bool,
    pub busy: bool,
    pub note: Option<String>,
}
#[derive(Clone, Debug)]
pub enum Event {
    Progress(Record),
    Finished(Record),
    Recovery {
        distro: Distro,
        restored: bool,
        error: Option<String>,
    },
}
pub(crate) struct Control {
    pub cancelled: AtomicBool,
}
impl Control {
    pub fn check(&self) -> Result<(), String> {
        if self.cancelled.load(Ordering::Acquire) {
            Err("Linux操作已请求取消".into())
        } else {
            Ok(())
        }
    }
}
pub(crate) fn shared_control() -> Arc<Control> {
    Arc::new(Control {
        cancelled: AtomicBool::new(false),
    })
}
