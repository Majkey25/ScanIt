# Direct Signing Release Design

## Goal

Make ScanIt's existing visual signature/stamp feature feel like signing a document: create or reuse a mark, drag it directly on the selected page, size it, apply it, then ship and install version `1.2.0-beta.2`.

## Product boundary

- This is a visual handwritten signature/stamp burned into the selected page.
- It is not a certificate-backed, cryptographic, eIDAS, or identity-verified digital signature.
- Keep the existing plain-language disclaimer visible in English and Czech.
- No new runtime dependency, navigation library, cloud service, account, or permission.

## User flow

1. Result shows `Sign or stamp document` / `Podepsat nebo orazítkovat dokument`.
2. Opening it keeps the current selected page and saved-mark library.
3. A first-time user chooses Draw, Import image, or Scan paper. A saved mark remains reusable.
4. With a mark selected, the user drags the mark itself on the page preview. Dragging elsewhere keeps normal vertical scrolling.
5. Horizontal, vertical, and size sliders remain as precise and screen-reader-accessible controls.
6. `Apply to page N` creates a new local scan revision and PDF through the existing guarded mark pipeline.

## Placement behavior

- Dragging is enabled only when the page bitmap and selected mark bitmap are loaded and the editor is not busy.
- A drag starts only inside the displayed mark rectangle.
- Pixel drag deltas are converted to normalized page coordinates.
- The actual rendered mark rectangle remains fully inside the page. Moving away from an edge has no dead zone.
- The editor state remains owned by `ScanViewModel`; Compose holds no durable placement state.
- Existing sliders and apply rendering use the same `MarkPlacement` value, so preview and final output match.

## UI

- Keep the current minimal black/white editor and top bar.
- Add one short hint below the preview: `Drag the signature to place it.` / `Přetažením podpis umístěte.`
- Keep saved marks, delete confirmation, progress, errors, and disclaimer.
- Do not add pinch zoom, rotation, free-form transform handles, certificate terminology, or another editor screen.

## Release

- Version code `6`.
- Version name and tag `1.2.0-beta.2` / `v1.2.0-beta.2`.
- Update release verifier expectations, changelog, README/release metadata, Play worksheet, and packaged third-party notice version text.
- Build and verify internal APK, Play AAB, GitHub APK, and GitHub AAB with the repository release gate.
- Merge through the protected `main` branch by PR after required checks pass.
- Publish a GitHub prerelease with the verified GitHub APK/AAB and checksums.
- Install the exact final internal APK on the user's physical phone; do not replace the stable public package.

## Verification

- Unit tests: drag conversion, edge clamping, invalid geometry, and unchanged mark rendering policies.
- Host gate: all internal tests, all three lint variants, internal APK, Play AAB, GitHub APK/AAB, and artifact verifier.
- Phone: draw a signature, drag it, apply it to one page, verify resulting PDF/share path, verify Back/cancel and a nearby Recent workflow, then check crash/ANR logs.
- If the physical phone is not connected, install to the emulator for lower-confidence QA and report the exact ADB connection blocker; do not claim phone installation.
