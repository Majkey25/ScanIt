package com.majkeylab.scanit

internal object BetaPremiumConfig {
    const val productId = "scanit_premium"
}

internal enum class BetaPremiumPurchaseState {
    Pending,
    Purchased,
}

internal data class BetaPremiumPurchase(
    val productIds: Set<String>,
    val state: BetaPremiumPurchaseState,
)

internal fun hasPremiumEntitlement(purchases: List<BetaPremiumPurchase>): Boolean =
    purchases.any {
        it.state == BetaPremiumPurchaseState.Purchased &&
            BetaPremiumConfig.productId in it.productIds
    }

internal fun hasPendingPremiumPurchase(purchases: List<BetaPremiumPurchase>): Boolean =
    purchases.any {
        it.state == BetaPremiumPurchaseState.Pending &&
            BetaPremiumConfig.productId in it.productIds
    }
