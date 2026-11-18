//! Bounded Markdown presentation. Parsing and layout are retained across repaint frames.
use super::mobile_chat::{Icon, Icons, Skin, single_line};
use eframe::egui::{
    self, Color32, FontId, Galley, Id, Label, Sense, Stroke, TextFormat, TextStyle, text::LayoutJob,
};
use std::collections::{BTreeMap, HashMap};
use std::fmt::Debug;
use std::hash::Hash;
use std::ops::Range;
use std::sync::{Arc, atomic::AtomicBool};
use std::time::{Duration, Instant};
use suixing_search::markdown_native::{self, Block, Bullet, Document, InlineRun, ParseLimits};

const SOURCE_BYTES: usize = 64 * 1024;
const RAW_PAGE_BYTES: usize = 16 * 1024;
const CACHE_ENTRIES: usize = 192;
const CACHE_SOURCE_BYTES: usize = 2 * 1024 * 1024;
const MAX_BLOCKS: usize = 2_048;
const STREAM_INTERVAL: Duration = Duration::from_millis(150);

/// Message IDs and body/thinking tags are supplied separately through the hashable source key.
#[derive(Default)]
pub struct MarkdownRenderer {
    entries: HashMap<Id, SourceCache>,
    raw_entries: HashMap<Id, RawFallback>,
    clock: u64,
}

impl MarkdownRenderer {
    /// A changing stream only reparses its unsettled suffix. Unchanged frames do no parsing.
    pub(super) fn show(
        &mut self,
        ui: &mut egui::Ui,
        source_id: impl Hash + Debug,
        text: &str,
        streaming: bool,
        chrome: Option<(Skin, &Icons)>,
    ) {
        let id = Id::unique(("jagent_markdown", source_id));
        let frame = ui.ctx().cumulative_frame_nr();
        self.clock = self.clock.saturating_add(1);
        if text.len() > SOURCE_BYTES {
            // Only the oversized source itself is released, never another active document.
            self.entries.remove(&id);
            self.show_raw(ui, id, text);
            return;
        }
        if !self.admit(id, text.len(), frame) {
            if let Some(entry) = self.entries.get_mut(&id) {
                entry.used = self.clock;
                entry.seen_frame = frame;
            }
            // Rejected sources never enter the prepared map or displace one of its entries.
            self.show_raw(ui, id, text);
            return;
        }
        self.raw_entries.remove(&id);
        let entry = self.entries.entry(id).or_default();
        entry.used = self.clock;
        entry.seen_frame = frame;
        ui.push_id(id, |ui| {
            entry.update(text, streaming, id, ui.ctx());
            if entry.failed {
                entry.raw.show(ui, text, id.with("original"));
            } else {
                for block in &mut entry.settled {
                    block.show(ui, chrome);
                }
                if entry.tail_plain {
                    entry
                        .raw
                        .show(ui, &text[entry.settled_end..], id.with("tail"));
                } else {
                    for block in &mut entry.tail {
                        block.show(ui, chrome);
                    }
                }
            }
        });
    }

    pub fn clear(&mut self) {
        self.entries.clear();
        self.raw_entries.clear();
    }

    // Do not churn active documents through the LRU when a long visible transcript fills it.
    // Inactive views can be evicted; an over-budget active source uses its cached original page.
    fn admit(&mut self, current: Id, bytes: usize, frame: u64) -> bool {
        loop {
            let new_entry = !self.entries.contains_key(&current);
            let source_bytes = self
                .entries
                .iter()
                .filter(|(key, _)| **key != current)
                .map(|(_, entry)| entry.source.len())
                .sum::<usize>();
            if (!new_entry || self.entries.len() < CACHE_ENTRIES)
                && source_bytes.saturating_add(bytes) <= CACHE_SOURCE_BYTES
            {
                return true;
            }
            let inactive = self
                .entries
                .iter()
                .filter(|(key, entry)| {
                    **key != current && entry.seen_frame.saturating_add(1) < frame
                })
                .min_by_key(|(_, entry)| entry.used)
                .map(|(key, _)| *key);
            let Some(inactive) = inactive else {
                return false;
            };
            self.entries.remove(&inactive);
        }
    }

