# Capture workbench: Cobalt Atelier

The redesigned interface is an operating tool. Media and transfer state lead; branding lives in the Keepfin fin mark and precise cobalt selection. Avoid promotional hero copy, ornamental statistics, nested cards, and oversized control typography.

Since 1.0 the main screens follow the approved "Cobalt Atelier" direction with direction B's ("Afterglow") player, specified in `dev-docs/plans/MAIN-REDESIGN.md` (which wins over this file where they differ). Concepts: `dev-docs/design/main-redesign/` (kept private). Tokens live in `gui/web/styles/tokens.css`, fonts in `fonts.css`, and the presentation of shell, Library home, Downloads and player in `atelier.css` (loaded last).

## Identity

The Keepfin mark: a cobalt fin above an action-blue download chevron on ink, legible at 16 px and in Android monochrome. Use the reproducible SVG source and its generated desktop, favicon and adaptive Android assets. Preserve package identifiers.

## Copy rule

Never show external tool names (yt-dlp, ffmpeg, gallery-dl, aria2c) in the interface, notifications, errors or release notes. Say "download engine", "gallery engine" or "multi-connection transfers". There is no licenses screen; license texts ship only as files inside the packages (see THIRD-PARTY-NOTICES.md).

## Color roles

Dark: ink background #111318, slate surface #191D25 (inset areas #151920), raised #1F2430, hairline #2A3040, text #EEF0F5, secondary #A5ADBD, tertiary #8D96A8. Light: porcelain background #F5F6F8, white surface, hairline #E1E4EA, text #151821, secondary #4A5263, tertiary #5A6377. Accent cobalt: #2855CE in light; in dark #6F8FF0 (about 6:1 on the ink background; the original #456FEA was only about 4.2:1, too low for text, links and selection), with #9FB8FF for accent-coloured text on dark. Cobalt marks the one primary action, selection and progress only; artwork supplies the rest of the colour. Primary buttons use white text on cobalt in light and dark text #0B1222 on #6F8FF0 in dark (both about 6:1 or better). Semantic green, amber and red appear only for state. Space colours (`--sp-*`) are tuned per theme. Control outlines must meet 3:1; text must meet 4.5:1.

Surfaces separate by tone, not by borders around every panel; hairlines only where rows need them. The only elevation is the player bar, menus and dialogs (`--shadow-bar`, `--shadow-md`, `--shadow-lg`).

## Typography and density

Bundled, no runtime download (SIL OFL, texts in `THIRD-PARTY-NOTICES.md` and `resources/licenses/`): Manrope (headings, 600–800) and Inter (text, 400–600), as variable fonts in `gui/web/assets/fonts/`, with the Windows system faces as fallback while they load. Scale at 100% in px (compact density, the same as the build before the Atelier restyle): display 26/32 (the one page title (h1) of every page, such as "Library" and "Downloads"), title 17/24 (section heads, the Settings title, album and collection titles up to 24), subtitle 14/20, body 13/18, caption 12/16. Android uses the same families as `res/font` and sp. Sizes, speeds, times and counts use tabular figures. Shape: 10px on cards and artwork, 12px on grouped surfaces. Space: `--space-1..4` = 6/12/16/24, 16–24px between sections, more spacing between sections than within rows. Desktop controls are 32px (buttons, inputs, icon buttons; small 28px; one hero button may be 36px) with 16–18px icons, track rows about 44px with 36–40px thumbnails, album and collection covers 120px at the right of the page header (Play, Shuffle and a ⋯ menu under the title), a space page with the same header (title, item count and path, Play all) and one toolbar row. Sidebar rows 32–34px. The player wash is a flat tint-to-canvas gradient with no blur or backdrop filter, and large shadows are gone (one small elevation on menus, dialogs and the mini player). Compact mobile web/native controls stay 44px/48dp. The interface scale of 80–140% keeps working because every size is in px under the root zoom.

## Structure

