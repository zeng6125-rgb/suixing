//! Frozen app-UID launch plans. Resolution checks installed paths only; it
//! neither probes a process nor retries a command that may already have effects.
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
};
pub(crate) mod lease;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Backend {
    #[default]
    Auto,
    Sh,
    Bash,
    Busybox,
    Alpine,
    Debian,
}
impl Backend {
    pub fn parse(value: &str) -> Result<Self, String> {
        match value {
            "auto" => Ok(Self::Auto),
            "sh" => Ok(Self::Sh),
            "bash" => Ok(Self::Bash),
            "busybox" => Ok(Self::Busybox),
            "alpine" | "linux:alpine" => Ok(Self::Alpine),
            "debian" | "linux:debian" => Ok(Self::Debian),
            _ => Err("未知应用UID Shell环境".into()),
        }
    }
}
#[derive(Clone, Debug)]
pub struct FrozenPaths {
    pub native_library_dir: PathBuf,
    pub files_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub workspace: PathBuf,
    pub linux_directory: PathBuf,
    pub system_shell: PathBuf,
}
impl FrozenPaths {
    #[cfg(target_os = "android")]
    pub fn from_platform(
        paths: &crate::platform::android::PlatformPaths,
        workspace: PathBuf,
    ) -> Self {
        Self {
            native_library_dir: paths.native_library_dir.clone(),
            files_dir: paths.files_dir.clone(),
            cache_dir: paths.cache_dir.clone(),
            linux_directory: paths.files_dir.join("linux"),
            workspace,
            system_shell: PathBuf::from("/system/bin/sh"),
        }
    }
    pub fn validate(&self) -> Result<(), String> {
        for path in [
            &self.native_library_dir,
            &self.files_dir,
            &self.cache_dir,
            &self.workspace,
            &self.linux_directory,
            &self.system_shell,
        ] {
            if !path.is_absolute() || path.as_os_str().to_string_lossy().contains('\0') {
                return Err("Shell平台路径无效".into());
            }
        }
        Ok(())
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LaunchPlan {
    pub executable: PathBuf,
    pub arguments: Vec<String>,
    pub environment: BTreeMap<String, String>,
    pub cwd: PathBuf,
    pub backend: Backend,
    #[serde(skip)]
    pub(crate) root_use: Option<std::sync::Arc<lease::UseLease>>,
}
#[derive(Clone, Debug)]
pub struct ShellEnvironment {
    paths: FrozenPaths,
}
impl ShellEnvironment {
    pub fn new(paths: FrozenPaths) -> Result<Self, String> {
        paths.validate()?;
        Ok(Self { paths })
    }
    pub fn paths(&self) -> &FrozenPaths {
        &self.paths
    }
    pub fn interactive(&self, backend: Backend) -> Result<LaunchPlan, String> {
        let mut plan = self.resolve(backend, "exit 0")?;
        if plan.arguments.len() < 2 || plan.arguments[plan.arguments.len() - 2] != "-c" {
            return Err("交互Shell启动模板无效".into());
        }
        plan.arguments.truncate(plan.arguments.len() - 2);
        plan.arguments.push("-i".into());
        plan.environment
            .insert("TERM".into(), "xterm-256color".into());
        plan.environment.insert("PS1".into(), "$ ".into());
        plan.environment.insert(
            "SHELL".into(),
            if matches!(plan.backend, Backend::Alpine | Backend::Debian) {
                "/bin/sh".into()
            } else {
                plan.executable.to_string_lossy().into_owned()
            },
        );
        if matches!(plan.backend, Backend::Alpine | Backend::Debian) {
            for argument in &mut plan.arguments {
                if argument == "TERM=dumb" {
                    *argument = "TERM=xterm-256color".into();
                }
            }
            let shell = plan.arguments.len() - 2;
            plan.arguments.insert(shell, "PS1=$ ".into());
        }
        Ok(plan)
    }
    fn bundled(&self, name: &str) -> Result<PathBuf, String> {
        let path = self.paths.native_library_dir.join(name);
        let actual = executable(&path)?;
        let owning =
            fs::canonicalize(&self.paths.native_library_dir).map_err(|_| "随包二进制目录不可用")?;
        if !actual.starts_with(owning) {
            return Err("随包二进制越过平台目录".into());
        }
        Ok(actual)
    }
    pub fn available(&self) -> Vec<Backend> {
        let mut values = Vec::new();
        for backend in [
            Backend::Sh,
            Backend::Bash,
            Backend::Busybox,
            Backend::Alpine,
            Backend::Debian,
        ] {
            if self.resolve(backend, "exit 0").is_ok() {
                values.push(backend);
            }
        }
        values
    }
    pub fn resolve(&self, requested: Backend, command: &str) -> Result<LaunchPlan, String> {
        if command.trim().is_empty() || command.len() > 64 * 1024 || command.contains('\0') {
            return Err("Shell命令无效或超过64 KiB".into());
        }
        let cwd = fs::canonicalize(&self.paths.workspace).map_err(|_| "Shell工作目录不可用")?;
        if !cwd.is_dir() {
            return Err("Shell工作目录不是目录".into());
        }
        let temp = self.paths.cache_dir.join("shell-tmp");
        let mut environment = BTreeMap::from([
            ("HOME".into(), cwd.to_string_lossy().into_owned()),
            ("TMPDIR".into(), temp.to_string_lossy().into_owned()),
            (
                "PATH".into(),
                format!(
                    "{}:/system/bin:/system/xbin:/usr/bin:/bin",
                    self.paths.native_library_dir.display()
                ),
            ),
            ("TERM".into(), "dumb".into()),
            ("LANG".into(), "C.UTF-8".into()),
        ]);
        if let Ok(path) = self.bundled("libbusybox.so") {
            environment.insert("BB".into(), path.to_string_lossy().into_owned());
        }
        let backend = if requested == Backend::Auto {
            if self.bundled("libbash.so").is_ok() {
                Backend::Bash
            } else {
                Backend::Sh
            }
        } else {
            requested
        };
        let mut root_use = None;
        let (executable, arguments) = match backend {
            Backend::Sh => (
                executable(&self.paths.system_shell)?,
                vec!["-c".into(), command.into()],
            ),
            Backend::Bash => (
                self.bundled("libbash.so")?,
                vec![
                    "--noprofile".into(),
                    "--norc".into(),
                    "-c".into(),
                    command.into(),
                ],
            ),
            Backend::Busybox => (
                self.bundled("libbusybox.so")?,
                vec!["sh".into(), "-c".into(), command.into()],
            ),
            Backend::Alpine | Backend::Debian => {
                if cfg!(target_os = "android") && !cfg!(target_arch = "aarch64") {
                    return Err("固定Linux rootfs仅支持Android arm64 ABI".into());
                }
                let distro = if backend == Backend::Alpine {
                    "alpine"
                } else {
                    "debian"
                };
                let expected = if backend == Backend::Alpine {
                    "6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489"
                } else {
                    "3a841a794ae5999b33e33b329582ed0379d4f54ca62c6ce5a8eb9cff5ef8900b"
                };
                let directory = self.paths.linux_directory.join(distro);
                let logical = fs::canonicalize(&self.paths.linux_directory)
                    .map_err(|_| "Linux目录不可用")?
                    .join(distro);
                root_use = Some(lease::read(logical)?);
                let ready = fs::symlink_metadata(directory.join("ready"))
                    .map_err(|_| "Linux环境未安装并检查")?;
                if !ready.is_file()
                    || ready.file_type().is_symlink()
                    || ready.len() > 128
                    || fs::read_to_string(directory.join("ready"))
                        .map_err(|_| "Linux安装标记不可读")?
                        .trim()
                        != expected
                {
                    return Err("Linux环境未通过安装标记核对".into());
                }
                let root =
                    fs::canonicalize(directory.join("rootfs")).map_err(|_| "Linux rootfs不可用")?;
                let owner = fs::canonicalize(&directory).map_err(|_| "Linux目录不可用")?;
                if !root.is_dir()
                    || !root.starts_with(&owner)
                    || !root.join("usr/bin/env").is_file()
                    || !root.join("bin/sh").is_file()
                {
                    return Err("Linux rootfs或必要程序不完整".into());
                }
                let loader = self.bundled("libproot-loader.so")?;
                environment.insert("PROOT_LOADER".into(), loader.to_string_lossy().into_owned());
                if let Ok(loader32) = self.bundled("libproot-loader32.so") {
                    environment.insert(
                        "PROOT_LOADER_32".into(),
                        loader32.to_string_lossy().into_owned(),
                    );
                }
                environment.insert("PROOT_TMP_DIR".into(), temp.to_string_lossy().into_owned());
                environment.insert(
                    "LD_LIBRARY_PATH".into(),
                    self.paths.native_library_dir.to_string_lossy().into_owned(),
                );
                (
                    self.bundled("libproot.so")?,
                    vec![
                        "--link2symlink".into(),
                        "--kill-on-exit".into(),
                        "--sysvipc".into(),
                        "-0".into(),
                        "-r".into(),
                        root.to_string_lossy().into_owned(),
                        "-b".into(),
                        "/dev".into(),
                        "-b".into(),
                        "/proc".into(),
                        "-b".into(),
                        "/sys".into(),
                        "-b".into(),
                        format!("{}:/workspace", cwd.display()),
                        "-b".into(),
                        format!("{}:/tmp", temp.display()),
                        "-w".into(),
                        "/workspace".into(),
                        "/usr/bin/env".into(),
                        "-i".into(),
                        "HOME=/root".into(),
                        "USER=root".into(),
                        "LOGNAME=root".into(),
                        "TERM=dumb".into(),
                        "LANG=C.UTF-8".into(),
                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin".into(),
                        "TMPDIR=/tmp".into(),
                        "/bin/sh".into(),
                        "-c".into(),
                        command.into(),
                    ],
                )
            }
            Backend::Auto => return Err("Shell自动解析未完成".into()),
        };
        Ok(LaunchPlan {
            executable,
            arguments,
            environment,
            cwd,
            backend,
            root_use,
        })
    }
    pub fn prepare_directories(&self) -> Result<(), String> {
        fs::create_dir_all(self.paths.cache_dir.join("shell-tmp"))
            .map_err(|_| "无法准备Shell临时目录".into())
    }
}
fn executable(path: &Path) -> Result<PathBuf, String> {
    let actual = fs::canonicalize(path).map_err(|_| "所选Shell或随包程序未安装")?;
    let metadata = fs::metadata(&actual).map_err(|_| "无法核对Shell程序")?;
    if !metadata.is_file() {
        return Err("Shell程序不是普通文件".into());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o111 == 0 {
            return Err("所选Shell程序不可执行".into());
        }
    }
    Ok(actual)
}
