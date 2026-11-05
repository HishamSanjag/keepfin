# Keepfin — approved original Ink Tide artwork

These layouts reuse the **top-left artwork from the original four-style concept sheet**. They do not use the rejected redraw. The original ivory, black and cobalt illustration pixels and brush lettering are preserved.

## Files and uses

- `keepfin-original.png`: untouched crop of the approved original logo.
- `keepfin-stacked-transparent.png` / `keepfin-stacked-dark.png`: full logo for splash screens, promotional graphics and larger placements.
- `keepfin-horizontal-transparent.png` / `keepfin-horizontal-dark.png`: emblem beside the original wordmark for wide headers and banners.
- `keepfin-emblem-transparent.png` / `keepfin-emblem-dark.png`: original shark and wave without lettering.
- `keepfin-avatar-dark.png`: centered emblem on a square dark canvas for avatars and larger icon placements.
- `keepfin-wordmark-transparent.png` / `keepfin-wordmark-dark.png`: original brush lettering and cobalt underline.
- `keepfin-stacked-light.png` / `keepfin-wordmark-light.png`: transparent versions for light backgrounds. The emblem is unchanged; only the ivory lettering is recoloured to ink, and the cobalt stroke stays.
- `keepfin-ink-tide-preview.png`: contact sheet of the layouts.
- `source/approved-concept-sheet.png`: unmodified source sheet.

Transparent files remove edge-connected near-black background, retaining the original RGB artwork. They are intended for dark backgrounds; the dark ink and antialiased edges are not optimized for white backgrounds. The dark versions use `#08090B`.

These are native-resolution raster PNGs, extracted from a roughly 600-pixel source logo, not vector masters or newly generated high-resolution art. The detailed emblem needs sufficient display space; it has not replaced or been tested as the application's small launcher icon. No installed branding or generated launcher assets have been changed.

## Reproduce

With Python and Pillow installed, run `python assets/branding/ink-tide/prepare.py` from the repository root. The script crops the source, preserves RGB values, separates the emblem and lettering through their existing negative space, and composes the layouts without redrawing or upscaling them. Only the preview thumbnails are reduced for display.
