package com.majkeylab.scanit

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Android10CompatibilityTest {
    private val repository = File("..").canonicalFile

    @Test
    fun speechEngineDiscoveryUsesOnlyTheTtsServiceQuery() {
        val manifest = File(repository, "app/src/main/AndroidManifest.xml").readText()
        val queries = manifest.substringAfter("<queries>", "").substringBefore("</queries>")

        assertTrue(queries.contains("android:name=\"android.intent.action.TTS_SERVICE\""))
        assertFalse(manifest.contains("android.permission.QUERY_ALL_PACKAGES"))
    }

    @Test
    fun scannerIntentWaitsForStartedAndIsConsumedBeforeLaunch() {
        val activity = File(repository, "app/src/main/java/com/majkeylab/scanit/MainActivity.kt").readText()
        val launch = activity.substringAfter("private fun launchPendingScannerIntent() {", "")
            .substringBefore("private fun handleScannerResult(")
        val started = launch.indexOf("lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)")
        val consume = launch.indexOf("pendingScannerIntent = null", started.coerceAtLeast(0))
        val dispatch = launch.indexOf("scannerLauncher.launch(")

        assertTrue(launch.contains("isFinishing || isDestroyed"))
        assertTrue(launch.contains("viewModel.isScannerLaunchCurrent(requestGeneration)"))
        assertTrue(started >= 0 && consume > started && dispatch > consume)
        assertEquals(1, Regex("scannerLauncher\\.launch\\(").findAll(activity).count())
        val observer = activity.substringAfter("viewModel.resumeScannerPreparation()")
            .substringBefore("override fun onStart()")
        assertTrue(observer.contains("repeatOnLifecycle(Lifecycle.State.STARTED)"))
        assertTrue(observer.substringAfter("viewModel.state.collect {").contains("launchPendingScannerIntent()"))
        val destroy = activity.substringAfter("override fun onDestroy() {")
            .substringBefore("override fun onInit(")
        assertTrue(destroy.contains("pendingScannerIntent = null"))
    }

    @Test
    fun api29UsesCompatibilityPathsForNewerPlatformApis() {
        val sources =
            listOf(
                "app/src/main/java/com/majkeylab/scanit/DurableOutputDelete.kt",
                "app/src/main/java/com/majkeylab/scanit/ScanStorage.kt",
            ).joinToString("\n") { File(repository, it).readText() }
        val renderer =
            File(repository, "app/src/main/java/com/majkeylab/scanit/ImageExportRenderer.kt")
                .readText()
        val activity =
            File(repository, "app/src/main/java/com/majkeylab/scanit/MainActivity.kt").readText()
        val receiver =
            File(repository, "app/src/main/java/com/majkeylab/scanit/ShareResultReceiver.kt")
                .readText()
        val manifest = File(repository, "app/src/main/AndroidManifest.xml").readText()

        assertTrue(activity.contains("enableEdgeToEdge()"))
        assertTrue(manifest.contains("android:windowSoftInputMode=\"adjustResize\""))
        assertTrue(
            manifest.contains(
                "android:name=\"com.google.mlkit.vision.documentscanner.internal.GmsDocumentScanningDelegateActivity\"",
            ),
        )
        assertTrue(manifest.contains("tools:remove=\"android:screenOrientation\""))
        assertFalse(sources.contains("getContentUri(address.volume, address.id)"))
        assertTrue(sources.contains("mediaItemContentUri(address)"))
        assertTrue(renderer.contains("newBitmapRegionDecoder(source)"))
        assertTrue(activity.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU"))
        assertTrue(activity.contains("LEGACY_APP_LANGUAGE"))
        assertFalse(receiver.contains("deleteDurableOutputs"))
        assertTrue(receiver.contains("discardPendingShareCleanups"))
    }
}
