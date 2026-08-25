package com.majkeylab.scanit

private const val INTERSTITIAL_COOLDOWN_MILLIS = 180_000L

internal enum class DistributionInterstitialTrigger {
    SettingsReturn,
    Share,
}

internal fun shouldShowRecentBanner(scanCount: Int): Boolean = scanCount > 0

internal fun isInterstitialCooldownElapsed(
    nowMillis: Long,
    lastShownMillis: Long?,
): Boolean =
    lastShownMillis == null ||
        nowMillis - lastShownMillis >= INTERSTITIAL_COOLDOWN_MILLIS

internal fun isShareInterstitialDue(
    completedScans: Int,
    dueScan: Int?,
    nowMillis: Long,
    lastShownMillis: Long?,
): Boolean =
    completedScans > 0 &&
        completedScans % 3 == 0 &&
        dueScan == completedScans &&
        isInterstitialCooldownElapsed(nowMillis, lastShownMillis)

internal fun isSettingsReturnInterstitialDue(
    returningToResult: Boolean,
    completedScans: Int,
    shownThisSession: Boolean,
    nowMillis: Long,
    lastShownMillis: Long?,
): Boolean =
    returningToResult &&
        completedScans > 0 &&
        !shownThisSession &&
        isInterstitialCooldownElapsed(nowMillis, lastShownMillis)
