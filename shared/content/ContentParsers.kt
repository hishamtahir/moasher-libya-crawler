package com.djphoenix.moasherlibya.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDateTime
import java.time.ZoneOffset

@Serializable
data class NewsItem(val id: Long, val t: Long, val title: String, val excerpt: String, val mediaId: Long = 0, val image: String = "")

@Serializable
data class NewsBlock(val kind: Int, val text: String) { companion object { const val P = 0; const val H = 1; const val QUOTE = 2; const val IMG = 3 } }

@Serializable
data class NewsDetail(val id: Long, val t: Long, val title: String, val image: String, val blocks: List<NewsBlock>)

@Serializable
data class Material(val name: String, val unit: String, val price: Double, val priceText: String)

@Serializable
data class MaterialGroup(val title: String, val items: List<Material>)

/** Pure parsers for the news (WordPress REST JSON) and building-materials (HTML table) sources. */
object ContentParsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Mentions of the publisher are removed so that no source is shown in the app. */
    // server copy: the source-specific patterns come from the SOURCES_JSON secret (tools/sync_shared.sh applies this patch)
    private val sourceRe = Regex(tools.Src.s.newsScrub.ifEmpty { "(?i)(https?://\\S+|www\\.\\S+)" })
    private val datelineRe = Regex(tools.Src.s.newsDateline.ifEmpty { "(?!)" })
    private fun scrub(s: String): String = s.replace(datelineRe, " ").replace(sourceRe, "").replace(Regex("\\s{2,}"), " ").trim().trim('-', '–', '|', '،', ':').trim()

    private fun text(html: String): String = Jsoup.parse(html).text()

    fun gmtMillis(s: String?): Long = runCatching { LocalDateTime.parse(s!!).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0L)

    fun newsList(body: String): List<NewsItem> = runCatching {
        json.parseToJsonElement(body).jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val title = scrub(text(o["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: ""))
            if (title.isEmpty()) return@mapNotNull null
            val excerpt = scrub(text(o["excerpt"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: "")).replace(Regex("\\s*\\[?(…|\\.\\.\\.)\\]?$"), "…")
            NewsItem(
                id = id,
                t = gmtMillis(o["date_gmt"]?.jsonPrimitive?.contentOrNull),
                title = title,
                excerpt = excerpt.take(220),
                mediaId = o["featured_media"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
    }.getOrDefault(emptyList())

    /** `/wp/v2/media?include=...` -> id -> best image url (a medium sized rendition when available). */
    fun mediaUrls(body: String): Map<Long, String> = runCatching {
        json.parseToJsonElement(body).jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val sizes = o["media_details"]?.jsonObject?.get("sizes")?.jsonObject
            val url = listOf("medium_large", "large", "medium").firstNotNullOfOrNull { sizes?.get(it)?.jsonObject?.get("source_url")?.jsonPrimitive?.contentOrNull }
                ?: o["source_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            id to url
        }.toMap()
    }.getOrDefault(emptyMap())

    fun newsDetail(body: String, image: String = ""): NewsDetail? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        val id = o["id"]!!.jsonPrimitive.long
        val title = scrub(text(o["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: ""))
        val html = o["content"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: ""
        NewsDetail(id, gmtMillis(o["date_gmt"]?.jsonPrimitive?.contentOrNull), title, image, blocks(html))
    }.getOrNull()

    /** Clean article body: paragraphs, sub headings, quotes and inline images; scripts, embeds, links and share widgets dropped. */
    fun blocks(html: String): List<NewsBlock> {
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script, style, iframe, noscript, form, figure figcaption, .sharedaddy, .jp-relatedposts, [class*=share], [class*=related]").remove()
        val out = ArrayList<NewsBlock>()
        fun walk(el: Element) {
            for (c in el.children()) {
                when (c.tagName()) {
                    "p", "li" -> {
                        c.select("img").firstOrNull()?.absUrl("src")?.takeIf { it.startsWith("https://") }?.let { out.add(NewsBlock(NewsBlock.IMG, it)) }
                        val t = scrub(c.text())
                        if (t.length > 1) out.add(NewsBlock(NewsBlock.P, t))
                    }
                    "h1", "h2", "h3", "h4", "h5", "h6" -> scrub(c.text()).takeIf { it.isNotEmpty() }?.let { out.add(NewsBlock(NewsBlock.H, it)) }
                    "blockquote" -> scrub(c.text()).takeIf { it.isNotEmpty() }?.let { out.add(NewsBlock(NewsBlock.QUOTE, it)) }
                    "img" -> c.attr("src").takeIf { it.startsWith("https://") }?.let { out.add(NewsBlock(NewsBlock.IMG, it)) }
                    "ul", "ol", "div", "figure", "section", "article", "span" -> walk(c)
                    else -> scrub(c.text()).takeIf { it.length > 1 }?.let { out.add(NewsBlock(NewsBlock.P, it)) }
                }
            }
        }
        walk(doc.body())
        if (out.isEmpty()) scrub(doc.body().text()).takeIf { it.isNotEmpty() }?.let { out.add(NewsBlock(NewsBlock.P, it)) }
        return out.distinct()
    }

    // ------------------------------------------------------------------ building materials

    private val numRe = Regex("""(\d[\d,]*(?:\.\d+)?)""")

    fun materials(html: String): List<MaterialGroup> {
        val doc = Jsoup.parse(html)
        val groups = ArrayList<MaterialGroup>()
        for (sec in doc.select("section.price-cat")) {
            val title = sec.selectFirst(".price-cat-title")?.text()?.trim().orEmpty()
            val items = sec.select("tbody tr").mapNotNull { tr ->
                val name = tr.selectFirst("td.c-name")?.text()?.trim()?.replace(Regex("\\s+"), " ") ?: return@mapNotNull null
                val unit = tr.selectFirst("td.c-unit")?.text()?.trim().orEmpty()
                val priceText = tr.selectFirst("td.c-price")?.text()?.trim().orEmpty()
                val p = numRe.find(com.djphoenix.moasherlibya.parser.Arabic.digitsToAscii(priceText))?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
                    ?: return@mapNotNull null
                Material(name.replace(Regex("ـ+"), ""), unit, p, priceText)
            }
            if (items.isNotEmpty()) groups.add(MaterialGroup(title.ifEmpty { "—" }, items))
        }
        if (groups.isEmpty()) {
            // fallback: any table with name / unit / price columns
            val rows = doc.select("table tr").mapNotNull { tr ->
                val tds = tr.select("td").map { it.text().trim() }
                if (tds.size < 3) return@mapNotNull null
                val priceCell = tds.lastOrNull { numRe.containsMatchIn(it) && it.contains("د") } ?: return@mapNotNull null
                val p = numRe.find(priceCell)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return@mapNotNull null
                val name = tds.firstOrNull { it.any { ch -> ch in 'ء'..'ي' } && it != priceCell } ?: return@mapNotNull null
                Material(name, "", p, priceCell)
            }
            if (rows.isNotEmpty()) groups.add(MaterialGroup("", rows))
        }
        return groups
    }

    /** Stable fingerprint of a materials list, used to detect when the list was updated. */
    fun fingerprint(groups: List<MaterialGroup>): String =
        groups.joinToString("|") { g -> g.title + ":" + g.items.joinToString(",") { "${it.name}=${it.price}/${it.unit}" } }.hashCode().toString(16)
}
