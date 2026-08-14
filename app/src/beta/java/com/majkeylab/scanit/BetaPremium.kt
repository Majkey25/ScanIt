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
    const val productId = "scanit_premium"
}

internal data class BetaPremiumPurchase(
    val productIds: Set<String>,
    val purchased: Boolean,
)

internal fun hasPremiumEntitlement(purchases: List<BetaPremiumPurchase>): Boolean =
    purchases.any { purchase ->
        purchase.purchased && BetaPremiumConfig.productId in purchase.productIds
    }

internal data class BetaPremiumState(
    val premium: Boolean,
    val loading: Boolean,
    val pending: Boolean = false,
    val formattedPrice: String? = null,
    val purchaseAvailable: Boolean = false,
    val error: Boolean = false,
)

private class BetaPremiumStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("beta_premium", Context.MODE_PRIVATE)

    fun load(): Boolean = preferences.getBoolean("premium", false)

    fun save(premium: Boolean) {
        preferences.edit().putBoolean("premium", premium).apply()
    }
}

internal object BetaPremiumController : PurchasesUpdatedListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private var store: BetaPremiumStore? = null
    private var connectionStarted = false

    var state by mutableStateOf(BetaPremiumState(premium = false, loading = true))
        private set

    fun ensureStarted(context: Context) {
        billingClient?.let { client ->
            if (client.isReady) {
                queryProduct(client)
                queryPurchases(client)
            } else if (!connectionStarted) {
                startConnection(client)
            }
            return
        }
        if (connectionStarted) return
        val applicationContext = context.applicationContext
        store = BetaPremiumStore(applicationContext)
        state = state.copy(premium = store?.load() == true, loading = true, error = false)
        val client =
            BillingClient.newBuilder(applicationContext)
                .setListener(this)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
                ).enableAutoServiceReconnection()
                .build()
        billingClient = client
        startConnection(client)
    }

    private fun startConnection(client: BillingClient) {
        connectionStarted = true
        client.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    connectionStarted = false
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryProduct(client)
                        queryPurchases(client)
                    } else {
                        updateState { it.copy(loading = false, error = true) }
                    }
                }

                override fun onBillingServiceDisconnected() {
                    connectionStarted = false
                    updateState { it.copy(loading = false, error = true) }
                }
            },
        )
    }

    fun hasCachedEntitlement(context: Context): Boolean =
        BetaPremiumStore(context.applicationContext).load()

    fun launchPurchase(activity: Activity) {
        val client = billingClient ?: return
        val details = productDetails ?: return
        val offer =
            details.oneTimePurchaseOfferDetailsList?.firstOrNull {
                it.offerToken != null
            } ?: return
        val offerToken = offer.offerToken ?: return
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
            BillingClient.BillingResponseCode.OK -> processPurchases(purchases.orEmpty())
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> updateState { it.copy(error = true) }
        }
    }

    private fun queryProduct(client: BillingClient) {
        val product =
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(BetaPremiumConfig.productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
        ) { result, detailsResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                updateState { it.copy(error = true) }
                return@queryProductDetailsAsync
            }
            val details =
                detailsResult.productDetailsList.firstOrNull {
                    it.productId == BetaPremiumConfig.productId
                }
            productDetails = details
            val offer =
                details?.oneTimePurchaseOfferDetailsList?.firstOrNull {
                    it.offerToken != null
                }
            updateState {
                it.copy(
                    formattedPrice = offer?.formattedPrice,
                    purchaseAvailable = offer != null,
                )
            }
        }
    }

    private fun queryPurchases(client: BillingClient) {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases)
            } else {
                updateState { it.copy(loading = false, error = true) }
            }
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        val premiumPurchases =
            purchases.filter { BetaPremiumConfig.productId in it.products }
        val premium =
            hasPremiumEntitlement(
                premiumPurchases.map {
                    BetaPremiumPurchase(
                        productIds = it.products.toSet(),
                        purchased = it.purchaseState == Purchase.PurchaseState.PURCHASED,
                    )
                },
            )
        val pending =
            premiumPurchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        if (premium) {
            premiumPurchases
                .filter {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged
                }.forEach(::acknowledge)
        }
        store?.save(premium)
        updateState {
            it.copy(premium = premium, loading = false, pending = pending, error = false)
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

    private fun updateState(update: (BetaPremiumState) -> BetaPremiumState) {
        mainHandler.post { state = update(state) }
    }
}
