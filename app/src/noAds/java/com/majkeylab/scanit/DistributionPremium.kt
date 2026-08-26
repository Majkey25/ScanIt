package com.majkeylab.scanit

import android.content.Context
import androidx.compose.runtime.Composable

internal const val distributionShowsPremiumSupportDisclaimer = false

private val unlockedPremiumState =
    DistributionPremiumState(
        premium = true,
        entitlementVerified = true,
        checking = false,
    )

internal fun refreshDistributionPremium(context: Context) = Unit

@Composable
internal fun rememberDistributionPremiumState(): DistributionPremiumState = unlockedPremiumState

@Composable
internal fun DistributionPremiumPaywall(onDismiss: () -> Unit) = Unit

@Composable
internal fun DistributionPremiumSettings() = Unit
