//! Application-owned recording, bounded ASR, and asynchronous TTS. Framework
//! acknowledgements are separate from admission; stale generations are ignored.
use super::permissions::{OperationKey, PermissionKind, PermissionOutcome};
use std::{
    collections::{BTreeMap, VecDeque},
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicUsize, Ordering},
    },
};

const MAX_OPERATIONS: usize = 32;
const MAX_AUDIO_BYTES: usize = 16_000 * 2 * 60;
#[derive(Clone)]
pub struct RecordedWav {
    pub bytes: Arc<Vec<u8>>,
    pub samples: u64,
    pub duration_ms: u64,
}
impl std::fmt::Debug for RecordedWav {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RecordedWav")
            .field("bytes", &self.bytes.len())
            .field("duration_ms", &self.duration_ms)
            .finish()
    }
}
#[derive(Clone, Debug)]
pub struct RecordingStatus {
    pub operation: OperationKey,
    pub started: bool,
    pub stopping: bool,
    pub elapsed_ms: u64,
    pub level: f32,
}
#[derive(Clone, Debug, Default)]
pub struct SpeakerStatus {
    pub initialized: bool,
    pub ready: bool,
    pub speaking: bool,
    pub pending: usize,
    pub releasing: bool,
    pub last_error: Option<String>,
}
#[derive(Debug)]
pub enum VoiceResult {
    RecordingFinished {
        operation: OperationKey,
        result: Result<RecordedWav, String>,
    },
    TranscriptionFinished {
        operation: OperationKey,
        result: Result<String, String>,
    },
    SpeechFinished {
        operation: OperationKey,
        result: Result<(), String>,
    },
    /// Only the additive scoped API uses this variant. Legacy UI callers keep
    /// their original SpeechFinished shape and drain behavior.
    ScopedSpeechFinished {
        operation: OperationKey,
        speaker: SpeakerKey,
        result: Result<(), String>,
        cleanup_confirmed: bool,
    },
    SpeechStopFinished {
        operation: OperationKey,
        expected: SpeakerKey,
        result: Result<StopConfirmation, String>,
    },
}
impl VoiceResult {
    fn operation(&self) -> &OperationKey {
        match self {
            Self::RecordingFinished { operation, .. }
            | Self::TranscriptionFinished { operation, .. }
            | Self::SpeechFinished { operation, .. }
            | Self::ScopedSpeechFinished { operation, .. }
            | Self::SpeechStopFinished { operation, .. } => operation,
        }
    }
}
struct Entry {
    operation: OperationKey,
    speaker: Option<SpeakerKey>,
    cancelled: Arc<AtomicBool>,
    finished: bool,
}
struct RecordControl {
    cancelled: Arc<AtomicBool>,
    stop: AtomicBool,
    started: AtomicBool,
    elapsed: AtomicU64,
    level: AtomicU32,
}
#[derive(Default)]
struct Registry {
    operations: BTreeMap<String, Entry>,
    results: VecDeque<VoiceResult>,
    recording: Option<(OperationKey, Arc<RecordControl>)>,
    speaker: SpeakerStatus,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
static ASR_WORKERS: AtomicUsize = AtomicUsize::new(0);
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}
fn admit(operation: &OperationKey) -> Result<Arc<AtomicBool>, String> {
    operation.validate()?;
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    if state.operations.len() >= MAX_OPERATIONS || state.operations.contains_key(&operation.id) {
        return Err("语音操作队列已满或操作身份重复".into());
    }
    let cancelled = Arc::new(AtomicBool::new(false));
    state.operations.insert(
        operation.id.clone(),
        Entry {
            operation: operation.clone(),
            speaker: None,
            cancelled: cancelled.clone(),
            finished: false,
        },
    );
    Ok(cancelled)
}
fn forget(id: &str) {
    registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .operations
        .remove(id);
}
fn emit(result: VoiceResult) {
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    let Some(entry) = state.operations.get_mut(&result.operation().id) else {
        return;
    };
    if entry.finished || &entry.operation != result.operation() {
        return;
    }
    entry.finished = true;
    state.results.push_back(result);
}
pub fn drain_results(limit: usize) -> Vec<VoiceResult> {
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    let count = limit.min(state.results.len());
    let results: Vec<_> = state.results.drain(..count).collect();
    for result in &results {
        state.operations.remove(&result.operation().id);
    }
    results
}
pub fn cancel(operation_id: &str) {
    let cancel = {
        let state = registry().lock().unwrap_or_else(|e| e.into_inner());
        state
            .operations
            .get(operation_id)
            .map(|entry| entry.cancelled.clone())
    };
    if let Some(cancel) = cancel {
        cancel.store(true, Ordering::Release);
    }
    #[cfg(target_os = "android")]
    speaker::wake();
}
pub fn recording_status() -> Option<RecordingStatus> {
    registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .recording
        .as_ref()
        .map(|(operation, control)| RecordingStatus {
            operation: operation.clone(),
            started: control.started.load(Ordering::Acquire),
            stopping: control.stop.load(Ordering::Acquire)
                || control.cancelled.load(Ordering::Acquire),
            elapsed_ms: control.elapsed.load(Ordering::Acquire),
            level: f32::from_bits(control.level.load(Ordering::Acquire)),
        })
}
pub fn speaker_status() -> SpeakerStatus {
    registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .speaker
        .clone()
}
pub fn finish_recording(operation_id: &str) {
    let control = {
        let state = registry().lock().unwrap_or_else(|e| e.into_inner());
        state
            .recording
            .as_ref()
            .filter(|(operation, _)| operation.id == operation_id)
            .map(|(_, control)| control.clone())
    };
    if let Some(control) = control {
        control.stop.store(true, Ordering::Release);
    }
}
pub fn activity_visibility_changed(visible: bool) {
    if !visible {
        let control = {
            let state = registry().lock().unwrap_or_else(|e| e.into_inner());
            state.recording.as_ref().map(|(_, control)| control.clone())
        };
        if let Some(control) = control {
            control.cancelled.store(true, Ordering::Release);
        }
    }
    #[cfg(target_os = "android")]
    speaker::wake();
}
pub fn record(operation: OperationKey) -> Result<(), String> {
    #[cfg(not(target_os = "android"))]
    {
        let _ = operation;
        return Err("当前平台没有录音后端".into());
    }
    #[cfg(target_os = "android")]
    {
        if !super::android::activity_visible() {
            return Err("录音必须由可见的应用页面发起".into());
        }
        if super::permissions::check(PermissionKind::Microphone) != PermissionOutcome::Granted {
            return Err("录音尚未获得麦克风权限".into());
        }
        let cancelled = admit(&operation)?;
        let control = Arc::new(RecordControl {
            cancelled,
            stop: AtomicBool::new(false),
            started: AtomicBool::new(false),
            elapsed: AtomicU64::new(0),
            level: AtomicU32::new(0),
        });
        {
            let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
            if state.recording.is_some() {
                drop(state);
                forget(&operation.id);
                return Err("已有录音正在进行或释放".into());
            }
            state.recording = Some((operation.clone(), control.clone()));
        }
        let worker_operation = operation.clone();
        if let Err(error) = std::thread::Builder::new()
            .name("rust-dictation-recorder".into())
            .spawn(move || {
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    recording::run(&control)
                }))
                .unwrap_or_else(|_| Err("录音异常，未提交音频".into()));
                {
                    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
                    if state
                        .recording
                        .as_ref()
                        .is_some_and(|(operation, _)| operation.id == worker_operation.id)
                    {
                        state.recording = None;
                    }
                }
                emit(VoiceResult::RecordingFinished {
                    operation: worker_operation,
                    result,
                });
            })
        {
            registry()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .recording = None;
            forget(&operation.id);
            return Err(format!("录音线程无法启动：{error}"));
        }
        Ok(())
    }
}
#[cfg(target_os = "android")]
mod recording {
    use super::*;
    use jni::{JValue, jni_sig, jni_str};
    use std::time::{Duration, Instant};
    pub(super) fn run(control: &RecordControl) -> Result<RecordedWav, String> {
        let recorder = super::super::android::invoke(|env| {
            let minimum = env
                .call_static_method(
                    jni_str!("android/media/AudioRecord"),
                    jni_str!("getMinBufferSize"),
                    jni_sig!("(III)I"),
                    &[JValue::Int(16_000), JValue::Int(16), JValue::Int(2)],
                )?
                .i()?;
            if minimum <= 0 || minimum > 1024 * 1024 {
                return Err(jni::errors::Error::NullPtr("audio buffer budget"));
            }
            let record = env.new_object(
                jni_str!("android/media/AudioRecord"),
                jni_sig!("(IIIII)V"),
                &[
                    JValue::Int(6),
                    JValue::Int(16_000),
                    JValue::Int(16),
                    JValue::Int(2),
                    JValue::Int(minimum * 2),
                ],
            )?;
            Ok(Arc::new(env.new_global_ref(record)?))
        })?;
        struct Release {
            recorder: Option<Arc<jni::refs::Global<jni::objects::JObject<'static>>>>,
            started: bool,
        }
        impl Release {
            fn finish(&mut self) -> Result<(), String> {
                let Some(recorder) = self.recorder.as_ref() else {
                    return Ok(());
                };
                let stop = if self.started {
                    super::super::android::invoke(|env| {
                        env.call_method(recorder.as_ref(), jni_str!("stop"), jni_sig!("()V"), &[])?;
                        Ok(())
                    })
                } else {
                    Ok(())
                };
                let released = super::super::android::invoke(|env| {
                    env.call_method(recorder.as_ref(), jni_str!("release"), jni_sig!("()V"), &[])?;
                    Ok(())
                });
                if released.is_ok() {
                    self.recorder = None;
                    self.started = false;
                }
                if stop.is_err() || released.is_err() {
                    return Err("录音停止或释放未确认；本次音频未提交".into());
                }
                Ok(())
            }
        }
        impl Drop for Release {
            fn drop(&mut self) {
                let _ = self.finish();
            }
        }
        let mut release = Release {
            recorder: Some(recorder.clone()),
            started: false,
        };
        if control.cancelled.load(Ordering::Acquire) || !super::super::android::activity_visible() {
            return Err("录音在启动前已取消或页面不可见".into());
        }
        super::super::android::invoke(|env| {
            if env
                .call_method(
                    recorder.as_ref(),
                    jni_str!("getState"),
                    jni_sig!("()I"),
                    &[],
                )?
                .i()?
                != 1
            {
                return Err(jni::errors::Error::NullPtr("uninitialized recorder"));
            }
            env.call_method(
                recorder.as_ref(),
                jni_str!("startRecording"),
                jni_sig!("()V"),
                &[],
            )?;
            if env
                .call_method(
                    recorder.as_ref(),
                    jni_str!("getRecordingState"),
                    jni_sig!("()I"),
                    &[],
                )?
                .i()?
                != 3
            {
                return Err(jni::errors::Error::NullPtr("recording not confirmed"));
            }
            Ok(())
        })?;
        release.started = true;
        control.started.store(true, Ordering::Release);
        let started = Instant::now();
        let mut pcm = Vec::new();
        let mut silence = 0u64;
        loop {
            if control.cancelled.load(Ordering::Acquire)
                || !super::super::android::activity_visible()
            {
                return Err("录音已取消或页面退到后台；麦克风正在释放".into());
            }
            if control.stop.load(Ordering::Acquire)
                || started.elapsed() >= Duration::from_secs(60)
                || pcm.len() >= MAX_AUDIO_BYTES
            {
                break;
            }
            let remaining = ((MAX_AUDIO_BYTES - pcm.len()) / 2).min(4096);
            let samples = super::super::android::invoke(|env| {
                let array = env.new_short_array(remaining)?;
                let read = env
                    .call_method(
                        recorder.as_ref(),
                        jni_str!("read"),
                        jni_sig!("([SIII)I"),
                        &[
                            JValue::Object(&array),
                            JValue::Int(0),
                            JValue::Int(remaining as i32),
                            JValue::Int(1),
                        ],
                    )?
                    .i()?;
                if read < 0 || read as usize > remaining {
                    return Err(jni::errors::Error::NullPtr("audio read failed"));
                }
                let mut data = vec![0i16; read as usize];
                env.get_short_array_region(&array, 0, &mut data)?;
                Ok(data)
            })?;
            control.elapsed.store(
                started.elapsed().as_millis().min(u64::MAX as u128) as u64,
                Ordering::Release,
            );
            if samples.is_empty() {
                std::thread::sleep(Duration::from_millis(5));
                continue;
            }
            let sum = samples
                .iter()
                .map(|sample| (*sample as f64).powi(2))
                .sum::<f64>();
            let rms = (sum / samples.len() as f64).sqrt();
            control.level.store(
                ((rms / 32768. * 5.).clamp(0., 1.) as f32).to_bits(),
                Ordering::Release,
            );
            silence = if rms < 32768. * 0.02 {
                silence.saturating_add(samples.len() as u64 * 1000 / 16_000)
            } else {
                0
            };
            for sample in samples {
                pcm.extend_from_slice(&sample.to_le_bytes());
            }
            if silence >= 1200 {
                break;
            }
        }
        let cleanup = release.finish();
        control.started.store(false, Ordering::Release);
        control.level.store(0, Ordering::Release);
        cleanup?;
        if control.cancelled.load(Ordering::Acquire) {
            return Err("录音已取消，音频未提交".into());
        }
        if pcm.is_empty() {
            return Err("没有录到音频".into());
        }
        let samples = pcm.len() as u64 / 2;
        let mut bytes = wav_header(pcm.len());
        bytes.extend_from_slice(&pcm);
        Ok(RecordedWav {
            bytes: Arc::new(bytes),
            samples,
            duration_ms: samples * 1000 / 16_000,
        })
    }
    fn wav_header(length: usize) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(44);
        bytes.extend_from_slice(b"RIFF");
        bytes.extend_from_slice(&(length as u32 + 36).to_le_bytes());
        bytes.extend_from_slice(b"WAVEfmt ");
        bytes.extend_from_slice(&16u32.to_le_bytes());
        bytes.extend_from_slice(&1u16.to_le_bytes());
        bytes.extend_from_slice(&1u16.to_le_bytes());
        bytes.extend_from_slice(&16_000u32.to_le_bytes());
        bytes.extend_from_slice(&32_000u32.to_le_bytes());
        bytes.extend_from_slice(&2u16.to_le_bytes());
        bytes.extend_from_slice(&16u16.to_le_bytes());
        bytes.extend_from_slice(b"data");
        bytes.extend_from_slice(&(length as u32).to_le_bytes());
        bytes
    }
}

