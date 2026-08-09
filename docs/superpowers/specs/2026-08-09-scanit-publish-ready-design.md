# ScanIt publish-ready feature design

Date: 2026-08-09
Status: Approved through delegated product authority
Project owner: Majkey25; Play legal identity remains the verified account-holder value

## Goal

Make ScanIt ready for a public Google Play listing without losing its core promise:
open the app, scan a document, and share a usable PDF with as few decisions as possible.

This release adds only four user-facing capabilities:

1. a small recent-scans dashboard,
2. measured PDF size limits,
3. reusable visual signature and stamp templates,
4. a legal, support, and product website.

It also replaces the license for new releases, removes cloud AI from public binaries, and
cleans up the repository and public documentation.

## Product rules

- Fresh launch stays scan-first. No landing-page tap is added.
- The default path needs no setup: scan -> review -> PDF -> share.
- New controls live in Settings, Recent scans, or the result screen.
- Black-and-white Material UI remains the visual language.
- English remains the source/default locale; Czech follows the existing localization.
- No accounts, ads, first-party analytics SDK, folders, tags, cloud sync, or document database.
- No FFmpeg or general PDF editor dependency.
- Failures preserve the original scan and explain what was not applied.

## Alternatives considered

### 1. Full document manager

Room database, folders, tags, OCR search, page grid, and permanent document copies.

Rejected. It changes ScanIt from a quick scanner into a file manager and duplicates files.

### 2. General PDF/FFmpeg editing stack

Use FFmpeg or a large PDF SDK for compression and signing.

Rejected. FFmpeg is aimed at audio/video, does not provide a truthful target-size PDF
contract here, adds native binaries and licensing work, and duplicates Android APIs already
used by the app. A large PDF SDK is unnecessary for a visual mark.

### 3. Native focused extension

Reuse ML Kit scanning, `BitmapFactory`, `Canvas`, `PdfDocument`, MediaStore, and the current
save/share/print pipeline.

Selected. It is the smallest design that implements the requested behavior and keeps the
app understandable.

## Public and internal builds

There is one public feature set. Google Play keeps `com.majkeylab.scanit`. The GitHub APK uses
`com.majkeylab.scanit.github` unless the Play App Signing certificate is proven identical to
the locally controlled release certificate. This prevents an APK from appearing to be an
update that Android cannot install. Both public artifacts compile Gemini implementation code,
its endpoint, its UI, and the app-level `INTERNET` permission out of the binary.

Gemini may remain in a private `debug`/internal developer build for local testing. It is not
uploaded to Play, attached to a GitHub release, or advertised as an end-user feature. Build-type
source sets keep the production binary free of the Gemini endpoint and implementation rather
than relying only on a hidden switch.

Reason: the current Gemini BYOK flow is not suitable for a public consumer build on either
distribution channel. Unpaid API projects can involve product-improvement use and human review,
a scanner predictably handles sensitive documents, and EEA public-client terms cannot be
enforced by a BYOK settings screen.

## Navigation and dashboard

### Launch behavior

- Fresh app start immediately opens the document scanner.
- The top bar contains two quiet icons: Recent scans, then Settings.
- Cancelling the scanner opens Recent scans instead of forcing Settings.
- `New scan` remains the only primary dashboard action.

### Recent scans

The screen is deliberately named `Recent scans`, not `Library`.

It reads the existing bounded `cacheDir/share` scan folders, newest first. Each row shows:

- document name,
- date/time,
- page count,
- PDF file size,
- an overflow menu for Open, Share, and Delete.

The screen explains that recent items are temporary working copies. Android may clear them.
Durable PDFs in Downloads/SAF and images in Gallery are never deleted with a recent item.

The existing eight-scan folder limit remains. Every dashboard entry, including a signed copy,
uses one complete folder. A derived operation writes a new temporary folder, verifies every
expected page/PDF, then atomically makes it visible. The eight-folder bound includes derived
entries. Pruning protects the source and any entry open in the UI.

No Room database, folders, tags, or new storage permissions are introduced.

Opening an item reuses the result screen. A small horizontal page strip lets the user inspect
each page and choose a page for signing/stamping. It does not add reordering, arbitrary page
editing, or background thumbnail work outside visible items.

Navigation transitions are explicit:

- Recent scans opened from a result returns to that result on Back.
- Recent scans reached by cancelling the scanner exits the app on Back.
- Settings returns to the previous result or Recent scans.
- Empty Recent scans shows one `New scan` action and no fake sample content.
- A file removed/evicted while visible is skipped on refresh; opening it shows one message and
  returns to the refreshed list.