    fn show_raw(&mut self, ui: &mut egui::Ui, id: Id, text: &str) {
        let raw = self.raw_entries.entry(id).or_default();
        raw.used = self.clock;
        ui.push_id(id, |ui| raw.page.show(ui, text, id.with("original")));
        if self.raw_entries.len() > CACHE_ENTRIES {
            let oldest = self
                .raw_entries
                .iter()
                .filter(|(key, _)| **key != id)
                .min_by_key(|(_, value)| value.used)
                .map(|(key, _)| *key);
            if let Some(oldest) = oldest {
                self.raw_entries.remove(&oldest);
            }
        }
    }
}

#[derive(Default)]
struct RawFallback {
    page: RawPage,
    used: u64,
}

struct SourceCache {
    source: String,
    settled_end: usize,
    scan_at: usize,
    in_fence: bool,
    settled: Vec<PreparedBlock>,
    tail: Vec<PreparedBlock>,
    tail_plain: bool,
    failed: bool,
    was_streaming: bool,
    last_parse: Option<Instant>,
    raw: RawPage,
    used: u64,
    seen_frame: u64,
}

impl Default for SourceCache {
    fn default() -> Self {
        Self {
            source: String::new(),
            settled_end: 0,
            scan_at: 0,
            in_fence: false,
            settled: Vec::new(),
            tail: Vec::new(),
            tail_plain: false,
            failed: false,
            was_streaming: false,
            last_parse: None,
            raw: RawPage::default(),
            used: 0,
            seen_frame: 0,
        }
    }
}

impl SourceCache {
    fn clear_document(&mut self) {
        if !self.source.is_empty() || !self.settled.is_empty() || !self.tail.is_empty() {
            self.source = String::new();
            self.settled.clear();
            self.tail.clear();
            self.settled_end = 0;
            self.scan_at = 0;
            self.in_fence = false;
            self.last_parse = None;
        }
        self.failed = false;
        self.tail_plain = false;
    }

    fn update(&mut self, text: &str, streaming: bool, id: Id, context: &egui::Context) {
        let now = Instant::now();
        let changed = self.source != text;
        let just_completed = self.was_streaming && !streaming;
        self.was_streaming = streaming;
        if !changed && !just_completed {
            // A debounce repaint still needs to prepare the raw tail once its delay expires.
            if !self.tail_plain
                || self.failed
                || self
                    .last_parse
                    .is_none_or(|at| now.duration_since(at) < STREAM_INTERVAL)
            {
                return;
            }
        }
        if changed {
            if !text.starts_with(&self.source) {
                self.clear_document();
                self.failed = false;
                self.raw = RawPage::default();
            }
            let old_length = self.source.len();
            // Reserve just this revision rather than geometrically growing the source snapshot.
            self.source.reserve_exact(text.len() - old_length);
            self.source.push_str(&text[old_length..]);
        }
        if self.failed {
            return;
        }
        let boundary = self.scan_boundaries(text);
        if boundary > self.settled_end {
            let base = self.settled.len();
            match prepare(&text[self.settled_end..boundary], id, base) {
                Ok(blocks) if base + blocks.len() <= MAX_BLOCKS => {
                    self.settled.extend(blocks);
                    self.settled_end = boundary;
                    self.tail.clear();
                    self.last_parse = None;
                }
                _ => {
                    self.failed = true;
                    return;
                }
            }
        }
        let tail = &text[self.settled_end..];
        if tail.is_empty() {
            self.tail.clear();
            self.tail_plain = false;
            return;
        }
        if streaming
            && self
                .last_parse
                .is_some_and(|at| now.duration_since(at) < STREAM_INTERVAL)
        {
            self.tail_plain = true;
            context.request_repaint_after(STREAM_INTERVAL);
            return;
        }
        match prepare(tail, id, self.settled.len()) {
            Ok(blocks) if self.settled.len() + blocks.len() <= MAX_BLOCKS => {
                self.tail = blocks;
                self.tail_plain = false;
                self.last_parse = Some(now);
            }
            _ => {
                self.failed = true;
            }
        }
    }

