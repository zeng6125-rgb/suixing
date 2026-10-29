package com.suixing.agent.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.suixing.agent.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * 随行自己的听写：录音 + 转写，全程不经过 `SpeechRecognizer`。
 *
 * 存在的理由只有一个：系统/ROM 的识别面板不受我们控制。用户两次报「丑、还是走系统的」，而
 * `isOnDeviceRecognitionAvailable` 与 `EXTRA_PREFER_OFFLINE` 都没能拦住 MIUI 的面板 —— 只要还
 * 用 `SpeechRecognizer`，面板就可能出现。这里改成本地抓音、把 wav 送到用户自己配置的服务商，
 * 屏幕上从头到尾只有我们的胶囊。
 */
class DictationRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 静音多久算说完了。短于这个数会把句子中间的停顿当成结尾。 */
        const val SILENCE_MS = 1_200L

        /** 总时长上限：一次听写不该长过一分钟，超过就当用户忘了关。 */
        const val MAX_MS = 60_000L

        /** 低于这个 RMS 视为静音（满量程 32768）。 */
        private const val SILENCE_RMS = 0.02 * Short.MAX_VALUE

        /** 说话时的 RMS 大约在 0.05–0.25 满量程，映射到 0..1 给音量条用。 */
        private const val LEVEL_SCALE = 5f
    }

    /** 0..1，UI 的音量条读它；由录音线程写、主线程读。 */
    @Volatile
    var level: Float = 0f
        private set

    /** 已经录了多久，用于胶囊上的计时。 */
    @Volatile
    var elapsedMs: Long = 0L
        private set

    /** 说完了（静音够久或到上限）：由录音线程回调，调用方负责在协程里收尾。 */
    @Volatile
    var onAutoStop: (() -> Unit)? = null

    // 这三个字段被录音线程与主线程交叉读写：不加 @Volatile 时停止/收尾可能读到旧值（issue #54 A-11）。
    @Volatile private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var pcm: java.io.ByteArrayOutputStream? = null

    /** 抓音开始。返回 false 表示这台设备/这个权限拿不到麦克风。 */
    @Synchronized
    fun start(): Boolean {
        if (record != null) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val bufferSize = minBuffer * 2
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (error: Throwable) {
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return false
        }
        val output = java.io.ByteArrayOutputStream()
        pcm = output
        level = 0f
        elapsedMs = 0L
        record = recorder
        thread = Thread({
            val chunk = ShortArray(bufferSize / 2)
            var silentFor = 0L
            val startedAt = System.currentTimeMillis()
            runCatching { recorder.startRecording() }
            while (record === recorder) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) {
                    // 0 is "no data yet" and a negative value is a dead recorder. Neither path
                    // updates the clock below, so without this the thread would spin at 100% and
                    // MAX_MS would never fire.
                    if (read < 0 || System.currentTimeMillis() - startedAt >= MAX_MS) {
                        onAutoStop?.invoke()
                        return@Thread
                    }
                    // read == 0 是"暂时没数据"：不休眠会让这个线程空转吃满 CPU。
                    Thread.sleep(5)
                    continue
                }
                var sum = 0.0
                for (index in 0 until read) {
                    val sample = chunk[index]
                    sum += sample.toDouble() * sample
                    output.write(sample.toInt() and 0xFF)
                    output.write((sample.toInt() shr 8) and 0xFF)
                }
                val rms = sqrt(sum / read)
                level = (rms / Short.MAX_VALUE * LEVEL_SCALE).toFloat().coerceIn(0f, 1f)
                val now = System.currentTimeMillis()
                elapsedMs = now - startedAt
                silentFor = if (rms < SILENCE_RMS) silentFor + (read * 1000L / SAMPLE_RATE) else 0L
                if (silentFor >= SILENCE_MS || elapsedMs >= MAX_MS) {
                    onAutoStop?.invoke()
                    return@Thread
                }
            }
        }, "dictation-recorder").apply { isDaemon = true; start() }
        return true
    }

    /**
     * 收尾并写出 wav，返回文件；没在录音时返回 null。
     *
     * 44 字节头按最小 WAVE 格式手拼，不引依赖：PCM16 单声道，字节率与块对齐都由采样率算出来。
     */
    @Synchronized
    fun stop(): File? {
        val recorder = record ?: return null
        record = null
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        thread?.join(1_000)
        thread = null
        val samples = pcm?.toByteArray() ?: ByteArray(0)
        pcm = null
        level = 0f
        if (samples.isEmpty()) return null
        val file = File(context.cacheDir, "dictation-${System.currentTimeMillis()}.wav")
        return runCatching {
            file.outputStream().use { out ->
                out.write(wavHeader(samples.size))
                out.write(samples)
            }
            file
        }.getOrNull()
    }

    /** 丢掉这次录音，不留文件。 */
    @Synchronized
    fun cancel() {
        val recorder = record
        record = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        thread = null
        pcm = null
        level = 0f
        elapsedMs = 0L
    }

    private fun wavHeader(dataBytes: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val header = ByteArray(44)
        fun ascii(offset: Int, text: String) {
            text.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun int(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun short(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF")
        int(4, 36 + dataBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        int(16, 16)
        short(20, 1)
        short(22, channels)
        int(24, SAMPLE_RATE)
        int(28, byteRate)
        short(32, channels * bitsPerSample / 8)
        short(34, bitsPerSample)
        ascii(36, "data")
        int(40, dataBytes)
        return header
    }
}

/**
 * 转写：把 wav 送到**用户已经配置好的那个服务商**，不另造一套账号。
 *
 * 接口按 OpenAI 兼容的 `POST {baseUrl}{asrPath}`（multipart：`file` + `model`）写，成功读回
 * `{"text": "…"}`。返回 [Result] 而不是抛异常：失败原因是给用户看的一行字，不是崩溃。
 */
object Dictation {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // 一分钟的音频在慢网络上要等一会儿：转写不是聊天，读超时给足。
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** 转写要用到的凭据是否齐了；UI 用它决定「能不能开始录音」。 */
    fun configured(settings: AppSettings): Boolean =
        settings.asrModel.isNotBlank() && settings.baseUrl.isNotBlank() && settings.apiKey.isNotBlank()

    suspend fun transcribe(settings: AppSettings, wav: File): Result<String> = withContext(Dispatchers.IO) {
        val path = settings.asrPath.ifBlank { "/audio/transcriptions" }
        val url = settings.baseUrl.trimEnd('/') + if (path.startsWith("/")) path else "/$path"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", settings.asrModel.trim())
            .addFormDataPart("language", "zh")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(body)
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("服务商返回 ${response.code}：${text.take(120)}")
                }
                json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.content
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: error("服务商没有返回文本")
            }
        }
    }
}
