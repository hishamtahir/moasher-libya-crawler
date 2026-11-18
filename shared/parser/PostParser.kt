package com.djphoenix.moasherlibya.parser

/**
 * Robust parser for the "price post" formats used by the source channels.
 *
 * Pipeline per post:
 *  1. split into segments on newlines and on any emoji / arrow / symbol glyph;
 *  2. glue orphan segments back (a segment starting with "=" belongs to the previous label, a unit-only
 *     segment belongs to the previous value);
 *  3. for every segment containing "=" extract label (left) and number + unit (right);
 *  4. map the label (compact-normalised) to a catalog item, or keep it as an "other" item with its
 *     original label.
 */
object PostParser {

    private const val SEP = '\u0001'

    private fun isSeparatorGlyph(cp: Int): Boolean =
        cp in 0x1F000..0x1FFFF ||            // emoji, flags, symbols & pictographs
            cp in 0x2190..0x21FF ||          // arrows
            cp in 0x2300..0x23FF ||          // misc technical (⏰ ⏱)
            cp in 0x25A0..0x25FF ||          // geometric shapes (▲ ▼ ●)
            cp in 0x2600..0x27BF ||          // misc symbols, dingbats (⚡ ✅ ❇)
            cp in 0x2900..0x297F ||          // supplemental arrows
            cp in 0x2B00..0x2BFF ||          // arrows & symbols (⬆ ⬇ ⬅ ⭐)
            cp == 0x00AD || cp == 0x2022 || cp == 0x25CF || cp == 0x2605 || cp == 0x2606 ||
            cp == 0x3030 || cp == 0xFE0F || cp == 0x200D

