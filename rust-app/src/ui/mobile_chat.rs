use super::{Navigation, Page, RichText, RustApplication};
use crate::subagent_configuration::ApprovalMode;
use eframe::egui::{self, Color32, FontId, Pos2, Rect, Sense, Stroke, Vec2, pos2, vec2};

/// Compose's conversation rows advance 53.8dp and draw the highlight 45.5dp tall.
const DRAWER_ROW_PITCH: f32 = 53.8;
const DRAWER_ROW_INSET: f32 = 4.15;
/// Compose's five drawer entries advance 50.2dp.
const DRAWER_ENTRY_PITCH: f32 = 50.2;

#[derive(Clone)]
pub(super) struct Icons {
    atlas: egui::TextureHandle,
    brand: egui::TextureHandle,
}

pub(super) fn page_header(ui: &mut egui::Ui, skin: Skin, title: &str) -> bool {
    egui::Panel::top("mobile_page_header")
        .exact_size(76.0)
        .resizable(false)
        .show_separator_line(false)
        .frame(egui::Frame::NONE)
        .show(ui, |ui| {
            let rect = ui.max_rect();
            let back = Rect::from_center_size(
                pos2(rect.left() + 36.0, rect.top() + 48.0),
                Vec2::splat(48.0),
            );
            let response = ui.interact(back, ui.make_persistent_id("page back"), Sense::click());
            response.widget_info(|| egui::WidgetInfo::labeled(egui::Role::Button, true, "返回"));
            let painter = ui.painter();
            let center = back.center();
            let start = center - vec2(8.0, 0.0);
            let stroke = Stroke::new(1.8, skin.ink);
            painter.line_segment([start, center + vec2(8.0, 0.0)], stroke);
            painter.line_segment([start, center + vec2(-2.0, -6.0)], stroke);
            painter.line_segment([start, center + vec2(-2.0, 6.0)], stroke);
            single_line(
                ui,
                title,
                Rect::from_min_max(
                    pos2(back.right() + 12.0, back.top()),
                    pos2(rect.right() - 12.0, back.bottom()),
                ),
                skin.font(18.0),
                skin.text,
            );
            response.clicked()
        })
        .inner
}

#[derive(Clone, Copy)]
pub(super) enum Icon {
    Menu,
    Compose,
    More,
    Context,
    Plus,
    ApprovalAsk,
    Send,
    Stop,
    Mic,
    Assistant,
    Clock,
    Files,
    Console,
    Settings,
    Copy,
    Edit,
    Regenerate,
    ApprovalAuto,
    ApprovalDeny,
    Image,
    Camera,
    Document,
    Goal,
    Compact,
}

impl Icon {
    pub(super) fn approval(mode: ApprovalMode) -> Self {
        match mode {
            ApprovalMode::Auto => Self::ApprovalAuto,
            ApprovalMode::Ask => Self::ApprovalAsk,
            ApprovalMode::Deny => Self::ApprovalDeny,
        }
    }
}

pub(super) fn composer_popup<'a>(
    ui: &egui::Ui,
    response: &egui::Response,
    skin: Skin,
    width: f32,
) -> egui::Popup<'a> {
    egui::Popup::menu(response)
        .align(egui::emath::RectAlign::TOP_START)
        .gap(8.0)
        .width(width.min(ui.ctx().content_rect().width() - 24.0))
        .frame(
            egui::Frame::popup(ui.style())
                .inner_margin(egui::Margin::symmetric(18, 14))
                .corner_radius(20)
                .fill(skin.surface),
        )
}