One left sidebar: the full logo (emblem and brush wordmark, the head alone when collapsed), Home, Search, Library, Downloads (with its count) and Settings, then the library's spaces and playlists as a quiet list (the second Library sidebar is gone), and at the bottom one compact status line (active downloads and connected devices, shown only when there is something to say). There is no theme switch or Support link in the sidebar: the theme follows the system by default (choice in Settings > Appearance) and Support is in Settings > About & help. Library home ("Library"): spaces as a light row of links, Continue watching as wide 16:9 cards with a play button, progress and minutes left, Recently added and Recently played each split into a video shelf (16:9) and a music shelf (squares) so a shelf never mixes shapes, then playlists; items without artwork get a calm gradient from their space colour with the title's initials. Library pages show the item count, a sort menu and a list/grid switch.

Downloads: the page header, its filters (All/Active/Finished/Failed) in the toolbar, the link field with Paste; then the queue as rows with one status line of aligned figures, a thin progress bar and at most two visible actions beside the row menu. A collection kept together is one header row (chevron, cover with the item count, title, counts, its actions) whose items are the same rows as a single download, inline below it; it starts open while anything is moving or failed and closed once everything is saved, and the chevron opens or closes it (the grid view keeps its cards; a card opens the collection in a popup, the same window as the preview, with the summary row and every item, and focus returns to the card on close). A link opens one focused window over the page (`gui/web/preview/`, approved 2026-10-31 as option D of `dev-docs/plans/DOWNLOAD-PREVIEW.md`), the same for every case: a video or song, a song you already have, a playlist, album or channel, several links, a photo post, the moment of reading and a link that could not be read. Its header is the cover, the title, one line of facts and Close; it has two tabs, the main one ("What to save", "Songs · 24", "Links · 5", "Photos · 6") and Extras, whose label counts what was changed from the defaults. The main tab starts with Video | Audio | Both (Both keeps the song's video beside it and is off, with the reason on a line, when none was found), then a Format row and one list under it with no fold (video: MP4, MKV, WebM and every size the engine listed, best first, with resolution, fps, HDR, codec and size; a lossy audio format: Best, 320, 256, 192 and 128 kbps with estimated sizes; Original: the source streams; FLAC and WAV: no list), a row always selected and the Download size matching it. A playlist, album, channel or several links show Format and Quality for all as two compact dropdowns on one line, so the list of items stays the main thing; a list that can be followed also has Keep in sync and how often on this tab, never in Extras. Extras is one group for each thing in plain words (Subtitles, Cut a part, Sponsor parts, Chapters, What goes in the file, Also save beside the file, and for a collection how it is queued). The bottom is always Save to, Add to playlist (when the library is on; the playlist's name once chosen; for a photo post it is absent), Download on… and Download with the size or count. Esc and Close put the window away and keep the link and the review ("Open review" in the field brings it back, Clear discards it); the queue behind never moves. Settings: grouped appearance, download defaults/location, authentication, and app updates. Native compact layout uses bottom navigation; larger screens use a rail. Lists are open rows with quiet separators, not stacked framed cards.

## Page structure (desktop)

Every page is three rows, built by the same components (`gui/web/shell/AppBar.tsx`, `PageHeader.tsx`, `PageToolbar.tsx`), so pages differ in content, not in structure (dev-docs/plans/PAGE-LAYOUT.md).

1. **App bar** (48px, sticky, rendered once by the shell): the global search on the left (about 340px; typing opens Search, a pasted link goes to the download picker; Ctrl+K or "/" focuses it), the update indicator and Activity on the right. Settings is in the sidebar (Ctrl+, opens it), not in the bar. No page draws a bar of its own.
2. **Page header**: an optional breadcrumb ("Music › Albums", "Library › Playlists"; every parent is a link, there is no "Back to ..." button), then exactly one h1 (26/32, 22/28 on a phone), an optional one-line caption (12/16) and the page's actions on the right: at most two buttons plus a ⋯ menu, one of them primary. The h1 is at the same top and left on every page (the layout check asserts it). Detail pages (album, artist, playlist, collection) use the same header: the title is the h1, the facts are the caption, the actions sit under them and the 120px cover sits at the right with a soft glow in the artwork's colour. Settings: the h1 is "Settings" and the chosen category is a section title (17/24) inside the pane; the settings search stays in the settings rail.
3. **Toolbar** (optional, one row of 32px controls): tabs and filters on the left; the small "Filter <scope>" field, sort, view, Select and Add on the right. A list that filters itself never puts a search above the title.

