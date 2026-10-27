package com.suixing.agent.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Text to speech for the agent. The engine initialises asynchronously, so lines
 * handed over before it is ready are held back and spoken in order afterwards
 * instead of being dropped.
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val sequence = AtomicInteger(0)
    private val pending = ArrayDeque<String>()
    private val inFlight = HashSet<String>()
    private var generation = 0
    private var ready = false
    private var engine: TextToSpeech? = null

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private val _engineLabel = MutableStateFlow("引擎未就绪")
    val engineLabel: StateFlow<String> = _engineLabel

    private val idleRelease = Runnable {
        synchronized(lock) {
            if (ready && pending.isEmpty() && inFlight.isEmpty()) releaseLocked()
        }
    }

    /** Opening voice settings is an explicit probe; ordinary app startup never binds TTS. */
    fun prepare() = synchronized(lock) {
        ensureEngineLocked()
        scheduleIdleLocked()
    }

    private fun ensureEngineLocked() {
        if (engine != null) return
        val ownGeneration = ++generation
        engine = TextToSpeech(appContext) { result ->
            // The constructor can report on another thread. Queue completion after assignment,
            // and reject a callback from an engine that has already been stopped/replaced.
            handler.post {
                synchronized(lock) {
                    if (ownGeneration != generation) return@synchronized
                    val tts = engine ?: return@synchronized
                    if (result != TextToSpeech.SUCCESS) {
                        releaseLocked()
                        return@synchronized
                    }
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            handler.post {
                                synchronized(lock) {
                                    if (ownGeneration == generation && utteranceId in inFlight) _speaking.value = true
                                }
                            }
                        }
                        override fun onDone(utteranceId: String?) = completed(utteranceId, ownGeneration)
                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onError(utteranceId: String?) = completed(utteranceId, ownGeneration)
                        override fun onError(utteranceId: String?, errorCode: Int) = completed(utteranceId, ownGeneration)
                    })
                    ready = true
                    _engineLabel.value = when (tts.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE)) {
                        TextToSpeech.LANG_MISSING_DATA -> "缺少中文语音数据"
                        TextToSpeech.LANG_NOT_SUPPORTED -> "引擎不支持中文"
                        else -> "就绪"
                    }
                    while (pending.isNotEmpty()) enqueueLocked(pending.removeFirst())
                    scheduleIdleLocked()
                }
            }
        }
    }

    fun languageLabel(): String = _engineLabel.value

    fun speak(text: String) {
        val clean = cleanForSpeech(text)
        if (clean.isEmpty()) return
        synchronized(lock) {
            handler.removeCallbacks(idleRelease)
            if (engine == null || !ready) {
                while (pending.size >= MAX_PENDING) pending.removeFirst()
                pending.addLast(clean)
                ensureEngineLocked()
            } else {
                enqueueLocked(clean)
                scheduleIdleLocked()
            }
        }
    }

    private fun enqueueLocked(clean: String) {
        val tts = engine ?: return
        val language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
        }
        val id = "Suixing-${sequence.incrementAndGet()}"
        if (tts.speak(clean, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS) inFlight.add(id)
    }

    private fun completed(id: String?, ownGeneration: Int) {
        handler.post {
            synchronized(lock) {
                if (ownGeneration != generation || !inFlight.remove(id)) return@synchronized
                _speaking.value = inFlight.isNotEmpty()
                scheduleIdleLocked()
            }
        }
    }

    private fun scheduleIdleLocked() {
        handler.removeCallbacks(idleRelease)
        if (ready && pending.isEmpty() && inFlight.isEmpty()) handler.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    fun stop() = synchronized(lock) {
        pending.clear()
        inFlight.clear()
        runCatching { engine?.stop() }
        _speaking.value = false
        scheduleIdleLocked()
    }

    fun shutdown() = synchronized(lock) {
        pending.clear()
        releaseLocked()
    }

    private fun releaseLocked() {
        handler.removeCallbacks(idleRelease)
        generation++
        inFlight.clear()
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
        _speaking.value = false
        _engineLabel.value = "引擎未就绪"
    }

    /**
     * Markdown reads terribly out loud: strip the syntax but keep the words.
     */
    private fun cleanForSpeech(text: String): String {
        var clean = text
        for ((pattern, replacement) in SPEECH_RULES) clean = clean.replace(pattern, replacement)
        return clean.trim().take(MAX_SPEECH_CHARS)
    }

    private companion object {
        val SPEECH_RULES = listOf(
            Regex("```[\\s\\S]*?```") to " 代码块省略 ",
            Regex("`([^`]*)`") to "$1",
            Regex("!\\[[^\\]]*\\]\\([^)]*\\)") to " ",
            Regex("\\[([^\\]]*)\\]\\([^)]*\\)") to "$1",
            Regex("(?m)^\\s{0,3}#{1,6}\\s*") to "",
            Regex("(?m)^\\s*[-*+]\\s+") to "",
            Regex("(?m)^\\s*\\d+\\.\\s+") to "",
            Regex("\\*\\*([^*]*)\\*\\*") to "$1",
            Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)") to "$1",
            Regex("~~([^~]*)~~") to "$1",
            Regex("(?m)^\\s*\\|.*\\|\\s*$") to " 表格省略 ",
            Regex("(?m)^\\s*>\\s?") to "",
            Regex("https?://\\S+") to " 链接 ",
            Regex("[ \\t]+") to " ",
            Regex("\\n{2,}") to "\n",
        )
        const val IDLE_RELEASE_MS = 60_000L
        const val MAX_PENDING = 8
        const val MAX_SPEECH_CHARS = 4000
    }
}
