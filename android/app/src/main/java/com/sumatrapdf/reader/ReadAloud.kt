package com.sumatrapdf.reader

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.concurrent.Executors

enum class ReadAloudState { Idle, Starting, Speaking, Paused }

class ReadAloud(context: Context) {
    private val tag = "SumatraReadAloud"
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()

    private var tts: TextToSpeech? = null
    private var engineReady = false

    private var sentences: List<String> = emptyList()
    private var nextSentence = 0
    private var speakingPage = -1
    private var lastPage = -1
    private var textForPage: ((Int) -> String)? = null

    var state by mutableStateOf(ReadAloudState.Idle)
        private set
    var page by mutableIntStateOf(-1)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    private var onPageAdvanced: ((Int) -> Unit)? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            engineReady = status == TextToSpeech.SUCCESS
            if (!engineReady) {
                lastError = "No text-to-speech engine is available on this device"
                state = ReadAloudState.Idle
                Log.w(tag, "TextToSpeech init failed: status=$status")
                return@TextToSpeech
            }
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    Log.i(tag, "done[$utteranceId] state=$state")
                    if (state != ReadAloudState.Speaking) return
                    worker.execute { speakNext() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.w(tag, "utterance error: $utteranceId")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(tag, "utterance error: $utteranceId code=$errorCode")
                }
            })
            if (state == ReadAloudState.Starting) {
                worker.execute { loadPageAndSpeak(speakingPage) }
            }
        }
    }

    fun voiceNames(): List<String> {
        val engine = tts ?: return emptyList()
        val all = try { engine.voices } catch (_: Throwable) { null } ?: return emptyList()
        val language = Locale.getDefault().language
        return all
            .filter { !it.isNetworkConnectionRequired }
            .sortedByDescending { it.locale.language == language }
            .map { it.name }
    }

    fun useVoice(name: String) {
        val engine = tts ?: return
        val match = try { engine.voices } catch (_: Throwable) { null }
            ?.firstOrNull { it.name == name } ?: return
        engine.voice = match
    }

    fun currentVoiceName(): String? = try { tts?.voice?.name } catch (_: Throwable) { null }

    fun start(fromPage: Int, lastPageIndex: Int, pageText: (Int) -> String, onPage: (Int) -> Unit) {
        textForPage = pageText
        onPageAdvanced = onPage
        lastPage = lastPageIndex
        speakingPage = fromPage
        page = fromPage
        lastError = null
        if (!engineReady) {
            state = ReadAloudState.Starting
            return
        }
        state = ReadAloudState.Speaking
        worker.execute { loadPageAndSpeak(fromPage) }
    }

    fun pause() {
        if (state != ReadAloudState.Speaking) return
        state = ReadAloudState.Paused
        try { tts?.stop() } catch (_: Throwable) {}
    }

    fun resume() {
        if (state != ReadAloudState.Paused) return
        state = ReadAloudState.Speaking
        worker.execute { speakNext() }
    }

    fun stop() {
        state = ReadAloudState.Idle
        sentences = emptyList()
        nextSentence = 0
        speakingPage = -1
        page = -1
        try { tts?.stop() } catch (_: Throwable) {}
    }

    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        worker.shutdownNow()
    }

    private fun loadPageAndSpeak(pageNo: Int) {
        val provider = textForPage ?: return
        val raw = try { provider(pageNo) } catch (t: Throwable) {
            Log.w(tag, "page text for $pageNo failed: ${t.message}", t)
            ""
        }
        sentences = splitIntoSentences(raw)
        nextSentence = 0
        speakingPage = pageNo
        page = pageNo
        if (sentences.isEmpty()) {
            advancePage()
            return
        }
        speakNext()
    }

    private fun speakNext() {
        if (state != ReadAloudState.Speaking) return
        if (nextSentence >= sentences.size) {
            advancePage()
            return
        }
        val engine = tts
        if (engine == null) {
            state = ReadAloudState.Idle
            return
        }
        val text = sentences[nextSentence]
        val id = "sumatra-$speakingPage-$nextSentence"
        nextSentence++
        val queueMode = if (nextSentence == 1) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = engine.speak(text, queueMode, Bundle(), id)
        Log.i(tag, "speak[$id] result=$result len=${text.length}: ${text.take(40)}")
        if (result != TextToSpeech.SUCCESS) {
            Log.w(tag, "speak() returned $result, stopping")
            state = ReadAloudState.Idle
            lastError = "The text-to-speech engine refused to speak"
        }
    }

    private fun advancePage() {
        val next = speakingPage + 1
        if (next > lastPage) {
            state = ReadAloudState.Idle
            page = -1
            return
        }
        onPageAdvanced?.invoke(next)
        loadPageAndSpeak(next)
    }
}

private val sentenceEnd = Regex("(?<=[.!?…])\\s+")

fun splitIntoSentences(text: String, maxChunk: Int = 380): List<String> {
    val normalised = text.replace(' ', ' ').replace(Regex("[ \\t]*\\n[ \\t]*"), " ")
    val out = mutableListOf<String>()
    for (candidate in normalised.split(sentenceEnd)) {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.length <= maxChunk) {
            out += trimmed
            continue
        }
        var start = 0
        while (start < trimmed.length) {
            var end = minOf(start + maxChunk, trimmed.length)
            if (end < trimmed.length) {
                val space = trimmed.lastIndexOf(' ', end)
                if (space > start) end = space
            }
            val piece = trimmed.substring(start, end).trim()
            if (piece.isNotEmpty()) out += piece
            start = end
        }
    }
    return out
}
