//! Native depth-first file enumeration. ASCII predicates stay native; Java-compatible Unicode
//! or unusual glob predicates return synchronously to Kotlin on this same calling thread.

use crate::{
    Input, LiteralNeedle, STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer,
    check_cancelled, task,
};
use std::ffi::c_void;
use std::fs;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::ptr;
use std::slice;
use std::sync::atomic::AtomicBool;
use std::time::{Duration, Instant};

const MAGIC: u32 = 0x3146_5853;
const MAX_REQUEST: usize = 1024 * 1024;
const MAX_RESPONSE: usize = 4 * 1024 * 1024;
const MAX_NATIVE_PATTERN: usize = 4096;
const CALLBACK_FAILED: i32 = 4;
const KOTLIN_COMPATIBILITY: i32 = 5;
const TIME_END: i32 = 6;

type Predicate = unsafe extern "C" fn(*mut c_void, u32, *const u8, usize) -> i32;

struct Request<'a> {
    kind: u32,
    max_depth: u32,
    limit: usize,
    max_visited: u32,
    time_budget_ms: u32,
    root: &'a str,
    name: &'a str,
    pattern: &'a str,
    exclude: &'a str,
}

impl<'a> Request<'a> {
    fn parse(bytes: &'a [u8]) -> Option<Self> {
        let mut input = Input { bytes, at: 0 };
        if input.u32()? != MAGIC {
            return None;
        }
        let kind = input.u32()?;
        let max_depth = input.u32()?;
        let limit = input.u32()? as usize;
        let max_visited = input.u32()?;
        let time_budget_ms = input.u32()?;
        let root = input.string()?;
        let name = input.string()?;
        let pattern = input.string()?;
        let exclude = input.string()?;
        if kind > 2
            || !(1..=30).contains(&max_depth)
            || !(1..=500).contains(&limit)
            || !(100..=500_000).contains(&max_visited)
            // Kotlin has already spent part of the overall budget preparing the request.
            || !(1..=60_000).contains(&time_budget_ms)
            || !Path::new(root).is_absolute()
            || root.contains('\0')
            || (name.is_empty() && pattern.is_empty())
            || input.at != bytes.len()
        {
            return None;
        }
        Some(Self {
            kind,
            max_depth,
            limit,
            max_visited,
            time_budget_ms,
            root,
            name,
            pattern,
            exclude,
        })
    }
}

/// The callback uses mode 0 for a candidate matcher and 1 for a directory-prefix predicate.
/// It returns 0/1, or a negative value while preserving a pending JNI exception.
///
/// # Safety
/// Request/output memory and callback/context must remain valid throughout the synchronous call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_find_scan(
    id: u64,
    request: *const u8,
    len: usize,
    predicate: Option<Predicate>,
    context: *mut c_void,
    output: *mut SearchBuffer,
) -> i32 {
    if output.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        });
    }
    if request.is_null() || len == 0 || len > MAX_REQUEST {
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let cancelled = task(id).ok_or(STATUS_REQUEST_ERROR)?;
        check_cancelled(&cancelled)?;
        let bytes = unsafe { slice::from_raw_parts(request, len) };
        let request = Request::parse(bytes).ok_or(STATUS_REQUEST_ERROR)?;
        let bytes = match Find::new(&request, &cancelled, predicate, context) {
            Ok(mut state) => {
                match state.walk(Path::new(request.root), 0) {
                    Ok(()) => {}
                    Err(TIME_END) => state.reason = 3,
                    Err(error) => return Err(error),
                }
                state.response()?
            }
            // Preparation consumes the same budget as walking, but visits no filesystem nodes.
            Err(TIME_END) => encode_response(0, 3, Vec::new())?,
            Err(error) => return Err(error),
        };
        check_cancelled(&cancelled)?;
        let bytes = bytes.into_boxed_slice();
        let len = bytes.len();
        let data = Box::into_raw(bytes) as *mut u8;
        unsafe {
            output.write(SearchBuffer { data, len });
        }
        Ok(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(error)) => error,
        Err(_) => STATUS_PANIC,
    }
}

struct Clock<'a> {
    cancelled: &'a AtomicBool,
    started: Instant,
    budget: Duration,
}

impl Clock<'_> {
    fn check(&self) -> Result<(), i32> {
        check_cancelled(self.cancelled)?;
        if self.started.elapsed() >= self.budget {
            Err(TIME_END)
        } else {
            Ok(())
        }
    }
}

struct Find<'a> {
    request: &'a Request<'a>,
    clock: Clock<'a>,
    predicate: Option<Predicate>,
    context: *mut c_void,
    base: String,
    prefix: Vec<&'a str>,
    glob: Option<Glob>,
    name: Option<LiteralNeedle<'a>>,
    visited: u32,
    reason: u32,
    hits: Vec<String>,
}