    /// Only complete new lines are scanned. Blank lines inside a fence cannot settle it.
    fn scan_boundaries(&mut self, text: &str) -> usize {
        let mut settled = self.settled_end;
        while let Some(end) = text[self.scan_at..].find('\n') {
            let end = self.scan_at + end;
            let line = text[self.scan_at..end].trim();
            if self.in_fence {
                if markdown_native::is_fence_end(line) {
                    self.in_fence = false;
                    settled = end + 1;
                }
            } else if markdown_native::is_fence_start(line) {
                self.in_fence = true;
            } else if line.is_empty() {
                settled = end + 1;
            }
            self.scan_at = end + 1;
        }
        settled
    }
}

fn prepare(text: &str, source: Id, base: usize) -> Result<Vec<PreparedBlock>, ()> {
    let limits = ParseLimits {
        source_bytes: SOURCE_BYTES,
        nodes: MAX_BLOCKS,
        output_bytes: 512 * 1024,
        list_depth: 24,
        inline_bytes: 16 * 1024,
    };
    let Document { blocks, inline } =
        markdown_native::parse_with_limits(text, &AtomicBool::new(false), limits)
            .map_err(|_| ())?;
    blocks
        .into_iter()
        .enumerate()
        .map(|(index, block)| {
            PreparedBlock::new(block, &inline, source.with(("block", base + index)))
        })
        .collect()
}

struct PreparedBlock {
    id: Id,
    body: PreparedBody,
}

enum PreparedBody {
    Heading(u32, CachedText),
    Paragraph(CachedText),
    Quote(CachedText),
    Code(String, CachedText),
    Bullet(PreparedBullet),
    Rule,
    Table(Vec<CachedText>, Vec<Vec<CachedText>>, usize),
}

impl PreparedBlock {
    fn new(block: Block, inline: &BTreeMap<String, Vec<InlineRun>>, id: Id) -> Result<Self, ()> {
        // Images need the image/platform renderer. Code containing image notation remains code.
        if block_has_image(&block) {
            return Err(());
        }
        let body = match block {
            Block::Heading(level, text) => {
                PreparedBody::Heading(level, CachedText::new(text, inline))
            }
            Block::Paragraph(text) => PreparedBody::Paragraph(CachedText::new(text, inline)),
            Block::Quote(text) => PreparedBody::Quote(CachedText::new(text, inline)),
            Block::Code(language, text) => {
                if language.len() > 128 {
                    return Err(());
                }
                PreparedBody::Code(language, CachedText::plain(text))
            }
            Block::Bullet(bullet) => PreparedBody::Bullet(PreparedBullet::new(bullet, inline)),
            Block::Rule => PreparedBody::Rule,
            Block::Table(header, rows) => {
                let columns = rows
                    .iter()
                    .map(Vec::len)
                    .chain(std::iter::once(header.len()))
                    .max()
                    .unwrap_or(0);
                if columns == 0 || columns > 24 || rows.len() > 256 {
                    return Err(());
                }
                PreparedBody::Table(
                    header
                        .into_iter()
                        .map(|text| CachedText::new(text, inline))
                        .collect(),
                    rows.into_iter()
                        .map(|row| {
                            row.into_iter()
                                .map(|text| CachedText::new(text, inline))
                                .collect()
                        })
                        .collect(),
                    columns,
                )
            }
        };
        Ok(Self { id, body })
    }

