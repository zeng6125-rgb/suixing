use eframe::egui;
use std::sync::Arc;

pub(crate) mod accessibility;
#[cfg(target_os = "android")]
pub mod android;
pub mod applications;
pub mod attachments;
pub mod automation;
#[cfg(target_os = "android")]
pub mod background;
pub mod calendar;
pub mod clipboard;
pub(crate) mod components;
pub(crate) mod float_console;
pub(crate) mod input_feedback;
pub mod job_service;
pub mod linux_service;
pub(crate) mod main_call;
pub mod manual_settings;
pub(crate) mod navigation;
pub mod permissions;
pub(crate) mod root_capability;
pub mod scheduler;
pub mod secrets;
#[cfg(target_os = "android")]
pub(crate) mod service_framework;
pub mod shizuku;
pub mod speech_service;
pub(crate) mod system;
pub(crate) mod terminal_keys;
pub mod terminal_service;
pub mod virtual_screen;
pub mod voice;
#[cfg(target_os = "android")]
pub(crate) mod window_insets;
pub(crate) mod window_policy;

#[cfg(target_os = "android")]
pub fn application_bootstrapped(paths: android::PlatformPaths) -> Result<(), String> {
    let host = crate::controller::boot(paths.files_dir)?;
    static HANDLERS: std::sync::OnceLock<Result<(), String>> = std::sync::OnceLock::new();
    HANDLERS
        .get_or_init(|| {
            android::set_service_handler(dispatch_service)?;
            android::set_receiver_handler(dispatch_receiver)
        })
        .clone()?;
    background::start(host.clone())?;
    float_console::start(host.clone())?;
    scheduler::start(host.clone())?;
    terminal_service::start(host)
}

#[cfg(target_os = "android")]
fn dispatch_service(request: android::ServiceRequest) -> Result<i32, String> {
    match request.component.as_str() {
        background::SERVICE_CLASS => background::handle_service(request),
        scheduler::SERVICE_CLASS => scheduler::handle_service(request),
        terminal_service::SERVICE_CLASS => terminal_service::handle_service(request),
        linux_service::SERVICE_CLASS => linux_service::handle_service(request),
        job_service::SERVICE_CLASS => job_service::handle_service(request),
        speech_service::SERVICE_CLASS => speech_service::handle_service(request),
        float_console::SERVICE_CLASS => float_console::handle_service(request),
        _ => Err("未注册的应用服务组件".into()),
    }
}

#[cfg(target_os = "android")]
fn dispatch_receiver(request: android::ReceiverRequest) -> Result<(), String> {
    match request.component.as_str() {
        scheduler::RECEIVER_CLASS | scheduler::BOOT_RECEIVER_CLASS => {
            scheduler::handle_receiver(request)
        }
        _ => {
            let _ = request.completion.finish();
            Err("未注册的应用广播组件".into())
        }
    }
}

/// Use installed Android fonts; no font downloads or bundled third-party artwork are required.
pub fn install_system_fonts(context: &egui::Context) {
    for path in [
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/NotoSansSC-Regular.otf",
        "/system/fonts/DroidSansFallback.ttf",
    ] {
        let Ok(bytes) = std::fs::read(path) else {
            continue;
        };
        let mut fonts = egui::FontDefinitions::default();
        fonts.font_data.insert(
            "android-cjk".into(),
            Arc::new(egui::FontData::from_owned(bytes)),
        );
        for family in [egui::FontFamily::Proportional, egui::FontFamily::Monospace] {
            fonts
                .families
                .entry(family)
                .or_default()
                .insert(0, "android-cjk".into());
        }
        context.set_fonts(fonts);
        break;
    }
}