    /** Replace separator glyphs by [SEP]. */
    private fun segmentize(text: String): List<String> {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            if (isSeparatorGlyph(cp)) {
                if (cp != 0xFE0F && cp != 0x200D) sb.append(SEP)
            } else sb.appendCodePoint(cp)
            i += n
        }
        val out = ArrayList<String>()
        for (line in sb.toString().split('\n', '\r')) {
            for (seg in line.split(SEP)) {
                val t = seg.trim()
                if (t.isNotEmpty()) out.add(t)
            }
        }
        return out
    }

    private val numberRe = Regex("""^\s*[$]?\s*(\d[\d,]*(?:\.\d+)*)""")
    private val rangeAfterRe = Regex("""^\s*[~\-–—]\s*[$]?\s*\d""")
    private val colonDecimalRe = Regex("""^(\s*[$]?\s*)(\d{1,2}):(\d{1,3})(?![\d:])""")
    private val malformedRe = Regex("""\d+\.\d+\.\d+""")

    /** Parse "3,952.84" / "47,859" / "62,5" / "10.135" to a Double, or null if malformed. */
    fun parseNumber(raw: String): Double? {
        var s = raw.trim().trim(',')
        if (s.isEmpty()) return null
        if (malformedRe.containsMatchIn(s)) return null
        if (',' in s) {
            s = if ('.' in s) {
                val ip = s.substringBefore('.')
                val g = ip.split(',')
                if (g[0].length !in 1..3 || g.drop(1).any { it.length != 3 }) return null   // "2,6458.77" is a typo
                s.replace(",", "")                                  // 3,952.84
            } else {
                val parts = s.split(',')
                if (parts.size > 1 && parts.drop(1).all { it.length == 3 }) parts.joinToString("")   // 47,859 / 1,234,567
                else if (parts.size == 2 && parts[1].length in 1..2) parts[0] + "." + parts[1]       // 62,5
                else return null
            }
        }
        return s.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    }

    private fun glue(segs: List<String>): List<String> {
        val out = ArrayList<StringBuilder>()
        for (s in segs) {
            val prev = out.lastOrNull()
            when {
                prev != null && s.startsWith("=") && '=' !in prev -> prev.append(' ').append(s)
                // unit / tail fragment of the previous entry, e.g. "دينار" after "100 دينار ليبي 🇱🇾 = 62,5 دينار تونسي 🇹🇳"
                prev != null && '=' !in s && '=' in prev && prev.endsWith("=") -> prev.append(' ').append(s)
                else -> out.add(StringBuilder(s))
            }
        }
        return out.map { it.toString() }
    }

    private val urlish = Regex("""(https?://|www\.|\.com|\.ly|t\.me|@\w+|\?\w+=)""", RegexOption.IGNORE_CASE)

    private val bankWords = setOf(
        "صحاري", "امان", "تجاري", "اسلامي", "سراي", "يقين", "اندلس", "وحده", "نوران", "جمهوريه", "متحد",
        "وحدهمصرف", "تجاريوطني", "ايفاء", "دينيز", "ناب", "يساومو", "واحه", "تنميه", "تجاره",
    )

    /** Parse one post text into price lines (empty list for non-price posts). */
    fun parse(text: String?): List<ParsedLine> {
        if (text.isNullOrBlank()) return emptyList()
        val t = text.replace('＝', '=')
        val segs = glue(segmentize(t))
        val out = ArrayList<ParsedLine>()
        val seen = HashSet<String>()
        for (seg in segs) {
            if ('=' !in seg) continue
            if (urlish.containsMatchIn(seg)) continue
            val label = seg.substringBefore('=').trim()
            val rest = seg.substringAfter('=').trim()
            if (label.isEmpty() || rest.isEmpty()) continue
            // "7:44" / "10:43": a colon typed instead of the decimal point ("=7:44" was read as 7 before)
            val restN = Arabic.digitsToAscii(rest).replace(colonDecimalRe, "$1$2.$3")
            val m = numberRe.find(restN) ?: continue
            val after = restN.substring(m.range.last + 1)
            if (rangeAfterRe.containsMatchIn(after)) continue            // "852~862": a range, not a single price
            val value = parseNumber(m.groupValues[1]) ?: continue
            val prefixDollar = restN.trimStart().startsWith("$")
            val unitRaw = after.trim()
            val unit = normalizeUnit(prefixDollar, unitRaw)
            val line = classify(label, value, unit) ?: continue
            if (seen.add(line.key)) out.add(line)
        }
        for (line in parseLegacy(t)) if (!Catalog.isOtherKey(line.key) && seen.add(line.key)) out.add(line)
        return out
    }

    // ---------------------------------------------------------------- legacy (2020-2023) formats without "="

    private val legacyNum = "(\\d{1,6}(?:\\.\\d+)?)"
    private val labelOnlyRe = Regex("^([\\p{L}][\\p{L} ]{1,30}?)\\s*:?\\s*$")
    private val singleValueRe = Regex("^ب?\\s*$legacyNum\\s*(?:دينار|LYD|د\\.?\\s?ل)?\\s*[،,.]*$")
    private val sellRe = Regex("^ب?\\s*$legacyNum\\s*(بيع|شراء)?\\s*(?:في\\s*(.*))?$")
    private val sameLineRe = Regex("^([\\p{L}][\\p{L} ]*?)\\s*(?:(\\d{2})(?=\\s*ب))?\\s*(?:(?<=[\\s\\d])ب)?\\s*$legacyNum\\s*(?:دينار|LYD)\\s*[،,.]*\\s*(?:عيار\\s*(\\d{2}))?\\s*$")
    private val karatContRe = Regex("^(\\d{2})\\s*ب\\s*$legacyNum\\s*دينار\\s*[،,.]*$")

    private fun parseLegacy(text: String): List<ParsedLine> {
        val lines = segmentize(text).flatMap { it.split('\n') }.map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<ParsedLine>()
        var lastWasKasr = false
        var i = 0
        while (i < lines.size) {
            val ln = lines[i]
            val lnN = Arabic.digitsToAscii(ln)
            val cont = karatContRe.matchEntire(lnN)
            if (cont != null && lastWasKasr) {
                classify("كسر ذهب عيار " + cont.groupValues[1], cont.groupValues[2].toDouble(), "دينار")?.let(out::add)
                i++; continue
            }
            val same = sameLineRe.matchEntire(lnN)
            if (same != null && '=' !in lnN) {
                var label = same.groupValues[1].trim()
                val karat = same.groupValues[2].ifEmpty { same.groupValues[4] }
                val v = same.groupValues[3].toDouble()
                lastWasKasr = Arabic.compact(label).let { it.startsWith("كسر") }
                if (karat.isNotEmpty()) label = "$label عيار $karat"
                classify(label, v, "دينار")?.let(out::add)
                i++; continue
            }
            val lab = labelOnlyRe.matchEntire(lnN)
            if (lab != null && i + 1 < lines.size) {
                val label = lab.groupValues[1].trim()
                lastWasKasr = false
                val next = Arabic.digitsToAscii(lines[i + 1])
                val sv = singleValueRe.matchEntire(next)
                if (sv != null && !next.contains('~')) {
                    val unitIsUsd = lines[i + 1].contains("دولار")
                    classify(label, sv.groupValues[1].toDouble(), if (unitIsUsd) "$" else "دينار")?.let(out::add)
                    i += 2; continue
                }
                // buy/sell blocks: "الدولار :" / "ب5.665 بيع في طرابلس" / "ب5.660 شراء"
                var j = i + 1
                var tripoliSell: Double? = null
                var firstSell: Double? = null
                while (j < lines.size) {
                    val m = sellRe.matchEntire(Arabic.digitsToAscii(lines[j])) ?: break
                    val place = Arabic.normalize(m.groupValues[3])
                    if (m.groupValues[2] == "بيع") {
                        val v = m.groupValues[1].toDouble()
                        if (firstSell == null) firstSell = v
                        if (tripoliSell == null && place.contains("طرابلس")) tripoliSell = v
                    }
                    j++
                }
                if (j > i + 1) {
                    (tripoliSell ?: firstSell)?.let { v -> classify(label, v, "دينار")?.let(out::add) }
                    i = j; continue
                }
            }
            lastWasKasr = false
            i++
        }
        return out
    }

    private fun normalizeUnit(prefixDollar: Boolean, unitRaw: String): String {
        if (prefixDollar || unitRaw.startsWith("$")) return "$"
        if (unitRaw.startsWith("%")) return "%"
        val u = Arabic.normalize(unitRaw).replace(Regex("(?<=\\p{L})\\.(?=\\p{L})"), " ").replace('.', ' ').trim()
        val c = Arabic.compact(u)
        return when {
            c.isEmpty() -> ""
            c == "دينار" || c == "دينارليبي" || c == "د" || c == "دل" || c == "دينارل" -> "دينار"
            c == "دولار" || c == "دولارا" -> "$"
            else -> cleanLabel(u).take(20)
        }
    }

    // ---------------------------------------------------------------- classification

    private val chkCardsRe = Regex("^نس[بي]هسحببطاقات(?:امان|دولار)?\$")
    private val karatRe = Regex("^(.*?)عيار(\\d{2,3})$")

    private fun cs(vararg labels: String): Set<String> = labels.map { Arabic.compact(it) }.toSet()

    private val usdSet = cs("دولار", "الدولار", "دولار أمريكي")
    private val eurSet = cs("يورو", "اليورو")
    private val gbpSet = cs("باوند", "الباوند", "الجنيه الاسترليني", "جنيه استرليني")
    private val chkUsd = cs("دولار بشيك", "الدولار بشيك", "الصك", "صك", "دولار بالصك", "الدولار بالصك", "الدولار بالصكوك", "دولار بالصكوك", "دولار صك", "الدولار صك")
    private val chkUsdTr = cs(
        "الدولار-تركيا", "دولار تركيا", "دولار حوالة تركيا", "دولار حواله تركيا", "الدولار-حوالة تركيا", "الدولار حوالة تركيا",
        "دولار بشيك تركيا", "دولار بشيك حوالة تركيا", "دولار بشيك حواله تركيا", "بشيك تركيا", "حوالة تركيا", "حواله تركيا",
        "الدولار بشيك تركيا", "الدولار بشيك حوالة تركيا", "دولار-تركيا", "دولار تركية", "دولار حوالةتركيا",
    )
    private val chkEurTr = cs("اليورو-تركيا", "يورو تركيا", "يورو حوالة تركيا", "يورو حواله تركيا", "اليورو حوالة تركيا", "يورو بشيك تركيا", "يورو بشيك حوالة تركيا", "اليورو بشيك تركيا")

    private val goldKasr = cs("كسر الذهب", "كسر ذهب", "الذهب الكسر", "ذهب الكسر", "كسر دهب", "كسر الدهب", "الكسر", "كسر")
    private val goldNew = cs("الذهب الجديد", "ذهب جديد")
    private val goldUsed = cs("الذهب المستعمل", "ذهب مستعمل")
    private val goldCast = cs("الذهب المسبوك", "مسبوك الذهب", "ذهب مسبوك", "مسبوك ذهب")

    private val silverTurk = cs("فضة نادر تركية", "فضة تركية نادر", "فضة نادر التركية", "فضة نادر تركيا")
    private val silverTurkChk = cs(
        "فضة نادر تركية-صك", "فضة نادر تركية بالصك", "فضة نادر تركية بالصكوك", "فضة نادر تركية-صكوك",
        "فضة تركية نادر بالصك", "فضة نادر التركية بالصك", "فضة نادر تركية صك",
    )

    private val wGold = cs("أونصة الذهب عالمياً", "اونصة الذهب", "أونصة الذهب", "الذهب", "Gold", "الذهب العالمي")
    private val wSilver = cs("أونصة الفضة عالمياً", "اونصة الفضة", "أونصة الفضة", "الفضة", "Silver", "الفضة العالمية")
    private val wBrent = cs("نفط برنت", "Brent", "برنت", "خام برنت", "النفط برنت")
    private val wWti = cs("WTI", "النفط الخام", "نفط خام", "Crude Oil", "نفط WTI", "خام WTI", "النفط الخام الأمريكي")

    private fun classify(rawLabel: String, value: Double, unit: String): ParsedLine? {
        val c = Arabic.compact(rawLabel)
        if (c.isEmpty() || c.all { it.isDigit() }) return null
        val isUsdUnit = unit == "$"
        val isPercent = unit == "%"
        var key: String? = null

        fun mapped(k: String, v: Double = value): ParsedLine? {
            val d = Catalog.byKey.getValue(k)
            if (v < d.min || v > d.max) return null
            return ParsedLine(k, d.title, v, d.unit)
        }

        // Tunisian dinar / Egyptian pound against the Libyan dinar
        fx(c, unit, value)?.let { (k, v) -> return mapped(k, Math.round(v * 10000.0) / 10000.0) }

        when {
            c in usdSet -> key = "cur_usd"
            c in eurSet -> key = "cur_eur"
            c in gbpSet -> key = "cur_gbp"
            c in chkUsd -> key = "chk_usd"
            c in chkUsdTr -> key = "chk_usd_tr"
            c in chkEurTr -> key = "chk_eur_tr"
            chkCardsRe.matches(c) && !isPercent && value >= 300 -> key = "chk_cards"
            c in silverTurkChk -> key = "silver_turk_chk"
            c in silverTurk -> key = "silver_turk"
        }
        if (key == null) {
            val km = karatRe.matchEntire(c)
            if (km != null) {
                val base = km.groupValues[1]
                val karat = km.groupValues[2].toIntOrNull()
                key = when {
                    base in goldKasr && karat == 18 -> "gold_k18"
                    base in goldKasr && karat == 21 -> "gold_k21"
                    base in goldNew && karat == 18 -> "gold_new"
                    base in goldUsed && karat == 18 -> "gold_used"
                    base in goldCast && karat == 18 -> "gold_cast"
                    else -> null
                }
            } else {
                // karat-less forms
                key = when (c) {
                    in goldNew -> "gold_new"
                    in goldUsed -> "gold_used"
                    in goldCast -> "gold_cast"
                    else -> null
                }
            }
        }
        if (key == null && cryptoWords.any { c.contains(Arabic.compact(it)) }) return null
        // World market quotes now come from the market API only: such lines are ignored (neither mapped nor "other")
        if (key == null && (isUsdUnit || unit.isEmpty()) && (c in wGold || c in wSilver || c in wBrent || c in wWti)) return null
        if (key != null) {
            // a mapped label with an implausible value (or wrong unit) is dropped rather than shown as "other"
            return mapped(key)
        }
        // ---- "other" items: keep the original (cleaned) label
        if (c in bankWords || c.length < 2) return null
        if (!c.any { it in 'ء'..'ي' || it in 'a'..'z' }) return null
        if (value <= 0.0) return null
        val label = cleanLabel(rawLabel)
        if (label.isEmpty()) return null
        val impliedLyd = unit.isEmpty() && value in 2.0..40.0 &&
            listOf("دولار", "يورو", "صك", "حوال", "باوند", "بشيك").any { Arabic.normalize(rawLabel).contains(it) }
        return ParsedLine("o_" + fnv(c), label, value, if (impliedLyd) "دينار" else unit)
    }

    private val invRe = Regex("^(\\d{1,4})?(دينارتونسي|جنيهمصري|جنيه)$")
    /** Crypto now comes live from the exchanges: Telegram crypto lines are ignored. */
    private val cryptoWords = listOf("بيتكوين", "بتكوين", "بيتكون", "bitcoin", "btc", "ايثريوم", "ايثيريوم", "ethereum", "eth", "usdt", "تيثر")
    private val lydLabelRe = Regex("^(\\d{1,4})?(دينار|دينارليبي|ليبي)$")

    /** "دينار.ليبي=0.375دينار.تونسي", "1000دينار.ليبي=470دينار.تونسي", "الدينار=6.85جنيه.مصري", "الدينار التونسي= 1.86 دينار". */
    private fun fx(c: String, unit: String, value: Double): Pair<String, Double>? {
        if (value <= 0.0) return null
        val u = Arabic.compact(unit)
        val m = lydLabelRe.matchEntire(c)
        if (m != null) {
            val n = m.groupValues[1].toIntOrNull() ?: 1
            if (n <= 0) return null
            return when {
                u.contains("تونسي") -> "cur_tnd" to value / n
                u.contains("جنيه") && !u.contains("استرليني") -> "cur_egp" to value / n
                else -> null
            }
        }
        // inverse quotes: "الدينار التونسي = 2.73 دينار" / "100دينار.تونسي= 273 دينار.ليبي"
        val inv = invRe.matchEntire(c)
        if (inv != null && (u.isEmpty() || u.startsWith("دينار")) && !u.contains("تونسي")) {
            val n = inv.groupValues[1].toIntOrNull() ?: 1
            if (n <= 0) return null
            val per = value / n
            if (inv.groupValues[2] == "دينارتونسي" && per in 1.0..10.0) return "cur_tnd" to 1.0 / per
            if (inv.groupValues[2] != "دينارتونسي" && per in 0.02..1.0) return "cur_egp" to 1.0 / per
        }
        return null
    }

    /**
     * True when a cached / shared "other" item would no longer be produced as an "other" item by the current
     * parser (e.g. it is now a first-class item such as the Tunisian dinar, or a world quote that is ignored).
     */
    fun isObsoleteOther(key: String, label: String, value: Double, unit: String): Boolean {
        if (!Catalog.isOtherKey(key)) return false
        val line = classify(label, value, unit)
        return line == null || !Catalog.isOtherKey(line.key)
    }

    fun cleanLabel(raw: String): String {
        var s = raw.replace('_', ' ').replace(Regex("""(?<=[\p{L}])\.(?=[\p{L}])"""), " ")
        s = Arabic.digitsToAscii(s).replace(Regex("[\\u064B-\\u065F\\u0640\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), "")
        s = s.replace(Regex("^[\\s\\-*•#:|]+|[\\s\\-*•#:|]+$"), "").replace(Regex("\\s+"), " ").trim()
        return s.take(48)
    }

    private fun fnv(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (ch in s) { h = h xor ch.code; h *= 16777619 }
        return Integer.toHexString(h).padStart(8, '0')
    }
}
