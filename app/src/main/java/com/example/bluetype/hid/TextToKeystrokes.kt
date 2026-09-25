package com.example.bluetype.hid

/**
 * TextToKeystrokes — Pure function mapping arbitrary text to HID keyboard report sequences.
 *
 * Responsibility: Converts plain text into timed HID keypress/release reports, accounting for unsupported characters.
 * Depends on: [KeyReport], [UsKeyboardMap].
 * Notes: Zero Android framework dependencies to allow fast, isolated JVM unit testing (§5 & §8).
 */
object TextToKeystrokes {

    /**
     * Result of converting text to keystrokes.
     *
     * @property reports Ordered list of HID key reports (alternating key-down and key-up/empty).
     * @property droppedCharCount Count of characters skipped due to lack of HID representation.
     */
    data class ConversionResult(
        val reports: List<KeyReport>,
        val droppedCharCount: Int
    )

    /**
     * Converts an input text string into a list of [KeyReport]s ready for HID transmission.
     * Handles newline normalization (\r\n -> \n, \r -> \n).
     *
     * @param text Raw text input from clipboard.
     * @param skipUnsupported Whether to skip characters not present in [UsKeyboardMap].
     * @param replacementChar Character to substitute when [skipUnsupported] is false and char is unsupported.
     * @return [ConversionResult] containing reports sequence and dropped count.
     */
    fun convert(
        text: String,
        skipUnsupported: Boolean = true,
        replacementChar: Char = '?'
    ): ConversionResult {
        if (text.isEmpty()) {
            return ConversionResult(emptyList(), 0)
        }

        // Normalize Windows/Mac line endings to standard Unix line feed
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val reports = ArrayList<KeyReport>(normalized.length * 2)
        var droppedCount = 0

        var previousReport: KeyReport? = null

        for (char in normalized) {
            val report = UsKeyboardMap.getReport(char)
            if (report != null) {
                if (previousReport != null && previousReport.keycode == report.keycode) {
                    reports.add(KeyReport.EMPTY)
                }
                reports.add(report)
                previousReport = report
            } else {
                if (skipUnsupported) {
                    droppedCount++
                } else {
                    val replacementReport = UsKeyboardMap.getReport(replacementChar)
                    if (replacementReport != null) {
                        if (previousReport != null && previousReport.keycode == replacementReport.keycode) {
                            reports.add(KeyReport.EMPTY)
                        }
                        reports.add(replacementReport)
                        previousReport = replacementReport
                    } else {
                        droppedCount++
                    }
                }
            }
        }

        if (reports.isNotEmpty() && reports.last() != KeyReport.EMPTY) {
            reports.add(KeyReport.EMPTY)
        }

        return ConversionResult(reports = reports, droppedCharCount = droppedCount)
    }
}
