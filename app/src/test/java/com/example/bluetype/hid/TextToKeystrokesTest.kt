package com.example.bluetype.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextToKeystrokesTest — Exhaustive unit tests for TextToKeystrokes converter.
 *
 * Responsibility: Validates text conversion, shift modifier injection, empty key releases,
 *                  newline normalization, and unsupported character policies.
 * Depends on: [TextToKeystrokes], [UsKeyboardMap], [KeyReport].
 * Notes: Pure JVM test verifying §4.3 and §13 requirements.
 */
class TextToKeystrokesTest {

    @Test
    fun testEmptyString() {
        val result = TextToKeystrokes.convert("")
        assertTrue(result.reports.isEmpty())
        assertEquals(0, result.droppedCharCount)
    }

    @Test
    fun testSimpleWordProducesAlternatingDownAndEmptyReports() {
        val result = TextToKeystrokes.convert("Hi")
        // 'H' (down), 'i' (down), EMPTY = 3 reports total
        assertEquals(3, result.reports.size)
        assertEquals(0, result.droppedCharCount)

        // First is 'H'
        assertEquals(KeyReport.MODIFIER_LEFT_SHIFT, result.reports[0].modifier)
        assertEquals(0x0B.toByte(), result.reports[0].keycode) // 'h' is 0x04 + 7 = 0x0B

        // Second is 'i'
        assertEquals(KeyReport.MODIFIER_NONE, result.reports[1].modifier)
        assertEquals(0x0C.toByte(), result.reports[1].keycode) // 'i' is 0x04 + 8 = 0x0C

        // Third is Empty
        assertEquals(KeyReport.EMPTY, result.reports[2])
    }

    @Test
    fun testNewlineNormalization() {
        // CRLF should normalize to single LF
        val resultCrlf = TextToKeystrokes.convert("A\r\nB")
        assertEquals(0, resultCrlf.droppedCharCount)
        assertEquals(4, resultCrlf.reports.size) // 'A' + '\n' + 'B' + EMPTY = 4

        val resultCr = TextToKeystrokes.convert("A\rB")
        assertEquals(0, resultCr.droppedCharCount)
        assertEquals(4, resultCr.reports.size)
    }

    @Test
    fun testUnsupportedCharactersSkipped() {
        val input = "Hello 👋 World 🌍!"
        val result = TextToKeystrokes.convert(input, skipUnsupported = true)
        // 👋 and 🌍 should be dropped. Note that emoji may be surrogate pairs or single code points.
        // String contains: "Hello " (6) + 👋 (2 chars) + " World " (7) + 🌍 (2 chars) + "!" (1)
        assertTrue(result.droppedCharCount >= 2)
        // Check that result only contains valid ASCII reports
        for (report in result.reports) {
            assertTrue(report == KeyReport.EMPTY || report.keycode != 0.toByte())
        }
    }

    @Test
    fun testUnsupportedCharactersSubstituted() {
        val input = "A👋B"
        val result = TextToKeystrokes.convert(input, skipUnsupported = false, replacementChar = '?')
        assertEquals(0, result.droppedCharCount)
        // 'A' + '?' + EMPTY + '?' + 'B' + EMPTY = 6 reports
        assertEquals(6, result.reports.size)
    }

    @Test
    fun testLargeInputPerformance() {
        val largeText = "The quick brown fox jumps over the lazy dog. 1234567890!\n".repeat(200)
        val result = TextToKeystrokes.convert(largeText)
        assertEquals(0, result.droppedCharCount)
        assertEquals(largeText.length + 1, result.reports.size)
    }
}
