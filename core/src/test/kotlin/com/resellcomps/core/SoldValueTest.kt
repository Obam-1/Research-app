package com.resellcomps.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoldValueTest {
    private fun comp(price: Double, match: Boolean = true, bestOffer: Boolean = false, shipping: Double? = 0.0, date: String? = null, why: String? = null) =
        SoldComp("Ralph Lauren Purple Label cardigan", price, shipping, date, bestOffer, match, why)

    @Test fun noMatchesMeansNoPrice() {
        assertNull(SoldValue.estimate(emptyList()))
        assertNull(SoldValue.estimate(listOf(comp(40.0, match = false))))
    }

    @Test fun medianAndMiddleHalfFromMatchesOnly() {
        val comps = listOf(80.0, 95.0, 100.0, 110.0, 120.0, 130.0, 140.0, 150.0).map { comp(it) } +
            comp(15.0, match = false, why = "lot of 3")
        val e = SoldValue.estimate(comps)!!
        assertEquals(8, e.used)
        assertEquals("Strong", e.strength)
        assertEquals(115.0, e.median)
        assertEquals(98.75, e.typicalLow)
        assertEquals(132.5, e.typicalHigh)
        assertEquals(e.typicalLow, e.quickSale)
        assertTrue(e.reasons.any { it.contains("1 other listing was left out (lot of 3)") })
        assertTrue(e.reasons.any { it.contains("free shipping") })
    }

    @Test fun outliersAndBestOffersAreLeftOut() {
        val comps = listOf(30.0, 32.0, 35.0, 36.0, 400.0).map { comp(it) } + comp(90.0, bestOffer = true)
        val e = SoldValue.estimate(comps)!!
        assertEquals(4, e.used)
        assertEquals(36.0, e.high)
        assertTrue(e.reasons.any { it.contains("far from the rest") })
        assertTrue(e.reasons.any { it.contains("best-offer") })
    }

    @Test fun onlyBestOffersGiveAnUpperLimit() {
        val e = SoldValue.estimate(listOf(comp(60.0, bestOffer = true), comp(70.0, bestOffer = true)))!!
        assertTrue(e.upperLimitOnly)
        assertEquals("Weak", e.strength)
    }

    @Test fun parsesTheAiReadingAndDropsDuplicates() {
        val o = Json.parseToJsonElement("""{"listings":[
            {"title":"RL Purple Label cardigan XXL","price":"${'$'}1,150.00","shipping":"Free","sold_date":"Sold Oct 14, 2026","best_offer":false,"match":true,"why":"same line"},
            {"title":"RL cardigan lot","price":45,"shipping":8.95,"match":false,"why":"lot"},
            {"title":"no price","price":null}]}""") as JsonObject
        val comps = SoldValue.parse(o)
        assertEquals(2, comps.size)
        assertEquals(1150.0, comps[0].price)
        assertEquals(0.0, comps[0].shipping)
        assertEquals(1, SoldValue.merge(comps, comps.take(1)).count { it.match })
        assertEquals(java.time.LocalDate.of(2026, 10, 14), SoldValue.soldDate("Sold Oct 14, 2026"))
        assertEquals("${'$'}1150", SoldValue.usd(1150.0))
    }

    @Test fun googleValueIsReadButKeptApartFromTheSolds() {
        val o = Json.parseToJsonElement("""{"listings":[],"google_value":{"value":"${'$'}150 - ${'$'}400","summary":"Based on listings","sources":["eBay","Grailed","eBay"]}}""") as JsonObject
        val r = SoldValue.read(o)
        assertTrue(r.comps.isEmpty())
        assertEquals("${'$'}150 - ${'$'}400", r.google!!.value)
        assertEquals(listOf("eBay", "Grailed"), r.google!!.sources)
        assertNull(SoldValue.estimate(r.comps))
        val merged = SoldValue.merge(listOf(SoldValue.Reading(listOf(comp(100.0)), null), r))
        assertEquals(1, merged.comps.size)
        assertEquals("Based on listings", merged.google!!.summary)
        assertNull(SoldValue.read(Json.parseToJsonElement("""{"listings":[],"google_value":null}""") as JsonObject).google)
    }
}
