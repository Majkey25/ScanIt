# Changelog

All notable changes are documented here.

## [1.8.0] - 2026-09-05

### Fixed

- Preserved early scanner results until startup restoration completes.
- Accepted high-resolution source scans while bounding OCR, barcode, and orientation decode memory.
- Saved automatic PDFs to the selected folder and rejected failed durable settings writes.
- Kept ambiguous or unreadable orientation unchanged instead of guessing.
- Flushed PDF staging files before publication and reduced Manual cleanup memory use.

### Changed

- Improved long-dialog scrolling, title truncation, heading semantics, and Settings accessibility actions.
- Updated the Android toolchain and pinned dependency verification metadata.
- Kept the stable GitHub edition fully unlocked, MIT-licensed, and free of Ads, Billing, Premium, and feature locks.

### Distribution

- Versioned the `github` flavor as `1.8.0` code 40 for Android 10 and newer.
- The separately maintained Google Play edition remains outside this checkout.

## [1.7.0] - 2026-08-30

### Changed

- Published the complete SeliaScan feature set as the stable GitHub edition.
- Kept every scan, signing, file-editing, cleanup, OCR, redaction, and Document Action feature unlocked.
- Removed advertising, consent, Billing, Premium, paywalls, locks, and monetization from the source and release artifacts.
- Licensed the public source under the MIT License; Buy Me a Coffee remains optional and unlocks nothing.

### Distribution

- Versioned the `github` flavor as `1.7.0` code 39 for Android 10 and newer.
- Kept the separately maintained Google Play ads and Premium build unchanged.

## [1.6.0] - 2026-08-26

### Changed

- Promoted the current document workflow, Actions tools, local cleanup, OCR languages, redaction, and high-resolution scan fixes to the stable GitHub build.
- Kept every Document Action unlocked without a Premium purchase.
- Removed advertising, consent, Billing, paywall, lock, and monetized flavor source from the public checkout.
- Reduced public CI to the no-ads GitHub release and its isolated internal QA build.

### Security and performance

- Kept Android backup disabled, sharing limited to read-only scoped content URIs, and release dependency checksums pinned.
- Verified that the GitHub artifact has no Internet, advertising ID, Billing, broad storage, camera, account, or media permission.
- Measured the final no-ads build at 249–301 ms cold startup, 46.9 MB PSS, and 0–3.25% janky frames during repeated warm Settings scrolling on an Android 10 Huawei device.

### Distribution

- The stable GitHub APK and AAB contain no Ads SDK, consent SDK, Google Play Billing, Premium UI, paywall, ad slot, interstitial, or locked Action row.
- Versioned only the `github` flavor as `1.6.0` code 36. The no-ads build will not be uploaded to Google Play.
- The separately maintained ads and Premium build remains a distinct Google Play/GitHub binary and is not built from this checkout.

## [1.6.0-vip-ads.9] - 2026-08-25

### Fixed

- Saved high-resolution original scanner JPEGs to Gallery on Android 10 devices instead of rejecting them with the 12 MP rendered-export limit.
- Displayed the real resolution of saved high-resolution originals in File details.
- Rendered Straight line redactions with square ends in both the editor preview and the permanent flattened image; Brush keeps round ends.
- Applied a successful Google Play purchase callback immediately, closed the paywall, and returned to the still-open unlocked Document Actions.

### Ads

- Initialized GMA after consent before the first banner placement needs it.
- Reused the loaded anchored adaptive banner for 60 seconds across Result, Recent scans, and Settings instead of requesting a new banner during quick navigation.
- Replaced the large empty loading surface with a compact 50 dp progress slot; failed loads collapse completely.
- Kept the Google-optimized large anchored adaptive size after verifying that larger eligible inventory can help performance but does not guarantee higher revenue by itself.

### Changed

- Replaced the Line / Brush chips with a Material 3 segmented control and rounded the redaction canvas surface.
- Prepared version code 35 for Closed testing and the matching GitHub prerelease.

## [1.6.0-vip-ads.8] - 2026-08-25

### Fixed

- Accepted streamed scanner JPEG pages up to common 200 MP dimensions, fixing Huawei camera scans that exceeded the previous PDF bound.
- Kept oversized black-and-white pages on the streaming JPEG PDF path to avoid memory-heavy bitonal conversion.

### Distribution

- Prepared version code 34 for Play testing without changing an existing track.

