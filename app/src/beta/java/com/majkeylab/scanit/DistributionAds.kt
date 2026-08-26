package com.majkeylab.scanit

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object BetaDistributionConfig {
    fun adsForDebuggable(debuggable: Boolean): BetaAdIds =
        if (debuggable) {
            BetaAdIds(
                appId = "ca-app-pub-3940256099942544~3347511713",
                bannerAdUnitId = "ca-app-pub-3940256099942544/9214589741",
                interstitialAdUnitId = "ca-app-pub-3940256099942544/1033173712",
            )
        } else {
            BetaAdIds(
                appId = "ca-app-pub-6991329209066655~2916806906",
                bannerAdUnitId = "ca-app-pub-6991329209066655/9690244602",
                interstitialAdUnitId = "ca-app-pub-6991329209066655/3092133938",
            )
        }
}

internal data class BetaAdIds(
    val appId: String,
    val bannerAdUnitId: String,
    val interstitialAdUnitId: String,
)

private const val BANNER_RETAIN_MILLIS = 60_000L
private val bannerHandler by lazy { Handler(Looper.getMainLooper()) }

private class BetaBannerSlot(
    val activity: Activity,
    val widthDp: Int,
    val ads: BetaAdIds,
    val adSize: AdSize,
    description: String,
) {
    val adView =
        AdView(activity).apply {
            contentDescription = description
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    var loaded by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var users = 0
    var release: Runnable? = null
    private var requestStarted = false

    fun updateDescription(description: String) {
        adView.contentDescription = description
    }

    suspend fun load() {
        if (requestStarted || loaded || failed) return
        requestStarted = true
        try {
            ensureAdsReady(activity.applicationContext, ads)
            adView.loadAd(
                BannerAdRequest.Builder(ads.bannerAdUnitId, adSize).build(),
                object : AdLoadCallback<BannerAd> {
                    override fun onAdLoaded(ad: BannerAd) {
                        bannerHandler.post { loaded = true }
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        bannerHandler.post { failed = true }
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            requestStarted = false
            throw cancelled
        } catch (_: RuntimeException) {
            failed = true
        }
    }

    fun destroy() {
        release?.let(bannerHandler::removeCallbacks)
        release = null
        adView.destroy()
    }
}

private object BetaBannerCoordinator {
    private var current: BetaBannerSlot? = null

    fun slot(
        activity: Activity,
        widthDp: Int,
        description: String,
        ads: BetaAdIds,
        adSize: AdSize,
    ): BetaBannerSlot {
        val slot =
            current?.takeIf {
                it.activity === activity &&
                    it.widthDp == widthDp &&
                    it.ads == ads &&
                    it.adSize == adSize
            } ?: BetaBannerSlot(activity, widthDp, ads, adSize, description).also {
                current?.destroy()
                current = it
            }
        slot.updateDescription(description)
        return slot
    }

    fun acquire(slot: BetaBannerSlot) {
        if (current !== slot) return
        slot.release?.let(bannerHandler::removeCallbacks)
        slot.release = null
        slot.users += 1
    }

    fun release(slot: BetaBannerSlot) {
        if (current !== slot || slot.users <= 0) return
        slot.users -= 1
        if (slot.users > 0) return
        val cleanup = Runnable {
            if (current === slot && slot.users == 0) clear(slot.activity)
        }
        slot.release = cleanup
        bannerHandler.postDelayed(cleanup, BANNER_RETAIN_MILLIS)
    }

    fun clear(activity: Activity) {
        val slot = current?.takeIf { it.activity === activity } ?: return
        current = null
        slot.destroy()
    }
}

internal fun shouldShowBetaAd(
    premium: Boolean,
    entitlementVerified: Boolean,
    canRequestAds: Boolean,
): Boolean = !premium && entitlementVerified && canRequestAds

internal fun betaAdEntitlementVerified(verified: Boolean, debuggable: Boolean): Boolean =
    verified || debuggable

internal class BetaConsentGate {
    var canRequestAds by mutableStateOf(false)
        private set
    var privacyOptionsRequired by mutableStateOf(false)
        private set
    private var requestStarted = false

    fun beginRequest(): Boolean {
        if (requestStarted) return false
        requestStarted = true
        return true
    }

    fun update(
        canRequestAds: Boolean,
        privacyOptionsRequired: Boolean,
    ) {
        this.canRequestAds = canRequestAds
        this.privacyOptionsRequired = privacyOptionsRequired
    }
}

private object BetaConsentCoordinator {
    val gate = BetaConsentGate()

    fun ensureRequested(activity: Activity) {
        if (!gate.beginRequest()) return

        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val requestParameters =
            ConsentRequestParameters.Builder().apply {
                if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    setConsentDebugSettings(
                        ConsentDebugSettings.Builder(activity)
                            .setDebugGeography(
                                ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA,
                            ).build(),
                    )
                }
            }.build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            requestParameters,
            {
                updateGate(consentInformation)
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    updateGate(consentInformation)
                }
            },
            { updateGate(consentInformation) },
        )
        updateGate(consentInformation)
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            updateGate(UserMessagingPlatform.getConsentInformation(activity))
        }
    }

    private fun updateGate(consentInformation: ConsentInformation) {
        gate.update(
            canRequestAds = consentInformation.canRequestAds(),
            privacyOptionsRequired =
                consentInformation.privacyOptionsRequirementStatus ==
                    ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED,
        )
    }
}