impl Icons {
    pub(super) fn popup_row(
        &self,
        ui: &mut egui::Ui,
        skin: Skin,
        icon: Icon,
        label: &str,
        enabled: bool,
        selected: bool,
    ) -> bool {
        let (row, response) = ui.allocate_exact_size(
            vec2(ui.available_width(), 48.0),
            if enabled {
                Sense::click()
            } else {
                Sense::hover()
            },
        );
        response.widget_info(|| {
            egui::WidgetInfo::selected(egui::Role::Button, enabled, selected, label)
        });
        if response.hovered() && enabled {
            ui.painter()
                .rect_filled(row, 8, mix(skin.surface, skin.ink, 0.08));
        }
        let ink = if enabled {
            skin.ink
        } else {
            skin.ink.gamma_multiply(0.38)
        };
        self.draw(ui, pos2(row.left() + 13.0, row.center().y), 18.0, icon, ink);
        let mut text = row;
        text.min.x += 32.0;
        text.max.x -= if selected { 26.0 } else { 4.0 };
        single_line(
            ui,
            label,
            text,
            skin.font(14.0),
            if enabled { skin.text } else { ink },
        );
        if selected {
            let center = pos2(row.right() - 12.0, row.center().y);
            let stroke = Stroke::new(1.7, skin.accent);
            let joint = center + vec2(-1.5, 3.5);
            ui.painter()
                .line_segment([center + vec2(-5.0, 0.0), joint], stroke);
            ui.painter()
                .line_segment([joint, center + vec2(5.0, -4.0)], stroke);
        }
        response.clicked()
    }

    pub(super) fn load(ctx: &egui::Context) -> Self {
        // The atlas uses the existing Android ic_* drawables, in Icon order.
        let atlas = Self::texture(ctx, "chat icons", include_bytes!("chat-icons.png"));
        let brand = Self::texture(
            ctx,
            "JAgent mark",
            include_bytes!("../../../app/src/main/res/drawable-xxxhdpi/brand_mark_round.png"),
        );
        Self { atlas, brand }
    }

    fn texture(ctx: &egui::Context, name: &str, bytes: &[u8]) -> egui::TextureHandle {
        let mut decoder = png::Decoder::new(std::io::Cursor::new(bytes));
        decoder.set_transformations(
            png::Transformations::normalize_to_color8() | png::Transformations::ALPHA,
        );
        let mut reader = decoder.read_info().unwrap();
        let mut rgba = vec![0; reader.output_buffer_size().unwrap()];
        let info = reader.next_frame(&mut rgba).unwrap();
        let image = egui::ColorImage::from_rgba_unmultiplied(
            [info.width as usize, info.height as usize],
            &rgba[..info.buffer_size()],
        );
        ctx.load_texture(name, image, egui::TextureOptions::LINEAR)
    }

    fn brand(&self, ui: &egui::Ui, rect: Rect) {
        ui.painter().image(
            self.brand.id(),
            rect,
            Rect::from_min_max(Pos2::ZERO, pos2(1.0, 1.0)),
            Color32::WHITE,
        );
    }

    pub(super) fn brand_texture(&self) -> egui::TextureHandle {
        self.brand.clone()
    }

    pub(super) fn draw(&self, ui: &egui::Ui, center: Pos2, size: f32, icon: Icon, tint: Color32) {
        let idx = icon as u32;
        let x = (idx % 8) as f32;
        let y = (idx / 8) as f32;
        let uv = Rect::from_min_max(
            pos2(x / 8.0, y / 3.0),
            pos2((x + 1.0) / 8.0, (y + 1.0) / 3.0),
        );
        ui.painter().image(
            self.atlas.id(),
            Rect::from_center_size(center, Vec2::splat(size)),
            uv,
            tint,
        );
    }

    pub(super) fn button(
        &self,
        ui: &egui::Ui,
        rect: Rect,
        icon: Icon,
        label: &str,
        size: f32,
        ink: Color32,
        enabled: bool,
    ) -> egui::Response {
        let sense = if enabled {
            Sense::click()
        } else {
            Sense::hover()
        };
        let response = ui.interact(rect, ui.make_persistent_id(label), sense);
        response.widget_info(|| egui::WidgetInfo::labeled(egui::Role::Button, enabled, label));
        if response.hovered() && enabled {
            ui.painter().rect_filled(rect, 20, ink.gamma_multiply(0.08));
        }
        self.draw(ui, rect.center(), size, icon, ink);
        response
    }
}

#[derive(Clone, Copy)]
pub(super) struct Skin {
    pub surface: Color32,
    pub bottom: Color32,
    pub drawer: Color32,
    pub input: Color32,
    pub high: Color32,
    pub ink: Color32,
    pub text: Color32,
    pub accent: Color32,
    pub divider: Color32,
    pub user_bubble: Color32,
    pub code: Color32,
    /// Compose's primaryContainer and onPrimaryContainer.
    pub primary_container: Color32,
    pub on_primary_container: Color32,
    serif: bool,
    scale: f32,
}

