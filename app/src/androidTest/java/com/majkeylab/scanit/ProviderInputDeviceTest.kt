package com.majkeylab.scanit

import android.app.Instrumentation
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderInputDeviceTest {
    @Test
    fun cancelledProviderOpenRetainsAdmissionUntilProviderReturns() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "input-open-test-").toFile()
        val source = File(root, "source").apply { writeBytes(ByteArray(32)) }
        val destination = File(root, "copy")
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val resolver = ContentResolver.wrap(object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/png"
            override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
            override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor {
                calls.incrementAndGet()
                opened.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return AssetFileDescriptor(
                    ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY),
                    0,
                    AssetFileDescriptor.UNKNOWN_LENGTH,
                )
            }
        })
        val uri = Uri.parse("content://fixture/stalled-open")
        val job = launch(Dispatchers.IO) { copyProviderInput(resolver, uri, destination, 32) }
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            job.cancel()
            repeat(16) {
                val failure = runCatching { copyProviderInput(resolver, uri, destination, 32) }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals("Another input is still being read", failure?.message)
            }
            assertEquals(1, calls.get())
            release.countDown()
            withTimeout(2_000) { job.join() }
            assertFalse(destination.exists())
            assertEquals(32L, copyProviderInput(context.contentResolver, Uri.fromFile(source), destination, 32))
            assertTrue(source.readBytes().contentEquals(destination.readBytes()))
        } finally {
            release.countDown()
            job.cancel()
            job.join()
            assertTrue(deleteTreeWithoutFollowingLinks(root))
        }
    }

    @Test
    fun applicationStartupRemovesOrphansWithoutDeletingOtherCacheOrActiveImports() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "staging-startup-test-").toFile()
        val context = object : ContextWrapper(base) {
            override fun getCacheDir(): File = root
        }
        val previousBudget = scanCacheBudget
        try {
            val outside = File(root, "unrelated-cache").apply { writeText("preserve") }
            val staging = providerInputStagingDirectory(root)
            val scan = File(staging, ".scanner_crashed").apply { check(mkdir()) }
            RandomAccessFile(File(scan, "0.jpg"), "rw").use { it.setLength(512L * 1024 * 1024) }
            File(staging, ".mark_import_crashed.tmp").writeText("unfinished mark")
            Files.createSymbolicLink(File(staging, "linked-orphan").toPath(), outside.toPath())

            Instrumentation.newApplication(ScanApplication::class.java, context).onCreate()

            assertTrue(staging.listFiles().orEmpty().isEmpty())
            assertEquals("preserve", outside.readText())
            val active = File.createTempFile(".mark_import_", ".tmp", staging).apply { writeText("active") }
            assertEquals(staging, providerInputStagingDirectory(root))
            assertEquals("active", active.readText())
        } finally {
            scanCacheBudget = previousBudget
            assertTrue(deleteTreeWithoutFollowingLinks(root))
        }
    }

    @Test
    fun cancelledProviderReadReleasesMarkOperationsAndTemporaryInput() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "mark-import-test-").toFile()
        val opened = CountDownLatch(1)
        val pipe = ParcelFileDescriptor.createPipe()
        val resolver = ContentResolver.wrap(object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/png"
            override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
            override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor {
                opened.countDown()
                return AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }
        })
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = root
            override fun getNoBackupFilesDir(): File = root
            override fun getContentResolver(): ContentResolver = resolver
        }
        val store = MarkTemplateStore(context)
        val job = launch(Dispatchers.IO) { store.import(Uri.parse("content://fixture/stalled")) }
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), withTimeout(2_000) { withContext(Dispatchers.IO) { store.list() } })
            job.cancel()
            withTimeout(2_000) { job.join() }
            assertTrue(job.isCancelled)
            assertFalse(pipe[0].fileDescriptor.valid())
            assertTrue(providerInputStagingDirectory(root).listFiles().orEmpty().isEmpty())
        } finally {
            pipe[1].close()
            job.cancel()
            job.join()
            pipe[0].close()
            assertTrue(deleteTreeWithoutFollowingLinks(root))
        }
    }

    @Test
    fun providerSnapshotRejectsBytesBeyondLimit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "input-limit-test-").toFile()
        try {
            val source = File(root, "source").apply { writeBytes(ByteArray(32)) }
            val destination = File(root, "copy")
            val failure = runCatching {
                copyProviderInput(context.contentResolver, Uri.fromFile(source), destination, 16)
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(destination.length() <= 16)
            assertEquals(32L, source.length())
        } finally {
            assertTrue(deleteTreeWithoutFollowingLinks(root))
        }
    }
}
