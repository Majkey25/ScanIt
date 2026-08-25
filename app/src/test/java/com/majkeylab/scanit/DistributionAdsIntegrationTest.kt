package com.majkeylab.scanit

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionAdsIntegrationTest {
    private val repository = File("..").canonicalFile

    @Test
    fun betaLauncherUsesSeliaScanBrand() {
        val stringFiles =
            File(repository, "app/src/beta/res")
                .walkTopDown()
                .filter { it.isFile && it.name == "strings.xml" }
                .toList()

        assertTrue(stringFiles.isNotEmpty())
        stringFiles.forEach { file ->
            assertTrue(file.readText().contains("<string name=\"beta_app_name\">SeliaScan</string>"))
        }
    }

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

    @Test
    fun bannersStayAnchoredAndHideTheEmptyAdSurface() {
        val appUi = File(repository, "app/src/main/java/com/majkeylab/scanit/AppUi.kt").readText()
        val betaAds = File(repository, "app/src/beta/java/com/majkeylab/scanit/DistributionAds.kt").readText()
        val recentScreen =
            appUi.substringAfter("private fun RecentScreen(")
                .substringBefore("private fun RecentScanRow(")
        val settingsScreen =
            appUi.substringAfter("private fun SettingsScreen(")
                .substringBefore("private fun SettingsCategoryHeader(")

        assertTrue(recentScreen.contains("bottomBar = {"))
        assertTrue(recentScreen.contains("DistributionBannerAd()"))
        assertTrue(settingsScreen.contains("bottomBar = { DistributionBannerAd() }"))
        assertFalse(recentScreen.contains("inline = true"))
        assertTrue(betaAds.contains("getLargeAnchoredAdaptiveBannerAdSize"))
        assertTrue(appUi.contains("DistributionAdsWarmup()"))
        assertTrue(betaAds.contains("if (adLoaded)"))
        assertFalse(betaAds.contains("if (slotVisible)"))
        assertTrue(betaAds.contains("BANNER_LOADING_HEIGHT_DP.dp"))
        assertTrue(betaAds.contains("BetaBannerCoordinator.acquire"))
        assertFalse(betaAds.contains("onDispose { adView.destroy() }"))
        assertFalse(betaAds.contains("Surface(color = MaterialTheme.colorScheme.surfaceVariant)"))
        assertTrue(betaAds.contains("next.takeIf { it % 3 == 0 }"))
        assertFalse(betaAds.contains("next.takeIf { it % 5 == 0 }"))
    }

    @Test
    fun premiumAndSupportAreClearlySeparated() {
        val appUi = File(repository, "app/src/main/java/com/majkeylab/scanit/AppUi.kt").readText()
        val premiumUi =
            File(repository, "app/src/beta/java/com/majkeylab/scanit/DistributionPremiumUi.kt")
                .readText()
        val settingsScreen =
            appUi.substringAfter("private fun SettingsScreen(")
                .substringBefore("private fun SettingsCategoryHeader(")

        assertTrue(settingsScreen.contains("R.string.support_scanit_no_benefits"))
        assertTrue(premiumUi.contains("Color(0xFFFFDD00)"))
    }

    @Test
    fun successfulPurchaseCallbackAppliesEntitlementBeforePlayRefresh() {
        val callback =
            File(repository, "app/src/beta/java/com/majkeylab/scanit/BetaPremium.kt")
                .readText()
                .substringAfter("override fun onPurchasesUpdated")
                .substringBefore("private fun startConnection")

        assertTrue(callback.contains("processPurchases(updatedPurchases)"))
        assertTrue(
            callback.indexOf("processPurchases(updatedPurchases)") <
                callback.indexOf("queryPurchases(client)"),
        )
    }

    @Test
    fun successfulPurchaseReturnsToTheStillOpenUnlockedActions() {
        val appUi = File(repository, "app/src/main/java/com/majkeylab/scanit/AppUi.kt").readText()
        val premiumUi =
            File(repository, "app/src/beta/java/com/majkeylab/scanit/DistributionPremiumUi.kt")
                .readText()
        val premiumRequired =
            appUi.substringAfter("onPremiumRequired = {").substringBefore("},")

        assertTrue(premiumRequired.contains("showPremiumPaywall = true"))
        assertFalse(premiumRequired.contains("showDocumentActions = false"))
        assertTrue(premiumUi.contains("LaunchedEffect(state.premium)"))
    }
}