## [1.6.0-vip-ads.7] - 2026-08-25

### Fixed

- Renamed the launcher label from SeliaScan VIP Ads to SeliaScan in every supported app language.

### Distribution

- Released version code 33 to Closed testing with real Google Play payments for non-license testers.

## [1.6.0-vip-ads.6] - 2026-08-25

### Fixed

- Kept the Recent scans banner composed while scrolling so it no longer reloads on every return.
- Hid the visible empty banner surface while an ad is still loading.
- Highlighted Premium separately from donations and clarified that Buy Me a Coffee does not remove ads or unlock features.

### Changed

- Added an anchored banner to Settings.
- Increased the scan interstitial cadence from every fifth to every third completed scan while preserving the three-minute cooldown.
- Released version code 32 to Internal testing only.

## [1.6.0-vip-ads.5] - 2026-08-24

### Added

- Added a 49 CZK monthly Premium subscription alongside the 299 CZK lifetime purchase.
- Added separate monthly and lifetime purchase buttons using prices returned by Google Play.

### Security

- Combined one-time and subscription purchase queries before publishing entitlement, so one product type cannot overwrite the other.
- Kept pending, unknown, unrelated, and failed purchases locked. No local Premium flag or purchase token is stored.

### Distribution

- Prepared version code 31 only for Google Play Internal testing. Public and closed-testing releases remain unchanged.

## [1.6.0-vip-ads.4] - 2026-08-24

### Changed

- Renamed the user-facing app and current documentation to SeliaScan while preserving the existing `com.majkeylab.scanit` package and repository URLs.
- Renamed the unopened lifetime Premium product ID to `seliascan_premium`.

### Play Console

- Completed the merchant profile with the `SELIASCAN` card statement descriptor.
- Saved `SeliaScan: PDF Scanner` as an unpublished main store-listing draft.

### Known limitation

- Play Console will not allow creation of the one-time Premium product until an APK or AAB with Google Play Billing is uploaded. No app build or release track was changed for this preparation.

## [1.6.0-vip-ads.3] - 2026-08-24

### Added

- Restored a Play-account-based lifetime Premium flow that removes ads and unlocks all Document Actions.
- Added a visible lock and purchase prompt to every Document Action while Premium is not confirmed.

### Security

- Google Play purchase queries are the entitlement authority; ScanIt stores no local Premium boolean or purchase token.
- Pending, unknown, wrong-product, unavailable, and failed purchase states grant no Premium access.

### Known limitation

- The merchant account and `scanit_premium` product are not configured in Play Console yet, so real purchase and restore remain unavailable until that external setup is complete.

## [1.6.0-vip-ads.2] - 2026-08-24

### Added

- Added an inline adaptive banner after a non-empty Recent scans list; the empty state remains ad-free.

## [1.6.0-vip-ads.1] - 2026-08-24

### Added

- Added a separate `ScanIt VIP Ads` prerelease package with one adaptive banner on the completed Result screen.
- Added consent-gated fullscreen ads after returning from Settings and before sharing on every fifth completed scan.
- Expanded current builds to Android 10 and newer (`minSdk 29`).

### Changed

- Limited fullscreen ads to one per three minutes, one Settings-return ad per app session, and no delayed display when an ad is unavailable.

### Privacy

- Added Google Mobile Ads and UMP only to the beta flavor. Stable Play and GitHub variants remain ad-free and without app-owned Internet access.

## [1.5.0] - 2026-08-24

### Added

- Added a straight-line redaction tool alongside the freehand brush; straight line is the default and both share adjustable thickness, undo, redo, and clear-page controls.
- Added Chinese text recognition alongside the Latin model, which includes Czech, English, German, and Spanish.
- Added selectable read-all-pages languages with Auto, Czech, English, German, Spanish, and Simplified Chinese choices.

### Changed

- Grouped Settings into collapsible General, Saving, Scanning, Sharing, and Advanced categories while keeping Support and App info separate.

### Security

- Kept OCR script settings bounded and scoped cached OCR to the selected resolved script.

## [1.4.1] - 2026-08-23

### Changed

- Replaced manual redaction boxes with an opaque black freehand brush, adjustable thickness, page-aware undo and redo, and clear-page controls.

### Security

- Manual redaction strokes are permanently rasterized into a protected child revision before its PDF is built; they are not removable annotations.

## [1.4.0] - 2026-08-23

### Added

