package com.majkeylab.scanit

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class VariantHandoffDeviceTest {
    @Test(timeout = 20_000)
    fun clearingViewModelBeforeIoResumeDeletesOnlyItsManualCleanupCandidate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        check(base.packageName == "com.majkeylab.scanit.internal")
        // ViewModel startup reconciles app-wide tree grants; never release another fixture's grants.
        assumeTrue("Requires an internal app without persisted grants", base.contentResolver.persistedUriPermissions.isEmpty())
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "variant-handoff-").toFile()
        val application = object : Application() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = root
            override fun getFilesDir(): File = root
            override fun getNoBackupFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("${root.name}_$name", mode)
        }
        val store = ViewModelStore()
        val releaseMain = CountDownLatch(1)
        var scopeJob: Job? = null
        try {
            val source = File(root, "source.jpg")
            val bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawRect(28f, 36f, 60f, 80f, Paint().apply { color = Color.BLACK })
                }
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally {
                bitmap.recycle()
            }
            val storage = ScanStorage(application)
            val settings = SettingsStore(application)
            settings.save(settings.load().copy(savePdf = false, saveImages = false))
            val parent = storage.activateProvisionalCacheEntry(
                storage.cacheScan(listOf(Uri.fromFile(source)), googleScannerAppearanceSettings(), PdfSizeTarget.Original).cached,
            )
            assertEquals(AuthorityMutationResult.Applied,
                settings.saveActiveResult(parent.baseName, settings.authoritySnapshot().owner))
            val checkpoint = checkNotNull(settings.activeResultCheckpoint())
            val parentDirectory = checkNotNull(parent.pdf.parentFile)
            val originals = (checkNotNull(parentDirectory.listFiles()).toList() + source)
                .associateWith(File::readBytes)
            val handle = SavedStateHandle().apply {
                set("screen_route", "result")
                set("screen_route_cache_id", parent.baseName)
                set("safe_share_active", true)
            }
            lateinit var viewModel: ScanViewModel
            instrumentation.runOnMainSync {
                val factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        checkNotNull(modelClass.cast(ScanViewModel(application, handle).also {
                            scopeJob = it.viewModelScope.coroutineContext[Job]
                        }))
                }
                viewModel = ViewModelProvider(store, factory)[ScanViewModel::class.java]
            }
            withTimeout(5_000) { viewModel.state.filterIsInstance<ScreenState.Result>().first() }
            val bystander = storage.cacheScan(
                listOf(Uri.fromFile(source)), googleScannerAppearanceSettings(), PdfSizeTarget.Original,
            ).cached
            val shareRoot = checkNotNull(parentDirectory.parentFile)
            val knownIds = setOf(parent.baseName, bystander.baseName)
            val blocked = CountDownLatch(1)
            val mainFailure = AtomicReference<Throwable>()
            val main = Handler(Looper.getMainLooper())
            storageTransactionLock.lock()
            try {
                assertTrue(main.post {
                    try {
                        viewModel.openManualCleanupEditor()
                        viewModel.updateManualCleanupStrokes(listOf(MarkStroke(listOf(
                            MarkPoint(0.25f, 0.25f), MarkPoint(0.75f, 0.25f),
                            MarkPoint(0.75f, 0.75f), MarkPoint(0.25f, 0.75f),
                        ))))
                        viewModel.applyManualCleanup()
                        check((viewModel.state.value as ScreenState.Result).manualCleanupEditor?.applying == true)
                        blocked.countDown()
                        check(releaseMain.await(5, TimeUnit.SECONDS)) { "Main blocker timed out" }
                    } catch (failure: Throwable) {
                        mainFailure.set(failure)
                        blocked.countDown()
                    }
                })
                assertTrue("Manual cleanup did not enter Main blocker", blocked.await(2, TimeUnit.SECONDS))
                mainFailure.get()?.let { throw it }
            } finally {
                storageTransactionLock.unlock()
            }
            var owned: CachedScan? = null
            awaitCondition {
                tryStorageTransaction {
                    owned = checkNotNull(shareRoot.listFiles()).singleOrNull {
                        it.name !in knownIds && !it.name.startsWith(".") && File(it, ".provisional").isFile
                    }?.let { storage.openCachedScan(it.name) }
                    owned != null
                } == true
            }
            assertEquals(1L, releaseMain.count)
            // Main remains blocked, so this clear precedes any queued IO continuation.
            assertTrue(main.postAtFrontOfQueue { store.clear() })
            releaseMain.countDown()
            withTimeout(5_000) { checkNotNull(scopeJob).join() }
            mainFailure.get()?.let { throw it }

            assertFalse(File(shareRoot, checkNotNull(owned).baseName).exists())
            assertTrue(storage.isProvisionalCacheEntry(bystander))
            assertEquals(checkpoint, settings.activeResultCheckpoint())
            originals.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
        } finally {
            releaseMain.countDown()
            instrumentation.runOnMainSync { store.clear() }
            // Do not remove fixture files while any native IO child is still alive.
            withTimeout(5_000) { scopeJob?.join() }
            check(base.deleteSharedPreferences("${root.name}_settings"))
            check(root.deleteRecursively())
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(4_000) {
            while (!condition()) delay(10)
        }
    }
}
