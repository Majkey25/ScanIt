package com.majkeylab.scanit

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

internal object BetaPremiumConfig {
    const val lifetimeProductId = "seliascan_premium"
    const val monthlyProductId = "seliascan_premium_monthly"
    const val monthlyBasePlanId = "monthly"
    val productIds = setOf(lifetimeProductId, monthlyProductId)
}

internal enum class BetaPremiumPlan { Monthly, Lifetime }

internal fun premiumProductId(plan: BetaPremiumPlan): String =
    when (plan) {
        BetaPremiumPlan.Monthly -> BetaPremiumConfig.monthlyProductId
        BetaPremiumPlan.Lifetime -> BetaPremiumConfig.lifetimeProductId
    }

internal enum class BetaPremiumPurchaseState {
    Pending,
    Purchased,
    Unknown,
}

internal data class BetaPremiumPurchase(
    val productIds: Set<String>,
    val state: BetaPremiumPurchaseState,
)

internal fun hasPremiumEntitlement(purchases: List<BetaPremiumPurchase>): Boolean =
    purchases.any {
        it.state == BetaPremiumPurchaseState.Purchased &&
            it.productIds.any(BetaPremiumConfig.productIds::contains)
    }

internal fun hasPendingPremiumPurchase(purchases: List<BetaPremiumPurchase>): Boolean =
    purchases.any {
        it.state == BetaPremiumPurchaseState.Pending &&
            it.productIds.any(BetaPremiumConfig.productIds::contains)
    }

internal data class BetaPremiumEntitlement(
    val premium: Boolean,
    val pending: Boolean,
)

internal fun resolvePremiumEntitlement(
    purchases: List<BetaPremiumPurchase>,
): BetaPremiumEntitlement =
    BetaPremiumEntitlement(
        premium = hasPremiumEntitlement(purchases),
        pending = hasPendingPremiumPurchase(purchases),
    )

