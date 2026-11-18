package com.djphoenix.moasherlibya.market

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** One OHLC candle; [t] = bucket start (epoch millis). */
data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double) {
    val up: Boolean get() = c >= o
}

/** Chart time frames (candle size). */
enum class Timeframe(val labelAr: String, val labelEn: String, val millis: Long) {
    H1("1س", "1H", 3600_000L),
    H4("4س", "4H", 4 * 3600_000L),
    H12("12س", "12H", 12 * 3600_000L),
    D1("يوم", "1D", 86400_000L),
    W1("أسبوع", "1W", 7 * 86400_000L),
    MN1("شهر", "1M", 30 * 86400_000L),
    MN6("6 أشهر", "6M", 182 * 86400_000L),
    Y1("سنة", "1Y", 365 * 86400_000L);

    /** Number of calendar months per candle for month based frames, 0 otherwise. */
    val months: Int get() = when (this) { MN1 -> 1; MN6 -> 6; Y1 -> 12; else -> 0 }
}

object CandleMath {
    val ZONE: ZoneId = ZoneId.of("Africa/Tripoli")

    /** Start of the bucket that contains [t] for the given frame (local Tripoli calendar for day+ frames). */
    fun bucketStart(t: Long, tf: Timeframe, zone: ZoneId = ZONE): Long {
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(t), zone)
        return when (tf) {
            Timeframe.H1, Timeframe.H4, Timeframe.H12 -> {
                val hours = (tf.millis / 3600_000L).toInt()
                z.truncatedTo(ChronoUnit.HOURS).withHour(z.hour - z.hour % hours).toInstant().toEpochMilli()
            }
            Timeframe.D1 -> z.truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()
            Timeframe.W1 -> {
                // weeks start on Saturday (local working week)
                val d = z.truncatedTo(ChronoUnit.DAYS)
                val back = (d.dayOfWeek.value - java.time.DayOfWeek.SATURDAY.value + 7) % 7
                d.minusDays(back.toLong()).toInstant().toEpochMilli()
            }
            else -> {
                val m = tf.months
                val d = z.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1)
                d.withMonth(((d.monthValue - 1) / m) * m + 1).toInstant().toEpochMilli()
            }
        }
    }

    /**
     * Candles from a sparse tick series (time, price), e.g. Telegram posts. Only buckets that contain at least one
     * tick get a candle; each candle opens at the previous candle's close so the series stays continuous
     * (carried forward) without drawing empty filler candles for silent periods.
     */
    fun fromTicks(points: List<Pair<Long, Double>>, tf: Timeframe, zone: ZoneId = ZONE): List<Candle> {
        if (points.isEmpty()) return emptyList()
        val sorted = points.sortedBy { it.first }
        val out = ArrayList<Candle>()
        var prevClose: Double? = null
        var curStart = Long.MIN_VALUE
        var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0
        for ((t, v) in sorted) {
            if (!v.isFinite()) continue
            val b = bucketStart(t, tf, zone)
            if (b != curStart) {
                if (curStart != Long.MIN_VALUE) { out.add(Candle(curStart, o, h, l, c)); prevClose = c }
                curStart = b
                o = prevClose ?: v
                h = maxOf(o, v); l = minOf(o, v); c = v
            } else {
                h = maxOf(h, v); l = minOf(l, v); c = v
            }
        }
        if (curStart != Long.MIN_VALUE) out.add(Candle(curStart, o, h, l, c))
        return out
    }

    /** Merge finer candles into a coarser frame (e.g. 1h -> 4h, 1M -> 6M / 1Y). */
    fun regroup(candles: List<Candle>, tf: Timeframe, zone: ZoneId = ZONE): List<Candle> {
        if (candles.isEmpty()) return candles
        val out = ArrayList<Candle>()
        var cur: Candle? = null
        for (k in candles.sortedBy { it.t }) {
            val b = bucketStart(k.t, tf, zone)
            val c0 = cur
            cur = if (c0 == null || c0.t != b) {
                if (c0 != null) out.add(c0)
                Candle(b, k.o, k.h, k.l, k.c)
            } else Candle(b, c0.o, maxOf(c0.h, k.h), minOf(c0.l, k.l), k.c)
        }
        cur?.let(out::add)
        return out
    }

    /** Update / append the newest candle with a live price. */
    fun withLive(candles: List<Candle>, t: Long, price: Double, tf: Timeframe, zone: ZoneId = ZONE): List<Candle> {
        if (candles.isEmpty() || !price.isFinite()) return candles
        val b = bucketStart(t, tf, zone)
        val last = candles.last()
        return when {
            b == last.t -> candles.dropLast(1) + last.copy(h = maxOf(last.h, price), l = minOf(last.l, price), c = price)
            b > last.t -> candles + Candle(b, last.c, maxOf(last.c, price), minOf(last.c, price), price)
            else -> candles
        }
    }
}
