package tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Data sources are NOT in this repository. They come at runtime from the `SOURCES_JSON` Actions secret
 * (masked in logs by the workflow). Nothing here may print a source name or URL.
 */
@Serializable
data class Sources(
    val channels: List<String> = emptyList(),      // board channels (web preview pages)
    val channelUrl: String = "",                    // template with {ch}
    val materials: String = "",
    val official: List<String> = emptyList(),
    val chart: String = "",                         // template with {sym} {i} {r}
    val news: String = "",                          // WordPress REST base ending in wp-json/wp/v2/
    val newsScrub: String = "",                     // regex: source names/links removed from news text
    val newsDateline: String = "",                  // regex: dateline/byline forms
)

object Src {
    val s: Sources by lazy {
        System.getenv("SOURCES_JSON")?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(Sources.serializer(), it) }.getOrNull() }
            ?: Sources().also { System.err.println("SOURCES_JSON missing or invalid: nothing will be crawled") }
    }
    fun channelUrl(ch: String) = s.channelUrl.replace("{ch}", ch)
}
