package com.djphoenix.moasherlibya.parser

import kotlinx.serialization.Serializable

/** Current state of one item: the single latest price across all channels, plus the previous *different* value. */
@Serializable
data class ItemState(
    val key: String,
    val label: String,
    val unit: String,
    val value: Double,
    val t: Long,            // post time, epoch millis
    val pv: Double? = null, // previous different value
    val pt: Long? = null,   // time of that previous value
) {
    val section: Section get() = Catalog.byKey[key]?.section ?: if (Catalog.isOfficialKey(key)) Section.OFFICIAL else Section.OTHER
    /** Signed change vs the previous value, or null when unknown. */
    val delta: Double? get() = pv?.let { value - it }
}

/** A value change that should be appended to the item's history. */
data class Tick(val key: String, val label: String, val unit: String, val t: Long, val v: Double)

data class MergeResult(
    val states: Map<String, ItemState>,
    val changedKeys: Set<String>,
    val ticks: List<Tick>,
)

/** Pure "latest price wins" logic shared by the app, the background worker and the history builder. */
object PriceEngine {
    /** Mirror posts of the same value arriving within this window are treated as duplicates. */
    const val DUP_WINDOW_MS = 10 * 60 * 1000L

    fun merge(base: Map<String, ItemState>, posts: List<RawPost>): MergeResult =
        mergeParsed(base, posts.map { it to PostParser.parse(it.text) })

    fun mergeParsed(base: Map<String, ItemState>, parsed: List<Pair<RawPost, List<ParsedLine>>>): MergeResult {
        val states = HashMap(base)
        val changed = LinkedHashSet<String>()
        val ticks = ArrayList<Tick>()
        for ((post, lines) in parsed.sortedWith(compareBy({ it.first.tsMillis }, { it.first.id }))) {
            for (l in lines) {
                val prior = states[l.key]
                if (prior == null) {
                    states[l.key] = ItemState(l.key, l.label, l.unit, l.value, post.tsMillis)
                    changed.add(l.key); ticks.add(Tick(l.key, l.label, l.unit, post.tsMillis, l.value))
                    continue
                }
                if (post.tsMillis <= prior.t) continue
                val sameValue = l.value == prior.value
                if (sameValue && post.tsMillis - prior.t < DUP_WINDOW_MS) continue
                states[l.key] = if (sameValue) prior.copy(t = post.tsMillis)
                else ItemState(l.key, l.label, l.unit, l.value, post.tsMillis, prior.value, prior.t)
                changed.add(l.key)
                if (!sameValue) ticks.add(Tick(l.key, l.label, l.unit, post.tsMillis, l.value))
            }
        }
        return MergeResult(states, changed, ticks)
    }
}

/** Compact text encoding of a price series, stored in one Firestore string field. */
object HistCodec {
    /** "base;dt:v,dt:v,..." - base and dt in epoch seconds. */
    fun encode(points: List<Pair<Long, Double>>): String {
        if (points.isEmpty()) return ""
        val base = points[0].first / 1000
        val sb = StringBuilder().append(base).append(';')
        var prev = base
        points.forEachIndexed { i, (t, v) ->
            val s = t / 1000
            if (i > 0) sb.append(',')
            sb.append(s - prev).append(':').append(fmt(v))
            prev = s
        }
        return sb.toString()
    }

    fun decode(s: String): List<Pair<Long, Double>> {
        if (s.isBlank()) return emptyList()
        val base = s.substringBefore(';').toLongOrNull() ?: return emptyList()
        var cur = base
        val out = ArrayList<Pair<Long, Double>>()
        for (tok in s.substringAfter(';').split(',')) {
            val dt = tok.substringBefore(':').toLongOrNull() ?: continue
            val v = tok.substringAfter(':').toDoubleOrNull() ?: continue
            cur += dt
            out.add(cur * 1000 to v)
        }
        return out
    }

    private fun fmt(v: Double): String {
        val r = Math.round(v * 10000.0) / 10000.0   // 4 decimals: official rates such as 0.0108
        return if (r == Math.floor(r) && Math.abs(r) < 1e12) r.toLong().toString() else r.toString()
    }
}
