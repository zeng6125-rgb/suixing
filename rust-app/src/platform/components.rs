//! Keep installed component identities when replacing the original package.
const IDENTITIES: &[(&str, &str)] = &[
    (
        "com.suixing.agent.MainActivity",
        "com.suixing.agent.rust.platform.RustNativeActivity",
    ),
    (
        "com.suixing.agent.service.AgentForegroundService",
        "com.suixing.agent.rust.platform.RustAgentService",
    ),
    (
        "com.suixing.agent.service.TerminalForegroundService",
        "com.suixing.agent.rust.platform.RustTerminalService",
    ),
    (
        "com.suixing.agent.service.SchedulerService",
        "com.suixing.agent.rust.platform.RustSchedulerService",
    ),
    (
        "com.suixing.agent.service.FloatConsoleService",
        "com.suixing.agent.rust.platform.RustFloatConsoleService",
    ),
    (
        "com.suixing.agent.service.SuixingAccessibilityService",
        "com.suixing.agent.rust.platform.RustAccessibilityService",
    ),
    (
        "com.suixing.agent.service.BootReceiver",
        "com.suixing.agent.rust.platform.RustBootReceiver",
    ),
    (
        "com.suixing.agent.virtualscreen.VirtualScreenActivity",
        "com.suixing.agent.rust.platform.RustVirtualScreenActivity",
    ),
];

pub(crate) fn canonical(name: &str) -> &str {
    IDENTITIES
        .iter()
        .find(|(old, _)| *old == name)
        .map_or(name, |(_, rust)| rust)
}

#[cfg(target_os = "android")]
pub(crate) fn installed(name: &str) -> &str {
    if super::android::paths().is_some_and(|paths| paths.package_name == "com.suixing.agent") {
        IDENTITIES
            .iter()
            .find(|(_, rust)| *rust == name)
            .map_or(name, |(old, _)| old)
    } else {
        name
    }
}
