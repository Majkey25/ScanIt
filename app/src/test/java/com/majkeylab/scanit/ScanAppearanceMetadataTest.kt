package com.majkeylab.scanit

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ScanAppearanceMetadataTest {
    @Test
    fun v3MetadataRoundTripsFullNormalizedSettingsAndExactParent() {
        val settings =
            ScanAppearanceSettings(
                colorMode = ScanColorMode.Grayscale,
                colorIntensity = 140,
                grayscaleIntensity = 35,
                blackWhiteIntensity = -5,
                shadows = 45,
            )
        val encoded =
            encodeScanAppearanceMetadata(
                settings,
                PdfSizeTarget.Mb5,
                "Scan_origin",
                parentCacheId = "Scan_parent",
                parentEntryId = "00000000-0000-0000-0000-000000000001",
            )

        assertEquals(
            ScanAppearanceMetadata(
                appearance =
                    ScanAppearance(ScanColorMode.Grayscale, intensity = 35, shadows = 45),
                appearanceSettings =
                    settings.copy(
                        colorIntensity = 100,
                        blackWhiteIntensity = 0,
                    ),
                pdfSizeTarget = PdfSizeTarget.Mb5,
                lineageCacheId = "Scan_origin",
                parentCacheId = "Scan_parent",
                parentEntryId = "00000000-0000-0000-0000-000000000001",
            ),
            decodeScanAppearanceMetadata(encoded),
        )
        assertNull(decodeScanAppearanceMetadata(encoded + "extra\n".toByteArray()))
        assertNull(
            decodeScanAppearanceMetadata(
                encoded.toString(Charsets.US_ASCII)
                    .replace("derived\nScan_parent\n", "derived\n../parent\n")
                    .toByteArray(Charsets.US_ASCII),
            ),
        )
    }

    @Test
    fun legacyV2RemainsReadableButCannotAuthorizeAProvisionalCandidate() {
        val decoded =
            decodeScanAppearanceMetadata(
                "scanit-appearance-v2\ncolor\n70\n25\n10_mb\nScan_origin\n"
                    .toByteArray(Charsets.US_ASCII),
            )

        assertEquals(ScanAppearance(ScanColorMode.Color, 70, 25), decoded?.appearance)
        assertEquals(PdfSizeTarget.Mb10, decoded?.pdfSizeTarget)
        assertEquals("Scan_origin", decoded?.lineageCacheId)
        assertNull(decoded?.appearanceSettings)
        assertNull(decoded?.parentCacheId)
        assertNull(decoded?.parentEntryId)
    }

    @Test
    fun writePublishesCompleteMetadataWithoutLeavingTemporaryFile() {
        val directory = Files.createTempDirectory("scanit-appearance-metadata-").toFile()
        try {
            writeScanAppearanceMetadata(
                directory,
                ScanAppearanceSettings(),
                PdfSizeTarget.Mb10,
                "Scan_origin",
            )

            assertEquals(
                ScanAppearanceMetadata(
                    ScanAppearance(),
                    ScanAppearanceSettings(),
                    PdfSizeTarget.Mb10,
                    "Scan_origin",
                    parentCacheId = null,
                    parentEntryId = null,
                ),
                readScanAppearanceMetadata(directory, "Scan_origin"),
            )
            assertFalse(File(directory, SCAN_APPEARANCE_TEMP_FILE_NAME).exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
