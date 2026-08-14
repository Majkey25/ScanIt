# Visual mark gesture controls

## Goal

Make signature placement direct and preview-first. The user manipulates the selected visual mark on the scanned page instead of depending on always-visible sliders.

## Considered approaches

1. Direct transform gestures + collapsed precise controls. Recommended. One finger moves the mark; two fingers resize and rotate it. Existing sliders remain available under `Manual position` for accessibility and exact adjustment.
2. Visible resize/rotation handles. Discoverable, but adds small targets and visual clutter over the document.
3. Sliders only. Simple implementation, but it is the current inconvenient workflow and leaves too little room for the page preview.

## UI

- Increase the document preview height.
- Keep direct one-finger drag.
- Add two-finger pinch to resize within the existing 10–80% width limits.
- Add two-finger rotation around the signature center.
- Update the hint to explain move, pinch, and rotate.
- Put horizontal position, vertical position, size, and rotation sliders in a collapsed `Manual position` section.
- Keep template creation, selection, deletion, disclaimer, and Apply behavior unchanged.

## State and rendering

`MarkPlacement` gains a finite normalized rotation in degrees. Gesture deltas produce one validated placement update. Preview rendering and final JPEG rendering use the same center, size, and rotation. Placement remains bounded to the page as far as the rotated signature geometry permits.

## Failure and accessibility

Gestures are disabled while the editor is busy or no template is selected. Invalid or non-finite gesture input is rejected. The collapsed sliders provide a non-gesture path and expose semantic labels and values.

## Verification

- Unit tests: pan, zoom clamp, rotation normalization, malformed values, and edge bounds.
- Build/lint/unit gate.
- Emulator: drag, pinch smaller/larger, rotate, expand precise controls, apply, reopen generated PDF, and confirm Back/cancel behavior.
