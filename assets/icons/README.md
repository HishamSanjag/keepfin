# Capture Loop identity

The mark is a continuous cobalt ribbon with an ice bridge. Its open upper fork suggests an open fork; the enclosed aperture suggests collecting and keeping media. It deliberately avoids the download-arrow motif.

`icon.svg` is the source of truth. `npm run icons:build` generates every platform asset from its two ribbon paths. Do not hand-edit generated PNG, ICO or native vector geometry.

| Role | Color |
| --- | --- |
| Ink backdrop | `#10151E` |
| Cobalt ribbon | `#4C7EFF` |
| Ice bridge | `#A7C3FF` |
| Light UI paper | `#F5F3EE` |

Desktop uses a seven-size ICO (16, 24, 32, 48, 64, 128 and 256 pixels) and a 512-pixel PNG. Web receives SVG, PNG, favicon and Apple touch variants. `mark.svg` omits the container for use beside a wordmark; keep its colors and proportions.

Android legacy square and genuinely circular launcher images cover all five densities. Adaptive foregrounds scale the mark to 72% around the center so its complete silhouette stays inside the 66dp safe region of a 108dp canvas. Android 13 themed icons use the matching monochrome geometry. Compact headers use `brand_mark.png` and the About and setup screens `brand_logo.png` (`brand_logo_tile.png` on light backgrounds), also generated.

Keep at least one ribbon width of clear space around a standalone mark. Use the container at 16px; never crop the aperture, rotate the mark, or add shadows and extra gradients to its geometry. The system supplies launcher masks.

Editable palette, identity and desktop action specimens live in [the Figma board](https://www.figma.com/design/cWhv0lOrqOfAleAu6X0Nar?node-id=5-203). Its plan allows one mode per variable collection, so Dark and Light semantic collections are separate. Figma uses Inter for editable reference text; the product uses platform fonts. App IDs, package names, profiles, signing identity and release version are unchanged.

## Icon variants

The app icon is chosen from eight designs in `variants/`: Ink Tide (the main one: the approved shark artwork from `assets/branding/ink-tide`, a raster design), Classic (the original `icon.svg`, kept as `classic.svg`), Keepfin K (`k-fin`), Fin, Keep, Drop, Bookmark and Wave. `variants/index.json` lists them with display names (apps can read it) and has a field `main` that selects the shipped icon (now `ink-tide`). To switch the main icon, change `main`, then run `npm run icons:build` and `npm run installer:art`; every shipped icon (ICO/PNG, web icons, Android launcher, installer art, in-app mark, and `icon.svg`/`mark.svg` here) follows with no other edits. To add a design, drop one SVG into `variants/` and add one line to `index.json`.

Rules for a variant SVG: `viewBox="0 0 1024 1024"` (Classic keeps its original 512 box), a full-bleed square background rect first (platforms and the generator round it), then symbol `<path>` fills only (no strokes, gradients, text or transforms), inside the Android safe circle of 626 px. Any number of paths is fine. The monochrome themed layer is derived by painting every fill white, so keep shapes apart by a gap where colour is the only separator.

Colour tokens (variants default; Classic overrides them in `index.json`):

| Token | Default | Role |
| --- | --- | --- |
| ink | `#1E2128` | background |
| accent | `#2855CE` | main colour (later user-selectable) |
| paper | `#FFFFFF` | white parts |
| accentLight | `#6F8FE6` | highlight; derived as the accent mixed 40% toward white when a custom accent is given |

Colours are substituted by the generators (`scripts/build/build-icon-variants.mjs`), never hand-edited per colour. `renderIcon(id, accent, size, {mono, round, plain})` returns a PNG and `outputsFor(id, accent)` returns every shipped file for any variant and accent. Dry run without touching shipped files: `node scripts/build/build-icons.mjs --variant k-fin --accent '#C2410C' --out "$env:TEMP\kf-icons\try"`, and the same options work for `scripts/build/build-installer-art.mjs`.

A variant entry may set `"fixedColours": true`: its colours are never replaced by an accent (every generator path ignores the accent for it: picker PNGs, ICO, Android outputs, installer art). Classic uses it, so it always keeps its own blue and ink. An entry may also set `adaptiveScale` (Fin uses 0.78, Classic 0.72) to shrink the symbol on Android's adaptive canvas; the symbol in the square tile can then be drawn larger.

### Raster designs (Ink Tide)

A design may use approved artwork instead of paths: its SVG embeds the PNG with a relative `<image href>` that the generator inlines. Extra `index.json` fields: `silhouette` (a flat vector SVG, drawn at `silhouetteScale` on the adaptive canvas, whose shape draws the one-colour notification and Android themed icons; Ink Tide uses the shark outline in `ink-tide-silhouette.svg`, because Android keeps only the shape of those icons) and `logo` (stacked PNGs for About, setup and the installer sidebar; the tile version is for light backgrounds). The Android adaptive foreground of a raster design is a transparent PNG (`adaptiveScale` 0.9 puts the emblem at 72% of the canvas). Windows shows the head through the `Mark` component and the stacked logo through `Logo`, Android through `KeepfinMark` and `KeepfinLogo`. The Windows sidebar shows the full emblem and the brush lettering side by side (`brand/emblem.png`, `brand/wordmark.png`, the art without its clear space) and the head when collapsed; Android's empty pages show the emblem (`brand_emblem.png`) on an ink disc. The art is never redrawn, recoloured or enlarged.