impl Skin {
    pub(super) fn from_app(app: &RustApplication, ui: &egui::Ui) -> Self {
        let visual = ui.visuals();
        let behavior = &app.state.settings.behavior;
        let dark = visual.dark_mode;
        let scale = match behavior.font_scale {
            0 => 0.88,
            2 => 1.12,
            3 => 1.26,
            _ => 1.0,
        };
        let serif = behavior.font_preset != 0;
        let surface = visual.panel_fill;
        let effective_palette = behavior.palette_mode;
        let (drawer, input, high, ink, divider, user_bubble) = match (effective_palette, dark) {
            (4, false) => (0xf7f7f7, 0xffffff, 0xf0f0f0, 0x5c5c5c, 0xe5e5ea, 0xeaf2ff),
            (4, true) => (0x0d0d0f, 0x1c1c1e, 0x242426, 0x9c9ca3, 0x2c2c2e, 0x1f3557),
            (1, _) => {
                return Self {
                    surface,
                    bottom: surface,
                    drawer: visual.faint_bg_color,
                    input: if dark { visual.window_fill } else { surface },
                    high: visual.faint_bg_color,
                    ink: visual.weak_text_color(),
                    text: visual.text_color(),
                    accent: visual.hyperlink_color,
                    divider: visual.window_stroke.color,
                    user_bubble: visual.faint_bg_color,
                    code: visual.code_bg_color,
                    primary_container: rgb(if dark { 0x2d2d2d } else { 0xf4f4f4 }),
                    on_primary_container: rgb(if dark { 0xffffff } else { 0x1a1c1f }),
                    serif,
                    scale,
                };
            }
            (_, false) => (0xedeBe3, 0xfaf9f5, 0xedeBe3, 0x635f55, 0xdedcd2, 0xeae4d5),
            (_, true) => (0x32312e, 0x2d2d2a, 0x32312e, 0xc3bfb3, 0x42413b, 0x57362c),
        };
        let accent = visual.hyperlink_color;
        let bottom = if dark {
            surface
        } else {
            mix(surface, accent, 0.06)
        };
        Self {
            surface,
            bottom,
            drawer: rgb(drawer),
            input: rgb(input),
            high: rgb(high),
            ink: rgb(ink),
            text: visual.text_color(),
            accent,
            divider: rgb(divider),
            user_bubble: rgb(user_bubble),
            primary_container: rgb(if dark { 0x57362c } else { 0xf6ded4 }),
            on_primary_container: rgb(if dark { 0xffdccd } else { 0x54271c }),
            code: if serif {
                rgb(if dark { 0x363531 } else { 0xe8e6dc })
            } else {
                visual.code_bg_color
            },
            serif,
            scale,
        }
    }

    pub(super) fn font(self, size: f32) -> FontId {
        static CHAT_SERIF: std::sync::OnceLock<egui::FontFamily> = std::sync::OnceLock::new();
        let family = if self.serif {
            CHAT_SERIF
                .get_or_init(|| egui::FontFamily::Name("chat-serif".into()))
                .clone()
        } else {
            egui::FontFamily::Proportional
        };
        FontId::new(size * self.scale, family)
    }

    pub(super) fn background(self, ui: &egui::Ui, rect: Rect) {
        let ctx = ui.ctx();
        let layer = ui.layer_id();
        let clip = ctx
            .layer_transform_to_global(layer)
            .unwrap_or_default()
            .inverse()
            * ctx.viewport_rect();
        let painter = egui::Painter::new(ctx.clone(), layer, clip.intersect(rect));
        if self.surface == self.bottom {
            painter.rect_filled(rect, 0, self.surface);
            return;
        }
        let mut mesh = egui::Mesh {
            vertices: Vec::with_capacity(4),
            indices: Vec::with_capacity(6),
            ..Default::default()
        };
        mesh.colored_vertex(rect.left_top(), self.surface);
        mesh.colored_vertex(rect.right_top(), self.surface);
        mesh.colored_vertex(rect.right_bottom(), self.bottom);
        mesh.colored_vertex(rect.left_bottom(), self.bottom);
        mesh.add_triangle(0, 1, 2);
        mesh.add_triangle(0, 2, 3);
        painter.add(egui::Shape::mesh(mesh));
    }
}