#[derive(Clone)]
pub struct AsrConnection {
    pub endpoint: String,
    pub model: String,
    pub api_key: String,
    pub path: String,
}
impl std::fmt::Debug for AsrConnection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AsrConnection")
            .field("endpoint", &"[configured]")
            .field("model", &self.model)
            .field("api_key", &"[redacted]")
            .finish()
    }
}
pub fn prepare_asr(
    settings: &crate::state::Settings,
    path: &str,
    explicit_key_configured: bool,
) -> Result<AsrConnection, String> {
    let main = settings.endpoint.trim().trim_end_matches('/');
    let endpoint = if settings.asr_endpoint.trim().is_empty() {
        main
    } else {
        settings.asr_endpoint.trim().trim_end_matches('/')
    };
    let key = if !settings.asr_key.is_empty() {
        settings.asr_key.as_str()
    } else if explicit_key_configured {
        return Err("已保存的转写凭据不可用，请重新填写；未回退其它密钥".into());
    } else if endpoint == main {
        settings.api_key.as_str()
    } else {
        ""
    };
    let path = if path.trim().is_empty() {
        "/audio/transcriptions"
    } else {
        path.trim()
    };
    if endpoint.is_empty()
        || settings.asr_model.trim().is_empty()
        || key.trim().is_empty()
        || endpoint.len() > 16 * 1024
        || key.len() > 64 * 1024
        || path.len() > 4096
        || path.contains("://")
        || path.starts_with("//")
        || path.contains(['\r', '\n', '\0'])
    {
        return Err("转写接口、模型、密钥或接口路径未配置或无效".into());
    }
    let url = reqwest::Url::parse(endpoint).map_err(|_| "转写接口不是URL")?;
    if !["http", "https"].contains(&url.scheme())
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err("转写接口必须是无嵌入凭据的HTTP(S)基础地址".into());
    }
    if settings.asr_model.len() > 1024 || settings.asr_model.contains(['\r', '\n', '\0']) {
        return Err("转写模型字段无效".into());
    }
    let connection = AsrConnection {
        endpoint: endpoint.into(),
        model: settings.asr_model.trim().into(),
        api_key: key.into(),
        path: path.into(),
    };
    asr_url(&connection)?;
    Ok(connection)
}
fn asr_url(connection: &AsrConnection) -> Result<reqwest::Url, String> {
    if connection.endpoint.is_empty()
        || connection.endpoint.len() > 16 * 1024
        || connection.model.trim().is_empty()
        || connection.model.len() > 1024
        || connection.model.contains(['\r', '\n', '\0'])
        || connection.api_key.trim().is_empty()
        || connection.api_key.len() > 64 * 1024
        || connection.api_key.contains(['\r', '\n', '\0'])
        || connection.path.is_empty()
        || connection.path.len() > 4096
        || connection.path.contains("://")
        || connection.path.starts_with("//")
        || connection.path.contains(['\r', '\n', '\0', '?', '#'])
    {
        return Err("转写连接参数无效".into());
    }
    let base = reqwest::Url::parse(&connection.endpoint).map_err(|_| "转写基础地址无效")?;
    if !["http", "https"].contains(&base.scheme())
        || base.host().is_none()
        || !base.username().is_empty()
        || base.password().is_some()
        || base.query().is_some()
        || base.fragment().is_some()
    {
        return Err("转写接口必须是无嵌入凭据的HTTP(S)基础地址".into());
    }
    let request = reqwest::Url::parse(&format!(
        "{}/{}",
        connection.endpoint.trim_end_matches('/'),
        connection.path.trim_start_matches('/')
    ))
    .map_err(|_| "转写请求地址无效")?;
    if request.origin() != base.origin()
        || !request.username().is_empty()
        || request.password().is_some()
        || request.query().is_some()
        || request.fragment().is_some()
    {
        return Err("转写路径改变了接口身份".into());
    }
    Ok(request)
}
fn validate_wav(wav: &RecordedWav) -> Result<(), String> {
    let bytes = wav.bytes.as_slice();
    let invalid = || "转写音频必须是有界16kHz单声道PCM16 WAV，字段与音频长度一致".to_string();
    if bytes.len() < 46
        || bytes.len() > MAX_AUDIO_BYTES + 44
        || bytes.get(..4) != Some(b"RIFF".as_slice())
        || bytes.get(8..16) != Some(b"WAVEfmt ".as_slice())
        || bytes.get(36..40) != Some(b"data".as_slice())
    {
        return Err(invalid());
    }
    let u16_at = |offset| u16::from_le_bytes([bytes[offset], bytes[offset + 1]]);
    let u32_at = |offset| {
        u32::from_le_bytes([
            bytes[offset],
            bytes[offset + 1],
            bytes[offset + 2],
            bytes[offset + 3],
        ])
    };
    let pcm = bytes.len() - 44;
    if pcm % 2 != 0
        || u32_at(4) as usize != bytes.len() - 8
        || u32_at(16) != 16
        || u16_at(20) != 1
        || u16_at(22) != 1
        || u32_at(24) != 16_000
        || u32_at(28) != 32_000
        || u16_at(32) != 2
        || u16_at(34) != 16
        || u32_at(40) as usize != pcm
        || wav.samples != pcm as u64 / 2
        || wav.duration_ms != wav.samples * 1000 / 16_000
    {
        return Err(invalid());
    }
    Ok(())
}
pub fn transcribe(
    operation: OperationKey,
    connection: AsrConnection,
    wav: RecordedWav,
) -> Result<(), String> {
    validate_wav(&wav)?;
    asr_url(&connection)?;
    if super::permissions::network_permission(&connection.endpoint).is_err() {
        return Err("转写接口需要局域网访问权限".into());
    }
    if ASR_WORKERS
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |count| {
            (count < 2).then_some(count + 1)
        })
        .is_err()
    {
        return Err("转写工作线程已满，请等待或取消当前操作".into());
    }
    let cancelled = match admit(&operation) {
        Ok(value) => value,
        Err(error) => {
            ASR_WORKERS.fetch_sub(1, Ordering::AcqRel);
            return Err(error);
        }
    };
    let worker_operation = operation.clone();
    if let Err(error) = std::thread::Builder::new()
        .name("rust-asr-request".into())
        .spawn(move || {
            struct Worker;
            impl Drop for Worker {
                fn drop(&mut self) {
                    ASR_WORKERS.fetch_sub(1, Ordering::AcqRel);
                }
            }
            let _worker = Worker;
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                asr(&worker_operation, &connection, &wav, &cancelled)
            }))
            .unwrap_or_else(|_| Err("转写内部异常，未填入草稿".into()));
            emit(VoiceResult::TranscriptionFinished {
                operation: worker_operation,
                result,
            });
        })
    {
        ASR_WORKERS.fetch_sub(1, Ordering::AcqRel);
        forget(&operation.id);
        return Err(format!("转写线程无法启动：{error}"));
    }
    Ok(())
}
fn asr(
    operation: &OperationKey,
    connection: &AsrConnection,
    wav: &RecordedWav,
    cancelled: &AtomicBool,
) -> Result<String, String> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .max_blocking_threads(2)
        .build()
        .map_err(|_| "无法创建转写运行时")?;
    let _ = rustls::crypto::ring::default_provider().install_default();
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(std::time::Duration::from_secs(15))
        .timeout(std::time::Duration::from_secs(120))
        .build()
        .map_err(|_| "无法初始化转写网络客户端")?;
    let endpoint = format!(
        "{}/{}",
        connection.endpoint.trim_end_matches('/'),
        connection.path.trim_start_matches('/')
    );
    let mut boundary = format!(
        "jagent-{}-{}",
        operation.generation,
        operation
            .id
            .replace(|c: char| !c.is_ascii_alphanumeric(), "")
    );
    if boundary.len() > 160 {
        boundary.truncate(160);
    }
    while wav
        .bytes
        .windows(boundary.len())
        .any(|part| part == boundary.as_bytes())
    {
        boundary.push('x');
        if boundary.len() > 200 {
            return Err("转写multipart边界无法分配".into());
        }
    }
    let mut body=format!("--{boundary}\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n{}\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nzh\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"dictation.wav\"\r\nContent-Type: audio/wav\r\n\r\n",connection.model).into_bytes();
    body.extend_from_slice(&wav.bytes);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    let result=runtime.block_on(async{
        let request=async{
            let mut response=client.post(&endpoint).bearer_auth(&connection.api_key).header(reqwest::header::CONTENT_TYPE,format!("multipart/form-data; boundary={boundary}")).body(body).send().await.map_err(|_|"转写请求失败，请检查网络或系统权限".to_string())?;
            let status=response.status();let mut bytes=Vec::new();while let Some(chunk)=response.chunk().await.map_err(|_|"转写响应读取失败")?{if bytes.len().saturating_add(chunk.len())>1024*1024{return Err("转写响应超过1 MiB预算，未返回截断文本".to_string());}bytes.extend_from_slice(&chunk);}
            if !status.is_success(){return Err(format!("转写服务返回HTTP {}，响应正文未公开",status.as_u16()));}
            let value:serde_json::Value=serde_json::from_slice(&bytes).map_err(|_|"转写响应不是JSON")?;let text=value.get("text").and_then(|v|v.as_str()).ok_or("转写服务没有返回文本")?.trim();if text.is_empty()||text.len()>256*1024{return Err("转写文本为空或超过草稿预算".to_string());}Ok(text.to_string())
        };
        let cancellation=async{loop{if cancelled.load(Ordering::Acquire){break;}tokio::time::sleep(std::time::Duration::from_millis(10)).await;}};
        tokio::select!{biased;_=cancellation=>Err("转写已取消；已发出的请求可能仍由服务商处理".to_string()),result=request=>result}
    });
    // Blocking DNS/HAL workers cannot be represented as stopped by a result.
    // Keep the worker slot until its runtime actually exits.
    result
}