    fn show(&mut self, ui: &mut egui::Ui, chrome: Option<(Skin, &Icons)>) {
        ui.push_id(self.id, |ui| {
            match &mut self.body {
                PreparedBody::Heading(level, text) => {
                    text.show(ui, TextKind::Heading(*level));
                }
                PreparedBody::Paragraph(text) => {
                    text.show(ui, TextKind::Body);
                }
                PreparedBody::Quote(text) => {
                    egui::Frame::NONE
                        .fill(ui.visuals().faint_bg_color)
                        .stroke(Stroke::new(
                            1.0,
                            ui.visuals().widgets.noninteractive.bg_stroke.color,
                        ))
                        .inner_margin(8)
                        .show(ui, |ui| {
                            text.show(ui, TextKind::Body);
                        });
                }
                PreparedBody::Code(language, text) => {
                    if let Some((skin, icons)) = chrome {
                        egui::Frame::NONE
                            .fill(skin.code)
                            .corner_radius(12)
                            .inner_margin(egui::Margin::symmetric(12, 8))
                            .show(ui, |ui| {
                                ui.set_min_width(ui.available_width());
                                ui.spacing_mut().item_spacing.y = 3.0;
                                ui.visuals_mut().code_bg_color = Color32::TRANSPARENT;
                                let (header, _) = ui.allocate_exact_size(
                                    egui::vec2(ui.available_width(), 24.0),
                                    Sense::hover(),
                                );
                                let copy = egui::Rect::from_min_max(
                                    egui::pos2(header.right() - 24.0, header.top()),
                                    header.right_bottom(),
                                );
                                let mut language_bounds = header;
                                language_bounds.max.x = copy.left() - 4.0;
                                single_line(
                                    ui,
                                    language,
                                    language_bounds,
                                    FontId::monospace(12.0),
                                    skin.ink,
                                );
                                if icons
                                    .button(ui, copy, Icon::Copy, "复制代码", 16.0, skin.ink, true)
                                    .clicked()
                                {
                                    ui.ctx().copy_text(text.source.clone());
                                }
                                egui::ScrollArea::both()
                                    .id_salt("code")
                                    .auto_shrink([false, true])
                                    .max_height(360.0)
                                    .show(ui, |ui| text.show(ui, TextKind::Code));
                            });
                    } else {
                        ui.horizontal(|ui| {
                            if !language.is_empty() {
                                ui.label(egui::RichText::new(language.as_str()).small().weak());
                            }
                            if ui.small_button("复制代码").clicked() {
                                ui.ctx().copy_text(text.source.clone());
                            }
                        });
                        egui::Frame::NONE
                            .fill(ui.visuals().code_bg_color)
                            .inner_margin(8)
                            .show(ui, |ui| {
                                egui::ScrollArea::both()
                                    .id_salt("code")
                                    .max_height(360.0)
                                    .show(ui, |ui| {
                                        text.show(ui, TextKind::Code);
                                    });
                            });
                    }
                }
                PreparedBody::Bullet(bullet) => bullet.show(ui, self.id),
                PreparedBody::Rule => {
                    ui.separator();
                }
                PreparedBody::Table(header, rows, columns) => {
                    egui::ScrollArea::horizontal()
                        .id_salt("table_scroll")
                        .auto_shrink([false, true])
                        .show(ui, |ui| {
                            egui::Grid::new("table")
                                .num_columns(*columns)
                                .striped(true)
                                .min_col_width(72.0)
                                .max_col_width(280.0)
                                .show(ui, |ui| {
                                    for column in 0..*columns {
                                        if let Some(text) = header.get_mut(column) {
                                            text.show(ui, TextKind::Header);
                                        } else {
                                            ui.label("");
                                        }
                                    }
                                    ui.end_row();
                                    for row in rows {
                                        for column in 0..*columns {
                                            if let Some(text) = row.get_mut(column) {
                                                text.show(ui, TextKind::Body);
                                            } else {
                                                ui.label("");
                                            }
                                        }
                                        ui.end_row();
                                    }
                                });
                        });
                }
            }
            ui.add_space(if chrome.is_some() { 2.0 } else { 4.0 });
        });
    }
}

fn block_has_image(block: &Block) -> bool {
    match block {
        Block::Heading(_, text) | Block::Paragraph(text) | Block::Quote(text) => {
            text.contains("![")
        }
        Block::Bullet(bullet) => bullet_has_image(bullet),
        Block::Table(header, rows) => header
            .iter()
            .chain(rows.iter().flatten())
            .any(|text| text.contains("![")),
        Block::Code(_, _) | Block::Rule => false,
    }
}

fn bullet_has_image(bullet: &Bullet) -> bool {
    bullet.items.iter().any(|text| text.contains("!["))
        || bullet.children.values().flatten().any(bullet_has_image)
}