fn rgb(hex: u32) -> Color32 {
    Color32::from_rgb((hex >> 16) as u8, (hex >> 8) as u8, hex as u8)
}

pub(super) fn mix(a: Color32, b: Color32, fraction: f32) -> Color32 {
    let channel = |a: u8, b: u8| (a as f32 + (b as f32 - a as f32) * fraction).round() as u8;
    Color32::from_rgb(
        channel(a.r(), b.r()),
        channel(a.g(), b.g()),
        channel(a.b(), b.b()),
    )
}

fn capsule(ui: &egui::Ui, rect: Rect, fill: Color32) {
    let shadow = egui::epaint::Shadow {
        offset: [0, 2],
        blur: 12,
        color: Color32::from_black_alpha(9),
        ..Default::default()
    };
    ui.painter().add(shadow.as_shape(rect, 20));
    ui.painter().rect_filled(rect, 20, fill);
}

pub(super) fn single_line(ui: &egui::Ui, text: &str, bounds: Rect, font: FontId, color: Color32) {
    paint_line(ui, text, bounds, font, color, false);
}

fn paint_line(
    ui: &egui::Ui,
    text: &str,
    bounds: Rect,
    font: FontId,
    color: Color32,
    centered: bool,
) {
    let mut job =
        egui::text::LayoutJob::simple(text.to_owned(), font, color, bounds.width().max(0.0));
    job.wrap.max_rows = 1;
    job.break_on_newline = false;
    let galley = ui.fonts_mut(|fonts| fonts.layout_job(job));
    let x = if centered {
        bounds.center().x - galley.size().x * 0.5
    } else {
        bounds.left()
    };
    let pos = pos2(x, bounds.center().y - galley.size().y * 0.5);
    ui.painter().galley(pos, galley, color);
}

impl RustApplication {
    pub(super) fn mobile_header(&mut self, ui: &mut egui::Ui, skin: Skin) {
        let (bar, _) = ui.allocate_exact_size(vec2(ui.available_width(), 60.0), Sense::hover());
        let y = bar.center().y + 2.0;
        let left = Rect::from_center_size(pos2(bar.left() + 40.0, y), Vec2::splat(40.0));
        let right = Rect::from_center_size(pos2(bar.right() - 56.0, y), vec2(80.0, 40.0));
        let title = Rect::from_min_max(
            pos2(left.right() + 12.0, y - 20.0),
            pos2(right.left() - 8.0, y + 20.0),
        );
        capsule(ui, left, skin.high);
        capsule(ui, title, skin.surface);
        capsule(ui, right, skin.high);
        if self
            .mobile_icons
            .button(ui, left, Icon::Menu, "打开侧边栏", 20.0, skin.ink, true)
            .clicked()
        {
            self.drawer_open = true;
            ui.ctx().request_repaint();
        }
        let context =
            Rect::from_min_size(pos2(title.right() - 42.0, title.top()), vec2(40.0, 40.0));
        let conversation = self
            .state
            .selected_conversation
            .as_ref()
            .and_then(|id| self.state.conversations.iter().find(|item| &item.id == id));
        let name = conversation
            .map(|item| item.title.as_str())
            .filter(|title| !title.trim().is_empty())
            .unwrap_or("新会话");
        single_line(
            ui,
            name,
            Rect::from_min_max(
                title.min + vec2(14.0, 0.0),
                pos2(context.left(), title.bottom()),
            ),
            skin.font(15.0),
            skin.text,
        );
        let response =
            self.mobile_icons
                .button(ui, context, Icon::Context, "上下文", 16.0, skin.ink, true);
        egui::Popup::menu(&response).show(|ui| {
            if let Some(id) = self.state.selected_conversation.as_ref() {
                let reading = self.context_readings.get(id).map(|(_, reading)| reading);
                if let Some(reading) = reading {
                    ui.label(format!("上下文 · {} tokens", reading.used));
                }
                if ui
                    .add_enabled(self.can_compact_context(), egui::Button::new("压缩上下文"))
                    .clicked()
                {
                    self.compact_context();
                    ui.close();
                }
            } else {
                ui.label("当前还没有会话上下文");
            }
        });
        let new = Rect::from_min_size(right.min, Vec2::splat(40.0));
        let enabled =
            self.state.loaded && self.state.capabilities.chat && !self.state.conversation_pending;
        if self
            .mobile_icons
            .button(ui, new, Icon::Compose, "新对话", 20.0, skin.ink, enabled)
            .clicked()
        {
            self.navigate(Navigation::NewConversation, true);
        }
        let more = new.translate(vec2(40.0, 0.0));
        let response = self
            .mobile_icons
            .button(ui, more, Icon::More, "更多", 20.0, skin.ink, true);
        egui::Popup::menu(&response).show(|ui| {
            if ui.button("打开终端").clicked() {
                self.navigate(Navigation::Page(Page::Terminal), true);
                ui.close();
            }
            if ui.button("任务清单").clicked() {
                self.todos_open = true;
                ui.close();
            }
            self.mobile_child_menu(ui);
            ui.menu_button("切换助手", |ui| self.conversation_assistant(ui));
            ui.menu_button("更多页面", |ui| {
                for page in Page::ALL {
                    if ui.button(page.label()).clicked() {
                        self.navigate(Navigation::Page(page), true);
                        ui.close();
                    }
                }
            });
        });
    }

