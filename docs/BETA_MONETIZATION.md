# ScanIt beta monetization checklist

The current beta is intentionally isolated as `com.majkeylab.scanit.beta`. It can coexist with the stable app, but Google Play products are package-scoped. Do not upload this build to the existing ScanIt listing without first choosing one of these paths:

1. Keep the `.beta` package and create a separate Play Console beta app, or
2. remove the suffix, bump `versionCode`, sign with the ScanIt upload key, and upload only to a testing track of the existing app.

## AdMob

- App ID: `ca-app-pub-6991329209066655~2916806906`
- Banner unit: `ca-app-pub-6991329209066655/9690244602`
- Debug builds use Google's next-generation banner demo unit.
- Publish an EEA/UK consent message in AdMob Privacy & messaging.
- Recheck Play Data Safety and the public privacy policy before any monetized Play upload.

## Google Play Billing

- Create a one-time, non-consumable product with ID `scanit_premium`.
- Suggested base price: USD 5.99; Czech price: CZK 199. Let Play localize other prices.
- Add license testers and activate the product before testing the purchase sheet.
- The app queries completed purchases on connection and when its monetized screens resume. Pending purchases never unlock Premium.
- Premium removes ads and unlocks experimental Gemini cleanup.

The beta currently performs client-side Play purchase checks and acknowledgment. Before a public monetized release, add server-side purchase-token verification with the Google Play Developer API to harden entitlement against a modified client.
