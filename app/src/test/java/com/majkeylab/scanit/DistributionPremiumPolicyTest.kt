package com.majkeylab.scanit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionPremiumPolicyTest {
    @Test
    fun documentActionsUnlockOnlyForPremium() {
        assertFalse(canRunDistributionDocumentActions(premium = false))
        assertTrue(canRunDistributionDocumentActions(premium = true))
    }
}