internal object BetaPremiumController : PurchasesUpdatedListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var billingClient: BillingClient? = null
    private var monthlyProductDetails: ProductDetails? = null
    private var lifetimeProductDetails: ProductDetails? = null
    private var connectionStarted = false

    var state by mutableStateOf(DistributionPremiumState())
        private set

    fun refresh(context: Context) {
        val client =
            billingClient ?: BillingClient.newBuilder(context.applicationContext)
                .setListener(this)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
                ).enableAutoServiceReconnection()
                .build()
                .also { billingClient = it }
        if (client.isReady) {
            queryProducts(client)
            queryPurchases(client)
        } else if (!connectionStarted) {
            startConnection(client)
        }
    }

    fun launchPurchase(activity: Activity, plan: BetaPremiumPlan) {
        val client = billingClient
        val details =
            when (plan) {
                BetaPremiumPlan.Monthly -> monthlyProductDetails
                BetaPremiumPlan.Lifetime -> lifetimeProductDetails
            }
        val offerToken =
            when (plan) {
                BetaPremiumPlan.Monthly ->
                    details?.subscriptionOfferDetails
                        ?.firstOrNull {
                            it.basePlanId == BetaPremiumConfig.monthlyBasePlanId &&
                                it.offerId == null
                        }?.offerToken
                BetaPremiumPlan.Lifetime ->
                    details?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
            }
        if (client == null || !client.isReady || details == null || offerToken == null) {
            updateState { it.copy(error = true) }
            return
        }
        val productParams =
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(offerToken)
                .build()
        val result =
            client.launchBillingFlow(
                activity,
                BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(productParams))
                    .build(),
            )
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            updateState { it.copy(error = true) }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val client = billingClient
                if (client != null && client.isReady) {
                    queryPurchases(client)
                } else {
                    processPurchases(purchases.orEmpty())
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> updateState { it.copy(checking = false, error = true) }
        }
    }

    private fun startConnection(client: BillingClient) {
        connectionStarted = true
        updateState { it.copy(checking = true, error = false) }
        client.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    connectionStarted = false
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryProducts(client)
                        queryPurchases(client)
                    } else {
                        updateState { it.copy(checking = false, error = true) }
                    }
                }

                override fun onBillingServiceDisconnected() {
                    connectionStarted = false
                    updateState { it.copy(checking = false, error = true) }
                }
            },
        )
    }

    private fun queryProducts(client: BillingClient) {
        queryProduct(client, BetaPremiumPlan.Monthly, BillingClient.ProductType.SUBS)
        queryProduct(client, BetaPremiumPlan.Lifetime, BillingClient.ProductType.INAPP)
    }

    private fun queryProduct(
        client: BillingClient,
        plan: BetaPremiumPlan,
        productType: String,
    ) {
        val productId = premiumProductId(plan)
        val product =
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(productType)
                .build()
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
        ) { result, detailsResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                when (plan) {
                    BetaPremiumPlan.Monthly -> monthlyProductDetails = null
                    BetaPremiumPlan.Lifetime -> lifetimeProductDetails = null
                }
                updateState { current ->
                    when (plan) {
                        BetaPremiumPlan.Monthly ->
                            current.copy(
                                monthlyPrice = null,
                                monthlyPurchaseAvailable = false,
                                error = true,
                            )
                        BetaPremiumPlan.Lifetime ->
                            current.copy(
                                lifetimePrice = null,
                                lifetimePurchaseAvailable = false,
                                error = true,
                            )
                    }
                }
                return@queryProductDetailsAsync
            }
            val details =
                detailsResult.productDetailsList.firstOrNull {
                    it.productId == productId
                }
            when (plan) {
                BetaPremiumPlan.Monthly -> {
                    val offer =
                        details?.subscriptionOfferDetails
                            ?.firstOrNull {
                                it.basePlanId == BetaPremiumConfig.monthlyBasePlanId &&
                                    it.offerId == null
                            }
                    monthlyProductDetails = details
                    updateState {
                        it.copy(
                            monthlyPrice =
                                offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
                                    ?.formattedPrice,
                            monthlyPurchaseAvailable = offer?.offerToken != null,
                        )
                    }
                }
                BetaPremiumPlan.Lifetime -> {
                    val offer = details?.oneTimePurchaseOfferDetailsList?.firstOrNull()
                    lifetimeProductDetails = details
                    updateState {
                        it.copy(
                            lifetimePrice = offer?.formattedPrice,
                            lifetimePurchaseAvailable = offer?.offerToken != null,
                        )
                    }
                }
            }
        }
    }

    private fun queryPurchases(client: BillingClient) {
        updateState { it.copy(checking = true, error = false) }
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                updateState { it.copy(checking = false, error = true) }
                return@queryPurchasesAsync
            }
            val ownsLifetime =
                purchases.any {
                    BetaPremiumConfig.lifetimeProductId in it.products &&
                        it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
            if (ownsLifetime) {
                processPurchases(purchases)
                return@queryPurchasesAsync
            }
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build(),
            ) { subscriptionResult, subscriptions ->
                if (subscriptionResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    processPurchases(purchases + subscriptions)
                } else {
                    updateState { it.copy(checking = false, error = true) }
                }
            }
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        val premiumPurchases =
            purchases.filter { it.products.any(BetaPremiumConfig.productIds::contains) }
        val entitlement =
            resolvePremiumEntitlement(
                premiumPurchases.map {
                    BetaPremiumPurchase(
                        productIds = it.products.toSet(),
                        state =
                            when (it.purchaseState) {
                                Purchase.PurchaseState.PURCHASED ->
                                    BetaPremiumPurchaseState.Purchased
                                Purchase.PurchaseState.PENDING ->
                                    BetaPremiumPurchaseState.Pending
                                else -> BetaPremiumPurchaseState.Unknown
                            },
                    )
                },
            )
        premiumPurchases
            .filter {
                it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged
            }.forEach(::acknowledge)
        updateState {
            it.copy(
                premium = entitlement.premium,
                entitlementVerified = true,
                checking = false,
                pending = entitlement.pending,
                error = false,
            )
        }
    }

    private fun acknowledge(purchase: Purchase) {
        billingClient?.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build(),
        ) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                updateState { it.copy(error = true) }
            }
        }
    }

    private fun updateState(update: (DistributionPremiumState) -> DistributionPremiumState) {
        mainHandler.post { state = update(state) }
    }
}

internal fun refreshDistributionPremium(context: Context) {
    BetaPremiumController.refresh(context.applicationContext)
}
