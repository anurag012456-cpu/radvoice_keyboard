package com.radvoice.keyboard

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * RadVoice keyboard: a voice-first input method. Switch to it from Gboard with the globe/keyboard
 * key, dictate, switch back for typing.
 */
class RadVoiceIME : InputMethodService(), DictationEngine.Callback,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var engine: DictationEngine
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()   // processing + rebuilds, strictly ordered
    @Volatile private var processor: TextProcessor? = null
    private val partialSeq = AtomicInteger()

    private var statusView: TextView? = null
    private var previewView: TextView? = null
    private var micView: TextView? = null

    private var composing = false
    private var composeBase: String = ""
    private val undoStack = ArrayDeque<String>()   // text we committed, most recent last
    private var lastProcessed = ""

    // ------------------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        engine = DictationEngine(this, this)
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        rebuild()
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        engine.destroy()
        worker.shutdown()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == "lastRaw") return
        val wasListening = engine.isListening
        rebuild()
        if (wasListening && (key == "language" || key == "preferOnDevice")) {
            engine.stop(); main.postDelayed({ startDictation() }, 300)
        }
    }

    private fun rebuild() {
        engine.language = prefs.language
        engine.preferOnDevice = prefs.preferOnDevice
        worker.execute {
            val built = runCatching { LexiconStore.build(this, prefs) }.getOrNull()
            if (built != null) {
                processor = built.processor
                main.post { engine.biasing = built.biasing }
            }
        }
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing = false
        if (!restarting) undoStack.clear()
        setStatus(if (hasMic()) "Tap the mic and dictate" else "Microphone permission needed – tap ⚙")
        if (prefs.autoStart && hasMic() && !engine.isListening) startDictation()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        engine.stop()          // never keep the microphone open for a hidden keyboard
        endComposition()
        super.onFinishInputView(finishingInput)
    }

    // ------------------------------------------------------------------------------ UI

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun keyBg(color: Int, radius: Int = 8): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun key(label: String, weight: Float, color: Int = KEY, textSp: Float = 17f, onTap: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            background = keyBg(color)
            isClickable = true
            setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onTap() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
        }

    private fun row(heightDp: Int, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
        views.forEach { addView(it) }
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }

        statusView = TextView(this).apply {
            setTextColor(0xFF9AA4B2.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(2), dp(8), 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        previewView = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(8), dp(2), dp(8), dp(4)); maxLines = 2; minLines = 2
            ellipsize = TextUtils.TruncateAt.START
        }
        root.addView(statusView); root.addView(previewView)

        root.addView(row(44,
            key(",", 1f) { insertPunct(",") },
            key(".", 1f) { insertPunct(".") },
            key(":", 1f) { insertPunct(":") },
            key("(", 1f) { insertLiteral(" (") },
            key(")", 1f) { insertPunct(")") },
            key("×", 1f) { insertLiteral(prefs.dimSep) },
            key("mm", 1.2f, textSp = 15f) { insertLiteral(" mm") },
            key("cm", 1.2f, textSp = 15f) { insertLiteral(" cm") },
            key("¶", 1f) { insertPunct("\n\n") },
        ))

        val switchKey = key("⌨", 1.2f, KEY_DARK) { switchBack() }.apply {
            setOnLongClickListener { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker(); true }
        }
        val mic = key("🎤", 2.6f, MIC_IDLE, textSp = 26f) { toggleMic() }
        micView = mic
        val back = key("⌫", 1.3f, KEY_DARK) { backspace() }
        attachRepeat(back) { backspace() }
        root.addView(row(64,
            switchKey,
            key("↶", 1.2f, KEY_DARK) { undo() },
            mic,
            back,
            key("⏎", 1.3f, KEY_DARK) { enter() },
        ))

        root.addView(row(46,
            key("Teach", 1.4f, KEY_DARK, textSp = 14f) { teach() },
            key("space", 4f, KEY, textSp = 14f) { insertLiteral(" ", join = false) },
            key("⚙", 1.2f, KEY_DARK) { openSettings(null) },
        ))
        refreshMic(engine.isListening)
        return root
    }

    private fun attachRepeat(v: View, action: () -> Unit) {
        val repeat = object : Runnable {
            override fun run() { action(); main.postDelayed(this, 60) }
        }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.isPressed = true; action(); main.postDelayed(repeat, 450) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.isPressed = false; main.removeCallbacks(repeat) }
            }
            true
        }
    }

    private fun setStatus(s: String) { statusView?.text = s }

    private fun refreshMic(listening: Boolean) {
        micView?.background = keyBg(if (listening) MIC_ON else MIC_IDLE, 14)
        micView?.text = if (listening) "■" else "🎤"
    }

    // ------------------------------------------------------------------------------ dictation

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toggleMic() = if (engine.isListening) engine.stop() else startDictation()

    private fun startDictation() {
        if (!hasMic()) { openSettings(null); return }
        engine.language = prefs.language
        engine.preferOnDevice = prefs.preferOnDevice
        engine.start()
    }

    override fun onStateChanged(listening: Boolean, status: String) {
        setStatus(status); refreshMic(listening)
        if (!listening) endComposition()
    }

    override fun onLevel(rmsDb: Float) {
        if (!engine.isListening) return
        val a = (0.55f + (rmsDb.coerceIn(0f, 10f) / 10f) * 0.45f)
        micView?.alpha = a
    }

    override fun onPartial(text: String) {
        val seq = partialSeq.incrementAndGet()
        worker.execute {
            if (seq != partialSeq.get()) return@execute           // a newer partial is queued – skip
            val p = processor?.process(text) ?: text
            main.post {
                if (seq != partialSeq.get()) return@post
                previewView?.text = p
                if (!prefs.liveComposing) return@post
                val ic = currentInputConnection ?: return@post
                if (!composing) {
                    composeBase = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
                    composing = true
                }
                val ins = processor?.joinWithContext(composeBase, p) ?: TextProcessor.Insertion(0, p)
                ic.setComposingText(ins.text, 1)
            }
        }
    }

    override fun onFinal(text: String) {
        partialSeq.incrementAndGet()                                  // invalidate pending partials
        worker.execute {
            val p = processor?.process(text) ?: text
            main.post { commitProcessed(text, p) }
        }
    }

    private fun commitProcessed(raw: String, processed: String) {
        val ic = currentInputConnection ?: return
        if (processed.isEmpty()) { endComposition(); return }
        val before = if (composing) composeBase else ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
        val ins = processor?.joinWithContext(before, processed) ?: TextProcessor.Insertion(0, " $processed")
        ic.beginBatchEdit()
        if (composing) { ic.setComposingText("", 1); ic.finishComposingText() }
        if (ins.deleteBefore > 0) ic.deleteSurroundingText(ins.deleteBefore, 0)
        ic.commitText(ins.text, 1)
        ic.endBatchEdit()
        composing = false
        undoStack.addLast(ins.text); while (undoStack.size > 30) undoStack.removeFirst()
        prefs.lastRaw = raw
        lastProcessed = processed
        previewView?.text = processed
    }

    private fun endComposition() {
        if (composing) currentInputConnection?.finishComposingText()
        composing = false
    }

    // ------------------------------------------------------------------------------ keys

    private fun insertPunct(p: String) {
        endComposition()
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        if (before == " " && p != "\n\n") ic.deleteSurroundingText(1, 0)
        ic.commitText(p, 1)
        undoStack.addLast(p)
    }

    private fun insertLiteral(s: String, join: Boolean = true) {
        endComposition()
        val ic = currentInputConnection ?: return
        var t = s
        if (join && t.startsWith(" ") && (ic.getTextBeforeCursor(1, 0)?.toString() ?: "").let { it.isEmpty() || it == " " || it == "\n" }) t = t.trimStart()
        ic.commitText(t, 1)
        undoStack.addLast(t)
    }

    private fun backspace() {
        endComposition()
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun undo() {
        endComposition()
        val ic = currentInputConnection ?: return
        val last = undoStack.removeLastOrNull() ?: run { setStatus("Nothing to undo"); return }
        val before = ic.getTextBeforeCursor(last.length, 0)?.toString()
        if (before == last) { ic.deleteSurroundingText(last.length, 0); setStatus("Undone") }
        else { undoStack.clear(); setStatus("Text was edited – cannot undo safely") }
    }

    private fun enter() {
        endComposition()
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ((ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val multiLine = ((ei?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
            ic.performEditorAction(action)
        else ic.commitText("\n", 1)
    }

    private fun switchBack() {
        engine.stop()
        val switched = if (Build.VERSION.SDK_INT >= 28) switchToPreviousInputMethod() else false
        if (!switched) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun teach() {
        val raw = prefs.lastRaw
        if (raw.isBlank()) { setStatus("Dictate something first, then Teach corrects it"); return }
        openSettings(raw)
    }

    private fun openSettings(teachRaw: String?) {
        engine.stop()
        val i = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (teachRaw != null) i.putExtra(SettingsActivity.EXTRA_TEACH, teachRaw).putExtra(SettingsActivity.EXTRA_WRITTEN, lastProcessed)
        startActivity(i)
    }

    companion object {
        private const val BG = 0xFF15171C.toInt()
        private const val KEY = 0xFF2C313A.toInt()
        private const val KEY_DARK = 0xFF22262D.toInt()
        private const val MIC_IDLE = 0xFF1F6FEB.toInt()
        private const val MIC_ON = 0xFFD93025.toInt()
    }
}
