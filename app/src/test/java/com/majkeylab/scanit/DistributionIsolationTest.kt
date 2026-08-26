package com.majkeylab.scanit

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionIsolationTest {
    private val repository = File("..").canonicalFile

    @Test
    fun publicSourceContainsOnlyUnlockedNoAdsBuilds() {
        val build = File(repository, "app/build.gradle.kts").readText()
        val appUi = File(repository, "app/src/main/java/com/majkeylab/scanit/AppUi.kt").readText()

        assertTrue(build.contains("create(\"github\")"))
        assertTrue(build.contains("create(\"internal\")"))
        assertFalse(build.contains("create(\"beta\")"))
        assertFalse(build.contains("create(\"play\")"))
        assertFalse(build.contains("billingclient"))
        assertFalse(build.contains("ads-mobile-sdk"))
        assertFalse(build.contains("user-messaging-platform"))
        assertFalse(File(repository, "app/src/beta").walkTopDown().any(File::isFile))
        assertFalse(appUi.contains("Premium"))
        assertFalse(appUi.contains("DistributionBannerAd"))
        assertFalse(appUi.contains("showDistributionInterstitial"))
    }
}
