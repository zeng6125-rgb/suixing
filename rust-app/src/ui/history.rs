//! Presentation-only bounded caches. Reading positions never derive model context.
use crate::display::{
    BodyKind, BodyPage, BodyPageRequest, BodyRef, CandidateNavigation, DisplayError, DisplayRow,
    DisplayWindow, FooterPage, FooterRequest, ImageRef, ImageRequest, ReadPosition, SourceStamp,
    ViewSource, WindowAt, WindowCursor, WindowRequest,
};
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;

const MAX_CACHED_ROWS: usize = 288;
const MAX_SOURCE_BYTES: usize = 4 * 1024 * 1024;
const MAX_PIXEL_BYTES: usize = 16 * 1024 * 1024;
const MAX_TOTAL_BYTES: usize = 24 * 1024 * 1024;
const MAX_POSITIONS: usize = 128;

#[derive(Clone)]
pub struct ImagePixels {
    pub request_id: String,
    pub image: ImageRef,
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}
impl std::fmt::Debug for ImagePixels {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ImagePixels")
            .field("request_id", &self.request_id)
            .field("image", &self.image)
            .field("width", &self.width)
            .field("height", &self.height)
            .field("bytes", &self.rgba.len())
            .finish()
    }
}
impl ImagePixels {
    pub(crate) fn valid(&self) -> bool {
        self.width > 0
            && self.height > 0
            && self.width <= 2160
            && self.height <= 2160
            && (self.width as usize)
                .checked_mul(self.height as usize)
                .and_then(|n| n.checked_mul(4))
                == Some(self.rgba.len())
            && self.rgba.len() <= MAX_PIXEL_BYTES
    }
}

