package tools

import com.djphoenix.moasherlibya.content.ContentParsers
import com.djphoenix.moasherlibya.content.NewsDetail
import com.djphoenix.moasherlibya.content.NewsItem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * News (phase 3): reads the news publisher's WordPress REST API (base URL from SOURCES_JSON) with the app's own parser (ContentParsers, same scrubbing of
 * the source name and links) and plans Firestore cache docs:
 *   cache/news        { s: JSON List<NewsItem> (Android shape), n, fp }        only when the list changed
 *   cache/news_{id}   { s: JSON NewsDetail (Android shape), n: blocks, id }   only for articles not cached yet
 * plus deletes of cache/news_{id} for articles that dropped out of the list. Output: {"docs":{...},"deletes":[...]}.
 */
private val WP get() = Src.s.news                    // from the SOURCES_JSON secret
private const val FS = "https://firestore.googleapis.com/v1/projects/moasher-libya/databases/(default)/documents/"
private val http = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
private val diag = ArrayList<String>()
private fun fetch(url: String): String? = runCatching {
    http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36").header("Accept-Language", "ar").build())
        .execute().use { r -> if (!r.isSuccessful && !url.startsWith(FS)) diag.add("HTTP ${r.code}"); if (r.isSuccessful) r.body?.string() else null }
}.onFailure { if (!url.startsWith(FS)) diag.add(it.javaClass.simpleName) }.getOrNull()
private fun bases() = listOf(WP, WP.removeSuffix("wp-json/wp/v2/") + "?rest_route=/wp/v2/")
private fun url(base: String, path: String, q: String) = if (base.contains("rest_route")) "$base$path&$q" else "$base$path?$q"

fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "/tmp/news_seed.json" })
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    val listSer = ListSerializer(NewsItem.serializer())
    // previous list (public read) -> which articles already have a detail doc
    val prev: List<NewsItem> = fetch(FS + "cache/news")?.let { b ->
        runCatching { json.decodeFromString(listSer, Json.parseToJsonElement(b).jsonObject["fields"]!!.jsonObject["s"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content) }.getOrNull()
    }.orEmpty()
    val prevFp = fetch(FS + "cache/news")?.let { b -> runCatching { Json.parseToJsonElement(b).jsonObject["fields"]!!.jsonObject["fp"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content }.getOrNull() }

    if (WP.isEmpty()) { out.writeText("""{"docs":{},"deletes":[]}"""); println("news: no source configured"); return }
    var items: List<NewsItem> = emptyList(); var base = WP
    for (b in bases()) {
        val body = fetch(url(b, "posts", "per_page=30&_fields=id,date_gmt,title,excerpt,featured_media")) ?: continue
        items = ContentParsers.newsList(body); if (items.isEmpty()) continue
        base = b
        val ids = items.map { it.mediaId }.filter { it > 0 }.distinct()
        if (ids.isNotEmpty()) fetch(url(b, "media", "include=${ids.joinToString(",")}&per_page=${ids.size}&_fields=id,source_url,media_details.sizes"))?.let { m ->
            val urls = ContentParsers.mediaUrls(m).filterValues { it.startsWith("https://") }
            items = items.map { it.copy(image = urls[it.mediaId] ?: "") }
        }
        break
    }
    if (items.isEmpty()) { out.writeText("""{"docs":{},"deletes":[]}"""); println("news: source unavailable, nothing planned ${diag.take(4)}"); return }

    val fp = items.joinToString("|") { "${it.id}:${it.t}:${it.title.hashCode()}:${it.image.hashCode()}" }.hashCode().toString(16)
    val have = prev.map { it.id }.toSet()
    val now = items.map { it.id }.toSet()
    var details = 0
    val docs = buildJsonObject {
        if (fp != prevFp) put("cache/news", buildJsonObject { put("s", json.encodeToString(listSer, items)); put("n", items.size); put("fp", fp) })
        for (it in items.filter { it.id !in have }.take(30)) {
            val body = fetch(url(base, "posts/${it.id}", "_fields=id,date_gmt,title,content")) ?: continue
            val d: NewsDetail = ContentParsers.newsDetail(body, it.image) ?: continue
            val s = json.encodeToString(NewsDetail.serializer(), d)
            if (s.length > 700_000) continue                       // stay far below the 1 MiB doc limit
            put("cache/news_${it.id}", buildJsonObject { put("s", s); put("n", d.blocks.size); put("id", it.id) })
            details++
        }
    }
    val deletes = (have - now).map { "cache/news_$it" }
    out.writeText(buildJsonObject { put("docs", docs); putJsonArray("deletes") { deletes.forEach { add(it) } } }.toString())
    println("news: ${items.size} items, list ${if (fp != prevFp) "changed" else "unchanged"}, new details $details, deletes ${deletes.size}")
}