#[derive(Clone)]
pub struct SpeechPolicy {
    pub opted_in: bool,
    /// Issued by the owning service manager only after the framework confirms a
    /// while-in-use eligible foreground lease; revoke on service loss/timeout.
    pub background_guard: Option<Arc<AtomicBool>>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SpeakerKey {
    pub operation: OperationKey,
    /// Process-local task nonce, independent of framework engine generation.
    /// Exact keys remain valid while initializing/queued and cannot alias a
    /// later task using another framework engine instance.
    pub instance: u64,
}
#[derive(Clone, Debug)]
pub enum StopConfirmation {
    RetractedBeforePlayback {
        speaker: SpeakerKey,
    },
    FrameworkStopped {
        speaker: SpeakerKey,
        engine_generation: u64,
        audio_focus_released: bool,
    },
}
#[derive(Clone)]
struct ScopedSpeech {
    operation: OperationKey,
    external_cancel: Arc<AtomicBool>,
    mandatory_ready: Arc<AtomicBool>,
}
impl ScopedSpeech {
    fn permitted(&self, operation: &OperationKey) -> bool {
        &self.operation == operation
            && !self.external_cancel.load(Ordering::Acquire)
            && self.mandatory_ready.load(Ordering::Acquire)
    }
}
static NEXT_SPEAKER: AtomicU64 = AtomicU64::new(1);
fn speaker_key(operation: &OperationKey) -> Result<SpeakerKey, String> {
    let instance = NEXT_SPEAKER
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |value| {
            value.checked_add(1)
        })
        .map_err(|_| "语音任务身份已耗尽")?;
    Ok(SpeakerKey {
        operation: operation.clone(),
        instance,
    })
}

