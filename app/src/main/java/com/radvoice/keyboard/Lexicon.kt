package com.radvoice.keyboard

/** Pure-Kotlin parsers for the lexicon and correction assets (JVM-testable). */
object Lexicon {

    /** Returns section name -> ordered terms. Terms before any header go to "core". */
    fun parseSections(text: String): LinkedHashMap<String, MutableList<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var section = "core"
        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                out.getOrPut(section) { mutableListOf() }
                continue
            }
            val list = out.getOrPut(section) { mutableListOf() }
            line.split('|').map { it.trim() }.filter { it.isNotEmpty() }.forEach { list.add(it) }
        }
        return out
    }

    /** Parses "heard<TAB>written" lines (also accepts " => " as separator for hand-typed user rules). */
    fun parseCorrections(text: String): List<Pair<String, String>> =
        text.lineSequence().mapNotNull { raw ->
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) return@mapNotNull null
            val parts = when {
                '\t' in line -> line.split('\t', limit = 2)
                "=>" in line -> line.split("=>", limit = 2)
                else -> return@mapNotNull null
            }
            val heard = parts[0].trim(); val written = parts.getOrNull(1)?.trim() ?: ""
            if (heard.isEmpty() || written.isEmpty()) null else heard to written
        }.toList()

    /** User vocabulary: one term per line or separated by "|" / ",". */
    fun parseUserTerms(text: String): List<String> =
        text.split('\n', '|', ',').map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

    /**
     * Biasing phrases for the recogniser, most important first:
     * user terms -> correction targets -> core -> chosen specialty sections -> everything else. Capped.
     */
    fun biasList(
        sections: Map<String, List<String>>,
        focus: Collection<String>,
        userTerms: List<String>,
        corrections: List<Pair<String, String>>,
        cap: Int = 1000,
    ): List<String> {
        val seen = LinkedHashSet<String>()
        fun add(t: String) { if (seen.size < cap && t.length in 2..60) seen.add(t) }
        userTerms.forEach(::add)
        corrections.map { it.second }.filter { it.any(Char::isLetter) }.forEach(::add)
        sections["core"]?.forEach(::add)
        sections["classification"]?.forEach(::add)
        focus.forEach { f -> sections[f]?.forEach(::add) }
        sections.forEach { (_, terms) -> terms.forEach(::add) }
        return seen.toList()
    }
}
