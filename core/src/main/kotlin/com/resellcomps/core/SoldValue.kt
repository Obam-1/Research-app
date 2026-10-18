package com.resellcomps.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** One sold listing read off the user's own screenshot of eBay's sold results. */
data class SoldComp(
    val title: String,
    /** The sold price shown, without shipping. */
    val price: Double,
    /** Shipping the buyer paid; 0.0 for free shipping, null when not shown. */
    val shipping: Double?,
    /** As printed, e.g. "Sold Oct 14, 2026". */
    val soldDate: String?,
    /** "Best offer accepted": the real price was lower than the crossed-out price shown. */
    val bestOffer: Boolean,
    /** Same item as the one scanned (same line, model, era); false for lots, parts, other models. */
    val match: Boolean,
    /** Why it does or doesn't match, in a few words. */
    val why: String?,
)

/**
 * Value from real sold listings. The user opens eBay's sold results, takes screenshots, and the
 * AI reads each listing off them; the app then does the math here. Nothing is estimated without
 * matching sold listings behind it.
 */
object SoldValue {

    /**
     * What Google (Lens or Search) said the item is worth, read off the user's screenshot. Kept
     * apart from the sold math: it's an AI summary of web pages, mostly asking prices.
     */
    data class GoogleSays(val value: String, val summary: String?, val sources: List<String>)

    /** Everything read off one set of screenshots. */
    data class Reading(val comps: List<SoldComp>, val google: GoogleSays?)

    val SYSTEM = """
        You read screenshots of eBay sold-listing search results for a reseller. Copy what is on
        the screen; never invent a listing or a price, and never estimate one.
        For every listing you can see, give its title, sold price, shipping, sold date and whether
        it's a best offer:
        - The sold price is the large price (often green). If it is crossed out and the listing says
          "Best offer accepted", set best_offer true and give the crossed-out number.
        - A price range (e.g. "${'$'}20.00 to ${'$'}35.00") is a multi-variation listing: skip it.
        - Shipping: "+${'$'}8.95 delivery" or "shipping" -> 8.95; "Free delivery/shipping" -> 0; not
          shown -> null. Ignore "Sponsored" labels, seller info and watcher counts.
        - Skip anything below a "Results matching fewer words" heading: those aren't real matches.
        - Read each listing once, even if two screenshots overlap.
        Then decide match: true only if the listing is the same item a buyer would compare with
        the scanned one (same brand, same line or model, same kind of item; size only matters
        where it changes the price). Set match false for lots and bundles, "for parts", empty boxes
        or accessories only, a different line or model, or a reproduction when the scanned one is
        original. Put the reason in why in a few words.
        Reply with only a JSON object: {"listings":[{"title":"...","price":24.99,"shipping":0,
        "sold_date":"Sold Oct 14, 2026","best_offer":false,"match":true,"why":"same item"}]}.
        Some screenshots may instead show Google Lens or Google Search answering what the item is
        worth (an AI overview or shopping results). For those, copy what Google says in
        google_value: "value" (the price or range exactly as Google wrote it), "summary" (one
        sentence of what it based that on, or null) and "sources" (the site names it shows or
        cites, e.g. "eBay", "WorthPoint", "Poshmark"). Don't put Google's prices or shopping
        results into listings. If no screenshot shows that, set google_value to null.
        Full reply shape: {"listings":[...],"google_value":{"value":"${'$'}40-${'$'}80","summary":"...",
        "sources":["eBay"]}}. If nothing on the screenshots shows prices, reply
        {"listings":[],"google_value":null}.
    """.trimIndent()

    fun userText(item: ItemIdentification, query: String, screenText: String?): String = buildString {
        append("The scanned item: ").append(item.name.ifBlank { query })
        item.brand?.let { append(" (brand: ").append(it).append(")") }
        item.era?.let { append(", era: ").append(it) }
        if (item.details.isNotBlank()) append(". Details: ").append(item.details.take(300))
        append(". The eBay search was \"").append(query).append("\".")
        if (!screenText.isNullOrBlank()) {
            append("\n\nText read from the screenshots by the phone, top to bottom (use it to get the ")
            append("numbers exactly right; use the images to see which price goes with which listing):\n")
            append(screenText.take(6000))
        }
        append("\n\nList every sold listing on these screenshots.")
    }

    fun read(o: JsonObject): Reading = Reading(parse(o), google(o))

