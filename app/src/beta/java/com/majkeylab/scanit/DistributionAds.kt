package com.majkeylab.scanit

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.view.View
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
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

internal fun shouldShowBetaAd(
    premium: Boolean,
    entitlementVerified: Boolean,
    canRequestAds: Boolean,
): Boolean = !premium && entitlementVerified && canRequestAds

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
                InitializationConfig.Builder(ads.appId).build(),
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
internal fun DistributionBannerAd() {
    val activity = LocalActivity.current ?: return
    val premiumState = BetaPremiumController.state
    LaunchedEffect(activity, premiumState.premium, premiumState.entitlementVerified) {
        if (premiumState.entitlementVerified && !premiumState.premium) {
            BetaConsentCoordinator.ensureRequested(activity)
        }
    }
    if (
        !shouldShowBetaAd(
            premium = premiumState.premium,
            entitlementVerified = premiumState.entitlementVerified,
            canRequestAds = BetaConsentCoordinator.gate.canRequestAds,
        )
    ) return

    val ads =
        remember(activity) {
            BetaDistributionConfig.adsForDebuggable(
                activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
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
        val adView =
            remember(activity, widthDp, description) {
                AdView(activity).apply {
                    contentDescription = description
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
            }
        var adLoaded by remember(adView) { mutableStateOf(false) }
        var adFailed by remember(adView) { mutableStateOf(false) }

        LaunchedEffect(adView, adSize) {
            try {
                ensureAdsReady(activity.applicationContext, ads)
                adView.loadAd(
                    BannerAdRequest.Builder(ads.bannerAdUnitId, adSize).build(),
                    object : AdLoadCallback<BannerAd> {
                        override fun onAdLoaded(ad: BannerAd) {
                            adLoaded = true
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            adFailed = true
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                adFailed = true
            }
        }

        DisposableEffect(adView) {
            onDispose { adView.destroy() }
        }

        if (!adFailed) {
            val height = adSize.height.dp
            if (adLoaded) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column {
                        HorizontalDivider()
                        AndroidView(
                            factory = { adView },
                            modifier = Modifier.fillMaxWidth().height(height),
                        )
                    }
                }
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(height),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
    if (
        activity.isFinishing ||
        activity.isDestroyed ||
        BetaPremiumController.state.premium ||
        !BetaPremiumController.state.entitlementVerified ||
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
            activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
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
