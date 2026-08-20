# Changelog

All notable changes are documented here.

## [1.4.0-beta.1] - 2026-08-20

### Added

- Added grouped on-device Actions for finding and reading text, receipt/contact candidates, automatic Safe Share, manual redaction, and whiteboard cleanup.
- Added a distinct local vector icon for every action and a review-first manual redaction flow that creates a protected child revision.

### Fixed

- Rapid scrolling in Actions no longer moves, glitches, or dismisses the sheet; only the action list owns vertical gestures.

### Security

- Kept untrusted text, QR/barcode payloads, redaction geometry, and action callbacks bounded and tied to the exact active scan.
- Public GitHub and Play variants retain no app-owned Internet permission, Gemini code, advertising SDK, billing SDK, or broad storage permission.

### Performance

- Kept action rendering lazy and image analysis bounded to one sampled bitmap at a time, with explicit cancellation and bitmap cleanup.

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

[1.0.0-preview.1]: https://github.com/Majkey25/ScanIt/releases/tag/v1.0.0-preview.1
[1.4.0-beta.1]: https://github.com/Majkey25/ScanIt/compare/v1.3.8...v1.4.0-beta.1
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
