package com.radvoice.keyboard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Continuous dictation on top of the system SpeechRecognizer.
 *
 * Strategy for "no skipped words":
 *  - Android 13+: segmented session – the recogniser keeps the mic open and returns each segment
 *    (onSegmentResults) without the stop/start gap of classic mode.
 *  - Older / unsupported: classic mode with immediate automatic restart after every result.
 *  - If a session dies with an error, the last partial hypothesis is committed rather than lost.
 *  - If advanced extras are rejected, the engine degrades step by step (segmented -> classic -> basic,
 *    on-device -> cloud recogniser) instead of stopping.
 * All calls must be on the main thread.
 */
class DictationEngine(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onStateChanged(listening: Boolean, status: String)
        fun onLevel(rmsDb: Float) {}
    }

    var language = "en-IN"
    var preferOnDevice = true
    var biasing: List<String> = emptyList()

    val isListening get() = active

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false
    private var active = false
    private var pendingPartial = ""
    private var gotResultThisSession = false
    private var consecutiveErrors = 0

    /** 2 = segmented + all extras, 1 = classic + extras, 0 = classic basic. */
    private var level = if (Build.VERSION.SDK_INT >= 33) 2 else 1
    private var onDeviceFailed = false

    fun start() {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx) && !onDeviceAvailable()) {
            cb.onStateChanged(false, "No speech recognition service on this phone (install/enable Google app)")
            return
        }
        active = true
        consecutiveErrors = 0
        begin()
    }

    fun stop() {
        if (!active) return
        active = false
        main.removeCallbacksAndMessages(null)
        flushPartial()
        recognizer?.let { runCatching { it.stopListening() }; runCatching { it.cancel() } }
        cb.onStateChanged(false, "Stopped")
    }

    fun destroy() {
        active = false
        main.removeCallbacksAndMessages(null)
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    /** Settings screen: ask the on-device service to download the offline model for [language]. */
    fun downloadOfflineModel(): String {
        if (Build.VERSION.SDK_INT < 33) return "Offline model download needs Android 13+. Use Google app > Settings > Voice > Offline speech recognition."
        if (!onDeviceAvailable()) return "On-device recognition is not available on this phone."
        return runCatching {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            r.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language))
            main.postDelayed({ runCatching { r.destroy() } }, 5000)
            "Download requested for $language. Watch the notification shade."
        }.getOrElse { "Download request failed: ${it.message}" }
    }

    // --------------------------------------------------------------------------------------------

    private fun onDeviceAvailable() =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

    private fun ensureRecognizer() {
        val wantOnDevice = preferOnDevice && !onDeviceFailed && onDeviceAvailable()
        if (recognizer != null && wantOnDevice == usingOnDevice) return
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = if (wantOnDevice && Build.VERSION.SDK_INT >= 31)
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        usingOnDevice = wantOnDevice
        recognizer!!.setRecognitionListener(listener)
    }

    private fun begin() {
        if (!active) return
        ensureRecognizer()
        pendingPartial = ""
        gotResultThisSession = false
        runCatching { recognizer!!.startListening(buildIntent()) }
            .onFailure { handleError(SpeechRecognizer.ERROR_CLIENT) }
    }

    private fun buildIntent(): Intent {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, usingOnDevice)
        if (level >= 1) {
            // Long pauses while looking at images must not end the utterance.
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10_000L)
        }
        if (Build.VERSION.SDK_INT >= 33 && level >= 1) {
            i.putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            if (biasing.isNotEmpty()) i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasing))
        }
        if (Build.VERSION.SDK_INT >= 33 && level >= 2) {
            i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000L)
        }
        return i
    }

    private fun first(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private fun emitFinal(text: String) {
        pendingPartial = ""
        if (text.isNotBlank()) {
            gotResultThisSession = true
            consecutiveErrors = 0
            cb.onFinal(text)
        }
    }

    private fun flushPartial() {
        val p = pendingPartial
        if (p.isNotBlank()) emitFinal(p)
        pendingPartial = ""
    }

    private fun restart(delayMs: Long) {
        if (!active) return
        main.postDelayed({ begin() }, delayMs)
    }

    private fun handleError(code: Int) {
        flushPartial() // never lose what was already heard
        if (!active) return
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                consecutiveErrors = 0
                restart(60)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                active = false
                cb.onStateChanged(false, "Microphone permission missing – open settings")
            }
            SpeechRecognizer.ERROR_AUDIO -> {
                active = false
                cb.onStateChanged(false, "Microphone busy or unavailable")
            }
            else -> {
                consecutiveErrors++
                val languageProblem = Build.VERSION.SDK_INT >= 31 &&
                    (code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                when {
                    languageProblem && usingOnDevice -> {
                        onDeviceFailed = true
                        cb.onStateChanged(true, "Offline model missing for $language – using Google recogniser")
                    }
                    !gotResultThisSession && level > 0 && consecutiveErrors >= 2 -> {
                        level--
                        consecutiveErrors = 0
                        cb.onStateChanged(true, "Recogniser rejected advanced mode – compatibility level $level")
                    }
                    usingOnDevice && consecutiveErrors >= 3 -> {
                        onDeviceFailed = true
                        consecutiveErrors = 0
                        cb.onStateChanged(true, "On-device recogniser failing – switched to Google recogniser")
                    }
                    consecutiveErrors >= 8 -> {
                        active = false
                        cb.onStateChanged(false, "Recogniser error $code – tap mic to retry")
                        return
                    }
                }
                // Busy/client errors: rebuild the recogniser, back off a little.
                recognizer?.let { runCatching { it.destroy() } }
                recognizer = null
                restart((150L * consecutiveErrors).coerceAtMost(1500L))
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            cb.onStateChanged(true, if (usingOnDevice) "Listening (on-device)" else "Listening")
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) = cb.onLevel(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) = handleError(error)

        override fun onPartialResults(partialResults: Bundle?) {
            val t = first(partialResults)
            if (t.isNotEmpty()) { pendingPartial = t; cb.onPartial(t) }
        }

        override fun onResults(results: Bundle?) {
            val t = first(results).ifEmpty { pendingPartial }
            emitFinal(t)
            restart(40)
        }

        // Android 13+ segmented session
        override fun onSegmentResults(segmentResults: Bundle) {
            emitFinal(first(segmentResults).ifEmpty { pendingPartial })
        }

        override fun onEndOfSegmentedSession() {
            flushPartial()
            restart(40)
        }
    }
}
