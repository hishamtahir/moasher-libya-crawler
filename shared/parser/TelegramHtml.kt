package com.djphoenix.moasherlibya.parser

import org.jsoup.Jsoup
import java.time.OffsetDateTime

/** A message as published on a channel's public web preview. */
data class RawPost(
    val channel: String,
    val id: Long,
    val tsMillis: Long,
    val text: String?,
)

/** Parses https://t.me/s/<channel> pages (div.tgme_widget_message_text + <time datetime>). */
object TelegramHtml {
    fun parsePage(channel: String, html: String): List<RawPost> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<RawPost>()
        for (m in doc.select("div.tgme_widget_message[data-post]")) {
            val post = m.attr("data-post")
            val id = post.substringAfter('/', "").toLongOrNull() ?: continue
            val dt = m.selectFirst("a.tgme_widget_message_date time[datetime]")?.attr("datetime") ?: continue
            val ts = runCatching { OffsetDateTime.parse(dt).toInstant().toEpochMilli() }.getOrNull() ?: continue
            val textEl = m.selectFirst("div.tgme_widget_message_text")
            val text = textEl?.let { el ->
                el.select("br").after("\\n")
                el.wholeText().replace("\\n", "\n")
            }
            out.add(RawPost(channel, id, ts, text))
        }
        return out
    }

    /** Oldest message id found on the page (used for ?before= pagination), or null. */
    fun minId(html: String): Long? {
        val doc = Jsoup.parse(html)
        return doc.select("div.tgme_widget_message[data-post]")
            .mapNotNull { it.attr("data-post").substringAfter('/', "").toLongOrNull() }
            .minOrNull()
    }
}
