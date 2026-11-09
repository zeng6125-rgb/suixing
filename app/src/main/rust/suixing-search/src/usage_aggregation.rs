//! Pure usage folds over bounded UTF-16 key/numeric metadata. No transcript bodies, Android
//! dates, file descriptors, generations, deletion commits or caches are owned here.

use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer, java_whitespace};
use std::collections::{HashMap, HashSet};
use std::hash::Hash;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::{ptr, slice};

const MAGIC: u32 = 0x3141_5855;
const MERGE: u32 = 1;
const SNAPSHOT: u32 = 2;
const WINDOW: u32 = 3;
const TOTALS: u32 = 4;
const STATUS_COMPATIBILITY: i32 = 4;
const MAX_BYTES: usize = 16 * 1024 * 1024;
const MAX_ROWS: usize = 262_144;
const MAX_KEYS: usize = 65_536;

/// # Safety
/// The caller retains length immutable readable request bytes and aligned writable output
/// storage for this synchronous call. Successful storage is released with sx_search_free.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_usage_aggregate(
    request: *const u8,
    length: usize,
    output: *mut SearchBuffer,
) -> i32 {
    if output.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    // SAFETY: output is writable by the ABI contract.
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        })
    };
    if length > MAX_BYTES {
        return STATUS_COMPATIBILITY;
    }
    if request.is_null() || length < 12 {
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the caller owns this immutable bounded request until the call exits.
        let bytes = unsafe { slice::from_raw_parts(request, length) };
        let bytes = aggregate(bytes)?;
        let owned = bytes.into_boxed_slice();
        let len = owned.len();
        let data = Box::into_raw(owned) as *mut u8;
        // SAFETY: the caller still owns the output storage.
        unsafe { output.write(SearchBuffer { data, len }) };
        Ok(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

#[derive(Clone, Copy, Default)]
struct Tally([i64; 4]);

impl Tally {
    fn add(&mut self, other: Self) {
        for (value, added) in self.0.iter_mut().zip(other.0) {
            *value = value.wrapping_add(added);
        }
    }
    fn total(self) -> i64 {
        self.0[0].wrapping_add(self.0[1])
    }
}

#[derive(Clone, Copy, Default)]
struct Bucket {
    tokens: Tally,
    reported: i64,
    unreported: i64,
}

impl Bucket {
    fn add(&mut self, other: Self) {
        self.tokens.add(other.tokens);
        self.reported = self.reported.wrapping_add(other.reported);
        self.unreported = self.unreported.wrapping_add(other.unreported);
    }
}

/// Hashing only locates entries. Observable order always follows the first insertion.
struct Ordered<K, V> {
    entries: Vec<(K, V)>,
    indices: HashMap<K, usize>,
}

impl<K, V> Default for Ordered<K, V> {
    fn default() -> Self {
        Self {
            entries: Vec::new(),
            indices: HashMap::new(),
        }
    }
}

impl<K: Copy + Eq + Hash, V: Default> Ordered<K, V> {
    fn entry(&mut self, key: K) -> &mut V {
        let index = if let Some(&index) = self.indices.get(&key) {
            index
        } else {
            let index = self.entries.len();
            self.indices.insert(key, index);
            self.entries.push((key, V::default()));
            index
        };
        &mut self.entries[index].1
    }
    fn insert_unique(&mut self, key: K, value: V) -> Result<(), i32> {
        if self.indices.contains_key(&key) {
            return Err(STATUS_REQUEST_ERROR);
        }
        self.indices.insert(key, self.entries.len());
        self.entries.push((key, value));
        Ok(())
    }
}

type Tallies = Ordered<u32, Tally>;
type Buckets = Ordered<u32, Bucket>;
type DailyModels = Ordered<u32, Buckets>;

struct Input<'a> {
    bytes: &'a [u8],
    at: usize,
    rows: usize,
    keys: Vec<Vec<u16>>,
    blank_keys: Vec<bool>,
}

impl<'a> Input<'a> {
    fn take(&mut self, count: usize) -> Result<&'a [u8], i32> {
        let end = self.at.checked_add(count).ok_or(STATUS_REQUEST_ERROR)?;
        let value = self.bytes.get(self.at..end).ok_or(STATUS_REQUEST_ERROR)?;
        self.at = end;
        Ok(value)
    }
    fn u32(&mut self) -> Result<u32, i32> {
        let value = self.take(4)?;
        Ok(u32::from_le_bytes([value[0], value[1], value[2], value[3]]))
    }
    fn i32(&mut self) -> Result<i32, i32> {
        Ok(self.u32()? as i32)
    }
    fn i64(&mut self) -> Result<i64, i32> {
        let value = self.take(8)?;
        Ok(i64::from_le_bytes([
            value[0], value[1], value[2], value[3], value[4], value[5], value[6], value[7],
        ]))
    }
    fn key(&mut self) -> Result<u32, i32> {
        let index = self.u32()?;
        if index as usize >= self.keys.len() {
            return Err(STATUS_REQUEST_ERROR);
        }
        Ok(index)
    }
    fn count(&mut self, min_bytes: usize) -> Result<usize, i32> {
        let count = self.u32()? as usize;
        if min_bytes > 0 && count > (self.bytes.len() - self.at) / min_bytes {
            return Err(STATUS_REQUEST_ERROR);
        }
        self.rows = self.rows.checked_add(count).ok_or(STATUS_REQUEST_ERROR)?;
        if self.rows > MAX_ROWS {
            return Err(STATUS_COMPATIBILITY);
        }
        Ok(count)
    }
    fn flag(&mut self) -> Result<bool, i32> {
        match self.u32()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err(STATUS_REQUEST_ERROR),
        }
    }
    fn tally(&mut self) -> Result<Tally, i32> {
        Ok(Tally([self.i64()?, self.i64()?, self.i64()?, self.i64()?]))
    }
    fn bucket(&mut self) -> Result<Bucket, i32> {
        Ok(Bucket {
            tokens: self.tally()?,
            reported: self.i64()?,
            unreported: self.i64()?,
        })
    }
    fn tallies(&mut self) -> Result<Tallies, i32> {
        let count = self.count(36)?;
        let mut values = Tallies::default();
        for _ in 0..count {
            let key = self.key()?;
            let value = self.tally()?;
            values.insert_unique(key, value)?;
        }
        Ok(values)
    }
    fn daily_models(&mut self) -> Result<DailyModels, i32> {
        let count = self.count(8)?;
        let mut days = DailyModels::default();
        for _ in 0..count {
            let date = self.key()?;
            let count = self.count(52)?;
            let mut models = Buckets::default();
            for _ in 0..count {
                let model = self.key()?;
                let value = self.bucket()?;
                models.insert_unique(model, value)?;
            }
            // Empty daily model maps in an existing seed must survive a merge too.
            days.insert_unique(date, models)?;
        }
        Ok(days)
    }
    fn done(&self) -> Result<(), i32> {
        if self.at == self.bytes.len() {
            Ok(())
        } else {
            Err(STATUS_REQUEST_ERROR)
        }
    }
}