impl<'a> Find<'a> {
    fn new(
        request: &'a Request<'a>,
        cancelled: &'a AtomicBool,
        predicate: Option<Predicate>,
        context: *mut c_void,
    ) -> Result<Self, i32> {
        let clock = Clock {
            cancelled,
            started: Instant::now(),
            budget: Duration::from_millis(request.time_budget_ms as u64),
        };
        clock.check()?;
        let prefix_text = request
            .pattern
            .trim_start_matches('/')
            .rsplit_once('/')
            .map(|(prefix, _)| prefix)
            .unwrap_or("");
        let prefix = prefix_text
            .split('/')
            .take_while(|part| !part.is_empty() && !part.contains('*') && !part.contains('?'))
            .collect();
        clock.check()?;
        let glob = if request.pattern.is_empty() {
            None
        } else {
            Glob::compile(request.pattern)
        };
        clock.check()?;
        let name = if request.pattern.is_empty() && request.name.is_ascii() {
            Some(LiteralNeedle::new(request.name, true, cancelled)?)
        } else {
            None
        };
        clock.check()?;
        let base = request
            .root
            .replace('\\', "/")
            .trim_end_matches('/')
            .to_owned()
            + "/";
        clock.check()?;
        Ok(Self {
            request,
            clock,
            predicate,
            context,
            base,
            prefix,
            glob,
            name,
            visited: 0,
            reason: 0,
            hits: Vec::with_capacity(request.limit),
        })
    }

    fn stopped(&mut self) -> Result<bool, i32> {
        check_cancelled(self.clock.cancelled)?;
        self.reason = if self.hits.len() >= self.request.limit {
            1
        } else if self.visited >= self.request.max_visited {
            2
        } else if self.clock.started.elapsed() >= self.clock.budget {
            3
        } else {
            return Ok(false);
        };
        Ok(true)
    }

    fn callback(&self, path: &str, mode: u32) -> Result<bool, i32> {
        self.clock.check()?;
        let callback = self.predicate.ok_or(STATUS_REQUEST_ERROR)?;
        let value = unsafe { callback(self.context, mode, path.as_ptr(), path.len()) };
        // Callback exceptions win over time/cancel checks, so JNI keeps their original cause.
        if value < 0 {
            return Err(CALLBACK_FAILED);
        }
        self.clock.check()?;
        match value {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err(STATUS_REQUEST_ERROR),
        }
    }

    fn walk(&mut self, directory: &Path, depth: u32) -> Result<(), i32> {
        if depth > self.request.max_depth || self.stopped()? {
            return Ok(());
        }
        let entries = match fs::read_dir(directory) {
            Ok(entries) => entries,
            Err(_) => return Ok(()),
        };
        // Snapshot this directory's enumeration order before descending, like File.listFiles().
        // More than the remaining visit budget plus one cannot be reached by the old walker.
        let remaining = (self.request.max_visited - self.visited) as usize + 1;
        let mut children: Vec<PathBuf> = Vec::new();
        for entry in entries.take(remaining) {
            self.clock.check()?;
            if let Ok(entry) = entry {
                children.push(entry.path());
            }
        }
        for child in children {
            if self.stopped()? {
                return Ok(());
            }
            let path = child.to_str().ok_or(KOTLIN_COMPATIBILITY)?;
            let metadata = fs::metadata(&child).ok(); // Follow symlinks, as File.isDirectory/isFile do.
            let is_dir = metadata.as_ref().is_some_and(|metadata| metadata.is_dir());
            let is_file = metadata.as_ref().is_some_and(|metadata| metadata.is_file());
            self.visited += 1;
            let excluded = !self.request.exclude.is_empty() && path.contains(self.request.exclude);
            let kind_ok = match self.request.kind {
                1 => is_dir,
                2 => true,
                _ => is_file,
            };
            if kind_ok && !excluded && self.matches(path, &child)? {
                self.hits.push(path.to_owned());
            }
            if is_dir && !excluded && self.descend(path)? {
                self.walk(&child, depth + 1)?;
            }
        }
        Ok(())
    }

