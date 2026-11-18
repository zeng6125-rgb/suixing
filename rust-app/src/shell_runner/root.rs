use super::{FrozenRequest, Outcome, PrivilegedExecutor, Route, process};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, Metadata},
    path::{Path, PathBuf},
    sync::{Arc, atomic::AtomicBool},
};
/// Root's capability owner freezes the already selected trusted su binary and
/// generation before approval. Constructing this adapter executes no probe.
pub struct RootSuExecutor {
    su: PathBuf,
    busybox: PathBuf,
    generation: u64,
    su_metadata: Metadata,
    busybox_metadata: Metadata,
}
impl RootSuExecutor {
    pub fn freeze(su: PathBuf, busybox: PathBuf, generation: u64) -> Result<Self, String> {
        if generation == 0 || !su.is_absolute() || !busybox.is_absolute() {
            return Err("Root后端缺少冻结路径或代次".into());
        }
        let su = fs::canonicalize(su).map_err(|_| "Root su可执行程序不可用")?;
        let busybox = fs::canonicalize(busybox).map_err(|_| "Root超时与环境工具不可用")?;
        let su_metadata = fs::metadata(&su).map_err(|_| "Root su信息不可读")?;
        let busybox_metadata = fs::metadata(&busybox).map_err(|_| "Root随包工具信息不可读")?;
        if !su_metadata.is_file() || !busybox_metadata.is_file() {
            return Err("Root启动程序不是普通文件".into());
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::{MetadataExt, PermissionsExt};
            if su_metadata.uid() != 0
                || su_metadata.permissions().mode() & 0o022 != 0
                || su_metadata.permissions().mode() & 0o111 == 0
            {
                return Err("Root su不是已核对的系统所有者可执行程序".into());
            }
            if busybox_metadata.permissions().mode() & 0o111 == 0 {
                return Err("Root随包工具不可执行".into());
            }
        }
        #[cfg(not(unix))]
        return Err("当前平台没有Root su执行后端".into());
        #[cfg(unix)]
        Ok(Self {
            su,
            busybox,
            generation,
            su_metadata,
            busybox_metadata,
        })
    }
}
fn quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}
fn same(path: &Path, expected: &Metadata) -> bool {
    fs::metadata(path).ok().is_some_and(|actual| {
        let basic = actual.is_file()
            && actual.len() == expected.len()
            && actual.modified().ok() == expected.modified().ok();
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            basic
                && actual.dev() == expected.dev()
                && actual.ino() == expected.ino()
                && actual.uid() == expected.uid()
                && actual.mode() == expected.mode()
                && actual.ctime() == expected.ctime()
                && actual.ctime_nsec() == expected.ctime_nsec()
        }
        #[cfg(not(unix))]
        {
            basic
        }
    })
}
impl PrivilegedExecutor for RootSuExecutor {
    fn execute(&self, request: &FrozenRequest, cancel: Arc<AtomicBool>) -> Outcome {
        if request.route
            != (Route::Root {
                generation: self.generation,
            })
        {
            return Outcome::not_started(request.route, "Root执行器代次与审批冻结代次不一致");
        }
        if let Err(error) = request.validate() {
            return Outcome::not_started(request.route.clone(), error);
        }
        if !same(&self.su, &self.su_metadata) || !same(&self.busybox, &self.busybox_metadata) {
            return Outcome::not_started(
                request.route.clone(),
                "冻结Root启动程序已变化，命令未启动",
            );
        }
        let marker = format!(
            "__JAGENT_ROOT_{}__\n",
            format!("{:x}", Sha256::digest(request.request_key.as_bytes()))
        );
        let mut inner = format!(
            "jagent_uid=$(/system/bin/id -u) || exit 125; [ \"$jagent_uid\" = 0 ] || exit 125; printf %s {}; cd {} || exit 125; exec {} env -i",
            quote(&marker),
            quote(&request.cwd.to_string_lossy()),
            quote(&self.busybox.to_string_lossy())
        );
        for environment in &request.environment {
            inner.push(' ');
            inner.push_str(&quote(environment));
        }
        for argument in &request.argv {
            inner.push(' ');
            inner.push_str(&quote(argument));
        }
        let seconds = request.timeout_ms.div_ceil(1000);
        let command = format!(
            "exec {} timeout -s KILL {} /system/bin/sh -c {}",
            quote(&self.busybox.to_string_lossy()),
            seconds,
            quote(&inner)
        );
        let launch = FrozenRequest {
            request_key: request.request_key.clone(),
            argv: vec![self.su.to_string_lossy().into_owned(), "-c".into(), command],
            environment: vec!["PATH=/system/bin:/system/xbin".into(), "TERM=dumb".into()],
            cwd: PathBuf::from("/"),
            route: request.route.clone(),
            timeout_ms: request.timeout_ms.saturating_add(2000).min(600_000),
            max_output_bytes: request
                .max_output_bytes
                .saturating_add(marker.len())
                .min(16 * 1024 * 1024),
            observe_paths: Vec::new(),
            root_use: request.root_use.clone(),
        };
        let mut outcome = process::execute(&launch, cancel);
        if let Some(output) = outcome.stdout.strip_prefix(&marker) {
            outcome.stdout = output.to_owned();
            outcome.stdout_bytes = outcome.stdout_bytes.saturating_sub(marker.len() as u64);
            outcome.identity_confirmed = true;
        } else {
            outcome.identity_confirmed = false;
            outcome.error.get_or_insert(
                "Root UID0执行确认未收到；不把su启动请求或提示当作命令执行成功".into(),
            );
        }
        process::bound_output(&mut outcome, request.max_output_bytes);
        outcome.route = request.route;
        outcome.group_cleanup_confirmed = false;
        if outcome.cancel_requested && !outcome.primary_exit_confirmed {
            outcome.error.get_or_insert(
                "Root委托进程取消未确认；Root端超时包装仍负责终止，未重放命令".into(),
            );
        }
        outcome
    }
}
