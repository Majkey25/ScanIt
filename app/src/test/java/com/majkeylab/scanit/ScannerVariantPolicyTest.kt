package com.majkeylab.scanit

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerVariantPolicyTest {
    private val repository = File("..").canonicalFile

    @Test
    fun v2TestIsAnIsolatedDebugApplication() {
        val buildScript = File(repository, "app/build.gradle.kts").readText()
        val manifest = File(repository, "app/src/v2test/AndroidManifest.xml")
        val strings = File(repository, "app/src/v2test/res/values/strings.xml")

        assertTrue(buildScript.contains("create(\"v2test\")"))
        assertTrue(buildScript.contains("applicationIdSuffix = \".v2test\""))
        assertTrue(buildScript.contains("versionNameSuffix = \"-v2-test\""))
        assertTrue(buildScript.contains("\"v2testDebug\""))
        assertTrue(manifest.isFile)
        assertTrue(strings.isFile)
        assertTrue(strings.readText().contains(">ScanIt v2 Test</string>"))
    }

    @Test
    fun cameraPermissionBelongsOnlyToV2Test() {
        val mainManifest = File(repository, "app/src/main/AndroidManifest.xml").readText()
        val playManifest = File(repository, "app/src/play/AndroidManifest.xml").readText()
        val githubManifest = File(repository, "app/src/github/AndroidManifest.xml").readText()
        val v2Manifest = File(repository, "app/src/v2test/AndroidManifest.xml")

        assertFalse(mainManifest.contains("android.permission.CAMERA"))
        assertFalse(playManifest.contains("android.permission.CAMERA"))
        assertFalse(githubManifest.contains("android.permission.CAMERA"))
        assertTrue(v2Manifest.isFile)
        val v2Text = v2Manifest.readText()
        assertTrue(v2Text.contains("android.permission.CAMERA"))
        assertTrue(v2Text.contains("android.permission.INTERNET"))
        assertTrue(v2Text.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertTrue(Regex("""android:name="android.permission.INTERNET"\s+tools:node="remove"""").containsMatchIn(v2Text))
        assertTrue(Regex("""android:name="android.permission.ACCESS_NETWORK_STATE"\s+tools:node="remove"""").containsMatchIn(v2Text))
    }
}
