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
        assertTrue(build.contains("create(\"play\")"))
        assertFalse(build.contains("billingclient"))
        assertFalse(build.contains("ads-mobile-sdk"))
        assertFalse(build.contains("user-messaging-platform"))
        assertFalse(File(repository, "app/src/beta").walkTopDown().any(File::isFile))
        assertFalse(appUi.contains("Premium"))
        assertFalse(appUi.contains("DistributionBannerAd"))
        assertFalse(appUi.contains("showDistributionInterstitial"))
        assertTrue(appUi.contains("if (BuildConfig.ENABLE_DONATIONS)"))
    }

    @Test
    fun playPrivacyPageHasNoDonationOrLandingPageLinks() {
        val policy = File(repository, "docs/play/privacy.html").readText()
        val links = Regex("""<a\s[^>]*href="([^"]+)"""").findAll(policy)
            .map { it.groupValues[1] }.toList()

        assertTrue(links.isNotEmpty())
        links.forEach { link ->
            assertTrue(link, link == "#main" || link == "mailto:majkeylab@gmail.com" ||
                listOf("developers.google.com", "support.google.com", "policies.google.com",
                    "play.google.com", "docs.github.com", "uoou.gov.cz")
                    .any { host -> link.startsWith("https://$host/") })
        }
        assertFalse(policy.contains("buymeacoffee", ignoreCase = true))
        assertFalse(policy.contains("<script", ignoreCase = true))
        assertTrue(policy.contains("Google Mobile Ads"))
        assertTrue(policy.contains("Google Play Billing"))
        assertTrue(policy.contains("com.majkeylab.scanit"))
    }
}
