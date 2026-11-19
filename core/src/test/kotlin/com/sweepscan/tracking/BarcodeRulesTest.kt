package com.sweepscan.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeRulesTest {

    @Test
    fun `values read from real Derinet labels are accepted`() {
        listOf("1639069419578", "5075212470427", "1177349217488", "8437034836787", "10148017").forEach {
            assertTrue(it, BarcodeRules.isAcceptable(it))
        }
    }

    @Test
    fun `13 digit labels happen to carry a GS1 check digit, the 8 digit one does not`() {
        // Evidence that the labels are Code 128 with arbitrary numeric content, not EAN-13/EAN-8.
        assertTrue(BarcodeRules.hasValidGs1CheckDigit("1639069419578"))
        assertTrue(BarcodeRules.hasValidGs1CheckDigit("5075212470427"))
        assertFalse(BarcodeRules.hasValidGs1CheckDigit("10148017"))
    }

    @Test
    fun `strict lengths reject 12 and 14 digits, loose lengths accept them`() {
        assertFalse(BarcodeRules.isAcceptable("163906941957", BarcodeRules.STRICT_LENGTHS))
        assertFalse(BarcodeRules.isAcceptable("16390694195781", BarcodeRules.STRICT_LENGTHS))
        assertTrue(BarcodeRules.isAcceptable("163906941957", BarcodeRules.LOOSE_LENGTHS))
        assertTrue(BarcodeRules.isAcceptable("16390694195781", BarcodeRules.LOOSE_LENGTHS))
    }

    @Test
    fun `length rule is described for messages`() {
        org.junit.Assert.assertEquals("8/13", BarcodeRules.describe(BarcodeRules.STRICT_LENGTHS))
        org.junit.Assert.assertEquals("8–14", BarcodeRules.describe(BarcodeRules.LOOSE_LENGTHS))
    }

    @Test
    fun `prices, short numbers and text are rejected`() {
        listOf("3250", "ABC123456789", "", "123456789012345").forEach {
            assertFalse(it, BarcodeRules.isAcceptable(it))
        }
    }
}
