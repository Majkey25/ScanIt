package com.majkeylab.scanit

import android.graphics.Bitmap
import android.content.ContextWrapper
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryCropTransformTest {
    @Test
    fun discardRejectsDanglingRootAndSessionLinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "gallery-links-").toFile()
        val isolated = object : ContextWrapper(context) {
            override fun getCacheDir(): File = root
        }
        val session = UUID.randomUUID().toString()
        val galleryRoot = File(root.canonicalFile, "gallery-crop")
        val missing = File(root.canonicalFile, "missing")
        try {
            Files.createSymbolicLink(galleryRoot.toPath(), missing.toPath())
            assertThrows(IOException::class.java) { galleryCropDirectory(isolated, session) }
            assertThrows(IOException::class.java) { discardGalleryCropSession(isolated, session) }
            assertTrue(Files.exists(galleryRoot.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertFalse(missing.exists())
            Files.delete(galleryRoot.toPath())
            val directory = galleryCropDirectory(isolated, session)
            Files.delete(directory.toPath())
            Files.createSymbolicLink(directory.toPath(), missing.toPath())
            assertThrows(IOException::class.java) { galleryCropDirectory(isolated, session) }
            assertThrows(IOException::class.java) { discardGalleryCropSession(isolated, session) }
            assertTrue(Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertFalse(missing.exists())
        } finally {
            deleteTreeWithoutFollowingLinks(root)
        }
    }

    @Test
    fun discardRemovesOnlyOwnedSessionAndDoesNotCreateMissingPaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "gallery-cleanup-").toFile()
        val isolated = object : ContextWrapper(context) {
            override fun getCacheDir(): File = root
        }
        val first = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        try {
            assertTrue(discardGalleryCropSession(isolated, first))
            assertFalse(File(root, "gallery-crop").exists())
            val discarded = galleryCropDirectory(isolated, first)
            File(discarded, "source_0").writeBytes(byteArrayOf(1, 2, 3))
            val survivor = File(galleryCropDirectory(isolated, other), "source_0")
            survivor.writeBytes(byteArrayOf(4, 5, 6))
            assertTrue(discardGalleryCropSession(isolated, first))
            assertTrue(discardGalleryCropSession(isolated, first))
            assertFalse(discarded.exists())
            assertArrayEquals(byteArrayOf(4, 5, 6), survivor.readBytes())
        } finally {
            deleteTreeWithoutFollowingLinks(root)
        }
    }

    @Test
    fun fullImageApplyFlattensTransparentPixelsOntoWhite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "gallery-alpha-").toFile()
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        var output: Bitmap? = null
        try {
            source.eraseColor(Color.TRANSPARENT)
            for (y in 24 until 40) for (x in 24 until 40) source.setPixel(x, y, Color.BLUE)
            File(directory, "source_0").outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            renderGalleryCrop(directory, 0, fullGalleryCrop()) { false }
            val rendered = decodeGalleryCropBitmap(File(directory, "page_0.jpg"))
            output = rendered
            val background = rendered.getPixel(4, 4)
            assertTrue(Color.red(background) >= 245 && Color.green(background) >= 245 && Color.blue(background) >= 245)
            assertTrue(Color.blue(rendered.getPixel(32, 32)) >= 245)
            assertEquals(Color.TRANSPARENT, source.getPixel(4, 4))
        } finally {
            output?.recycle()
            source.recycle()
            directory.deleteRecursively()
        }
    }

    @Test
    fun previewAndCropDecodeRespectExifRotation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("gallery-exif-", ".jpg", context.cacheDir)
        val source = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.RED)
            for (y in 0 until 120) for (x in 0 until 160) source.setPixel(x, y, Color.BLUE)
            file.outputStream().use { check(source.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val preview = decodeGalleryCropBitmap(file, maxPixels = 2_000_000L, maxEdge = 1600)
            val decoded = decodeGalleryCropBitmap(file)
            try {
                assertEquals(240, preview.width)
                assertEquals(320, preview.height)
                assertEquals(preview.width, decoded.width)
                assertEquals(preview.height, decoded.height)
                assertEquals(preview.getPixel(180, 80), decoded.getPixel(180, 80))
                assertTrue(Color.blue(decoded.getPixel(180, 80)) >= 250)
            } finally {
                decoded.recycle()
                preview.recycle()
            }
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test
    fun perspectiveMatrixMapsAllFourCornersToOutputEdges() {
        val corners = listOf(GalleryCropPoint(.2f, .1f), GalleryCropPoint(.8f, .1f), GalleryCropPoint(1f, .9f), GalleryCropPoint(0f, .9f))
        val plan = requireNotNull(galleryCropPlan(corners, 1000, 1000))
        val mapped = plan.source.copyOf()
        galleryCropMatrix(plan).mapPoints(mapped)
        assertArrayEquals(plan.destination, mapped, .01f)
    }

    @Test
    fun fullFrameTransformPreservesPixelsAndSource() {
        val source = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(16 * 12) { index -> Color.rgb(index % 16 * 15, index / 16 * 20, 80) }
        source.setPixels(pixels, 0, 16, 0, 0, 16, 12)
        val output = cropGalleryBitmap(source, fullGalleryCrop())
        try {
            val actual = IntArray(pixels.size)
            output.getPixels(actual, 0, 16, 0, 0, 16, 12)
            assertArrayEquals(pixels, actual)
            source.getPixels(actual, 0, 16, 0, 0, 16, 12)
            assertArrayEquals(pixels, actual)
        } finally {
            output.recycle()
            source.recycle()
        }
    }

    @Test
    fun rectangularCropRemovesOnlySelectedMargins() {
        val source = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        for (y in 5 until 15) for (x in 5 until 15) source.setPixel(x, y, Color.BLUE)
        val corners = listOf(GalleryCropPoint(.25f, .25f), GalleryCropPoint(.75f, .25f), GalleryCropPoint(.75f, .75f), GalleryCropPoint(.25f, .75f))
        val output = cropGalleryBitmap(source, corners)
        try {
            assertEquals(10, output.width)
            assertEquals(10, output.height)
            assertEquals(Color.BLUE, output.getPixel(5, 5))
            assertEquals(Color.RED, source.getPixel(0, 0))
        } finally {
            output.recycle()
            source.recycle()
        }
    }
}