- Route, selected cache identifier, selected page, and editor placement survive rotation through
  `SavedStateHandle`; invalid process-restored identifiers fall back safely to Recent scans.

## Smart PDF size limit

Add `PDF size target` in Settings:

- Original (default)
- 5 MB
- 10 MB
- 20 MB

The setting applies to PDF output only. Gallery images and image sharing remain original
quality; the label and help text say this explicitly.

`MB` means 1,000,000 bytes. A target is not a guarantee: if the minimum readable profile still
exceeds it, the app keeps that smallest readable complete PDF and shows both the target and
actual size.

### Algorithm

1. Finish scanning and all visual edits first.
2. For an untouched scan, `Original` keeps the scanner PDF byte-for-byte.
3. For an untouched scan already at or below a selected target, keep it byte-for-byte.
4. A visually edited scan must be rebuilt. `Original` uses the highest-quality profile.
5. Otherwise rebuild from the existing ordered page JPEGs with `PdfDocument`.
6. Try bounded long-edge profiles in order: 2480, 1754, 1240, then 877 pixels.
7. Measure the completed file after each attempt.
8. Atomically replace the working PDF with the first candidate at or below the target.
9. If no readable profile fits, keep the smallest readable complete candidate and show its
   actual size.

The contract is measured output size, not a misleading JPEG-quality percentage.

### Failure handling

- Missing or corrupt page -> preserve original PDF and warn.
- Decode/write/no-space failure -> delete the temporary candidate, preserve original, warn.
- Cancellation -> clean temporary files and preserve original.
- Impossible target -> keep the smallest readable complete PDF and report the actual size.
- Never replace a readable original until a complete candidate has been verified.

## Visual signatures and stamps

This feature creates a visual mark. It does not create a certificate-backed digital signature,
authenticate identity, certify the PDF, or guarantee legal validity. This wording appears in
the app, website, privacy policy, and terms.

### Template creation

Users can create up to 12 private templates by:

- drawing with a finger,
- importing an image,
- scanning one signature or stamp with the existing ML Kit scanner.

Templates are normalized to transparent PNG, cropped to visible content, bounded to a
1024-pixel maximum side, and stored in `noBackupFilesDir/marks`. They are not added to
Gallery, not exposed through FileProvider, not uploaded, and not backed up.

Imported or scanned marks remove near-white background while preserving dark or colored ink.
Blank, malformed, or oversized inputs are rejected without leaving a file. Writes use a
temporary file followed by an atomic rename.

Before saving an imported/scanned template, the user sees crop and transparency previews and
must accept the result. Rotation/EXIF orientation is applied before cropping. Input is capped
at 20 MB, decoded with a safe target size, then normalized to the 1024-pixel bound. The drawing
surface provides Undo, Clear, Cancel, and Save. Templates are identified by thumbnail and
creation order; naming and cloud sync are deliberately omitted.

### Applying a mark

The result screen contains `Sign / stamp`.

The editor lets the user:

- choose a saved template,
- choose one page,
- set horizontal position,
- set vertical position,
- set size.

The same normalized placement calculation drives preview and final rendering. Rotation,
freehand page annotation, multiple marks in one operation, and certificate signing are out of
scope.

Applying a mark creates a new derived scan. The source is never overwritten. Untouched pages
are copied; only the selected page is rendered with the transparent overlay. The PDF is then
rebuilt, passed through the selected size-limit policy, saved, and opened through the existing
result/share/print flow.

The derived entry uses `<original name>_Signed` with a collision-safe numeric suffix. When
automatic PDF/images saving is enabled, applying the mark saves the derived outputs immediately
and leaves the already-saved original untouched. Share and Print always use the result currently
on screen; after a successful apply that is the signed copy.

Any failure deletes incomplete derived files and keeps the source result active.

## Storage and privacy

- Public-release scans and marks remain local unless the user explicitly shares them.
- Recent scan cache remains bounded to eight source folders.
- Mark templates remain bounded to 12 and can be deleted individually.
- `allowBackup=false` stays enabled.
- User-controlled Downloads, Gallery, and SAF outputs are not silently removed.
- Canonical-path checks prevent row/template identifiers from escaping their storage roots.
- All bitmap decoding is dimension-checked and sampled before allocation.
- No account, advertising SDK, first-party behavioral analytics, or payment SDK is added.

## Release correctness fixes

These are required because the requested result is publication-ready, not only feature-complete.

