package com.majkeylab.scanit

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionAdsIntegrationTest {
    private val repository = File("..").canonicalFile

    @Test
    fun betaAdsStayBehindDistributionHooks() {
        val appUi = File(repository, "app/src/main/java/com/majkeylab/scanit/AppUi.kt").readText()
        val activity = File(repository, "app/src/main/java/com/majkeylab/scanit/MainActivity.kt").readText()
        val noAds = File(repository, "app/src/noAds/java/com/majkeylab/scanit/DistributionAds.kt")

        assertTrue(appUi.contains("DistributionBannerAd()"))
        assertTrue(appUi.contains("DistributionInterstitialTrigger.SettingsReturn"))
        assertTrue(appUi.contains("DistributionInterstitialTrigger.Share"))
        assertTrue(appUi.contains("DistributionPrivacyOptionsLink()"))
        assertTrue(activity.contains("recordDistributionDocumentScan(this)"))
        assertTrue(noAds.isFile)
        assertTrue(noAds.readText().contains("showDistributionInterstitial"))
    }

    @Test
    fun betaManifestDeclaresOnlyRequiredAdIntegration() {
        val manifest = File(repository, "app/src/beta/AndroidManifest.xml")

        assertTrue(manifest.isFile)
        val text = manifest.readText()
        assertTrue(text.contains("android.permission.INTERNET"))
        assertTrue(text.contains("com.google.android.gms.ads.APPLICATION_ID"))
        assertTrue(text.contains("@string/beta_app_name"))
    }
}