/// Holds the owning service's actual Ready guard and caller cancel Arc across
/// admission, initialization, audio focus and every actual speak/chunk boundary.
/// A visible Activity cannot override mandatory service loss for scoped calls.
pub fn speak_scoped(
    operation: OperationKey,
    text: String,
    policy: SpeechPolicy,
    external_cancel: Arc<AtomicBool>,
    ready: &super::speech_service::Lease,
) -> Result<SpeakerKey, String> {
    operation.validate()?;
    if ready.operation() != &operation || !ready.confirmed() {
        return Err("当前语音操作尚无真实SpeechService Ready确认".into());
    }
    let scope = ScopedSpeech {
        operation: operation.clone(),
        external_cancel,
        mandatory_ready: ready.guard(),
    };
    if !scope.permitted(&operation) || !policy.permitted() {
        return Err("朗读调用已取消或用户/服务保护不可用".into());
    }
    let key = speaker_key(&operation)?;
    #[cfg(not(target_os = "android"))]
    {
        let _ = (text, policy, scope, key);
        Err("当前平台没有语音朗读后端".into())
    }
    #[cfg(target_os = "android")]
    {
        submit_speech(operation, text, policy, key.clone(), Some(scope))?;
        Ok(key)
    }
}

/// Stops only this exact queued/active speaker. Accepted dispatch returns no
/// stop success; SpeechStopFinished carries the actual actor/framework result.
pub fn stop_request(
    operation: OperationKey,
    expected: SpeakerKey,
    external_cancel: Arc<AtomicBool>,
) -> Result<(), String> {
    operation.validate()?;
    expected.operation.validate()?;
    if expected.instance == 0
        || operation == expected.operation
        || operation.conversation_id != expected.operation.conversation_id
    {
        return Err("停止操作必须拥有独立身份和精确目标语音key".into());
    }
    if external_cancel.load(Ordering::Acquire) {
        return Err("停止所属父/子调用已取消".into());
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (expected, external_cancel);
        Err("当前平台没有语音停止后端".into())
    }
    #[cfg(target_os = "android")]
    {
        {
            let state = registry().lock().unwrap_or_else(|error| error.into_inner());
            if !state
                .operations
                .get(&expected.operation.id)
                .is_some_and(|entry| {
                    entry.operation == expected.operation
                        && entry.speaker.as_ref() == Some(&expected)
                        && !entry.finished
                })
            {
                return Err("目标语音任务身份已终结或变化".into());
            }
        }
        admit(&operation)?;
        if let Err(error) = speaker::submit(speaker::Command::StopExact {
            operation: operation.clone(),
            expected,
            external_cancel,
        }) {
            forget(&operation.id);
            return Err(error);
        }
        Ok(())
    }
}

/// Scoped cancellation never calls the global stop API or accepts an ID alone.
pub fn cancel_speaker(expected: &SpeakerKey) -> bool {
    let flag = registry()
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .operations
        .get(&expected.operation.id)
        .filter(|entry| {
            entry.operation == expected.operation
                && entry.speaker.as_ref() == Some(expected)
                && !entry.finished
        })
        .map(|entry| entry.cancelled.clone());
    if let Some(flag) = flag {
        flag.store(true, Ordering::Release);
        #[cfg(target_os = "android")]
        speaker::wake();
        true
    } else {
        false
    }
}
impl SpeechPolicy {
    // Existing UI policy remains compatible. Scoped model tasks additionally
    // require ScopedSpeech::permitted at every actual effect boundary.
    fn permitted(&self) -> bool {
        self.opted_in && {
            #[cfg(target_os = "android")]
            {
                super::android::activity_visible()
                    || self
                        .background_guard
                        .as_ref()
                        .is_some_and(|guard| guard.load(Ordering::Acquire))
            }
            #[cfg(not(target_os = "android"))]
            {
                false
            }
        }
    }
}
/// Exact operation cancellation also covers a pending StopExact command.
pub fn cancel_operation(operation: &OperationKey) -> bool {
    let flag = registry()
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .operations
        .get(&operation.id)
        .filter(|entry| &entry.operation == operation && !entry.finished)
        .map(|entry| entry.cancelled.clone());
    if let Some(flag) = flag {
        flag.store(true, Ordering::Release);
        #[cfg(target_os = "android")]
        speaker::wake();
        true
    } else {
        false
    }
}
pub fn prepare_speaker() -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        speaker::submit(speaker::Command::Prepare)
    }
    #[cfg(not(target_os = "android"))]
    {
        Err("当前平台没有语音朗读后端".into())
    }
}
pub fn speak(operation: OperationKey, text: String, policy: SpeechPolicy) -> Result<(), String> {
    #[cfg(not(target_os = "android"))]
    {
        let _ = (operation, text, policy);
        return Err("当前平台没有语音朗读后端".into());
    }
    #[cfg(target_os = "android")]
    {
        let key = speaker_key(&operation)?;
        submit_speech(operation, text, policy, key, None)
    }
}
#[cfg(target_os = "android")]
fn submit_speech(
    operation: OperationKey,
    text: String,
    policy: SpeechPolicy,
    key: SpeakerKey,
    scoped: Option<ScopedSpeech>,
) -> Result<(), String> {
    if !policy.permitted()
        || scoped
            .as_ref()
            .is_some_and(|scope| !scope.permitted(&operation))
    {
        return Err("朗读未获用户启用、Ready保护失效或所属调用已取消".into());
    }
    if text.len() > 256 * 1024 || text.contains('\0') {
        return Err("朗读正文超过预算或包含NUL".into());
    }
    let cancelled = admit(&operation)?;
    {
        let mut state = registry().lock().unwrap_or_else(|error| error.into_inner());
        state
            .operations
            .get_mut(&operation.id)
            .ok_or("语音任务登记丢失")?
            .speaker = Some(key.clone());
    }
    let task = speaker::Task {
        operation: operation.clone(),
        key,
        text,
        policy,
        scoped,
        cancelled,
        stop_epoch: speaker::stop_epoch(),
    };
    if let Err(error) = speaker::submit(speaker::Command::Speak(task)) {
        forget(&operation.id);
        return Err(error);
    }
    Ok(())
}
pub fn stop_speaking() {
    #[cfg(target_os = "android")]
    speaker::stop();
}
pub fn shutdown_speaker() {
    #[cfg(target_os = "android")]
    speaker::shutdown();
}
pub fn on_tts_init(generation: u64, status: i32) {
    #[cfg(target_os = "android")]
    speaker::init_event(generation, status);
    #[cfg(not(target_os = "android"))]
    let _ = (generation, status);
}
pub fn on_tts_progress(generation: u64, id: String, kind: i32, code: i32) {
    #[cfg(target_os = "android")]
    speaker::progress_event(generation, id, kind, code);
    #[cfg(not(target_os = "android"))]
    let _ = (generation, id, kind, code);
}
pub fn on_audio_focus(generation: u64, change: i32) {
    #[cfg(target_os = "android")]
    speaker::focus_event(generation, change);
    #[cfg(not(target_os = "android"))]
    let _ = (generation, change);
}