- Persist a validated active-result descriptor in `SavedStateHandle` and reconstruct it from
  the bounded cache after process recreation. If the files were evicted, fall back to Recent
  scans with a clear message.
- Verify destination copy size against the known source size before reporting a MediaStore or
  SAF save as successful. Delete or report incomplete rows instead of accepting a positive but
  truncated file.
- Sharing prefers the durable saved PDF/Gallery URIs when they exist. Cache FileProvider URIs
  are a fallback only. This keeps default email-draft attachments valid when Recent scans is
  later pruned.
- Cache-derived edit operations protect the active source directory from pruning.
- Signed AAB verification checks the exact artifact intended for upload, not only an APK.

## Donation

Buy Me a Coffee is an optional external tip with no reward, feature, badge, membership, or
paywall.

- The Play app and Play-linked website do not show or link it.
- README contains it quietly with a locally stored button image and QR code.
- No remote Buy Me a Coffee image is loaded automatically.
- ScanIt and the repository never receive payment details.

## License and third-party notices

Replace MIT for versions first published with the new notice using a proprietary,
all-rights-reserved license that grants end users permission to install and use unmodified
official binary releases.

Important boundary:

- `v1.0.0-preview.1` and any other code already published under MIT remain MIT.
- The new license cannot revoke rights already granted for historical copies.
- The public GitHub repository remains source-visible, not open source.
- GitHub's own Terms still permit platform-required viewing and forking.

Update README, badges, changelog, Play copy, website, release notes, repository topics, and
Reddit wording. Add `THIRD_PARTY_NOTICES.md` plus a bundled static notice screen under About;
do not add a license-viewer runtime. Third-party code and marks stay under their own terms.

Contributor history and vendored assets are audited before the switch. All current human code
commits are attributed to Majkey25; Dependabot updates do not transfer copyright. Historical
MIT tags/releases are enumerated, including the distributed alpha.2 artifact. The proprietary
cutoff is the first new tag containing the new notice, planned as `v1.2.0-beta.1`.

Do not accept external code contributions under ambiguous proprietary terms. Add a short
`CONTRIBUTING.md` that directs feature requests and bug reports to Issues and says code pull
requests are not currently accepted.

## GitHub Pages site

Publish from `main/docs` with no framework and no deployment service:

- `docs/index.html`: product, features, screenshots, FAQ, and support.
- `docs/privacy.html`: production privacy policy.
- `docs/terms.html`: end-user terms and visual-signature disclaimer.
- `docs/assets/site.css`: shared minimal black-and-white styling.
- local screenshots and logo.
- `docs/.nojekyll`.

The site uses no analytics, cookies, web fonts, trackers, forms, or remote image dependencies.
Support mail opens `mailto:majkeylab@gmail.com`. Privacy URL:
`https://majkey25.github.io/ScanIt/privacy.html`.

Privacy disclosures cover:

- local scan/cache/output retention and deletion,
- local signature/stamp templates,
- ML Kit diagnostic collection,
- support-email processing,
- GitHub Pages hosting,
- external links deliberately limited to support email and repository.

README developer documentation separately labels private debug Gemini as non-production and
links the current provider terms. It is not described as part of the public app privacy flow.

## Google Play completion

Prepare source-backed answers and assets; do not invent legal identity/contact values.

- App access: all Play-build functionality is available without an account.
- Ads: No.
- Category: Productivity.
- Government, financial, and health app declarations: No.
- Account deletion: not applicable because the app has no accounts.
- Target audience: 18+. ScanIt is intentionally marketed as an adult document-workflow and
  visual-signature utility; listing copy and screenshots do not target minors. Reassess before
  any future youth-oriented marketing.
- Store contact: `majkeylab@gmail.com` and the Pages root URL.
- Privacy policy: Pages privacy URL.
- Data Safety: declare current ML Kit diagnostic/device/app-interaction collection according
  to Google's current disclosure; normal on-device scans are not declared as collected.

The website and repository document the answers. Play Console submission remains a separate
reviewed step because legal developer identity and final declarations belong to the account
holder.

Concrete source files:

- `docs/play-store/LISTING_EN.md` and `LISTING_CS.md`.
- `docs/play-store/APP_CONTENT.md` for access, ads, audience, content rating, government,
  financial, and health answers.
- `docs/play-store/DATA_SAFETY.md` with the shipped SDK mapping.
- `docs/play-store/RELEASE_CHECKLIST.md`.
- `docs/play-store/assets/icon-512.png`.
- `docs/play-store/assets/feature-graphic-1024x500.png`.
- `docs/play-store/assets/phone/en/` and `phone/cs/` screenshots.

