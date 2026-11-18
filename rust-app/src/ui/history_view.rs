//! Native chat presentation over bounded immutable display pages.
//! Drawing emits intents; it performs no file reads, network requests or bitmap decode.
use super::mobile_chat::{Icon, Icons, Skin, transcript_action};
use super::{
    history::{HistoryViews, ImagePixels, SourceView, WindowApplied},
    markdown::MarkdownRenderer,
};
use crate::display::{
    BodyKind, BodyPage, BodyPageRequest, BodyRef, CandidateNavigation, DisplayError, DisplayRow,
    FooterPage, FooterRequest, ImageRef, ImageRequest, ReadPosition, SourceStamp, ViewSource,
    WindowAt, WindowRequest,
};
use eframe::egui::{self, RichText, TextureHandle, Vec2};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::{Duration, Instant};

const MAX_TEXTURE_BYTES: usize = 20 * 1024 * 1024;
const MAX_TEXTURES: usize = 64;
const MAX_RENDER_SOURCES: usize = 128;
const MAX_REQUESTS: usize = 128;

pub enum HistoryIntent {
    Window(WindowRequest),
    Body(BodyPageRequest),
    Image(ImageRequest),
    Footer(FooterRequest),
    ReadPosition(ReadPosition),
    SelectCandidate {
        source: ViewSource,
        stamp: SourceStamp,
        user_id: String,
        run_id: String,
    },
    Regenerate {
        source: ViewSource,
        stamp: SourceStamp,
        question_id: String,
    },
    /// The backend verifies the original body hash, preserves both untouched
    /// ranges, then atomically saves or runs the original rewrite planner.
    EditResend {
        body: BodyRef,
        from_byte: u64,
        to_byte: u64,
        replacement: String,
        resend: bool,
    },
    CopyBody(BodyRef),
    SpeakReply(BodyRef),
    OpenFile {
        source: ViewSource,
        path: String,
    },
    TransferFile {
        source: ViewSource,
        path: String,
        kind: crate::file_transfer::Kind,
    },
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
enum RequestOwner {
    Window(ViewSource),
    Body(BodyRef, u64),
    Image(ImageRef, u32),
    Footer(BodyRef, usize),
}
enum Ask {
    Window(WindowAt),
    Body(BodyRef, u64),
    Image(ImageRef, u32),
    Footer(BodyRef, usize),
}
struct CachedTexture {
    handle: TextureHandle,
    width: u32,
    height: u32,
    bytes: usize,
    touched: u64,
}
struct Height {
    message_hash: String,
    width: f32,
    points: f32,
    first_turn: bool,
    running: bool,
    latest: bool,
}
struct BodyHeight {
    message_hash: String,
    width: f32,
    points: f32,
}
#[derive(Default)]
struct LayoutState {
    current_row: String,
    body_offsets: HashMap<(String, BodyKind), u64>,
    footer_offsets: HashMap<String, usize>,
    heights: HashMap<String, Height>,
    body_heights: HashMap<(String, BodyKind, u64), BodyHeight>,
    restore: Option<ReadPosition>,
    next_scroll: Option<f32>,
    force_tail: bool,
    last_width: f32,
    last_saved: Option<ReadPosition>,
    touched: u64,
    notice: Option<String>,
    presented_stamp: Option<SourceStamp>,
    viewport: Option<egui::Rect>,
    navigation_reading: String,
    navigation: Option<PendingNavigation>,
    navigation_interacting: bool,
    turn_motion: Option<TurnMotion>,
    reasoning_manual: HashSet<String>,
}
struct PendingNavigation {
    request_id: String,
    previous: ReadPosition,
    started: Instant,
}
/// The current Kotlin NavigationLandingController's continuous, critically damped
/// approach. One velocity survives the transition to newly measured row geometry.
struct TurnMotion {
    started: Instant,
    previous_frame: Option<Instant>,
    velocity: f32,
}
impl TurnMotion {
    fn new() -> Self {
        Self {
            started: Instant::now(),
            previous_frame: None,
            velocity: 0.0,
        }
    }
    fn speed(error: f32, viewport: f32) -> f32 {
        let viewport = viewport.max(1.0);
        let distance = error.abs() / viewport;
        let (low, high, from, to) = if distance <= 10.0 {
            (12.0, 18.0, 2.0, 10.0)
        } else if distance <= 50.0 {
            (18.0, 36.0, 10.0, 50.0)
        } else {
            (36.0, 72.0, 50.0, 200.0)
        };
        let progress = ((distance - from) / (to - from)).clamp(0.0, 1.0);
        let smooth = progress * progress * (3.0 - 2.0 * progress);
        viewport * (low + (high - low) * smooth)
    }
    fn step(&mut self, error: f32, viewport: f32, scale: f32) -> Option<f32> {
        if self.started.elapsed() > Duration::from_secs(15) {
            return None;
        }
        let now = Instant::now();
        let delta = self
            .previous_frame
            .replace(now)
            .map_or(1.0 / 60.0, |previous| {
                now.saturating_duration_since(previous).as_secs_f32()
            });
        let delta = (delta / scale).clamp(0.0, 0.032);
        let direction = error.signum();
        if self.velocity * direction < 0.0 {
            self.velocity = 0.0;
        }
        let speed = Self::speed(error, viewport);
        let limit = speed.min(14.0 * error.abs());
        self.velocity = self.velocity.clamp(-limit, limit);
        let term = 14.0 * error - self.velocity;
        let decay = (-14.0 * delta).exp();
        let next_error = (error + term * delta) * decay;
        let analytic = error - next_error;
        if !analytic.is_finite() || analytic * direction <= 0.0 {
            self.velocity = 0.0;
            return Some(0.0);
        }
        let magnitude = analytic.abs().min(speed * delta).min(error.abs());
        let remaining = (error.abs() - magnitude).max(0.0);
        let velocity = (self.velocity + 14.0 * term * delta) * decay;
        let limit = Self::speed(direction * remaining, viewport).min(14.0 * remaining);
        self.velocity = if magnitude >= error.abs() || velocity * direction < 0.0 {
            0.0
        } else {
            velocity.clamp(-limit, limit)
        };
        Some(direction * magnitude)
    }
}
struct BodyLayout {
    message_id: String,
    body_message_id: String,
    footer_from: Option<usize>,
    kind: BodyKind,
    from: u64,
    top: f32,
    bottom: f32,
    ready: bool,
}
struct EditDraft {
    body: BodyRef,
    from: u64,
    to: u64,
    text: String,
    error: Option<String>,
    pending: bool,
    resend_allowed: bool,
}

#[derive(Default)]
pub struct HistoryRenderer {
    markdown: MarkdownRenderer,
    sources: HashMap<ViewSource, LayoutState>,
    textures: HashMap<ImageRef, CachedTexture>,
    requests: HashMap<String, RequestOwner>,
    errors: HashMap<RequestOwner, String>,
    image_popup: Option<ImageRef>,
    editor: Option<EditDraft>,
    clock: u64,
    reply_audio_enabled: bool,
    behavior: crate::settings_policy::BehaviorSettings,
    typewriter: super::typewriter::Typewriter,
    assistant_faces: HashMap<ViewSource, super::avatars::Face>,
    motion_scale: Option<f32>,
}
impl HistoryRenderer {
    pub(super) fn set_motion_scale(&mut self, scale: f32) {
        self.motion_scale = Some(if scale.is_finite() && scale >= 0.0 {
            scale
        } else {
            1.0
        });
    }
    pub(super) fn covering_dialog(&self) -> bool {
        self.image_popup.is_some() || self.editor.is_some()
    }
    pub(super) fn set_assistant_face(&mut self, source: ViewSource, face: super::avatars::Face) {
        self.assistant_faces.insert(source.clone(), face);
        while self.assistant_faces.len() > 128 {
            let victim = self
                .assistant_faces
                .keys()
                .find(|key| **key != source)
                .cloned();
            if let Some(victim) = victim {
                self.assistant_faces.remove(&victim);
            } else {
                break;
            }
        }
    }
    pub fn set_behavior(&mut self, behavior: &crate::settings_policy::BehaviorSettings) {
        if &self.behavior != behavior {
            self.behavior = behavior.clone();
            // Saved layout changes invalidate only measured geometry. Cached
            // body pages, images and durable reading positions remain owned.
            for state in self.sources.values_mut() {
                state.heights.clear();
                state.body_heights.clear();
                if !behavior.auto_follow {
                    state.force_tail = false;
                }
            }
        }
    }
    pub fn set_reply_audio_enabled(&mut self, enabled: bool) {
        self.reply_audio_enabled = enabled;
    }
    pub fn disconnected(&mut self) {
        self.requests.clear();
        for state in self.sources.values_mut() {
            if let Some(navigation) = state.navigation.take() {
                state.restore = Some(navigation.previous);
            }
            state.turn_motion = None;
            state.navigation_interacting = false;
            state.next_scroll = None;
        }
        if let Some(editor) = &mut self.editor {
            editor.pending = false;
            editor.error = Some("运行时连接已断开，请重新读取后确认消息状态。".into());
        }
    }
    pub fn on_window(&mut self, source: &ViewSource, applied: WindowApplied) {
        let state = self.sources.entry(source.clone()).or_default();
        if state.navigation.take().is_some() {
            state.turn_motion = Some(TurnMotion::new());
        }
        state.restore = applied.restore.filter(|_| applied.anchor_found);
        if applied.follow_tail && self.behavior.auto_follow {
            state.next_scroll = None;
            state.force_tail = true;
        }
        if let Some(position) = &state.restore {
            state.body_offsets.insert(
                (
                    position
                        .body_message_id
                        .as_ref()
                        .unwrap_or(&position.message_id)
                        .clone(),
                    position.body_kind.clone(),
                ),
                position.byte_offset,
            );
            if let Some(from) = position.footer_from {
                state
                    .footer_offsets
                    .insert(position.message_id.clone(), from);
            }
        }
        self.requests
            .retain(|_, owner| !matches!(owner, RequestOwner::Window(old) if old == source));
        self.errors
            .retain(|owner, _| !matches!(owner, RequestOwner::Window(old) if old == source));
    }
    pub fn on_body(
        &mut self,
        views: &mut HistoryViews,
        page: BodyPage,
    ) -> Result<(), DisplayError> {
        let id = page.request_id.clone();
        let source = page.body.source.clone();
        let message = page.body.message_id.clone();
        let result = views.apply_body(page);
        if result.is_ok() || result == Err(DisplayError::Stale) {
            if let Some(owner) = self.requests.remove(&id) {
                self.errors.remove(&owner);
            }
        }
        if result == Err(DisplayError::Stale) {
            views.request_failed(&source, &id);
        }
        result?;
        if let Some(state) = self.sources.get_mut(&source) {
            state.heights.remove(&message);
            preserve_anchor(state, views.view(&source));
        }
        Ok(())
    }
    pub fn on_footer(
        &mut self,
        views: &mut HistoryViews,
        request_id: &str,
        page: FooterPage,
    ) -> Result<(), DisplayError> {
        let source = page.source.clone();
        let message = page.message_id.clone();
        let result = views.apply_footer(request_id, page);
        if result.is_ok() || result == Err(DisplayError::Stale) {
            if let Some(owner) = self.requests.remove(request_id) {
                self.errors.remove(&owner);
            }
        }
        if result == Err(DisplayError::Stale) {
            views.request_failed(&source, request_id);
        }
        result?;
        if let Some(state) = self.sources.get_mut(&source) {
            state.heights.remove(&message);
            preserve_anchor(state, views.view(&source));
        }
        Ok(())
    }
    /// Call from the event drain, before drawing. Bitmap decoding has already
    /// finished on the backend worker. Stale/unmatched pixels never get uploaded.
    pub fn on_image(
        &mut self,
        ctx: &egui::Context,
        views: &mut HistoryViews,
        pixels: Arc<ImagePixels>,
    ) -> Result<(), DisplayError> {
        if !pixels.valid() {
            return Err(DisplayError::Budget);
        }
        if !views.accepts_image(&pixels) {
            self.requests.remove(&pixels.request_id);
            views.request_failed(&pixels.image.source, &pixels.request_id);
            return Err(DisplayError::Stale);
        }
        let image = pixels.image.clone();
        let request_id = pixels.request_id.clone();
        let width = pixels.width;
        let height = pixels.height;
        let bytes = pixels.rgba.len();
        let color = egui::ColorImage::from_rgba_unmultiplied(
            [width as usize, height as usize],
            &pixels.rgba,
        );
        views.apply_image(pixels)?;
        self.clock = self.clock.saturating_add(1);
        let handle = ctx.load_texture(
            format!("history-image-{request_id}"),
            color,
            egui::TextureOptions::LINEAR,
        );
        self.textures.insert(
            image.clone(),
            CachedTexture {
                handle,
                width,
                height,
                bytes,
                touched: self.clock,
            },
        );
        self.requests.remove(&request_id);
        if let Some(state) = self.sources.get_mut(&image.source) {
            state.heights.remove(&image.message_id);
            preserve_anchor(state, views.view(&image.source));
        }
        while self.textures.len() > MAX_TEXTURES
            || self
                .textures
                .values()
                .map(|texture| texture.bytes)
                .sum::<usize>()
                > MAX_TEXTURE_BYTES
        {
            let oldest = self
                .textures
                .iter()
                .filter(|(reference, _)| *reference != &image)
                .min_by_key(|(_, texture)| texture.touched)
                .map(|(reference, _)| reference.clone());
            let Some(oldest) = oldest else {
                break;
            };
            self.textures.remove(&oldest);
        }
        Ok(())
    }
    pub fn request_failed(
        &mut self,
        views: &mut HistoryViews,
        source: &ViewSource,
        request_id: &str,
        reason: &str,
    ) {
        if !views.request_pending(source, request_id) {
            if self
                .requests
                .get(request_id)
                .is_some_and(|owner| owner_source(owner) == source)
            {
                self.requests.remove(request_id);
            }
            return;
        }
        if self
            .requests
            .get(request_id)
            .is_some_and(|owner| owner_source(owner) != source)
        {
            return;
        }
        self.restore_navigation(views, source, request_id);
        views.request_failed(source, request_id);
        let owner = self
            .requests
            .remove(request_id)
            .unwrap_or_else(|| RequestOwner::Window(source.clone()));
        if self.errors.len() >= MAX_REQUESTS {
            self.errors.clear();
        }
        self.errors.insert(owner, bounded(reason, 2048));
    }
    /// Called only for typed backend read failures. The cache's exact pending ID
    /// is required even when a stale renderer owner has not yet been pruned by
    /// the next draw. Unowned late failures cannot clear or poison a newer load.
    pub fn read_failed(
        &mut self,
        views: &mut HistoryViews,
        source: &ViewSource,
        request_id: &str,
        error: &DisplayError,
        current_stamp: Option<SourceStamp>,
    ) -> Result<Vec<HistoryIntent>, DisplayError> {
        if !views.request_pending(source, request_id) {
            if self
                .requests
                .get(request_id)
                .is_some_and(|owner| owner_source(owner) == source)
            {
                self.requests.remove(request_id);
            }
            return Ok(Vec::new());
        }
        if self
            .requests
            .get(request_id)
            .is_some_and(|owner| owner_source(owner) != source)
        {
            return Ok(Vec::new());
        }
        self.restore_navigation(views, source, request_id);
        let owner = self
            .requests
            .get(request_id)
            .cloned()
            .unwrap_or_else(|| RequestOwner::Window(source.clone()));
        if *error == DisplayError::Stale {
            self.requests.remove(request_id);
            views.request_failed(source, request_id);
            let request = views.reload_stale(source, current_stamp)?;
            self.requests
                .retain(|id, owner| views.request_pending(owner_source(owner), id));
            self.errors.retain(|owner, _| owner_source(owner) != source);
            let state = self.sources.entry(source.clone()).or_default();
            state.restore = views
                .view(source)
                .filter(|view| !view.position.follow_tail)
                .map(|view| view.position.clone());
            state.heights.clear();
            if self
                .image_popup
                .as_ref()
                .is_some_and(|image| &image.source == source)
            {
                self.image_popup = None;
            }
            self.requests.insert(
                request.request_id.clone(),
                RequestOwner::Window(source.clone()),
            );
            return Ok(vec![HistoryIntent::Window(request)]);
        }
        if let RequestOwner::Body(body, from) = &owner {
            let restored = self
                .sources
                .get(source)
                .and_then(|state| state.restore.as_ref())
                .filter(|position| {
                    position
                        .body_message_id
                        .as_ref()
                        .unwrap_or(&position.message_id)
                        == &body.message_id
                        && position.body_kind == body.kind
                        && position.byte_offset == *from
                })
                .cloned();
            // An edited body can make a persisted offset non-UTF8 even with a
            // fresh hash. One safe page-zero fallback is enough; an Invalid at
            // zero becomes a normal error, not a repeated automatic retry.
            if *from > 0 && matches!(error, DisplayError::Invalid(_)) {
                if let Some(mut position) = restored {
                    self.requests.remove(request_id);
                    self.errors.remove(&owner);
                    views.request_failed(source, request_id);
                    position.byte_offset = 0;
                    position.pixel_offset = 0.;
                    let state = self.sources.entry(source.clone()).or_default();
                    state
                        .body_offsets
                        .insert((body.message_id.clone(), body.kind.clone()), 0);
                    state.heights.remove(&position.message_id);
                    state.restore = Some(position.clone());
                    state.last_saved = None;
                    state.notice = Some("原正文阅读位置已变化，已从本段开头重新读取。".into());
                    views.remember_position(position.clone())?;
                    let request = views.begin_body(body.clone(), 0)?;
                    self.requests.insert(
                        request.request_id.clone(),
                        RequestOwner::Body(body.clone(), 0),
                    );
                    return Ok(vec![
                        HistoryIntent::ReadPosition(position),
                        HistoryIntent::Body(request),
                    ]);
                }
            }
        }
        self.request_failed(views, source, request_id, &error.to_string());
        Ok(Vec::new())
    }
    pub fn edit_finished(&mut self, body: &BodyRef, result: Result<(), String>) {
        if self
            .editor
            .as_ref()
            .is_some_and(|editor| &editor.body == body)
        {
            match result {
                Ok(()) => self.editor = None,
                Err(reason) => {
                    if let Some(editor) = &mut self.editor {
                        editor.error = Some(bounded(&reason, 2048));
                        editor.pending = false;
                    }
                }
            }
        }
    }

