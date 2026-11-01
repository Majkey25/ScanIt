package com.majkeylab.scanit

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VisualMarkRenderingDeviceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val markBackgrounds = listOf(
        "yellow" to Color.rgb(255, 230, 80),
        "offwhite" to Color.rgb(246, 240, 224),
        "dark" to Color.rgb(24, 40, 56),
    )

    @Test
    fun savedPhotographsAndJpegImportsKeepInkAndPaperHolesOnColoredPages() = runBlocking {
        withIsolatedMarkStore { store, root ->
            val papers = listOf(
                "white" to Color.WHITE,
                "offwhite" to Color.rgb(240, 228, 203),
                "tinted" to Color.rgb(220, 237, 255),
            )
            for ((paperName, paper) in papers) {
                for ((stampName, stamp) in listOf("blue" to Color.BLUE, "red" to Color.RED)) {
                    val name = "$paperName-$stampName"
                    val photo = photographedMark(paper, stamp)
                    try {
                        val original = bitmapPixels(photo)
                        withSavedMark(store, store.save(photo)) { saved ->
                            assertPhotoComposites(saved, root, "$name-save")
                            assertPhotographedMark(saved, stamp, jpeg = false)
                        }
                        assertFalse(photo.isRecycled)
                        assertArrayEquals(original, bitmapPixels(photo))

                        val jpeg = File(root, "$name-input.jpg")
                        try {
                            jpeg.outputStream().use {
                                assertTrue(photo.compress(Bitmap.CompressFormat.JPEG, 90, it))
                            }
                            val encoded = jpeg.readBytes()
                            withSavedMark(store, store.import(Uri.fromFile(jpeg))) { imported ->
                                assertPhotoComposites(imported, root, "$name-jpeg-import")
                                assertPhotographedMark(imported, stamp, jpeg = true)
                            }
                            assertArrayEquals(encoded, jpeg.readBytes())
                        } finally {
                            assertTrue(jpeg.delete() || !jpeg.exists())
                        }
                    } finally {
                        photo.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun savedAndImportedTransparentPngPreservesWhitePaleAndAntialiasedInk() = runBlocking {
        withIsolatedMarkStore { store, root ->
            val source = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            val png = File(root, "transparent-input.png")
            try {
                for (y in 4..19) for (x in 4..27) {
                    source.setPixel(x, y, when (x) {
                        in 4..7 -> Color.WHITE
                        in 8..11 -> Color.argb(128, 240, 228, 204)
                        in 12..15 -> Color.argb(64, 0, 0, 192)
                        in 16..19 -> Color.TRANSPARENT
                        in 20..23 -> Color.BLACK
                        else -> Color.argb(MARK_VISIBLE_ALPHA, 0, 0, 0)
                    })
                }
                val expected = IntArray(24 * 16)
                source.getPixels(expected, 0, 24, 4, 4, 24, 16)
                withSavedMark(store, store.save(source)) { saved ->
                    assertTransparentMark(saved, expected, root, "transparent-save")
                    withSavedMark(store, store.save(saved)) { again ->
                        assertTransparentMark(again, expected, root, "transparent-resave")
                    }
                }
                source.outputStreamToPng(png)
                val encoded = png.readBytes()
                withSavedMark(store, store.import(Uri.fromFile(png))) { imported ->
                    assertTransparentMark(imported, expected, root, "transparent-import")
                }
                assertArrayEquals(encoded, png.readBytes())
                assertFalse(source.isRecycled)
            } finally {
                source.recycle()
                assertTrue(png.delete() || !png.exists())
            }
        }
    }

    @Test
    fun savedPngKeepsLowPaperChannelsBrightStampColorAndFaintPencil() {
        withIsolatedMarkStore { store, _ ->
            val cases = listOf(
                0xFF104080.toInt() to Color.BLACK,
                0xFF001080.toInt() to 0xFF200040.toInt(),
                0xFFFFFF00.toInt() to Color.BLUE,
                0xFF808080.toInt() to 0xFFFF6464.toInt(),
                0xFFE0D0C0.toInt() to 0xFFD2C3B4.toInt(),
            )
            for ((paper, ink) in cases) {
                val source = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
                try {
                    source.eraseColor(paper)
                    val canvas = Canvas(source)
                    val paint = Paint().apply { color = Color.BLACK }
                    canvas.drawRect(24f, 16f, 72f, 48f, paint)
                    paint.color = paper
                    canvas.drawRect(28f, 20f, 68f, 44f, paint)
                    paint.color = ink
                    canvas.drawRect(32f, 24f, 36f, 28f, paint)
                    withSavedMark(store, store.save(source)) { saved ->
                        assertEquals(48, saved.width)
                        assertEquals(32, saved.height)
                        assertEquals("Paper/hole retained alpha", 0, Color.alpha(saved.getPixel(24, 16)))
                        val decodedInk = saved.getPixel(9, 9)
                        assertTrue("Saved ink disappeared", Color.alpha(decodedInk) >= MARK_VISIBLE_ALPHA)
                        if (ink == 0xFFD2C3B4.toInt()) assertEquals(MARK_VISIBLE_ALPHA, Color.alpha(decodedInk))
                        val composite = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
                        try {
                            composite.eraseColor(paper)
                            Canvas(composite).drawBitmap(saved, 0f, 0f, null)
                            assertEquals(paper, composite.getPixel(24, 16))
                            val reconstructed = composite.getPixel(9, 9)
                            for (shift in listOf(16, 8, 0)) {
                                assertEquals(
                                    "Saved PNG clipped channel $shift on ${Integer.toHexString(paper)}",
                                    (ink ushr shift and 255).toDouble(),
                                    (reconstructed ushr shift and 255).toDouble(),
                                    2.0,
                                )
                            }
                        } finally {
                            composite.recycle()
                        }
                    }
                } finally {
                    source.recycle()
                }
            }
        }
    }

    @Test
    fun largeSolidStampKeepsItsInteriorAndTransparentLetterHole() {
        val source = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
        val paper = Color.rgb(220, 210, 200)
        source.eraseColor(paper)
        val canvas = Canvas(source)
        val paint = Paint().apply { color = Color.rgb(68, 68, 68) }
        canvas.drawRect(400f, 400f, 1600f, 1100f, paint)
        paint.color = paper
        canvas.drawRect(600f, 600f, 800f, 900f, paint)
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            val result = normalizeMarkBitmap(source)
            try {
                assertEquals(1024, result.width)
                assertEquals(597, result.height)
                assertTrue(Color.alpha(result.getPixel(100, 100)) >= 64)
                assertEquals(0, Color.alpha(result.getPixel(256, 256)))
                println("MARK_EXTRACTION_2048_MS=${android.os.SystemClock.elapsedRealtime() - started}")
            } finally {
                result.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun normalizedPhotoRemovesShadedPaperAndKeepsColoredInkWithoutHalo() {
        val width = 192
        val height = 128
        val pixels = IntArray(width * height) { index ->
            val shade = 120 + 80 * (index % width) / (width - 1) + 30 * (index / width) / (height - 1)
            Color.rgb(shade, shade - 8, shade - 16)
        }
        for (y in 20..100) {
            pixels[y * width + 40] = Color.BLACK
            pixels[y * width + 90] = Color.BLUE
            pixels[y * width + 140] = Color.RED
        }
        pixels[60 * width + 41] = Color.rgb(80, 76, 72)
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            val normalized = normalizeMarkBitmap(source)
            try {
                assertEquals(101, normalized.width)
                assertEquals(81, normalized.height)
                assertEquals(Color.BLACK, normalized.getPixel(0, 40))
                assertEquals(Color.BLUE, normalized.getPixel(50, 40))
                assertEquals(Color.RED, normalized.getPixel(100, 40))
                assertEquals(0, Color.alpha(normalized.getPixel(25, 40)))
                val edge = normalized.getPixel(1, 40)
                assertTrue(Color.alpha(edge) in 100..160)
                assertTrue(Color.red(edge) < 12 && Color.green(edge) < 12 && Color.blue(edge) < 12)
                val dark = Bitmap.createBitmap(101, 81, Bitmap.Config.ARGB_8888)
                try {
                    val background = Color.rgb(24, 40, 56)
                    dark.eraseColor(background)
                    Canvas(dark).drawBitmap(normalized, 0f, 0f, null)
                    assertEquals(background, dark.getPixel(25, 40))
                    val renderedEdge = dark.getPixel(1, 40)
                    assertTrue(Color.red(renderedEdge) < Color.red(background))
                    assertTrue(Color.green(renderedEdge) < Color.green(background))
                    assertTrue(Color.blue(renderedEdge) < Color.blue(background))
                } finally {
                    dark.recycle()
                }
            } finally {
                normalized.recycle()
            }
            assertArrayEquals(pixels, bitmapPixels(source))
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun blankTintedBitmapIsRejectedEvenWhenItsFormatSupportsAlpha() {
        val source = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.rgb(220, 237, 255))
            assertTrue(source.hasAlpha())
            val error = assertThrows(IOException::class.java) { normalizeMarkBitmap(source) }
            assertEquals("The selected mark has no visible ink", error.message)
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun pretransparentPngAndDrawnSignatureKeepTheirAlphaAndRgbWhenNormalizedAgain() {
        val source = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        try {
            for (y in 4..7) {
                source.setPixel(5, y, Color.WHITE)
                source.setPixel(6, y, Color.argb(128, 240, 228, 204))
                source.setPixel(7, y, Color.argb(64, 0, 0, 192))
                source.setPixel(8, y, Color.BLACK)
            }
            val png = temporaryFolder.newFile("transparent-mark.png")
            png.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = requireNotNull(BitmapFactory.decodeFile(png.path))
            try {
                val normalized = normalizeMarkBitmap(decoded)
                try {
                    assertEquals(4, normalized.width)
                    assertEquals(4, normalized.height)
                    for (y in 0..3) for (x in 0..3) {
                        assertEquals(decoded.getPixel(x + 5, y + 4), normalized.getPixel(x, y))
                    }
                } finally {
                    normalized.recycle()
                }
            } finally {
                decoded.recycle()
            }
            val drawn = renderDrawnMark(listOf(MarkStroke(listOf(MarkPoint(0.2f, 0.5f), MarkPoint(0.8f, 0.5f)))))
            try {
                val normalized = normalizeMarkBitmap(drawn)
                try {
                    val again = normalizeMarkBitmap(normalized)
                    try {
                        assertEquals(normalized.width, again.width)
                        assertEquals(normalized.height, again.height)
                        assertArrayEquals(bitmapPixels(normalized), bitmapPixels(again))
                    } finally {
                        again.recycle()
                    }
                } finally {
                    normalized.recycle()
                }
            } finally {
                drawn.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun exportedLineHasSquareCapsWhileBrushHasRoundCapsAndSourceRemainsUnchanged() {
        val source = createSource()
        val original = source.readBytes()
        val baseline = requireNotNull(BitmapFactory.decodeFile(source.path))
        try {
            assertPixelChannels(baseline, 512, 256, 240..255)
            assertPixelChannels(baseline, 212, 212, 240..255)
            assertPixelChannels(baseline, 812, 300, 240..255)
            assertPixelChannels(baseline, 96, 800, 0..20)

            for (tool in listOf(RedactionTool.Line, RedactionTool.Brush)) {
                val destination = File(temporaryFolder.root, "${tool.name}.jpg")
                assertEquals(
                    JpegDimensions(1024, 1024),
                    renderRedactedJpeg(
                        source = source,
                        destination = destination,
                        regions = emptyList(),
                        strokes = listOf(
                            RedactionStroke(
                                points = listOf(MarkPoint(0.25f, 0.25f), MarkPoint(0.75f, 0.25f)),
                                widthFraction = 0.10f,
                                tool = tool,
                            ),
                        ),
                        isCancelled = { false },
                    ),
                )
                val decoded = requireNotNull(BitmapFactory.decodeFile(destination.path))
                try {
                    assertEquals(1024, decoded.width)
                    assertEquals(1024, decoded.height)
                    assertPixelChannels(decoded, 512, 256, 0..20)
                    assertPixelChannels(decoded, 212, 256, 0..20)
                    // Both corners lie inside square caps but outside round caps.
                    val cornerChannels = if (tool == RedactionTool.Line) 0..20 else 240..255
                    assertPixelChannels(decoded, 212, 212, cornerChannels)
                    assertPixelChannels(decoded, 812, 300, cornerChannels)
                    for ((x, y) in listOf(160 to 256, 512 to 160, 512 to 352, 96 to 800, 160 to 800)) {
                        assertEquals(
                            "$tool changed an unmarked pixel ($x,$y)",
                            baseline.getPixel(x, y),
                            decoded.getPixel(x, y),
                        )
                    }
                } finally {
                    decoded.recycle()
                }
                assertArrayEquals(original, source.readBytes())
            }
            assertEquals(
                setOf("source.jpg", "Line.jpg", "Brush.jpg"),
                requireNotNull(temporaryFolder.root.list()).toSet(),
            )
        } finally {
            baseline.recycle()
        }
    }

    @Test
    fun rotatedMarkReachesRightEdgeWithoutShrinkingAndPreservesSource() {
        val source = createSource()
        val original = source.readBytes()
        val mark = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        try {
            mark.eraseColor(Color.BLUE)
            for (rotation in listOf(-90f, 90f)) {
                val destination = File(temporaryFolder.root, "mark-$rotation.jpg")
                renderMarkedSourceJpeg(
                    source, destination, mark,
                    MarkPlacement(centerX = 1f, centerY = 0.5f, widthFraction = 0.5f, rotationDegrees = rotation),
                    isCancelled = { false },
                )
                val decoded = requireNotNull(BitmapFactory.decodeFile(destination.path))
                try {
                    for (y in listOf(280, 512, 744)) {
                        val pixel = decoded.getPixel(992, y)
                        assertTrue(Color.blue(pixel) > 230 && Color.red(pixel) < 25 && Color.green(pixel) < 25)
                    }
                    assertPixelChannels(decoded, 704, 512, 240..255)
                } finally {
                    decoded.recycle()
                }
            }
            assertArrayEquals(original, source.readBytes())
        } finally {
            mark.recycle()
        }
    }

    @Test
    fun thinRedactionPreviewDoesNotExceedScaledExportCoverage() {
        val points = listOf(MarkPoint(0.25f, 0.5f), MarkPoint(0.75f, 0.5f))
        for (tool in RedactionTool.entries) {
            val bitmap = Bitmap.createBitmap(60, 600, Bitmap.Config.ARGB_8888)
            try {
                androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                    density = androidx.compose.ui.unit.Density(1f),
                    layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
                    canvas = androidx.compose.ui.graphics.Canvas(Canvas(bitmap)),
                    size = androidx.compose.ui.geometry.Size(60f, 600f),
                ) {
                    drawRedactionStroke(points, 0.01f, tool, 0f, 0f, 60f, 600f)
                }
                val exported = redactionPixelStrokes(
                    600, 6_000, listOf(RedactionStroke(points, 0.01f, tool)),
                ).single()
                val previewCoverage = (0 until bitmap.height).sumOf { Color.alpha(bitmap.getPixel(30, it)) }
                assertEquals(exported.width * 0.1f * 255f, previewCoverage.toFloat(), 8f)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private inline fun withIsolatedMarkStore(block: (MarkTemplateStore, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName == "com.majkeylab.scanit.internal") {
            "Mark device tests may only target the isolated internal app"
        }
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "mark-alpha-qa-").toFile()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = root
            override fun getNoBackupFilesDir(): File = root
        }
        // Keep only this run's QA composites for later inspection; never use the user's mark store.
        println("MARK_ALPHA_QA_DIR=${root.absolutePath}")
        block(MarkTemplateStore(context), root)
    }

    private inline fun withSavedMark(store: MarkTemplateStore, id: String, block: (Bitmap) -> Unit) {
        try {
            val loaded = requireNotNull(store.load(id)) { "Saved mark $id could not be decoded" }
            try {
                assertTrue("Stored PNG lost its alpha channel", loaded.hasAlpha())
                block(loaded)
            } finally {
                loaded.recycle()
            }
        } finally {
            assertTrue("Own test mark $id was not deleted", store.delete(id))
        }
    }

    private fun photographedMark(paper: Int, stamp: Int): Bitmap {
        // Model smooth lighting on plain paper, not arbitrary texture or isolated interior shadows.
        val paperPixels = IntArray(192 * 128) { index ->
            val shade = 8 * (index % 192) / 191 + 4 * (index / 192) / 127
            Color.rgb(Color.red(paper) - shade, Color.green(paper) - shade, Color.blue(paper) - shade)
        }
        val bitmap = Bitmap.createBitmap(192, 128, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(paperPixels, 0, 192, 0, 0, 192, 128)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawRect(40f, 20f, 48f, 100f, paint)
        paint.alpha = 128
        canvas.drawRect(48f, 20f, 52f, 100f, paint)
        paint.alpha = 48
        canvas.drawRect(60f, 88f, 84f, 92f, paint)
        paint.alpha = 255
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        canvas.drawPath(Path().apply {
            moveTo(44f, 60f)
            quadTo(66f, 22f, 76f, 60f)
            quadTo(66f, 90f, 88f, 60f)
        }, paint)
        paint.style = Paint.Style.FILL
        paint.color = stamp
        canvas.drawRect(104f, 20f, 152f, 100f, paint)
        bitmap.setPixels(paperPixels, 40 * 192 + 116, 192, 116, 40, 24, 40)
        return bitmap
    }

    private fun assertPhotographedMark(mark: Bitmap, stamp: Int, jpeg: Boolean) {
        if (jpeg) {
            // Allow JPEG ringing around the known ink bounds, not a retained 192 x 128 paper rectangle.
            assertTrue("JPEG mark width was ${mark.width}", mark.width in 112..128)
            assertTrue("JPEG mark height was ${mark.height}", mark.height in 80..96)
        } else {
            assertEquals(112, mark.width)
            assertEquals(80, mark.height)
            val edge = mark.getPixel(9, 8)
            assertTrue("Antialias alpha was ${Color.alpha(edge)}", Color.alpha(edge) in 126..130)
            assertTrue("Paper RGB remained in antialias ink", maxRgb(edge) <= 4)
            val pencil = mark.getPixel(30, 70)
            assertTrue("Faint pencil disappeared", Color.alpha(pencil) in 40..56)
            assertTrue("Paper RGB remained in faint pencil", maxRgb(pencil) <= 12)
        }
        val stampPixel = mark.getPixel(mark.width * 11 / 14, mark.height * 4 / 25)
        assertTrue("Stamp interior became transparent", Color.alpha(stampPixel) >= 192)
        if (stamp == Color.BLUE) {
            assertTrue(Color.blue(stampPixel) >= 200 && Color.red(stampPixel) <= 48 && Color.green(stampPixel) <= 48)
        } else {
            assertTrue(Color.red(stampPixel) >= 200 && Color.blue(stampPixel) <= 48 && Color.green(stampPixel) <= 48)
        }
        var antialiasPixels = 0
        for (y in 0 until mark.height) for (x in 0 until mark.width / 5) {
            val pixel = mark.getPixel(x, y)
            if (Color.alpha(pixel) in 64..192) {
                assertTrue("Light matte at $x,$y: ${Integer.toHexString(pixel)}", maxRgb(pixel) <= if (jpeg) 48 else 4)
                antialiasPixels++
            }
        }
        assertTrue("Antialiased signature edge disappeared", antialiasPixels >= 32)
        var pencilPixels = 0
        for (y in mark.height * 4 / 5 until mark.height * 19 / 20) {
            for (x in mark.width / 5 until mark.width * 2 / 5) {
                val pixel = mark.getPixel(x, y)
                if (Color.alpha(pixel) in 24..72 && maxRgb(pixel) <= 64) pencilPixels++
            }
        }
        assertTrue("Faint pencil did not survive storage/import", pencilPixels >= 8)
    }

    private fun assertPhotoComposites(mark: Bitmap, root: File, name: String) {
        val paperPoints = listOf(mark.width * 5 / 14 to mark.height / 10, mark.width * 11 / 14 to mark.height / 2)
        for ((backgroundName, background) in markBackgrounds) {
            withQaComposite(mark, background, root, "$name-$backgroundName") { composite ->
                assertEquals(background, composite.getPixel(0, 0))
                for ((x, y) in paperPoints) {
                    assertEquals("Paper/hole at $x,$y retained alpha", 0, Color.alpha(mark.getPixel(x, y)))
                    assertEquals("Paper rectangle on $backgroundName", background, composite.getPixel(x + 16, y + 16))
                }
                val page = Bitmap.createBitmap(composite.width, composite.height, Bitmap.Config.ARGB_8888)
                val source = File(root, "$name-$backgroundName-page.jpg")
                val destination = File(root, "$name-$backgroundName-rendered.jpg")
                try {
                    page.eraseColor(background)
                    source.outputStream().use { assertTrue(page.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                    val original = source.readBytes()
                    renderMarkedSourceJpeg(
                        source, destination, mark,
                        MarkPlacement(centerX = 0.5f, centerY = 0.5f, widthFraction = mark.width.toFloat() / page.width),
                        isCancelled = { false },
                    )
                    assertArrayEquals(original, source.readBytes())
                    val rendered = requireNotNull(BitmapFactory.decodeFile(destination.path))
                    try {
                        rendered.outputStreamToPng(File(root, "$name-$backgroundName-rendered.png"))
                        assertEquals(composite.width, rendered.width)
                        assertEquals(composite.height, rendered.height)
                        val stampPoint = mark.width * 11 / 14 to mark.height * 4 / 25
                        // Check known interiors with JPEG tolerance; do not require a lossless JPEG page.
                        for ((x, y) in paperPoints + stampPoint) {
                            val expected = composite.getPixel(x + 16, y + 16)
                            val actual = rendered.getPixel(x + 16, y + 16)
                            for (shift in listOf(16, 8, 0)) {
                                assertEquals(
                                    "$name on $backgroundName at $x,$y, channel $shift",
                                    (expected ushr shift and 255).toDouble(),
                                    (actual ushr shift and 255).toDouble(),
                                    24.0,
                                )
                            }
                        }
                    } finally {
                        rendered.recycle()
                    }
                } finally {
                    page.recycle()
                    assertTrue(source.delete() || !source.exists())
                    assertTrue(destination.delete() || !destination.exists())
                }
            }
        }
    }

    private fun assertTransparentMark(mark: Bitmap, expected: IntArray, root: File, name: String) {
        assertEquals(24, mark.width)
        assertEquals(16, mark.height)
        assertArrayEquals(expected, bitmapPixels(mark))
        for ((backgroundName, background) in markBackgrounds) {
            withQaComposite(mark, background, root, "$name-$backgroundName") { composite ->
                assertEquals(background, composite.getPixel(30, 24))
                for (x in listOf(1, 5, 9, 17, 21)) {
                    val ink = expected[8 * 24 + x]
                    val actual = composite.getPixel(x + 16, 24)
                    for (shift in listOf(16, 8, 0)) {
                        val alpha = Color.alpha(ink)
                        val expectedChannel =
                            ((ink ushr shift and 255) * alpha + (background ushr shift and 255) * (255 - alpha) + 127) / 255
                        assertEquals("$name on $backgroundName at $x, channel $shift", expectedChannel.toDouble(), (actual ushr shift and 255).toDouble(), 1.0)
                    }
                }
            }
        }
    }

    private inline fun withQaComposite(mark: Bitmap, background: Int, root: File, name: String, check: (Bitmap) -> Unit) {
        val composite = Bitmap.createBitmap(mark.width + 32, mark.height + 32, Bitmap.Config.ARGB_8888)
        try {
            composite.eraseColor(background)
            Canvas(composite).drawBitmap(mark, 16f, 16f, null)
            composite.outputStreamToPng(File(root, "$name.png"))
            check(composite)
        } finally {
            composite.recycle()
        }
    }

    private fun Bitmap.outputStreamToPng(file: File) {
        file.outputStream().use { assertTrue(compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun maxRgb(pixel: Int): Int = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))

    private fun bitmapPixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun createSource(): File {
        val source = temporaryFolder.newFile("source.jpg")
        val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawRect(64f, 768f, 128f, 832f, Paint().apply { color = Color.BLACK })
            source.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        return source
    }

    private fun assertPixelChannels(bitmap: Bitmap, x: Int, y: Int, expected: IntRange) {
        val pixel = bitmap.getPixel(x, y)
        assertTrue(
            "Pixel ($x,$y) was ${Integer.toHexString(pixel)}, expected RGB channels in $expected",
            Color.red(pixel) in expected && Color.green(pixel) in expected && Color.blue(pixel) in expected,
        )
    }
}