struct Output {
    bytes: Vec<u8>,
    rows: usize,
}

impl Output {
    fn new(operation: u32) -> Result<Self, i32> {
        let mut output = Self {
            bytes: Vec::new(),
            rows: 0,
        };
        output.u32(MAGIC)?;
        output.u32(operation)?;
        Ok(output)
    }
    fn append(&mut self, value: &[u8]) -> Result<(), i32> {
        if self
            .bytes
            .len()
            .checked_add(value.len())
            .is_none_or(|size| size > MAX_BYTES)
        {
            return Err(STATUS_COMPATIBILITY);
        }
        self.bytes.extend_from_slice(value);
        Ok(())
    }
    fn u32(&mut self, value: u32) -> Result<(), i32> {
        self.append(&value.to_le_bytes())
    }
    fn i32(&mut self, value: i32) -> Result<(), i32> {
        self.append(&value.to_le_bytes())
    }
    fn i64(&mut self, value: i64) -> Result<(), i32> {
        self.append(&value.to_le_bytes())
    }
    fn count(&mut self, count: usize) -> Result<(), i32> {
        self.rows = self.rows.checked_add(count).ok_or(STATUS_COMPATIBILITY)?;
        if self.rows > MAX_ROWS {
            return Err(STATUS_COMPATIBILITY);
        }
        self.u32(count as u32)
    }
    fn tally(&mut self, value: Tally) -> Result<(), i32> {
        for field in value.0 {
            self.i64(field)?;
        }
        Ok(())
    }
    fn bucket(&mut self, value: Bucket) -> Result<(), i32> {
        self.tally(value.tokens)?;
        self.i64(value.reported)?;
        self.i64(value.unreported)
    }
    fn tallies(&mut self, values: &Tallies) -> Result<(), i32> {
        self.count(values.entries.len())?;
        for &(key, value) in &values.entries {
            self.u32(key)?;
            self.tally(value)?;
        }
        Ok(())
    }
    fn daily_models(&mut self, values: &DailyModels) -> Result<(), i32> {
        self.count(values.entries.len())?;
        for (date, models) in &values.entries {
            self.u32(*date)?;
            self.count(models.entries.len())?;
            for &(model, value) in &models.entries {
                self.u32(model)?;
                self.bucket(value)?;
            }
        }
        Ok(())
    }
}