    private fun google(o: JsonObject): GoogleSays? {
        val g = o["google_value"] as? JsonObject ?: return null
        fun str(k: String) = (g[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val value = str("value") ?: return null
        val sources = (g["sources"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }.distinct()
        return GoogleSays(value, str("summary"), sources)
    }

    fun parse(o: JsonObject): List<SoldComp> =
        (o["listings"] as? JsonArray).orEmpty().mapNotNull { el ->
            val l = el as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (l[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            fun num(k: String) = (l[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: money(it.contentOrNull) }
            val price = num("price")?.takeIf { it > 0 } ?: return@mapNotNull null
            SoldComp(
                title = str("title") ?: return@mapNotNull null,
                price = price,
                shipping = num("shipping")?.takeIf { it >= 0 },
                soldDate = str("sold_date"),
                bestOffer = (l["best_offer"] as? JsonPrimitive)?.booleanOrNull ?: false,
                match = (l["match"] as? JsonPrimitive)?.booleanOrNull ?: false,
                why = str("why"),
            )
        }

    /** "$1,234.50" -> 1234.5; "Free" -> 0. */
    fun money(text: String?): Double? {
        val t = text?.trim()?.lowercase() ?: return null
        if (t.startsWith("free")) return 0.0
        return Regex("""(\d[\d,]*(?:\.\d+)?)""").find(t)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
    }

    /** Combines readings from several batches of screenshots. */
    fun merge(readings: List<Reading>): Reading =
        Reading(merge(*readings.map { it.comps }.toTypedArray()), readings.firstNotNullOfOrNull { it.google })

    /** Combines readings from several screenshots, keeping each listing once. */
    fun merge(vararg batches: List<SoldComp>): List<SoldComp> =
        batches.toList().flatten().distinctBy { it.title.lowercase().replace(Regex("\\s+"), " ").take(60) to it.price }

    /** The value, worked out only from the listings marked as matches. */
    data class Estimate(
        /** Matching sales the numbers come from (outliers and best offers removed). */
        val used: Int,
        val median: Double,
        val low: Double,
        val high: Double,
        /** The middle half of sales, when there are enough of them. */
        val typicalLow: Double?,
        val typicalHigh: Double?,
        /** What it usually goes for when priced to sell. */
        val quickSale: Double,
        val medianShipping: Double?,
        /** "Strong", "Fair" or "Weak", from how many sales back it. */
        val strength: String,
        /** True when only best-offer sales matched, so the real prices were lower than these. */
        val upperLimitOnly: Boolean,
        /** Plain-language reasons, each tied to the listings. */
        val reasons: List<String>,
    )

    fun estimate(comps: List<SoldComp>): Estimate? {
        val matches = comps.filter { it.match }
        if (matches.isEmpty()) return null
        val firm = matches.filter { !it.bestOffer }
        val upperLimitOnly = firm.isEmpty()
        val basis = if (upperLimitOnly) matches else firm

        val sorted = basis.map { it.price }.sorted()
        // With 4+ sales, drop prices far outside the rest (a mislisted bundle, a typo).
        val kept = if (sorted.size >= 4) {
            val q1 = percentile(sorted, 0.25)
            val q3 = percentile(sorted, 0.75)
            val fence = 1.5 * (q3 - q1)
            sorted.filter { it >= q1 - fence && it <= q3 + fence }
        } else sorted
        val outliers = sorted.size - kept.size

        val median = percentile(kept, 0.5)
        val typicalLow = if (kept.size >= 4) percentile(kept, 0.25) else null
        val typicalHigh = if (kept.size >= 4) percentile(kept, 0.75) else null
        val shipping = basis.mapNotNull { it.shipping }.sorted()
        val strength = when {
            kept.size >= 8 -> "Strong"
            kept.size >= 4 -> "Fair"
            else -> "Weak"
        }

        val reasons = buildList {
            add("Based on ${kept.size} matching sold listing${if (kept.size == 1) "" else "s"} from your screenshots.")
            val skipped = comps.size - matches.size
            if (skipped > 0) {
                val whys = comps.filter { !it.match }.mapNotNull { it.why }.distinct().take(3)
                add("$skipped other listing${if (skipped == 1) " was" else "s were"} left out (" +
                    (whys.joinToString(", ").ifEmpty { "not the same item" }) + ").")
            }
            if (outliers > 0) add("$outliers price${if (outliers == 1) " was" else "s were"} far from the rest and left out of the math.")
            val bo = matches.count { it.bestOffer }
            if (bo > 0 && !upperLimitOnly) add("$bo best-offer sale${if (bo == 1) "" else "s"} not counted: eBay hides what they really sold for (it was less than shown).")
            if (upperLimitOnly) add("Every match was a best offer, so these are upper limits: the real prices were lower.")
            if (kept.size < 4) add("Fewer than 4 sales: treat this as a rough guide, and screenshot more results to firm it up.")
            dateSpan(basis.mapNotNull { it.soldDate })?.let { add(it) }
            if (shipping.isNotEmpty()) {
                val free = shipping.count { it == 0.0 }
                add(
                    if (free == shipping.size) "All sold with free shipping, so the price includes it."
                    else "Buyers also paid about ${usd(percentile(shipping, 0.5))} shipping on top (median)."
                )
            }
        }

        return Estimate(
            used = kept.size,
            median = median,
            low = kept.first(),
            high = kept.last(),
            typicalLow = typicalLow,
            typicalHigh = typicalHigh,
            quickSale = typicalLow ?: kept.first(),
            medianShipping = shipping.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.5) },
            strength = strength,
            upperLimitOnly = upperLimitOnly,
            reasons = reasons,
        )
    }

    fun usd(v: Double): String = "$" + if (v >= 100) v.roundToInt().toString() else String.format(Locale.US, "%.2f", v)

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = p * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    private val DATE_FORMATS = listOf("MMM d, yyyy", "MMM d yyyy", "d MMM yyyy", "MMM dd, yyyy")
        .map { DateTimeFormatter.ofPattern(it, Locale.US) }

    /** "Sold Oct 14, 2026" -> that date, when it can be read. */
    fun soldDate(text: String): LocalDate? {
        val t = text.replace(Regex("(?i)^\\s*sold\\s*"), "").replace(".", "").trim()
        return DATE_FORMATS.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(t, f) }.getOrNull() }
    }

    private fun dateSpan(dates: List<String>): String? {
        val parsed = dates.mapNotNull { soldDate(it) }.sorted()
        if (parsed.isEmpty()) return null
        val f = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
        return if (parsed.first() == parsed.last()) "Sold ${f.format(parsed.first())}."
        else "Sold between ${f.format(parsed.first())} and ${f.format(parsed.last())}."
    }
}
