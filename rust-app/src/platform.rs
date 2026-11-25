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
    let mut fonts = egui::FontDefinitions::default();
    if let Some(data) = [
        ("/system/fonts/NotoSansCJK-Regular.ttc", 2),
        ("/system/fonts/NotoSansSC-Regular.otf", 0),
        ("/system/fonts/DroidSansFallback.ttf", 0),
    ]
    .iter()
    .find_map(|(path, index)| {
        std::fs::read(path).ok().map(|bytes| {
            let mut data = egui::FontData::from_owned(bytes);
            data.index = *index;
            data
        })
    }) {
        fonts.font_data.insert("android-cjk".into(), Arc::new(data));
        fonts
            .families
            .entry(egui::FontFamily::Proportional)
            .or_default()
            .insert(0, "android-cjk".into());
        fonts
            .families
            .entry(egui::FontFamily::Monospace)
            .or_default()
            .push("android-cjk".into());
    }
    if let Ok(bytes) = std::fs::read("/system/fonts/Roboto-Regular.ttf") {
        fonts.font_data.insert(
            "android-sans".into(),
            Arc::new(egui::FontData::from_owned(bytes)),
        );
        fonts
            .families
            .entry(egui::FontFamily::Proportional)
            .or_default()
            .insert(0, "android-sans".into());
    }
    let mut serif = Vec::new();
    for (name, paths) in [
        (
            "android-serif",
            &[
                "/system/fonts/NotoSerif-Regular.ttf",
                "/system/fonts/NotoSerif-Regular.otf",
            ][..],
        ),
        (
            "android-serif-cjk",
            &[
                "/system/fonts/NotoSerifCJK-Regular.ttc",
                "/system/fonts/NotoSerifSC-Regular.otf",
            ][..],
        ),
    ] {
        if let Some((path, bytes)) = paths
            .iter()
            .find_map(|path| std::fs::read(path).ok().map(|bytes| (*path, bytes)))
        {
            let mut data = egui::FontData::from_owned(bytes);
            if path.ends_with("NotoSerifCJK-Regular.ttc") {
                data.index = 2;
            }
            fonts.font_data.insert(name.into(), Arc::new(data));
            serif.push(name.to_owned());
        }
    }
    if let Some(fallback) = fonts.families.get(&egui::FontFamily::Proportional) {
        serif.extend(fallback.iter().cloned());
    }
    fonts
        .families
        .insert(egui::FontFamily::Name("chat-serif".into()), serif);
    context.set_fonts(fonts);
}
