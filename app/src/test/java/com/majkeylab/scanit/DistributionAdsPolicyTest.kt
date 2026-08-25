package com.majkeylab.scanit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionAdsPolicyTest {
    @Test
    fun recentBannerRequiresAtLeastOneScan() {
        assertFalse(shouldShowRecentBanner(0))
        assertTrue(shouldShowRecentBanner(1))
    }

    @Test
    fun shareInterstitialIsDueOnceOnEveryThirdScan() {
        assertFalse(isShareInterstitialDue(2, null, 300_000, null))
        assertTrue(isShareInterstitialDue(3, 3, 300_000, null))
        assertFalse(isShareInterstitialDue(3, null, 300_000, null))
        assertFalse(isShareInterstitialDue(4, 3, 300_000, null))
        assertTrue(isShareInterstitialDue(6, 6, 300_000, null))
    }

    @Test
    fun fullscreenCooldownBlocksBackToBackAds() {
        assertFalse(isInterstitialCooldownElapsed(179_999, 0))
        assertTrue(isInterstitialCooldownElapsed(180_000, 0))
        assertTrue(isInterstitialCooldownElapsed(1, null))
    }

    @Test
    fun settingsInterstitialRequiresResultAndCompletedScan() {
        assertFalse(isSettingsReturnInterstitialDue(false, 1, false, 300_000, null))
        assertFalse(isSettingsReturnInterstitialDue(true, 0, false, 300_000, null))
        assertTrue(isSettingsReturnInterstitialDue(true, 1, false, 300_000, null))
        assertFalse(isSettingsReturnInterstitialDue(true, 1, true, 300_000, null))
        assertFalse(isSettingsReturnInterstitialDue(true, 1, false, 300_000, 200_000))
    }
}
