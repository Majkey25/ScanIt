# Dual Premium Billing Design

## Goal

Let users unlock the same SeliaScan Premium entitlement through either a monthly subscription or a lifetime purchase. Test both paths through Google Play without changing the production or closed-testing releases.

## Products and prices

- Lifetime product: `seliascan_premium`, one-time buy option, 299 CZK.
- Monthly product: `seliascan_premium_monthly`, auto-renewing subscription with base plan `monthly`, 49 CZK per month.
- Google Play converts the Czech base prices for other regions.
- Neither product has a free trial, introductory offer, discount, or prepaid plan.
- Both products remove ads and unlock every Document Action.

## App behavior

The Premium card and paywall show two purchase buttons. Each button uses the localized price returned by Google Play. The lifetime button says that the price is paid once. The monthly button says that billing renews monthly until cancellation.

`BetaPremiumController` queries `seliascan_premium` as `INAPP` and `seliascan_premium_monthly` as `SUBS`. The controller starts the selected billing flow with the matching offer token. One Restore purchases action queries both product types and combines the results into one Premium entitlement.

A `PURCHASED` item for either known product grants Premium. A `PENDING`, unknown, or unrelated item grants nothing. The controller acknowledges each confirmed purchase and does not store a local Premium flag or purchase token.

## Failure handling

The controller combines the one-time and subscription queries before it publishes entitlement state. A failed query does not revoke an entitlement that the current session already verified. Ads remain blocked while the initial entitlement check is incomplete.

If Google Play cannot return one offer, the app keeps the other purchase button usable. If Billing is unavailable, the app shows the existing clear error and keeps Document Actions locked.

No app backend is added. Google Play purchase queries remain the entitlement authority. Add server verification only if fraud, cross-platform accounts, or revenue scale makes it necessary.

## Version and distribution

- Beta version code: 31.
- Beta version name: `1.6.0-vip-ads.5`.
- Keep `applicationId` and package `com.majkeylab.scanit`.
- Upload the signed AAB only to Google Play Internal testing.
- Do not change the production, closed-testing, or open-testing releases.

## Play Console setup

1. Upload the Billing-enabled `.5` AAB to Internal testing.
2. Create and activate the lifetime product and its buy option.
3. Create and activate the monthly subscription and the `monthly` base plan.
4. Set Czech prices to 299 CZK and 49 CZK per month.
5. Add the test Google account as both an Internal tester and a license tester.

## Verification

Automated tests cover both product IDs, combined entitlement, pending purchases, unrelated products, partial product availability, and release metadata. Existing ad and Document Action policy tests must remain green.

Emulator QA uses a separate Google Play Store AVD and a license-test account. Test these flows:

- lifetime purchase succeeds and survives app restart;
- monthly purchase succeeds and survives app restart;
- pending and declined payments do not unlock Premium;
- subscription cancellation keeps Premium until expiration;
- expiration removes Premium after a fresh purchase query;
- Restore purchases works after reinstall;
- Premium hides ads and unlocks Document Actions;
- a free account still sees ads and locked Document Actions.

Delete the temporary SeliaScan AVD after testing. Do not access a physical phone.