struct PreparedBullet {
    ordered: bool,
    start: u32,
    items: Vec<CachedText>,
    children: BTreeMap<usize, Vec<PreparedBullet>>,
}

impl PreparedBullet {
    fn new(bullet: Bullet, inline: &BTreeMap<String, Vec<InlineRun>>) -> Self {
        Self {
            ordered: bullet.ordered,
            start: bullet.start,
            items: bullet
                .items
                .into_iter()
                .map(|text| CachedText::new(text, inline))
                .collect(),
            children: bullet
                .children
                .into_iter()
                .map(|(index, children)| {
                    (
                        index,
                        children
                            .into_iter()
                            .map(|child| Self::new(child, inline))
                            .collect(),
                    )
                })
                .collect(),
        }
    }

    fn show(&mut self, ui: &mut egui::Ui, id: Id) {
        for (index, text) in self.items.iter_mut().enumerate() {
            ui.push_id(id.with(index), |ui| {
                ui.horizontal_top(|ui| {
                    let marker = if self.ordered {
                        format!("{}.", self.start.saturating_add(index as u32))
                    } else {
                        "•".into()
                    };
                    ui.label(marker);
                    ui.vertical(|ui| {
                        text.show(ui, TextKind::Body);
                        if let Some(children) = self.children.get_mut(&index) {
                            for (child_index, child) in children.iter_mut().enumerate() {
                                child.show(ui, id.with((index, child_index)));
                            }
                        }
                    });
                });
            });
        }
    }
}

#[derive(Clone, Copy, PartialEq)]
enum TextKind {
    Body,
    Heading(u32),
    Code,
    Header,
}

#[derive(PartialEq)]
struct LayoutStyle {
    width: f32,
    pixels_per_point: f32,
    body: FontId,
    code: FontId,
    color: Color32,
    strong: Color32,
    link: Color32,
    code_background: Color32,
    kind: TextKind,
}

struct LinkTarget {
    characters: Range<usize>,
    label: String,
    url: String,
    bounds: egui::Rect,
}

struct CachedText {
    source: String,
    runs: Option<Vec<InlineRun>>,
    links: Vec<LinkTarget>,
    galley: Option<Arc<Galley>>,
    style: Option<LayoutStyle>,
}

impl CachedText {
    fn plain(source: String) -> Self {
        Self {
            source,
            runs: None,
            links: Vec::new(),
            galley: None,
            style: None,
        }
    }

    fn new(source: String, inline: &BTreeMap<String, Vec<InlineRun>>) -> Self {
        let mut runs = inline.get(&source).cloned();
        let mut links: Vec<LinkTarget> = Vec::new();
        let mut offset = 0;
        if let Some(runs) = &mut runs {
            for run in runs {
                let end = offset + run.text.chars().count();
                if let Some(url) = run.link.as_deref().and_then(safe_url) {
                    if let Some(previous) = links
                        .last_mut()
                        .filter(|previous| previous.characters.end == offset && previous.url == url)
                    {
                        // A styled link may consist of several consecutive inline runs.
                        previous.characters.end = end;
                        previous.label.push_str(&run.text);
                    } else {
                        links.push(LinkTarget {
                            characters: offset..end,
                            label: run.text.clone(),
                            url,
                            bounds: egui::Rect::NOTHING,
                        });
                    }
                } else if run.link.is_some() {
                    // Unsupported URI schemes remain selectable text, with no implied action.
                    run.style &= !(16 | 32);
                }
                offset = end;
            }
        }
        Self {
            source,
            runs,
            links,
            galley: None,
            style: None,
        }
    }