#[cfg(target_os = "android")]
mod speaker {
    use super::*;
    use crate::platform::android;
    use jni::{JValue, jni_sig, jni_str, objects::JObject, refs::Global};
    use std::{
        sync::mpsc::{self, Receiver, SyncSender, TrySendError},
        time::{Duration, Instant},
    };
    type Object = Arc<Global<JObject<'static>>>;
    pub(super) struct Task {
        pub operation: OperationKey,
        pub key: SpeakerKey,
        pub text: String,
        pub policy: SpeechPolicy,
        pub scoped: Option<ScopedSpeech>,
        pub cancelled: Arc<AtomicBool>,
        pub stop_epoch: u64,
    }
    impl Task {
        fn permitted(&self) -> bool {
            !self.cancelled.load(Ordering::Acquire)
                && self.policy.permitted()
                && self
                    .scoped
                    .as_ref()
                    .is_none_or(|scope| scope.permitted(&self.operation))
        }
        fn finish(self, result: Result<(), String>, cleanup_confirmed: bool) {
            if self.scoped.is_some() {
                emit(VoiceResult::ScopedSpeechFinished {
                    operation: self.operation,
                    speaker: self.key,
                    result,
                    cleanup_confirmed,
                });
            } else {
                emit(VoiceResult::SpeechFinished {
                    operation: self.operation,
                    result,
                });
            }
        }
    }
    pub(super) enum Command {
        Prepare,
        Speak(Task),
        StopExact {
            operation: OperationKey,
            expected: SpeakerKey,
            external_cancel: Arc<AtomicBool>,
        },
        Wake,
    }
    #[derive(Default)]
    struct Mailbox {
        engine: u64,
        created: Option<(u64, Result<Object, String>)>,
        utterance: Option<String>,
        init: Option<(u64, i32)>,
        progress: VecDeque<(u64, String, i32, i32)>,
        focus_generation: u64,
        focus: Option<(u64, i32)>,
    }
    static MAILBOX: OnceLock<Mutex<Mailbox>> = OnceLock::new();
    static SENDER: OnceLock<SyncSender<Command>> = OnceLock::new();
    static START: Mutex<()> = Mutex::new(());
    static NEXT: AtomicU64 = AtomicU64::new(1);
    static STOP: AtomicU64 = AtomicU64::new(0);
    static SHUTDOWN: AtomicBool = AtomicBool::new(false);
    static RELEASING: AtomicUsize = AtomicUsize::new(0);
    fn mailbox() -> &'static Mutex<Mailbox> {
        MAILBOX.get_or_init(|| Mutex::new(Mailbox::default()))
    }
    fn next() -> Result<u64, String> {
        NEXT.fetch_update(Ordering::AcqRel, Ordering::Acquire, |value| {
            value.checked_add(1).filter(|next| *next <= i64::MAX as u64)
        })
        .map_err(|_| "朗读代号已耗尽".into())
    }
    fn sender() -> Result<&'static SyncSender<Command>, String> {
        if let Some(sender) = SENDER.get() {
            return Ok(sender);
        }
        let _start = START.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(sender) = SENDER.get() {
            return Ok(sender);
        }
        let (tx, rx) = mpsc::sync_channel(64);
        std::thread::Builder::new()
            .name("rust-tts-actor".into())
            .spawn(move || run(rx))
            .map_err(|_| "无法启动朗读管理线程")?;
        SENDER.set(tx).map_err(|_| "朗读管理线程重复初始化")?;
        Ok(SENDER.get().unwrap())
    }
    pub(super) fn submit(command: Command) -> Result<(), String> {
        sender()?.try_send(command).map_err(|error| match error {
            TrySendError::Full(_) => "朗读命令队列已满".into(),
            TrySendError::Disconnected(_) => "朗读管理线程已结束".into(),
        })
    }
    pub(super) fn wake() {
        if let Some(sender) = SENDER.get() {
            let _ = sender.try_send(Command::Wake);
        }
    }
    pub(super) fn stop() {
        STOP.fetch_add(1, Ordering::AcqRel);
        wake();
    }
    pub(super) fn stop_epoch() -> u64 {
        STOP.load(Ordering::Acquire)
    }
    pub(super) fn shutdown() {
        SHUTDOWN.store(true, Ordering::Release);
        stop();
    }
    pub(super) fn init_event(generation: u64, status: i32) {
        let mut state = mailbox().lock().unwrap_or_else(|e| e.into_inner());
        if state.engine == generation {
            state.init = Some((generation, status));
        }
        drop(state);
        wake();
    }
    pub(super) fn progress_event(generation: u64, id: String, kind: i32, code: i32) {
        let mut state = mailbox().lock().unwrap_or_else(|e| e.into_inner());
        if state.engine == generation
            && state.utterance.as_deref() == Some(&id)
            && state.progress.len() < 8
        {
            state.progress.push_back((generation, id, kind, code));
        }
        drop(state);
        wake();
    }
    pub(super) fn focus_event(generation: u64, change: i32) {
        let mut state = mailbox().lock().unwrap_or_else(|e| e.into_inner());
        if state.focus_generation == generation {
            state.focus = Some((generation, change));
        }
        drop(state);
        wake();
    }
    struct Speaking {
        task: Task,
        chunks: VecDeque<String>,
        utterance: Option<String>,
        deadline: Instant,
    }
    struct Focus {
        manager: Object,
        request: Object,
        generation: u64,
    }
    struct Actor {
        generation: u64,
        engine: Option<Object>,
        creating: bool,
        initialization_deadline: Option<Instant>,
        init_status: Option<i32>,
        ready: bool,
        pending: VecDeque<Task>,
        active: Option<Speaking>,
        focus: Option<Focus>,
        stop: u64,
        idle: Instant,
    }
    fn run(rx: Receiver<Command>) {
        let mut actor = Actor {
            generation: 0,
            engine: None,
            creating: false,
            initialization_deadline: None,
            init_status: None,
            ready: false,
            pending: VecDeque::new(),
            active: None,
            focus: None,
            stop: STOP.load(Ordering::Acquire),
            idle: Instant::now(),
        };
        loop {
            let command = rx.recv_timeout(if actor.active.is_some() || actor.creating {
                Duration::from_millis(20)
            } else {
                Duration::from_millis(250)
            });
            actor.sync_stop();
            match command {
                Ok(Command::Prepare) => actor.ensure(),
                Ok(Command::Speak(task)) => {
                    if task.stop_epoch != STOP.load(Ordering::Acquire) || !task.permitted() {
                        task.finish(Err("朗读入队后已停止或Ready/调用范围已撤销".into()), true);
                    } else if actor.pending.len() >= 8 {
                        task.finish(Err("朗读待处理队列超过8条".into()), true);
                    } else {
                        actor.pending.push_back(task);
                        actor.ensure();
                    }
                }
                Ok(Command::StopExact {
                    operation,
                    expected,
                    external_cancel,
                }) => {
                    let live = registry()
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .operations
                        .get(&operation.id)
                        .is_some_and(|entry| {
                            entry.operation == operation
                                && !entry.finished
                                && !entry.cancelled.load(Ordering::Acquire)
                        });
                    let result = if live && !external_cancel.load(Ordering::Acquire) {
                        actor.stop_exact(&expected, &external_cancel)
                    } else {
                        Err("停止请求本身已取消或身份变化".into())
                    };
                    emit(VoiceResult::SpeechStopFinished {
                        operation,
                        expected,
                        result,
                    });
                }
                Ok(_) => {}
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    actor.fail("朗读管理线程已结束", true);
                    break;
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {}
            }
            actor.tick();
        }
    }
    impl Actor {
        fn stop_exact(
            &mut self,
            expected: &SpeakerKey,
            external_cancel: &AtomicBool,
        ) -> Result<StopConfirmation, String> {
            if external_cancel.load(Ordering::Acquire) {
                return Err("停止调用在执行边界已取消".into());
            }
            if self
                .active
                .as_ref()
                .is_some_and(|active| &active.task.key == expected)
            {
                return self.stop_active_scoped("已按精确语音任务请求停止", Some(external_cancel));
            }
            if let Some(index) = self.pending.iter().position(|task| &task.key == expected) {
                if external_cancel.load(Ordering::Acquire) {
                    return Err("停止调用在撤回队列边界已取消".into());
                }
                let task = self.pending.remove(index).ok_or("目标语音队列身份变化")?;
                let speaker = task.key.clone();
                task.finish(Err("语音在实际播放前已撤回".into()), true);
                self.status();
                return Ok(StopConfirmation::RetractedBeforePlayback { speaker });
            }
            Err("精确目标语音已终结或属于另一任务；未停止其它音频".into())
        }
        fn stop_active(&mut self, reason: &str) -> Result<StopConfirmation, String> {
            self.stop_active_scoped(reason, None)
        }
        fn stop_active_scoped(
            &mut self,
            reason: &str,
            external_cancel: Option<&AtomicBool>,
        ) -> Result<StopConfirmation, String> {
            if external_cancel.is_some_and(|cancel| cancel.load(Ordering::Acquire)) {
                return Err("精确停止所属调用已取消，未改变目标音频".into());
            }
            let expected = self
                .active
                .as_ref()
                .ok_or("没有当前所属语音")?
                .task
                .key
                .clone();
            let generation = self.generation;
            let attempted = AtomicBool::new(false);
            let stopped = self
                .engine
                .as_ref()
                .ok_or("目标系统语音引擎已丢失")
                .and_then(|engine| {
                    if external_cancel.is_some_and(|cancel| cancel.load(Ordering::Acquire)) {
                        return Err("精确停止调用在系统停止边界已取消");
                    }
                    android::invoke(|env| {
                        if external_cancel.is_some_and(|cancel| cancel.load(Ordering::Acquire)) {
                            return Err(jni::errors::Error::NullPtr(
                                "exact stop scope revoked before JNI",
                            ));
                        }
                        attempted.store(true, Ordering::Release);
                        if env
                            .call_method(engine.as_ref(), jni_str!("stop"), jni_sig!("()I"), &[])?
                            .i()?
                            != 0
                        {
                            return Err(jni::errors::Error::NullPtr(
                                "exact tts stop not confirmed",
                            ));
                        }
                        Ok(())
                    })
                    .map_err(|_| "目标系统语音停止未确认")
                });
            if !attempted.load(Ordering::Acquire)
                && external_cancel.is_some_and(|cancel| cancel.load(Ordering::Acquire))
            {
                return Err("精确停止调用在JNI执行前已取消，未改变目标音频".into());
            }
            let released = self.abandon_focus();
            let confirmed = stopped.is_ok() && released.is_ok() && generation != 0;
            let error = if confirmed {
                reason.to_owned()
            } else {
                format!("{reason}；目标系统停止或音频焦点释放未确认")
            };
            if let Some(active) = self.active.take() {
                active.task.finish(Err(error.clone()), confirmed);
            }
            {
                let mut state = mailbox().lock().unwrap_or_else(|error| error.into_inner());
                state.utterance = None;
                state.progress.clear();
            }
            self.idle = Instant::now();
            if stopped.is_err() {
                self.close();
            }
            self.status();
            if confirmed {
                Ok(StopConfirmation::FrameworkStopped {
                    speaker: expected,
                    engine_generation: generation,
                    audio_focus_released: true,
                })
            } else {
                Err(error)
            }
        }
        fn fail_current(&mut self, reason: &str) {
            if self
                .active
                .as_ref()
                .is_some_and(|active| active.task.scoped.is_some())
            {
                let _ = self.stop_active(reason);
            } else {
                self.fail(reason, false);
            }
        }
        fn sync_stop(&mut self) {
            let stop = STOP.load(Ordering::Acquire);
            if stop != self.stop {
                self.stop = stop;
                self.fail("朗读已停止", SHUTDOWN.swap(false, Ordering::AcqRel));
            }
        }
        fn ensure(&mut self) {
            if self.engine.is_some() || self.creating {
                return;
            }
            let generation = match next() {
                Ok(value) => value,
                Err(error) => {
                    self.fail(&error, false);
                    return;
                }
            };
            self.generation = generation;
            self.creating = true;
            self.initialization_deadline = Some(Instant::now() + Duration::from_secs(30));
            self.init_status = None;
            self.ready = false;
            {
                let mut state = mailbox().lock().unwrap_or_else(|e| e.into_inner());
                *state = Mailbox {
                    engine: generation,
                    ..Default::default()
                };
            }
            let launch = android::post_to_main(move || {
                let result = android::invoke(|env| {
                    let context = android::application_context()
                        .map_err(|_| jni::errors::Error::NullPtr("tts context"))?;
                    let class = android::listener_class(android::ListenerKind::TtsInit)
                        .map_err(|_| jni::errors::Error::NullPtr("tts listener class"))?;
                    let listener = env.new_object(
                        class.as_ref(),
                        jni_sig!("(J)V"),
                        &[JValue::Long(generation as i64)],
                    )?;
                    let engine=env.new_object(jni_str!("android/speech/tts/TextToSpeech"),jni_sig!("(Landroid/content/Context;Landroid/speech/tts/TextToSpeech$OnInitListener;)V"),&[JValue::Object(context.as_ref()),JValue::Object(&listener)])?;
                    Ok(Arc::new(env.new_global_ref(engine)?))
                });
                let mut state = mailbox().lock().unwrap_or_else(|e| e.into_inner());
                if state.engine == generation && state.created.is_none() {
                    state.created = Some((generation, result));
                    drop(state);
                    wake();
                } else {
                    drop(state);
                    if let Ok(engine) = result {
                        release(engine);
                    }
                }
            });
            if let Err(error) = launch {
                self.creating = false;
                self.fail(&error, true);
            }
            self.status();
        }
        fn configure_if_ready(&mut self) {
            if self.init_status != Some(0) || self.engine.is_none() {
                return;
            }
            let engine = self.engine.as_ref().unwrap().clone();
            let generation = self.generation;
            let result = android::invoke(|env| {
                let class = android::listener_class(android::ListenerKind::TtsProgress)
                    .map_err(|_| jni::errors::Error::NullPtr("tts progress class"))?;
                let listener = env.new_object(
                    class.as_ref(),
                    jni_sig!("(J)V"),
                    &[JValue::Long(generation as i64)],
                )?;
                if env
                    .call_method(
                        engine.as_ref(),
                        jni_str!("setOnUtteranceProgressListener"),
                        jni_sig!("(Landroid/speech/tts/UtteranceProgressListener;)I"),
                        &[JValue::Object(&listener)],
                    )?
                    .i()?
                    != 0
                {
                    return Err(jni::errors::Error::NullPtr("tts progress registration"));
                }
                let locale = env
                    .get_static_field(
                        jni_str!("java/util/Locale"),
                        jni_str!("SIMPLIFIED_CHINESE"),
                        jni_sig!("Ljava/util/Locale;"),
                    )?
                    .l()?;
                let language = env
                    .call_method(
                        engine.as_ref(),
                        jni_str!("setLanguage"),
                        jni_sig!("(Ljava/util/Locale;)I"),
                        &[JValue::Object(&locale)],
                    )?
                    .i()?;
                if language < 0 {
                    let default = env
                        .call_static_method(
                            jni_str!("java/util/Locale"),
                            jni_str!("getDefault"),
                            jni_sig!("()Ljava/util/Locale;"),
                            &[],
                        )?
                        .l()?;
                    if env
                        .call_method(
                            engine.as_ref(),
                            jni_str!("setLanguage"),
                            jni_sig!("(Ljava/util/Locale;)I"),
                            &[JValue::Object(&default)],
                        )?
                        .i()?
                        < 0
                    {
                        return Err(jni::errors::Error::NullPtr("tts language unavailable"));
                    }
                }
                Ok(())
            });
            match result {
                Ok(()) => {
                    self.ready = true;
                    self.initialization_deadline = None;
                    registry()
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .speaker
                        .last_error = None;
                }
                Err(error) => self.fail(&error, true),
            }
            self.status();
        }
        fn tick(&mut self) {
            self.sync_stop();
            let (created, init, progress, focus) = {
                let mut state = mailbox().lock().unwrap_or_else(|e| e.into_inner());
                (
                    state.created.take(),
                    state.init.take(),
                    state.progress.drain(..).collect::<Vec<_>>(),
                    state.focus.take(),
                )
            };
            if let Some((generation, result)) = created {
                if generation == self.generation {
                    self.creating = false;
                    match result {
                        Ok(engine) => {
                            self.engine = Some(engine);
                            self.configure_if_ready();
                        }
                        Err(error) => self.fail(&error, true),
                    }
                } else if let Ok(engine) = result {
                    release(engine);
                }
            }
            if let Some((generation, status)) = init {
                if generation == self.generation {
                    self.init_status = Some(status);
                    if status == 0 {
                        self.configure_if_ready();
                    } else {
                        self.fail("系统朗读引擎初始化失败", true);
                    }
                }
            }
            if self
                .initialization_deadline
                .is_some_and(|deadline| deadline <= Instant::now())
            {
                self.fail("系统朗读引擎初始化超时，未确认可播放", true);
            }
            if let Some((generation, change)) = focus {
                if self
                    .focus
                    .as_ref()
                    .is_some_and(|focus| focus.generation == generation)
                    && change <= 0
                {
                    self.fail_current("朗读失去音频焦点，正在请求停止");
                }
            }
            for (generation, id, kind, code) in progress {
                if generation != self.generation {
                    continue;
                }
                let matches = self
                    .active
                    .as_ref()
                    .is_some_and(|active| active.utterance.as_deref() == Some(&id));
                if !matches {
                    continue;
                }
                match kind {
                    1 => {
                        registry()
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .speaker
                            .speaking = true
                    }
                    2 => {
                        if let Some(active) = self.active.as_mut() {
                            active.utterance = None;
                        }
                        mailbox()
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .utterance = None;
                    }
                    3 => self.fail_current(&format!("朗读引擎返回错误{code}")),
                    4 => self.fail_current("朗读引擎已中断当前内容"),
                    _ => {}
                }
            }
            if self
                .active
                .as_ref()
                .is_some_and(|active| !active.task.permitted() || active.deadline < Instant::now())
            {
                self.fail_current("朗读已取消、失去强制Ready保护或超时");
            }
            if self.active.is_none() && self.ready {
                while let Some(task) = self.pending.pop_front() {
                    if !task.permitted() {
                        task.finish(Err("朗读操作已取消或强制Ready/用户保护不可用".into()), true);
                        continue;
                    }
                    let chunks = match clean_chunks(&task.text) {
                        Ok(chunks) => chunks,
                        Err(error) => {
                            task.finish(Err(error), true);
                            continue;
                        }
                    };
                    match acquire_focus() {
                        Ok(focus) => {
                            self.focus = Some(focus);
                            self.active = Some(Speaking {
                                task,
                                chunks,
                                utterance: None,
                                deadline: Instant::now() + Duration::from_secs(600),
                            });
                        }
                        Err(error) => task.finish(Err(error), true),
                    }
                    break;
                }
            }
            if self
                .active
                .as_ref()
                .is_some_and(|active| active.utterance.is_none())
            {
                let chunk = self
                    .active
                    .as_mut()
                    .and_then(|active| active.chunks.pop_front());
                match chunk {
                    Some(chunk) => self.enqueue(chunk),
                    None => {
                        let released = self.abandon_focus();
                        if let Some(active) = self.active.take() {
                            active.task.finish(released.clone(), released.is_ok());
                        }
                        if let Err(error) = released {
                            registry()
                                .lock()
                                .unwrap_or_else(|e| e.into_inner())
                                .speaker
                                .last_error = Some(error);
                        }
                        self.idle = Instant::now();
                    }
                }
            }
            if self.active.is_none()
                && self.pending.is_empty()
                && self.engine.is_some()
                && self.idle.elapsed() > Duration::from_secs(60)
            {
                self.close();
            }
            if !self.pending.is_empty() && self.engine.is_none() && !self.creating {
                self.ensure();
            }
            self.status();
        }
        fn enqueue(&mut self, text: String) {
            if self
                .active
                .as_ref()
                .is_none_or(|active| !active.task.permitted())
            {
                self.fail_current("朗读在实际speak前被取消或Ready保护撤销");
                return;
            }
            let Some(engine) = self.engine.clone() else {
                return;
            };
            let id = match next() {
                Ok(serial) => format!("jagent-tts-{}-{serial}", self.generation),
                Err(error) => {
                    self.fail(&error, true);
                    return;
                }
            };
            if let Some(active) = self.active.as_mut() {
                active.utterance = Some(id.clone());
                active.deadline = Instant::now() + Duration::from_secs(600);
            }
            mailbox()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .utterance = Some(id.clone());
            let result = android::invoke(|env| {
                let text = env.new_string(&text)?;
                let id = env.new_string(&id)?;
                let maximum = env
                    .call_static_method(
                        jni_str!("android/speech/tts/TextToSpeech"),
                        jni_str!("getMaxSpeechInputLength"),
                        jni_sig!("()I"),
                        &[],
                    )?
                    .i()?;
                let length = env
                    .call_method(&text, jni_str!("length"), jni_sig!("()I"), &[])?
                    .i()?;
                if length > maximum {
                    return Err(jni::errors::Error::NullPtr("tts input budget"));
                }
                // Recheck the exact stored scope immediately before the JNI
                // effect. Activity visibility cannot override this guard.
                if self
                    .active
                    .as_ref()
                    .is_none_or(|active| !active.task.permitted())
                {
                    return Err(jni::errors::Error::NullPtr("scoped tts authority revoked"));
                }
                if env
                    .call_method(
                        engine.as_ref(),
                        jni_str!("speak"),
                        jni_sig!(
                            "(Ljava/lang/CharSequence;ILandroid/os/Bundle;Ljava/lang/String;)I"
                        ),
                        &[
                            JValue::Object(&text),
                            JValue::Int(1),
                            JValue::Object(&JObject::null()),
                            JValue::Object(&id),
                        ],
                    )?
                    .i()?
                    != 0
                {
                    return Err(jni::errors::Error::NullPtr("tts request rejected"));
                }
                Ok(())
            });
            if let Err(error) = result {
                self.fail_current(&error);
            }
        }
        fn status(&self) {
            let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
            state.speaker.initialized = self.engine.is_some() || self.creating;
            state.speaker.ready = self.ready;
            state.speaker.pending = self.pending.len() + usize::from(self.active.is_some());
            state.speaker.releasing = RELEASING.load(Ordering::Acquire) > 0;
            if self.active.is_none() {
                state.speaker.speaking = false;
            }
        }
        fn fail(&mut self, message: &str, close: bool) {
            let stopped = self.engine.as_ref().map_or(Ok(()), |engine| {
                android::invoke(|env| {
                    if env
                        .call_method(engine.as_ref(), jni_str!("stop"), jni_sig!("()I"), &[])?
                        .i()?
                        != 0
                    {
                        return Err(jni::errors::Error::NullPtr("tts stop not confirmed"));
                    }
                    Ok(())
                })
            });
            let message = if stopped.is_err() {
                format!("{message}；系统停止未确认，正在关闭引擎")
            } else {
                message.to_owned()
            };
            let abandoned = self.abandon_focus();
            if let Some(active) = self.active.take() {
                active
                    .task
                    .finish(Err(message.clone()), stopped.is_ok() && abandoned.is_ok());
            }
            for task in self.pending.drain(..) {
                task.finish(Err(message.clone()), true);
            }
            {
                let mut state = mailbox().lock().unwrap_or_else(|e| e.into_inner());
                state.utterance = None;
                state.progress.clear();
            }
            registry()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .speaker
                .last_error = Some(if abandoned.is_err() {
                format!("{message}；音频焦点释放未确认")
            } else {
                message
            });
            self.idle = Instant::now();
            if close || stopped.is_err() {
                self.close();
            }
            self.status();
        }
        fn abandon_focus(&mut self) -> Result<(), String> {
            if let Some(focus) = self.focus.take() {
                mailbox()
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .focus_generation = 0;
                let abandoned = android::invoke(|env| {
                    if env
                        .call_method(
                            focus.manager.as_ref(),
                            jni_str!("abandonAudioFocusRequest"),
                            jni_sig!("(Landroid/media/AudioFocusRequest;)I"),
                            &[JValue::Object(focus.request.as_ref())],
                        )?
                        .i()?
                        != 1
                    {
                        return Err(jni::errors::Error::NullPtr("focus abandon not confirmed"));
                    }
                    Ok(())
                });
                return abandoned.map_err(|_| "音频焦点释放未确认".into());
            }
            Ok(())
        }
        fn close(&mut self) {
            if let Some(engine) = self.engine.take() {
                release(engine);
            }
            self.generation = 0;
            self.ready = false;
            self.creating = false;
            self.initialization_deadline = None;
            self.init_status = None;
            let created = {
                let mut state = mailbox().lock().unwrap_or_else(|e| e.into_inner());
                let created = state.created.take();
                *state = Mailbox::default();
                created
            };
            if let Some((_, Ok(engine))) = created {
                release(engine);
            }
        }
    }
    fn release(engine: Object) {
        RELEASING.fetch_add(1, Ordering::AcqRel);
        let result = android::post_to_main(move || {
            struct Lease;
            impl Drop for Lease {
                fn drop(&mut self) {
                    RELEASING.fetch_sub(1, Ordering::AcqRel);
                }
            }
            let _lease = Lease;
            let stopped = android::invoke(|env| {
                if env
                    .call_method(engine.as_ref(), jni_str!("stop"), jni_sig!("()I"), &[])?
                    .i()?
                    != 0
                {
                    return Err(jni::errors::Error::NullPtr("tts stop not confirmed"));
                }
                Ok(())
            });
            let shutdown = android::invoke(|env| {
                env.call_method(engine.as_ref(), jni_str!("shutdown"), jni_sig!("()V"), &[])?;
                Ok(())
            });
            if stopped.is_err() || shutdown.is_err() {
                registry()
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .speaker
                    .last_error = Some("系统朗读引擎停止或关闭未确认".into());
            }
        });
        if result.is_err() {
            RELEASING.fetch_sub(1, Ordering::AcqRel);
            registry()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .speaker
                .last_error = Some("朗读引擎释放未确认，主线程不可用".into());
        }
    }
    fn acquire_focus() -> Result<Focus, String> {
        let generation = next()?;
        mailbox()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .focus_generation = generation;
        let result = android::invoke(|env| {
            let context = android::application_context()
                .map_err(|_| jni::errors::Error::NullPtr("audio context"))?;
            let audio = env.new_string("audio")?;
            let manager = env
                .call_method(
                    context.as_ref(),
                    jni_str!("getSystemService"),
                    jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
                    &[JValue::Object(&audio)],
                )?
                .l()?;
            let attributes = env.new_object(
                jni_str!("android/media/AudioAttributes$Builder"),
                jni_sig!("()V"),
                &[],
            )?;
            env.call_method(
                &attributes,
                jni_str!("setContentType"),
                jni_sig!("(I)Landroid/media/AudioAttributes$Builder;"),
                &[JValue::Int(1)],
            )?;
            env.call_method(
                &attributes,
                jni_str!("setUsage"),
                jni_sig!("(I)Landroid/media/AudioAttributes$Builder;"),
                &[JValue::Int(1)],
            )?;
            let attributes = env
                .call_method(
                    attributes,
                    jni_str!("build"),
                    jni_sig!("()Landroid/media/AudioAttributes;"),
                    &[],
                )?
                .l()?;
            let class = android::listener_class(android::ListenerKind::AudioFocus)
                .map_err(|_| jni::errors::Error::NullPtr("audio focus listener"))?;
            let listener = env.new_object(
                class.as_ref(),
                jni_sig!("(J)V"),
                &[JValue::Long(generation as i64)],
            )?;
            let builder = env.new_object(
                jni_str!("android/media/AudioFocusRequest$Builder"),
                jni_sig!("(I)V"),
                &[JValue::Int(3)],
            )?;
            env.call_method(
                &builder,
                jni_str!("setAudioAttributes"),
                jni_sig!(
                    "(Landroid/media/AudioAttributes;)Landroid/media/AudioFocusRequest$Builder;"
                ),
                &[JValue::Object(&attributes)],
            )?;
            let handler = android::main_handler()
                .map_err(|_| jni::errors::Error::NullPtr("audio focus handler"))?;
            env.call_method(&builder,jni_str!("setOnAudioFocusChangeListener"),jni_sig!("(Landroid/media/AudioManager$OnAudioFocusChangeListener;Landroid/os/Handler;)Landroid/media/AudioFocusRequest$Builder;"),&[JValue::Object(&listener),JValue::Object(handler.as_ref())])?;
            let request = env
                .call_method(
                    builder,
                    jni_str!("build"),
                    jni_sig!("()Landroid/media/AudioFocusRequest;"),
                    &[],
                )?
                .l()?;
            let result = env
                .call_method(
                    &manager,
                    jni_str!("requestAudioFocus"),
                    jni_sig!("(Landroid/media/AudioFocusRequest;)I"),
                    &[JValue::Object(&request)],
                )?
                .i()?;
            if result != 1 {
                return Err(jni::errors::Error::NullPtr("audio focus not granted"));
            }
            Ok(Focus {
                manager: Arc::new(env.new_global_ref(manager)?),
                request: Arc::new(env.new_global_ref(request)?),
                generation,
            })
        });
        if result.is_err() {
            mailbox()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .focus_generation = 0;
        }
        result
    }
    fn clean_chunks(text: &str) -> Result<VecDeque<String>, String> {
        let mut text = text.to_owned();
        for (pattern, replacement) in [
            (r"```[\s\S]*?```", " 代码块省略 "),
            (r"`([^`]*)`", "$1"),
            (r"!\[[^\]]*\]\([^)]*\)", " "),
            (r"\[([^\]]*)\]\([^)]*\)", "$1"),
            (r"(?m)^\s{0,3}#{1,6}\s*", ""),
            (r"(?m)^\s*[-*+]\s+", ""),
            (r"https?://\S+", " 链接 "),
        ] {
            text = regex::Regex::new(pattern)
                .map_err(|_| "朗读文本规则无效")?
                .replace_all(&text, replacement)
                .into_owned();
        }
        let chars: Vec<_> = text.trim().chars().collect();
        if chars.is_empty() {
            return Err("没有可朗读文本".into());
        }
        if chars.len() > 64_000 {
            return Err("朗读正文超过64,000字符预算，请选择部分内容".into());
        }
        Ok(chars
            .chunks(1000)
            .map(|chunk| chunk.iter().collect())
            .collect())
    }
}
