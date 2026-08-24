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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberDistributionPremiumState(): DistributionPremiumState {
    val context = LocalContext.current
    val state = BetaPremiumController.state
    LaunchedEffect(context) { refreshDistributionPremium(context) }
    return state
}

@Composable
internal fun DistributionPremiumPaywall(onDismiss: () -> Unit) {
    val activity = LocalActivity.current
    val state = rememberDistributionPremiumState()
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
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.beta_premium_title),
                style = MaterialTheme.typography.titleLarge,
            )
            PremiumContent(state, activity)
        }
    }
}

@Composable
private fun PremiumContent(
    state: DistributionPremiumState,
    activity: Activity?,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.beta_premium_account),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.premium && activity != null) {
            Button(
                onClick = { BetaPremiumController.launchPurchase(activity) },
                enabled = state.purchaseAvailable && !state.checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    state.formattedPrice?.let {
                        stringResource(R.string.beta_premium_buy_price, it)
                    } ?: stringResource(R.string.beta_premium_buy),
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
            state.pending -> Text(stringResource(R.string.beta_premium_pending))
            !state.premium && state.error ->
                Text(
                    stringResource(R.string.beta_premium_error),
                    color = MaterialTheme.colorScheme.error,
                )
            !state.premium && !state.purchaseAvailable ->
                Text(stringResource(R.string.beta_premium_unavailable))
        }
    }
}