private object BetaAdRuntime {
    private const val PREFS_NAME = "vip_ads"
    private const val COMPLETED_SCANS_KEY = "completed_scans"

    private var scanCompletedThisSession = false
    private var shareDueScan: Int? = null
    private var lastInterstitialMillis: Long? = null
    private var settingsInterstitialShown = false

    fun recordDocumentScan(context: Context) {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = preferences.getInt(COMPLETED_SCANS_KEY, 0)
        val next = if (current == Int.MAX_VALUE) 1 else current + 1
        preferences.edit().putInt(COMPLETED_SCANS_KEY, next).apply()
        scanCompletedThisSession = true
        shareDueScan = next.takeIf { it % 3 == 0 }
    }

    fun shouldShow(
        context: Context,
        trigger: DistributionInterstitialTrigger,
        nowMillis: Long,
    ): Boolean {
        val completedScans =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(COMPLETED_SCANS_KEY, 0)
        return when (trigger) {
            DistributionInterstitialTrigger.SettingsReturn ->
                isSettingsReturnInterstitialDue(
                    returningToResult = true,
                    completedScans = if (scanCompletedThisSession) completedScans else 0,
                    shownThisSession = settingsInterstitialShown,
                    nowMillis = nowMillis,
                    lastShownMillis = lastInterstitialMillis,
                )
            DistributionInterstitialTrigger.Share ->
                isShareInterstitialDue(
                    completedScans = completedScans,
                    dueScan = shareDueScan,
                    nowMillis = nowMillis,
                    lastShownMillis = lastInterstitialMillis,
                )
        }
    }

    fun consume(trigger: DistributionInterstitialTrigger) {
        if (trigger == DistributionInterstitialTrigger.Share) shareDueScan = null
    }

    fun markShown(
        trigger: DistributionInterstitialTrigger,
        nowMillis: Long,
    ) {
        lastInterstitialMillis = nowMillis
        if (trigger == DistributionInterstitialTrigger.SettingsReturn) {
            settingsInterstitialShown = true
            shareDueScan = null
        }
    }
}

private suspend fun ensureAdsReady(
    context: Context,
    ads: BetaAdIds,
) {
    withContext(Dispatchers.IO) {
        if (!MobileAds.isInitialized) {
            MobileAds.initialize(
                context,
                InitializationConfig.Builder(ads.appId)
                    .disableSdkCrashReporting()
                    .build(),
            )
        }
    }
    if (InterstitialAdPreloader.getConfiguration(ads.interstitialAdUnitId) == null) {
        InterstitialAdPreloader.start(
            ads.interstitialAdUnitId,
            PreloadConfiguration(AdRequest.Builder(ads.interstitialAdUnitId).build()),
        )
    }
}

