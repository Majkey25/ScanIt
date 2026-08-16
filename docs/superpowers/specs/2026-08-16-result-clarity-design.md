# ScanIt Result clarity design

## Scope

Ship a small 1.3.1 usability update on the proven Google ML Kit scanner flow.
The update changes Result and File details presentation only. It must not alter
output replacement, sharing, storage, document Actions, or signature data.

Scanner v2 and the ads/Premium build remain separate unpublished work.

## Product framing

- Audience: people who want to scan, verify, and share a document quickly.
- Primary action: share the PDF.
- Secondary actions: rescan, sign/stamp, run document Actions, share images,
  print, and change saved-file details.
- Main obstacle: tappable text currently looks like labels and the page browser
  does not clearly communicate horizontal navigation.

## Verified platform boundary

The public Google ML Kit Document Scanner API exposes only
`getStartScanIntent(Activity)` for a new scanner flow. It does not accept an
existing result or source pages and cannot reopen its crop/filter editor.

Therefore the stable app must not label the local appearance screen as the
Google editor. The current local appearance Edit entry is removed. The action
is labelled **Rescan** and starts a new Google scanner flow while the existing
scan remains recoverable in Recent. True crop/filter editing of existing pages
is a Scanner v2 requirement.

## Considered layouts

1. **Compact action buttons + page pager (chosen).** Three equal actions remain
   visible, the main preview becomes the page navigator, and File details uses
   a consistent outlined control grid. This preserves discoverability without
   adding menus.
2. Bottom action sheet. It reduces visual density but hides frequent actions
   and contradicts the requirement that all three actions remain visible.
3. Overflow menu. It is smallest but recreates the existing discoverability
   problem and is rejected.

## Result layout

1. Large horizontally swipeable page preview.
   - Keep 12-24 dp of the neighbouring page visible when multiple pages exist.
   - A tap opens the existing full-screen viewer.
   - A completed swipe updates the selected page through the existing
     `onSelectPage` state path.
   - No duplicate thumbnail strip appears below the main preview.
2. Centred `current of total` status, including `1 of 1`.
   - TalkBack receives the full localized page description.
3. Action grid.
   - Rescan, Sign / stamp, and Actions are real Material buttons with icon and
     label, a minimum 48 dp touch target, and no ellipsis.
   - Use equal columns when labels fit; stack at large font or narrow measured
     width.
4. Full-width primary **Send PDF** button.
5. Equal secondary **Send images** and **Print** outlined buttons.
6. Existing expandable File details.

## File details layout

- Preserve the existing PDF and Images sections and metadata rows.
- Replace action `TextButton`s with `OutlinedButton`s.
- PDF: Size and Location share one equal-width row when they fit; otherwise
  stack.
- Images: Size and Format share one equal-width row when they fit; Location is
  a full-width row below.
- Buttons use 48 dp minimum touch targets and wrap vertically at large font.
- Replace the PDF vector containing letter-shaped `P`/`D` paths with a plain
  folded document outline and horizontal rules. The adjacent section title
  already communicates PDF.

## Accessibility and responsive behavior

- Never ellipsize action labels.
- At font scale 1.3 or narrow content, stack action buttons rather than clip.
- Keep semantic selected-page state and localized page descriptions.
- Disabled/busy states continue using the existing Result action gates.
- Preserve monochrome color roles, borders, and existing Material shapes.

## Error handling and state

- Page swipes use the existing bounded page-index resolver.
- Loading and unavailable previews keep their existing visible states.
- Rescan never deletes or mutates the current scan before a new scan succeeds.
- Back, rotation, process restore, sharing, output changes, and full-screen
  preview retain their existing ViewModel authority.

## Verification

- TDD: page label/status policy, responsive action layout policy, and page
  selection synchronization fail before production changes and pass after.
- Full internal JVM tests, lint, and assemble.
- Emulator API 35: one-page and multi-page Result, swipe/peek, full-screen,
  Sign/stamp, Actions, File details controls, 320 dp equivalent, font scale
  1.3+, rotation, process restart, and no crash/ANR.
- Regression: share PDF/images, output dialogs, Recent row open, scanner launch,
  and existing scan preservation after Rescan cancellation.
- Re-run the release verifier and signed artifact gate before publication.
