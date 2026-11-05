// URL → provider identity and routing hints. The decisions are made by the shared core (core/, package
// app.keepfin.core.providers), which the Android app uses too; gui/shared/core/providers.ts is the adapter.
// This file keeps the import path the rest of the app already uses.
export {
  isCollectionUrl,
  isGalleryHost,
  isRadioMix,
  isShortMusicLink,
  isYouTube,
  isYouTubeMusic,
  musicLink,
  musicLinkUrl,
  platformFolder,
  playlistContext,
  prefersAudio,
  startsAsVideo,
  providerName,
  youTubeVideoId,
} from './core/providers.js'
export type {MusicLink, MusicLinkType, MusicService} from './core/providers.js'