    fn matches(&self, path: &str, child: &Path) -> Result<bool, i32> {
        if self.request.pattern.is_empty() {
            let name = child
                .file_name()
                .and_then(|name| name.to_str())
                .ok_or(KOTLIN_COMPATIBILITY)?;
            return match &self.name {
                Some(needle) if name.is_ascii() => {
                    self.clock.check()?;
                    let result = needle.matches(name, self.clock.cancelled)?;
                    self.clock.check()?;
                    Ok(result)
                }
                _ => self.callback(path, 0),
            };
        }
        let normalized;
        let value = if self.request.pattern.contains('/') {
            normalized = path.replace('\\', "/");
            normalized.strip_prefix(&self.base).unwrap_or(&normalized)
        } else {
            child
                .file_name()
                .and_then(|name| name.to_str())
                .ok_or(KOTLIN_COMPATIBILITY)?
        };
        match &self.glob {
            Some(glob) if value.is_ascii() => glob.matches(value.as_bytes(), &self.clock),
            _ => self.callback(path, 0),
        }
    }

    fn descend(&self, path: &str) -> Result<bool, i32> {
        if self.prefix.is_empty() {
            return Ok(true);
        }
        let normalized = path.replace('\\', "/");
        let relative = normalized.strip_prefix(&self.base).unwrap_or(&normalized);
        for (part, expected) in relative.split('/').zip(&self.prefix) {
            self.clock.check()?;
            if !part.is_ascii() || !expected.is_ascii() {
                return self.callback(path, 1);
            }
            if !part.eq_ignore_ascii_case(expected) {
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn response(self) -> Result<Vec<u8>, i32> {
        encode_response(self.visited, self.reason, self.hits)
    }
}

fn encode_response(visited: u32, reason: u32, hits: Vec<String>) -> Result<Vec<u8>, i32> {
    let size = 16usize
        .checked_add(hits.iter().map(|path| 4 + path.len()).sum())
        .ok_or(STATUS_REQUEST_ERROR)?;
    if size > MAX_RESPONSE {
        return Err(KOTLIN_COMPATIBILITY);
    }
    let mut bytes = Vec::with_capacity(size);
    for integer in [MAGIC, visited, reason, hits.len() as u32] {
        bytes.extend_from_slice(&integer.to_le_bytes());
    }
    for path in hits {
        bytes.extend_from_slice(&(path.len() as u32).to_le_bytes());
        bytes.extend_from_slice(path.as_bytes());
    }
    Ok(bytes)
}

#[derive(Clone, Copy)]
enum Token {
    Literal(u8),
    One(bool),
    Star(bool),
} // bool means regex dot: excludes CR/LF in ASCII.

struct Glob {
    tokens: Vec<Token>,
}

impl Glob {
    fn compile(pattern: &str) -> Option<Self> {
        // The legacy Regex builder leaves backslashes and braces unescaped. Keep its escapes
        // and quantifier behavior in Kotlin instead of interpreting them as literal glob bytes.
        if !pattern.is_ascii()
            || pattern
                .bytes()
                .any(|byte| matches!(byte, b'\\' | b'{' | b'}'))
            || pattern.len() > MAX_NATIVE_PATTERN
        {
            return None;
        }
        let path = pattern.contains('/');
        let bytes = if path {
            pattern.trim_start_matches('/').as_bytes()
        } else {
            pattern.as_bytes()
        };
        let mut at = 0;
        let mut tokens = Vec::with_capacity(bytes.len());
        while at < bytes.len() {
            match bytes[at] {
                b'*' if path && bytes.get(at + 1) == Some(&b'*') => {
                    tokens.push(Token::Star(true));
                    at += 2;
                }
                b'*' => {
                    tokens.push(Token::Star(!path));
                    at += 1;
                }
                b'?' => {
                    tokens.push(Token::One(!path));
                    at += 1;
                }
                byte => {
                    tokens.push(Token::Literal(byte));
                    at += 1;
                }
            }
        }
        Some(Self { tokens })
    }

    fn matches(&self, bytes: &[u8], clock: &Clock<'_>) -> Result<bool, i32> {
        let mut current = vec![false; bytes.len() + 1];
        let mut next = vec![false; bytes.len() + 1];
        current[0] = true;
        let mut work = 0usize;
        for token in &self.tokens {
            clock.check()?;
            next.fill(false);
            for at in 0..=bytes.len() {
                work += 1;
                if work % 8192 == 0 {
                    clock.check()?;
                }
                next[at] = match *token {
                    Token::Literal(byte) => {
                        at > 0 && current[at - 1] && bytes[at - 1].eq_ignore_ascii_case(&byte)
                    }
                    Token::One(dot) => at > 0 && current[at - 1] && allowed(bytes[at - 1], dot),
                    Token::Star(dot) => {
                        current[at] || (at > 0 && next[at - 1] && allowed(bytes[at - 1], dot))
                    }
                };
            }
            std::mem::swap(&mut current, &mut next);
        }
        clock.check()?;
        Ok(current[bytes.len()])
    }
}

fn allowed(byte: u8, dot: bool) -> bool {
    if dot {
        byte != b'\n' && byte != b'\r'
    } else {
        byte != b'/'
    }
}
