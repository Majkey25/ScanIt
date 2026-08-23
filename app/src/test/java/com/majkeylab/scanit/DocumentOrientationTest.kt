package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentOrientationTest {
    @Test
    fun readableLineAnglesChoosePhysicalRotation() {
        assertEquals(
            ImageExifOrientation.Rotate180,
            selectDocumentOrientation(
                listOf(line(180f, 22), line(-178f, 18)),
                imageWidth = 1200,
                imageHeight = 1800,
            ),
        )
        assertEquals(
            ImageExifOrientation.Rotate270,
            selectDocumentOrientation(
                listOf(line(89f, 24), line(92f, 16)),
                imageWidth = 1800,
                imageHeight = 1200,
            ),
        )
        assertEquals(
            ImageExifOrientation.Rotate90,
            selectDocumentOrientation(
                listOf(line(-91f, 24), line(-88f, 16)),
                imageWidth = 1800,
                imageHeight = 1200,
            ),
        )
    }

    @Test
    fun uprightOrAmbiguousTextKeepsScannerPixelsUnchanged() {
        assertEquals(
            ImageExifOrientation.Normal,
            selectDocumentOrientation(
                listOf(line(1f, 30), line(-2f, 20)),
                imageWidth = 1800,
                imageHeight = 1200,
            ),
        )
        assertEquals(
            ImageExifOrientation.Normal,
            selectDocumentOrientation(
                listOf(line(0f, 10), line(180f, 10)),
                imageWidth = 1200,
                imageHeight = 1800,
            ),
        )
    }

    @Test
    fun noReliableTextUsesPortraitFallbackOnlyForLandscapeInput() {
        assertEquals(
            ImageExifOrientation.Normal,
            selectDocumentOrientation(
                listOf(line(90f, 3)),
                imageWidth = 1200,
                imageHeight = 1800,
            ),
        )
        assertEquals(
            ImageExifOrientation.Rotate270,
            selectDocumentOrientation(
                emptyList(),
                imageWidth = 1800,
                imageHeight = 1200,
            ),
        )
    }

    @Test
    fun scannerPageOrientationsRequireOnePhysicalRotationPerPage() {
        assertEquals(
            listOf(ImageExifOrientation.Normal, ImageExifOrientation.Rotate90),
            validatedScannerPageOrientations(
                pageCount = 2,
                orientations =
                    listOf(ImageExifOrientation.Normal, ImageExifOrientation.Rotate90),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            validatedScannerPageOrientations(2, listOf(ImageExifOrientation.Rotate180))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedScannerPageOrientations(
                1,
                listOf(ImageExifOrientation.FlipHorizontal),
            )
        }
    }

    private fun line(angleDegrees: Float, readableCharacters: Int) =
        DocumentLineOrientationEvidence(angleDegrees, readableCharacters)
}
