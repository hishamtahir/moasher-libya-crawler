package com.djphoenix.moasherlibya.parser

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneId

/** One row of the central bank's exchange-rate table: LYD per [qty] units of the currency. */
@Serializable
data class OfficialRate(
    val key: String,
    val name: String,       // Arabic name as published
    val qty: Int,           // units per quote (1, 10, 100)
    val avg: Double,        // average rate (the value shown in the app)
    val sell: Double?,
    val buy: Double?,
    val day: Long,          // publication date, local midnight (Africa/Tripoli) in epoch millis
)

/**
 * Parser of the official exchange-rate page. Primary path: `table#currency-table` (date, currency, unit, average,
 * sell, buy). Fallbacks: any table whose header mentions the currency and the average, then a plain-text scan of
 * rows. Column order is taken from the header when present so a re-ordered table still parses.
 */
object OfficialParser {
    val ZONE: ZoneId = ZoneId.of("Africa/Tripoli")

    private val dateRe = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val numRe = Regex("""\d+(?:[.,]\d+)?""")

    /** Known currencies by a distinctive word of their Arabic (compacted) name. Order matters (first match wins). */
    private val keyWords: List<Pair<String, String>> = listOf(
        "امريكي" to "cbl_usd", "يورو" to "cbl_eur", "استرليني" to "cbl_gbp", "كندي" to "cbl_cad", "استرالي" to "cbl_aud",
        "سويسري" to "cbl_chf", "سويدي" to "cbl_sek", "نرويجي" to "cbl_nok", "دنمركي" to "cbl_dkk", "دانمركي" to "cbl_dkk",
        "ياباني" to "cbl_jpy", "سعودي" to "cbl_sar", "امارات" to "cbl_aed", "تونسي" to "cbl_tnd", "جزائري" to "cbl_dzd",
        "مغربي" to "cbl_mad", "موريتاني" to "cbl_mru", "اوقيه" to "cbl_mru", "افريقي" to "cbl_xof", "روسي" to "cbl_rub",
        "روبل" to "cbl_rub", "تركي" to "cbl_try", "ليره" to "cbl_try", "صيني" to "cbl_cny", "يوان" to "cbl_cny", "فرنك" to "cbl_xof",
    ).map { (w, k) -> Arabic.compact(w) to k }

    fun keyFor(name: String): String {
        val c = Arabic.compact(name)
        keyWords.firstOrNull { c.contains(it.first) }?.let { return it.second }
        // unknown currency: stable dynamic key
        var h = 0x811c9dc5.toInt()
        for (ch in c) { h = h xor ch.code; h *= 16777619 }
        return "cbl_h" + Integer.toHexString(h).padStart(8, '0')
    }

    /** "دولار واحد" -> 1, "100 ين" -> 100, "10 دينار" -> 10, "فرنـك" -> 1. */
    fun qtyOf(unit: String): Int {
        val n = Regex("""\d+""").find(Arabic.digitsToAscii(unit))?.value?.toIntOrNull()
        return if (n != null && n in 1..100000) n else 1
    }

    fun dayMillis(s: String): Long? {
        val m = dateRe.find(Arabic.digitsToAscii(s)) ?: return null
        return runCatching {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()).atStartOfDay(ZONE).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun num(s: String): Double? {
        val t = Arabic.digitsToAscii(s).replace('٫', '.')
        return numRe.find(t)?.value?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
    }

    /** Text of a cell without the responsive "label: " prefix span. */
    private fun cell(td: Element): String {
        val c = td.clone()
        c.select("span[class*=hidden]").remove()
        var t = c.text().replace('\u00a0', ' ').replace("\u0640", "").trim()
        // plain-text fallback: drop a leading "label:" when the span was not marked
        t = t.replace(Regex("^(التاريخ|العملة|الوحدة|المتوسط|بيع|شراء|Date|Currency|Unit|Average|Sell|Buy)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        return t
    }

    private data class Cols(val date: Int, val name: Int, val unit: Int, val avg: Int, val sell: Int, val buy: Int)

    private fun columns(headers: List<String>): Cols? {
        if (headers.isEmpty()) return null
        fun idx(vararg ws: String) = headers.indexOfFirst { h -> ws.any { h.contains(it, ignoreCase = true) } }
        val c = Cols(idx("التاريخ", "date"), idx("العملة", "currency"), idx("الوحدة", "unit"), idx("المتوسط", "average", "mid"), idx("بيع", "sell"), idx("شراء", "buy"))
        return if (c.name >= 0 && (c.avg >= 0 || (c.sell >= 0 && c.buy >= 0))) c else null
    }

    /** All rows of the page (several dates when a date range was requested). */
    fun parseAll(html: String): List<OfficialRate> {
        val doc = Jsoup.parse(html)
        val tables = doc.select("table#currency-table").ifEmpty {
            doc.select("table").filter { t -> val h = t.select("th").text(); h.contains("العملة") || h.contains("Currency", true) }
        }
        val out = ArrayList<OfficialRate>()
        for (t in tables) {
            val cols = columns(t.select("thead th, tr:first-child th").map { it.text().trim() }) ?: Cols(0, 1, 2, 3, 4, 5)
            for (tr in t.select("tbody tr").ifEmpty { t.select("tr") }) {
                val tds = tr.select("td")
                if (tds.size < 3) continue
                fun at(i: Int) = if (i in tds.indices) cell(tds[i]) else ""
                val name = at(cols.name)
                if (name.isEmpty()) continue
                val sell = num(at(cols.sell)); val buy = num(at(cols.buy))
                val avg = num(at(cols.avg)) ?: (if (sell != null && buy != null) (sell + buy) / 2 else null) ?: continue
                val day = dayMillis(at(cols.date)) ?: dayMillis(tr.text()) ?: continue
                out.add(OfficialRate(keyFor(name), name, if (cols.unit >= 0) qtyOf(at(cols.unit)) else 1, avg, sell, buy, day))
            }
        }
        return out
    }

    /** The newest publication of each currency (page order kept). */
    fun parseLatest(html: String): List<OfficialRate> {
        val all = parseAll(html)
        if (all.isEmpty()) return all
        val latest = LinkedHashMap<String, OfficialRate>()
        for (r in all) { val p = latest[r.key]; if (p == null || r.day > p.day) latest[r.key] = r }
        return latest.values.toList()
    }

    /** Per currency: (day, average) series, ascending, one point per day. */
    fun series(rows: List<OfficialRate>): Map<String, List<Pair<Long, Double>>> =
        rows.groupBy { it.key }.mapValues { (_, l) -> l.associate { it.day to it.avg }.toSortedMap().map { it.key to it.value } }
}
