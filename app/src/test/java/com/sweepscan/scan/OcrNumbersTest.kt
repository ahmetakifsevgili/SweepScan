package com.sweepscan.scan

import com.sweepscan.tracking.BarcodeRules
import com.sweepscan.tracking.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrNumbersTest {

    @Test
    fun findsTheBarcodeNumberAmongTheLabelText() {
        // What text recognition returns for a label: model code, colour, size, then the number.
        val lines = listOf("0159 2041", "SİYAH", "38", "5895214670690")
        assertEquals(listOf("5895214670690"), OcrEngine.numbers(lines))
    }

    @Test
    fun joinsDigitGroupsOfThirteenButNotOfEight() {
        assertEquals(listOf("5895214670690"), OcrEngine.numbers(listOf("5 895214 670690")))
        assertEquals(listOf("12345670"), OcrEngine.numbers(listOf("12345670")))
        // The model code, printed with a space, is not an 8-digit barcode.
        assertEquals(emptyList<String>(), OcrEngine.numbers(listOf("0159 2041")))
    }

    @Test
    fun ignoresRunsOfOtherLengths() {
        assertTrue(OcrEngine.numbers(listOf("01592041", "123456789012", "38")).none { it.length == 12 })
    }

    @Test
    fun aSingleWrongDigitFailsTheCheckDigit() {
        // The misread seen on the phone (16 Nov) and its true value.
        assertTrue(BarcodeRules.hasValidGs1CheckDigit("5895214670690"))
        assertEquals(false, BarcodeRules.hasValidGs1CheckDigit("5895214670360"))
        for (i in 0 until 12) {
            val digits = "5895214670690".toCharArray()
            digits[i] = '0' + ((digits[i] - '0' + 1) % 10)
            assertEquals("changed digit $i", false, BarcodeRules.hasValidGs1CheckDigit(String(digits)))
        }
    }

    @Test
    fun regionCoversTheBarsAndTheDigitsBelow() {
        val scanLine = Box(100f, 500f, 400f, 510f) // a thin scan line, as ML Kit returns on the phone
        val r = OcrEngine.region(scanLine)
        assertTrue(r.top < 400f && r.bottom > 690f && r.left < 100f && r.right > 400f)
    }
}