@Composable
internal fun DistributionAdsWarmup() {
    val activity = LocalActivity.current ?: return
    val premiumState = BetaPremiumController.state
    val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val entitlementVerified =
        betaAdEntitlementVerified(premiumState.entitlementVerified, debuggable)
    DisposableEffect(activity) {
        onDispose { BetaBannerCoordinator.clear(activity) }
    }
    LaunchedEffect(activity, premiumState.premium, entitlementVerified) {
        if (entitlementVerified && !premiumState.premium) {
            BetaConsentCoordinator.ensureRequested(activity)
        }
        if (premiumState.premium) BetaBannerCoordinator.clear(activity)
    }
    val canRequestAds = BetaConsentCoordinator.gate.canRequestAds
    val ads =
        remember(activity) {
            BetaDistributionConfig.adsForDebuggable(
                debuggable,
            )
        }
    LaunchedEffect(
        activity,
        premiumState.premium,
        entitlementVerified,
        canRequestAds,
    ) {
        if (
            shouldShowBetaAd(
                premium = premiumState.premium,
                entitlementVerified = entitlementVerified,
                canRequestAds = canRequestAds,
            )
        ) {
            ensureAdsReady(activity.applicationContext, ads)
        }
    }
}

@Composable
internal fun DistributionBannerAd() {
    val activity = LocalActivity.current ?: return
    val premiumState = BetaPremiumController.state
    val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    if (
        !shouldShowBetaAd(
            premium = premiumState.premium,
            entitlementVerified =
                betaAdEntitlementVerified(premiumState.entitlementVerified, debuggable),
            canRequestAds = BetaConsentCoordinator.gate.canRequestAds,
        )
    ) return

    val ads =
        remember(activity) {
            BetaDistributionConfig.adsForDebuggable(
                debuggable,
            )
        }
    val description = stringResource(R.string.beta_advertisement)
    BoxWithConstraints(
        modifier =
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.Center,
    ) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(1)
        val adSize =
            remember(activity, widthDp) {
                AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, widthDp)
            }
        val slot =
            remember(activity, widthDp, description, ads, adSize) {
                BetaBannerCoordinator.slot(
                    activity = activity,
                    widthDp = widthDp,
                    description = description,
                    ads = ads,
                    adSize = adSize,
                )
            }
        val adLoaded = slot.loaded

        LaunchedEffect(slot) { slot.load() }

        DisposableEffect(slot) {
            BetaBannerCoordinator.acquire(slot)
            onDispose { BetaBannerCoordinator.release(slot) }
        }

        if (adLoaded) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    HorizontalDivider()
                    AndroidView(
                        factory = {
                            (slot.adView.parent as? ViewGroup)?.removeView(slot.adView)
                            slot.adView
                        },
                        modifier = Modifier.fillMaxWidth().height(adSize.height.dp),
                    )
                }
            }
        }
    }
}

internal fun recordDistributionDocumentScan(context: Context) {
    BetaAdRuntime.recordDocumentScan(context.applicationContext)
}

internal fun showDistributionInterstitial(
    activity: Activity,
    trigger: DistributionInterstitialTrigger,
    onComplete: () -> Unit,
) {
    val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    if (
        activity.isFinishing ||
        activity.isDestroyed ||
        BetaPremiumController.state.premium ||
        !betaAdEntitlementVerified(
            BetaPremiumController.state.entitlementVerified,
            debuggable,
        ) ||
        !BetaConsentCoordinator.gate.canRequestAds
    ) {
        onComplete()
        return
    }
    val nowMillis = SystemClock.elapsedRealtime()
    if (!BetaAdRuntime.shouldShow(activity, trigger, nowMillis)) {
        onComplete()
        return
    }
    BetaAdRuntime.consume(trigger)
    val ads =
        BetaDistributionConfig.adsForDebuggable(
            debuggable,
        )
    val ad = InterstitialAdPreloader.pollAd(ads.interstitialAdUnitId)
    if (ad == null) {
        onComplete()
        return
    }

    var completed = false
    fun finish() {
        if (completed) return
        completed = true
        onComplete()
    }
    BetaAdRuntime.markShown(trigger, nowMillis)
    ad.adEventCallback =
        object : InterstitialAdEventCallback {
            override fun onAdDismissedFullScreenContent() = finish()

            override fun onAdFailedToShowFullScreenContent(
                fullScreenContentError: FullScreenContentError,
            ) = finish()
        }
    try {
        ad.show(activity)
    } catch (_: RuntimeException) {
        finish()
    }
}

@Composable
internal fun DistributionPrivacyOptionsLink() {
    val activity = LocalActivity.current
    if (
        !BetaPremiumController.state.premium &&
        activity != null &&
        BetaConsentCoordinator.gate.privacyOptionsRequired
    ) {
        TextButton(
            onClick = { BetaConsentCoordinator.showPrivacyOptions(activity) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.beta_privacy_options))
        }
    }
}
