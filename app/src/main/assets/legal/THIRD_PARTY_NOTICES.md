# Third-party notices

SeliaScan's original source is licensed under the MIT License. Third-party
components keep their own licenses and terms.

The `githubReleaseRuntimeClasspath` graph was inspected for SeliaScan 1.8.0 on
2026-09-05. Direct runtime dependencies are:

- Kotlin standard library 2.4.10.
- AndroidX Activity Compose 1.13.0.
- AndroidX Core KTX 1.19.0.
- AndroidX ExifInterface 1.4.2.
- AndroidX Compose Material 3 1.4.0 and Compose 1.12.0 modules selected by the Compose BOM 2026.08.00.
- AndroidX Lifecycle ViewModel KTX 2.11.0.
- Google Play services ML Kit Document Scanner 16.0.0.
- Google Play services ML Kit Text Recognition 19.0.1 and Chinese Text Recognition 16.0.1.
- Google Play services ML Kit Barcode Scanning 18.3.1.
- Google Play services ML Kit Face Detection 17.1.0.

The stable GitHub edition does not package Google Mobile Ads, User Messaging
Platform, Google Play Billing, or another monetization SDK.

## Apache License 2.0 components

Kotlin, KotlinX, JetBrains annotations, AndroidX, JSpecify, Guava
`listenablefuture`, `javax.inject`, and open-source Google/Firebase transport and
encoder components in the resolved graph are distributed under the Apache
License 2.0. The complete text is in `LICENSES/Apache-2.0.txt`.

The complete MIT License for SeliaScan is in `LICENSES/MIT.txt`.

Copyright notices remain with their authors and contributors.

## Google ML Kit

The ML Kit dependencies above are subject to the
[ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) and
[Google APIs Terms of Service](https://developers.google.com/terms). Scanner UI,
models, and related resources are delivered through Google Play services. A
model may download before first use. SeliaScan processes scan content on-device
unless the user explicitly exports or shares it.

Android, Google Play, Google Play services, and ML Kit are trademarks of Google
LLC. Kotlin is a trademark of the Kotlin Foundation. Other names and marks
belong to their owners. Their inclusion does not imply endorsement.

The exact dependency artifact controls if it differs from this summary.
