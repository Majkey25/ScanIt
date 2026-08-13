# Third-party notices

ScanIt's proprietary license covers only original ScanIt material. The public
Android release includes third-party components under their own licenses and
terms.

The `playReleaseRuntimeClasspath` graph was inspected for ScanIt
`1.2.1` on 2026-08-13. Its direct runtime dependencies are:

- Kotlin standard library 2.4.10.
- AndroidX Activity Compose 1.13.0.
- AndroidX Core KTX 1.18.0.
- AndroidX Compose Material 3 1.4.0 and Compose 1.11.4 modules selected by the Compose BOM 2026.06.01.
- AndroidX Lifecycle ViewModel KTX 2.10.0.
- Google Play services ML Kit Document Scanner 16.0.0 and its runtime dependencies.

## Apache License 2.0 components

Kotlin, KotlinX, JetBrains annotations, AndroidX, JSpecify, Guava
`listenablefuture`, `javax.inject`, and open-source Google/Firebase transport and
encoder components in the resolved graph are distributed under the Apache
License 2.0.

Complete local license text: [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt).
The official reference is <https://www.apache.org/licenses/LICENSE-2.0>.

Copyright notices remain with their respective authors and contributors. No
changes to these libraries are represented by this notice.

## Google ML Kit Document Scanner

`com.google.android.gms:play-services-mlkit-document-scanner:16.0.0` declares
the [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms). Its
scanner UI, models, and related resources are delivered through Google Play
services. The public ScanIt build does not claim ownership of them.

See also:

- [ML Kit Document Scanner documentation](https://developers.google.com/ml-kit/vision/doc-scanner/android)
- [Google APIs Terms of Service](https://developers.google.com/terms)

Android, Google Play, Google Play services, and ML Kit are trademarks of Google
LLC. Kotlin is a trademark of the Kotlin Foundation. Other names and marks
belong to their respective owners. Their inclusion does not imply endorsement.

If a packaged artifact and this summary differ, the notices and license metadata
inside the exact dependency artifact control. Recheck the resolved public
runtime graph before every release that changes dependencies.

## Binary-distribution gate

Before distributing an APK or AAB, package the complete Apache License 2.0 text
and all applicable dependency notices with that binary distribution, then
inspect the exact artifact. A repository link or separate web page alone is not
a substitute for giving binary recipients the required license copy. This
repository document does not claim that the current APK/AAB packaging gate has
already passed.
