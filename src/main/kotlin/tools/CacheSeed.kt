package tools

import com.djphoenix.moasherlibya.content.ContentParsers
import com.djphoenix.moasherlibya.content.MaterialGroup
import com.djphoenix.moasherlibya.market.CandleCodec
import com.djphoenix.moasherlibya.market.MarketJson
import com.djphoenix.moasherlibya.parser.OfficialParser
import com.djphoenix.moasherlibya.parser.OfficialRate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable data class MatSnap(val groups: List<MaterialGroup>, val updatedAt: Long, val checkedAt: Long, val fp: String)
@Serializable data class OffSnap(val rates: List<OfficialRate>, val fetchedAt: Long, val prev: Map<String, Double> = emptyMap(), val prevDay: Map<String, Long> = emptyMap())

private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
private fun get(url: String, lang: String = "ar"): String? = runCatching {
    http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36").header("Accept-Language", lang).build())
        .execute().use { if (it.isSuccessful) it.body?.string() else null }
}.getOrNull()

/** Builds the same cache documents the Android app publishes (cache/materials, cache/cbl, mkt/w_*) into a JSON file. */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "/tmp/cache_seed.json" })
    val json = Json { encodeDefaults = true }
    val docs = buildJsonObject {
        val now = System.currentTimeMillis()
        Src.s.materials.takeIf { it.isNotEmpty() }?.let { get(it) }?.let { html ->
            val g = ContentParsers.materials(html)
            if (g.isNotEmpty()) put("cache/materials", buildJsonObject {
                put("s", json.encodeToString(MatSnap.serializer(), MatSnap(g, now, now, ContentParsers.fingerprint(g))))
                put("n", g.sumOf { it.items.size }); put("fp", ContentParsers.fingerprint(g).take(64))
            })
        }
        for (u in Src.s.official) {
            val r = get(u, "ar,en;q=0.8")?.let { OfficialParser.parseLatest(it) }.orEmpty()
            if (r.isNotEmpty()) {
                put("cache/cbl", buildJsonObject {
                    put("s", json.encodeToString(OffSnap.serializer(), OffSnap(r, now)))
                    put("n", r.size); put("day", r.maxOf { it.day } / 1000)
                })
                break
            }
        }
        val syms = linkedMapOf("w_gold" to "GC%3DF", "w_silver" to "SI%3DF", "w_brent" to "BZ%3DF", "w_wti" to "CL%3DF")
        val frames = listOf("h" to ("60m" to "3mo"), "d" to ("1d" to "2y"), "w" to ("1wk" to "10y"), "m" to ("1mo" to "max"))
        for ((k, s) in syms) for ((f, ir) in frames) {
            if (Src.s.chart.isEmpty()) continue
            val body = get(Src.s.chart.replace("{sym}", s).replace("{i}", ir.first).replace("{r}", ir.second)) ?: continue
            val c = MarketJson.yahooChart(k, body)?.candles?.takeLast(2500).orEmpty()
            if (c.isEmpty()) continue
            put("mkt/${k}_$f", buildJsonObject { put("s", CandleCodec.encode(c)); put("n", c.size); put("c", c.last().c) })
        }
    }
    out.writeText(docs.toString())
    println("docs: " + docs.keys)
}
