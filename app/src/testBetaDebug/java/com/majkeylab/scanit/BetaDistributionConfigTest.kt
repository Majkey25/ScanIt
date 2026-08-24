package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaDistributionConfigTest {
    @Test
    fun debugBuildUsesOnlyGoogleDemoIds() {
        val ads = BetaDistributionConfig.adsForDebuggable(debuggable = true)

        assertEquals("ca-app-pub-3940256099942544~3347511713", ads.appId)
        assertEquals("ca-app-pub-3940256099942544/9214589741", ads.bannerAdUnitId)
        assertEquals("ca-app-pub-3940256099942544/1033173712", ads.interstitialAdUnitId)
    }

    @Test
    fun releaseBuildUsesScanItAdMobIds() {
        val ads = BetaDistributionConfig.adsForDebuggable(debuggable = false)

        assertEquals("ca-app-pub-6991329209066655~2916806906", ads.appId)
        assertEquals("ca-app-pub-6991329209066655/9690244602", ads.bannerAdUnitId)
        assertEquals("ca-app-pub-6991329209066655/3092133938", ads.interstitialAdUnitId)
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
    fun onlyPurchasedMatchingProductGrantsPremium() {
        assertTrue(
            hasPremiumEntitlement(
                listOf(
                    BetaPremiumPurchase(
                        productIds = setOf("scanit_premium"),
                        state = BetaPremiumPurchaseState.Purchased,
                    ),
                ),
            ),
        )
        assertFalse(
            hasPremiumEntitlement(
                listOf(
                    BetaPremiumPurchase(
                        productIds = setOf("scanit_premium"),
                        state = BetaPremiumPurchaseState.Pending,
                    ),
                ),
            ),
        )
        assertFalse(
            hasPremiumEntitlement(
                listOf(
                    BetaPremiumPurchase(
                        productIds = setOf("another_product"),
                        state = BetaPremiumPurchaseState.Purchased,
                    ),
                ),
            ),
        )
    }

    @Test
    fun pendingMatchingProductStaysLocked() {
        val pending =
            listOf(
                BetaPremiumPurchase(
                    productIds = setOf("scanit_premium"),
                    state = BetaPremiumPurchaseState.Pending,
                ),
            )

        assertTrue(hasPendingPremiumPurchase(pending))
        assertFalse(hasPremiumEntitlement(pending))
    }

    @Test
    fun successfulPurchaseQueryResolvesVerifiedEntitlement() {
        val free = resolvePremiumEntitlement(emptyList())
        val premium =
            resolvePremiumEntitlement(
                listOf(
                    BetaPremiumPurchase(
                        productIds = setOf("scanit_premium"),
                        state = BetaPremiumPurchaseState.Purchased,
                    ),
                ),
            )

        assertFalse(free.premium)
        assertFalse(free.pending)
        assertTrue(premium.premium)
        assertFalse(premium.pending)
    }

    @Test
    fun unknownPurchaseStateGrantsNothing() {
        val entitlement =
            resolvePremiumEntitlement(
                listOf(
                    BetaPremiumPurchase(
                        productIds = setOf("scanit_premium"),
                        state = BetaPremiumPurchaseState.Unknown,
                    ),
                ),
            )

        assertFalse(entitlement.premium)
        assertFalse(entitlement.pending)
    }

    @Test
    fun adsRequireVerifiedFreeEntitlementAndConsent() {
        assertTrue(
            shouldShowBetaAd(
                premium = false,
                entitlementVerified = true,
                canRequestAds = true,
            ),
        )
        assertFalse(
            shouldShowBetaAd(
                premium = true,
                entitlementVerified = true,
                canRequestAds = true,
            ),
        )
        assertFalse(
            shouldShowBetaAd(
                premium = false,
                entitlementVerified = false,
                canRequestAds = true,
            ),
        )
        assertFalse(
            shouldShowBetaAd(
                premium = false,
                entitlementVerified = true,
                canRequestAds = false,
            ),
        )
    }
}