Text fields, selects and segmented controls have a visible boundary, `--field-border` (light #8A93A6, dark #5C6680, about 3:1 on their surfaces); hairlines (`--line`) stay for dividers. Keyboard: Ctrl+K and "/" search, Ctrl+, Settings, Ctrl+B sidebar, Space play/pause (never while typing or on a control that uses Space), Esc closes the full player, the lyrics and dialogs; the list is in Settings > About & help.
## Player (direction B, "Afterglow")

Mini bar: a raised surface with the soft shadow above it, about 64px artwork with 10px corners, title and artist, the transport centred (shuffle, previous, an outlined circular play/pause, next, repeat), the seek bar under the transport across the middle with elapsed and total times in tabular figures, volume and queue on the right, and a very faint tint from the artwork (darkened to a luminance cap in `player/tint.ts`, painted at low alpha, so text keeps AA). Full player and lyrics: a blurred, darkened wash of the artwork behind everything that cross-fades when the track changes (no wash animation under reduced motion); the darkening is a fixed brightness filter plus an opaque veil in CSS, so any artwork ends up dark enough for AA text, and tertiary text uses the secondary colour over the wash. Controls sit on an opaque layer; lyrics keep the current line brightest and the others at 76% text colour, never faded out. The video player keeps its behaviour and takes only the same control and type styling.

## States and motion

Inline busy state and structured progress. Useful empty and filtered states with recovery actions. Expand transfer details in place. Destructive history clearing requires explicit inline confirmation; files remain untouched. Inspection results must never replace a changed link or survive dismissal. Native actions honor Back, keyboard and system insets. Reduced motion (Windows "Animation effects" off, Android "Remove animations") turns every animation into an instant change.

### Motion system

Approved study: `dev-docs/design/mockups/motion.html` (kept private). Motion runs on springs that can be grabbed and redirected mid-way, never fixed-duration timers. Tokens are shared by both apps as (response in seconds, damping ratio); stiffness = (2π / response)², mass 1:

| Token | Response | Damping | Use |
|---|---|---|---|
| quick | 0.24 | 0.90 | press feedback, content swaps, small controls |
| snappy | 0.34 | 0.86 | morphs, covers, rows (a hair of overshoot at most) |
| smooth | 0.46 | 0.94 | containers, lyrics, space card to page |
| sheet | 0.42 | 0.90 | mini player ↔ full player |
| lead / trail | 0.20 / 0.44 | 0.92 / 1.0 | front and back edges of tabs and switch knobs |
| truth | 0.32 | 1.0 | progress: critically damped, never passes the engine's last reported value |

Rules: one shape morphs into its next state; swapped content exits quickly and enters about 70 ms later with a short blur. Drags are direct manipulation and release with the finger's velocity. Animate only transform and opacity in lists. No bounce, glows, particles or gradients on controls. Progress never runs ahead of real engine values.

## Responsive behavior

Desktop rail at wide widths, icon rail at medium widths, compact navigation at narrow widths. No fixed content widths that overflow at 80–140% interface scale. Mobile previews and action areas stack, long titles/URLs wrap or disclose details. Android stays native rather than loading the web UI.

## App updates (desktop)

A single quiet icon button sits at the right of the page header on every screen. Neutral when up to date; when an update is available, downloading or ready it takes the cobalt tint, shows a cobalt dot and the tooltip "Update available · v<version>". It opens a compact popover (not a modal) with the version change, rendered release notes and one primary action (Download, progress, Restart to update, or View releases for Portable). After an update, a one-time "What's new in v<version>" dialog shows that version's notes; the popover can reopen it. Collection discovery notices (e.g. Spotify's first-100 limit) are neutral info banners, never errors.
