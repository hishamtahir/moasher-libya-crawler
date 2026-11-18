package com.djphoenix.moasherlibya.parser

/** English display names for the rare "other" items. Unknown labels keep their original Arabic text. */
object Translit {
    private val labels: Map<String, String> = listOf(
        "دينار ليبي" to "Libyan dinar",
        "دينار تونسي" to "Tunisian dinar",
        "100 دينار ليبي" to "100 Libyan dinar",
        "100 دينار تونسي" to "100 Tunisian dinar",
        "100 يورو" to "100 Euro",
        "100 دولار" to "100 US dollar",
        "100 دولار أمريكي" to "100 US dollar",
        "1 دينار ليبي" to "1 Libyan dinar",
        "1 دولار" to "1 US dollar",
        "1 يورو" to "1 Euro",
        "دولار ليبي عشرينات" to "USD (20s notes, local)",
        "دولار ليبي خمسينات" to "USD (50s notes, local)",
        "دولار بشيك ارضه" to "USD cheque – Ardah",
        "دولار بشيك الوحدة" to "USD cheque – Wahda Bank",
        "دولار بشيك الجمهورية" to "USD cheque – Jumhouria Bank",
        "دولار بشيك الامان" to "USD cheque – Aman Bank",
        "دولار بشيك طرابلس" to "USD cheque – Tripoli",
        "دولار بشيك التجارة والتنمية" to "USD cheque – Trade & Development Bank",
        "دولار طرابلس" to "USD – Tripoli",
        "دولار بنغازي" to "USD – Benghazi",
        "دولار-حوالة دبي" to "USD – Dubai transfer",
        "حوالة-دبي" to "Dubai transfer",
        "صك الجمهورية" to "Jumhouria Bank cheque",
        "صك التنمية" to "Development Bank cheque",
        "صك التنمية-تركيا" to "Development Bank cheque – Turkey",
        "الذهب الخارجي عيار 18" to "Imported gold 18K",
        "مسبوك فضة نادر" to "Cast rare silver",
        "مسبوك فضة نادر تركية" to "Cast rare silver (Turkey)",
        "مسبوك فضة تركية نادر" to "Cast rare silver (Turkey)",
        "فضة نادر تركية-بيع" to "Rare silver (Turkey) – sell",
        "فضة نادر تركية-معروض" to "Rare silver (Turkey) – offered",
        "فضة نادر تركية-يساومو" to "Rare silver (Turkey) – negotiable",
        "فضة نادر تركية-انباعت" to "Rare silver (Turkey) – sold",
        "سبيكة فضة نادر التركية" to "Rare silver (Turkey) bar",
        "البنزين" to "Gasoline",
        "الغاز الطبيعي" to "Natural gas",
        "Natural Gas" to "Natural gas",
        "النحاس" to "Copper",
        "اسمنت" to "Cement",
        "بيتكوين" to "Bitcoin",
        "الفحم" to "Coal",
        "زيت التسخين" to "Heating oil",
        "مصرف الصحاري" to "Sahara Bank",
        "مصرف الامان" to "Aman Bank",
        "مصرف الوحده" to "Wahda Bank",
        "مصرف الجمهورية" to "Jumhouria Bank",
    ).associate { Arabic.compact(it.first) to it.second }

    private val units = mapOf(
        Arabic.compact("دينار تونسي") to "Tunisian dinar",
        Arabic.compact("دينار ليبي") to "Libyan dinar",
        Arabic.compact("ليرة تركية") to "Turkish lira",
        Arabic.compact("جنيه") to "EGP",
        Arabic.compact("صك") to "cheque",
        Arabic.compact("حوالة مصرفية") to "bank transfer",
    )

    fun label(original: String): String = labels[Arabic.compact(original)] ?: original

    /** Arabic display text for an "other" label: only the wording of the silver name is adjusted (parsing is unaffected). */
    fun displayAr(original: String): String = original.replace("فضة نادر تركية", "فضة نادر تركيا")

    fun unit(unit: String): String = when (unit) {
        "دينار" -> "LYD"
        "د.ت" -> "TND"
        "ج.م" -> "EGP"
        "" -> ""
        "$" -> "$"
        else -> units[Arabic.compact(unit)] ?: unit
    }
}