fn aggregate(bytes: &[u8]) -> Result<Vec<u8>, i32> {
    let mut input = Input {
        bytes,
        at: 0,
        rows: 0,
        keys: Vec::new(),
        blank_keys: Vec::new(),
    };
    if input.u32()? != MAGIC {
        return Err(STATUS_REQUEST_ERROR);
    }
    let operation = input.u32()?;
    if !(MERGE..=TOTALS).contains(&operation) {
        return Err(STATUS_REQUEST_ERROR);
    }
    let key_count = input.u32()? as usize;
    if key_count > (input.bytes.len() - input.at) / 4 {
        return Err(STATUS_REQUEST_ERROR);
    }
    if key_count > MAX_KEYS {
        return Err(STATUS_COMPATIBILITY);
    }
    let mut seen = HashSet::new();
    for _ in 0..key_count {
        let length = input.u32()? as usize;
        let raw = input.take(length.checked_mul(2).ok_or(STATUS_REQUEST_ERROR)?)?;
        let key: Vec<u16> = raw
            .chunks_exact(2)
            .map(|unit| u16::from_le_bytes([unit[0], unit[1]]))
            .collect();
        // A duplicate pool entry would split same-name aggregation across distinct indices.
        if !seen.insert(key.clone()) {
            return Err(STATUS_REQUEST_ERROR);
        }
        // Classify each UTF-16 model key once rather than rescanning it for every message.
        input.blank_keys.push(operation == MERGE && blank(&key));
        input.keys.push(key);
    }
    let mut output = Output::new(operation)?;
    match operation {
        MERGE => merge(&mut input, &mut output)?,
        SNAPSHOT => snapshot(&mut input, &mut output)?,
        WINDOW => window(&mut input, &mut output)?,
        TOTALS => totals(&mut input, &mut output)?,
        _ => return Err(STATUS_REQUEST_ERROR),
    }
    input.done()?;
    Ok(output.bytes)
}

fn blank(key: &[u16]) -> bool {
    key.iter()
        .all(|&unit| char::from_u32(u32::from(unit)).is_some_and(java_whitespace))
}

