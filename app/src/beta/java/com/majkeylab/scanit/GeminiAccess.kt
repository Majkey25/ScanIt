package com.majkeylab.scanit

import android.content.Context

internal fun distributionAllowsGemini(context: Context): Boolean =
    BetaPremiumController.hasCachedEntitlement(context)
