package com.djphoenix.moasherlibya.parser

/** Arabic text normalisation helpers used by the price parser. */
object Arabic {
    private val arabicIndic = "٠١٢٣٤٥٦٧٨٩"
    private val easternIndic = "۰۱۲۳۴۵۶۷۸۹"

    /** Arabic-Indic / Persian digits -> ASCII, Arabic decimal & thousands separators -> ASCII. */
    fun digitsToAscii(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val i = arabicIndic.indexOf(c)
            val j = easternIndic.indexOf(c)
            when {
                i >= 0 -> sb.append('0' + i)
                j >= 0 -> sb.append('0' + j)
                c == '٫' -> sb.append('.')
                c == '٬' -> sb.append(',')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun isMark(c: Char) = c in '\u064B'..'\u065F' || c == '\u0670' || c == '\u0640' ||
        c in '\u200B'..'\u200F' || c in '\u202A'..'\u202E' || c == '\u2066' || c == '\u2067' ||
        c == '\u2068' || c == '\u2069' || c == '\uFEFF' || c == '\uFE0F'

    /**
     * Light normalisation: strip diacritics/tatweel/bidi marks, unify alef forms, ى->ي, ة->ه, ؤ->و, ئ->ي,
     * digits -> ASCII, lower-case latin. Keeps spaces and punctuation.
     */
    fun normalize(s: String): String {
        val d = digitsToAscii(s)
        val sb = StringBuilder(d.length)
        for (c in d) {
            if (c == '\u00A0') { sb.append(' '); continue }
            if (isMark(c)) continue
            sb.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى' -> 'ي'
                    'ة' -> 'ه'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    'ک' -> 'ك'
                    'ی' -> 'ي'
                    else -> c.lowercaseChar()
                },
            )
        }
        return sb.toString()
    }

    /**
     * Compact matching form: normalised, every non letter/digit removed (spaces, '-', '_', '.', '#', ...),
     * and every "ال" removed (so "الذهب", "كسرالذهب" and "كسر ذهب" all compare equal).
     */
    fun compact(s: String): String {
        val n = normalize(s)
        val sb = StringBuilder(n.length)
        for (c in n) if (c.isLetterOrDigit()) sb.append(c)
        return sb.toString().replace("ال", "")
    }
}
