# Changelog

All notable changes are documented here.

## [1.2.1] - 2026-08-13

### Added

- Added a localized Buy Me a Coffee support button at the bottom of Settings. It opens the published ScanIt support page and does not unlock app features.

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
[1.1.0]: https://github.com/Majkey25/ScanIt/compare/v1.0.0...v1.1.0
[1.2.0]: https://github.com/Majkey25/ScanIt/compare/v1.1.0...v1.2.0
[1.2.1]: https://github.com/Majkey25/ScanIt/compare/v1.2.0...v1.2.1
[1.1.0-alpha.1]: https://github.com/Majkey25/ScanIt/compare/v1.0.0-preview.1...main
[1.2.0-beta.2]: https://github.com/Majkey25/ScanIt/compare/v1.0.0-preview.1...v1.2.0-beta.2
