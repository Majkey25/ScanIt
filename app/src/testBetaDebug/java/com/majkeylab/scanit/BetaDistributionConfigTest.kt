package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaDistributionConfigTest {
    @Test
    fun debugBuildUsesOnlyGoogleDemoIds() {
        val ads = BetaDistributionConfig.adsForDebuggable(debuggable = true)

        assertEquals(
            "ca-app-pub-3940256099942544~3347511713",
            ads.appId,
        )
        assertEquals(
            "ca-app-pub-3940256099942544/9214589741",
            ads.bannerAdUnitId,
        )
    }

    @Test
    fun releaseBuildUsesScanItAdMobIds() {
        val ads = BetaDistributionConfig.adsForDebuggable(debuggable = false)

        assertEquals("ca-app-pub-6991329209066655~2916806906", ads.appId)
        assertEquals("ca-app-pub-6991329209066655/9690244602", ads.bannerAdUnitId)
    }

    @Test
    fun consentRequestStartsOnlyOncePerProcess() {
        val gate = BetaConsentGate()

        assertTrue(gate.beginRequest())
        assertFalse(gate.beginRequest())
    }

    @Test
    fun adsStayBlockedUntilConsentAllowsRequests() {
        val gate = BetaConsentGate()

        gate.update(canRequestAds = false, privacyOptionsRequired = true)
        assertFalse(gate.canRequestAds)
        assertTrue(gate.privacyOptionsRequired)

        gate.update(canRequestAds = true, privacyOptionsRequired = false)
        assertTrue(gate.canRequestAds)
        assertFalse(gate.privacyOptionsRequired)
    }

    @Test
    fun premiumUsesStablePlayProductId() {
        assertEquals("scanit_premium", BetaPremiumConfig.productId)
    }

    @Test
    fun onlyCompletedPremiumPurchaseGrantsEntitlement() {
        assertTrue(
            hasPremiumEntitlement(
                listOf(BetaPremiumPurchase(setOf("scanit_premium"), purchased = true)),
            ),
        )
        assertFalse(
            hasPremiumEntitlement(
                listOf(BetaPremiumPurchase(setOf("scanit_premium"), purchased = false)),
            ),
        )
        assertFalse(
            hasPremiumEntitlement(
                listOf(BetaPremiumPurchase(setOf("another_product"), purchased = true)),
            ),
        )
    }

    @Test
    fun adsRequireConsentAndNoPremiumEntitlement() {
        assertTrue(shouldShowBetaAd(premium = false, canRequestAds = true))
        assertFalse(shouldShowBetaAd(premium = true, canRequestAds = true))
        assertFalse(shouldShowBetaAd(premium = false, canRequestAds = false))
    }
}
