package com.djphoenix.moasherlibya.parser

/** The collapsible sections of the home screen, in display order. */
enum class Section(val id: String, val title: String, val titleEn: String) {
    CURRENCY("cur", "العملات", "Currencies"),
    CHECKS("chk", "الصكوك والحوالات", "Cheques & Transfers"),
    GOLD("gold", "الذهب", "Gold"),
    SILVER("silver", "الفضة", "Silver"),
    OFFICIAL("cbl", "الأسعار الرسمية (المركزي)", "Official Rates (CBL)"),
    WORLD("world", "الأسواق العالمية", "Global Markets"),
    CRYPTO("crypto", "العملات الرقمية", "Crypto"),
    OTHER("other", "أسعار أخرى", "Other Prices");

    /** Sections fed by live market APIs (not by the Telegram price posts). */
    val isLive: Boolean get() = this == WORLD || this == CRYPTO

    /** Official central-bank rates: one publication per working day (date only, no time of day). */
    val isDaily: Boolean get() = this == OFFICIAL
}

/** Calculator units of one item: 1 [base] = price [quote]. */
data class CalcUnits(val baseAr: String, val baseEn: String, val quoteAr: String, val quoteEn: String)

/** Static definition of a first-class (mapped) item. Unmapped items live in [Section.OTHER] with dynamic keys. */
data class ItemDef(
    val key: String,
    val section: Section,
    val title: String,
    val titleEn: String,
    val unit: String,       // display unit: "دينار", "$", "د.ت", "ج.م"
    val min: Double,        // sanity bounds used to reject garbage values
    val max: Double,
    val decimals: Int,
    val calc: CalcUnits? = null,      // null = no calculator for this row
    val noteAr: String = "",          // small caption under the title
    val noteEn: String = "",
)

object Catalog {
    private const val LYD = "دينار"
    private const val USD = "$"
    const val TND = "د.ت"
    const val EGP = "ج.م"

    private fun c(baseAr: String, baseEn: String, quoteAr: String = "د.ل", quoteEn: String = "LYD") = CalcUnits(baseAr, baseEn, quoteAr, quoteEn)
    private val gram = c("جرام", "gram")
    private fun coin(sym: String) = c(sym, sym, "دولار", "USD")

    /** Default units per quote of an official rate (the parser reads the actual unit from the page). */
    val officialQty: MutableMap<String, Int> = HashMap()

    private fun official(key: String, ar: String, en: String, unitAr: String, code: String, qty: Int = 1): ItemDef {
        officialQty[key] = qty
        val base = if (qty == 1) c(unitAr, code) else c("$qty $unitAr", "$qty $code")
        val noteAr = if (qty == 1) "" else "لكل $qty $unitAr"
        val noteEn = if (qty == 1) "" else "per $qty $code"
        return ItemDef(key, Section.OFFICIAL, ar, en, LYD, 0.0001, 100000.0, 4, base, noteAr, noteEn)
    }

