//! Native Rust Android frontend. Feature parity with the original application is tracked explicitly.
mod agent;
mod android_shell_tools;
pub(crate) mod avatars;
mod chat;
mod compaction_runtime;
mod composer;
mod configuration;
mod context;
mod controller;
mod conversation_export;
pub(crate) mod conversation_manager;
pub(crate) mod conversation_preferences;
mod display;
mod events;
mod execution_policy;
mod file_export;
mod file_provider;
mod file_transfer;
mod goal;
mod jobs;
mod legacy_import;
mod legacy_migration;
mod legacy_process_browser;
mod legacy_processes;
pub(crate) mod legacy_reading;
mod legacy_usage;
mod linux;
mod mcp;
mod migration;
mod network;
mod performance;
mod platform;
mod platform_tools;
mod plugin_assets;
mod plugin_runtime;
mod plugins;
mod profile;
mod profile_summary;
mod profile_tools;
mod runtime_host;
mod schedules;
mod session_tools;
mod settings_policy;
mod shell;
mod shell_compatibility;
mod shell_environment;
mod shell_runner;
mod skill_import;
mod skills;
pub mod state;
mod store;
mod subagent_configuration;
mod subagents;
mod system_tools;
mod terminal;
mod terminal_tools;
mod todos;
mod tool_execution;
mod tools;
mod transcript_redaction;
pub mod ui;
mod voice_tools;
mod vt;
mod web;

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
fn android_main(app: winit::platform::android::activity::AndroidApp) {
    let data = match platform::android::paths()
        .map(|paths| paths.files_dir)
        .or_else(|| app.internal_data_path())
    {
        Some(path) => path,
        None => return,
    };
    let options = eframe::NativeOptions {
        android_app: Some(app.clone()),
        renderer: eframe::Renderer::Wgpu,
        ..Default::default()
    };
    let _ = eframe::run_native(
        "JAgent",
        options,
        Box::new(move |cc| {
            platform::install_system_fonts(&cc.egui_ctx);
            let bridge = controller::spawn(data.clone(), cc.egui_ctx.clone())?;
            Ok(Box::new(
                ui::RustApplication::new(cc, data.clone())
                    .with_window_insets(app, cc.egui_ctx.clone())
                    .with_runtime(bridge),
            ))
        }),
    );
}