    pub(super) fn presented_stamp(&self, source: &ViewSource) -> Option<&SourceStamp> {
        self.sources
            .get(source)
            .and_then(|state| state.presented_stamp.as_ref())
    }
    pub(super) fn navigation_viewport(&self, source: &ViewSource) -> Option<egui::Rect> {
        self.sources.get(source).and_then(|state| state.viewport)
    }
    pub(super) fn navigation_reading(&self, source: &ViewSource) -> Option<&str> {
        self.sources
            .get(source)
            .map(|state| state.navigation_reading.as_str())
            .filter(|id| !id.is_empty())
    }
    pub(super) fn navigation_request_pending(&self, source: &ViewSource, id: &str) -> bool {
        self.sources
            .get(source)
            .and_then(|state| state.navigation.as_ref())
            .is_some_and(|navigation| navigation.request_id == id)
    }
    pub(super) fn set_navigation_interacting(&mut self, source: &ViewSource, interacting: bool) {
        if let Some(state) = self.sources.get_mut(source) {
            state.navigation_interacting = interacting;
        }
    }
    fn restore_navigation(&mut self, views: &mut HistoryViews, source: &ViewSource, id: &str) {
        let Some(state) = self.sources.get_mut(source) else {
            return;
        };
        if !state
            .navigation
            .as_ref()
            .is_some_and(|navigation| navigation.request_id == id)
        {
            return;
        }
        let navigation = state.navigation.take().unwrap();
        state.restore = Some(navigation.previous.clone());
        state.turn_motion = None;
        state.next_scroll = None;
        state.force_tail = false;
        let _ = views.remember_position(navigation.previous);
    }
    pub(super) fn navigate_turn(
        &mut self,
        views: &mut HistoryViews,
        target: crate::display::turn_navigation::TurnTarget,
    ) -> Result<Vec<HistoryIntent>, DisplayError> {
        let view = views.view(&target.source).ok_or(DisplayError::Missing)?;
        if view
            .stamp
            .as_ref()
            .is_none_or(|stamp| stamp.generation != target.stamp.generation)
        {
            return Err(DisplayError::Stale);
        }
        let previous = view.position.clone();
        let navigation_offset = self
            .sources
            .get(&target.source)
            .and_then(|state| state.viewport)
            .map_or(0.0, |viewport| -viewport.height() * 0.28);
        let position = ReadPosition {
            source: target.source.clone(),
            message_id: target.message_id.clone(),
            body_message_id: None,
            footer_from: None,
            body_kind: BodyKind::RowHeader,
            byte_offset: 0,
            pixel_offset: navigation_offset,
            follow_tail: false,
            legacy: None,
        };
        views.remember_position(position.clone())?;
        let request = views.begin_window(&target.source, WindowAt::Turn(target.clone()))?;
        let state = self.sources.entry(target.source.clone()).or_default();
        let previous = state
            .navigation
            .take()
            .map_or(previous, |navigation| navigation.previous);
        state.navigation = Some(PendingNavigation {
            request_id: request.request_id.clone(),
            previous,
            started: Instant::now(),
        });
        state.restore = Some(position);
        state.turn_motion = None;
        state.next_scroll = None;
        state.force_tail = false;
        state.last_saved = None;
        state.presented_stamp = None;
        self.requests.retain(
            |_, owner| !matches!(owner, RequestOwner::Window(source) if source == &target.source),
        );
        self.requests.insert(
            request.request_id.clone(),
            RequestOwner::Window(target.source),
        );
        Ok(vec![HistoryIntent::Window(request)])
    }
    pub(super) fn show(
        &mut self,
        ui: &mut egui::Ui,
        views: &mut HistoryViews,
        source: &ViewSource,
        running: bool,
        editable: bool,
        chrome: Option<(Skin, &Icons)>,
    ) -> Vec<HistoryIntent> {
        let mut intents = Vec::new();
        let interrupt = self.sources.get(source).is_some_and(|state| {
            !state.navigation_interacting
                && state.viewport.is_some_and(|viewport| {
                    ui.input(|input| {
                        (input.pointer.primary_pressed() || input.smooth_scroll_delta.y != 0.0)
                            && input
                                .pointer
                                .interact_pos()
                                .is_some_and(|point| viewport.contains(point))
                    })
                })
        });
        if interrupt {
            if let Some(id) = self
                .sources
                .get(source)
                .and_then(|state| state.navigation.as_ref())
                .map(|navigation| navigation.request_id.clone())
            {
                self.restore_navigation(views, source, &id);
                views.request_failed(source, &id);
                self.requests.remove(&id);
            }
            if let Some(state) = self.sources.get_mut(source) {
                state.turn_motion = None;
                state.restore = None;
                state.next_scroll = None;
            }
        }
        if let Some((id, remaining)) = self
            .sources
            .get(source)
            .and_then(|state| state.navigation.as_ref())
            .map(|navigation| {
                (
                    navigation.request_id.clone(),
                    Duration::from_secs(10).saturating_sub(navigation.started.elapsed()),
                )
            })
        {
            if remaining.is_zero() {
                self.restore_navigation(views, source, &id);
                views.request_failed(source, &id);
                self.requests.remove(&id);
            } else {
                ui.ctx().request_repaint_after(remaining);
            }
        }
        self.requests
            .retain(|id, owner| views.request_pending(owner_source(owner), id));
        let Some(view) = views.view(source) else {
            if let Some(state) = self.sources.get_mut(source) {
                state.presented_stamp = None;
            }
            ui.spinner();
            ui.label("正在读取会话…");
            return intents;
        };
        self.clock = self.clock.saturating_add(1);
        let mut state = self.sources.remove(source).unwrap_or_else(|| LayoutState {
            restore: (!view.position.follow_tail).then(|| view.position.clone()),
            ..Default::default()
        });
        state.touched = self.clock;
        state.presented_stamp = None;
        if state.last_width > 0.0
            && (state.last_width - ui.available_width()).abs() > 1.0
            && !view.position.follow_tail
        {
            state.restore = Some(view.position.clone());
        }
        state.last_width = ui.available_width();
        if let Some(notice) = &state.notice {
            ui.label(RichText::new(notice).small().weak());
        }
        let mut asks = Vec::new();
        let source_error = RequestOwner::Window(source.clone());
        if let Some(error) = self.errors.get(&source_error).cloned() {
            ui.label(RichText::new(error).color(ui.visuals().error_fg_color));
            if ui.button("重新读取").clicked() {
                self.errors.remove(&source_error);
                asks.push(Ask::Window(at_position(&view.position)));
            }
        } else if view.needs_window && !view.window_pending() {
            asks.push(Ask::Window(at_position(&view.position)));
        }
        if view.anchor_missing {
            ui.label(RichText::new("原阅读位置已不在当前候选中，已载入最近记录。").weak());
        }
        if view.rows.is_empty() {
            if view.window_pending() || view.needs_window {
                ui.spinner();
                ui.label("正在读取会话…");
            } else {
                ui.label(RichText::new("此会话还没有消息").weak());
            }
        }
        let desired_restore = state.restore.clone();
        if let Some(position) = &desired_restore {
            state.body_offsets.insert(
                (
                    position
                        .body_message_id
                        .as_ref()
                        .unwrap_or(&position.message_id)
                        .clone(),
                    position.body_kind.clone(),
                ),
                position.byte_offset,
            );
        }
        let mut scroll = egui::ScrollArea::vertical()
            .id_salt(("history", source))
            .auto_shrink([false, false])
            .stick_to_bottom(view.position.follow_tail && self.behavior.auto_follow);
        if let Some(offset) = state.next_scroll.take() {
            scroll = scroll.vertical_scroll_offset(offset);
        }
        let mut body_layouts = Vec::new();
        let mut drawn_offset = 0.0;
        let tail_turn = view.rows.back().and_then(|row| row.turn_id.as_deref());
        let mobile_regenerate = chrome.is_some()
            && view.after.is_none()
            && view.rows.back().is_some_and(|row| {
                row.role == "assistant" && row.complete && row.tool_calls.is_empty()
            });
        let output = scroll.show_viewport(ui, |ui, viewport| {
            drawn_offset = viewport.min.y;
            if let Some(cursor) = &view.before {
                if ui
                    .add_enabled(!view.window_pending(), egui::Button::new("加载更早的消息"))
                    .clicked()
                {
                    asks.push(Ask::Window(WindowAt::Before(cursor.clone())));
                }
            }
            let mut seen_turns = HashSet::new();
            for row in &view.rows {
                let before = ui.cursor().min.y;
                let forced = desired_restore
                    .as_ref()
                    .is_some_and(|position| position.message_id == row.id);
                let width = ui.available_width();
                let first_turn = row
                    .turn_id
                    .as_ref()
                    .is_some_and(|turn| seen_turns.insert(turn.clone()));
                let latest = row.turn_id.as_deref() == tail_turn && view.after.is_none();
                let saved_height = state.heights.get(&row.id).filter(|height| {
                    height.message_hash == row.text.message_hash
                        && (height.width - width).abs() < 1.0
                        && height.first_turn == first_turn
                        && height.running == running
                        && height.latest == latest
                });
                let skip = saved_height.is_some_and(|height| {
                    !forced
                        && !egui::Rect::from_min_size(
                            egui::pos2(ui.cursor().min.x, before),
                            egui::vec2(width, height.points),
                        )
                        .intersects(ui.clip_rect().expand(320.0))
                });
                if skip {
                    ui.add_space(saved_height.map_or(0.0, |height| height.points));
                } else {
                    let navigation = row
                        .turn_id
                        .as_ref()
                        .and_then(|turn| view.candidates.get(turn));
                    let move_regenerate = latest && mobile_regenerate;
                    if first_turn
                        && let Some(candidate) = navigation
                        && (!move_regenerate || candidate.total > 1)
                    {
                        self.candidates(
                            ui,
                            view,
                            candidate,
                            latest && !move_regenerate,
                            editable && !running,
                            chrome,
                            &mut intents,
                        );
                    }
                    let regenerate = navigation.filter(|_| {
                        move_regenerate && view.rows.back().is_some_and(|last| last.id == row.id)
                    });
                    self.row(
                        ui,
                        view,
                        row,
                        &mut state,
                        running,
                        editable,
                        chrome,
                        regenerate,
                        &mut asks,
                        &mut intents,
                        &mut body_layouts,
                    );
                    ui.add_space(8.0);
                    state.heights.insert(
                        row.id.clone(),
                        Height {
                            message_hash: row.text.message_hash.clone(),
                            width,
                            points: (ui.cursor().min.y - before).max(1.0),
                            first_turn,
                            running,
                            latest,
                        },
                    );
                }
            }
            if let Some(cursor) = &view.after {
                if ui
                    .add_enabled(!view.window_pending(), egui::Button::new("加载后续消息"))
                    .clicked()
                {
                    asks.push(Ask::Window(WindowAt::After(cursor.clone())));
                }
            }
        });
        let offset = output.state.clamped_offset().y;
        let maximum = (output.content_size.y - output.inner_rect.height()).max(0.0);
        let interacting = ui.input(|input| {
            input.smooth_scroll_delta.y.abs() > 0.0
                || (input.pointer.any_down() && input.pointer.delta().length() > 2.0)
        });
        if interacting
            && !state.navigation_interacting
            && ui.rect_contains_pointer(output.inner_rect)
        {
            state.restore = None;
            state.turn_motion = None;
            state.next_scroll = None;
        }
        let visual_top = output.inner_rect.top() + offset - drawn_offset;
        state.viewport = Some(output.inner_rect);
        let mut restoring =
            state.force_tail || state.navigation.is_some() || state.navigation_interacting;
        if state.force_tail {
            if (offset - maximum).abs() > 0.75 {
                state.next_scroll = Some(maximum);
                ui.ctx().request_repaint();
            } else if (offset - drawn_offset).abs() <= 0.75 {
                state.force_tail = false;
                restoring = false;
            } else {
                ui.ctx().request_repaint();
            }
        }
        if let Some(position) = state
            .restore
            .as_mut()
            .filter(|position| position.legacy.is_some())
        {
            let bookmark = position.legacy.as_ref().unwrap();
            let first = body_layouts.iter().find(|body| {
                body.kind == BodyKind::RowHeader && body.message_id == position.message_id
            });
            let last = bookmark.native_end_id.as_ref().and_then(|id| {
                body_layouts
                    .iter()
                    .find(|body| body.kind == BodyKind::RowHeader && body.message_id == *id)
            });
            if let Some(first) = first {
                let bottom = last.map_or(first.bottom, |last| last.bottom);
                let ready = body_layouts
                    .iter()
                    .filter(|body| body.top >= first.top && body.top < bottom)
                    .all(|body| body.ready);
                // A read failure must not keep the bookmark permanently pending.
                let failed = self
                    .errors
                    .keys()
                    .any(|owner| owner_source(owner) == source);
                if ready || failed {
                    let font = ui
                        .style()
                        .text_styles
                        .get(&egui::TextStyle::Body)
                        .map_or(15.0, |font| font.size);
                    let offset = crate::legacy_reading::offset(
                        bookmark,
                        ui.ctx().pixels_per_point(),
                        ui.ctx().viewport_rect().width(),
                        font,
                        bottom - first.top,
                        output.inner_rect.height(),
                        ready && last.is_some(),
                    );
                    position.pixel_offset =
                        if offset > bottom - first.top || offset < -output.inner_rect.height() {
                            0.0
                        } else {
                            offset
                        };
                    position.legacy = None;
                }
            }
        }
        if let Some(position) = state
            .restore
            .clone()
            .filter(|position| position.legacy.is_none())
        {
            if let Some(body) = body_layouts.iter().find(|body| {
                body.message_id == position.message_id
                    && body.kind == position.body_kind
                    && body.body_message_id
                        == *position
                            .body_message_id
                            .as_ref()
                            .unwrap_or(&position.message_id)
                    && body.from == position.byte_offset
            }) {
                if body.ready {
                    let alignment = if state.turn_motion.is_some()
                        && position.body_kind == BodyKind::RowHeader
                    {
                        -output.inner_rect.height() * 0.28
                    } else {
                        position.pixel_offset
                    };
                    let target = (drawn_offset + body.top - output.inner_rect.top() + alignment)
                        .clamp(0.0, maximum);
                    let tolerance = 0.5 / ui.ctx().pixels_per_point();
                    if (target - offset).abs() > tolerance {
                        let scale = self.motion_scale.unwrap_or(1.0);
                        let next = if let Some(motion) = &mut state.turn_motion {
                            if scale == 0.0 {
                                Some(target)
                            } else {
                                motion
                                    .step(target - offset, output.inner_rect.height(), scale)
                                    .map(|delta| offset + delta)
                            }
                        } else {
                            Some(target)
                        };
                        if let Some(next) = next {
                            state.next_scroll = Some(next);
                        } else {
                            state.turn_motion = None;
                            state.restore = None;
                        }
                        restoring = true;
                        ui.ctx().request_repaint();
                    } else if (offset - drawn_offset).abs() > tolerance {
                        restoring = true;
                        ui.ctx().request_repaint();
                    } else {
                        state.restore = None;
                        state.turn_motion = None;
                    }
                } else {
                    restoring = true;
                }
            } else if view.rows.iter().any(|row| row.id == position.message_id) {
                // The anchored section was removed or its footer is empty.
                state.restore = None;
                state.turn_motion = None;
            }
        }
        if state
            .restore
            .as_ref()
            .is_some_and(|position| position.legacy.is_some())
        {
            restoring = true;
        }
        if !restoring {
            let probe = visual_top + output.inner_rect.height() * 0.28;
            let row = body_layouts
                .iter()
                .filter(|body| body.kind == BodyKind::RowHeader && body.top <= probe)
                .max_by(|left, right| left.top.total_cmp(&right.top))
                .or_else(|| {
                    body_layouts
                        .iter()
                        .find(|body| body.kind == BodyKind::RowHeader)
                });
            if let Some(row) = row {
                state.navigation_reading.clone_from(&row.message_id);
            }
            let follow_tail = view.after.is_none() && maximum - offset <= 12.0;
            let nearest = body_layouts
                .iter()
                .find(|body| {
                    body.kind != BodyKind::RowHeader
                        && body.top <= visual_top
                        && body.bottom >= visual_top
                })
                .or_else(|| {
                    body_layouts.iter().find(|body| {
                        body.kind == BodyKind::RowHeader
                            && body.top <= visual_top
                            && body.bottom >= visual_top
                    })
                })
                .or_else(|| {
                    body_layouts
                        .iter()
                        .filter(|body| body.top >= visual_top)
                        .min_by(|a, b| a.top.total_cmp(&b.top))
                })
                .or_else(|| body_layouts.last());
            if let Some(body) = nearest {
                let position = ReadPosition {
                    source: source.clone(),
                    message_id: body.message_id.clone(),
                    body_message_id: (body.body_message_id != body.message_id)
                        .then(|| body.body_message_id.clone()),
                    footer_from: body.footer_from,
                    body_kind: body.kind.clone(),
                    byte_offset: body.from,
                    pixel_offset: visual_top - body.top,
                    follow_tail,
                    legacy: None,
                };
                if state
                    .last_saved
                    .as_ref()
                    .is_none_or(|old| !same_position(old, &position))
                {
                    state.last_saved = Some(position.clone());
                    intents.push(HistoryIntent::ReadPosition(position));
                }
            }
        }
        if !view.position.follow_tail && ui.button("回到最新").clicked() {
            asks.push(Ask::Window(WindowAt::Tail));
            let mut position = view.position.clone();
            position.follow_tail = true;
            position.legacy = None;
            intents.push(HistoryIntent::ReadPosition(position));
            state.restore = None;
            state.next_scroll = None;
            state.force_tail = true;
        }
        state
            .heights
            .retain(|id, _| view.rows.iter().any(|row| &row.id == id));
        if state.body_offsets.len() > 512 {
            state
                .body_offsets
                .retain(|(id, _), _| view.rows.iter().any(|row| &row.id == id));
        }
        if state.footer_offsets.len() > 288 {
            state
                .footer_offsets
                .retain(|id, _| view.rows.iter().any(|row| &row.id == id));
        }
        if state.reasoning_manual.len() > 512 {
            state
                .reasoning_manual
                .retain(|id| view.rows.iter().any(|row| &row.id == id));
        }
        if state.body_heights.len() > 512 {
            state.body_heights.clear();
        }
        if let Some(position) = &state.restore {
            if desired_restore
                .as_ref()
                .is_none_or(|old| !same_position(old, position))
            {
                intents.push(HistoryIntent::ReadPosition(position.clone()));
            }
        }
        let visible = output.inner_rect.intersect(ui.clip_rect());
        if !state.force_tail
            && state.restore.is_none()
            && !view.needs_window
            && !view.window_pending()
            && visible.is_positive()
            && body_layouts.iter().any(|body| {
                body.ready
                    && body.kind != BodyKind::RowHeader
                    && body.bottom > visible.top()
                    && body.top < visible.bottom()
            })
        {
            state.presented_stamp = view.stamp.clone();
        }
        self.sources.insert(source.clone(), state);
        while self.sources.len() > MAX_RENDER_SOURCES {
            let oldest = self
                .sources
                .iter()
                .filter(|(candidate, _)| *candidate != source)
                .min_by_key(|(_, state)| state.touched)
                .map(|(source, _)| source.clone());
            let Some(oldest) = oldest else {
                break;
            };
            self.sources.remove(&oldest);
        }
        self.dialogs(
            ui.ctx(),
            views,
            source,
            running,
            editable,
            &mut asks,
            &mut intents,
        );
        for intent in &intents {
            if let HistoryIntent::ReadPosition(position) = intent {
                let _ = views.remember_position(position.clone());
            }
        }
        for ask in asks {
            if let Ask::Body(body, from) = &ask {
                let anchored = desired_restore.as_ref().is_some_and(|position| {
                    position
                        .body_message_id
                        .as_ref()
                        .unwrap_or(&position.message_id)
                        == &body.message_id
                        && position.body_kind == body.kind
                });
                let visible = body_layouts.iter().any(|layout| {
                    layout.body_message_id == body.message_id
                        && layout.kind == body.kind
                        && layout.from == *from
                        && layout.bottom >= visual_top - 320.0
                        && layout.top <= visual_top + output.inner_rect.height() + 320.0
                });
                let tail = views
                    .view(source)
                    .and_then(|view| view.rows.back())
                    .is_some_and(|row| row.id == body.message_id);
                if !anchored && !visible && !(restoring && tail) {
                    continue;
                }
            }
            self.materialize(views, source, ask, &mut intents);
        }
        intents
    }

