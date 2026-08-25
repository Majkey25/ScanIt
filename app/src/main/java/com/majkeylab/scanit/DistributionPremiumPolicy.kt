package com.majkeylab.scanit

internal data class DistributionPremiumState(
    val premium: Boolean = false,
    val entitlementVerified: Boolean = false,
    val checking: Boolean = true,
    val pending: Boolean = false,
    val monthlyPrice: String? = null,
    val monthlyPurchaseAvailable: Boolean = false,
    val lifetimePrice: String? = null,
    val lifetimePurchaseAvailable: Boolean = false,
    val error: Boolean = false,
)

internal fun canRunDistributionDocumentActions(premium: Boolean): Boolean = premium
