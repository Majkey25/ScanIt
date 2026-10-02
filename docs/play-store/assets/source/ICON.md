# Icon assets

`icon-reference.svg` is the supplied design. `icon-master.png` is the selected
ImageGen square master. `../icon.png` is its opaque 512 × 512 Play export.
`feature-graphic.svg` uses the same master for the 1024 × 500 banner.

The Android foreground and monochrome VectorDrawables reproduce the same
document, four scanner corners, and green beam with clean edges at launcher
sizes. The mark is centered at (54, 54) in a 108 dp viewport, about 56 dp wide;
the full-bleed charcoal background is a separate adaptive layer. Android 13+
uses a monochrome layer. Do not add another inset or put the Play square inside
the adaptive foreground: either would change the launcher scale.

Generation: built-in ImageGen, 2 October 2026. Final master prompt:

> Preserve the supplied white folded document, grey lines, white scanner frame,
> and green horizontal beam. Regenerate as a restrained modern flat square
> Android/Play icon on full-bleed opaque charcoal #1D1D1B. Center the compact
> mark within the central 60% of the canvas. Keep the supplied palette. Crisp
> geometric forms, clean negative space. No letters, wordmark, watermark,
> mockup, devices, extra decoration, 3D, shadows, or glossy effects.

The master is retained at 1024 × 1024. Launcher sizing is checked separately
from Play imagery because launchers apply masks and adaptive-layer scaling.
