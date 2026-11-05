// The "clean title" rule: the same on desktop and Android, decided by the shared core (core/, shared fixtures in
// fixtures/engine/formats/titles.json). Posts (Facebook, Instagram, TikTok, X, Reddit) use their whole caption as the
// title; rows, notifications, file names and tags want a short one. The full text stays available as the description.
export {cleanTitle, TITLE_MAX, type TitleKind} from './core/formats.js'