    val items: List<ItemDef> = listOf(
        ItemDef("cur_usd", Section.CURRENCY, "الدولار", "US Dollar", LYD, 2.0, 40.0, 2, c("دولار", "USD")),
        ItemDef("cur_eur", Section.CURRENCY, "اليورو", "Euro", LYD, 2.0, 45.0, 2, c("يورو", "EUR")),
        ItemDef("cur_gbp", Section.CURRENCY, "الباوند", "British Pound", LYD, 2.0, 55.0, 2, c("باوند", "GBP")),
        ItemDef("cur_tnd", Section.CURRENCY, "الدينار التونسي", "Tunisian Dinar", TND, 0.05, 2.0, 3, c("دينار ليبي", "LYD", "دينار تونسي", "TND"), "مقابل 1 دينار ليبي", "per 1 Libyan dinar"),
        ItemDef("cur_egp", Section.CURRENCY, "الجنيه المصري", "Egyptian Pound", EGP, 0.5, 40.0, 2, c("دينار ليبي", "LYD", "جنيه مصري", "EGP"), "مقابل 1 دينار ليبي", "per 1 Libyan dinar"),

        ItemDef("chk_usd", Section.CHECKS, "الدولار بشيك", "USD Cheque", LYD, 2.0, 40.0, 2, c("دولار", "USD")),
        ItemDef("chk_usd_tr", Section.CHECKS, "الدولار بشيك حوالة تركيا", "USD Cheque – Turkey Transfer", LYD, 2.0, 40.0, 2, c("دولار", "USD")),
        ItemDef("chk_eur_tr", Section.CHECKS, "اليورو حوالة تركيا", "EUR – Turkey Transfer", LYD, 2.0, 45.0, 2, c("يورو", "EUR")),
        ItemDef("chk_cards", Section.CHECKS, "نسبة سحب بطاقات الأمان", "Security Cards Withdrawal Rate", USD, 300.0, 20000.0, 0, null),

        ItemDef("gold_k18", Section.GOLD, "كسر عيار 18", "Scrap Gold 18K", LYD, 100.0, 8000.0, 0, gram),
        ItemDef("gold_k21", Section.GOLD, "كسر عيار 21", "Scrap Gold 21K", LYD, 100.0, 9000.0, 0, gram),

        ItemDef("silver_turk", Section.SILVER, "فضة نادر تركيا", "Rare Silver (Turkey)", LYD, 3.0, 300.0, 2, gram),
        ItemDef("silver_turk_chk", Section.SILVER, "فضة نادر تركيا بالصك", "Rare Silver (Turkey) – Cheque", LYD, 3.0, 300.0, 2, gram),

        ItemDef("w_gold", Section.WORLD, "أونصة الذهب", "Gold Ounce", USD, 300.0, 50000.0, 2, c("أونصة", "oz", "دولار", "USD")),
        ItemDef("w_silver", Section.WORLD, "أونصة الفضة", "Silver Ounce", USD, 3.0, 2000.0, 2, c("أونصة", "oz", "دولار", "USD")),
        ItemDef("w_brent", Section.WORLD, "نفط برنت", "Brent Crude", USD, 10.0, 400.0, 2, c("برميل", "barrel", "دولار", "USD")),
        ItemDef("w_wti", Section.WORLD, "نفط غرب تكساس", "WTI Crude", USD, 10.0, 400.0, 2, c("برميل", "barrel", "دولار", "USD")),

        ItemDef("c_btc", Section.CRYPTO, "بيتكوين", "Bitcoin", USD, 100.0, 10_000_000.0, 2, coin("BTC"), "BTC", "BTC"),
        ItemDef("c_eth", Section.CRYPTO, "إيثريوم", "Ethereum", USD, 1.0, 1_000_000.0, 2, coin("ETH"), "ETH", "ETH"),
        ItemDef("c_bnb", Section.CRYPTO, "بايننس كوين", "BNB", USD, 1.0, 1_000_000.0, 2, coin("BNB"), "BNB", "BNB"),
        ItemDef("c_sol", Section.CRYPTO, "سولانا", "Solana", USD, 0.1, 1_000_000.0, 2, coin("SOL"), "SOL", "SOL"),
        ItemDef("c_xrp", Section.CRYPTO, "ريبل", "XRP", USD, 0.001, 100_000.0, 4, coin("XRP"), "XRP", "XRP"),

        // Official central bank rates (average of buy/sell, in LYD per [qty] units of the currency)
        official("cbl_usd", "الدولار الأمريكي", "US Dollar", "دولار", "USD"),
        official("cbl_eur", "اليورو", "Euro", "يورو", "EUR"),
        official("cbl_gbp", "الجنيه الإسترليني", "British Pound", "جنيه إسترليني", "GBP"),
        official("cbl_cad", "الدولار الكندي", "Canadian Dollar", "دولار كندي", "CAD"),
        official("cbl_aud", "الدولار الأسترالي", "Australian Dollar", "دولار أسترالي", "AUD"),
        official("cbl_chf", "الفرنك السويسري", "Swiss Franc", "فرنك سويسري", "CHF"),
        official("cbl_sek", "الكرونة السويدية", "Swedish Krona", "كرونة سويدية", "SEK"),
        official("cbl_nok", "الكرونة النرويجية", "Norwegian Krone", "كرونة نرويجية", "NOK"),
        official("cbl_dkk", "الكرونة الدنماركية", "Danish Krone", "كرونة دنماركية", "DKK"),
        official("cbl_jpy", "الين الياباني", "Japanese Yen", "ين", "JPY", 100),
        official("cbl_sar", "الريال السعودي", "Saudi Riyal", "ريال سعودي", "SAR"),
        official("cbl_aed", "الدرهم الإماراتي", "UAE Dirham", "درهم إماراتي", "AED"),
        official("cbl_tnd", "الدينار التونسي", "Tunisian Dinar", "دينار تونسي", "TND"),
        official("cbl_dzd", "الدينار الجزائري", "Algerian Dinar", "دينار جزائري", "DZD", 10),
        official("cbl_mad", "الدرهم المغربي", "Moroccan Dirham", "درهم مغربي", "MAD"),
        official("cbl_mru", "الأوقية الموريتانية", "Mauritanian Ouguiya", "أوقية", "MRU", 100),
        official("cbl_xof", "الفرنك الأفريقي", "CFA Franc", "فرنك أفريقي", "XOF"),
        official("cbl_rub", "الروبل الروسي", "Russian Ruble", "روبل", "RUB", 10),
        official("cbl_try", "الليرة التركية", "Turkish Lira", "ليرة تركية", "TRY"),
        official("cbl_cny", "اليوان الصيني", "Chinese Yuan", "يوان", "CNY"),

        // Gold variants that live in "other prices" but are always shown there (not subject to the 7-day rule)
        ItemDef("gold_new", Section.OTHER, "الذهب الجديد", "New Gold", LYD, 100.0, 9000.0, 0, gram),
        ItemDef("gold_used", Section.OTHER, "الذهب المستعمل", "Used Gold", LYD, 100.0, 9000.0, 0, gram),
        ItemDef("gold_cast", Section.OTHER, "الذهب المسبوك", "Cast Gold", LYD, 100.0, 9000.0, 0, gram),
    )