    fn mobile_child_menu(&mut self, ui: &mut egui::Ui) {
        let Some(id) = self.state.selected_conversation.as_ref() else {
            return;
        };
        let Some(records) = self
            .state
            .children
            .get(id)
            .filter(|children| !children.is_empty())
        else {
            return;
        };
        ui.menu_button("查看子智能体", |ui| {
            egui::ScrollArea::vertical()
                .max_height(280.0)
                .show(ui, |ui| {
                    for child in records {
                        if ui
                            .button(format!("{} · {}", child.name, child.status.label()))
                            .clicked()
                        {
                            self.child_viewer = Some((id.clone(), child.id().to_owned()));
                            ui.close();
                        }
                    }
                });
        });
    }

    pub(super) fn mobile_drawer(&mut self, ui: &mut egui::Ui, skin: Skin) -> Option<Navigation> {
        let mut navigation = None;
        ui.spacing_mut().item_spacing.y = 0.0;
        ui.add_space(18.0);
        let (header, _) = ui.allocate_exact_size(vec2(ui.available_width(), 38.0), Sense::hover());
        let mark = Rect::from_center_size(
            pos2(header.left() + 35.0, header.center().y),
            Vec2::splat(30.0),
        );
        let mut portrait = ui.new_child(
            egui::UiBuilder::new()
                .id_salt("drawer portrait")
                .max_rect(mark),
        );
        if !self.chat_portrait(&mut portrait, 30.0) {
            self.mobile_icons.brand(ui, mark);
        }
        let new = Rect::from_min_size(pos2(header.right() - 48.0, header.top()), Vec2::splat(38.0));
        let sort = new.translate(vec2(-38.0, 0.0));
        let name = self
            .current_assistant_id()
            .and_then(|id| {
                self.state
                    .settings
                    .assistants
                    .iter()
                    .find(|item| item.id == id)
                    .map(|item| item.name.as_str())
            })
            .unwrap_or("JAgent");
        single_line(
            ui,
            name,
            Rect::from_min_max(
                pos2(mark.right() + 11.0, header.top()),
                pos2(sort.left(), header.bottom()),
            ),
            skin.font(17.0),
            skin.text,
        );
        let sort_ink = if self.drawer_sorting {
            skin.accent
        } else {
            skin.ink
        };
        if self
            .mobile_icons
            .button(ui, sort, Icon::Menu, "排序会话", 18.0, sort_ink, true)
            .clicked()
        {
            self.drawer_sorting = !self.drawer_sorting;
        }
        let enabled =
            self.state.loaded && self.state.capabilities.chat && !self.state.conversation_pending;
        if self
            .mobile_icons
            .button(ui, new, Icon::Compose, "新对话", 22.0, skin.ink, enabled)
            .clicked()
        {
            navigation = Some(Navigation::NewConversation);
        }
        ui.add_space(14.0);
        divider(ui, 16.0, skin.divider);
        ui.add_space(8.5);
        self.drawer_delete_confirmation(ui, &mut navigation);
        let footer_height = 13.0 + 5.0 * DRAWER_ENTRY_PITCH + 18.0;
        let list_height = (ui.available_height() - footer_height).max(24.0);
        let count = self.conversation_manager.active_order(&self.state).len();
        let (list_rect, _) =
            ui.allocate_exact_size(vec2(ui.available_width(), list_height), Sense::hover());
        let mut list = ui.new_child(
            egui::UiBuilder::new()
                .id_salt("mobile conversations")
                .max_rect(list_rect),
        );
        list.set_clip_rect(ui.clip_rect().intersect(list_rect));
        egui::ScrollArea::vertical()
            .auto_shrink([false, false])
            .max_height(list_height)
            .show_rows(&mut list, DRAWER_ROW_PITCH, count, |ui, rows| {
                for index in rows {
                    let index = self.conversation_manager.active_order(&self.state)[index];
                    let conversation = self.state.conversations[index].clone();
                    ui.push_id(&conversation.id, |ui| {
                        let (rect, _) = ui.allocate_exact_size(
                            vec2(ui.available_width(), DRAWER_ROW_PITCH),
                            Sense::hover(),
                        );
                        let row = rect.shrink2(vec2(12.0, DRAWER_ROW_INSET));
                        let actions = Rect::from_min_size(
                            pos2(row.right() - 48.0, row.top()),
                            vec2(44.0, row.height()),
                        );
                        let hit = Rect::from_min_max(row.min, pos2(actions.left(), row.bottom()));
                        let selected =
                            self.state.selected_conversation.as_ref() == Some(&conversation.id);
                        let response = ui.interact(
                            hit,
                            ui.make_persistent_id("open"),
                            if enabled {
                                Sense::click()
                            } else {
                                Sense::hover()
                            },
                        );
                        let name = if conversation.title.is_empty() {
                            "未命名会话"
                        } else {
                            &conversation.title
                        };
                        response.widget_info(|| {
                            egui::WidgetInfo::labeled(egui::Role::ListItem, enabled, name)
                        });
                        if selected || response.hovered() {
                            ui.painter().rect_filled(
                                row,
                                15,
                                mix(skin.drawer, skin.accent, if selected { 0.16 } else { 0.08 }),
                            );
                        }
                        let ink = if selected { skin.accent } else { skin.text };
                        let title = hit.shrink2(vec2(14.0, 0.0));
                        single_line(ui, name, title, skin.font(15.0), ink);
                        if self
                            .state
                            .conversation_preferences
                            .completed_unread
                            .contains(&conversation.id)
                        {
                            ui.painter().circle_filled(
                                pos2(row.left() + 7.0, row.center().y),
                                2.5,
                                skin.accent,
                            );
                        }
                        self.conversation_drop_target(&response, &conversation);
                        if response.clicked() {
                            navigation = Some(Navigation::Conversation(conversation.id.clone()));
                        }
                        if self.drawer_sorting {
                            let mut grip = ui.new_child(
                                egui::UiBuilder::new().id_salt("order").max_rect(actions),
                            );
                            self.drawer_reorder_handle(&mut grip, &conversation);
                        } else {
                            let dots =
                                ui.interact(actions, ui.make_persistent_id("更多"), Sense::click());
                            dots.widget_info(|| {
                                egui::WidgetInfo::labeled(egui::Role::Button, true, "会话操作")
                            });
                            // Compose shows the three dots on the selected row only.
                            if selected {
                                for x in [-4.5, 0.0, 4.5] {
                                    ui.painter().circle_filled(
                                        actions.center() + vec2(x, 0.0),
                                        1.5,
                                        skin.ink,
                                    );
                                }
                            }
                            egui::Popup::menu(&dots).show(|ui| {
                                if ui.button("导出完整会话 JSON").clicked() {
                                    self.begin_conversation_export(
                                        conversation.id.clone(),
                                        conversation.title.clone(),
                                    );
                                    ui.close();
                                }
                                if ui
                                    .add_enabled(
                                        self.can_delete_conversation(&conversation.id),
                                        egui::Button::new("删除会话"),
                                    )
                                    .clicked()
                                {
                                    self.conversation_delete = Some(conversation.id.clone());
                                    ui.close();
                                }
                            });
                        }
                    });
                }
            });
        ui.add_space(6.0);
        divider(ui, 16.0, skin.divider);
        ui.add_space(6.0);
        for (name, icon, page) in [
            ("助手", Icon::Assistant, Some(Page::Assistants)),
            ("定时任务", Icon::Clock, Some(Page::Schedules)),
            ("文件", Icon::Files, Some(Page::Files)),
            ("悬浮窗控制台", Icon::Console, None),
            ("设置", Icon::Settings, Some(Page::Settings)),
        ] {
            let (rect, _) = ui.allocate_exact_size(
                vec2(ui.available_width(), DRAWER_ENTRY_PITCH),
                Sense::hover(),
            );
            let row = rect.shrink2(vec2(12.0, 1.0));
            let response = ui.interact(row, ui.make_persistent_id(name), Sense::click());
            response.widget_info(|| egui::WidgetInfo::labeled(egui::Role::Button, true, name));
            if response.hovered() {
                ui.painter()
                    .rect_filled(row, 14, mix(skin.drawer, skin.accent, 0.08));
            }
            self.mobile_icons.draw(
                ui,
                pos2(row.left() + 24.0, row.center().y),
                20.0,
                icon,
                skin.ink,
            );
            single_line(
                ui,
                name,
                row.translate(vec2(46.0, 0.0)),
                skin.font(14.0),
                skin.text,
            );
            if let Some(page) = page {
                if response.clicked() {
                    navigation = Some(Navigation::Page(page));
                }
            } else {
                let active = self.state.settings.behavior.floating_console;
                let track = Rect::from_center_size(
                    pos2(row.right() - 38.0, row.center().y),
                    vec2(42.0, 22.0),
                );
                ui.painter().rect_filled(
                    track,
                    11,
                    if active {
                        skin.accent
                    } else {
                        mix(skin.drawer, skin.ink, 0.14)
                    },
                );
                ui.painter().circle_filled(
                    track.center() + vec2(if active { 10.0 } else { -10.0 }, 0.0),
                    9.0,
                    skin.surface,
                );
                if response.clicked() && self.state.loaded && !self.state.settings_pending {
                    let mut settings = self.state.settings.clone();
                    settings.behavior.floating_console = !active;
                    if self.settings_save_value(settings) {
                        self.state.settings_pending = true;
                    }
                }
            }
        }
        navigation
    }

