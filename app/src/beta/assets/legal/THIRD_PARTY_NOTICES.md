# Third-party notices

ScanIt's proprietary license covers only original ScanIt material. The VIP Ads
beta includes third-party components under their own licenses and terms.

The `betaReleaseRuntimeClasspath` graph was inspected on 2026-08-24. Its direct
runtime dependencies are:

- Kotlin standard library 2.4.10.
- AndroidX Activity Compose 1.13.0.
- AndroidX Core KTX 1.18.0.
- AndroidX ExifInterface 1.4.2.
- AndroidX Compose Material 3 1.4.0 and Compose 1.11.4 modules selected by the Compose BOM 2026.06.01.
- AndroidX Lifecycle ViewModel KTX 2.10.0.
- Google Play services ML Kit Document Scanner 16.0.0 and its runtime dependencies.
- Google Play services ML Kit Text Recognition 19.0.1, Chinese Text Recognition 16.0.1, and their runtime dependencies.
- Google Play services ML Kit Barcode Scanning 18.3.1 and its runtime dependencies.
- Google Play services ML Kit Face Detection 17.1.0 and its runtime dependencies.
- GMA Next-Gen SDK 1.4.0, Google User Messaging Platform 4.0.0, and Google Play Billing Library 9.1.0.

## Apache License 2.0 components

Kotlin, KotlinX, JetBrains annotations, AndroidX, JSpecify, Guava,
`listenablefuture`, `javax.inject`, and open-source Google/Firebase transport and
encoder components in the resolved graph are distributed under the Apache
License 2.0.

Complete local license text: [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt).
The official reference is <https://www.apache.org/licenses/LICENSE-2.0>.

Copyright notices remain with their respective authors and contributors. No
changes to these libraries are represented by this notice.

## Google Mobile Ads and consent

`com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.4.0` and
`com.google.android.ump:user-messaging-platform:4.0.0` are included only in this
beta. Their published Maven metadata declares the
[Android Software Development Kit License](https://developer.android.com/studio/terms.html).
Google Mobile Ads and AdMob also operate under Google's applicable APIs,
advertising, and privacy terms.

## Google Play Billing

`com.android.billingclient:billing:9.1.0` connects this beta to Google Play for
product details, purchases, restoration, and acknowledgement. It is distributed
under the
[Android Software Development Kit License](https://developer.android.com/studio/terms.html).

## Google ML Kit

`com.google.android.gms:play-services-mlkit-document-scanner:16.0.0` declares
the [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms). Its
scanner UI, models, and related resources are delivered through Google Play
services. ScanIt does not claim ownership of them.

The text recognition, barcode scanning, and face detection dependencies use
on-device models delivered through Google Play services. A model may download
before first use. Results remain on the device unless the user copies or shares
them.

See also:

- [ML Kit Document Scanner documentation](https://developers.google.com/ml-kit/vision/doc-scanner/android)
- [ML Kit Text Recognition documentation](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [ML Kit Barcode Scanning documentation](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- [ML Kit Face Detection documentation](https://developers.google.com/ml-kit/vision/face-detection/android)
- [Google APIs Terms of Service](https://developers.google.com/terms)

Android, Google Play, Google Play services, AdMob, and ML Kit are trademarks of
Google LLC. Kotlin is a trademark of the Kotlin Foundation. Other names and
marks belong to their respective owners. Their inclusion does not imply
endorsement.

If a packaged artifact and this summary differ, the notices and license
metadata inside the exact dependency artifact control. Recheck the resolved
runtime graph before every release that changes dependencies.