    val byKey: Map<String, ItemDef> = items.associateBy { it.key }

    /** Mapped items of a section in display order. */
    fun itemsOf(section: Section): List<ItemDef> = items.filter { it.section == section }

    fun isOtherKey(key: String) = key.startsWith("o_")

    /** Official central-bank rates (fetched from the bank's rates page, never from Telegram nor the shared board). */
    fun isOfficialKey(key: String) = key.startsWith("cbl_")

    /** Keys whose prices come from live market APIs; never taken from Telegram nor published to the shared board. */
    fun isLiveKey(key: String) = key.startsWith("w_") || key.startsWith("c_")

    /** Generic calculator units for an "other" row. */
    fun calcFor(key: String, unit: String): CalcUnits? {
        byKey[key]?.let { return it.calc }
        if (unit == "%") return null
        return when (unit) {
            "$" -> CalcUnits("وحدة", "unit", "دولار", "USD")
            "دينار", "" -> CalcUnits("وحدة", "unit", "د.ل", "LYD")
            else -> CalcUnits("وحدة", "unit", unit, Translit.unit(unit))
        }
    }

    val cryptoSymbols: Map<String, String> = mapOf("c_btc" to "BTC", "c_eth" to "ETH", "c_bnb" to "BNB", "c_sol" to "SOL", "c_xrp" to "XRP")
}

/** One price line extracted from a post. [label] is the cleaned original label (used for "other" items). */
/** UI language. Default is Arabic. */
enum class Lang(val code: String) { AR("ar"), EN("en") }

fun Section.title(lang: Lang) = if (lang == Lang.EN) titleEn else title
fun ItemDef.title(lang: Lang) = if (lang == Lang.EN) titleEn else title

data class ParsedLine(
    val key: String,
    val label: String,
    val value: Double,
    val unit: String,
)
