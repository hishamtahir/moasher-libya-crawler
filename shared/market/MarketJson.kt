package com.djphoenix.moasherlibya.market

import kotlinx.serialization.json.*

/** Quote of a live market item. [prevClose] is used for the change pill. */
data class Quote(val key: String, val price: Double, val t: Long, val prevClose: Double? = null)

/** Pure JSON parsers for the market APIs (unit tested on recorded responses). */
object MarketJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.d(): Double? = (this as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() }
    private fun JsonElement?.l(): Long? = (this as? JsonPrimitive)?.let { p -> p.longOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toLong() }

    // ------------------------------------------------------------------ Yahoo Finance v8 chart

    data class YahooChart(val quote: Quote?, val candles: List<Candle>)

    fun yahooChart(key: String, body: String): YahooChart? = runCatching {
        val res = json.parseToJsonElement(body).jsonObject["chart"]!!.jsonObject["result"]!!.jsonArray[0].jsonObject
        val meta = res["meta"]!!.jsonObject
        val price = meta["regularMarketPrice"].d()
        val tSec = meta["regularMarketTime"].l()
        val prev = meta["chartPreviousClose"].d() ?: meta["previousClose"].d()
        val ts = res["timestamp"]?.jsonArray?.map { it.l() } ?: emptyList()
        val q = res["indicators"]?.jsonObject?.get("quote")?.jsonArray?.getOrNull(0)?.jsonObject
        val candles = ArrayList<Candle>()
        if (q != null) {
            val o = q["open"]?.jsonArray; val h = q["high"]?.jsonArray; val lo = q["low"]?.jsonArray; val c = q["close"]?.jsonArray
            for (i in ts.indices) {
                val t = ts[i] ?: continue
                val oo = o?.getOrNull(i).d(); val hh = h?.getOrNull(i).d(); val ll = lo?.getOrNull(i).d(); val cc = c?.getOrNull(i).d()
                if (oo == null || hh == null || ll == null || cc == null) continue
                if (oo <= 0 || cc <= 0) continue
                candles.add(Candle(t * 1000, oo, maxOf(hh, oo, cc), minOf(ll, oo, cc), cc))
            }
        }
        val quote = if (price != null && price > 0 && tSec != null) Quote(key, price, tSec * 1000, prev) else null
        YahooChart(quote, candles)
    }.getOrNull()

    // ------------------------------------------------------------------ Binance

    /** One message of the `<sym>@ticker` stream, or one element of `/api/v3/ticker/24hr`. */
    fun binanceTicker(el: JsonObject, keyOf: (String) -> String?): Quote? {
        val sym = (el["s"] ?: el["symbol"])?.jsonPrimitive?.contentOrNull ?: return null
        val key = keyOf(sym) ?: return null
        val price = (el["c"] ?: el["lastPrice"]).d() ?: return null
        val open = (el["o"] ?: el["openPrice"]).d()
        val t = (el["E"] ?: el["closeTime"]).l() ?: System.currentTimeMillis()
        return Quote(key, price, t, open)
    }

    fun binanceTickerMessage(body: String, keyOf: (String) -> String?): Quote? =
        runCatching { binanceTicker(json.parseToJsonElement(body).jsonObject, keyOf) }.getOrNull()

    fun binanceTickers(body: String, keyOf: (String) -> String?): List<Quote> = runCatching {
        json.parseToJsonElement(body).jsonArray.mapNotNull { binanceTicker(it.jsonObject, keyOf) }
    }.getOrDefault(emptyList())

    fun binanceKlines(body: String): List<Candle> = runCatching {
        json.parseToJsonElement(body).jsonArray.mapNotNull { row ->
            val a = row.jsonArray
            val t = a[0].l() ?: return@mapNotNull null
            Candle(t, a[1].d() ?: return@mapNotNull null, a[2].d() ?: return@mapNotNull null, a[3].d() ?: return@mapNotNull null, a[4].d() ?: return@mapNotNull null)
        }.sortedBy { it.t }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ OKX

    fun okxTickers(body: String, keyOf: (String) -> String?): List<Quote> = runCatching {
        json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            val inst = o["instId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val key = keyOf(inst.replace("-", "")) ?: return@mapNotNull null
            Quote(key, o["last"].d() ?: return@mapNotNull null, o["ts"].l() ?: System.currentTimeMillis(), o["sodUtc0"].d() ?: o["open24h"].d())
        }
    }.getOrDefault(emptyList())

    fun okxCandles(body: String): List<Candle> = runCatching {
        json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray.mapNotNull { row ->
            val a = row.jsonArray
            Candle(a[0].l() ?: return@mapNotNull null, a[1].d() ?: return@mapNotNull null, a[2].d() ?: return@mapNotNull null,
                a[3].d() ?: return@mapNotNull null, a[4].d() ?: return@mapNotNull null)
        }.sortedBy { it.t }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ CoinGecko (last resort, prices only)

    val geckoIds = mapOf("c_btc" to "bitcoin", "c_eth" to "ethereum", "c_bnb" to "binancecoin", "c_sol" to "solana", "c_xrp" to "ripple")

    fun geckoSimple(body: String, now: Long = System.currentTimeMillis()): List<Quote> = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        geckoIds.mapNotNull { (key, id) ->
            val e = o[id]?.jsonObject ?: return@mapNotNull null
            val p = e["usd"].d() ?: return@mapNotNull null
            val ch = e["usd_24h_change"].d()
            Quote(key, p, now, ch?.let { p / (1 + it / 100.0) })
        }
    }.getOrDefault(emptyList())
}