    fn show(&mut self, ui: &mut egui::Ui, kind: TextKind) {
        let style = LayoutStyle {
            width: if kind == TextKind::Code {
                f32::INFINITY
            } else {
                ui.available_width().max(1.0)
            },
            pixels_per_point: ui.ctx().pixels_per_point(),
            body: TextStyle::Body.resolve(ui.style()),
            code: TextStyle::Monospace.resolve(ui.style()),
            color: ui.visuals().text_color(),
            strong: ui.visuals().strong_text_color(),
            link: ui.visuals().hyperlink_color,
            code_background: ui.visuals().code_bg_color,
            kind,
        };
        if self.style.as_ref() != Some(&style) || self.galley.is_none() {
            let mut job = LayoutJob::default();
            job.wrap.max_width = style.width;
            if let Some(runs) = &self.runs {
                for run in runs {
                    job.append(
                        &run.text,
                        0.0,
                        text_format(&style, run.style, run.link.is_some()),
                    );
                }
            } else {
                job.append(&self.source, 0.0, text_format(&style, 0, false));
            }
            let galley = ui.fonts_mut(|fonts| fonts.layout_job(job));
            self.layout_links(&galley);
            self.galley = Some(galley);
            self.style = Some(style);
        }
        let Some(galley) = self.galley.as_ref() else {
            return;
        };
        // Label accepts Arc<Galley>; unchanged text does not become a newly cloned String.
        let sense = if self.links.is_empty() {
            Sense::hover()
        } else {
            Sense::click()
        };
        let response = ui.add(Label::new(Arc::clone(galley)).selectable(true).sense(sense));
        self.show_accessible_links(ui, &response);
        if let Some(pointer) = response.hover_pos() {
            let character = galley.cursor_from_pos(pointer - response.rect.min).index.0;
            if let Some(target) = self
                .links
                .iter()
                .find(|link| link.characters.contains(&character))
            {
                ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
                if response.clicked() {
                    // Only a deliberate click can dispatch a previously validated standard URI.
                    ui.ctx().open_url(egui::OpenUrl::new_tab(&target.url));
                }
                response.on_hover_text(&target.url);
            }
        }
    }

    fn layout_links(&mut self, galley: &Galley) {
        if self.links.is_empty() {
            return;
        }
        for link in &mut self.links {
            link.bounds = egui::Rect::NOTHING;
        }
        let mut character = 0;
        let mut link_index = 0;
        // The same scalar-character offsets used by pointer hit testing locate each
        // link. Scan the retained layout once, including omitted newline glyphs.
        for row in &galley.rows {
            for glyph in &row.glyphs {
                while self
                    .links
                    .get(link_index)
                    .is_some_and(|link| character >= link.characters.end)
                {
                    link_index += 1;
                }
                if let Some(link) = self.links.get_mut(link_index)
                    && link.characters.contains(&character)
                {
                    let left = row.pos.x + glyph.pos.x;
                    let bounds = egui::Rect::from_min_max(
                        egui::pos2(left, row.pos.y),
                        egui::pos2(left + glyph.advance_width, row.pos.y + row.size.y),
                    );
                    link.bounds = link.bounds.union(bounds);
                }
                character += 1;
            }
            character += usize::from(row.ends_with_newline);
        }
    }

    fn show_accessible_links(&self, ui: &mut egui::Ui, response: &egui::Response) {
        use egui::accesskit::{Action, Role};
        if self.links.is_empty() || !ui.ctx().has_accesskit_node(response.id) {
            return;
        }
        // Pointer clicks still belong to the selectable paragraph. A screen reader
        // gets a separate target for each link, with no ambiguous paragraph action.
        ui.ctx()
            .accesskit_node_builder(response.id, |node| node.remove_action(Action::Click));
        if !ui.is_rect_visible(response.rect) {
            return;
        }
        let mut links_ui = ui.new_child(
            egui::UiBuilder::new()
                .id_salt(response.id.with("accessible_links"))
                .accessibility_parent(response.id)
                .max_rect(response.rect),
        );
        for link in &self.links {
            let bounds = link
                .bounds
                .translate(response.rect.min.to_vec2())
                .intersect(response.rect)
                .intersect(ui.clip_rect());
            if !bounds.is_positive() {
                continue;
            }
            let id = response.id.with((
                "markdown_link",
                link.characters.start,
                link.characters.end,
                &link.url,
            ));
            // Hover-only regions never claim the paragraph's mouse/touch selection.
            let target = links_ui.interact(bounds, id, Sense::hover());
            target.widget_info(|| {
                egui::WidgetInfo::labeled(Role::Link, target.enabled(), &link.label)
            });
            ui.ctx().accesskit_node_builder(id, |node| {
                node.set_url(link.url.clone());
                if target.enabled() {
                    node.add_action(Action::Click);
                }
            });
            let mut clicked = false;
            ui.input_mut(|input| {
                input.consume_accesskit_action_requests(id, |request| {
                    if request.action == Action::Click {
                        clicked = true;
                        true
                    } else {
                        false
                    }
                });
            });
            if clicked && target.enabled() {
                ui.ctx().open_url(egui::OpenUrl::new_tab(&link.url));
            }
        }
        // Give the accessibility-only group the existing paragraph's bounds;
        // `interact` itself does not advance a child's layout or its visible rect.
        links_ui.advance_cursor_after_rect(response.rect);
    }
}

