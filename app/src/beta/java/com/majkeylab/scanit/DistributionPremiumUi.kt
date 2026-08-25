package com.majkeylab.scanit

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberDistributionPremiumState(): DistributionPremiumState =
    BetaPremiumController.state

@Composable
internal fun DistributionPremiumPaywall(onDismiss: () -> Unit) {
    val activity = LocalActivity.current
    val state = rememberDistributionPremiumState()
    LaunchedEffect(state.premium) {
        if (state.premium) onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.beta_premium_title)) },
        text = { PremiumContent(state, activity) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
internal fun DistributionPremiumSettings() {
    val activity = LocalActivity.current
    val state = rememberDistributionPremiumState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color(0xFFFFDD00),
        border = BorderStroke(1.dp, Color(0xFF111111)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.beta_premium_title),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF111111),
            )
            PremiumContent(state, activity, Color(0xFF111111))
        }
    }
}

@Composable
private fun PremiumContent(
    state: DistributionPremiumState,
    activity: Activity?,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(
                if (state.premium) {
                    R.string.beta_premium_active
                } else {
                    R.string.beta_premium_body
                },
            ),
            color = textColor,
        )
        Text(
            stringResource(R.string.beta_premium_account),
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
        )
        if (!state.premium && activity != null) {
            Button(
                onClick = {
                    BetaPremiumController.launchPurchase(activity, BetaPremiumPlan.Monthly)
                },
                enabled = state.monthlyPurchaseAvailable && !state.checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    state.monthlyPrice?.let {
                        stringResource(R.string.beta_premium_monthly_price, it)
                    } ?: stringResource(R.string.beta_premium_monthly),
                )
            }
            Text(
                stringResource(R.string.beta_premium_monthly_notice),
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
            )
            Button(
                onClick = {
                    BetaPremiumController.launchPurchase(activity, BetaPremiumPlan.Lifetime)
                },
                enabled = state.lifetimePurchaseAvailable && !state.checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    state.lifetimePrice?.let {
                        stringResource(R.string.beta_premium_lifetime_price, it)
                    } ?: stringResource(R.string.beta_premium_lifetime),
                )
            }
            TextButton(
                onClick = { refreshDistributionPremium(activity) },
                enabled = !state.checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.beta_premium_restore))
            }
        }
        when {
            state.checking -> CircularProgressIndicator()
            state.pending -> Text(stringResource(R.string.beta_premium_pending), color = textColor)
            !state.premium &&
                state.error &&
                !state.monthlyPurchaseAvailable &&
                !state.lifetimePurchaseAvailable ->
                Text(
                    stringResource(R.string.beta_premium_error),
                    color = MaterialTheme.colorScheme.error,
                )
            !state.premium &&
                !state.monthlyPurchaseAvailable &&
                !state.lifetimePurchaseAvailable ->
                Text(stringResource(R.string.beta_premium_unavailable), color = textColor)
        }
    }
}
