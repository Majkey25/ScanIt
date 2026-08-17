# README screenshot refresh design

## Goal

Refresh the ScanIt README graphics so they accurately show the current stable UI while retaining the visual style and the same sheet-music scan used by the existing Reddit gallery.

## Source assets

- Use the eight current English Play Store assets in `docs/play-store/assets/en-US/phone/` as the authoritative UI screenshots.
- Preserve their existing 1080 x 1920 layout, typography, phone framing, and shared sheet-music document.
- Do not generate or invent application UI. Real captured UI remains authoritative.

## README layout

- Replace the outdated top hero with one new wide overview image assembled from four current screens: capture, result/share, file details, and signature placement. Actions remains visible in the complete gallery.
- Add all eight current English screenshots below the introduction in two centered rows of four.
- Keep concise, accurate alt text for every image.
- Replace obsolete `scanit-v1.1-*` image references with version-neutral current asset names.

## Implementation

- Build the wide overview deterministically from the existing screenshots so text and controls remain pixel-accurate.
- Store README-specific output under `docs/images/`.
- Reuse the eight existing Play Store assets directly instead of duplicating them.
- Do not change application code, Play Store assets, release artifacts, or Reddit content.

## Verification

- Verify every source and generated image is a readable PNG with valid dimensions.
- Visually inspect the wide overview and the eight-image README gallery.
- Render README Markdown through GitHub's Markdown API and check image paths.
- Review the final diff for unrelated changes and secrets.
- Publish through a feature branch, PR, required CI, and protected-branch merge.