fn merge(input: &mut Input<'_>, output: &mut Output) -> Result<(), i32> {
    let unknown = input.key()?;
    let mut newest = input.i64()?;
    let mut days = input.tallies()?;
    let mut models = input.tallies()?;
    let mut daily_models = input.daily_models()?;
    let count = input.count(32)?;
    for _ in 0..count {
        let date = input.key()?;
        let raw_model = input.key()?;
        let model = if input.blank_keys[raw_model as usize] {
            unknown
        } else {
            raw_model
        };
        let timestamp = input.i64()?;
        let prompt = input.i32()?;
        let completion = input.i32()?;
        let reasoning = input.i32()?;
        let cached = input.i32()?;
        let known = prompt > 0 || completion > 0;
        let bucket = if known {
            Bucket {
                tokens: Tally([
                    i64::from(prompt.max(0)),
                    i64::from(completion.max(0)),
                    i64::from(reasoning.max(0)),
                    i64::from(cached.max(0)),
                ]),
                reported: 1,
                unreported: 0,
            }
        } else {
            Bucket {
                tokens: Tally::default(),
                reported: 0,
                unreported: 1,
            }
        };
        daily_models.entry(date).entry(model).add(bucket);
        if known {
            days.entry(date).add(bucket.tokens);
            models.entry(model).add(bucket.tokens);
        }
        newest = newest.max(timestamp);
    }
    output.tallies(&days)?;
    output.tallies(&models)?;
    output.daily_models(&daily_models)?;
    output.i64(newest)
}

fn snapshot(input: &mut Input<'_>, output: &mut Output) -> Result<(), i32> {
    let count = input.count(16)?;
    let mut days = Tallies::default();
    let mut models = Tallies::default();
    let mut coverage = Bucket::default();
    let mut deleted_coverage = Bucket::default();
    for _ in 0..count {
        let deleted = input.flag()?;
        for (key, tally) in input.tallies()?.entries {
            days.entry(key).add(tally);
        }
        for (key, tally) in input.tallies()?.entries {
            models.entry(key).add(tally);
        }
        for (_, by_model) in input.daily_models()?.entries {
            for (_, bucket) in by_model.entries {
                coverage.add(bucket);
                if deleted {
                    deleted_coverage.add(bucket);
                }
            }
        }
    }
    let mut total = Tally::default();
    for &(_, tally) in &days.entries {
        total.add(tally);
    }
    output.tallies(&days)?;
    output.tallies(&models)?;
    output.tally(total)?;
    output.bucket(coverage)?;
    output.bucket(deleted_coverage)
}

fn window(input: &mut Input<'_>, output: &mut Output) -> Result<(), i32> {
    let count = input.count(4)?;
    let mut dates = Vec::with_capacity(count);
    let mut selected = HashSet::new();
    for _ in 0..count {
        let date = input.key()?;
        if !selected.insert(date) {
            return Err(STATUS_REQUEST_ERROR);
        }
        dates.push(date);
    }
    let count = input.count(4)?;
    let mut days = Buckets::default();
    let mut models = Buckets::default();
    for _ in 0..count {
        for (date, by_model) in input.daily_models()?.entries {
            if selected.contains(&date) {
                for (model, bucket) in by_model.entries {
                    days.entry(date).add(bucket);
                    models.entry(model).add(bucket);
                }
            }
        }
    }
    let mut usage = Bucket::default();
    for &(_, bucket) in &days.entries {
        usage.add(bucket);
    }
    output.count(dates.len())?;
    for date in dates {
        let bucket = days
            .indices
            .get(&date)
            .map(|&index| days.entries[index].1)
            .unwrap_or_default();
        output.u32(date)?;
        output.bucket(bucket)?;
    }
    // JVM compares signed wrapped totals, then String's raw UTF-16 lexicographic order.
    models
        .entries
        .sort_by(|(left_key, left), (right_key, right)| {
            right
                .tokens
                .total()
                .cmp(&left.tokens.total())
                .then_with(|| input.keys[*left_key as usize].cmp(&input.keys[*right_key as usize]))
        });
    output.count(models.entries.len())?;
    for (model, bucket) in models.entries {
        output.u32(model)?;
        output.bucket(bucket)?;
    }
    output.bucket(usage)
}