fn safe_url(target: &str) -> Option<String> {
    if target
        .chars()
        .any(|ch| ch.is_control() || ch.is_whitespace())
    {
        return None;
    }
    let url = reqwest::Url::parse(target).ok()?;
    match url.scheme() {
        "http" | "https" if url.host_str().is_some() => Some(url.into()),
        "mailto" if !url.path().is_empty() => Some(url.into()),
        _ => None,
    }
}

fn text_format(style: &LayoutStyle, bits: u32, has_target: bool) -> TextFormat {
    let code = bits & 8 != 0 || style.kind == TextKind::Code;
    let strong = bits & 1 != 0 || matches!(style.kind, TextKind::Heading(_) | TextKind::Header);
    let mut font = if code {
        style.code.clone()
    } else {
        style.body.clone()
    };
    if let TextKind::Heading(level) = style.kind {
        font.size *= match level {
            1 => 1.6,
            2 => 1.4,
            3 => 1.2,
            _ => 1.1,
        };
    }
    let color = if bits & 16 != 0 && has_target {
        style.link
    } else if strong {
        style.strong
    } else {
        style.color
    };
    TextFormat {
        font_id: font,
        color,
        italics: bits & 2 != 0,
        background: if code {
            style.code_background
        } else {
            Color32::TRANSPARENT
        },
        underline: if bits & 32 != 0 && has_target {
            Stroke::new(1.0, color)
        } else {
            Stroke::NONE
        },
        strikethrough: if bits & 4 != 0 {
            Stroke::new(1.0, color)
        } else {
            Stroke::NONE
        },
        ..Default::default()
    }
}

#[derive(Default)]
struct RawPage {
    page: usize,
    text: Option<CachedText>,
}

impl RawPage {
    fn show(&mut self, ui: &mut egui::Ui, source: &str, id: Id) {
        let pages = source.len().div_ceil(RAW_PAGE_BYTES).max(1);
        self.page = self.page.min(pages - 1);
        ui.push_id(id, |ui| {
            if pages > 1 {
                ui.horizontal(|ui| {
                    if ui
                        .add_enabled(self.page > 0, egui::Button::new("上一段").small())
                        .clicked()
                    {
                        self.page -= 1;
                    }
                    ui.label(
                        egui::RichText::new(format!("原文 · {}/{}", self.page + 1, pages))
                            .small()
                            .weak(),
                    );
                    if ui
                        .add_enabled(self.page + 1 < pages, egui::Button::new("下一段").small())
                        .clicked()
                    {
                        self.page += 1;
                    }
                    if ui.small_button("复制全文").clicked() {
                        ui.ctx().copy_text(source.to_owned());
                    }
                });
            }
            let mut start = self.page * RAW_PAGE_BYTES;
            let mut end = ((self.page + 1) * RAW_PAGE_BYTES).min(source.len());
            while !source.is_char_boundary(start) {
                start -= 1;
            }
            while !source.is_char_boundary(end) {
                end -= 1;
            }
            let visible = &source[start..end];
            if self
                .text
                .as_ref()
                .is_none_or(|cached| cached.source != visible)
            {
                self.text = Some(CachedText::plain(visible.to_owned()));
            }
            if let Some(text) = &mut self.text {
                text.show(ui, TextKind::Body);
            }
        });
    }
}
