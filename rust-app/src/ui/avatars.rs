use super::*;
use crate::{
    avatars::{Crop, Request, Update},
    configuration::Assistant,
    platform::{attachments::PixelFrame, permissions::OperationKey},
};
use std::collections::HashMap;
#[derive(Clone, Default)]
pub(super) struct Descriptor {
    pub name: String,
    pub emoji: String,
    pub path: String,
}
impl From<&Assistant> for Descriptor {
    fn from(item: &Assistant) -> Self {
        Self {
            name: item.name.clone(),
            emoji: if item.avatar.trim().is_empty() || item.avatar == "🪶" {
                String::new()
            } else {
                item.avatar.clone()
            },
            path: item.avatar_path.clone(),
        }
    }
}
#[derive(Clone, Default)]
pub(super) struct Face {
    pub name: String,
    texture: Option<egui::TextureId>,
    emoji: String,
}
impl Face {
    pub(super) fn draw(&self, ui: &mut egui::Ui, size: f32) {
        if let Some(id) = self.texture {
            ui.add(
                egui::Image::new((id, egui::vec2(size, size)))
                    .fit_to_exact_size(egui::vec2(size, size))
                    .corner_radius(egui::CornerRadius::same((size / 2.0).min(255.0) as u8)),
            );
        } else if !self.emoji.is_empty() {
            ui.add_sized(
                [size, size],
                egui::Label::new(RichText::new(&self.emoji).size(size * 0.7)),
            );
        } else {
            ui.allocate_space(egui::vec2(size, size));
        }
    }
}
struct Cached {
    texture: egui::TextureHandle,
    touched: u64,
}
struct CropView {
    operation: OperationKey,
    assistant_id: String,
    selection_id: String,
    pixels: std::sync::Arc<PixelFrame>,
    texture: egui::TextureHandle,
    zoom: f32,
    center: egui::Vec2,
    saving: bool,
    error: Option<String>,
}
impl CropView {
    fn rect(&self) -> Crop {
        let side = ((self.pixels.width.min(self.pixels.height) as f32 / self.zoom).round() as u32)
            .clamp(1, self.pixels.width.min(self.pixels.height));
        let left = (self.center.x - side as f32 / 2.0)
            .round()
            .clamp(0.0, (self.pixels.width - side) as f32) as u32;
        let top = (self.center.y - side as f32 / 2.0)
            .round()
            .clamp(0.0, (self.pixels.height - side) as f32) as u32;
        Crop { left, top, side }
    }
}
#[derive(Default)]
pub(super) struct View {
    cache: HashMap<String, Cached>,
    brand: Option<egui::TextureHandle>,
    reads: HashMap<String, String>,
    failed: HashMap<String, String>,
    clock: u64,
    generation: u64,
    pending: Option<(OperationKey, String, String)>,
    crop: Option<CropView>,
}
impl View {
    pub(super) fn modal_open(&self) -> bool {
        self.pending.is_some() || self.crop.is_some()
    }
    pub(super) fn set_brand(&mut self, brand: Option<egui::TextureHandle>) {
        if brand.is_some() {
            self.brand = brand;
        }
    }
    pub(super) fn reset(&mut self) {
        self.cache.clear();
        self.reads.clear();
        self.failed.clear();
        self.pending = None;
        self.crop = None;
        self.generation = self.generation.saturating_add(1).max(1);
    }
    pub(super) fn face(&mut self, descriptor: &Descriptor, requests: &mut Vec<Request>) -> Face {
        self.clock = self.clock.saturating_add(1);
        let mut texture = None;
        if !descriptor.path.is_empty() {
            if let Some(cached) = self.cache.get_mut(&descriptor.path) {
                cached.touched = self.clock;
                texture = Some(cached.texture.id());
            } else if !self.reads.contains_key(&descriptor.path)
                && !self.failed.contains_key(&descriptor.path)
                && self.reads.len() < 16
            {
                let request_id = crate::controller::unique_id();
                self.reads
                    .insert(descriptor.path.clone(), request_id.clone());
                requests.push(Request::Read {
                    request_id,
                    path: descriptor.path.clone(),
                });
            }
        }
        if texture.is_none() && descriptor.emoji.is_empty() {
            texture = self.brand.as_ref().map(egui::TextureHandle::id);
        }
        Face {
            name: descriptor.name.clone(),
            texture,
            emoji: descriptor.emoji.clone(),
        }
    }
    pub(super) fn show(
        &mut self,
        ui: &mut egui::Ui,
        assistant: &Assistant,
        size: f32,
        requests: &mut Vec<Request>,
    ) {
        self.face(&Descriptor::from(assistant), requests)
            .draw(ui, size);
    }
    fn failure(&mut self, path: String, error: String) {
        self.failed.insert(path, error);
        while self.failed.len() > 32 {
            if let Some(key) = self.failed.keys().next().cloned() {
                self.failed.remove(&key);
            } else {
                break;
            }
        }
    }
    fn install(
        &mut self,
        ctx: &egui::Context,
        path: &str,
        pixels: &PixelFrame,
    ) -> Result<(), String> {
        if pixels.width == 0
            || pixels.height == 0
            || pixels.width > 256
            || pixels.height > 256
            || pixels.rgba.len() != pixels.width as usize * pixels.height as usize * 4
        {
            return Err("头像预览像素超出256px预算".into());
        }
        self.clock = self.clock.saturating_add(1);
        let texture = ctx.load_texture(
            format!("assistant-avatar:{path}"),
            egui::ColorImage::from_rgba_unmultiplied(
                [pixels.width as usize, pixels.height as usize],
                &pixels.rgba,
            ),
            egui::TextureOptions::LINEAR,
        );
        self.cache.insert(
            path.to_owned(),
            Cached {
                texture,
                touched: self.clock,
            },
        );
        self.failed.remove(path);
        while self.cache.len() > 16 {
            let victim = self
                .cache
                .iter()
                .filter(|(key, _)| key.as_str() != path)
                .min_by_key(|(_, entry)| entry.touched)
                .map(|(key, _)| key.clone());
            if let Some(victim) = victim {
                self.cache.remove(&victim);
            } else {
                break;
            }
        }
        Ok(())
    }
}
impl RustApplication {
    pub(super) fn avatar_requests(&mut self, requests: Vec<Request>) {
        for request in requests {
            let failed = request.failure("头像操作未被运行时受理");
            if !self.dispatch(Action::Avatar(request)) {
                self.avatar_event_no_context(failed);
            }
        }
    }
    fn avatar_event_no_context(&mut self, failed: Update) {
        match failed {
            Update::Read {
                request_id,
                path,
                result: Err(error),
            } => {
                if self.avatar_view.reads.get(&path) == Some(&request_id) {
                    self.avatar_view.reads.remove(&path);
                    self.avatar_view.failure(path, error);
                }
            }
            Update::Failed { operation, error } => {
                if self
                    .avatar_view
                    .pending
                    .as_ref()
                    .is_some_and(|(key, _, _)| key == &operation)
                {
                    self.avatar_view.pending = None;
                    self.avatar_view.crop = None;
                    self.state.error(error);
                }
            }
            _ => {}
        }
    }
    pub(super) fn avatar_pick(&mut self, assistant_id: String, previous_path: String) {
        if self.avatar_view.pending.is_some() {
            self.state.error("请先完成或取消当前头像选择");
            return;
        }
        self.avatar_view.generation = self.avatar_view.generation.saturating_add(1).max(1);
        let operation = OperationKey {
            id: crate::controller::unique_id(),
            conversation_id: None,
            generation: self.avatar_view.generation,
        };
        if self.dispatch(Action::Avatar(Request::Pick {
            operation: operation.clone(),
            assistant_id: assistant_id.clone(),
        })) {
            self.avatar_view.pending = Some((operation, assistant_id, previous_path));
        }
    }
    pub(super) fn avatar_event(&mut self, ctx: &egui::Context, update: &Update) {
        match update {
            Update::Read {
                request_id,
                path,
                result,
            } => {
                if self.avatar_view.reads.get(path) != Some(request_id) {
                    return;
                }
                self.avatar_view.reads.remove(path);
                let outcome = result
                    .as_ref()
                    .map_err(Clone::clone)
                    .and_then(|pixels| self.avatar_view.install(ctx, path, pixels));
                if let Err(error) = outcome {
                    self.avatar_view.failure(path.clone(), error);
                }
            }
            Update::Selected {
                operation,
                assistant_id,
                selection_id,
                pixels,
            } => {
                if !self
                    .avatar_view
                    .pending
                    .as_ref()
                    .is_some_and(|(key, id, _)| key == operation && id == assistant_id)
                {
                    return;
                }
                if pixels.width == 0
                    || pixels.height == 0
                    || pixels.width > 1080
                    || pixels.height > 1080
                    || pixels.rgba.len() != pixels.width as usize * pixels.height as usize * 4
                {
                    self.state.error("头像裁剪工作像素无效");
                    return;
                }
                let texture = ctx.load_texture(
                    format!("avatar-crop:{}", operation.id),
                    egui::ColorImage::from_rgba_unmultiplied(
                        [pixels.width as usize, pixels.height as usize],
                        &pixels.rgba,
                    ),
                    egui::TextureOptions::LINEAR,
                );
                self.avatar_view.crop = Some(CropView {
                    operation: operation.clone(),
                    assistant_id: assistant_id.clone(),
                    selection_id: selection_id.clone(),
                    pixels: pixels.clone(),
                    texture,
                    zoom: 1.0,
                    center: egui::vec2(pixels.width as f32 / 2.0, pixels.height as f32 / 2.0),
                    saving: false,
                    error: None,
                });
            }
            Update::Saved {
                operation,
                assistant_id,
                path,
                pixels,
            } => {
                let Some((key, id, previous)) = self.avatar_view.pending.as_ref() else {
                    return;
                };
                if key != operation || id != assistant_id {
                    return;
                }
                let Some(assistant) = self
                    .settings_draft
                    .assistants
                    .iter_mut()
                    .find(|item| item.id == *assistant_id && item.avatar_path == *previous)
                else {
                    self.state
                        .error("助手或原头像已变化，未将旧操作结果写入新草稿");
                    self.avatar_view.pending = None;
                    self.avatar_view.crop = None;
                    return;
                };
                assistant.avatar_path = path.clone();
                if let Err(error) = self.avatar_view.install(ctx, path, pixels) {
                    self.state.error(error);
                }
                self.avatar_view.pending = None;
                self.avatar_view.crop = None;
                self.state
                    .info("头像已裁剪并写入私有图片文件，请保存助手配置后使用。");
            }
            Update::Failed { operation, error } => {
                if !self
                    .avatar_view
                    .pending
                    .as_ref()
                    .is_some_and(|(key, _, _)| key == operation)
                {
                    return;
                }
                if let Some(crop) = self.avatar_view.crop.as_mut() {
                    crop.saving = false;
                    crop.error = Some(error.clone());
                } else {
                    self.avatar_view.pending = None;
                    self.state.error(error);
                }
            }
            Update::Cancelled { operation } => {
                if self
                    .avatar_view
                    .pending
                    .as_ref()
                    .is_some_and(|(key, _, _)| key == operation)
                {
                    self.avatar_view.pending = None;
                    self.avatar_view.crop = None;
                }
            }
        }
    }
    pub(super) fn avatar_dialog(&mut self, ctx: &egui::Context) {
        let mut open = true;
        let mut save = None;
        let mut cancel = None;
        if let Some(crop) = self.avatar_view.crop.as_mut() {
            egui::Window::new("裁剪助手头像")
                .id(egui::Id::new("assistant-avatar-crop"))
                .open(&mut open)
                .collapsible(false)
                .default_width(420.0)
                .show(ctx, |ui| {
                    ui.label(
                        "拖动图片调整位置，缩放后选取方形区域。图片保留透明度，最终最长边256px。",
                    );
                    let selected = crop.rect();
                    let size = ui.available_width().min(340.0);
                    let uv = egui::Rect::from_min_max(
                        egui::pos2(
                            selected.left as f32 / crop.pixels.width as f32,
                            selected.top as f32 / crop.pixels.height as f32,
                        ),
                        egui::pos2(
                            (selected.left + selected.side) as f32 / crop.pixels.width as f32,
                            (selected.top + selected.side) as f32 / crop.pixels.height as f32,
                        ),
                    );
                    let response = ui.add(
                        egui::Image::new((crop.texture.id(), egui::vec2(size, size)))
                            .fit_to_exact_size(egui::vec2(size, size))
                            .uv(uv)
                            .sense(egui::Sense::drag()),
                    );
                    if response.dragged() && !crop.saving {
                        let delta = ctx.input(|input| input.pointer.delta());
                        crop.center -= delta * selected.side as f32 / size;
                        let half = selected.side as f32 / 2.0;
                        crop.center.x = crop.center.x.clamp(half, crop.pixels.width as f32 - half);
                        crop.center.y = crop.center.y.clamp(half, crop.pixels.height as f32 - half);
                    }
                    ui.add_enabled(
                        !crop.saving,
                        egui::Slider::new(&mut crop.zoom, 1.0..=4.0).text("缩放"),
                    );
                    if let Some(error) = &crop.error {
                        ui.label(RichText::new(error).color(ui.visuals().error_fg_color));
                    }
                    ui.horizontal(|ui| {
                        if ui
                            .add_enabled(!crop.saving, egui::Button::new("使用这个头像"))
                            .clicked()
                        {
                            save = Some(Request::Crop {
                                operation: crop.operation.clone(),
                                selection_id: crop.selection_id.clone(),
                                crop: crop.rect(),
                            });
                            crop.saving = true;
                            crop.error = None;
                        }
                        if ui.button("取消").clicked() {
                            cancel = Some(crop.operation.clone());
                        }
                    });
                    if crop.saving {
                        ui.spinner();
                    }
                });
            if !open {
                cancel = Some(crop.operation.clone());
            }
        } else if let Some((operation, _, _)) = &self.avatar_view.pending {
            egui::Window::new("选择助手头像")
                .id(egui::Id::new("assistant-avatar-pending"))
                .collapsible(false)
                .show(ctx, |ui| {
                    ui.spinner();
                    ui.label("等待图片选择或读取结果…");
                    if ui.button("取消").clicked() {
                        cancel = Some(operation.clone());
                    }
                });
        }
        if let Some(operation) = cancel {
            self.dispatch(Action::Avatar(Request::Cancel(operation)));
            self.avatar_view.pending = None;
            self.avatar_view.crop = None;
        } else if let Some(request) = save {
            let failure = request.failure("头像裁剪请求未受理");
            if !self.dispatch(Action::Avatar(request)) {
                self.avatar_event(ctx, &failure);
            }
        }
    }
    pub(super) fn avatar_back(&mut self) -> bool {
        let operation = self
            .avatar_view
            .crop
            .as_ref()
            .map(|crop| crop.operation.clone())
            .or_else(|| {
                self.avatar_view
                    .pending
                    .as_ref()
                    .map(|(operation, _, _)| operation.clone())
            });
        let Some(operation) = operation else {
            return false;
        };
        self.dispatch(Action::Avatar(Request::Cancel(operation)));
        self.avatar_view.pending = None;
        self.avatar_view.crop = None;
        true
    }
    pub(super) fn chat_portrait(&mut self, ui: &mut egui::Ui, size: f32) -> bool {
        let child_descriptor = self
            .child_viewer
            .as_ref()
            .and_then(|(chat, id)| {
                self.state
                    .children
                    .get(chat)
                    .and_then(|children| children.iter().find(|child| child.id() == id))
            })
            .and_then(|record| record.config.as_ref())
            .map(|config| Descriptor {
                name: config.assistant_name.clone(),
                emoji: if config.assistant_avatar == "🪶" {
                    String::new()
                } else {
                    config.assistant_avatar.clone()
                },
                path: config.assistant_avatar_path.clone(),
            });
        let descriptor = child_descriptor.or_else(|| {
            self.current_assistant_id().and_then(|id| {
                self.state
                    .settings
                    .assistants
                    .iter()
                    .find(|item| item.id == id)
                    .map(Descriptor::from)
            })
        });
        let Some(descriptor) = descriptor else {
            return false;
        };
        if descriptor.path.is_empty()
            && descriptor.emoji.is_empty()
            && self.avatar_view.brand.is_none()
        {
            return false;
        }
        let mut requests = Vec::new();
        self.avatar_view
            .face(&descriptor, &mut requests)
            .draw(ui, size);
        self.avatar_requests(requests);
        true
    }
    pub(super) fn main_history_face(&mut self, source: &crate::display::ViewSource) {
        let descriptor = self
            .current_assistant_id()
            .and_then(|id| {
                self.state
                    .settings
                    .assistants
                    .iter()
                    .find(|item| item.id == id)
                    .map(Descriptor::from)
            })
            .unwrap_or_else(|| Descriptor {
                name: "JAgent".into(),
                ..Default::default()
            });
        let mut requests = Vec::new();
        let face = self.avatar_view.face(&descriptor, &mut requests);
        self.history_renderer
            .set_assistant_face(source.clone(), face);
        self.avatar_requests(requests);
    }
}
