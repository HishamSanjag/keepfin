# Capture workbench · Android

Jetpack Compose + Material 3 (`app.keepfin.ui`), coordinated with the root Capture workbench system.
Brand palette below is the fallback; Settings › Appearance can switch to Material You dynamic color
(Android 12+) and light/dark/system. Theme tokens live in `ui/theme/Theme.kt`.

## Structure

`MainActivity` hosts `StudioApp`. `StudioViewModel` owns navigation, capture/inspection generations,
collection review (selection survives recreation) and update state; `StudioEngine` resolves the typed
`EngineApi` (debug builds can install `FakeEngine`/`FakeUpdates` through launch extras for screenshots
and Compose tests). Screens: `downloads/` (the link box and the download preview), `jobs/` (queue rows),
`library/`, `settings/`, `updates/` (top-bar update action, update sheet, what's-new sheet).
Queue rows are keyed by job id and take the immutable `Job`, so a progress tick recomposes only its
row; artwork requests are remembered per model and never reload on ticks. Debug launch extras:
`keepfin.fixtures`, `keepfin.scene` (default|live|library|empty), `keepfin.theme`, `keepfin.page`,
`keepfin.link`, `keepfin.playlist`, `keepfin.update` (available|downloading|ready), `keepfin.sheet`
(update|whatsnew).

## App updates

The top app bar shows an update icon on every main screen. A Material badge dot and the description
"Update available, version X" appear when AppUpdates reports a newer release (checked at start when the
last check is older than six hours). It opens a bottom sheet with notes and Download, progress, Install,
or Check now when up to date. After an update, "What's new in vX" shows once from the bundled
`release-notes.md`; a fresh install shows nothing. The sheet can reopen it.

## Palette and hierarchy (Cobalt Atelier)

Spec: `dev-docs/plans/MAIN-REDESIGN.md`. Tokens live in `ui/theme/Theme.kt` (colour schemes, `StudioColors`) and `ui/theme/Type.kt`.

Dark: ink background #111318, slate surfaces #191D25, raised #1F2430 (highest #262C3A), hairline #2A3040, text #EEF0F5 and
#A5ADBD. Light: porcelain background #F5F6F8, white surfaces, hairline #E1E4EA, text #151821 and #4A5263. Cobalt is the one
accent: #2855CE in light, #6F8FF0 in dark, used for the primary action, selection and progress only. Where cobalt is text or a
thin icon on the dark canvas the lighter `Studio.colors.accentText` (#8CA6FF) is used, because #6F8FF0 alone is about 4.2:1
there. Artwork supplies the rest of the colour; green, amber and red appear only for state. Surfaces separate by tone, with
hairlines only where rows need them; the player bar, menus and dialogs are the only elevated surfaces. Material You
(dynamic colour) still replaces the brand palette when chosen in Settings > Appearance.

Accent means pressable: links, buttons, selected states and switches. Section titles, eyebrows ("Settings ›"), list numbers,
counters and captions use onSurface / onSurfaceVariant, with weight for emphasis.

Top-level tabs (Home, Search, Library, Downloads) share one header: the title at the same place and size, then activity (the
bell, with its badge), then settings (the gear). Help lives in Settings > About & help. Search happens in the Search tab only; a
list that filters itself has a field named for its scope ("Filter Music"). There is no floating Download button: the Downloads
tab and sharing a link to Keepfin are the ways in.

## Type

Manrope (display, headline, title: 600 to 800) and Inter (body and labels: 400 to 600), both SIL OFL, bundled as variable
fonts in `res/font` (`manrope_variable.ttf`, `inter_variable.ttf`; weights come from `FontVariation`, so one file per family
serves minSdk 26). Licence texts are in `assets/third-party/NOTICES.txt`. Scale in sp: display 34/40 (page titles such as
"Your library" and "Downloads"; 40/44 is the desktop size), title 22/28, subtitle 16/22, body 15/21, caption 12.5/16, labels
11 to 14. Text follows Android font scaling. Sizes, speeds, times and counts use tabular figures (`TextStyle.tabular()`).
Corners are 12dp on cards and artwork. Every actionable control has a 48dp minimum touch target.
## Composition

One native app bar identifies Downloads, Library, or Settings. The capture
composer exposes Paste and Preview. A pasted or shared link opens the download preview (below),
which shows real thumbnails and the engine's real sizes. Transfers are open rows with quiet separators, progress and
contextual actions. Downloads' All lists active work, then recoverable failures, then
every saved download, newest first; Finished adds search and status/type filters.
A saved video's picture is the cover inside its file. App updates belong inside Settings.

Compact screens use a full-width bottom navigation bar; expanded widths use a
navigation rail. Content respects system bars, keyboard and cutout insets.
Long copy wraps; sections scroll. Native Back returns secondary destinations to
Downloads. Share intents load a fresh draft, invalidate prior inspection and
automatically preview the new link without a Preview tap.

## The download preview

One full-height `ModalBottomSheet` (through `StudioSheet`, `ui/downloads/PreviewSheet.kt`) over Downloads, built the same way
in every case (dev-docs/plans/DOWNLOAD-PREVIEW.md). `PreviewHost` keeps one sheet composed from reading to result, so it never closes and
reopens when a link is read.

- **Header:** cover (square for a song, 16:9 for a video), title, one line of facts, Close.
- **Two tabs:** the main tab ("What to save", "Songs · 24", "Videos · 24", "Links · 5") and Extras, which counts what was changed from
  the person's defaults ("Extras · 2"; the rule is the core's `PreviewWords.extrasCount`, shared with Windows). A link being read has no tabs.
- **Video | Audio | Both** at the top of the main tab. Both is a song kept with its video: a YouTube link uses the link itself; a music
  link needs the official video the background look found, and while it looks or when none is found Both is off with a line saying why.
  Playlists: Both is "keep music videos where there is one". Several links have no Both.
- **Sizes (one link):** a labelled Format row (video: MP4, MKV, WebM; audio: Original, MP3, M4A, Opus, FLAC, WAV) and one list under it, no
  fold. Video lists every size the engine listed, best first, with resolution, fps, HDR, codec and size; a lossy audio format lists Best,
  320, 256, 192 and 128 kbps with sizes; Original lists the source streams; FLAC and WAV have no list. A row is always selected and
  the Download button names its size.
- **Collections** (playlist, album, channel) are in the same sheet: Format and Quality for all as two compact dropdowns on one line (the
  same for several links), Keep in sync with how often when it is on (only for lists that can be followed, never in Extras), Select
  all and the count, the list, Load more. Closing keeps the review as a
  summary row on Downloads; its X discards it.
- **Extras:** Subtitles, Cut a part (one link only), Sponsor parts, What goes in the file, Also save beside the file, In the queue (a
  collection), and "Use these extras as my default". The phone has no "one file per chapter" or description/info-file choices.
- **Bottom, always above the navigation bar:** Save to and Add to playlist (library on; "No playlist", the person's own playlists, "New
  playlist…"; the chip shows the chosen name), Download on… (when a paired device takes downloads), and Download with the
  size or the count. A song you already have shows its note with Play on the main tab and "Download anyway" at the bottom.
- **Closing:** swipe, Back and Close hide the sheet and keep the link and the choices; a summary row reopens it. Clear discards it.
  A shared link only reads; downloading always takes a tap.
- The phone has no photo posts (no gallery engine), so that case does not exist here.

## Interaction and truth

No hidden automatic download: inspection leads to an explicit download action.
Edits invalidate old results; late inspection callbacks cannot replace a new
shared link. Preserve engine, jobs, cookies, storage, signing and update owners.
Open, export, share file and inspect source are contextual file actions. Clearing
history confirms that saved files remain. Completed videos automatically save in
Movies/Keepfin through MediaStore.Video so gallery album queries can discover
the app bucket and generate frames. Audio uses Music/Keepfin; subtitle/artwork
sidecars use Download/Keepfin. Names remain collision-safe across folders.
Owned earlier saves migrate automatically; Settings offers Repair gallery album.
User-relocated files remain in their chosen folders. Saved rows use local media
frames first, falling back to provider artwork if the phone cannot decode the video.
Show the saved location and an explicit retry if automatic saving fails. Keep Save
a copy to… as an optional action. Public copies survive uninstalling; older private
downloads need saving first. Disabled animation settings remove motion. Progress may
settle onto each reported value with the critically damped `truth` spring but never runs
ahead of it; never claim provider support from a UI screenshot. Motion tokens and rules
are shared with desktop: see "Motion system" in the root DESIGN.md (`ui/theme/Motion.kt`).

## Atelier screens

- **Home and Library.** One job each, the same split as Windows (dev-docs/plans/MEDIA-SURFACES.md): Home is what to play next,
  Library is everything kept by kind (spaces as a wrapping row of 48dp chips, then playlists, albums, artists, videos, photos,
  collections). Every card is `ui/media/MediaCard` (video 16:9, square, round artist, mix, photo, tile) at the core's compact size
  times Card size, in a `section` frame: a snapping row or a grid of at most three rows. Edit Home and Edit Library (pencil in the
  top bar) show, hide and reorder sections (⋮ › Move up / Move down) and switch Row or Grid, with a bar above the navigation for
  Card size and Text. Items without artwork get a gradient cover with the title's initials or a type glyph (`ArtTile`).
- **Library pages.** The header shows the count, a sort and view menu and a visible grid/list switch (`library-view-toggle`).
- **Downloads.** Large title, the link field with Paste, the inspected card, then the sizes as one list under a Format row
  (see the preview section above; the preset cards and "All formats (n)" were replaced on 2026-11-02). The button reads "Download · {size}". Queue rows show at most two visible
  actions: Pause/Resume plus the menu while running, Play/Open plus the menu when finished. A list (playlist, album, channel) is one header row in the same layout (its 16:9 picture with the item count, title, "8 of 12 done", the menu and a chevron) and its items are the same row as a download on its own, with no card around them; it starts closed when everything is finished and open while anything is active or failed, and the chevron remembers the choice.
- **Player (direction B).** The mini player floats above the navigation on a raised card with a soft shadow, a faint wash of the
  artwork colour, 48dp artwork, an outlined round play/pause and the seek bar under the controls with aligned elapsed and
  total times (`mini-seek`). The full player is always dark: a blurred, darkened wash of the artwork (`ArtworkWash`) that
  cross-fades on track change (instant when animations are off), large cover with depth, controls on an opaque layer, and
  lyrics at no less than 70% opacity. The wash uses a constant scrim, so text clears WCAG AA over even a white cover
  (unit-tested). The video player only takes the new type and colours.

## Spaces v2 (Library)

Spec: `dev-docs/architecture/SPACES-V2.md`; approved mockups: `dev-docs/design/mockups/spaces-v2.html` (kept private).

- **Hub.** Library home shows the pinned spaces (at most five) as chips in the space's colour and icon, then an
  "All spaces · N more" chip. (Edit mode keeps the 56dp card grid below.) Edit turns the same grid into edit mode (`HubEditScreen`): long-press and drag any card (built-ins
  included; `sh.calvin.reorderable`), a 48dp pin toggle on each card, "+ New space" and "Where downloads go" below the grid. TalkBack
  gets Move up / Move down / Pin actions on every card.  Edit cards are the same 56dp cards as home with the pin toggle added at the card's end.
- **Colors.** Eight palette colors (`SpaceTones`, cobalt, violet, teal, olive, amber, rust, berry, slate), each with a light and a
  dark tone; icon on tile and text on card are checked to at least 4.5:1 in `SpacesUiLogicTest`. Swatches are named for TalkBack.
- **Spaces.** A folder space stores files; a smart space (`SmartScreens.kt`) stores nothing and lists what its rules gather, live.
  The rules editor shows a live match count and the sources of the matches. A smart space is never a download target.
- **Inside a space.** Music: Songs, Collections, Albums, Artists. Video and mixed: items, Collections. A tag chip row ("Tags ▾",
  active tags, top tags) filters in place; Play all plays the filtered list. The tag sheet filters by any or all tags and can save
  the filter as a smart space. Selecting items offers Tags… for your own tags.
- **Collections.** Downloaded playlists, albums and channels remember their link. The detail page shows the follow card
  ("Following · every 6 hours", last check, Check now, Review N new when a check held items back), New items, then every item in
  source order with items removed upstream dimmed ("Removed from the playlist · kept here"). Your own lists keep their editable page.
- **Add to a space.** The library picker comes first (Collections, Songs, Videos, search, multi-select), then Move (default) or Copy
  and a "Move N" button, with progress and a result message. "Device files" stays as the secondary way in. On a smart space, Add
  opens its rules.
- **Settings.** Every Settings page and sub-page has a back arrow and a "Settings ›" line above its title. General has "Open Keepfin
  on" (Downloads, Library, Where I left off); Library settings has "Import tags from sources".
- **Debug fixtures.** Scene `spaces-v2` (`FakeLibraryData`) has tagged songs, a followed playlist with new and removed items, a
  held-back check, more spaces than the hub shows and three smart spaces. `SpacesV2ScreenshotTest` (run with the `screenshots=true`
  instrumentation argument) writes screenshots of every screen into the app's `files/shots` folder.

## Devices page (phone)

Order: This device (name, on/off switch, one line of where it is visible), a problem card only when the phone has neither Wi-Fi nor Tailscale, Your devices (status and route: Home network, Tailscale or Not reachable), Nearby (unpaired devices from DNS-SD, the local scan or Tailscale, each with Pair; a quiet 'Looking on this network...' while scanning; two tips and Add by address or code when empty), libraries on other devices, Away from home (Tailscale status and three steps), Advanced last (Reachable when Keepfin is closed, name, port, fingerprint, turn off). Compact rows, 48dp touch targets, usable at font scale 1.3. Looking runs only while the page is open and the app is in front.

## Setup: choosing the Keepfin folder

- The first start and the Where step lead with one big button, "Use the recommended place". Keepfin makes `Documents/Keepfin` itself and opens the system picker inside it. Before it launches, a small two-step picture says what Android will ask: "Use this folder", then "Allow".
- "Choose another place" (outlined) opens the picker at the top of the storage, memory cards included. "I have a Keepfin folder" (text button) opens the same picker and accepts a Keepfin folder, or a folder that holds one called Keepfin.
- Files an earlier Keepfin saved in Music, Movies and Download get their own step ("Files Keepfin saved before") with a plain reason before the media permissions are asked. Declining skips silently; Settings > Storage keeps "Bring in files from an earlier Keepfin".
- Parts with nothing in them are not shown. An interrupted run continues by itself ("Continuing…"). The step header's "Keepfin" label is `onSurface`; accent colour stays on decorative icons only.
