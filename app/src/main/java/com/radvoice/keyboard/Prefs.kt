package com.radvoice.keyboard

import android.content.Context
import android.content.SharedPreferences

/** All user settings in one SharedPreferences file. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var language: String
        get() = sp.getString("language", "en-IN") ?: "en-IN"
        set(v) = sp.edit().putString("language", v).apply()

    var preferOnDevice: Boolean
        get() = sp.getBoolean("preferOnDevice", true)
        set(v) = sp.edit().putBoolean("preferOnDevice", v).apply()

    var spelling: TextProcessor.Spelling
        get() = runCatching { TextProcessor.Spelling.valueOf(sp.getString("spelling", "BRITISH")!!) }
            .getOrDefault(TextProcessor.Spelling.BRITISH)
        set(v) = sp.edit().putString("spelling", v.name).apply()

    /** " × " or " x " */
    var dimSep: String
        get() = sp.getString("dimSep", " × ") ?: " × "
        set(v) = sp.edit().putString("dimSep", v).apply()

    var liveComposing: Boolean
        get() = sp.getBoolean("liveComposing", true)
        set(v) = sp.edit().putBoolean("liveComposing", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", false)
        set(v) = sp.edit().putBoolean("autoStart", v).apply()

    var autoCapitalise: Boolean
        get() = sp.getBoolean("autoCapitalise", true)
        set(v) = sp.edit().putBoolean("autoCapitalise", v).apply()

    /** Lexicon sections to prioritise for recogniser biasing, comma-separated. */
    var focus: Set<String>
        get() = (sp.getString("focus", "obstetric,gynae,neuro,pelvic-floor") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        set(v) = sp.edit().putString("focus", v.joinToString(",")).apply()

    /** User correction rules, "heard<TAB>written" per line. */
    var userCorrections: String
        get() = sp.getString("userCorrections", "") ?: ""
        set(v) = sp.edit().putString("userCorrections", v).apply()

    /** Extra vocabulary, one per line. */
    var userTerms: String
        get() = sp.getString("userTerms", "") ?: ""
        set(v) = sp.edit().putString("userTerms", v).apply()

    /** Raw recogniser text of the last committed utterance (for the Teach button). */
    var lastRaw: String
        get() = sp.getString("lastRaw", "") ?: ""
        set(v) = sp.edit().putString("lastRaw", v).apply()

    fun addCorrection(heard: String, written: String) {
        val h = heard.trim(); val w = written.trim()
        if (h.isEmpty() || w.isEmpty()) return
        val kept = Lexicon.parseCorrections(userCorrections)
            .filter { TextProcessor.normKey(it.first) != TextProcessor.normKey(h) }
        userCorrections = (kept + (h to w)).joinToString("\n") { "${it.first}\t${it.second}" }
    }

    companion object {
        const val FILE = "radvoice"
        val LANGUAGES = listOf(
            "en-IN" to "English (India)",
            "en-GB" to "English (UK)",
            "en-US" to "English (US)",
            "hi-IN" to "Hindi (Devanagari output)",
        )
    }
}