    pub(super) fn mobile_empty_chat(&mut self, ui: &mut egui::Ui, skin: Skin, configured: bool) {
        // Compose's column: 92dp mark, 6, headlineSmall 31, 6, bodyMedium 24, 24,
        // four 48dp chip boxes 8dp apart, 16 and the bodySmall 18 line.
        let content_height = 433.0;
        let space = ((ui.available_height() - content_height) * 0.5 + 15.0).max(0.0);
        ui.add_space(space);
        ui.vertical_centered(|ui| {
            ui.spacing_mut().item_spacing = vec2(8.0, 0.0);
            if !self.chat_portrait(ui, 92.0) {
                let (rect, _) = ui.allocate_exact_size(Vec2::splat(92.0), Sense::hover());
                self.mobile_icons.brand(ui, rect);
            }
            ui.add_space(6.0);
            let (title, _) =
                ui.allocate_exact_size(vec2(ui.available_width(), 31.0), Sense::hover());
            paint_line(ui, "我们开始吧", title, skin.font(24.0), skin.text, true);
            ui.add_space(6.0);
            let (subtitle, _) =
                ui.allocate_exact_size(vec2(ui.available_width(), 24.0), Sense::hover());
            paint_line(
                ui,
                "说一句话，它就能读屏、点击、翻文件、跑命令。",
                subtitle,
                skin.font(15.0),
                skin.ink,
                true,
            );
            ui.add_space(24.0);
            for (index, suggestion) in [
                "看看现在屏幕上是什么",
                "把下载目录里最新的三个文件列出来",
                "搜一下今天有什么科技新闻",
                "打开设置看看还剩多少存储空间",
            ]
            .into_iter()
            .enumerate()
            {
                if index > 0 {
                    ui.add_space(8.0);
                }
                let enabled = !self.state.run_pending && !self.state.conversation_pending;
                let font = skin.font(14.0);
                let galley = ui
                    .painter()
                    .layout_no_wrap(suggestion.to_owned(), font, skin.ink);
                // The Material surface keeps a 48dp touch box around the 38dp pill.
                let (row, response) = ui.allocate_exact_size(
                    vec2(galley.size().x + 28.0, 48.0),
                    if enabled {
                        Sense::click()
                    } else {
                        Sense::hover()
                    },
                );
                let pill = Rect::from_center_size(row.center(), vec2(row.width(), 38.0));
                ui.painter().rect_filled(pill, 19.0, skin.high);
                ui.painter().rect_stroke(
                    pill,
                    19.0,
                    Stroke::new(1.0, skin.divider),
                    egui::StrokeKind::Middle,
                );
                let text_bounds = pill.shrink2(vec2(14.0, 0.0));
                ui.painter().galley(
                    pos2(
                        text_bounds.left(),
                        text_bounds.center().y - galley.size().y * 0.5,
                    ),
                    galley,
                    skin.ink,
                );
                if response.clicked() {
                    self.composer_platform.fill_suggestion(suggestion);
                }
            }
            ui.add_space(16.0);
            let tip = if configured {
                "建议会先填入输入框，手机操作权限按需开启。"
            } else {
                "先在设置中添加模型，即可开始聊天。"
            };
            let (tip_rect, _) =
                ui.allocate_exact_size(vec2(ui.available_width(), 18.0), Sense::hover());
            paint_line(ui, tip, tip_rect, skin.font(13.0), skin.ink, true);
            if !configured
                && ui
                    .add(
                        egui::Button::new(
                            RichText::new("打开设置")
                                .font(skin.font(14.0))
                                .color(skin.accent),
                        )
                        .frame(false),
                    )
                    .clicked()
            {
                self.navigate(Navigation::Page(Page::Settings), true);
            }
        });
    }
}

