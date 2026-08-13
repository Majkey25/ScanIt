# ScanIt Support And Premium Design

## Scope

Deliver two isolated products in this order:

1. Public ScanIt `v1.2.1`: add one optional Buy Me a Coffee link at the bottom of Settings. It grants no feature, entitlement, badge, or support priority.
2. Monetized beta: add bottom banner ads, a lifetime Premium purchase through Google Play Billing, and an experimental BYOK Gemini cleanup tool. Premium hides ads and unlocks the AI settings and workflow.

The monetized beta must not be uploaded to Google Play until its Billing product, privacy declarations, and testing are ready.

## Public Support Release

Settings adds a full-width text button after Privacy policy and Third-party notices. It opens `https://www.buymeacoffee.com/majkey` through Android's external URI handler. Nearby copy states that support is optional and grants no feature or priority. The button is localized in English, Czech, German, Spanish, and Simplified Chinese.

No advertising, Billing, Internet permission, AI code, or behavior change enters the Play/GitHub public build. Existing privacy and README text already disclose the external Buy Me a Coffee link and its no-benefit nature.

## Premium Beta

Premium is a Google Play one-time non-consumable product with product ID `scanit_premium_lifetime`. Recommended launch price is USD 4.99 with Play regional pricing; Czech pricing should be reviewed in Play Console, with 129 CZK as the starting recommendation.

Google Play owns the purchase identity. The entitlement restores after reinstall and on another Android device using the same purchasing Google Play account. ScanIt creates no account. Purchases are not transferable between different Google accounts.

The app queries current purchases on startup and resume, grants Premium only for `PURCHASED`, handles `PENDING` without entitlement, acknowledges completed unacknowledged purchases, and offers Restore purchase. A client-only implementation is acceptable for this low-value BYOK feature; a backend remains a future hardening option.

Premium removes beta banner ads. It also exposes an Experimental AI section in Settings. The user supplies a Gemini API key stored with Android Keystore encryption. ScanIt sends only the explicitly selected scan page directly to Gemini, shows the returned preview before applying it, and warns that the feature is experimental and may incur charges on the user's Gemini account. No developer-owned Gemini key is shipped.

Debug builds never charge real money: they use an injected fake entitlement/billing boundary and Google demo ads. Release Billing remains unavailable when the app is sideloaded; real purchase testing requires the exact app package delivered by a Google Play test track and a configured one-time product.

## Policy Boundaries

Buy Me a Coffee is a pure optional tip and never unlocks Premium. Premium, ad removal, and AI access use Google Play Billing because they are digital app functionality. Public Play metadata and Data Safety must be updated before any monetized beta reaches a Play track.

## Verification

- Public: unit tests, lint, signed Play/GitHub artifact verification, emulator Settings link, and public-manifest check for no Internet/ad/Billing/Gemini material.
- Beta: pure entitlement tests covering purchased, pending, canceled, restored, duplicate, and acknowledgement states; lint/build; emulator ad/consent/Premium UI; and later Play license-tester purchase, restore, refund/revocation, and pending-payment checks.
