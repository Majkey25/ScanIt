package com.majkeylab.scanit

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Android10CompatibilityTest {
    private val repository = File("..").canonicalFile

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
