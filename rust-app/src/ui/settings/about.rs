//! Version metadata and button-triggered update/link work are bounded jobs.
//! Android always reads its installed PackageInfo; no installed version is
//! copied from Cargo metadata or a remembered release.
use crate::{network::CancelToken, platform::permissions::OperationKey};
use eframe::egui;
use serde::Deserialize;
use std::{
    sync::{
        Arc,
        mpsc::{self, Receiver, TryRecvError},
    },
    time::Duration,
};
const REPOSITORY: &str = "https://github.com/zeng6125-rgb/suixing";
const LATEST: &str = "https://api.github.com/repos/zeng6125-rgb/suixing/releases/latest";
#[derive(Clone)]
struct Version {
    name: String,
    code: Option<i64>,
}
struct Release {
    version: String,
    title: String,
    notes: String,
    url: String,
}
enum Work {
    Version,
    Release,
    Open(String),
}
enum ResultValue {
    Version(Version),
    Release(Option<Release>),
    Open,
}
struct Pending {
    cancel: Arc<CancelToken>,
    result: Receiver<Result<ResultValue, String>>,
}
impl Drop for Pending {
    fn drop(&mut self) {
        self.cancel.cancel();
    }
}
#[derive(Default)]
pub(super) struct View {
    version: Option<Version>,
    release: Option<Release>,
    checked: bool,
    requested: Option<Work>,
    pending: Option<Pending>,
    notice: Option<String>,
}
impl View {
    pub(super) fn disconnected(&mut self) {
        self.requested = None;
        if let Some(pending) = &self.pending {
            pending.cancel.cancel();
        }
    }
    pub(super) fn tick(&mut self, visible: bool, ctx: &egui::Context) {
        if !visible {
            self.disconnected();
        }
        if let Some(pending) = &self.pending {
            match pending.result.try_recv() {
                Ok(result) => {
                    if !pending.cancel.is_cancelled() {
                        match result {
                            Ok(ResultValue::Version(version)) => self.version = Some(version),
                            Ok(ResultValue::Release(release)) => {
                                self.release = release;
                                self.checked = true;
                                self.notice = None;
                            }
                            Ok(ResultValue::Open) => self.notice = None,
                            Err(error) => self.notice = Some(error),
                        }
                    }
                    self.pending = None;
                    ctx.request_repaint();
                }
                Err(TryRecvError::Disconnected) => {
                    self.pending = None;
                    self.notice = Some("关于页面操作未完成，可重新尝试".into());
                }
                Err(TryRecvError::Empty) => ctx.request_repaint_after(Duration::from_millis(50)),
            }
        }
        if visible && self.pending.is_none() {
            if let Some(work) = self.requested.take() {
                let (sender, result) = mpsc::sync_channel(1);
                let cancel = Arc::new(CancelToken::default());
                let worker_cancel = cancel.clone();
                let spawned = std::thread::Builder::new()
                    .name("rust-about-operation".into())
                    .spawn(move || {
                        let value = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                            if worker_cancel.is_cancelled() {
                                return Err("关于页面操作已取消".into());
                            }
                            match work {
                                Work::Version => version().map(ResultValue::Version),
                                Work::Release => {
                                    let runtime = tokio::runtime::Builder::new_current_thread()
                                        .enable_all()
                                        .build()
                                        .map_err(|_| "更新检查运行时不可用".to_owned())?;
                                    runtime.block_on(async { tokio::select! {
                                    _ = worker_cancel.cancelled() => Err("更新检查已取消".into()),
                                    result = latest() => result.map(ResultValue::Release),
                                } })
                                }
                                Work::Open(url) => {
                                    let operation = OperationKey {
                                        id: crate::controller::unique_id(),
                                        conversation_id: None,
                                        generation: 1,
                                    };
                                    crate::platform::system::open_url(
                                        &operation,
                                        &url,
                                        worker_cancel.clone(),
                                    )
                                    .map(|_| ResultValue::Open)
                                }
                            }
                        }))
                        .unwrap_or_else(|_| Err("关于页面操作异常".into()));
                        let _ = sender.try_send(value);
                    });
                match spawned {
                    Ok(_) => self.pending = Some(Pending { cancel, result }),
                    Err(_) => self.notice = Some("关于页面工作线程不可用".into()),
                }
            }
        }
    }
    fn link(&mut self, ui: &mut egui::Ui, label: &str, subtitle: &str, url: String) {
        let ready = self.pending.is_none() && self.requested.is_none();
        let clicked = ui
            .add_enabled_ui(ready, |ui| super::controls::link(ui, label, subtitle))
            .inner;
        if clicked {
            self.requested = Some(Work::Open(url));
        }
    }
    pub(super) fn show(
        &mut self,
        ui: &mut egui::Ui,
        brand: egui::TextureHandle,
        assets: &mut crate::plugin_assets::bundled::View,
    ) {
        if self.version.is_none()
            && self.pending.is_none()
            && self.requested.is_none()
            && self.notice.is_none()
        {
            self.requested = Some(Work::Version);
        }
        super::controls::card(ui, 16).show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                ui.add(egui::Image::new((brand.id(), egui::vec2(44.0, 44.0))));
                ui.add_space(4.0);
                ui.vertical(|ui| {
                    super::controls::text(ui, "JAgent", 18.0, 25.0, ui.visuals().text_color());
                    let version = self
                        .version
                        .as_ref()
                        .map(|version| format!("v{}", version.name))
                        .unwrap_or_else(|| "读取应用版本…".into());
                    super::controls::description(ui, &version);
                });
            });
            ui.add_space(12.0);
            super::controls::description(
                ui,
                "跟着你走的手机智能体：说一句话，它就能读屏、点按、跑命令、翻文件。",
            );
        });
        ui.add_space(12.0);
        super::controls::card(ui, 0).show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.add_space(16.0);
            let ready =
                self.version.is_some() && self.pending.is_none() && self.requested.is_none();
            if ui
                .add_enabled_ui(ready, |ui| {
                    super::controls::link(ui, "检查更新", "查看最新正式版本与更新说明")
                })
                .inner
            {
                self.requested = Some(Work::Release);
                self.notice = None;
            }
            if let Some(version) = &self.version {
                let mut url = reqwest::Url::parse(&format!("{REPOSITORY}/issues/new"))
                    .expect("constant repository URL");
                url.query_pairs_mut().append_pair(
                    "body",
                    &format!(
                        "版本：{}\n\n问题描述：\n\n复现步骤：\n1. \n\n期望表现：\n\n实际表现：\n",
                        version.name
                    ),
                );
                self.link(
                    ui,
                    "问题反馈",
                    "填写复现步骤，提交前可自行检查内容",
                    url.to_string(),
                );
            }
            self.link(
                ui,
                "源代码仓库",
                "源码、构建说明与项目文档",
                REPOSITORY.into(),
            );
            ui.add_space(16.0);
        });
        if self.pending.is_some() {
            ui.spinner();
            ui.label("正在处理…");
        }
        if let (Some(current), Some(release)) = (&self.version, &self.release) {
            match relation(&current.name, &release.version) {
                Ok(std::cmp::Ordering::Less) => {
                    ui.label(format!("有新版本 {}", release.version));
                }
                Ok(std::cmp::Ordering::Equal) => {
                    ui.label("已是公开发布的最新版本");
                }
                Ok(std::cmp::Ordering::Greater) => {
                    ui.label(format!("本机版本比最新公开版本 {} 更新", release.version));
                }
                Err(error) => {
                    ui.colored_label(ui.visuals().error_fg_color, error);
                }
            }
            ui.strong(&release.title);
            let url = release.url.clone();
            ui.collapsing("更新说明", |ui| {
                ui.add(egui::Label::new(&release.notes).wrap().selectable(true));
            });
            self.link(ui, "打开官方发布页", "更新说明与安装文件", url);
        } else if self.checked {
            ui.label("官方仓库暂无可用的正式版本");
        }
        ui.add_space(12.0);
        super::controls::card(ui, 16).show(ui, |ui| {
            ui.set_width(ui.available_width());
            super::controls::text(ui, "隐私", 18.0, 25.0, ui.visuals().text_color());
            ui.add_space(8.0);
            super::controls::description(
                ui,
                "回答由你自己配置的模型服务提供；对话、记忆与密钥保存在这台手机上。",
            );
            ui.add_space(8.0);
            super::controls::description(
                ui,
                "API Key 使用系统 Keystore 加密保存，只发送给你配置的对应服务。",
            );
        });
        ui.add_space(12.0);
        super::controls::card(ui, 0).show(ui, |ui| {
            ui.set_width(ui.available_width());
            egui::Frame::NONE.inner_margin(16).show(ui, |ui| {
                super::controls::text(ui, "开源与致谢", 18.0, 25.0, ui.visuals().text_color());
                ui.add_space(8.0);
                super::controls::description(ui, "图标：Lucide（ISC License）");
            });
            self.link(
                ui,
                "Aether 扶摇",
                "配色与排版的参考",
                "https://github.com/Zhou-Shilin/Aether".into(),
            );
            self.link(
                ui,
                "Operit",
                "能力清单与交互约定的参考",
                "https://github.com/AAswordman/Operit".into(),
            );
            self.link(
                ui,
                "Model Context Protocol",
                "MCP 协议规范",
                "https://modelcontextprotocol.io/".into(),
            );
            ui.add_space(16.0);
        });
        ui.add_space(12.0);
        super::controls::card(ui, 16).show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.collapsing("开源许可", |ui| {
                ui.add(
                    egui::Label::new(include_str!("../../../../THIRD_PARTY_NOTICES.md"))
                        .wrap()
                        .selectable(true),
                );
                assets.show_licenses(ui);
            });
            self.link(
                ui,
                "所有版本",
                "已发布版本与源码",
                format!("{REPOSITORY}/releases"),
            );
        });
        ui.add_space(24.0);
        if let Some(notice) = &self.notice {
            ui.colored_label(ui.visuals().error_fg_color, notice);
        }
        if self.version.is_none()
            && self.notice.is_some()
            && self.pending.is_none()
            && ui.button("重新读取版本").clicked()
        {
            self.notice = None;
            self.requested = Some(Work::Version);
        }
        if self.requested.is_some() {
            ui.ctx().request_repaint();
        }
    }
}
fn components(version: &str) -> Result<Vec<u32>, String> {
    let value = version
        .trim()
        .trim_start_matches(['v', 'V'])
        .split('+')
        .next()
        .unwrap_or("")
        .split('-')
        .next()
        .unwrap_or("");
    let parts = value
        .split('.')
        .map(|part| part.parse::<u32>())
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| "无法识别应用或发布版本号".to_owned())?;
    if !(2..=4).contains(&parts.len()) {
        return Err("无法识别应用或发布版本号".into());
    }
    Ok(parts)
}
fn relation(current: &str, published: &str) -> Result<std::cmp::Ordering, String> {
    let current = components(current)?;
    let published = components(published)?;
    for index in 0..current.len().max(published.len()) {
        let relation = current
            .get(index)
            .unwrap_or(&0)
            .cmp(published.get(index).unwrap_or(&0));
        if relation != std::cmp::Ordering::Equal {
            return Ok(relation);
        }
    }
    Ok(std::cmp::Ordering::Equal)
}
async fn latest() -> Result<Option<Release>, String> {
    let roots = rustls::RootCertStore {
        roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
    };
    let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .map_err(|_| "更新检查TLS不可用".to_owned())?
    .with_root_certificates(roots)
    .with_no_client_auth();
    let client = reqwest::Client::builder()
        .use_preconfigured_tls(tls)
        .connect_timeout(Duration::from_secs(10))
        .read_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(20))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|_| "更新检查客户端不可用".to_owned())?;
    let mut response = client
        .get(LATEST)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "suixing-android")
        .send()
        .await
        .map_err(|_| "更新服务连接失败".to_owned())?;
    if response.status().as_u16() == 404 {
        return Ok(None);
    }
    if !response.status().is_success() {
        return Err(format!("更新服务返回HTTP {}", response.status().as_u16()));
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| "更新服务响应读取失败".to_owned())?
    {
        if chunk.len() > (1024 * 1024usize).saturating_sub(bytes.len()) {
            return Err("更新服务响应超过1MiB".into());
        }
        bytes.extend_from_slice(&chunk);
    }
    #[derive(Deserialize)]
    struct Document {
        #[serde(default)]
        draft: bool,
        #[serde(default)]
        prerelease: bool,
        tag_name: String,
        html_url: String,
        #[serde(default)]
        name: Option<String>,
        #[serde(default)]
        body: Option<String>,
    }
    let document: Document =
        serde_json::from_slice(&bytes).map_err(|_| "更新服务返回内容无法识别".to_owned())?;
    if document.draft || document.prerelease {
        return Err("没有可用的正式版本".into());
    }
    components(&document.tag_name)?;
    let url = reqwest::Url::parse(&document.html_url).map_err(|_| "发布链接无效".to_owned())?;
    if url.scheme() != "https"
        || url.host_str() != Some("github.com")
        || !url
            .path()
            .starts_with("/zeng6125-rgb/suixing/releases/tag/")
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err("发布链接不是官方仓库".into());
    }
    let title = document
        .name
        .filter(|name| !name.trim().is_empty())
        .unwrap_or_else(|| document.tag_name.clone())
        .chars()
        .take(512)
        .collect();
    Ok(Some(Release {
        version: document.tag_name,
        title,
        notes: document
            .body
            .unwrap_or_default()
            .chars()
            .take(12_000)
            .collect(),
        url: document.html_url,
    }))
}
#[cfg(not(target_os = "android"))]
fn version() -> Result<Version, String> {
    Ok(Version {
        name: env!("CARGO_PKG_VERSION").into(),
        code: None,
    })
}
#[cfg(target_os = "android")]
fn version() -> Result<Version, String> {
    use crate::platform::android;
    use jni::{JValue, jni_sig, jni_str};
    let context = android::application_context()?;
    android::invoke(|env| {
        let package = env
            .call_method(
                context.as_ref(),
                jni_str!("getPackageName"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        let manager = env
            .call_method(
                context.as_ref(),
                jni_str!("getPackageManager"),
                jni_sig!("()Landroid/content/pm/PackageManager;"),
                &[],
            )?
            .l()?;
        let info = env
            .call_method(
                &manager,
                jni_str!("getPackageInfo"),
                jni_sig!("(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;"),
                &[JValue::Object(&package), JValue::Int(0)],
            )?
            .l()?;
        let name = env
            .get_field(
                &info,
                jni_str!("versionName"),
                jni_sig!("Ljava/lang/String;"),
            )?
            .l()?;
        let name = android::string_value(env, name, 256)?;
        let sdk = env
            .get_static_field(
                jni_str!("android/os/Build$VERSION"),
                jni_str!("SDK_INT"),
                jni_sig!("I"),
            )?
            .i()?;
        let code = if sdk >= 28 {
            env.call_method(&info, jni_str!("getLongVersionCode"), jni_sig!("()J"), &[])?
                .j()?
        } else {
            i64::from(
                env.get_field(&info, jni_str!("versionCode"), jni_sig!("I"))?
                    .i()?,
            )
        };
        Ok(Version {
            name,
            code: Some(code),
        })
    })
}