- Added grouped on-device Actions for finding and reading text, receipt/contact candidates, automatic Safe Share, manual redaction, and whiteboard cleanup.
- Added a distinct local vector icon for every action and a review-first manual redaction flow that creates a protected child revision.
- Added Smart cleanup for local contrast, white balance, sharpening, stain removal, and conservative edge-finger removal.
- Added Manual cleanup: draw one or more loops around spots and replace them locally with the surrounding paper color.

### Fixed

- Rapid scrolling in Actions no longer moves, glitches, or dismisses the sheet; only the action list owns vertical gestures.
- New scanner pages are conservatively normalized to the most likely readable orientation before preview, saving, and PDF creation.
- ScanIt keeps one launcher and starts directly in the scanner.

### Security

- Kept untrusted text, QR/barcode payloads, redaction geometry, and action callbacks bounded and tied to the exact active scan.
- Cleanup operations preserve the parent revision and bound work, memory, cancellation, and selection geometry.
- Public variants contain no Internet permission, cloud document processing, advertising SDK, billing SDK, analytics SDK, or broad storage permission.

### Performance

- Kept action rendering lazy and image analysis bounded to one sampled bitmap or a 2-megapixel cleanup region at a time, with explicit cancellation and bitmap cleanup.

## [1.3.8] - 2026-08-17

### Fixed

- File details keeps Deleted status rows aligned and detects externally deleted saved images.
- Missing PDFs and images can each be recreated directly with Save without reusing stale provider URIs.

## [1.3.7] - 2026-08-17

### Fixed

- File details now shows only the containing folder in Location; the file name remains in its separate editable field.

## [1.3.6] - 2026-08-17

### Fixed

- Replaced technical provider URIs in File details with readable storage paths.
- Detects a saved PDF that was deleted outside the app and offers a direct Save action to recreate it.

## [1.3.5] - 2026-08-17

### Changed

- Kept PDF and image renaming compact in the existing File details header, with an inline editable name, a fixed visible extension, and confirm/cancel actions in place of the pencil.
- Replaced vague saved-location labels with the full resolved output path, while retaining the exact content URI when a provider has no physical filesystem path.
- Replaced the Review every page store screenshot with a correctly framed English scan and refreshed File details to match the released UI.

## [1.3.4] - 2026-08-16

### Added

- Added inline PDF and image renaming in File details with exact byte-preserving replacement and verified cleanup of the previous saved output.

### Changed

- Shortened the Actions label to “Extract text”.
- Removed the ellipsis from the Custom PDF size option.
- A fresh app launch now opens the scanner instead of restoring the previously opened Result screen; completed scans remain available in Recent.

## [1.3.3] - 2026-08-16

### Changed

- Tightened Rescan, Sign / stamp, and Actions to compact 48 dp buttons with smaller icons and spacing while preserving accessible touch targets.
- Refreshed the English and Czech Result screenshots to match the final compact layout.

## [1.3.2] - 2026-08-16

### Added

- Added measured 200 KB, 500 KB, and 1 MB PDF targets plus custom whole-kilobyte targets from 1 KB to 500 MB.
- Restored Privacy policy, third-party notices, source code, and the installed version inside a compact expandable App info section.

### Changed

- Reduced the height of Rescan, Sign / stamp, and Actions while keeping clear button affordances and 48 dp minimum touch targets.
- Put image Size and Location beside each other with the wider Format action below in File details.
- Added clear icons to the PDF section and the Draw, Import image, and Scan paper signature actions.
- Made the support confirmation generic so a future display-name change does not require changing that message.

## [1.3.1] - 2026-08-16

### Changed

- Reworked Result into a swipeable page preview that shows the edge of the next page and keeps a single clear page count.
- Replaced the ambiguous Result links with large Rescan, Sign / stamp, and Actions buttons.
- Reorganized File details into explicit PDF Size / Location and image Size / Format / Location buttons.
- Replaced the letter-like PDF mark with a plain document icon.
- Replaced ScanIt's redundant appearance entry with Rescan, which opens a fresh Google scan session while preserving the existing scan if canceled.

### Performance

- Kept adjacent page previews bounded to 1024 px and verified stable memory after repeated page swipes.

## [1.3.0] - 2026-08-15

### Added

