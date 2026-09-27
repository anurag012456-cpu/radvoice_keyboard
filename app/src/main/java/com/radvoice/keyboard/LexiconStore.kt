package com.radvoice.keyboard

import android.content.Context

/** Loads assets once, then builds a TextProcessor + bias list from the current preferences. */
object LexiconStore {
    @Volatile private var sections: Map<String, List<String>>? = null
    @Volatile private var builtIn: List<Pair<String, String>>? = null

    fun sections(ctx: Context): Map<String, List<String>> =
        sections ?: synchronized(this) {
            sections ?: Lexicon.parseSections(readAsset(ctx, "radiology_lexicon.txt")).also { sections = it }
        }

    private fun builtInCorrections(ctx: Context): List<Pair<String, String>> =
        builtIn ?: synchronized(this) {
            builtIn ?: Lexicon.parseCorrections(readAsset(ctx, "corrections.tsv")).also { builtIn = it }
        }

    private fun readAsset(ctx: Context, name: String): String =
        ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    class Built(val processor: TextProcessor, val biasing: List<String>)

    fun build(ctx: Context, prefs: Prefs): Built {
        val secs = sections(ctx)
        val userTerms = Lexicon.parseUserTerms(prefs.userTerms)
        val userCorr = Lexicon.parseCorrections(prefs.userCorrections)
        val allCorr = userCorr + builtInCorrections(ctx)          // user rules first -> they win
        val terms = userTerms + secs.values.flatten()
        val processor = TextProcessor(
            lexiconTerms = terms,
            corrections = allCorr,
            spelling = prefs.spelling,
            autoCapitalise = prefs.autoCapitalise,
            dimensionSeparator = prefs.dimSep,
        )
        val bias = Lexicon.biasList(secs, prefs.focus, userTerms, allCorr)
        return Built(processor, bias)
    }
}