pub(super) fn composer_frame(skin: Skin) -> egui::Frame {
    egui::Frame::NONE
        .fill(skin.input)
        .corner_radius(24)
        .inner_margin(egui::Margin {
            left: 0,
            right: 0,
            top: 0,
            bottom: 8,
        })
        .shadow(egui::epaint::Shadow {
            offset: [0, 4],
            blur: 18,
            color: Color32::from_black_alpha(15),
            ..Default::default()
        })
}

pub(super) fn transcript_action(
    ui: &mut egui::Ui,
    chrome: Option<(Skin, &Icons)>,
    icon: Icon,
    label: &str,
    enabled: bool,
) -> bool {
    if let Some((skin, icons)) = chrome {
        let (rect, _) = ui.allocate_exact_size(Vec2::splat(28.0), Sense::hover());
        icons
            .button(ui, rect, icon, label, 17.0, skin.ink, enabled)
            .clicked()
    } else {
        ui.add_enabled(enabled, egui::Button::new(label).small())
            .clicked()
    }
}

pub(super) fn divider(ui: &mut egui::Ui, padding: f32, shade: Color32) {
    let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), 1.0), Sense::hover());
    ui.painter().hline(
        rect.left() + padding..=rect.right() - padding,
        rect.center().y,
        Stroke::new(1.0, shade),
    );
}

pub(super) struct InputChrome<'a> {
    pub icons: &'a Icons,
    pub skin: Skin,
    pub placeholder: &'a str,
    pub model: Option<Rect>,
    pub context: Option<Rect>,
    pub approval_mode: ApprovalMode,
    pub approval_choice: Option<ApprovalMode>,
    pub compact: bool,
    pub stop: bool,
}