- Added compact per-document controls for changing PDF size and location, plus image size, format, and location from File details.
- Added exact Original image export, high-quality JPEG, and lossless PNG with Original, 3840 px, 2560 px, 1600 px, and custom 320–6000 px size options.
- Added a full-screen, zoomable multipage preview opened by tapping the Result image.
- Added stable on-device Actions for Latin-script text extraction, text export, and selected-page QR/barcode detection.

### Changed

- Tapping a Recent scan now opens its Result directly, while the overflow menu keeps secondary file actions.
- Added an explicit Result Edit action for ScanIt's appearance presets. Crop and perspective correction remain in Google's scan-time editor.
- Saved-output changes now use verified create, metadata commit, and exact old-output cleanup transactions instead of overwriting files in place.
- Moved OCR and barcode recognition to on-demand Google Play services modules, keeping recognition-model payloads out of the APK while processing remains on-device. The model may download before first use.

### Security

- Added strict bounds and stale-request rejection for OCR, barcodes, previews, exports, and storage-provider callbacks.
- Marked copied recognition results as sensitive, kept detected codes inert by default, and restricted URL opening to validated typed HTTP(S) results.
- Enforced SHA-256 dependency verification for the built Windows and Ubuntu CI dependency graphs.

## 1.2.5 test candidate - 2026-08-15

### Added

- Added per-document PDF size changes from File details with Original, 5 MB, 10 MB, 20 MB, and custom 1–500 MB targets without changing the saved default.
- Added on-device document Actions for extracting selectable text from all pages and detecting QR codes or barcodes on the current page.

### Changed

- Reorganized Result around a larger document preview, compact visual-mark and Actions controls, clear sharing actions, and structured PDF and image details.
- File details now show measured sizes, temporary or saved state, and human-readable locations for both PDF and image output.
- Replaced the ambiguous Settings symbol with a conventional gear icon.

## [1.2.4] - 2026-08-14

### Changed

- Expanded support to Android 13 and newer (`minSdk 33`).
- Kept delete-after-sharing callbacks safe on Android 13 and 14 by using the legacy selected-component result while retaining the richer Android 15+ chooser result.

## [1.2.3] - 2026-08-14

### Added

- Added direct drag, pinch-to-resize, and two-finger rotation for visual signatures and stamps on a larger document preview.
- Added a collapsed Manual position panel for precise position, size, and rotation adjustments.

### Fixed

- Settings now persist automatically after every change; Back, scanning, relaunching, and in-place updates no longer discard changes.
- Fresh installs now delete saved PDFs after a sharing app is selected by default while keeping saved images.

## [1.2.2] - 2026-08-13

### Added

- Added a localized Buy Me a Coffee support button at the bottom of Settings. It opens the published ScanIt support page and does not unlock app features.
- Verified that ScanIt preferences survive an in-place app update. Uninstalling, clearing app data, or installing a different package flavor still creates a separate settings store.

## [1.2.1] - 2026-08-13

### Fixed

- Removed the redundant ScanIt appearance-review screen and its unstable intensity and shadow sliders.
- Kept the full Google ML Kit review editor, including page management, crop and rotate, and Google filters such as Grayscale, Black and white, and Shadows.
- Preserved the Google-edited JPEG output directly before showing the normal ScanIt Result and sharing actions.

## [1.2.0] - 2026-08-12

### Added

- Added a dedicated pre-save Review scan step with live preview cards for Natural, Color, Light text, Grayscale, Black and white, and Whiteboard filters.
- Added a remembered intensity for every filter plus one shared shadow-strength control before output files are created.

### Changed

- Restored the full Google ML Kit editor for document scans, including page-specific filters, crop, rotation, cleanup, and multi-page editing.
- Kept Result and sharing focused on completed output actions; appearance editing now happens before saving and sharing.
- Made an unfinished Review scan survive process death and resume the exact cached scan generation.

## [1.1.0] - 2026-08-12

### Fixed

- Preserved the manifest-discovered ML Kit component registrar constructor in minified public builds so the scanner opens from the stable GitHub APK.

### Added

- Added a validated custom PDF size target from 1 MB to 500 MB alongside the measured presets.
- Added complete German, Spanish, and Simplified Chinese app translations behind one compact language picker.

### Changed

- Published the current complete feature set as a stable release and simplified the README to one latest-stable APK download link.

## [1.2.0-beta.2] - 2026-08-12

### Changed