pub struct WindowApplied {
    /// The renderer restores this message/body byte and pixel offset after layout.
    /// It must not substitute the old absolute scroll offset after prepending rows.
    pub restore: Option<ReadPosition>,
    pub follow_tail: bool,
    pub anchor_found: bool,
}
struct PendingWindow {
    request_id: String,
    at: WindowAt,
}
struct PendingBody {
    request_id: String,
    from_byte: u64,
}
pub struct SourceView {
    pub source: ViewSource,
    pub stamp: Option<SourceStamp>,
    pub rows: VecDeque<DisplayRow>,
    pub before: Option<WindowCursor>,
    pub after: Option<WindowCursor>,
    pub candidates: HashMap<String, CandidateNavigation>,
    pub position: ReadPosition,
    pub anchor_missing: bool,
    pub needs_window: bool,
    body_pages: VecDeque<BodyPage>,
    images: VecDeque<Arc<ImagePixels>>,
    footer_pages: VecDeque<FooterPage>,
    footer_requests: HashMap<(String, usize), (String, BodyRef)>,
    window: Option<PendingWindow>,
    body_requests: HashMap<BodyRef, PendingBody>,
    image_requests: HashMap<ImageRef, String>,
    touched: u64,
}
impl SourceView {
    fn new(source: ViewSource, position: Option<ReadPosition>, touched: u64) -> Self {
        let position = position
            .filter(|position| position.source == source && position.validate().is_ok())
            .unwrap_or(ReadPosition {
                source: source.clone(),
                message_id: String::new(),
                body_message_id: None,
                footer_from: None,
                body_kind: BodyKind::Text,
                byte_offset: 0,
                pixel_offset: 0.0,
                follow_tail: true,
                legacy: None,
            });
        Self {
            source,
            stamp: None,
            rows: VecDeque::new(),
            before: None,
            after: None,
            candidates: HashMap::new(),
            position,
            anchor_missing: false,
            needs_window: true,
            body_pages: VecDeque::new(),
            images: VecDeque::new(),
            footer_pages: VecDeque::new(),
            footer_requests: HashMap::new(),
            window: None,
            body_requests: HashMap::new(),
            image_requests: HashMap::new(),
            touched,
        }
    }
    fn data_bytes(&self) -> usize {
        self.footer_pages
            .iter()
            .map(FooterPage::estimated_bytes)
            .sum::<usize>()
            + self
                .rows
                .iter()
                .map(DisplayRow::estimated_bytes)
                .sum::<usize>()
            + self
                .body_pages
                .iter()
                .map(BodyPage::estimated_bytes)
                .sum::<usize>()
            + self
                .images
                .iter()
                .map(|image| image.rgba.len() + image.image.estimated_bytes())
                .sum::<usize>()
            + self
                .candidates
                .values()
                .map(CandidateNavigation::estimated_bytes)
                .sum::<usize>()
    }
    fn clear_data(&mut self) {
        self.needs_window = true;
        self.rows.clear();
        self.body_pages.clear();
        self.images.clear();
        self.footer_pages.clear();
        self.footer_requests.clear();
        self.candidates.clear();
        self.before = None;
        self.after = None;
        self.window = None;
        self.body_requests.clear();
        self.image_requests.clear();
    }
    fn trim(&mut self) {
        while self.data_bytes() > MAX_SOURCE_BYTES && !self.images.is_empty() {
            self.images.pop_front();
        }
        while self.data_bytes() > MAX_SOURCE_BYTES && !self.body_pages.is_empty() {
            self.body_pages.pop_front();
        }
        while self.data_bytes() > MAX_SOURCE_BYTES && !self.footer_pages.is_empty() {
            self.footer_pages.pop_front();
        }
        while self.rows.len() > MAX_CACHED_ROWS || self.data_bytes() > MAX_SOURCE_BYTES {
            if self.rows.len() <= 1 {
                break;
            }
            let anchor = self
                .rows
                .iter()
                .position(|row| row.id == self.position.message_id);
            let left = if self.position.follow_tail {
                true
            } else {
                anchor.is_none_or(|index| index >= self.rows.len() / 2)
            };
            let remove_batch = if left {
                self.rows.front()
            } else {
                self.rows.back()
            }
            .and_then(|row| row.batch_id.clone());
            loop {
                if left {
                    self.rows.pop_front();
                } else {
                    self.rows.pop_back();
                }
                let edge = if left {
                    self.rows.front()
                } else {
                    self.rows.back()
                };
                if remove_batch.is_none() || edge.is_none_or(|row| row.batch_id != remove_batch) {
                    break;
                }
            }
            // A discarded edge remains reachable through its boundary cursor.
            if left {
                self.before = self.rows.front().map(|row| WindowCursor {
                    stamp: self.stamp.clone().expect("cached rows have a stamp"),
                    boundary_id: row.id.clone(),
                });
            } else {
                self.after = self.rows.back().map(|row| WindowCursor {
                    stamp: self.stamp.clone().expect("cached rows have a stamp"),
                    boundary_id: row.id.clone(),
                });
            }
            self.candidates.retain(|turn, _| {
                self.rows
                    .iter()
                    .any(|row| row.turn_id.as_ref() == Some(turn))
            });
        }
    }
    pub fn body_page(&self, body: &BodyRef, from_byte: u64) -> Option<&BodyPage> {
        self.body_pages
            .iter()
            .find(|page| &page.body == body && page.from_byte == from_byte)
    }
    pub fn image(&self, image: &ImageRef) -> Option<&ImagePixels> {
        self.images
            .iter()
            .find(|pixels| &pixels.image == image)
            .map(|pixels| pixels.as_ref())
    }
    pub fn window_pending(&self) -> bool {
        self.window.is_some()
    }
    pub fn body_pending(&self, body: &BodyRef) -> bool {
        self.body_requests.contains_key(body)
    }
    pub fn image_pending(&self, image: &ImageRef) -> bool {
        self.image_requests.contains_key(image)
    }
    pub fn footer(&self, body: &BodyRef, from: usize) -> Option<&FooterPage> {
        self.footer_pages.iter().find(|page| {
            page.message_id == body.message_id
                && page.message_hash == body.message_hash
                && page.stamp == body.stamp
                && page.from == from
        })
    }
    pub fn footer_pending(&self, body: &BodyRef, from: usize) -> bool {
        self.footer_requests
            .contains_key(&(body.message_id.clone(), from))
    }
}

