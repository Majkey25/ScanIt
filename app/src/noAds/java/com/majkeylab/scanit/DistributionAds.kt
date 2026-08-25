package com.majkeylab.scanit

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable

@Composable
internal fun DistributionBannerAd() = Unit

@Composable
internal fun DistributionAdsWarmup() = Unit

internal fun recordDistributionDocumentScan(context: Context) = Unit

internal fun showDistributionInterstitial(
    activity: Activity,
    trigger: DistributionInterstitialTrigger,
    onComplete: () -> Unit,
) = onComplete()

@Composable
internal fun DistributionPrivacyOptionsLink() = Unit
