//! The fixed execution tier is a ceiling. Resolve before approval, freeze the
//! result, and never resolve a replacement route when the effect runs.
use crate::{
    platform::{
        self,
        root_capability::{Phase, RootAuthority, Status as RootStatus},
    },
    platform_tools::{self, Context, RoutePolicy, ScreenshotRepository},
    settings_policy::{ExecutionSettings, Preference},
    shell_environment::ShellEnvironment,
    shell_runner::{self, Ceiling, FrozenRequest, Grants, Outcome, PrivilegedExecutor, Route},
    state::ToolCall,
};
use std::{
    path::PathBuf,
    sync::{Arc, RwLock, atomic::AtomicBool},
};
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Requested {
    Automatic,
    AppUid,
    Shizuku,
    Root,
}
impl Requested {
    pub fn parse(value: &str) -> Result<Self, String> {
        match value.trim().to_ascii_lowercase().as_str() {
            "auto" => Ok(Self::Automatic),
            "local" => Ok(Self::AppUid),
            "shizuku" => Ok(Self::Shizuku),
            "root" => Ok(Self::Root),
            _ => Err("未知执行路线请求".into()),
        }
    }
}
#[derive(Clone, Debug)]
pub struct Snapshot {
    pub revision: u64,
    pub preference: Preference,
    pub android: bool,
    pub accessibility: platform::automation::Capabilities,
    pub shizuku: platform::shizuku::Status,
    pub root: RootStatus,
    pub selected_shell: Route,
}
struct Policy {
    revision: u64,
    settings: ExecutionSettings,
}
#[derive(Clone)]
pub struct RoutingOwner {
    policy: Arc<RwLock<Policy>>,
    root: RootAuthority,
    environment: ShellEnvironment,
    screenshots: Option<Arc<dyn ScreenshotRepository>>,
}
pub struct PreparedShell {
    pub request: FrozenRequest,
    pub revision: u64,
    pub original_call_id: String,
}
impl RoutingOwner {
    pub fn new(
        settings: ExecutionSettings,
        root: RootAuthority,
        environment: ShellEnvironment,
        screenshots: Option<Arc<dyn ScreenshotRepository>>,
    ) -> Result<Self, String> {
        settings.validate()?;
        Ok(Self {
            policy: Arc::new(RwLock::new(Policy {
                revision: 1,
                settings,
            })),
            root,
            environment,
            screenshots,
        })
    }
    pub fn root(&self) -> &RootAuthority {
        &self.root
    }
    pub fn environment(&self) -> &ShellEnvironment {
        &self.environment
    }
    /// Actor calls only after successful Store publication. Pure snapshot reads
    /// never open Store, prompt su, probe a process, or make Binder transactions.
    pub fn publish_saved(&self, settings: ExecutionSettings) -> Result<u64, String> {
        settings.validate()?;
        let mut policy = self.policy.write().unwrap_or_else(|e| e.into_inner());
        if policy.settings.preferred_capability != settings.preferred_capability
            || policy.settings.always_allowed_tools != settings.always_allowed_tools
            || policy.settings.always_denied_tools != settings.always_denied_tools
        {
            policy.revision = policy.revision.checked_add(1).ok_or("执行策略版本用尽")?;
        }
        policy.settings = settings;
        self.root.publish_remembered(
            policy.settings.root_available,
            policy.settings.root_checked_at,
        );
        Ok(policy.revision)
    }
    pub fn saved_settings(&self) -> ExecutionSettings {
        self.policy
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .settings
            .clone()
    }
    pub fn snapshot(&self) -> Snapshot {
        let policy = self.policy.read().unwrap_or_else(|e| e.into_inner());
        let root = self.root.status();
        let shizuku = platform::shizuku::status();
        let preference = policy.settings.preference();
        let selected_shell = select(preference, Requested::Automatic, &root, &shizuku);
        Snapshot {
            revision: policy.revision,
            preference,
            android: cfg!(target_os = "android"),
            accessibility: platform::automation::capabilities(),
            root,
            shizuku,
            selected_shell,
        }
    }
    pub fn grants(&self) -> Grants {
        let snapshot = self.snapshot();
        Grants {
            root: root_generation(&snapshot.root),
            shizuku: shizuku_generation(&snapshot.shizuku),
            ceiling: ceiling(snapshot.preference),
        }
    }
    pub fn context(&self) -> Arc<Context> {
        let snapshot = self.snapshot();
        let root_generation = root_generation(&snapshot.root);
        let root_executor = root_generation.and_then(|generation| self.root.executor(generation));
        let privileged: Arc<dyn PrivilegedExecutor> = Arc::new(FrozenExecutor {
            owner: self.clone(),
            revision: snapshot.revision,
            root_generation,
            root_executor,
            shizuku_generation: shizuku_generation(&snapshot.shizuku),
        });
        // Root and Shizuku preferences constrain shell routes. Native UiTools
        // still prepare accessibility first; a rejected effect never falls back.
        Arc::new(Context {
            route_policy: match snapshot.preference {
                Preference::AppUid => RoutePolicy::AppUid,
                Preference::Accessibility => RoutePolicy::Accessibility,
                _ => RoutePolicy::Auto,
            },
            shizuku: snapshot.shizuku,
            root_generation,
            ceiling: ceiling(snapshot.preference),
            privileged_cwd: Some(PathBuf::from("/data/local/tmp")),
            privileged: Some(privileged),
            screenshots: self.screenshots.clone(),
        })
    }
    pub fn prepare_shell(
        &self,
        call: &ToolCall,
        operation_id: String,
    ) -> Result<PreparedShell, String> {
        if call.arguments.len() > 128 * 1024 {
            return Err("Shell参数超过128KiB".into());
        }
        let snapshot = self.snapshot();
        let mut arguments: serde_json::Value =
            serde_json::from_str(&call.arguments).map_err(|_| "Shell参数不是JSON对象")?;
        let object = arguments.as_object_mut().ok_or("Shell参数不是JSON对象")?;
        let requested = match object.get("mode") {
            None => Requested::Automatic,
            Some(serde_json::Value::String(value)) => Requested::parse(value)?,
            _ => return Err("Shell执行路线必须为字符串".into()),
        };
        let guest = if call.name == "bash" {
            false
        } else {
            let backend = match object.get("shell") {
                None => crate::shell_environment::Backend::Auto,
                Some(serde_json::Value::String(value)) => {
                    crate::shell_environment::Backend::parse(&value.trim().to_ascii_lowercase())?
                }
                _ => return Err("Shell选择必须为字符串".into()),
            };
            matches!(
                backend,
                crate::shell_environment::Backend::Alpine
                    | crate::shell_environment::Backend::Debian
            )
        };
        if guest && matches!(requested, Requested::Root | Requested::Shizuku) {
            return Err("Linux guest root不能使用Android Root或Shizuku身份".into());
        }
        let selected = if call.name == "bash" || guest {
            Route::AppUid
        } else {
            select(
                snapshot.preference,
                requested,
                &snapshot.root,
                &snapshot.shizuku,
            )
        };
        // This clamp is preparation only, matching Kotlin shellChannel. The
        // original command and original ToolCall remain the approval source.
        if call.name != "bash" {
            object.insert(
                "mode".into(),
                serde_json::Value::String(
                    match selected {
                        Route::AppUid => "local",
                        Route::Shizuku { .. } => "shizuku",
                        Route::Root { .. } => "root",
                    }
                    .into(),
                ),
            );
        }
        let effective = ToolCall {
            id: operation_id.clone(),
            name: call.name.clone(),
            arguments: arguments.to_string(),
        };
        let grants = Grants {
            root: root_generation(&snapshot.root),
            shizuku: shizuku_generation(&snapshot.shizuku),
            ceiling: ceiling(snapshot.preference),
        };
        let request = crate::shell_compatibility::prepare_tool(
            &effective,
            &self.environment,
            &grants,
            operation_id,
        )?;
        if request.route != selected {
            return Err("Shell准备过程中执行代次已改变，未选其它路线".into());
        }
        self.validate_frozen(snapshot.revision, request.route)?;
        Ok(PreparedShell {
            request,
            revision: snapshot.revision,
            original_call_id: call.id.clone(),
        })
    }
    pub fn validate_frozen(&self, revision: u64, route: Route) -> Result<(), String> {
        let snapshot = self.snapshot();
        if snapshot.revision != revision {
            return Err("审批后执行档次设置已改变，请重新准备原调用".into());
        }
        let allowed = match route {
            Route::AppUid => true,
            Route::Shizuku { generation } => {
                !matches!(ceiling(snapshot.preference), Ceiling::AppUid)
                    && shizuku_generation(&snapshot.shizuku) == Some(generation)
            }
            Route::Root { generation } => {
                matches!(ceiling(snapshot.preference), Ceiling::Root)
                    && root_generation(&snapshot.root) == Some(generation)
            }
        };
        if allowed {
            Ok(())
        } else {
            Err("冻结权限路线或连接代次已失效，未降级执行".into())
        }
    }
    /// Intended actor bridge for run_shell as well as other exact frozen shell
    /// work. The existing shell runner remains the process/lease owner.
    pub fn execute_shell(&self, prepared: &PreparedShell, cancel: Arc<AtomicBool>) -> Outcome {
        if let Err(error) = self.validate_frozen(prepared.revision, prepared.request.route) {
            return Outcome::not_started(prepared.request.route, error);
        }
        let snapshot = self.snapshot();
        let generation = root_generation(&snapshot.root);
        let executor = FrozenExecutor {
            owner: self.clone(),
            revision: prepared.revision,
            root_generation: generation,
            root_executor: generation.and_then(|g| self.root.executor(g)),
            shizuku_generation: shizuku_generation(&snapshot.shizuku),
        };
        shell_runner::execute(&prepared.request, cancel, Some(&executor))
    }
}
fn root_generation(status: &RootStatus) -> Option<u64> {
    status
        .generation
        .filter(|g| *g > 0 && status.phase == Phase::Ready && !status.quarantined)
}
fn shizuku_generation(status: &platform::shizuku::Status) -> Option<u64> {
    (status.connected && status.granted && status.generation > 0).then_some(status.generation)
}
fn ceiling(preference: Preference) -> Ceiling {
    match preference {
        Preference::AppUid | Preference::Accessibility => Ceiling::AppUid,
        Preference::Shizuku => Ceiling::Shizuku,
        _ => Ceiling::Root,
    }
}
pub fn select(
    preference: Preference,
    requested: Requested,
    root: &RootStatus,
    shizuku: &platform::shizuku::Status,
) -> Route {
    let preferred_ceiling = match preference {
        Preference::AppUid | Preference::Accessibility => 0,
        Preference::Shizuku => 1,
        _ => 2,
    };
    let requested_ceiling = match requested {
        Requested::AppUid => 0,
        Requested::Shizuku => 1,
        _ => 2,
    };
    let limit = preferred_ceiling.min(requested_ceiling);
    if limit >= 2 {
        if let Some(generation) = root_generation(root) {
            return Route::Root { generation };
        }
    }
    if limit >= 1 {
        if let Some(generation) = shizuku_generation(shizuku) {
            return Route::Shizuku { generation };
        }
    }
    Route::AppUid
}
struct FrozenExecutor {
    owner: RoutingOwner,
    revision: u64,
    root_generation: Option<u64>,
    root_executor: Option<Arc<dyn PrivilegedExecutor>>,
    shizuku_generation: Option<u64>,
}
impl PrivilegedExecutor for FrozenExecutor {
    fn execute(&self, request: &FrozenRequest, cancel: Arc<AtomicBool>) -> Outcome {
        if let Err(error) = self.owner.validate_frozen(self.revision, request.route) {
            return Outcome::not_started(request.route, error);
        }
        match request.route {
            Route::Root { generation } if Some(generation) == self.root_generation => {
                match &self.root_executor {
                    Some(executor) => executor.execute(request, cancel),
                    None => Outcome::not_started(request.route, "冻结Root执行器已经失效"),
                }
            }
            Route::Shizuku { generation } if Some(generation) == self.shizuku_generation => {
                platform_tools::shizuku_executor::ShizukuExecutor.execute(request, cancel)
            }
            Route::AppUid => shell_runner::execute(request, cancel, None),
            _ => Outcome::not_started(request.route, "执行器拒绝非冻结的权限代次，未切换路线"),
        }
    }
}
