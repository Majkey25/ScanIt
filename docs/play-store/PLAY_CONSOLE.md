# Google Play 2.0.0 submission

This checkout builds both fully unlocked editions. Neither contains Ads, consent,
Billing, Premium, paywalls, or feature locks.

| Edition | Package | Distribution | Optional Coffee |
| --- | --- | --- | --- |
| Google Play | `com.majkeylab.scanit` | `playRelease` AAB | No |
| GitHub | `com.majkeylab.scanit.github` | `githubRelease` APK | Yes |

## Submission status

The GitHub 2.0.0 release is public. Its prepared Play AAB has not been submitted.
The last verified Play production version is 1.9.9 with Ads + Premium.
Do not describe the free Play update as approved or available until Console and
the store confirm those states.

## Exact prepared artifact

* File: `SeliaScan-v2.0.0-PLAY-FULL.aab`
* Version: `2.0.0`, code `58`
* Source: `43109f7b66efe818e6d6c612dc38c5392f541341`
* SHA256: `6dd348206d091c0b1675f9e6160da9ac535b1916b55e3c214d94cf204830e047`
* Archive: [GitHub release](https://github.com/Majkey25/ScanIt/releases/tag/v2.0.0)

Upload only the Play AAB to the existing `com.majkeylab.scanit` listing.
The GitHub package is different and cannot update that listing.
Signed artifact checks passed for provenance, certificate, version, permissions,
R8 metadata, alignment, and absence of Ads/Billing/UMP.

## Before sending for review

1. Verify the file hash, signing, package, version, and current Console tracks.
2. Review store copy, screenshots, App Content, Ads ID, and Data Safety against
   the uploaded artifact and any still-distributed older versions. ML Kit and
   Play services telemetry still require disclosure; do not claim zero data collection.
3. Set the Play privacy URL to `https://majkey25.github.io/ScanIt/play/privacy.html`.
   This page has no donation link. Support remains `majkeylab@gmail.com`.
4. Review legacy subscription products and active subscriptions. Removing Billing,
   updating, or uninstalling does not cancel renewals. Explain the change and give
   existing subscribers the Google Play manage/cancel link. Do not claim refunds
   or cancellations unless verified.
5. Keep Play signing and automatic protection enabled. Review current vitals and
   applicable policy notices. No available vitals data means unmeasured, not zero defects.
6. Submit the production release, then verify review and store availability separately.
   Announce the free Play update only after it is available to users.

The previous Ads + Premium binary and source ZIP remain in the labelled
[1.9.9 prerelease archive](https://github.com/Majkey25/ScanIt/releases/tag/v1.9.9-ads).
