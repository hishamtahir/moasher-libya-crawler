package com.djphoenix.moasherlibya.market

/** Compact text form of a candle series for the shared Firestore cache: "tSec,o,h,l,c;tSec,o,h,l,c;..." */
object CandleCodec {
    private fun n(v: Double): String {
        val r = Math.round(v * 10000.0) / 10000.0
        return if (r == Math.floor(r) && Math.abs(r) < 1e12) r.toLong().toString() else r.toString()
    }

    fun encode(candles: List<Candle>): String =
        candles.filter { it.o.isFinite() && it.h.isFinite() && it.l.isFinite() && it.c.isFinite() }
            .joinToString(";") { "${it.t / 1000},${n(it.o)},${n(it.h)},${n(it.l)},${n(it.c)}" }

    fun decode(s: String): List<Candle> = s.split(';').mapNotNull { row ->
        val p = row.split(',')
        if (p.size != 5) return@mapNotNull null
        val t = p[0].toLongOrNull() ?: return@mapNotNull null
        val v = p.drop(1).map { it.toDoubleOrNull() ?: return@mapNotNull null }
        Candle(t * 1000, v[0], v[1], v[2], v[3])
    }
}
