package tools

import com.djphoenix.moasherlibya.parser.Catalog
import com.djphoenix.moasherlibya.parser.ItemState
import com.djphoenix.moasherlibya.parser.PostParser
import com.djphoenix.moasherlibya.parser.PriceEngine
import com.djphoenix.moasherlibya.parser.RawPost
import com.djphoenix.moasherlibya.parser.TelegramHtml
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Server-side "shared board" crawler (phase 3). Same parser and "latest price wins" engine as the Android app:
 *  1. read the current board latest/{key} (public REST read),
 *  2. read the newest posts of every source channel (web preview, up to [PAGES] pages back; list from SOURCES_JSON),
 *  3. merge and write the planned Firestore writes (latest/{key} replacements + new ticks/{key}_{t}) to a JSON file.
 * The privileged write happens in server/publish.py with a service account; this program needs no secrets.
 * Usage: gradle -p crawler run -PmainClass=tools.BoardCrawlerKt --args=/tmp/board_writes.json
 */
private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
private const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
private val BOARD_CHANNELS get() = Src.s.channels      // from the SOURCES_JSON secret
private const val PAGES = 3
private const val FS = "https://firestore.googleapis.com/v1/projects/moasher-libya/databases/(default)/documents"

private fun get(url: String, hdr: Map<String, String> = emptyMap()): String? = runCatching {
    val b = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "ar")
    hdr.forEach { (k, v) -> b.header(k, v) }
    http.newCall(b.build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
}.getOrNull()

private fun num(f: JsonObject?, k: String): Double? = f?.get(k)?.jsonObject?.let {
    it["doubleValue"]?.jsonPrimitive?.doubleOrNull ?: it["integerValue"]?.jsonPrimitive?.content?.toDoubleOrNull()
}
private fun str(f: JsonObject?, k: String): String? = f?.get(k)?.jsonObject?.get("stringValue")?.jsonPrimitive?.content

fun readBoard(): Map<String, ItemState>? {
    val key = System.getenv("WEB_API_KEY") ?: "AIzaSyCPbN6cKjCpscYPRmdX31nXMhxo5O9zDK4"
    val body = get("$FS/latest?pageSize=300&key=$key", mapOf("Referer" to "https://moasher-libya.web.app/")) ?: return null
    val docs = Json.parseToJsonElement(body).jsonObject["documents"]?.jsonArray ?: return emptyMap()
    return docs.mapNotNull { d ->
        val o = d.jsonObject; val k = o["name"]!!.jsonPrimitive.content.substringAfterLast('/'); val f = o["fields"]?.jsonObject
        val v = num(f, "v") ?: return@mapNotNull null; val t = num(f, "t") ?: return@mapNotNull null
        k to ItemState(k, str(f, "lbl") ?: k, str(f, "u") ?: "", v, t.toLong() * 1000, num(f, "pv"), num(f, "pt")?.toLong()?.times(1000))
    }.toMap()
}

fun fetchChannel(ch: String): List<RawPost>? {
    val first = get(Src.channelUrl(ch))?.let { TelegramHtml.parsePage(ch, it) } ?: return null
    val all = ArrayList(first); var oldest = first.minOfOrNull { it.id }; var n = 1
    while (n < PAGES && oldest != null && oldest > 1) {
        val p = get(Src.channelUrl(ch) + "?before=$oldest")?.let { TelegramHtml.parsePage(ch, it) } ?: break
        if (p.isEmpty()) break; all.addAll(p); oldest = p.minOf { it.id }; n++
    }
    return all
}

fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "/tmp/board_writes.json" })
    val board = readBoard() ?: run { System.err.println("board read failed"); kotlin.system.exitProcess(2) }
    val posts = ArrayList<RawPost>(); var ok = 0
    for (ch in BOARD_CHANNELS) fetchChannel(ch)?.let { posts.addAll(it); ok++; println("channel ok: ${it.size} posts") } ?: println("channel failed")
    if (ok == 0) { System.err.println("no channel could be read"); kotlin.system.exitProcess(3) }
    val sane = { m: Map<String, ItemState> -> m.filter { (k, s) -> !Catalog.isLiveKey(k) && !PostParser.isObsoleteOther(k, s.label, s.value, s.unit) } }
    val merged = PriceEngine.merge(sane(board), posts)
    val states = sane(merged.states)
    val otherCutoff = System.currentTimeMillis() - 7 * 86_400_000L   // "other" items live 7 days (as in the app)
    val newer = states.values.filter { s -> merged.changedKeys.contains(s.key) && (board[s.key]?.let { s.t > it.t } ?: true)
        && (!Catalog.isOtherKey(s.key) || s.t > otherCutoff) }
    val ticks = merged.ticks.filter { tk -> !Catalog.isLiveKey(tk.key) && states.containsKey(tk.key) && (board[tk.key]?.let { tk.t > it.t } ?: true) }
        .filter { tk -> !Catalog.isOtherKey(tk.key) || tk.t > otherCutoff }
        .distinctBy { it.key to it.t / 1000 }
    val doc = buildJsonObject {
        putJsonObject("latest") {
            for (s in newer) putJsonObject(s.key) {
                put("v", s.value); put("t", s.t / 1000); put("lbl", s.label.take(60)); put("u", s.unit.take(24))
                s.pv?.let { put("pv", it) }; s.pt?.let { put("pt", it / 1000) }
            }
        }
        putJsonArray("ticks") { for (tk in ticks) addJsonObject { put("k", tk.key); put("t", tk.t / 1000); put("v", tk.v) } }
        put("boardSize", board.size); put("posts", posts.size); put("channelsOk", ok)
    }
    out.writeText(doc.toString())
    println("planned writes: latest=${newer.size} ticks=${ticks.size} (board ${board.size} items, ${posts.size} posts)")
}
