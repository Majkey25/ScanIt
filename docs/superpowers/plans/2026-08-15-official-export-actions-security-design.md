# ScanIt 1.3.0 Export Controls, Actions, and Security Design

## Goal

Ship one production release that keeps the proven Google ML Kit capture and review flow while making Result/File details complete, adding a fullscreen preview, treating reliable document Actions as normal product features, and closing any confirmed security gaps.

Scanner v2 is a separate follow-up. It must not replace Google ML Kit, enter `main`, or be published until its own quality comparison passes. The ads/Premium build is also a separate unpublished beta.

## Release boundaries

### Official release: `1.3.0` / version code `14`

- Keep Google ML Kit Document Scanner and Google Play services.
- Keep the current compact Result layout and visual-mark workflow.
- Add File details controls for PDF size/location and image size/format/location.
- Add a fullscreen, zoomable page preview opened by tapping the Result preview.
- Keep `Actions` as a normal feature. Ship only actions that return real, locally verified results.
- Keep public Play/GitHub variants local-first and without an app-owned `INTERNET` permission.
- Publish only after unit/lint/release gates, emulator QA, phone QA, signed artifact inspection, and a clean security review.

### Scanner v2: local prerelease work only

- Replace Google capture/review only after a same-document benchmark proves acceptable accuracy, speed, memory use, and reliability.
- Work on an isolated `feat/scanner-v2/15-08-2026` branch/worktree.
- Do not merge to `main`, push a release, or upload to Play without later user approval.

### Ads/Premium beta: local alignment only

- Reconstruct from the final `1.3.0` code instead of rebasing the dirty historical beta worktree.
- Keep Ads, UMP, Billing, Gemini, and `INTERNET` isolated to the beta flavor.
- Do not publish or upload this beta.

## File details UX

The approved Variant A hierarchy stays unchanged. Expanding File details adds a 12 dp gap before the PDF card so the card is not attached to the header.

PDF card:

- actual file size;
- target: Original, 5 MB, 10 MB, 20 MB, or Custom MB;
- current status and location;
- `Change size` and `Change location` compact actions.

Images card:

- total actual size, page count, format, and pixel dimensions;
- size: Original, High, Balanced, Small, or Custom maximum long edge;
- format: Original, JPG, or PNG (lossless);
- current status and location;
- `Change size`, `Change format`, and `Change location` compact actions.

Image size values:

- Original: preserve current pixel dimensions;
- High: maximum long edge 3840 px;
- Balanced: maximum long edge 2560 px;
- Small: maximum long edge 1600 px;
- Custom: 320 through 6000 px, additionally bounded by the existing 12 MP decode/render policy.

`Original` performs an exact byte copy when no resize or format conversion is requested. JPG uses Android's JPEG encoder at quality 95. PNG uses Android's lossless PNG encoder. The UI never calls JPEG lossless. FFmpeg is not added: it brings a large native dependency and attack/licensing surface without improving these still-image operations.

## Output transaction

Changing an already saved output must not overwrite it in place.

1. Decode/render into app-private staging with existing pixel/memory limits.
2. Create the destination through MediaStore or a persisted SAF tree.
3. Verify canonical URI/tree relationship, MIME, display name, byte length, dimensions, and SHA-256.
4. Record verified-but-not-active outputs in a bounded staging journal inside `outputs.json` before provider publication.
5. Publish MediaStore rows, reopen them, and verify identity/fingerprint again.
6. Atomically update `outputs.json` so the new output is current and the old exact reference is retained as pending cleanup.
7. Delete the old output through `ExactOutputDeleter` using its recorded identity and fingerprint.
8. Remove staging/pending-cleanup references only after exact rollback or `Deleted`/independently verified `Absent`.

A deletion failure keeps the new output active and the old exact reference journaled for a later retry. It never deletes by filename search, never falls back to a raw URI delete, and never discards the cache metadata needed for recovery.

Existing v1/v2 output metadata remains readable. Untouched legacy entries are not rewritten. The strict codec writes v3 only after a new export operation and bounds all lists/strings/byte fields. `SavedScan` exposes rich saved-image records rather than URI-only state so File details and deletion share one source of truth for format, dimensions, tree, fingerprint, and status.

## Destination behavior

- PDF destination: Downloads or an arbitrary persisted SAF folder.
- Image destination: Gallery album or an arbitrary persisted SAF folder.
- A destination picker changes the current scan only. Settings remain the place for automatic-save defaults.
- Moving means copy, verify, commit metadata, then delete the exact previous output.
- If an output is not saved yet, the operation is a verified save rather than a move.

## Fullscreen preview

Tapping the selected Result page opens an edge-to-edge dialog/page with:

- the selected page;
- pinch zoom and pan;
- double-tap reset;
- page position and close affordance;
- the existing page strip for multipage scans;
- a display-bounded decode performed on `Dispatchers.IO`.

The fullscreen viewer is read-only. It does not duplicate the editor or mutate files.

## Actions

`Actions` is a stable product surface, not labelled beta. The official release exposes only functions backed by real on-device implementations:

- Extract text from all pages;
- copy extracted text;
- export extracted text through Android's document picker;
- detect QR codes and barcodes on the selected page;
- readable QR/barcode results that remain inert unless a later typed-payload implementation is independently validated.

No placeholder cards are shown. Receipt/invoice interpretation, business-card field classification, automatic sensitive-region detection, whiteboard cleanup, form interpretation, and MusicXML/MIDI remain Scanner v2 work until they have dedicated models/parsers, confidence handling, and real acceptance fixtures. Manual visual redaction is not represented as automatic Safe Share.

## Security requirements

- No known Critical/High issue may remain in changed code or final dependencies.
- Public manifests request no app-owned `INTERNET`, camera, broad media, storage, advertising ID, or account permission.
- FileProvider stays non-exported and limited to `cache/share/`.
- Every SAF output requires a canonical document under the selected tree plus persisted read/write access.
- Imports/decodes validate scheme, MIME, byte count, dimensions, page count, and canonical app-private paths.
- All external output mutations use exact identity and SHA-256 checks.
- PendingIntent remains explicit, one-shot, and correctly mutable only where Android chooser callbacks require it.
- Sensitive cache, settings, and mark templates remain excluded from backup/device transfer.
- No API key or secret is present in public sources/artifacts.
- Dependency, license, CodeQL/Dependabot, merged-manifest, permission, signature, R8 mapping, and artifact-content evidence is retained for the release.

Codex Security is not installed in the current tool set. The release therefore uses the repository verifier, Android lint/tests, dependency checksum verification, GitHub security automation where Kotlin extraction is proven, current provider documentation, dependency/advisory checks, and a manual hostile Android review. No claim of absolute security is made.

## Verification and publication

Minimum release evidence:

- focused JVM tests for codecs, size policies, transaction reduction, stale generation refusal, URI/MIME bounds, and action payload validation;
- full `tools/build.ps1` signed gate;
- emulator API 33 and API 36 flows covering happy, edge, failure, rotation, process death, update-preserved settings, multipage, and oversized inputs;
- Samsung API 36 smoke covering scanner, Result, export changes, share/print, visual marks, fullscreen, and Actions;
- public AAB/APK verifier output, SHA-256, permissions, signing certificate, mapping, and no Gemini/Ads/Billing/public `INTERNET` proof;
- GitHub CI and release asset verification;
- closed-track Play upload and review submission only after the exact AAB is verified.
