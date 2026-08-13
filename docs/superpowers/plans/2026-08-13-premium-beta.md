# Premium Beta Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add account-restorable lifetime Premium to the monetized beta, hiding ads and unlocking the existing BYOK Gemini cleanup capability.

**Architecture:** Put Billing, ads, consent, and Gemini code only in the monetized beta source set. Expose small no-op distribution hooks to public variants. A lifecycle-owned Billing controller queries Google Play as the entitlement authority; Compose renders immutable entitlement state.

**Tech Stack:** Kotlin, Jetpack Compose, Google Play Billing Library 9.1.0, Google Mobile Ads SDK, UMP, Android Keystore, existing Gemini client.

---

### Task 1: Premium state contract

**Files:**
- Create: `app/src/beta/java/com/majkeylab/scanit/PremiumEntitlement.kt`
- Create: `app/src/testBetaDebug/java/com/majkeylab/scanit/PremiumEntitlementTest.kt`

- [ ] Write failing pure tests for purchased, pending, canceled, wrong-product, duplicate, restored, and acknowledgement-needed states.
- [ ] Run the focused beta test and capture the missing-contract failure.
- [ ] Implement the minimal reducer with product ID `scanit_premium_lifetime`; grant only purchased matching non-consumable records.
- [ ] Re-run focused tests and verify all pass.

### Task 2: Google Play Billing boundary

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/beta/java/com/majkeylab/scanit/PremiumBillingController.kt`
- Modify: `app/src/beta/java/com/majkeylab/scanit/DistributionUi.kt`

- [ ] Add failing controller-boundary tests for unavailable Play Store, product not configured, pending purchase, completed purchase, acknowledgement failure, restore, and reconnect.
- [ ] Add beta-only `com.android.billingclient:billing-ktx:9.1.0`.
- [ ] Query `INAPP` product details without caching stale `ProductDetails`, launch Billing on the main thread, enable pending purchases, query owned purchases on connect/resume, and acknowledge completed unacknowledged purchases.
- [ ] Keep purchase state in bounded process state and never persist a local Premium boolean as authority.
- [ ] Re-run focused tests.

### Task 3: Premium UI and ad gating

**Files:**
- Modify: `app/src/beta/java/com/majkeylab/scanit/DistributionUi.kt`
- Modify: beta localized `strings.xml` files
- Modify: `app/src/main/java/com/majkeylab/scanit/AppUi.kt` only if a new distribution hook is required

- [ ] Add failing UI-policy tests: Free shows ads + purchase; Premium hides ads + shows owned/restore state; Pending grants nothing.
- [ ] Add a Settings Premium card with live localized price, Buy, Restore, Pending, Owned, and retry states.
- [ ] Gate `DistributionBannerAd()` on consent AND not-Premium.
- [ ] Re-run focused tests.

### Task 4: Premium BYOK Gemini

**Files:**
- Move/reuse: `app/src/internal/java/com/majkeylab/scanit/GeminiClient.kt`
- Move/reuse: `app/src/internal/java/com/majkeylab/scanit/InternalGeminiKeyStore.kt`
- Move/reuse: `app/src/internal/java/com/majkeylab/scanit/InternalGeminiViewModel.kt`
- Move/reuse: `app/src/internal/java/com/majkeylab/scanit/InternalGeminiActivity.kt`
- Modify: `app/src/beta/AndroidManifest.xml`
- Create: beta-localized Gemini resources under `app/src/beta/res/values*/strings.xml`
- Test: `app/src/testBetaDebug/java/com/majkeylab/scanit/GeminiClientTest.kt`
- Test: `app/src/testBetaDebug/java/com/majkeylab/scanit/PremiumGeminiPolicyTest.kt`
- Modify: beta Settings distribution footer

- [ ] Add failing tests proving AI UI is hidden for Free and visible for Premium, key validation is bounded, and cleanup never uses a developer key.
- [ ] Reuse the existing Gemini upload/response bounds and Android Keystore storage.
- [ ] Add the Experimental AI Settings entry only for confirmed Premium.
- [ ] Re-run beta Gemini and entitlement tests.

### Task 5: Beta verification without Play upload

**Files:**
- Verify all beta and public variant manifests/artifacts

- [ ] Run beta unit tests, lint, debug/release assembly, plus public Play/GitHub artifact gates.
- [ ] Emulator-test UMP consent, demo banner, unavailable sideloaded Billing state, fake-debug Premium ad removal, AI Settings visibility, and key workflow.
- [ ] Verify public artifacts contain no ads, Billing, Internet permission, or Gemini material.
- [ ] Do not upload beta. Record that real purchase/restore/refund tests remain blocked until `scanit_premium_lifetime` exists in Play Console and the exact package is installed from a test track.
