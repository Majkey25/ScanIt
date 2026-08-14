package com.majkeylab.scanit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
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
            )
        } else {
            BetaAdIds(
                appId = "ca-app-pub-6991329209066655~2916806906",
                bannerAdUnitId = "ca-app-pub-6991329209066655/9690244602",
            )
        }
}

internal data class BetaAdIds(
    val appId: String,
    val bannerAdUnitId: String,
)

internal fun shouldShowBetaAd(premium: Boolean, canRequestAds: Boolean): Boolean =
    !premium && canRequestAds

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
            {
                updateGate(consentInformation)
            },
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

@Composable
internal fun DistributionBannerAd() {
    val activity = LocalActivity.current ?: return
    val premiumState = BetaPremiumController.state
    LaunchedEffect(activity, premiumState.premium) {
        BetaPremiumController.ensureStarted(activity)
        if (!premiumState.premium) {
            BetaConsentCoordinator.ensureRequested(activity)
        }
    }
    if (
        !shouldShowBetaAd(
            premium = premiumState.premium,
            canRequestAds = BetaConsentCoordinator.gate.canRequestAds,
        )
    ) return

    val context = activity.applicationContext
    val ads =
        remember(activity) {
            BetaDistributionConfig.adsForDebuggable(
                debuggable =
                    activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            )
        }
    val adView = remember(activity) { AdView(activity) }

    LaunchedEffect(adView) {
        try {
            withContext(Dispatchers.IO) {
                if (!MobileAds.isInitialized) {
                    MobileAds.initialize(
                        context,
                        InitializationConfig.Builder(ads.appId).build(),
                    )
                }
            }
            adView.loadAd(
                BannerAdRequest.Builder(
                    ads.bannerAdUnitId,
                    AdSize.BANNER,
                ).build(),
                object : AdLoadCallback<BannerAd> {
                    override fun onAdLoaded(ad: BannerAd) = Unit

                    override fun onAdFailedToLoad(adError: LoadAdError) = Unit
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            // Ads are optional. Scanning remains fully usable when loading fails.
        }
    }

    DisposableEffect(adView) {
        onDispose { adView.destroy() }
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { adView },
            modifier = Modifier.width(320.dp).height(50.dp),
        )
    }
}

@Composable
internal fun DistributionSettingsFooter() {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val premiumState = BetaPremiumController.state
    var showAbout by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(activity, premiumState.premium) {
        if (activity != null) {
            BetaPremiumController.ensureStarted(activity)
            if (!premiumState.premium) {
                BetaConsentCoordinator.ensureRequested(activity)
            }
        }
    }

    Text(
        stringResource(R.string.beta_premium_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        stringResource(
            if (premiumState.premium) {
                R.string.beta_premium_active
            } else {
                R.string.beta_premium_body
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!premiumState.premium && activity != null) {
        Button(
            onClick = { BetaPremiumController.launchPurchase(activity) },
            enabled = premiumState.purchaseAvailable && !premiumState.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                premiumState.formattedPrice?.let {
                    stringResource(R.string.beta_premium_buy_price, it)
                } ?: stringResource(R.string.beta_premium_buy),
            )
        }
    }
    if (premiumState.premium && activity != null) {
        OutlinedButton(
            onClick = {
                activity.startActivity(Intent(activity, InternalGeminiActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.beta_ai_cleanup))
        }
        Text(
            stringResource(R.string.beta_ai_experimental),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (premiumState.pending) {
        Text(stringResource(R.string.beta_premium_pending))
    } else if (!premiumState.premium && premiumState.error) {
        Text(
            stringResource(R.string.beta_premium_error),
            color = MaterialTheme.colorScheme.error,
        )
    } else if (!premiumState.premium && !premiumState.loading && !premiumState.purchaseAvailable) {
        Text(stringResource(R.string.beta_premium_unavailable))
    }
    if (
        !premiumState.premium &&
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
    TextButton(
        onClick = { showAbout = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.beta_about))
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.beta_about_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.beta_about_body,
                        betaVersionName(context),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

private fun betaVersionName(context: Context): String =
    try {
        context.packageManager
            .getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            ).versionName
            .orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }
