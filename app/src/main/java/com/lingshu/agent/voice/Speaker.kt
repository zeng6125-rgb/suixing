package com.lingshu.agent.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Text to speech for the agent. The engine initialises asynchronously, so lines
 * handed over before it is ready are held back and spoken in order afterwards
 * instead of being dropped.
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private val ready = AtomicBoolean(false)
    private val sequence = AtomicInteger(0)
    private val pending = ArrayDeque<String>()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(appContext) { status ->
            val tts = engine
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _speaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _speaking.value = false
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        _speaking.value = false
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _speaking.value = false
                    }
                })
                ready.set(true)
                drain()
            }
        }
    }

    /** Languages the current engine actually has data for, for the settings screen. */
    fun languageLabel(): String {
        val tts = engine ?: return "引擎未就绪"
        val chinese = tts.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE)
        return when {
            chinese == TextToSpeech.LANG_MISSING_DATA -> "缺少中文语音数据"
            chinese == TextToSpeech.LANG_NOT_SUPPORTED -> "引擎不支持中文"
            else -> "就绪"
        }
    }

    fun speak(text: String) {
        val clean = cleanForSpeech(text)
        if (clean.isEmpty()) return
        val tts = engine
        if (tts == null || !ready.get()) {
            synchronized(pending) {
                while (pending.size >= MAX_PENDING) pending.removeFirst()
                pending.addLast(clean)
            }
            return
        }
        val language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
        }
        tts.speak(clean, TextToSpeech.QUEUE_ADD, null, "lingshu-${sequence.incrementAndGet()}")
    }

    fun stop() {
        synchronized(pending) { pending.clear() }
        runCatching { engine?.stop() }
        _speaking.value = false
    }

    fun shutdown() {
        synchronized(pending) { pending.clear() }
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
        ready.set(false)
        _speaking.value = false
    }

    private fun drain() {
        val queued = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        queued.forEach { speak(it) }
    }

    /**
     * Markdown reads terribly out loud: strip the syntax but keep the words.
     */
    private fun cleanForSpeech(text: String): String {
        var clean = text
        clean = clean.replace(Regex("```[\\s\\S]*?```"), " 代码块省略 ")
        clean = clean.replace(Regex("`([^`]*)`"), "$1")
        clean = clean.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), " ")
        clean = clean.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        clean = clean.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        clean = clean.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        clean = clean.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        clean = clean.replace(Regex("\\*\\*([^*]*)\\*\\*"), "$1")
        clean = clean.replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"), "$1")
        clean = clean.replace(Regex("~~([^~]*)~~"), "$1")
        clean = clean.replace(Regex("(?m)^\\s*\\|.*\\|\\s*$"), " 表格省略 ")
        clean = clean.replace(Regex("(?m)^\\s*>\\s?"), "")
        clean = clean.replace(Regex("https?://\\S+"), " 链接 ")
        clean = clean.replace(Regex("[ \\t]+"), " ")
        clean = clean.replace(Regex("\\n{2,}"), "\n")
        return clean.trim().take(MAX_SPEECH_CHARS)
    }

    private companion object {
        const val MAX_PENDING = 8
        const val MAX_SPEECH_CHARS = 4000
    }
}
