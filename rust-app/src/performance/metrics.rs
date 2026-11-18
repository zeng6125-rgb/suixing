//! Worker-only sampling. CPU requires two monotonic samples; unavailable nodes
//! remain unknown for this visible-page instance. PSS is throttled to five seconds.
use super::{Device, frames::FrameLease};
use std::{
    fs::File,
    io::Read,
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
pub const PSS_INTERVAL_SECONDS: u64 = 5;
const MAX_NODE_BYTES: usize = 16 * 1024;
pub struct Sampler {
    last_wall: Option<Instant>,
    last_cpu: Option<u128>,
    last_system: Option<(u128, u128)>,
    system_readable: Option<bool>,
    gpu_probed: bool,
    gpu_busy: Option<PathBuf>,
    gpu_frequency: Option<PathBuf>,
    last_pss_at: Option<Instant>,
    last_pss: Option<f64>,
    frames: Option<FrameLease>,
    visibility: Arc<AtomicBool>,
}
impl Default for Sampler {
    fn default() -> Self {
        Self {
            last_wall: None,
            last_cpu: None,
            last_system: None,
            system_readable: None,
            gpu_probed: false,
            gpu_busy: None,
            gpu_frequency: None,
            last_pss_at: None,
            last_pss: None,
            frames: None,
            visibility: Arc::new(AtomicBool::new(true)),
        }
    }
}
impl Sampler {
    pub fn with_guard(visibility: Arc<AtomicBool>) -> Self {
        Self {
            visibility,
            ..Default::default()
        }
    }
    pub fn suspend(&mut self) {
        self.frames = None;
        self.last_wall = None;
        self.last_cpu = None;
        self.last_system = None;
    }
    pub fn sample(&mut self) -> Device {
        if !self.visibility.load(Ordering::Acquire) {
            self.suspend();
            return Device::default();
        }
        let wall = Instant::now();
        let cpu = cpu_time_ns();
        let process =
            self.last_cpu
                .zip(cpu)
                .zip(self.last_wall)
                .and_then(|((previous, current), last)| {
                    let elapsed = wall.saturating_duration_since(last).as_nanos();
                    current
                        .checked_sub(previous)
                        .filter(|_| elapsed > 0)
                        .map(|delta| delta as f64 * 100.0 / elapsed as f64)
                });
        let stat = self.system_stat();
        let system = self.last_system.zip(stat).and_then(
            |((previous_total, previous_idle), (total, idle))| {
                let delta = total
                    .checked_sub(previous_total)
                    .filter(|delta| *delta > 0)?;
                let idle_delta = idle.checked_sub(previous_idle)?;
                (idle_delta <= delta).then(|| (delta - idle_delta) as f64 * 100.0 / delta as f64)
            },
        );
        self.last_wall = Some(wall);
        self.last_cpu = cpu;
        self.last_system = stat;
        if self.last_pss_at.is_none_or(|last| {
            wall.saturating_duration_since(last) >= Duration::from_secs(PSS_INTERVAL_SECONDS)
        }) {
            self.last_pss_at = Some(wall);
            self.last_pss = android_pss_mb();
        }
        self.probe_gpu();
        let gpu_percent = self
            .gpu_busy
            .as_deref()
            .and_then(read_integer)
            .filter(|value| *value >= 0)
            .map(|value| (value as f64).clamp(0.0, 100.0));
        let gpu_frequency_mhz = self
            .gpu_frequency
            .as_deref()
            .and_then(read_integer)
            .filter(|value| *value > 0)
            .map(|hz| hz as f64 / 1_000_000.0);
        let heap = android_heap_mb();
        if self.frames.is_none() {
            self.frames = FrameLease::start_with_guard(self.visibility.clone()).ok();
        }
        let frame = self.frames.as_ref().and_then(FrameLease::latest);
        Device {
            process_cpu_percent: process,
            system_cpu_percent: system,
            gpu_percent,
            gpu_frequency_mhz,
            fps: frame.map(|frame| frame.fps),
            p90_frame_ms: frame.map(|frame| frame.p90_ms),
            pss_mb: self.last_pss,
            java_heap_mb: heap.map(|(used, _)| used),
            java_heap_max_mb: heap.map(|(_, maximum)| maximum),
            threads: thread_count(),
        }
    }
    fn system_stat(&mut self) -> Option<(u128, u128)> {
        if self.system_readable == Some(false) {
            return None;
        }
        let text = match read_first_line(Path::new("/proc/stat")) {
            Some(text) => {
                self.system_readable = Some(true);
                text
            }
            None => {
                self.system_readable = Some(false);
                return None;
            }
        };
        let mut fields = text.lines().next()?.split_whitespace();
        if fields.next() != Some("cpu") {
            return None;
        }
        let mut values = Vec::new();
        // guest/guest_nice are already contained in user/nice. Summing only the
        // first eight counters avoids counting virtual CPU time twice.
        for field in fields.take(8) {
            values.push(field.parse::<u128>().ok()?);
        }
        if values.len() < 5 {
            return None;
        }
        let total = values
            .iter()
            .try_fold(0u128, |sum, value| sum.checked_add(*value))?;
        Some((total, values[3].checked_add(values[4])?))
    }
    fn probe_gpu(&mut self) {
        if self.gpu_probed {
            return;
        }
        self.gpu_probed = true;
        self.gpu_busy = ["/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"]
            .into_iter()
            .map(PathBuf::from)
            .find(|path| read_node(path).is_some());
        self.gpu_frequency = [
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
        ]
        .into_iter()
        .map(PathBuf::from)
        .find(|path| read_node(path).is_some());
        if self.gpu_frequency.is_none() {
            if let Ok(entries) = std::fs::read_dir("/sys/class/devfreq") {
                for entry in entries.take(64).flatten() {
                    let path = entry.path();
                    if read_node(&path.join("name"))
                        .is_some_and(|name| name.to_ascii_lowercase().contains("gpu"))
                        && read_node(&path.join("cur_freq")).is_some()
                    {
                        self.gpu_frequency = Some(path.join("cur_freq"));
                        break;
                    }
                }
            }
        }
    }
}
fn read_node(path: &Path) -> Option<String> {
    let mut bytes = Vec::new();
    File::open(path)
        .ok()?
        .take((MAX_NODE_BYTES + 1) as u64)
        .read_to_end(&mut bytes)
        .ok()?;
    if bytes.len() > MAX_NODE_BYTES {
        return None;
    }
    String::from_utf8(bytes).ok()
}
fn read_first_line(path: &Path) -> Option<String> {
    let mut bytes = Vec::new();
    File::open(path)
        .ok()?
        .take(8193)
        .read_to_end(&mut bytes)
        .ok()?;
    if let Some(end) = bytes.iter().position(|byte| *byte == b'\n') {
        bytes.truncate(end);
    }
    if bytes.len() > 8192 {
        return None;
    }
    String::from_utf8(bytes).ok()
}
fn read_integer(path: &Path) -> Option<i64> {
    let text = read_node(path)?;
    let bytes = text.as_bytes();
    let start = bytes
        .iter()
        .position(|byte| byte.is_ascii_digit() || *byte == b'-')?;
    let mut end = start;
    if bytes[start] == b'-' {
        end += 1;
    }
    while end < bytes.len() && bytes[end].is_ascii_digit() {
        end += 1;
    }
    text.get(start..end)?.parse().ok()
}
fn thread_count() -> Option<u64> {
    let text = read_node(Path::new("/proc/self/status"))?;
    text.lines().find_map(|line| {
        line.strip_prefix("Threads:")
            .and_then(|value| value.trim().parse().ok())
    })
}
#[cfg(any(target_os = "android", target_os = "linux"))]
fn cpu_time_ns() -> Option<u128> {
    let mut value = std::mem::MaybeUninit::<libc::timespec>::uninit();
    if unsafe { libc::clock_gettime(libc::CLOCK_PROCESS_CPUTIME_ID, value.as_mut_ptr()) } != 0 {
        return None;
    }
    let value = unsafe { value.assume_init() };
    if value.tv_sec < 0 || !(0..1_000_000_000).contains(&value.tv_nsec) {
        return None;
    }
    (value.tv_sec as u128)
        .checked_mul(1_000_000_000)?
        .checked_add(value.tv_nsec as u128)
}
#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn cpu_time_ns() -> Option<u128> {
    None
}
#[cfg(target_os = "android")]
fn android_pss_mb() -> Option<f64> {
    use jni::{jni_sig, jni_str};
    crate::platform::android::invoke(|env| {
        env.call_static_method(
            jni_str!("android/os/Debug"),
            jni_str!("getPss"),
            jni_sig!("()J"),
            &[],
        )?
        .j()
    })
    .ok()
    .filter(|value| *value >= 0)
    .map(|kb| kb as f64 / 1024.0)
}
#[cfg(not(target_os = "android"))]
fn android_pss_mb() -> Option<f64> {
    None
}
#[cfg(target_os = "android")]
fn android_heap_mb() -> Option<(f64, f64)> {
    use jni::{jni_sig, jni_str};
    crate::platform::android::invoke(|env| {
        let runtime = env
            .call_static_method(
                jni_str!("java/lang/Runtime"),
                jni_str!("getRuntime"),
                jni_sig!("()Ljava/lang/Runtime;"),
                &[],
            )?
            .l()?;
        let total = env
            .call_method(&runtime, jni_str!("totalMemory"), jni_sig!("()J"), &[])?
            .j()?;
        let free = env
            .call_method(&runtime, jni_str!("freeMemory"), jni_sig!("()J"), &[])?
            .j()?;
        let maximum = env
            .call_method(&runtime, jni_str!("maxMemory"), jni_sig!("()J"), &[])?
            .j()?;
        Ok((total, free, maximum))
    })
    .ok()
    .and_then(|(total, free, maximum)| {
        let used = total.checked_sub(free)?;
        (used >= 0 && maximum >= 0)
            .then(|| (used as f64 / 1_048_576.0, maximum as f64 / 1_048_576.0))
    })
}
#[cfg(not(target_os = "android"))]
fn android_heap_mb() -> Option<(f64, f64)> {
    None
}
