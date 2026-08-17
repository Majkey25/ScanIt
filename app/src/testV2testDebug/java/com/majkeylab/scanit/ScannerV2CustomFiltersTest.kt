package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ScannerV2CustomFiltersTest {
    @Test
    fun customFilterRequiresSafeIdentityNameAndEditableAppearance() {
        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2CustomFilter("not-a-uuid", "Notes", colorAppearance())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2CustomFilter(ID_ONE, "  ", colorAppearance())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2CustomFilter(ID_ONE, "Bad\nname", colorAppearance())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2CustomFilter(ID_ONE, "A".repeat(25), colorAppearance())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2CustomFilter(ID_ONE, "Original", ScannerV2Appearance.original())
        }
    }

    @Test
    fun upsertTrimsNewNameAndUpdatesCaseInsensitiveMatchInPlace() {
        val created = upsertScannerV2CustomFilter(
            current = emptyList(),
            rawName = "  School notes  ",
            appearance = colorAppearance(),
            newId = ID_ONE,
        )
        assertEquals(
            listOf(ScannerV2CustomFilter(ID_ONE, "School notes", colorAppearance())),
            created,
        )

        val updated = upsertScannerV2CustomFilter(
            current = created,
            rawName = "school NOTES",
            appearance = grayscaleAppearance(),
            newId = ID_TWO,
        )
        assertEquals(
            listOf(ScannerV2CustomFilter(ID_ONE, "school NOTES", grayscaleAppearance())),
            updated,
        )
    }

    @Test
    fun capacityBlocksOnlyANinthDistinctName() {
        val full = (0 until MAX_SCANNER_V2_CUSTOM_FILTERS).map { index ->
            ScannerV2CustomFilter(
                id = "00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}",
                name = "Preset ${index + 1}",
                appearance = colorAppearance(),
            )
        }
        assertEquals(
            ScannerV2CustomFilterSaveError.CapacityReached,
            scannerV2CustomFilterSaveError(full, "Ninth", colorAppearance()),
        )
        assertNull(scannerV2CustomFilterSaveError(full, "preset 1", colorAppearance()))
        assertThrows(IllegalArgumentException::class.java) {
            upsertScannerV2CustomFilter(full, "Ninth", colorAppearance(), ID_NINE)
        }
    }

    @Test
    fun deleteRemovesOnlyTheExactPreset() {
        val first = ScannerV2CustomFilter(ID_ONE, "Notes", colorAppearance())
        val second = ScannerV2CustomFilter(ID_TWO, "Drawing", drawingAppearance())

        assertEquals(
            listOf(second),
            deleteScannerV2CustomFilter(listOf(first, second), ID_ONE),
        )
        assertEquals(
            listOf(first, second),
            deleteScannerV2CustomFilter(listOf(first, second), ID_NINE),
        )
    }

    @Test
    fun strictCodecRoundTripsExactOrderedPresets() {
        val presets = listOf(
            ScannerV2CustomFilter(ID_ONE, "Notes", colorAppearance()),
            ScannerV2CustomFilter(ID_TWO, "Drawing", drawingAppearance()),
        )

        assertEquals(presets, decodeScannerV2CustomFilters(encodeScannerV2CustomFilters(presets)))
    }

    @Test
    fun strictCodecRejectsUnknownDuplicateInvalidAndOversizedData() {
        val duplicateName =
            """{"version":1,"presets":[{"id":"$ID_ONE","name":"Notes","filterId":"color","intensity":80,"shadows":12},{"id":"$ID_TWO","name":"notes","filterId":"drawing","intensity":100,"shadows":0}]}"""
        val unknownKey =
            """{"version":1,"presets":[],"extra":true}"""
        val invalidFilter =
            """{"version":1,"presets":[{"id":"$ID_ONE","name":"Notes","filterId":"missing","intensity":80,"shadows":12}]}"""

        assertNull(decodeScannerV2CustomFilters(duplicateName))
        assertNull(decodeScannerV2CustomFilters(unknownKey))
        assertNull(decodeScannerV2CustomFilters(invalidFilter))
        assertNull(decodeScannerV2CustomFilters("x".repeat(MAX_SCANNER_V2_CUSTOM_FILTER_BYTES + 1)))
    }

    @Test
    fun saveErrorExplainsInvalidNamesBeforeStorage() {
        assertEquals(
            ScannerV2CustomFilterSaveError.InvalidName,
            scannerV2CustomFilterSaveError(emptyList(), "\n", colorAppearance()),
        )
        assertEquals(
            ScannerV2CustomFilterSaveError.InvalidName,
            scannerV2CustomFilterSaveError(emptyList(), "A".repeat(25), colorAppearance()),
        )
        assertEquals(
            ScannerV2CustomFilterSaveError.UnsupportedAppearance,
            scannerV2CustomFilterSaveError(emptyList(), "Original", ScannerV2Appearance.original()),
        )
    }

    @Test
    fun matchingNameIsAnExplicitReplacementNotANewPreset() {
        val current = listOf(ScannerV2CustomFilter(ID_ONE, "Notes", colorAppearance()))

        assertEquals("Notes", scannerV2CustomFilterReplacementName(current, " notes "))
        assertNull(scannerV2CustomFilterReplacementName(current, "Drawing"))
    }

    @Test
    fun loadResultSeparatesMissingValidAndUnreadablePreferences() {
        assertEquals(
            ScannerV2CustomFilterLoadResult.Loaded(emptyList()),
            scannerV2CustomFilterLoadResult(null),
        )
        val presets = listOf(ScannerV2CustomFilter(ID_ONE, "Notes", colorAppearance()))
        assertEquals(
            ScannerV2CustomFilterLoadResult.Loaded(presets),
            scannerV2CustomFilterLoadResult(encodeScannerV2CustomFilters(presets)),
        )
        assertEquals(
            ScannerV2CustomFilterLoadResult.Unreadable,
            scannerV2CustomFilterLoadResult("broken"),
        )
        assertEquals(
            ScannerV2CustomFilterLoadResult.Unreadable,
            scannerV2CustomFilterLoadResult(1),
        )
    }

    @Test
    fun strictCodecRejectsEverySchemaAndBoundViolation() {
        val validPreset =
            """{"id":"$ID_ONE","name":"Notes","filterId":"color","intensity":80,"shadows":12}"""
        val secondPreset =
            """{"id":"$ID_TWO","name":"Drawing","filterId":"drawing","intensity":100,"shadows":0}"""
        val ninePresets = (1..9).joinToString(",") { index ->
            """{"id":"00000000-0000-0000-0000-${index.toString().padStart(12, '0')}","name":"P$index","filterId":"color","intensity":80,"shadows":12}"""
        }
        val invalid = listOf(
            """{"version":"1","presets":[$validPreset]}""",
            """{"version":2,"presets":[$validPreset]}""",
            """{"version":1,"presets":[$validPreset,$validPreset]}""",
            """{"version":1,"presets":[$validPreset,${secondPreset.replace(ID_TWO, ID_ONE)}]}""",
            """{"version":1,"presets":[$ninePresets]}""",
            """{"version":1,"presets":[{"id":1,"name":"Notes","filterId":"color","intensity":80,"shadows":12}]}""",
            """{"version":1,"presets":[{"id":"$ID_ONE","name":1,"filterId":"color","intensity":80,"shadows":12}]}""",
            """{"version":1,"presets":[{"id":"$ID_ONE","name":"Notes","filterId":"color","intensity":"80","shadows":12}]}""",
            """{"version":1,"presets":[{"id":"$ID_ONE","name":"Notes","filterId":"color","intensity":999,"shadows":12}]}""",
            """{"version":1,"presets":[{"id":"$ID_ONE","name":"Notes","filterId":"color","intensity":80}]}""",
        )

        invalid.forEach { raw -> assertNull(raw, decodeScannerV2CustomFilters(raw)) }
    }

    @Test
    fun upsertRejectsOriginalEvenWhenNameIsValid() {
        assertThrows(IllegalArgumentException::class.java) {
            upsertScannerV2CustomFilter(
                current = emptyList(),
                rawName = "Original",
                appearance = ScannerV2Appearance.original(),
                newId = ID_ONE,
            )
        }
    }

    private fun colorAppearance() = ScannerV2Appearance(
        ScannerV2Filter.Color,
        intensity = 80,
        shadows = 12,
    )

    private fun grayscaleAppearance() = ScannerV2Appearance(
        ScannerV2Filter.Grayscale,
        intensity = 64,
        shadows = 20,
    )

    private fun drawingAppearance() = ScannerV2Appearance.defaultFor(ScannerV2Filter.Drawing)

    private companion object {
        const val ID_ONE = "00000000-0000-0000-0000-000000000001"
        const val ID_TWO = "00000000-0000-0000-0000-000000000002"
        const val ID_NINE = "00000000-0000-0000-0000-000000000009"
    }
}