struct ChatTotals {
    turns: i32,
    steps: i32,
    prompt: i32,
    completion: i32,
    cached: i32,
    context: i32,
    elapsed: i64,
    first_token: i64,
    longest: i64,
    trailing_prompt: i64,
    trailing_completion: i64,
    run: u32,
}

impl ChatTotals {
    fn read(input: &mut Input<'_>) -> Result<Self, i32> {
        Ok(Self {
            turns: input.i32()?,
            steps: input.i32()?,
            prompt: input.i32()?,
            completion: input.i32()?,
            cached: input.i32()?,
            context: input.i32()?,
            elapsed: input.i64()?,
            first_token: input.i64()?,
            longest: input.i64()?,
            trailing_prompt: input.i64()?,
            trailing_completion: input.i64()?,
            run: input.key()?,
        })
    }
    fn write(self, output: &mut Output) -> Result<(), i32> {
        for field in [
            self.turns,
            self.steps,
            self.prompt,
            self.completion,
            self.cached,
            self.context,
        ] {
            output.i32(field)?;
        }
        for field in [
            self.elapsed,
            self.first_token,
            self.longest,
            self.trailing_prompt,
            self.trailing_completion,
        ] {
            output.i64(field)?;
        }
        output.u32(self.run)
    }
}

fn ascii(key: &[u16], value: &[u8]) -> bool {
    key.len() == value.len()
        && key
            .iter()
            .zip(value)
            .all(|(&left, &right)| left == u16::from(right))
}

fn settle(keys: &[Vec<u16>], run: u32, prompt: i64, completion: i64, longest: &mut i64) {
    if !keys[run as usize].is_empty() {
        *longest = (*longest).max(prompt.wrapping_add(completion));
    }
}

fn totals(input: &mut Input<'_>, output: &mut Output) -> Result<(), i32> {
    let mut totals = ChatTotals::read(input)?;
    let mut run = totals.run;
    let mut prompt = totals.trailing_prompt;
    let mut completion = totals.trailing_completion;
    let mut longest = totals.longest;
    let count = input.count(40)?;
    for _ in 0..count {
        let role = input.key()?;
        let steer = input.flag()?;
        let id = input.key()?;
        let message_prompt = input.i32()?;
        let message_completion = input.i32()?;
        let message_cached = input.i32()?;
        let elapsed = input.i64()?;
        let first_token = input.i64()?;
        if ascii(&input.keys[role as usize], b"user") && !steer {
            totals.turns = totals.turns.wrapping_add(1);
        }
        if ascii(&input.keys[role as usize], b"tool") {
            totals.steps = totals.steps.wrapping_add(1);
        }
        // ChatTotals preserves raw negative values, unlike the usage page's known-token buckets.
        totals.prompt = totals.prompt.wrapping_add(message_prompt);
        totals.completion = totals.completion.wrapping_add(message_completion);
        totals.cached = totals.cached.wrapping_add(message_cached);
        totals.elapsed = totals.elapsed.wrapping_add(elapsed);
        totals.first_token = totals.first_token.wrapping_add(first_token);
        if message_prompt > 0 {
            totals.context = message_prompt;
        }
        // plus(totalsOfMessage) compares the accumulator against the message's default zero.
        // Empty input must retain an initially negative longest value instead.
        totals.longest = totals.longest.max(0);
        if id != run {
            settle(&input.keys, run, prompt, completion, &mut longest);
            run = id;
            prompt = 0;
            completion = 0;
        }
        if message_prompt > 0 {
            prompt = i64::from(message_prompt);
        }
        completion = completion.wrapping_add(i64::from(message_completion));
    }
    settle(&input.keys, run, prompt, completion, &mut longest);
    totals.longest = longest.max(totals.longest);
    totals.run = run;
    totals.trailing_prompt = prompt;
    totals.trailing_completion = completion;
    totals.write(output)
}
