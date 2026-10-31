# Google Play 2.0.0 release

This checkout builds both fully unlocked editions. Neither contains Ads, consent,
Billing, Premium, paywalls, or feature locks.

| Edition | Package | Distribution | Optional Coffee |
| --- | --- | --- | --- |
| Google Play | `com.majkeylab.scanit` | `playRelease` AAB | No |
| GitHub | `com.majkeylab.scanit.github` | `githubRelease` APK | Yes |

## Release status

The GitHub 2.0.0 release is public. Google Play approved and published
`58 | 2.0.0 Free full edition` on October 31, 2026 at 12:35 Budapest time.
Console confirms full production rollout to all 178 targeted countries. The
public store also reports version 2.0.0 and its free-edition release notes.
Internal testing published at 12:38 and closed Alpha at 12:48, using the exact
same AAB. Older monetized bundles are inactive.

## Exact released artifact

* File: `SeliaScan-v2.0.0-PLAY-FULL.aab`
* Version: `2.0.0`, code `58`
* Source: `43109f7b66efe818e6d6c612dc38c5392f541341`
* SHA256: `6dd348206d091c0b1675f9e6160da9ac535b1916b55e3c214d94cf204830e047`
* Archive: [GitHub release](https://github.com/Majkey25/ScanIt/releases/tag/v2.0.0)

Upload only the Play AAB to the existing `com.majkeylab.scanit` listing.
The GitHub package is different and cannot update that listing.
Signed artifact checks passed for provenance, certificate, version, permissions,
R8 metadata, alignment, and absence of Ads/Billing/UMP.

The old monthly base plan and lifetime purchase option were deactivated on
October 31 and Console confirmed `Neaktivní` for both. Two unused lifetime promo
campaigns were paused so the purchase option could be deactivated. These changes
prevent new purchases; they do not cancel existing subscription renewals.
See [Google's base-plan guidance](https://support.google.com/googleplay/android-developer/answer/140504?hl=en).

The release-specific Advertising ID check was resolved with Console's
`Vydat bez oprávnění` control. No AD_ID permission was added. After all tracks
published version 58, Ads and Advertising ID declarations were changed to No.
English and Czech store descriptions now explain the free edition and previous
subscription management. Data safety retains required ML Kit diagnostics,
performance metrics, app interactions and identifiers for analytics, encrypted
in transit, without third-party sharing. Advertising location and purchase
history were removed. The donation-free privacy URL remains correct.
These metadata changes are separate from the already-live binary release.
Data safety covers all currently distributed versions,
including closed testing; see [Google's disclosure guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).
The remaining release warning concerns missing third-party native debug symbols.

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