    fn candidates(
        &mut self,
        ui: &mut egui::Ui,
        view: &SourceView,
        navigation: &CandidateNavigation,
        latest: bool,
        editable: bool,
        chrome: Option<(Skin, &Icons)>,
        intents: &mut Vec<HistoryIntent>,
    ) {
        let Some(stamp) = &view.stamp else {
            return;
        };
        ui.horizontal_wrapped(|ui| {
            if navigation.total > 1 {
                ui.label(
                    RichText::new(format!(
                        "候选回答 {} / {}",
                        navigation.position + 1,
                        navigation.total
                    ))
                    .small()
                    .weak(),
                );
                let mut selected = None;
                if ui
                    .add_enabled(
                        editable && navigation.previous.is_some(),
                        egui::Button::new("上一份").small(),
                    )
                    .clicked()
                {
                    selected = navigation.previous.clone();
                }
                if ui
                    .add_enabled(
                        editable && navigation.next.is_some(),
                        egui::Button::new("下一份").small(),
                    )
                    .clicked()
                {
                    selected = navigation.next.clone();
                }
                if let Some(run_id) = selected {
                    intents.push(HistoryIntent::ReadPosition(ReadPosition {
                        source: view.source.clone(),
                        message_id: navigation.turn_id.clone(),
                        body_message_id: None,
                        footer_from: None,
                        body_kind: BodyKind::Text,
                        byte_offset: 0,
                        pixel_offset: 0.0,
                        follow_tail: false,
                        legacy: None,
                    }));
                    intents.push(HistoryIntent::SelectCandidate {
                        source: view.source.clone(),
                        stamp: stamp.clone(),
                        user_id: navigation.turn_id.clone(),
                        run_id,
                    });
                }
            }
            if latest && transcript_action(ui, chrome, Icon::Regenerate, "重新回答", editable) {
                intents.push(HistoryIntent::Regenerate {
                    source: view.source.clone(),
                    stamp: stamp.clone(),
                    question_id: navigation.turn_id.clone(),
                });
            }
        });
    }
    fn row(
        &mut self,
        ui: &mut egui::Ui,
        view: &SourceView,
        row: &DisplayRow,
        state: &mut LayoutState,
        running: bool,
        editable: bool,
        chrome: Option<(Skin, &Icons)>,
        regenerate: Option<&CandidateNavigation>,
        asks: &mut Vec<Ask>,
        intents: &mut Vec<HistoryIntent>,
        layouts: &mut Vec<BodyLayout>,
    ) {
        let row_top = ui.cursor().min.y;
        state.current_row = row.id.clone();
        ui.push_id((&view.source, &row.id), |ui| {
            let mobile_user = chrome.is_some() && row.role == "user";
            let layout = if mobile_user {
                egui::Layout::top_down(egui::Align::Max)
            } else {
                *ui.layout()
            };
            if let Some((skin, _)) = chrome {
                ui.spacing_mut().item_spacing.y = 5.0;
                ui.style_mut()
                    .text_styles
                    .insert(egui::TextStyle::Body, skin.font(15.0));
                if mobile_user
                    && self.behavior.show_timestamps
                    && let Some(timestamp) = &row.info.timestamp_label
                {
                    ui.vertical_centered(|ui| {
                        ui.label(
                            RichText::new(timestamp)
                                .font(skin.font(12.0))
                                .color(skin.ink),
                        )
                    });
                }
            }
            ui.with_layout(layout, |ui| {
                let fill = if row.role == "user" {
                    chrome.map_or(ui.visuals().faint_bg_color, |(skin, _)| skin.user_bubble)
                } else {
                    ui.visuals().panel_fill
                };
                let flat = row.role == "assistant" && !self.behavior.assistant_card;
                let width = if let Some((skin, _)) = chrome.filter(|_| mobile_user) {
                    let max = (ui.available_width() - 32.0).clamp(0.0, 268.0);
                    row.preview
                        .as_ref()
                        .filter(|preview| preview.text.len() < 240 && row.images.is_empty())
                        .map_or(max, |preview| {
                            ui.painter()
                                .layout_no_wrap(preview.text.clone(), skin.font(15.0), skin.text)
                                .size()
                                .x
                                .clamp(1.0, max.max(1.0))
                        })
                } else {
                    ui.available_width() * self.behavior.bubble_fraction()
                };
                let corner = match self.behavior.corner_scale {
                    0 => 6,
                    2 => 15,
                    _ => 10,
                };
                egui::Frame::NONE
                    .fill(if flat {
                        egui::Color32::TRANSPARENT
                    } else {
                        fill
                    })
                    .corner_radius(if flat {
                        0
                    } else if mobile_user {
                        24
                    } else {
                        corner
                    })
                    .inner_margin(if flat {
                        egui::Margin::same(self.behavior.flat_padding_dp() as i8)
                    } else if mobile_user {
                        egui::Margin::symmetric(16, 12)
                    } else {
                        egui::Margin::same(12)
                    })
                    .show(ui, |ui| {
                        ui.set_max_width(width);
                        ui.set_min_width(width);
                        let role = match row.role.as_str() {
                            "user" => {
                                if row.steer {
                                    "你的补充"
                                } else {
                                    "你"
                                }
                            }
                            "assistant" => self
                                .assistant_faces
                                .get(&view.source)
                                .map(|face| face.name.as_str())
                                .filter(|name| !name.is_empty())
                                .unwrap_or("助手"),
                            "system" => "系统",
                            "tool" => "工具",
                            other => other,
                        };
                        if !mobile_user
                            || row.steer
                            || row.info.failed_model_attempt
                            || row.tool_name.is_some()
                            || row.info.tool_ok.is_some()
                        {
                            ui.horizontal_wrapped(|ui| {
                                if row.role == "assistant" {
                                    if let Some(face) = self.assistant_faces.get(&view.source) {
                                        face.draw(ui, if chrome.is_some() { 28.0 } else { 24.0 });
                                    }
                                }
                                if !mobile_user || row.steer {
                                    let label = RichText::new(role);
                                    ui.label(if let Some((skin, _)) = chrome {
                                        label.font(skin.font(15.0)).color(skin.ink)
                                    } else {
                                        label.small().strong()
                                    });
                                }
                                if row.info.failed_model_attempt {
                                    ui.label(
                                        RichText::new("失败尝试 · 部分内容已保留")
                                            .small()
                                            .color(ui.visuals().error_fg_color),
                                    );
                                }
                                if chrome.is_none()
                                    && self.behavior.show_timestamps
                                    && row.info.timestamp > 0
                                {
                                    ui.label(
                                        RichText::new(
                                            row.info.timestamp_label.clone().unwrap_or_else(|| {
                                                format!("{}（Unix 毫秒）", row.info.timestamp)
                                            }),
                                        )
                                        .small()
                                        .weak(),
                                    );
                                }
                                if let Some(name) = &row.tool_name {
                                    ui.label(RichText::new(name).small().weak());
                                }
                                if let Some(ok) = row.info.tool_ok {
                                    ui.label(
                                        RichText::new(if ok {
                                            "已完成"
                                        } else {
                                            "执行失败"
                                        })
                                        .small(),
                                    );
                                }
                            });
                        }
                        if let Some(note) = &row.info.trace_note {
                            egui::CollapsingHeader::new("执行说明")
                                .id_salt("trace")
                                .default_open(false)
                                .open(restore_open(state, note))
                                .show(ui, |ui| {
                                    self.body(
                                        ui, view, note, None, state, false, false, chrome, asks,
                                        layouts,
                                    );
                                });
                        }
                        for call in &row.tool_calls {
                            egui::CollapsingHeader::new(format!("工具：{}", call.name))
                                .id_salt(("call", &call.id, self.behavior.expand_tools))
                                .default_open(self.behavior.expand_tools)
                                .open(restore_open(state, &call.arguments))
                                .show(ui, |ui| {
                                    self.body(
                                        ui,
                                        view,
                                        &call.arguments,
                                        None,
                                        state,
                                        false,
                                        true,
                                        chrome,
                                        asks,
                                        layouts,
                                    );
                                });
                        }
                        if self.behavior.show_reasoning && row.thinking.bytes > 0 {
                            let streaming =
                                running && !row.complete && !row.info.failed_model_attempt;
                            let default_open = streaming || self.behavior.expand_reasoning;
                            let open = restore_open(state, &row.thinking).or_else(|| {
                                (!state.reasoning_manual.contains(&row.id)).then_some(default_open)
                            });
                            let response = egui::CollapsingHeader::new("思考过程")
                                .id_salt("thinking")
                                .default_open(default_open)
                                .open(open)
                                .show(ui, |ui| {
                                    egui::ScrollArea::vertical()
                                        .id_salt((
                                            "reasoning-body",
                                            &body_source_key(&row.thinking),
                                        ))
                                        .max_height(self.behavior.reasoning_height_dp() as f32)
                                        .stick_to_bottom(
                                            self.behavior.follow_reasoning
                                                && running
                                                && !row.complete
                                                && !row.info.failed_model_attempt,
                                        )
                                        .show(ui, |ui| {
                                            self.body(
                                                ui,
                                                view,
                                                &row.thinking,
                                                None,
                                                state,
                                                running
                                                    && !row.complete
                                                    && !row.info.failed_model_attempt,
                                                false,
                                                chrome,
                                                asks,
                                                layouts,
                                            );
                                        });
                                });
                            if response.header_response.clicked() {
                                state.reasoning_manual.insert(row.id.clone());
                            }
                        }
                        if row.role == "tool" {
                            if let Some(arguments) = &row.tool_arguments {
                                egui::CollapsingHeader::new("调用参数")
                                    .id_salt(("tool-args", self.behavior.expand_tools))
                                    .default_open(self.behavior.expand_tools)
                                    .open(restore_open(state, arguments))
                                    .show(ui, |ui| {
                                        self.body(
                                            ui, view, arguments, None, state, false, true, chrome,
                                            asks, layouts,
                                        );
                                    });
                            }
                            let output = row.tool_output.as_ref().unwrap_or(&row.text);
                            if output.bytes > 0 {
                                egui::CollapsingHeader::new("工具结果")
                                    .id_salt(("tool-output", self.behavior.expand_tools))
                                    .default_open(self.behavior.expand_tools)
                                    .open(restore_open(state, output))
                                    .show(ui, |ui| {
                                        self.body(
                                            ui,
                                            view,
                                            output,
                                            row.preview.as_ref(),
                                            state,
                                            false,
                                            true,
                                            chrome,
                                            asks,
                                            layouts,
                                        );
                                    });
                            }
                        } else if row.text.bytes > 0 {
                            self.body(
                                ui,
                                view,
                                &row.text,
                                row.preview.as_ref(),
                                state,
                                running && !row.complete && !row.info.failed_model_attempt,
                                mobile_user,
                                chrome,
                                asks,
                                layouts,
                            );
                        } else {
                            let top = ui.cursor().min.y;
                            if !row.complete {
                                ui.label(
                                    RichText::new(if running {
                                        "等待回复…"
                                    } else {
                                        "本轮已结束，保留已接收记录"
                                    })
                                    .weak(),
                                );
                            }
                            layouts.push(BodyLayout {
                                message_id: row.id.clone(),
                                body_message_id: row.id.clone(),
                                footer_from: None,
                                kind: BodyKind::Text,
                                from: 0,
                                top,
                                bottom: ui.cursor().min.y.max(top + 20.0),
                                ready: true,
                            });
                        }
                        if !row.images.is_empty() {
                            egui::CollapsingHeader::new(format!("图片 · {} 张", row.images.len()))
                                .id_salt("images")
                                .default_open(false)
                                .open(
                                    state
                                        .restore
                                        .as_ref()
                                        .filter(|position| {
                                            position.message_id == row.id
                                                && matches!(
                                                    position.body_kind,
                                                    BodyKind::ImageBlock(_)
                                                )
                                        })
                                        .map(|_| true),
                                )
                                .show(ui, |ui| {
                                    for (slot, image) in row.images.iter().enumerate() {
                                        let top = ui.cursor().min.y;
                                        self.image(ui, view, image, asks);
                                        layouts.push(BodyLayout {
                                            message_id: row.id.clone(),
                                            body_message_id: row.id.clone(),
                                            footer_from: None,
                                            kind: BodyKind::ImageBlock(slot as u16),
                                            from: 0,
                                            top,
                                            bottom: ui.cursor().min.y.max(top + 20.0),
                                            ready: self.textures.contains_key(image),
                                        });
                                    }
                                });
                        }
                        if !running
                            && row.complete
                            && row.role == "assistant"
                            && row.tool_calls.is_empty()
                        {
                            self.footer(ui, view, row, state, asks, intents, layouts);
                        }
                        if !mobile_user && !running && row.complete && row.tool_calls.is_empty() {
                            ui.horizontal_wrapped(|ui| {
                                if transcript_action(ui, chrome, Icon::Copy, "复制", true) {
                                    intents.push(HistoryIntent::CopyBody(row.text.clone()));
                                }
                                if row.role == "assistant"
                                    && row.text.bytes > 0
                                    && (chrome.is_none() || self.reply_audio_enabled)
                                    && ui
                                        .add_enabled(
                                            self.reply_audio_enabled
                                                && row.text.bytes <= 256 * 1024,
                                            egui::Button::new("朗读").small(),
                                        )
                                        .clicked()
                                {
                                    intents.push(HistoryIntent::SpeakReply(row.text.clone()));
                                }
                                let offset = body_offset(state, &row.text, &view.position);
                                let page = view.body_page(&row.text, offset).or_else(|| {
                                    row.preview.as_ref().filter(|page| {
                                        page.from_byte == offset && page.to_byte == row.text.bytes
                                    })
                                });
                                if ["user", "assistant"].contains(&row.role.as_str())
                                    && transcript_action(
                                        ui,
                                        chrome,
                                        Icon::Edit,
                                        "修改",
                                        editable && page.is_some(),
                                    )
                                {
                                    if let Some(page) = page {
                                        self.editor = Some(EditDraft {
                                            body: row.text.clone(),
                                            from: page.from_byte,
                                            to: page.to_byte,
                                            text: page.text.clone(),
                                            error: None,
                                            pending: false,
                                            resend_allowed: row.role == "user" && !row.steer,
                                        });
                                    }
                                }
                                if row.info.edited {
                                    ui.label(RichText::new("已修改").small().weak());
                                }
                                if let Some(candidate) = regenerate
                                    && let Some(stamp) = &view.stamp
                                    && transcript_action(
                                        ui,
                                        chrome,
                                        Icon::Regenerate,
                                        "重新回答",
                                        editable,
                                    )
                                {
                                    intents.push(HistoryIntent::Regenerate {
                                        source: view.source.clone(),
                                        stamp: stamp.clone(),
                                        question_id: candidate.turn_id.clone(),
                                    });
                                }
                                if row.info.elapsed_ms > 0 {
                                    ui.label(
                                        RichText::new(format!(
                                            "{:.1} 秒",
                                            row.info.elapsed_ms as f64 / 1000.0
                                        ))
                                        .small()
                                        .weak(),
                                    );
                                }
                                if let Some((input, output)) =
                                    row.info.prompt_tokens.zip(row.info.completion_tokens)
                                {
                                    ui.label(
                                        RichText::new(format!("输入 {input} · 输出 {output}"))
                                            .small()
                                            .weak(),
                                    );
                                } else if row.role == "assistant" {
                                    ui.label(RichText::new("用量未报告").small().weak());
                                }
                            });
                        }
                    });
                if mobile_user && !running && row.complete && row.tool_calls.is_empty() {
                    ui.horizontal(|ui| {
                        let offset = body_offset(state, &row.text, &view.position);
                        let page = view.body_page(&row.text, offset).or(row.preview.as_ref());
                        if transcript_action(
                            ui,
                            chrome,
                            Icon::Edit,
                            "修改",
                            editable && page.is_some(),
                        ) && let Some(page) = page
                        {
                            self.editor = Some(EditDraft {
                                body: row.text.clone(),
                                from: page.from_byte,
                                to: page.to_byte,
                                text: page.text.clone(),
                                error: None,
                                pending: false,
                                resend_allowed: !row.steer,
                            });
                        }
                        if transcript_action(ui, chrome, Icon::Copy, "复制", true) {
                            intents.push(HistoryIntent::CopyBody(row.text.clone()));
                        }
                    });
                }
            });
        });
        layouts.push(BodyLayout {
            message_id: row.id.clone(),
            body_message_id: row.id.clone(),
            footer_from: None,
            kind: BodyKind::RowHeader,
            from: 0,
            top: row_top,
            bottom: ui.cursor().min.y.max(row_top + 20.0),
            ready: true,
        });
    }
    fn body(
        &mut self,
        ui: &mut egui::Ui,
        view: &SourceView,
        body: &BodyRef,
        preview: Option<&BodyPage>,
        state: &mut LayoutState,
        streaming: bool,
        plain: bool,
        chrome: Option<(Skin, &Icons)>,
        asks: &mut Vec<Ask>,
        layouts: &mut Vec<BodyLayout>,
    ) {
        let offset = body_offset(state, body, &view.position);
        if state.restore.as_ref().is_some_and(|position| {
            position
                .body_message_id
                .as_ref()
                .unwrap_or(&position.message_id)
                == &body.message_id
                && position.body_kind == body.kind
                && position.byte_offset > body.bytes
        }) {
            if let Some(position) = &mut state.restore {
                position.byte_offset = 0;
                position.pixel_offset = 0.;
            }
            state
                .body_offsets
                .insert((body.message_id.clone(), body.kind.clone()), 0);
            state.notice = Some("原正文阅读位置已超出当前内容，已从本段开头重新读取。".into());
        }
        let page = view.body_page(body, offset);
        let shown =
            page.or_else(|| preview.filter(|page| page.body == *body && page.from_byte == offset));
        let top = ui.cursor().min.y;
        let height_key = (body.message_id.clone(), body.kind.clone(), offset);
        let saved_height = state.body_heights.get(&height_key).filter(|height| {
            height.message_hash == body.message_hash
                && (height.width - ui.available_width()).abs() < 1.0
        });
        let forced = restore_open(state, body).is_some();
        if let Some(height) = saved_height.filter(|height| {
            !forced
                && !egui::Rect::from_min_size(
                    egui::pos2(ui.cursor().min.x, top),
                    egui::vec2(ui.available_width(), height.points),
                )
                .intersects(ui.clip_rect().expand(320.0))
        }) {
            // Keep an earlier large page's geometry when it was evicted while
            // restoring a later folded section in the same message card.
            ui.add_space(height.points);
            layouts.push(BodyLayout {
                message_id: state.current_row.clone(),
                body_message_id: body.message_id.clone(),
                footer_from: matches!(body.kind, BodyKind::FileActivityDescription(_)).then(|| {
                    state
                        .footer_offsets
                        .get(&state.current_row)
                        .copied()
                        .unwrap_or(0)
                }),
                kind: body.kind.clone(),
                from: offset,
                top,
                bottom: top + height.points,
                ready: page.is_some() || shown.is_some_and(|page| page.to_byte == body.bytes),
            });
            return;
        }
        let owner = RequestOwner::Body(body.clone(), offset);
        if page.is_none()
            && shown.is_none_or(|page| page.to_byte < body.bytes)
            && !view.body_pending(body)
            && !self.errors.contains_key(&owner)
        {
            asks.push(Ask::Body(body.clone(), offset));
        }
        if let Some(page) = shown {
            let revealed = self.typewriter.prefix(
                (
                    body.source.clone(),
                    body.message_id.clone(),
                    body.kind.clone(),
                    page.from_byte,
                ),
                &page.text,
                self.behavior.typewriter && streaming && body.kind == BodyKind::Text,
            );
            if revealed < page.text.len() {
                ui.ctx().request_repaint_after(Duration::from_millis(16));
            }
            let visible_text = &page.text[..revealed];
            if plain {
                ui.add(egui::Label::new(&page.text).wrap().selectable(true));
            } else {
                self.markdown.show(
                    ui,
                    (
                        &body.source,
                        &body.message_id,
                        &body.kind,
                        page.from_byte,
                        &body.message_hash,
                    ),
                    visible_text,
                    streaming || revealed < page.text.len(),
                    chrome,
                );
            }
        } else if body.bytes == 0 {
            ui.add_space(1.0);
        } else {
            ui.spinner();
            ui.label("正在读取内容…");
        }
        let bottom = ui.cursor().min.y.max(top + 20.0);
        layouts.push(BodyLayout {
            message_id: state.current_row.clone(),
            body_message_id: body.message_id.clone(),
            footer_from: matches!(body.kind, BodyKind::FileActivityDescription(_)).then(|| {
                state
                    .footer_offsets
                    .get(&state.current_row)
                    .copied()
                    .unwrap_or(0)
            }),
            kind: body.kind.clone(),
            from: offset,
            top,
            bottom,
            ready: page.is_some()
                || shown.is_some_and(|page| page.to_byte == body.bytes)
                || body.bytes == 0,
        });
        if let Some(error) = self.errors.get(&owner).cloned() {
            ui.label(RichText::new(error).color(ui.visuals().error_fg_color));
            if ui.small_button("重试读取").clicked() {
                self.errors.remove(&owner);
                asks.push(Ask::Body(body.clone(), offset));
            }
            if offset > 0 && ui.small_button("从头读取").clicked() {
                set_body_offset(state, body, 0);
                asks.push(Ask::Body(body.clone(), 0));
            }
        }
        if let Some(page) = shown {
            if page.previous_byte.is_some() || page.next_byte.is_some() {
                ui.horizontal_wrapped(|ui| {
                    if ui
                        .add_enabled(
                            page.previous_byte.is_some(),
                            egui::Button::new("上一段").small(),
                        )
                        .clicked()
                    {
                        if let Some(offset) = page.previous_byte {
                            set_body_offset(state, body, offset);
                            asks.push(Ask::Body(body.clone(), offset));
                        }
                    }
                    ui.label(
                        RichText::new(format!(
                            "{}–{} / {}",
                            page.from_byte, page.to_byte, body.bytes
                        ))
                        .small()
                        .weak(),
                    );
                    if ui
                        .add_enabled(
                            page.next_byte.is_some(),
                            egui::Button::new("下一段").small(),
                        )
                        .clicked()
                    {
                        if let Some(offset) = page.next_byte {
                            set_body_offset(state, body, offset);
                            asks.push(Ask::Body(body.clone(), offset));
                        }
                    }
                    if ui.small_button("复制本段").clicked() {
                        ui.ctx().copy_text(page.text.clone());
                    }
                });
            }
        }
        if page.is_some() || shown.is_some_and(|page| page.to_byte == body.bytes) {
            state.body_heights.insert(
                height_key,
                BodyHeight {
                    message_hash: body.message_hash.clone(),
                    width: ui.available_width(),
                    points: (ui.cursor().min.y - top).max(1.0),
                },
            );
        }
    }
    fn image(
        &mut self,
        ui: &mut egui::Ui,
        view: &SourceView,
        image: &ImageRef,
        asks: &mut Vec<Ask>,
    ) {
        if let Some(texture) = self.textures.get_mut(image) {
            texture.touched = self.clock;
            let scale = (ui.available_width().min(384.0) / texture.width as f32).min(1.0);
            let size = egui::vec2(texture.width as f32 * scale, texture.height as f32 * scale);
            if ui
                .add(egui::Image::new((texture.handle.id(), size)).sense(egui::Sense::click()))
                .clicked()
            {
                self.image_popup = Some(image.clone());
                asks.push(Ask::Image(image.clone(), 2160));
            }
        } else {
            let owner = RequestOwner::Image(image.clone(), 512);
            if let Some(error) = self.errors.get(&owner).cloned() {
                ui.label(RichText::new(error).color(ui.visuals().error_fg_color));
                if ui.small_button("重新读取图片").clicked() {
                    self.errors.remove(&owner);
                    asks.push(Ask::Image(image.clone(), 512));
                }
            } else {
                ui.spinner();
                if !view.image_pending(image) {
                    asks.push(Ask::Image(image.clone(), 512));
                }
            }
        }
    }
    fn footer(
        &mut self,
        ui: &mut egui::Ui,
        view: &SourceView,
        row: &DisplayRow,
        state: &mut LayoutState,
        asks: &mut Vec<Ask>,
        intents: &mut Vec<HistoryIntent>,
        layouts: &mut Vec<BodyLayout>,
    ) {
        let from = state.footer_offsets.get(&row.id).copied().unwrap_or(0);
        let page = view.footer(&row.text, from);
        if page.is_none() {
            let top = ui.cursor().min.y;
            let owner = RequestOwner::Footer(row.text.clone(), from);
            if let Some(error) = self.errors.get(&owner).cloned() {
                ui.label(RichText::new(error).color(ui.visuals().error_fg_color));
                if ui.small_button("重新读取文件变更").clicked() {
                    self.errors.remove(&owner);
                    asks.push(Ask::Footer(row.text.clone(), from));
                }
                if from > 0 && ui.small_button("从第一项读取").clicked() {
                    state.footer_offsets.insert(row.id.clone(), 0);
                    if let Some(position) = &mut state.restore {
                        position.footer_from = Some(0);
                    }
                    asks.push(Ask::Footer(row.text.clone(), 0));
                }
            } else {
                ui.spinner();
                if !view.footer_pending(&row.text, from) {
                    asks.push(Ask::Footer(row.text.clone(), from));
                }
            }
            if state.restore.as_ref().is_some_and(|position| {
                position.message_id == row.id && position.footer_from.is_some()
            }) {
                layouts.push(BodyLayout {
                    message_id: row.id.clone(),
                    body_message_id: row.id.clone(),
                    footer_from: Some(from),
                    kind: BodyKind::FooterHeader,
                    from: 0,
                    top,
                    bottom: ui.cursor().min.y.max(top + 20.0),
                    ready: false,
                });
            }
            return;
        }
        if page.is_some_and(|page| page.total == 0) {
            return;
        }
        let title = page.map_or_else(
            || "文件变更".into(),
            |page| format!("文件变更 · {} 个文件", page.total),
        );
        let footer_top = ui.cursor().min.y;
        let response = egui::CollapsingHeader::new(title)
            .id_salt("footer")
            .default_open(false)
            .open(
                state
                    .restore
                    .as_ref()
                    .filter(|position| {
                        position.message_id == row.id && position.footer_from.is_some()
                    })
                    .map(|_| true),
            )
            .show(ui, |ui| {
                if let Some(page) = page {
                    for activity in &page.activities {
                        let kind = match activity.kind {
                            crate::chat::FileActivityKind::Created => "新建",
                            crate::chat::FileActivityKind::Modified => "修改",
                            crate::chat::FileActivityKind::Downloaded => "下载",
                            crate::chat::FileActivityKind::Delivered => "交付",
                            crate::chat::FileActivityKind::Deleted => "删除",
                            crate::chat::FileActivityKind::Moved => "移动",
                        };
                        egui::CollapsingHeader::new(format!("{kind} · {}", activity.path))
                            .id_salt((&activity.path, page.from))
                            .default_open(false)
                            .open(
                                activity
                                    .description
                                    .as_ref()
                                    .and_then(|body| restore_open(state, body)),
                            )
                            .show(ui, |ui| {
                                if let Some(previous) = &activity.previous_path {
                                    ui.label(format!("原路径：{previous}"));
                                }
                                ui.horizontal_wrapped(|ui| {
                                    if let Some(added) = activity.added_lines {
                                        ui.label(format!("+{added}"));
                                    }
                                    if let Some(removed) = activity.removed_lines {
                                        ui.label(format!("−{removed}"));
                                    }
                                    if let Some(size) = activity.size_bytes {
                                        ui.label(format!("{size} 字节"));
                                    }
                                    if activity.kind != crate::chat::FileActivityKind::Deleted
                                        && ui.small_button("查看文件").clicked()
                                    {
                                        intents.push(HistoryIntent::OpenFile {
                                            source: view.source.clone(),
                                            path: activity.path.clone(),
                                        });
                                    }
                                    if activity.kind != crate::chat::FileActivityKind::Deleted {
                                        for (kind, label) in [
                                            (crate::file_transfer::Kind::Open, "用其它应用打开"),
                                            (crate::file_transfer::Kind::Share, "分享"),
                                            (crate::file_transfer::Kind::Save, "保存到设备"),
                                        ] {
                                            if ui.small_button(label).clicked() {
                                                intents.push(HistoryIntent::TransferFile {
                                                    source: view.source.clone(),
                                                    path: activity.path.clone(),
                                                    kind,
                                                });
                                            }
                                        }
                                    }
                                });
                                if let Some(description) = &activity.description {
                                    self.body(
                                        ui,
                                        view,
                                        description,
                                        None,
                                        state,
                                        false,
                                        true,
                                        None,
                                        asks,
                                        layouts,
                                    );
                                }
                            });
                    }
                    ui.horizontal_wrapped(|ui| {
                        if ui
                            .add_enabled(from > 0, egui::Button::new("从第一项查看").small())
                            .clicked()
                        {
                            state.footer_offsets.insert(row.id.clone(), 0);
                        }
                        if let Some(next) = page.next {
                            if ui.small_button("更多文件").clicked() {
                                state.footer_offsets.insert(row.id.clone(), next);
                                state.heights.remove(&row.id);
                                state.restore = Some(ReadPosition {
                                    source: view.source.clone(),
                                    message_id: row.id.clone(),
                                    body_message_id: None,
                                    footer_from: Some(next),
                                    body_kind: BodyKind::FooterHeader,
                                    byte_offset: 0,
                                    pixel_offset: 0.0,
                                    follow_tail: false,
                                    legacy: None,
                                });
                                asks.push(Ask::Footer(row.text.clone(), next));
                            }
                        }
                    });
                } else {
                    let owner = RequestOwner::Footer(row.text.clone(), from);
                    if let Some(error) = self.errors.get(&owner).cloned() {
                        ui.label(RichText::new(error).color(ui.visuals().error_fg_color));
                        if ui.small_button("重新读取文件变更").clicked() {
                            self.errors.remove(&owner);
                            asks.push(Ask::Footer(row.text.clone(), from));
                        }
                        if from > 0 && ui.small_button("从第一项读取").clicked() {
                            state.footer_offsets.insert(row.id.clone(), 0);
                            if let Some(position) = &mut state.restore {
                                position.footer_from = Some(0);
                            }
                            asks.push(Ask::Footer(row.text.clone(), 0));
                        }
                    } else {
                        ui.spinner();
                        if !view.footer_pending(&row.text, from) {
                            asks.push(Ask::Footer(row.text.clone(), from));
                        }
                    }
                }
            });
        if response.body_response.is_some() {
            layouts.push(BodyLayout {
                message_id: row.id.clone(),
                body_message_id: row.id.clone(),
                footer_from: Some(from),
                kind: BodyKind::FooterHeader,
                from: 0,
                top: footer_top,
                bottom: ui.cursor().min.y.max(footer_top + 20.0),
                ready: page.is_some(),
            });
        }
    }
    fn dialogs(
        &mut self,
        ctx: &egui::Context,
        views: &HistoryViews,
        source: &ViewSource,
        running: bool,
        editable: bool,
        asks: &mut Vec<Ask>,
        intents: &mut Vec<HistoryIntent>,
    ) {
        if self.image_popup.as_ref().is_some_and(|image| {
            views
                .view(&image.source)
                .and_then(|view| view.stamp.as_ref())
                != Some(&image.stamp)
        }) {
            self.image_popup = None;
        }
        if let Some(image) = self.image_popup.clone() {
            if &image.source == source {
                let mut open = true;
                egui::Window::new("图片预览")
                    .id(egui::Id::unique(("history-image", &image)))
                    .open(&mut open)
                    .resizable(true)
                    .show(ctx, |ui| {
                        if let Some(texture) = self.textures.get_mut(&image) {
                            texture.touched = self.clock;
                            let scale =
                                (ui.available_width().max(1.0) / texture.width as f32).min(1.0);
                            egui::ScrollArea::both().show(ui, |ui| {
                                ui.add(egui::Image::new((
                                    texture.handle.id(),
                                    egui::vec2(
                                        texture.width as f32 * scale,
                                        texture.height as f32 * scale,
                                    ),
                                )));
                            });
                        } else {
                            ui.spinner();
                        }
                        let owner = RequestOwner::Image(image.clone(), 2160);
                        if let Some(error) = self.errors.get(&owner).cloned() {
                            ui.label(error);
                            if ui.button("重新读取原图预览").clicked() {
                                self.errors.remove(&owner);
                                asks.push(Ask::Image(image.clone(), 2160));
                            }
                        }
                    });
                if !open {
                    self.image_popup = None;
                }
            }
        }
        if let Some(editor) = &mut self.editor {
            if &editor.body.source == source {
                let mut open = true;
                egui::Window::new("修改消息")
                    .id(egui::Id::unique(("history-edit", source)))
                    .open(&mut open)
                    .resizable(true)
                    .show(ctx, |ui| {
                        let current = views.view(source).and_then(|view| view.stamp.as_ref())
                            == Some(&editor.body.stamp);
                        if editor.from > 0 || editor.to < editor.body.bytes {
                            ui.label("只替换当前显示的内容，消息的其余部分会保留。");
                        }
                        ui.add_enabled(
                            !editor.pending && current && !running,
                            egui::TextEdit::multiline(&mut editor.text)
                                .char_limit(crate::display::MAX_BODY_PAGE_BYTES)
                                .desired_width(f32::INFINITY)
                                .desired_rows(12),
                        );
                        if editor.text.len() > crate::display::MAX_BODY_PAGE_BYTES {
                            ui.label(
                                RichText::new("当前内容超过编辑大小上限")
                                    .color(ui.visuals().error_fg_color),
                            );
                        }
                        if !current {
                            ui.label("消息已变化，请重新读取后再修改。");
                        }
                        if let Some(error) = &editor.error {
                            ui.label(RichText::new(error).color(ui.visuals().error_fg_color));
                        }
                        let enabled = editable
                            && !editor.pending
                            && !running
                            && current
                            && editor.text.len() <= crate::display::MAX_BODY_PAGE_BYTES;
                        ui.horizontal(|ui| {
                            if ui
                                .add_enabled(enabled, egui::Button::new("保存修改"))
                                .clicked()
                            {
                                editor.pending = true;
                                intents.push(HistoryIntent::EditResend {
                                    body: editor.body.clone(),
                                    from_byte: editor.from,
                                    to_byte: editor.to,
                                    replacement: editor.text.clone(),
                                    resend: false,
                                });
                            }
                            if ui
                                .add_enabled(
                                    enabled && editor.resend_allowed,
                                    egui::Button::new("保存并重新回答"),
                                )
                                .clicked()
                            {
                                editor.pending = true;
                                intents.push(HistoryIntent::EditResend {
                                    body: editor.body.clone(),
                                    from_byte: editor.from,
                                    to_byte: editor.to,
                                    replacement: editor.text.clone(),
                                    resend: true,
                                });
                            }
                        });
                    });
                if !open {
                    self.editor = None;
                }
            }
        }
    }
    fn materialize(
        &mut self,
        views: &mut HistoryViews,
        source: &ViewSource,
        ask: Ask,
        intents: &mut Vec<HistoryIntent>,
    ) {
        if self.requests.len() >= MAX_REQUESTS {
            return;
        }
        let result = match ask {
            Ask::Window(at) => views.begin_window(source, at).map(|request| {
                (
                    request.request_id.clone(),
                    RequestOwner::Window(source.clone()),
                    HistoryIntent::Window(request),
                )
            }),
            Ask::Body(body, from) => views.begin_body(body.clone(), from).map(|request| {
                (
                    request.request_id.clone(),
                    RequestOwner::Body(body, from),
                    HistoryIntent::Body(request),
                )
            }),
            Ask::Image(image, side) => {
                views.begin_image_sized(image.clone(), side).map(|request| {
                    (
                        request.request_id.clone(),
                        RequestOwner::Image(image, side),
                        HistoryIntent::Image(request),
                    )
                })
            }
            Ask::Footer(body, from) => views.begin_footer(body.clone(), from).map(|request| {
                (
                    request.request_id.clone(),
                    RequestOwner::Footer(body, from),
                    HistoryIntent::Footer(request),
                )
            }),
        };
        if let Ok((id, owner, intent)) = result {
            self.errors.remove(&owner);
            self.requests.insert(id, owner);
            intents.push(intent);
        }
    }
}
fn at_position(position: &ReadPosition) -> WindowAt {
    if position.follow_tail || position.message_id.is_empty() {
        WindowAt::Tail
    } else {
        WindowAt::Around {
            message_id: position.message_id.clone(),
        }
    }
}
fn preserve_anchor(state: &mut LayoutState, view: Option<&SourceView>) {
    if state.restore.is_none() {
        state.restore = view
            .filter(|view| !view.position.follow_tail)
            .map(|view| view.position.clone());
    }
}
fn owner_source(owner: &RequestOwner) -> &ViewSource {
    match owner {
        RequestOwner::Window(source) => source,
        RequestOwner::Body(body, _) | RequestOwner::Footer(body, _) => &body.source,
        RequestOwner::Image(image, _) => &image.source,
    }
}
fn restore_open(state: &LayoutState, body: &BodyRef) -> Option<bool> {
    state
        .restore
        .as_ref()
        .filter(|position| {
            position
                .body_message_id
                .as_ref()
                .unwrap_or(&position.message_id)
                == &body.message_id
                && position.body_kind == body.kind
        })
        .map(|_| true)
}
fn body_offset(state: &LayoutState, body: &BodyRef, position: &ReadPosition) -> u64 {
    let offset = state
        .body_offsets
        .get(&(body.message_id.clone(), body.kind.clone()))
        .copied()
        .unwrap_or_else(|| {
            if &body.message_id
                == position
                    .body_message_id
                    .as_ref()
                    .unwrap_or(&position.message_id)
                && body.kind == position.body_kind
            {
                position.byte_offset
            } else {
                0
            }
        });
    if offset > body.bytes { 0 } else { offset }
}
fn set_body_offset(state: &mut LayoutState, body: &BodyRef, offset: u64) {
    state
        .body_offsets
        .insert((body.message_id.clone(), body.kind.clone()), offset);
    state.heights.remove(&body.message_id);
    state.restore = Some(ReadPosition {
        source: body.source.clone(),
        message_id: state.current_row.clone(),
        body_message_id: (state.current_row != body.message_id).then(|| body.message_id.clone()),
        footer_from: matches!(body.kind, BodyKind::FileActivityDescription(_)).then(|| {
            state
                .footer_offsets
                .get(&state.current_row)
                .copied()
                .unwrap_or(0)
        }),
        body_kind: body.kind.clone(),
        byte_offset: offset,
        pixel_offset: 0.0,
        follow_tail: false,
        legacy: None,
    });
}
fn same_position(a: &ReadPosition, b: &ReadPosition) -> bool {
    a.source == b.source
        && a.message_id == b.message_id
        && a.body_kind == b.body_kind
        && a.body_message_id == b.body_message_id
        && a.footer_from == b.footer_from
        && a.byte_offset == b.byte_offset
        && a.follow_tail == b.follow_tail
        && a.legacy == b.legacy
        && (a.pixel_offset - b.pixel_offset).abs() < 1.0
}
fn bounded(text: &str, max: usize) -> String {
    let mut end = text.len().min(max);
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    text[..end].to_owned()
}

fn body_source_key(body: &BodyRef) -> (&ViewSource, &str) {
    (&body.source, &body.message_id)
}