For the current ML Kit dependency, the worksheet conservatively records Device or other IDs,
Diagnostics, and App interactions as collected, required, not shared, not ephemeral, encrypted
in transit, and used for analytics/diagnostics. Public release scans remain on-device and are
not declared as collected. The final form is cross-checked against the exact merged release
dependencies before submission.

### Store assets

Create faithful assets from the real app and the user's music-sheet scan:

- exact 512 x 512 app icon,
- exact 1024 x 500 feature graphic,
- phone screenshots within Play's accepted aspect ratio,
- repository/website hero and screenshot frames.

Documentation screenshots show `12:12`, remove notification and battery-percentage clutter,
and may retain a simple Wi-Fi indicator. App content itself is not fabricated or materially
altered. Marketing frames can add captions and neutral device framing but must remain faithful
to verified UI.

## GitHub maintenance

Open dependency pull requests are handled conservatively:

- Rebase, rerun required CI, then merge checkout, upload-artifact, Kotlin Compose plugin, and
  Gradle wrapper updates sequentially.
- Re-evaluate AGP only after the wrapper update and merge only with fresh green CI.
- Close Core KTX 1.19 and Lifecycle 2.11 updates because they require an intentional API 37
  migration while this app remains on compile SDK 36.

No red or stale check is treated as mergeable evidence.

## Reddit update

The existing post title contains `open-source`; Reddit titles cannot be edited. Keep the post
only with a prominent first-line correction, or create a new accurately titled update post.
The body must replace MIT/open-source claims with source-visible/proprietary wording.

The linked compression comment belongs to another user and cannot be edited. Reply to it after
the feature is verified. Also reply to the dashboard requester and update the owner's general
release comment. Mention only features that passed device verification and link the final
release/site.

## Versioning and release artifacts

- Port the existing uncommitted R8/deobfuscation and native-symbol settings into this branch.
- Use the next unused Play `versionCode` (at least 5).
- Use a new prerelease version name suitable for the feature set.
- Build `playRelease` AAB and `githubRelease` APK/AAB from the same public feature sources;
  only distribution package metadata differs unless certificate equality is proven.
- Preserve mapping and native-symbol outputs next to the release artifacts when available.
- Never upload fake symbols for stripped third-party native libraries.

## Verification

### Automated

- Unit tests for size-preset parsing, profile selection, placement math, cache parsing/order,
  path validation, template limits, and malformed-input fallbacks.
- Full repository quality gate: unit tests, lint, debug build, and public release APK/AAB.
- Release verifier checks application ID, version code/name, signature, min/target SDK, R8
  mapping, expected distribution, absence of Gemini/`INTERNET`, and the exact signed AAB
  intended for upload.
- Static website link/accessibility check and local HTTP smoke test.
- Asset dimension/aspect validation for every Play image.

### Android device

Verify on the paired physical phone:

1. Fresh launch -> scanner without an extra tap.
2. Cancel -> Recent scans; New scan works.
3. Single and multi-page scans save/share/print as before.
4. Under-limit PDF remains byte-identical.
5. Over-limit PDF fits the selected target and every page renders legibly.
6. Impossible target keeps the smallest readable complete PDF and shows target + actual size.
7. Draw, import, and scan a mark; restart app; template persists.
8. Apply a mark to a selected page; page order/count remain correct.
9. Delete a template and a recent item; durable Gallery/Downloads outputs remain.
10. Public release APK/AAB has no Gemini UI, endpoint, implementation, or callable path.
11. Private debug Gemini remains clearly marked non-production and is not published.
12. English and Czech UI, dark/light mode, back navigation, rotation, and process restart.
13. Default email share uses a durable URI; deleting an old Recent scans entry does not break
    that attachment.
14. Forced short/incomplete destination copy is rejected and cleaned up.

### Public surfaces

- Draft PR diff, checks, screenshots, and rendered Pages preview reviewed.
- GitHub metadata no longer says open source or MIT.
- Historical MIT release remains unchanged and is documented.
- Play declaration worksheet matches the exact shipped `playRelease` build.
- Reddit is updated only after the release URLs and behavior are real.

## Explicitly out of scope

- Cryptographic/certificate PDF signatures.
- OCR search, folders, tags, cloud document library, collaboration, or accounts.
- Ads or an ads SDK.
- Paid features, donation rewards, or Play Billing.
- Editing arbitrary external PDFs.
- Guaranteed compression of image-share output.
- API 37 migration solely to satisfy optional Dependabot updates.
- Production cloud AI service.