#[derive(Default)]
pub struct HistoryViews {
    sources: HashMap<ViewSource, SourceView>,
    clock: u64,
}
impl HistoryViews {
    /// Persist the returned evicted position in the backend before forgetting it.
    /// Eviction never mixes positions between main and child sources.
    pub fn open(
        &mut self,
        source: ViewSource,
        saved: Option<ReadPosition>,
    ) -> Option<ReadPosition> {
        self.clock = self.clock.saturating_add(1);
        if let Some(view) = self.sources.get_mut(&source) {
            view.touched = self.clock;
            return None;
        }
        let evicted = if self.sources.len() >= MAX_POSITIONS {
            let oldest = self
                .sources
                .values()
                .min_by_key(|view| view.touched)
                .map(|view| view.source.clone());
            oldest
                .and_then(|source| self.sources.remove(&source))
                .map(|view| view.position)
        } else {
            None
        };
        self.sources
            .insert(source.clone(), SourceView::new(source, saved, self.clock));
        evicted
    }
    pub fn view(&self, source: &ViewSource) -> Option<&SourceView> {
        self.sources.get(source)
    }
    pub fn has_pending(&self) -> bool {
        self.sources.values().any(|view| {
            view.window.is_some()
                || !view.body_requests.is_empty()
                || !view.image_requests.is_empty()
                || !view.footer_requests.is_empty()
        })
    }
    pub fn disconnected(&mut self) {
        for view in self.sources.values_mut() {
            view.window = None;
            view.body_requests.clear();
            view.image_requests.clear();
            view.footer_requests.clear();
        }
    }
    pub fn request_pending(&self, source: &ViewSource, request_id: &str) -> bool {
        self.sources.get(source).is_some_and(|view| {
            view.window
                .as_ref()
                .is_some_and(|pending| pending.request_id == request_id)
                || view
                    .body_requests
                    .values()
                    .any(|pending| pending.request_id == request_id)
                || view
                    .image_requests
                    .values()
                    .any(|pending| pending == request_id)
                || view
                    .footer_requests
                    .values()
                    .any(|(pending, _)| pending == request_id)
        })
    }
    /// Adopt an initial backend reading position without restarting an already
    /// pending tail request. A saved non-tail anchor needs one around request.
    pub fn confirm_open(
        &mut self,
        source: &ViewSource,
        saved: Option<ReadPosition>,
        stamp: SourceStamp,
    ) -> Result<Option<WindowRequest>, DisplayError> {
        let view = self.sources.get_mut(source).ok_or(DisplayError::Missing)?;
        if view.stamp.as_ref() == Some(&stamp) {
            return Ok(None);
        }
        if view.stamp.is_none() {
            if let Some(position) =
                saved.filter(|position| position.source == *source && position.validate().is_ok())
            {
                view.position = position;
            }
            if view.window.is_some() && view.position.follow_tail {
                view.stamp = Some(stamp);
                return Ok(None);
            }
        }
        self.invalidate(source, stamp).map(Some)
    }
    /// A worker failure settles only its matching request; newer attempts survive.
    pub fn request_failed(&mut self, source: &ViewSource, request_id: &str) {
        if let Some(view) = self.sources.get_mut(source) {
            if view
                .window
                .as_ref()
                .is_some_and(|pending| pending.request_id == request_id)
            {
                view.window = None;
            }
            view.body_requests
                .retain(|_, pending| pending.request_id != request_id);
            view.image_requests
                .retain(|_, pending| pending != request_id);
            view.footer_requests
                .retain(|_, (pending, _)| pending != request_id);
        }
    }
    pub fn remember_position(&mut self, position: ReadPosition) -> Result<(), DisplayError> {
        position.validate()?;
        let view = self
            .sources
            .get_mut(&position.source)
            .ok_or(DisplayError::Missing)?;
        view.position = position;
        Ok(())
    }
    pub fn begin_window(
        &mut self,
        source: &ViewSource,
        at: WindowAt,
    ) -> Result<WindowRequest, DisplayError> {
        let view = self.sources.get_mut(source).ok_or(DisplayError::Missing)?;
        let request_id = crate::controller::unique_id();
        view.window = Some(PendingWindow {
            request_id: request_id.clone(),
            at: at.clone(),
        });
        Ok(WindowRequest {
            request_id,
            source: source.clone(),
            at,
            max_rows: 96,
            previews: true,
        })
    }
    pub fn reload_at_position(
        &mut self,
        source: &ViewSource,
    ) -> Result<WindowRequest, DisplayError> {
        let view = self.sources.get(source).ok_or(DisplayError::Missing)?;
        let at = if view.position.follow_tail || view.position.message_id.is_empty() {
            WindowAt::Tail
        } else {
            WindowAt::Around {
                message_id: view.position.message_id.clone(),
            }
        };
        self.begin_window(source, at)
    }
    /// A typed stale read reloads only this source around its saved anchor.
    /// Without an authoritative new stamp, discard the old identity first so
    /// existing editors cannot submit against it while the new window loads.
    pub fn reload_stale(
        &mut self,
        source: &ViewSource,
        current_stamp: Option<SourceStamp>,
    ) -> Result<WindowRequest, DisplayError> {
        if let Some(stamp) = current_stamp {
            return self.invalidate(source, stamp);
        }
        let view = self.sources.get_mut(source).ok_or(DisplayError::Missing)?;
        view.clear_data();
        view.stamp = None;
        self.reload_at_position(source)
    }
    /// A topology/choice revision discards pending replies and reloads around the
    /// recorded source-specific anchor, without changing follow-tail preference.
    pub fn invalidate(
        &mut self,
        source: &ViewSource,
        stamp: SourceStamp,
    ) -> Result<WindowRequest, DisplayError> {
        let view = self.sources.get_mut(source).ok_or(DisplayError::Missing)?;
        if view
            .stamp
            .as_ref()
            .is_some_and(|old| old.revision > stamp.revision)
        {
            return Err(DisplayError::Stale);
        }
        view.clear_data();
        view.stamp = Some(stamp);
        self.reload_at_position(source)
    }
    pub fn apply_window(&mut self, page: DisplayWindow) -> Result<WindowApplied, DisplayError> {
        if page.estimated_bytes() > crate::display::MAX_WINDOW_BYTES {
            return Err(DisplayError::Budget);
        }
        let view = self
            .sources
            .get_mut(&page.source)
            .ok_or(DisplayError::Missing)?;
        let pending = view
            .window
            .as_ref()
            .filter(|pending| pending.request_id == page.request_id)
            .ok_or(DisplayError::Stale)?;
        if view
            .stamp
            .as_ref()
            .is_some_and(|old| old.revision > page.stamp.revision)
        {
            return Err(DisplayError::Stale);
        }
        let append = matches!(pending.at, WindowAt::After(_));
        let prepend = matches!(pending.at, WindowAt::Before(_));
        if (append || prepend) && view.stamp.as_ref() != Some(&page.stamp) {
            return Err(DisplayError::Stale);
        }
        let restore = (!view.position.follow_tail).then(|| view.position.clone());
        if !(append || prepend) || view.stamp.as_ref() != Some(&page.stamp) {
            view.clear_data();
        }
        view.stamp = Some(page.stamp.clone());
        view.window = None;
        if !append {
            view.before = page.before;
        }
        if !prepend {
            view.after = page.after;
        }
        for candidate in page.candidates {
            view.candidates.insert(candidate.turn_id.clone(), candidate);
        }
        for row in page.rows {
            if let Some(index) = view.rows.iter().position(|old| old.id == row.id) {
                view.rows[index] = row;
            } else {
                view.rows.push_back(row);
            }
        }
        view.rows
            .make_contiguous()
            .sort_by_key(|row| row.source_index);
        view.anchor_missing = !page.anchor_found;
        view.needs_window = false;
        view.trim();
        let applied = WindowApplied {
            restore,
            follow_tail: view.position.follow_tail,
            anchor_found: page.anchor_found,
        };
        self.enforce_total_budget(&page.source);
        Ok(applied)
    }
    pub fn begin_body(
        &mut self,
        body: BodyRef,
        from_byte: u64,
    ) -> Result<BodyPageRequest, DisplayError> {
        let view = self
            .sources
            .get_mut(&body.source)
            .ok_or(DisplayError::Missing)?;
        if view.stamp.as_ref() != Some(&body.stamp) {
            return Err(DisplayError::Stale);
        }
        if view.body_requests.len() >= 32 || view.body_requests.contains_key(&body) {
            return Err(DisplayError::Budget);
        }
        let request_id = crate::controller::unique_id();
        view.body_requests.insert(
            body.clone(),
            PendingBody {
                request_id: request_id.clone(),
                from_byte,
            },
        );
        Ok(BodyPageRequest {
            request_id,
            body,
            from_byte,
            max_bytes: crate::display::MAX_BODY_PAGE_BYTES,
        })
    }
    pub fn apply_body(&mut self, page: BodyPage) -> Result<(), DisplayError> {
        if page.text.len() > crate::display::MAX_BODY_PAGE_BYTES
            || page.to_byte < page.from_byte
            || page.to_byte - page.from_byte != page.text.len() as u64
        {
            return Err(DisplayError::Budget);
        }
        let view = self
            .sources
            .get_mut(&page.body.source)
            .ok_or(DisplayError::Missing)?;
        if view.stamp.as_ref() != Some(&page.body.stamp) {
            return Err(DisplayError::Stale);
        }
        let pending = view
            .body_requests
            .get(&page.body)
            .ok_or(DisplayError::Stale)?;
        if pending.request_id != page.request_id || pending.from_byte != page.from_byte {
            return Err(DisplayError::Stale);
        }
        view.body_requests.remove(&page.body);
        let source = page.body.source.clone();
        view.body_pages
            .retain(|old| old.body != page.body || old.from_byte != page.from_byte);
        view.body_pages.push_back(page);
        view.trim();
        self.enforce_total_budget(&source);
        Ok(())
    }
    pub fn begin_image(&mut self, image: ImageRef) -> Result<ImageRequest, DisplayError> {
        self.begin_image_sized(image, 512)
    }
    pub fn begin_image_sized(
        &mut self,
        image: ImageRef,
        max_side: u32,
    ) -> Result<ImageRequest, DisplayError> {
        let view = self
            .sources
            .get_mut(&image.source)
            .ok_or(DisplayError::Missing)?;
        if view.stamp.as_ref() != Some(&image.stamp) {
            return Err(DisplayError::Stale);
        }
        if view.image_requests.len() >= 16 || view.image_requests.contains_key(&image) {
            return Err(DisplayError::Budget);
        }
        let request_id = crate::controller::unique_id();
        view.image_requests
            .insert(image.clone(), request_id.clone());
        Ok(ImageRequest {
            request_id,
            image,
            max_side: max_side.clamp(64, 2160),
        })
    }
    pub fn accepts_image(&self, pixels: &ImagePixels) -> bool {
        pixels.valid()
            && self.sources.get(&pixels.image.source).is_some_and(|view| {
                view.stamp.as_ref() == Some(&pixels.image.stamp)
                    && view.image_requests.get(&pixels.image) == Some(&pixels.request_id)
            })
    }
    pub fn apply_image(&mut self, pixels: Arc<ImagePixels>) -> Result<(), DisplayError> {
        if !pixels.valid() {
            return Err(DisplayError::Budget);
        }
        let view = self
            .sources
            .get_mut(&pixels.image.source)
            .ok_or(DisplayError::Missing)?;
        if view.stamp.as_ref() != Some(&pixels.image.stamp)
            || view.image_requests.get(&pixels.image) != Some(&pixels.request_id)
        {
            return Err(DisplayError::Stale);
        }
        view.image_requests.remove(&pixels.image);
        let source = pixels.image.source.clone();
        view.images.retain(|old| old.image != pixels.image);
        view.images.push_back(pixels);
        view.trim();
        self.enforce_total_budget(&source);
        Ok(())
    }
    pub fn begin_footer(
        &mut self,
        body: BodyRef,
        from: usize,
    ) -> Result<FooterRequest, DisplayError> {
        let view = self
            .sources
            .get_mut(&body.source)
            .ok_or(DisplayError::Missing)?;
        if view.stamp.as_ref() != Some(&body.stamp) {
            return Err(DisplayError::Stale);
        }
        let key = (body.message_id.clone(), from);
        if view.footer_requests.len() >= 16 || view.footer_requests.contains_key(&key) {
            return Err(DisplayError::Budget);
        }
        let request_id = crate::controller::unique_id();
        view.footer_requests
            .insert(key, (request_id.clone(), body.clone()));
        Ok(FooterRequest {
            request_id,
            source: body.source,
            stamp: body.stamp,
            message_id: body.message_id,
            message_hash: body.message_hash,
            from,
        })
    }
    pub fn apply_footer(&mut self, request_id: &str, page: FooterPage) -> Result<(), DisplayError> {
        if page.estimated_bytes() > crate::display::MAX_BODY_PAGE_BYTES {
            return Err(DisplayError::Budget);
        }
        let view = self
            .sources
            .get_mut(&page.source)
            .ok_or(DisplayError::Missing)?;
        let key = (page.message_id.clone(), page.from);
        let pending = view.footer_requests.get(&key).ok_or(DisplayError::Stale)?;
        if pending.0 != request_id
            || pending.1.stamp != page.stamp
            || pending.1.message_hash != page.message_hash
            || view.stamp.as_ref() != Some(&page.stamp)
        {
            return Err(DisplayError::Stale);
        }
        view.footer_requests.remove(&key);
        let source = page.source.clone();
        view.footer_pages
            .retain(|old| old.message_id != page.message_id || old.from != page.from);
        view.footer_pages.push_back(page);
        view.trim();
        self.enforce_total_budget(&source);
        Ok(())
    }
    fn enforce_total_budget(&mut self, active: &ViewSource) {
        loop {
            if self
                .sources
                .values()
                .map(SourceView::data_bytes)
                .sum::<usize>()
                <= MAX_TOTAL_BYTES
            {
                return;
            }
            let oldest = self
                .sources
                .values()
                .filter(|view| &view.source != active && view.data_bytes() > 0)
                .min_by_key(|view| view.touched)
                .map(|view| view.source.clone());
            let Some(oldest) = oldest else {
                return;
            };
            if let Some(view) = self.sources.get_mut(&oldest) {
                view.clear_data();
            }
        }
    }
}
