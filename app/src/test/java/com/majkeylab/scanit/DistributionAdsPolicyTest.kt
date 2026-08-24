package com.majkeylab.scanit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionAdsPolicyTest {
    @Test
    fun shareInterstitialIsDueOnceOnEveryFifthScan() {
        assertFalse(isShareInterstitialDue(4, null, 300_000, null))
        assertTrue(isShareInterstitialDue(5, 5, 300_000, null))
        assertFalse(isShareInterstitialDue(5, null, 300_000, null))
        assertFalse(isShareInterstitialDue(6, 5, 300_000, null))
        assertTrue(isShareInterstitialDue(10, 10, 300_000, null))
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
