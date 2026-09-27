package com.radvoice.keyboard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Setup, options, correction table, vocabulary and a test box. Built in code (no XML layouts). */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var testProcessor: TextProcessor? = null

    private lateinit var col: LinearLayout
    private lateinit var setupStatus: TextView
    private lateinit var heardEdit: EditText
    private lateinit var writtenEdit: EditText
    private lateinit var correctionsEdit: EditText
    private lateinit var termsEdit: EditText
    private lateinit var testIn: EditText
    private lateinit var testOut: TextView

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "RadVoice Keyboard"
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(40)) }
        setContentView(ScrollView(this).apply { addView(col) })

        // 1. Setup -----------------------------------------------------------------------
        header("1 · Setup")
        setupStatus = text("", 14f)
        button("Grant microphone permission") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
        button("Enable RadVoice in keyboard settings") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button("Switch keyboard to RadVoice") { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        button("Download offline speech model (Android 13+)") {
            val e = DictationEngine(this, object : DictationEngine.Callback {
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) {}
                override fun onStateChanged(listening: Boolean, status: String) {}
            })
            e.language = prefs.language
            toast(e.downloadOfflineModel())
        }
        note("Offline (on-device) recognition keeps audio on the phone. If the button is unsupported: Google app › Settings › Voice › Offline speech recognition › download English (India).")

        // 2. Recognition -----------------------------------------------------------------
        header("2 · Recognition")
        radio("Language", Prefs.LANGUAGES, prefs.language) { prefs.language = it }
        switch("Prefer on-device recognition (private, no network)", prefs.preferOnDevice) { prefs.preferOnDevice = it }
        switch("Show words live while speaking", prefs.liveComposing) { prefs.liveComposing = it }
        switch("Start listening automatically when keyboard opens", prefs.autoStart) { prefs.autoStart = it }
        switch("Auto-capitalise sentences", prefs.autoCapitalise) { prefs.autoCapitalise = it; rebuildTest() }

        // 3. Output ----------------------------------------------------------------------
        header("3 · Output style")
        radio("Spelling", listOf(
            "BRITISH" to "British (haemorrhage, oedema, tumour)",
            "AMERICAN" to "American (hemorrhage, edema, tumor)",
            "AS_RECOGNISED" to "As recognised (no change)",
        ), prefs.spelling.name) { prefs.spelling = TextProcessor.Spelling.valueOf(it); rebuildTest() }
        radio("Dimension separator", listOf(" × " to "3.2 × 2.1 cm", " x " to "3.2 x 2.1 cm"), prefs.dimSep) {
            prefs.dimSep = it; rebuildTest()
        }

        // 4. Specialty focus -------------------------------------------------------------
        header("4 · Specialty focus (recogniser biasing, Android 13+)")
        note("Core terms and classification systems are always included. Ticked sections are sent first.")
        val sections = LexiconStore.sections(this).keys.filter { it != "core" && it != "classification" }
        val chosen = prefs.focus.toMutableSet()
        sections.forEach { s ->
            col.addView(CheckBox(this).apply {
                text = "$s  (${LexiconStore.sections(this@SettingsActivity)[s]?.size ?: 0} terms)"
                isChecked = s in chosen
                setOnCheckedChangeListener { _, on -> if (on) chosen.add(s) else chosen.remove(s); prefs.focus = chosen.toSet() }
            })
        }

        // 5. Teach -------------------------------------------------------------------------
        header("5 · Teach a correction")
        note("Type what the recogniser wrote and what it should be. Applied to all future dictation.")
        heardEdit = edit("Heard (e.g. plural effusion)", false)
        writtenEdit = edit("Should be (e.g. pleural effusion)", false)
        button("Add correction") {
            val h = heardEdit.text.toString(); val w = writtenEdit.text.toString()
            if (h.isBlank() || w.isBlank()) { toast("Fill both boxes"); return@button }
            prefs.addCorrection(h, w)
            correctionsEdit.setText(prefs.userCorrections)
            heardEdit.setText(""); writtenEdit.setText("")
            rebuildTest(); toast("Saved: $h → $w")
        }

        header("Your correction table")
        note("One rule per line: heard ⇥ written   (a TAB, or  =>  between them). Your rules override built-in ones.")
        correctionsEdit = edit("plural => pleural", true).apply { setText(prefs.userCorrections) }
        button("Save correction table") {
            prefs.userCorrections = correctionsEdit.text.toString().replace(" => ", "\t").replace("=>", "\t")
            correctionsEdit.setText(prefs.userCorrections); rebuildTest(); toast("Saved")
        }

        // 6. Vocabulary ----------------------------------------------------------------------
        header("6 · Extra vocabulary")
        note("Names, local terms, your hospital's templates. One per line. Capitalised/hyphenated entries also fix the written form.")
        termsEdit = edit("e.g. Dr Mehta\nFMF\nIOTA ADNEX", true).apply { setText(prefs.userTerms) }
        button("Save vocabulary") { prefs.userTerms = termsEdit.text.toString(); rebuildTest(); toast("Saved") }

        // 7. Test ----------------------------------------------------------------------------
        header("7 · Test the text engine")
        note("Type raw recogniser-style text to see what will be inserted.")
        testIn = edit("disc desecration at l4 l5 indenting the the cal sac", true).apply {
            setText("there is a well defined hypo echoic lesion measuring three point two by two point one cm full stop plural effusion noted")
        }
        testOut = text("", 15f).apply { setTextColor(0xFF0B5CAD.toInt()); setTextIsSelectable(true) }
        testIn.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = runTest()
        })

        // 8. Commands --------------------------------------------------------------------------
        header("Spoken commands")
        note(
            "full stop · comma · semicolon · colon mark (plain \"colon\" is left as anatomy) · question mark\n" +
            "new line · new paragraph · open bracket · close bracket · hyphen · slash · plus minus\n" +
            "\"3 point 2 by 2 point 1 cm\" → 3.2 × 2.1 cm   ·   \"L4 L5\" → L4–L5\n\n" +
            "Keyboard: ⌨ back to Gboard (long-press: keyboard picker) · ↶ undo last dictation · Teach = correct the last phrase."
        )

        handleTeachIntent(intent)
        rebuildTest()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTeachIntent(intent)
    }

    override fun onResume() { super.onResume(); refreshSetup() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSetup()
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    private fun handleTeachIntent(i: Intent?) {
        val raw = i?.getStringExtra(EXTRA_TEACH) ?: return
        heardEdit.setText(raw)
        writtenEdit.setText(i.getStringExtra(EXTRA_WRITTEN) ?: raw)
        toast("Section 5: trim both boxes to just the wrong phrase and its correction, then Add")
        heardEdit.requestFocus()
    }

    private fun refreshSetup() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        fun mark(ok: Boolean) = if (ok) "✅" else "⬜"
        setupStatus.text = "${mark(mic)} Microphone permission\n${mark(enabled)} Keyboard enabled\n${mark(selected)} RadVoice is the current keyboard"
    }

    // ------------------------------------------------------------------------------ test box

    private fun rebuildTest() {
        worker.execute {
            testProcessor = runCatching { LexiconStore.build(this, prefs).processor }.getOrNull()
            main.post { runTest() }
        }
    }

    private fun runTest() {
        if (!::testIn.isInitialized) return
        val raw = testIn.text.toString()
        worker.execute {
            val p = testProcessor ?: return@execute
            val out = p.joinWithContext("", p.process(raw)).text
            main.post { testOut.text = "→ $out" }
        }
    }

    // ------------------------------------------------------------------------------ view helpers

    private fun header(s: String) = col.addView(TextView(this).apply {
        text = s; setTypeface(typeface, Typeface.BOLD); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(0, dp(20), 0, dp(6))
    })

    private fun text(s: String, sp: Float) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setPadding(0, dp(2), 0, dp(6))
    }.also { col.addView(it) }

    private fun note(s: String) = text(s, 13f).apply { setTextColor(Color.GRAY) }

    private fun button(label: String, onClick: () -> Unit) = col.addView(Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    })

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) = col.addView(Switch(this).apply {
        text = label; isChecked = value; setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    })

    private fun radio(title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        text(title, 14f)
        val g = RadioGroup(this)
        options.forEachIndexed { idx, (value, label) ->
            g.addView(RadioButton(this).apply { id = View.generateViewId(); text = label; tag = value; isChecked = value == current })
        }
        g.setOnCheckedChangeListener { grp, id -> (grp.findViewById<View>(id)?.tag as? String)?.let(onPick) }
        col.addView(g)
    }

    private fun edit(hint: String, multi: Boolean) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if (multi) { minLines = 3; maxLines = 12 }
        typeface = Typeface.MONOSPACE
    }.also { col.addView(it) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_TEACH = "teach_raw"
        const val EXTRA_WRITTEN = "teach_written"
    }
}