- Prepared the source-visible proprietary license for the first new release at `v1.2.0-beta.2` while preserving every historical MIT grant.
- Removed cloud processing and app-owned network access from the public Google Play and GitHub variants.
- Added a Recent scans dashboard for bounded, temporary working copies.
- Added lazy multi-page result browsing with bounded thumbnails.
- Added remembered color, grayscale, and black-and-white intensity controls with shadow adjustment.
- Added measured Original, 5 MB, 10 MB, and 20 MB PDF size goals with an honest target-miss warning.
- Added manual PDF/image saving, exact saved-output deletion, and optional deletion after a sharing app is chosen.
- Added reusable drawn, imported, or scanned signatures and stamps for a selected page, with direct drag placement and an explicit non-digital-signature disclaimer.
- Added a static privacy, terms, support, and product site for GitHub Pages.
- Updated Google Play listing and App Content/Data Safety worksheets for the public no-cloud build.

## [1.1.0-alpha.1] - 2026-08-07

### Changed

- Set the permanent Google Play package to `com.majkeylab.scanit`.
- Added local release signing support without storing keys or passwords in Git.
- Added an in-app Privacy Policy link and prepared an internal Google Play alpha.
- Kept `v1.0.0-preview.1` as the immutable GitHub stable sideload release.

## [1.0.0-preview.1] - 2026-08-06

### Added

- ScanIt branding and direct launch into ML Kit Document Scanner.
- Android 15+ document scanning with monochrome light/dark UI.
- System-default, English, and Czech language selection.
- JPEG/PDF persistence through MediaStore and the Storage Access Framework.
- Automatic PDF saving enabled by default with a settings toggle.
- PDF/image sharing, system printing, and configurable email subject/body.
- Bounded cache retaining at most eight temporary scan directories.
- Unit tests, Android lint, checksum-pinned Gradle wrapper, GitHub Actions, Dependabot, and local build/verification tools.

Historical copies retain the license supplied with them. See
[Historical MIT releases](HISTORICAL_MIT_RELEASES.md).

[1.8.0]: https://github.com/Majkey25/ScanIt/compare/v1.7.0...v1.8.0
[1.7.0]: https://github.com/Majkey25/ScanIt/compare/v1.6.0...v1.7.0
[1.0.0-preview.1]: https://github.com/Majkey25/ScanIt/releases/tag/v1.0.0-preview.1
[1.5.0]: https://github.com/Majkey25/ScanIt/compare/v1.4.1...v1.5.0
[1.4.1]: https://github.com/Majkey25/ScanIt/compare/v1.4.0...v1.4.1
[1.4.0]: https://github.com/Majkey25/ScanIt/compare/v1.3.8...v1.4.0
[1.1.0]: https://github.com/Majkey25/ScanIt/compare/v1.0.0...v1.1.0
[1.2.0]: https://github.com/Majkey25/ScanIt/compare/v1.1.0...v1.2.0
[1.2.2]: https://github.com/Majkey25/ScanIt/compare/v1.2.1...v1.2.2
[1.2.3]: https://github.com/Majkey25/ScanIt/compare/v1.2.2...v1.2.3
[1.2.4]: https://github.com/Majkey25/ScanIt/compare/v1.2.3...v1.2.4
[1.3.0]: https://github.com/Majkey25/ScanIt/compare/v1.2.4...v1.3.0
[1.3.1]: https://github.com/Majkey25/ScanIt/compare/v1.3.0...v1.3.1
[1.3.2]: https://github.com/Majkey25/ScanIt/compare/v1.3.1...v1.3.2
[1.3.3]: https://github.com/Majkey25/ScanIt/compare/v1.3.2...v1.3.3
[1.3.4]: https://github.com/Majkey25/ScanIt/compare/v1.3.3...v1.3.4
[1.3.8]: https://github.com/Majkey25/ScanIt/compare/v1.3.7...v1.3.8
[1.3.7]: https://github.com/Majkey25/ScanIt/compare/v1.3.6...v1.3.7
[1.3.6]: https://github.com/Majkey25/ScanIt/compare/v1.3.5...v1.3.6
[1.3.5]: https://github.com/Majkey25/ScanIt/compare/v1.3.4...v1.3.5
[1.2.1]: https://github.com/Majkey25/ScanIt/compare/v1.2.0...v1.2.1
[1.1.0-alpha.1]: https://github.com/Majkey25/ScanIt/compare/v1.0.0-preview.1...main
[1.2.0-beta.2]: https://github.com/Majkey25/ScanIt/compare/v1.0.0-preview.1...v1.2.0-beta.2
