# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

## Users

People saving everyday video and audio links on Windows and Android. The user confirmed everyday downloads as the primary workflow; playlists, batches, and detailed formats remain supported.

## Product Purpose

Inspect a media link, choose its actual available format, download locally, and find the saved file without reading engine output.

## Operating Context

Windows Electron application, a React browser preview, and a separate native Kotlin Android application (Jetpack Compose, Material 3). Each app has its own local engine around yt-dlp; there is no hosted backend. The browser has no download API and must explain that limitation honestly. Android accepts shared links from other applications and inspects them automatically; downloading still needs an explicit action. Finished Android media is published to `Movies/Keepfin`, `Music/Keepfin` and `Download/Keepfin`.

## Capabilities and Constraints

Keepfin (see `dev-docs/product/SPEC.md`): any site yt-dlp supports; a self-updating engine (stable or nightly, Update now); a real format picker with resolution, fps, HDR, codec and estimated size including audio; audio as MP3, M4A, Opus, FLAC or WAV with cover art and tags; parallel downloads with pause and resume; playlists, channels, albums and mixes loaded in pages with selection and Load more; Spotify, Apple Music and Deezer links matched to YouTube Music recordings and tagged with the original metadata; private or age-restricted content through in-app sign-in (Android) or cookies from a file or browser (desktop); subtitles, SponsorBlock, chapters and trim; friendly errors with suggested fixes; image posts and galleries via gallery-dl, proxy, speed limit and optional Turbo transfer (desktop); optional aria2c transfers and Wi-Fi only (Android); signed in-app updates.

Preserve package identifiers, existing profiles, settings and release/signing contracts. Do not claim speed-ups that were not measured, provider-independent timing, or support a provider test did not exercise.

## Brand Commitments

The user authorizes a complete interface and icon replacement, autonomous design decisions, exploration, parallel critique, and real interaction testing. The product name is Keepfin.

## Product Principles

1. Capture and inspect first; advanced choices appear in context.
2. Separate work in progress from saved media.
3. Every state explains what happens next.
4. Platform-native behavior takes priority over pixel-identical layouts.
5. Never fabricate provider capability or successful downloads.

## Accessibility & Inclusion

Keyboard navigation, meaningful control labels, visible focus, readable light and dark palettes, responsive desktop scaling, Android sp text/font scaling, system insets and accessible touch targets.
