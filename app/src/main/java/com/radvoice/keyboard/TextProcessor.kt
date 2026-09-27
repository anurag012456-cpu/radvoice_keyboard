package com.radvoice.keyboard

import java.text.Normalizer

/**
 * Deterministic radiology post-processor applied to every recognised utterance.
 *
 * Pipeline (order matters):
 *  1. Spoken commands   ("full stop", "new line", "comma" ...)   -> punctuation / layout
 *  2. Corrections       (user table first, then built-in mishearing table)
 *  3. Numbers           ("three point two" -> 3.2, "point 5 cm" -> 0.5 cm)
 *  4. Units             (millimetres -> mm, Hounsfield units -> HU, per cent -> %)
 *  5. Dimensions        ("3.2 by 2.1 into 1.8 cm" -> 3.2 × 2.1 × 1.8 cm)
 *  6. Spinal levels     ("l 4 l 5" -> L4–L5, adjacency-validated)
 *  7. Canonical forms   (lexicon: bi rads -> BI-RADS, t2 weighted -> T2-weighted, doppler -> Doppler)
 *  8. Spelling          (British default: haemorrhage, oedema; or American)
 *  9. Cleanup + sentence capitalisation
 *
 * No Android dependencies, so it is unit-tested on the JVM.
 */
class TextProcessor(
    lexiconTerms: Collection<String>,
    corrections: List<Pair<String, String>>,
    private val spelling: Spelling = Spelling.BRITISH,
    private val autoCapitalise: Boolean = true,
    private val dimensionSeparator: String = " × ",
) {
    enum class Spelling { BRITISH, AMERICAN, AS_RECOGNISED }

    /** Text to insert, plus how many characters before the cursor to delete first (e.g. a stray space before a comma). */
    data class Insertion(val deleteBefore: Int, val text: String)

    private class Rule(val regex: Regex, val replacement: String, val allowPlural: Boolean = false)

    private val correctionRules: List<Rule>
    private val canonicalRules: List<Rule>
    private val properWords: Set<String>

    init {
        val seen = HashSet<String>()
        correctionRules = corrections
            .map { it.first.trim() to it.second.trim() }
            .filter { it.first.isNotEmpty() && seen.add(normKey(it.first)) } // first occurrence wins -> user table overrides
            .sortedByDescending { it.first.length }
            .map { Rule(bounded(flex(it.first)), it.second) }

        val canon = LinkedHashMap<String, String>()
        for (raw in lexiconTerms) {
            val term = raw.trim()
            if (term.isEmpty() || !needsCanonical(term) || term.lowercase() in STOP) continue
            canon.putIfAbsent(normKey(term), term)
        }
        canonicalRules = canon.values
            .sortedByDescending { it.length }
            .map { t -> Rule(bounded(flex(t), pluralSuffix = t.last().isLowerCase()), t, t.last().isLowerCase()) }

        properWords = lexiconTerms
            .flatMap { it.split(' ', '-', '–', '/') }
            .map { it.trim().removeSuffix("'s").removeSuffix("’s") }
            .filter { it.length > 1 && it[0].isUpperCase() && it.drop(1).any { c -> c.isLowerCase() } }
            .map { it.lowercase() }
            .toSet()
    }

    // ------------------------------------------------------------------ public API

    /** Normalise one recognised utterance. Returns text without leading/trailing spaces. */
    fun process(raw: String): String {
        var s = " " + raw.replace(Regex("\\s+"), " ").trim() + " "
        if (s.isBlank()) return ""
        s = applyCommands(s)
        for (r in correctionRules) s = r.regex.replace(s) { r.replacement }
        s = numbers(s)
        s = units(s)
        s = dimensions(s)
        s = spinalLevels(s)
        for (r in canonicalRules) s = applyCanonical(r, s)
        s = spellingPass(s)
        s = cleanup(s)
        return s
    }

    /** Work out spacing/capitalisation of [chunk] relative to the text already before the cursor. */
    fun joinWithContext(before: CharSequence?, chunk: String): Insertion {
        if (chunk.isEmpty()) return Insertion(0, "")
        val b = before?.toString() ?: ""
        var text = chunk
        var delete = 0
        val first = text[0]
        when {
            first in CLOSING_PUNCT -> if (b.endsWith(" ")) delete = 1
            first == '\n' -> if (b.endsWith(" ")) delete = 1
            b.isNotEmpty() && !b.last().isWhitespace() && b.last() != '(' && b.last() != '/' -> text = " $text"
        }
        val trimmed = b.dropLast(delete).trimEnd(' ')
        val sentenceStart = trimmed.isEmpty() || trimmed.last() in ".?!:\n"
        if (autoCapitalise) text = if (sentenceStart) capitaliseFirst(text) else decapitaliseFirst(text)
        return Insertion(delete, text)
    }

    // ------------------------------------------------------------------ 1. commands

    private fun applyCommands(input: String): String {
        var s = input
        for ((re, rep) in COMMANDS) s = re.replace(s) { rep }
        return s
    }

    // ------------------------------------------------------------------ 3. numbers

    private fun numbers(input: String): String {
        var s = input
        val w = NUMBER_WORDS.keys.joinToString("|")
        val unitAhead = "(?=\\s+(?:(?:by|x|into|cross)\\s+(?:\\d|$w)|point\\s|mm\\b|cm\\b|millimet|centimet|ml\\b|millilit|cc\\b|hounsfield|hu\\b|percent|per cent|weeks?\\b|days?\\b|degrees?\\b|grams?\\b|kg\\b|years?\\b|months?\\b))"
        // compound tens: "thirty five mm" -> 35 mm
        val tens = "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety"
        val ones = "one|two|three|four|five|six|seven|eight|nine"
        s = Regex("(?i)(?<![\\p{L}\\p{N}])($tens)[\\s-]+($ones)$unitAhead").replace(s) {
            (NUMBER_WORDS[it.groupValues[1].lowercase()]!!.toInt() + NUMBER_WORDS[it.groupValues[2].lowercase()]!!.toInt()).toString()
        }
        s = Regex("(?i)(?<![\\p{L}\\p{N}])($w)$unitAhead").replace(s) { NUMBER_WORDS[it.groupValues[1].lowercase()]!! }
        s = Regex("(?i)(?<=point\\s)($w)(?![\\p{L}])").replace(s) { NUMBER_WORDS[it.groupValues[1].lowercase()]!! }
        s = Regex("(?i)(\\d)(\\s+(?:by|x|into|cross)\\s+)($w)(?![\\p{L}])").replace(s) {
            it.groupValues[1] + it.groupValues[2] + NUMBER_WORDS[it.groupValues[3].lowercase()]!!
        }
        // "3 point 2" -> 3.2  (a spoken full stop between numbers is deliberately NOT merged)
        s = Regex("(?i)(\\d)\\s+point\\s+(\\d)").replace(s) { it.groupValues[1] + "." + it.groupValues[2] }
        return s
    }

    // ------------------------------------------------------------------ 4. units

    private fun units(input: String): String {
        var s = input
        for ((re, rep) in UNITS) s = re.replace(s, rep)
        // leading "point 5 cm" -> 0.5 cm
        s = Regex("(?i)(?<![\\d.])\\bpoint\\s+(\\d+)(?=\\s*(?:mm|cm|mL|cc|HU|g|%))").replace(s) { "0." + it.groupValues[1] }
        return s
    }

    // ------------------------------------------------------------------ 5. dimensions

    private fun dimensions(input: String): String {
        var s = input
        val re = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:x|×|by|into|cross)\\s*(?=\\d)")
        var prev: String
        do {
            prev = s
            s = re.replace(s) { it.groupValues[1] + dimensionSeparator }
        } while (s != prev)
        return s
    }

    // ------------------------------------------------------------------ 6. spinal levels

    private fun spinalLevels(input: String): String {
        var s = input
        // Pair with both letters: "l4 l5", "L4 to L5", "c7-t1"
        s = LEVEL_PAIR.replace(s) { m ->
            val l1 = m.groupValues[1][0].uppercaseChar(); val n1 = m.groupValues[2].toInt()
            val conn = m.groupValues[3]
            val l2 = m.groupValues[4][0].uppercaseChar(); val n2 = m.groupValues[5].toInt()
            val i1 = levelIndex(l1, n1); val i2 = levelIndex(l2, n2)
            // "T1 T2" without an explicit connector is almost always MR sequences, not a disc level.
            val sequenceClash = l1 == 'T' && l2 == 'T' && n1 == 1 && n2 == 2 && conn.isBlank()
            if (i1 >= 0 && i2 == i1 + 1 && !sequenceClash) "$l1$n1–$l2$n2" else m.value
        }
        // Shorthand with explicit hyphen: "L4-5" -> L4–L5
        s = LEVEL_SHORT.replace(s) { m ->
            val l = m.groupValues[1][0].uppercaseChar(); val n1 = m.groupValues[2].toInt(); val n2 = m.groupValues[3].toInt()
            val i1 = levelIndex(l, n1); val i2 = levelIndex(l, n2)
            if (i1 >= 0 && i2 == i1 + 1) "$l$n1–$l$n2" else m.value
        }
        // Single token: "l 5" -> L5, "t2" -> T2, "c8" (root) -> C8
        s = LEVEL_SINGLE.replace(s) { m ->
            val l = m.groupValues[1][0].uppercaseChar(); val n = m.groupValues[2].toInt()
            val max = when (l) { 'C' -> 8; 'T' -> 12; 'L' -> 5; else -> 5 }
            if (n in 1..max) "$l$n" else m.value
        }
        return s
    }

    // ------------------------------------------------------------------ 7. canonical forms

    private fun applyCanonical(r: Rule, s: String): String =
        r.regex.replace(s) { m ->
            val suffix = if (r.allowPlural) m.groups[1]?.value ?: "" else ""
            r.replacement + suffix
        }

    // ------------------------------------------------------------------ 8. spelling

    private fun spellingPass(input: String): String {
        val rules = when (spelling) {
            Spelling.BRITISH -> UK_RULES
            Spelling.AMERICAN -> US_RULES
            Spelling.AS_RECOGNISED -> return input
        }
        var s = input
        for ((re, rep) in rules) s = re.replace(s) { m -> matchCase(m.value, rep(m)) }
        return s
    }

    // ------------------------------------------------------------------ 9. cleanup

    private fun cleanup(input: String): String {
        var s = input
        s = s.replace(Regex("[ \\t]*\uE001[ \\t]*"), "-")            // spoken "hyphen"
        s = s.replace(Regex("[ \\t]*\uE002[ \\t]*"), "/")            // spoken "slash"
        s = s.replace(Regex("\\s+([.,;:?!)%])"), "$1")
        s = s.replace(Regex("\\(\\s+"), "(")
        s = s.replace(Regex("([,;:])(?=[\\p{L}])"), "$1 ")
        s = s.replace(Regex("([.?!])(?=\\p{Lu}\\p{Ll})"), "$1 ")
        s = s.replace(Regex("[.]{2,}"), ".")
        s = s.replace(Regex(",\\s*\\."), ".")
        s = s.replace(Regex("[ \\t]*([\u2028\u2029])[ \\t]*"), "$1")
        s = s.replace(Regex("[ \\t]{2,}"), " ")
        s = s.replace(PARA.toString(), "\n\n").replace(LINE.toString(), "\n")
        s = s.trim(' ', '\t')
        if (autoCapitalise) {
            s = Regex("([.?!]\\s+|\\n)(\\p{Ll})").replace(s) { it.groupValues[1] + it.groupValues[2].uppercase() }
        }
        return s
    }

    // ------------------------------------------------------------------ helpers

    private fun capitaliseFirst(t: String): String {
        val i = t.indexOfFirst { it.isLetter() }
        if (i < 0 || !t[i].isLowerCase()) return t
        // don't capitalise units that must stay lower-case at sentence start (rare) e.g. "mL"
        return t.substring(0, i) + t[i].uppercaseChar() + t.substring(i + 1)
    }

    private fun decapitaliseFirst(t: String): String {
        val m = Regex("^(\\s*)(\\p{Lu})(\\p{Ll}+)").find(t) ?: return t
        val word = (m.groupValues[2] + m.groupValues[3])
        val wordEnd = m.range.last + 1
        val rest = t.substring(wordEnd)
        if (word.lowercase() in properWords) return t   // eponyms stay capitalised (Doppler, Morison's)
        return m.groupValues[1] + word.lowercase() + rest
    }

    companion object {
        const val PARA = '\u2029'
        const val LINE = '\u2028'
        private const val CLOSING_PUNCT = ".,;:?!)%"

        /** Short lexicon entries that are also common English words: never force their casing. */
        private val STOP = setOf(
            "a", "i", "an", "am", "as", "at", "be", "by", "do", "go", "he", "if", "in", "is", "it", "me", "my",
            "no", "of", "on", "or", "so", "to", "up", "us", "we", "all", "art", "cat", "dip", "lip", "net", "op",
            "tips", "hp", "la", "ba", "va", "sd", "sv", "et", "ms", "cam", "fast", "won", "cm", "mm", "it's"
        )

        private val NUMBER_WORDS = linkedMapOf(
            "seventeen" to "17", "thirteen" to "13", "fourteen" to "14", "fifteen" to "15", "sixteen" to "16",
            "eighteen" to "18", "nineteen" to "19", "eleven" to "11", "twelve" to "12", "twenty" to "20",
            "thirty" to "30", "forty" to "40", "fifty" to "50", "sixty" to "60", "seventy" to "70",
            "eighty" to "80", "ninety" to "90", "zero" to "0", "one" to "1", "two" to "2", "three" to "3",
            "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10"
        )

        private fun cmd(pattern: String) = Regex("(?i)(?<![\\p{L}])(?:$pattern)(?![\\p{L}])")

        private val COMMANDS: List<Pair<Regex, String>> = listOf(
            cmd("new paragraph|next paragraph") to " $PARA ",
            cmd("new line|next line") to " $LINE ",
            cmd("full stop") to ".",
            cmd("semi ?colon") to ";",
            // NB: bare "colon" is anatomy (sigmoid colon) – punctuation colon must be said as "colon mark"
            cmd("colon mark|punctuation colon") to ":",
            cmd("comma") to ",",
            cmd("question mark") to "?",
            cmd("open bracket|open parenthesis") to " (",
            cmd("close bracket|close parenthesis") to ")",
            cmd("hyphen") to "\uE001",
            cmd("forward slash|slash") to "\uE002",
            cmd("plus or minus|plus minus") to " ± ",
        )

        private fun unit(p: String) = Regex("(?i)(\\d)\\s*(?:$p)(?![\\p{L}])")

        private val UNITS: List<Pair<Regex, String>> = listOf(
            unit("millimet(?:er|re)s? of mercury|mm ?hg") to "$1 mmHg",
            unit("centimet(?:er|re)s? per second|cm per second|cm ?/ ?s(?:ec)?") to "$1 cm/s",
            unit("met(?:er|re)s? per second") to "$1 m/s",
            unit("millimet(?:er|re)s?|mms|mm") to "$1 mm",
            unit("centimet(?:er|re)s?|cms|cm") to "$1 cm",
            unit("cubic centimet(?:er|re)s?|cc") to "$1 cc",
            unit("millilit(?:er|re)s?|mls|ml") to "$1 mL",
            unit("hounsfield units?|hu") to "$1 HU",
            unit("per ?cent|percent|%") to "$1%",
            unit("kilograms?|kgs?") to "$1 kg",
            unit("grams?|gms?") to "$1 g",
            unit("beats per minute|bpm") to "$1 bpm",
            unit("kilo ?pascals?|kpa") to "$1 kPa",
        )

        private val LEVEL_ORDER = (1..7).map { "C$it" } + (1..12).map { "T$it" } + (1..5).map { "L$it" } + (1..5).map { "S$it" }
        private fun levelIndex(l: Char, n: Int) = LEVEL_ORDER.indexOf("$l$n")
        private const val LB = "(?<![\\p{L}\\p{N}'’])"
        private const val RB = "(?![\\p{L}\\p{N}])"
        private val LEVEL_PAIR = Regex("$LB([ctlsCTLS]) ?(\\d{1,2})\\s*(-|–|/|to)?\\s*([ctlsCTLS]) ?(\\d{1,2})$RB")
        private val LEVEL_SHORT = Regex("$LB([ctlsCTLS]) ?(\\d{1,2})\\s*[-–/]\\s*(\\d{1,2})$RB")
        private val LEVEL_SINGLE = Regex("$LB([ctlsCTLS]) ?(\\d{1,2})$RB")

        // --- spelling -------------------------------------------------------------------------
        private fun r(p: String) = Regex("(?i)$p")
        private val UK_RULES: List<Pair<Regex, (MatchResult) -> String>> = listOf(
            r("(?<![\\p{L}])gastroesophag") to { _ -> "gastro-oesophag" },
            r("(?<![\\p{L}])hem(?=(?:orrhag|atom|angio|osiderin|operitone|othora|arthros|atocel|atometr|atocolp|atosalp|atur|olys|oglobin|odynam|ostas|optys|atemes|atopoie|ochromat|atocrit|opericard|atolog|obilia|ophil|atogen|orrhoid|odialys|oconcentr|odilut))") to { _ -> "haem" },
            r("(?<=cephal|hydro|pyo)hem(?=at)") to { _ -> "haem" },
            r("(?<![\\p{L}])(isch|an|hyper|leuk|septic|ur|hypox|bacter|vir|tox|hypovol|hyperlipid)emi(?=a|c)") to { m -> m.groupValues[1] + "aemi" },
            r("(?<![\\p{L}])edem(?=a)") to { _ -> "oedem" },
            r("(?<=lymph|myx|papill|angio)edem(?=a)") to { _ -> "oedem" },
            r("(?<![\\p{L}])esophag") to { _ -> "oesophag" },
            r("(?<![\\p{L}])estrogen") to { _ -> "oestrogen" },
            r("(?<![\\p{L}])etiolog") to { _ -> "aetiolog" },
            r("pediatr") to { _ -> "paediatr" },
            r("gynec") to { _ -> "gynaec" },
            r("orthoped") to { _ -> "orthopaed" },
            r("anesthe") to { _ -> "anaesthe" },
            r("(?<![\\p{L}])(ileo)?cec(?=um|al)") to { m -> m.groupValues[1] + "caec" },
            r("(?<=\\p{L})rrhea(?![\\p{L}])") to { _ -> "rrhoea" },
            r("(?<=\\p{L})pnea(?![\\p{L}])") to { _ -> "pnoea" },
            r("(?<![\\p{L}])amebi") to { _ -> "amoebi" },
            r("(?<![\\p{L}])tumor(?=s?(?![\\p{L}]))") to { _ -> "tumour" },
            r("(?<![\\p{L}])color(?=s?(?![\\p{L}])|ed|ing)") to { _ -> "colour" },
            r("(?<![\\p{L}])gray(?![\\p{L}])") to { _ -> "grey" },
            r("(?<![\\p{L}])(epi)?center(?=s?(?![\\p{L}]))") to { m -> m.groupValues[1] + "centre" },
            r("(?<![\\p{L}])fiber(?=s?(?![\\p{L}]))") to { _ -> "fibre" },
            r("(?<![\\p{L}])caliber(?![\\p{L}])") to { _ -> "calibre" },
            r("(?<![\\p{L}])goiter(?![\\p{L}])") to { _ -> "goitre" },
            r("(?<![\\p{L}])maneuver(?=s?(?![\\p{L}]))") to { _ -> "manoeuvre" },
            r("(?<![\\p{L}])previa(?![\\p{L}])") to { _ -> "praevia" },
            r("(?<![\\p{L}])artifact") to { _ -> "artefact" },
            r("(?<![\\p{L}])(organ|necrot|recanal|sacral|lumbar|mineral|peripheral|local|visual|character|vascular|opacif)iz(?=ation|ing|ed|e)") to { m -> m.groupValues[1] + "is" },
        )
        private val US_RULES: List<Pair<Regex, (MatchResult) -> String>> = listOf(
            r("gastro-oesophag") to { _ -> "gastroesophag" },
            r("haem") to { _ -> "hem" },
            r("(?<=\\p{L})aemi(?=a|c)") to { _ -> "emi" },
            r("oedem") to { _ -> "edem" },
            r("oesophag") to { _ -> "esophag" },
            r("oestrogen") to { _ -> "estrogen" },
            r("aetiolog") to { _ -> "etiolog" },
            r("paediatr") to { _ -> "pediatr" },
            r("gynaec") to { _ -> "gynec" },
            r("orthopaed") to { _ -> "orthoped" },
            r("anaesthe") to { _ -> "anesthe" },
            r("caec(?=um|al)") to { _ -> "cec" },
            r("rrhoea") to { _ -> "rrhea" },
            r("pnoea") to { _ -> "pnea" },
            r("amoebi") to { _ -> "amebi" },
            r("(?<![\\p{L}])tumour") to { _ -> "tumor" },
            r("(?<![\\p{L}])colour") to { _ -> "color" },
            r("(?<![\\p{L}])grey(?![\\p{L}])") to { _ -> "gray" },
            r("centre(?=s?(?![\\p{L}])|d)") to { _ -> "center" },
            r("(?<![\\p{L}])fibre") to { _ -> "fiber" },
            r("(?<![\\p{L}])calibre") to { _ -> "caliber" },
            r("(?<![\\p{L}])goitre") to { _ -> "goiter" },
            r("(?<![\\p{L}])manoeuvre") to { _ -> "maneuver" },
            r("(?<![\\p{L}])praevia") to { _ -> "previa" },
            r("(?<![\\p{L}])artefact") to { _ -> "artifact" },
            r("(?<![\\p{L}])(organ|necrot|recanal|sacral|lumbar|mineral|peripheral|local|visual|character|vascular|opacif)is(?=ation|ing|ed|e)") to { m -> m.groupValues[1] + "iz" },
        )

        private fun matchCase(original: String, replacement: String): String =
            if (original.isNotEmpty() && original[0].isUpperCase() && replacement.isNotEmpty())
                replacement[0].uppercaseChar() + replacement.substring(1) else replacement

        // --- pattern building -------------------------------------------------------------------
        private fun stripDiacritics(s: String) =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

        fun normKey(s: String): String =
            stripDiacritics(s.lowercase()).replace(Regex("['’]"), "").replace(Regex("[\\s\\-–‐]+"), " ").trim()

        private fun needsCanonical(term: String) =
            term.any { it.isUpperCase() || it.isDigit() || it in "-–'’/*+." || it.code > 127 }

        /**
         * Build a tolerant pattern: case-insensitive, space/hyphen/no-space equivalent,
         * optional apostrophe, British/American spelling equivalent (ae/e, oe/e, our/or, re/er, is/iz, grey/gray).
         */
        fun flex(term: String): String {
            val t = term.trim()
            val sb = StringBuilder()
            var i = 0
            while (i < t.length) {
                val ch = t[i]
                val next = t.getOrNull(i + 1)
                val prev = t.getOrNull(i - 1)
                when {
                    ch == ' ' || ch == '-' || ch == '–' || ch == '‐' -> {
                        if (!sb.endsWith("[\\s\\-–‐]*")) sb.append("[\\s\\-–‐]*")
                    }
                    ch == '\'' || ch == '’' -> sb.append("['’]?")
                    ch == '/' -> sb.append("\\s*/\\s*")
                    t.regionMatches(i, "artefact", 0, 8, ignoreCase = true) -> { sb.append("art[ei]fact"); i += 7 }
                    t.regionMatches(i, "grey", 0, 4, ignoreCase = true) -> { sb.append("gr[ae]y"); i += 3 }
                    (ch == 'a' || ch == 'o') && next == 'e' && prev != null && prev.isLetter() && ch == 'a' -> sb.append("a?")
                    ch == 'o' && next == 'e' && (prev == null || !prev.isLetter() || prev == 'h' || prev == 'n' || prev == '-') -> sb.append("o?")
                    ch == 'o' && next == 'u' && t.getOrNull(i + 2) == 'r' -> { sb.append("ou?"); i += 1 }
                    ch == 'r' && next == 'e' && prev != null && prev in "btv" &&
                        (t.getOrNull(i + 2)?.isLetter() != true || t.getOrNull(i + 2) == 's') -> { sb.append("(?:re|er)"); i += 1 }
                    ch == 's' && prev == 'i' && next != null && next in "aie" && i > 2 -> sb.append("[sz]")
                    ch.isLetterOrDigit() -> {
                        if (ch.code > 127) {
                            val base = stripDiacritics(ch.toString())
                            sb.append('[').append(ch.lowercaseChar()).append(ch.uppercaseChar())
                                .append(base.lowercase()).append(base.uppercase()).append(']')
                        } else sb.append(ch)
                    }
                    else -> sb.append(Regex.escape(ch.toString()))
                }
                i++
            }
            return sb.toString()
        }

        private fun bounded(p: String, pluralSuffix: Boolean = false): Regex {
            val suffix = if (pluralSuffix) "(e?s)?" else ""
            return Regex("(?<![\\p{L}\\p{N}])(?:$p)$suffix(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        }
    }
}
