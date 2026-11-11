import type {ReactNode} from 'react'

// One stroke grammar: 24px grid, 1.7 stroke, round joins. Drawn for this product, no icon font.
const paths: Record<string, ReactNode> = {
  download: (
    <>
      <path d="M12 4v11m-4.5-4.5L12 15l4.5-4.5" />
      <path d="M5 19h14" />
    </>
  ),
  library: (
    <>
      <rect x="3.5" y="4.5" width="17" height="15" rx="2.5" />
      <path d="M3.5 9h17M9 4.5V9" />
    </>
  ),
  settings: (
    <>
      <path d="M4 7h9m4 0h3M4 17h3m4 0h9" />
      <circle cx="15" cy="7" r="2.2" />
      <circle cx="9" cy="17" r="2.2" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  check: <path d="m5 12.5 4.2 4.2L19 7" />,
  close: <path d="m6.5 6.5 11 11m0-11-11 11" />,
  search: (
    <>
      <circle cx="10.5" cy="10.5" r="5.5" />
      <path d="m15 15 4.5 4.5" />
    </>
  ),
  link: (
    <path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1" />
  ),
  retry: (
    <>
      <path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3" />
      <path d="M4.5 4.5v4h4" />
    </>
  ),
  video: (
    <>
      <rect x="3.5" y="5.5" width="17" height="13" rx="2.5" />
      <path d="m10.5 9.5 4 2.5-4 2.5Z" />
    </>
  ),
  music: (
    <>
      <path d="M9 17.5V6l10-2v11.5" />
      <circle cx="6.5" cy="17.5" r="2.5" />
      <circle cx="16.5" cy="15.5" r="2.5" />
    </>
  ),
  image: (
    <>
      <rect x="3.5" y="4.5" width="17" height="15" rx="2.5" />
      <circle cx="9" cy="10" r="1.6" />
      <path d="m4 17 5-4.5 3.5 3 3-2.5 4.5 4" />
    </>
  ),
  stack: (
    <>
      <path d="M7 4.5h10M5.5 7.5h13" />
      <rect x="3.5" y="10.5" width="17" height="9" rx="2" />
    </>
  ),
  sun: (
    <>
      <circle cx="12" cy="12" r="3.6" />
      <path d="M12 3v1.8m0 14.4V21M3 12h1.8m14.4 0H21M5.6 5.6l1.3 1.3m10.2 10.2 1.3 1.3M5.6 18.4l1.3-1.3M17.1 6.9l1.3-1.3" />
    </>
  ),
  moon: <path d="M19 14.5A7.5 7.5 0 0 1 9.5 5a7.5 7.5 0 1 0 9.5 9.5Z" />,
  more: (
    <>
      <circle cx="6" cy="12" r="1.3" fill="currentColor" stroke="none" />
      <circle cx="12" cy="12" r="1.3" fill="currentColor" stroke="none" />
      <circle cx="18" cy="12" r="1.3" fill="currentColor" stroke="none" />
    </>
  ),
  info: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 11v5.5M12 7.6v.1" />
    </>
  ),
  warning: (
    <>
      <path d="M10.3 4.6 3 17.5A2 2 0 0 0 4.7 20.5h14.6a2 2 0 0 0 1.7-3L13.7 4.6a2 2 0 0 0-3.4 0Z" />
      <path d="M12 9.5v4.5M12 17v.1" />
    </>
  ),
  paste: (
    <>
      <path d="M9 4.5H7A2.5 2.5 0 0 0 4.5 7v11A2.5 2.5 0 0 0 7 20.5h10a2.5 2.5 0 0 0 2.5-2.5V7A2.5 2.5 0 0 0 17 4.5h-2" />
      <rect x="9" y="3" width="6" height="3.5" rx="1.2" />
    </>
  ),
  pause: <path d="M9 6v12M15 6v12" />,
  play: <path d="M8 5.5v13l10.5-6.5Z" />,
  stop: <rect x="6.5" y="6.5" width="11" height="11" rx="2" />,
  up: <path d="M12 19V5m-6 6 6-6 6 6" />,
  upcircle: (
    <>
      <circle cx="12" cy="12" r="8.75" />
      <path d="M12 16V8.5m-3.5 3.5L12 8.5l3.5 3.5" />
    </>
  ),
  folder: (
    <path d="M3.5 7.5A2 2 0 0 1 5.5 5.5h3.7l2 2h7.3a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2Z" />
  ),
  open: (
    <>
      <path d="M13.5 4.5h6v6M19.5 4.5 11 13" />
      <path d="M17.5 14v3.5a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2H10" />
    </>
  ),
  trash: (
    <>
      <path d="M4.5 7h15M9.5 7V4.5h5V7M6.5 7l.8 11.2A2 2 0 0 0 9.3 20h5.4a2 2 0 0 0 2-1.8L17.5 7" />
    </>
  ),
  chevronDown: <path d="m6.5 9.5 5.5 5.5 5.5-5.5" />,
  chevronRight: <path d="m9.5 6.5 5.5 5.5-5.5 5.5" />,
  list: <path d="M9 6.5h11M9 12h11M9 17.5h11M4.5 6.5h.1M4.5 12h.1M4.5 17.5h.1" />,
  grid: (
    <>
      <rect x="4" y="4" width="7" height="7" rx="1.6" />
      <rect x="13" y="4" width="7" height="7" rx="1.6" />
      <rect x="4" y="13" width="7" height="7" rx="1.6" />
      <rect x="13" y="13" width="7" height="7" rx="1.6" />
    </>
  ),
  bolt: <path d="M13 3.5 5.5 13.5H12L11 20.5l7.5-10H12Z" />,
  globe: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M3.5 12h17M12 3.5c2.4 2.5 3.5 5.3 3.5 8.5s-1.1 6-3.5 8.5c-2.4-2.5-3.5-5.3-3.5-8.5s1.1-6 3.5-8.5Z" />
    </>
  ),
  key: (
    <>
      <circle cx="8" cy="15" r="3.8" />
      <path d="m10.8 12.3 8.2-8.3M16 7l2.5 2.5M13.8 9.2l2 2" />
    </>
  ),
  engine: (
    <>
      <rect x="6.5" y="6.5" width="11" height="11" rx="2" />
      <path d="M9.5 3.5v3m5-3v3m-5 11v3m5-3v3m-11-11h3m-3 5h3m11-5h3m-3 5h3" />
    </>
  ),
  bell: (
    <>
      <path d="M6.5 16.5V11a5.5 5.5 0 0 1 11 0v5.5l1.5 2H5Z" />
      <path d="M10 20.5h4" />
    </>
  ),
  appearance: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 3.5v17a8.5 8.5 0 0 0 0-17Z" fill="currentColor" stroke="none" />
    </>
  ),
  badge: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 8v.1M12 11v5" />
    </>
  ),
  subtitles: (
    <>
      <rect x="3.5" y="5.5" width="17" height="13" rx="2.5" />
      <path d="M7 12.5h3.5M13 12.5h4M7 15.5h6M15.5 15.5h1.5" />
    </>
  ),
  scissors: (
    <>
      <circle cx="6.5" cy="7" r="2.5" />
      <circle cx="6.5" cy="17" r="2.5" />
      <path d="M8.6 8.4 19.5 17M8.6 15.6 19.5 7" />
    </>
  ),
  sort: <path d="M7 5v14m-3-3 3 3 3-3M17 19V5m-3 3 3-3 3 3" />,
  filter: <path d="M4 6h16M7 12h10M10 18h4" />,
  equalizer: (
    <>
      <path d="M6 4v16M12 4v16M18 4v16" />
      <path d="M4 15h4M10 8h4M16 13h4" />
    </>
  ),
  copy: (
    <>
      <rect x="8.5" y="8.5" width="11" height="11" rx="2" />
      <path d="M15.5 8.5v-2a2 2 0 0 0-2-2h-7a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h2" />
    </>
  ),
  heart: <path d="M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.2a4.3 4.3 0 0 1 7.5 2.6C19.5 15.4 12 20 12 20Z" />,
  coffee: (
    <>
      <path d="M5 9h11v5a4.5 4.5 0 0 1-4.5 4.5h-2A4.5 4.5 0 0 1 5 14Z" />
      <path d="M16 10.5h1.5a2.5 2.5 0 0 1 0 5H15.5" />
      <path d="M8.5 3.8c-.8 1 .8 1.6 0 2.7M12 3.8c-.8 1 .8 1.6 0 2.7" />
    </>
  ),
  wallet: (
    <>
      <path d="M4.5 8.5a2 2 0 0 1 2-2H18a1.5 1.5 0 0 1 1.5 1.5V9" />
      <path d="M4.5 8.5V17a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2v-6.5a1.5 1.5 0 0 0-1.5-1.5H6.5a2 2 0 0 1-2-.5Z" />
      <circle cx="15.5" cy="13.8" r="1.1" fill="currentColor" stroke="none" />
    </>
  ),
  chevron: <path d="m9 6 6 6-6 6" />,
  external: (
    <>
      <path d="M14 4.5h5.5V10M19.5 4.5 12 12" />
      <path d="M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4" />
    </>
  ),
  refresh: (
    <>
      <path d="M19.5 12a7.5 7.5 0 0 1-13 5.1M4.5 12a7.5 7.5 0 0 1 13-5.1" />
      <path d="M17.5 3.5v3.4h-3.4M6.5 20.5v-3.4h3.4" />
    </>
  ),
  layers: (
    <>
      <path d="m12 4 8.5 4.5L12 13 3.5 8.5Z" />
      <path d="m3.5 12.5 8.5 4.5 8.5-4.5M3.5 16.5 12 21l8.5-4.5" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8.5" r="3.5" />
      <path d="M5 20a7 7 0 0 1 14 0" />
    </>
  ),
  clock: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.5V12l3 2" />
    </>
  ),
  lock: (
    <>
      <rect x="5" y="10.5" width="14" height="10" rx="2" />
      <path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5" />
    </>
  ),
  sparkle: <path d="M12 4v4m0 8v4M4 12h4m8 0h4" />,
  blank: <></>,
  chevronUp: <path d="m6 15 6-6 6 6" />,
  chevronLeft: <path d="m15 6-6 6 6 6" />,
  arrowLeft: <path d="M19 12H5m6-6-6 6 6 6" />,
  shuffle: (
    <>
      <path d="M4 7h3c3 0 4.5 2 6 5s3 5 6 5h1M4 17h3c1.3 0 2.4-.5 3.3-1.3M20 7h-1c-2 0-3.4.8-4.5 2" />
      <path d="m17.5 4.5 2.5 2.5-2.5 2.5M17.5 14.5l2.5 2.5-2.5 2.5" />
    </>
  ),
  repeat: (
    <>
      <path d="M4 11V9.5A3.5 3.5 0 0 1 7.5 6H20M17 3l3 3-3 3M20 13v1.5a3.5 3.5 0 0 1-3.5 3.5H4M7 21l-3-3 3-3" />
    </>
  ),
  repeatOne: (
    <>
      <path d="M4 11V9.5A3.5 3.5 0 0 1 7.5 6H20M17 3l3 3-3 3M20 13v1.5a3.5 3.5 0 0 1-3.5 3.5H4M7 21l-3-3 3-3" />
      <path d="M11 11.2l1.4-.9v4.6" />
    </>
  ),
  skipNext: <path d="M6 5.5v13l9-6.5ZM18 5.5v13" />,
  skipPrev: <path d="M18 5.5v13l-9-6.5ZM6 5.5v13" />,
  volume: (
    <>
      <path d="M4 9.5h3.5L12 6v12l-4.5-3.5H4Z" />
      <path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a8 8 0 0 1 0 11" />
    </>
  ),
  volumeMute: (
    <>
      <path d="M4 9.5h3.5L12 6v12l-4.5-3.5H4Z" />
      <path d="m15.5 9.5 5 5m0-5-5 5" />
    </>
  ),
  queue: <path d="M4 6.5h11M4 11.5h11M4 16.5h6M15 14.5v6l5-3Z" />,
  fullscreen: <path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />,
  pip: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <rect x="12" y="12" width="7" height="5" rx="1" />
    </>
  ),
  speed: (
    <>
      <path d="M4.5 17a8 8 0 1 1 15 0" />
      <path d="m12 13 4-4" />
    </>
  ),
  camera: (
    <>
      <path d="M4 8h3l1.5-2h7L17 8h3v11H4Z" />
      <circle cx="12" cy="13" r="3.2" />
    </>
  ),
  edit: <path d="M4 20h4L19 9l-4-4L4 16ZM13.5 6.5l4 4" />,
  move: (
    <>
      <path d="M3.5 7.5a2 2 0 0 1 2-2H10l2 2.5h6.5a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2Z" />
      <path d="M10 13.5h6m-2.5-2.5 2.5 2.5-2.5 2.5" />
    </>
  ),
  playlistAdd: <path d="M4 6.5h11M4 11.5h7M4 16.5h6M16 12.5v7M12.5 16h7" />,
  home: <path d="M4.5 11 12 4.5l7.5 6.5V19a1 1 0 0 1-1 1h-4v-5h-5v5h-4a1 1 0 0 1-1-1Z" />,
  mic: (
    <>
      <rect x="9" y="4" width="6" height="10" rx="3" />
      <path d="M6 11.5a6 6 0 0 0 12 0M12 17.5V20" />
    </>
  ),
  disc: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <circle cx="12" cy="12" r="2.2" />
    </>
  ),
  drag: (
    <>
      <circle cx="9" cy="6.5" r="1.1" fill="currentColor" stroke="none" />
      <circle cx="15" cy="6.5" r="1.1" fill="currentColor" stroke="none" />
      <circle cx="9" cy="12" r="1.1" fill="currentColor" stroke="none" />
      <circle cx="15" cy="12" r="1.1" fill="currentColor" stroke="none" />
      <circle cx="9" cy="17.5" r="1.1" fill="currentColor" stroke="none" />
      <circle cx="15" cy="17.5" r="1.1" fill="currentColor" stroke="none" />
    </>
  ),
  folderPlus: (
    <>
      <path d="M3.5 7.5a2 2 0 0 1 2-2H10l2 2.5h6.5a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2Z" />
      <path d="M12 11v5m-2.5-2.5h5" />
    </>
  ),
  speaker: (
    <>
      <rect x="6.5" y="3.5" width="11" height="17" rx="2.5" />
      <circle cx="12" cy="14.5" r="3" />
      <path d="M12 7.5h.1" />
    </>
  ),
  heartFill: <path d="M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.2a4.3 4.3 0 0 1 7.5 2.6C19.5 15.4 12 20 12 20Z" fill="currentColor" />,
  crop: <path d="M6 3v15h15M3 6h15v15" />,
  minus: <path d="M5 12h14" />,
  zoomIn: (
    <>
      <circle cx="10.5" cy="10.5" r="5.5" />
      <path d="m15 15 4.5 4.5M10.5 8v5M8 10.5h5" />
    </>
  ),
  zoomOut: (
    <>
      <circle cx="10.5" cy="10.5" r="5.5" />
      <path d="m15 15 4.5 4.5M8 10.5h5" />
    </>
  ),
  star: <path d="m12 4.2 2.4 4.9 5.4.8-3.9 3.8.9 5.4-4.8-2.5-4.8 2.5.9-5.4-3.9-3.8 5.4-.8Z" />,
  book: (
    <>
      <path d="M5 5.5A1.5 1.5 0 0 1 6.5 4H19v13H6.5A1.5 1.5 0 0 0 5 18.5Z" />
      <path d="M5 18.5A1.5 1.5 0 0 0 6.5 20H19v-3" />
    </>
  ),
  dumbbell: <path d="M7 8v8M17 8v8M4.5 10v4M19.5 10v4M7 12h10" />,
  child: (
    <>
      <circle cx="12" cy="7.5" r="2.7" />
      <path d="M7 19.5v-3.2a5 5 0 0 1 10 0v3.2M9.5 14.2 7 12.5m7.5 1.7 2.5-1.7" />
    </>
  ),
  tag: (
    <>
      <path d="M4 12.2V5.5A1.5 1.5 0 0 1 5.5 4h6.7a1.5 1.5 0 0 1 1.06.44l6.3 6.3a1.5 1.5 0 0 1 0 2.12l-6.7 6.7a1.5 1.5 0 0 1-2.12 0l-6.3-6.3A1.5 1.5 0 0 1 4 12.2Z" />
      <circle cx="8.6" cy="8.6" r="1.1" />
    </>
  ),
  pin: <path d="M9 4h6l-.8 5.2L18 13v1.5h-5.2V20h-1.6v-5.5H6V13l3.8-3.8Z" />,
  smart: <path d="M12 3.8v3.4m0 9.6v3.4M3.8 12h3.4m9.6 0h3.4M6.2 6.2l2.4 2.4m6.8 6.8 2.4 2.4m0-11.6-2.4 2.4m-6.8 6.8-2.4 2.4" />,
  lyrics: (
    <>
      <path d="M5.5 4h13A1.5 1.5 0 0 1 20 5.5v9a1.5 1.5 0 0 1-1.5 1.5H11l-4 3.5V16H5.5A1.5 1.5 0 0 1 4 14.5v-9A1.5 1.5 0 0 1 5.5 4Z" />
      <circle cx="9.6" cy="12" r="1.3" />
      <path d="M10.9 12V7.6l2.6.8" />
      <path d="M15.5 12.2h1.6" />
    </>
  ),
  sidebar: (
    <>
      <rect x="3.5" y="4.5" width="17" height="15" rx="2.5" />
      <path d="M9.5 4.5v15" />
    </>
  ),
  maximize: <path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />,
  // Settings categories, shaped like the Material outline glyphs the Android app uses.
  'video-library': (
    <>
      <path d="M3.5 16V6.5a2 2 0 0 1 2-2H15" />
      <rect x="7.5" y="8.5" width="13" height="11" rx="2" />
      <path d="m12.5 11.5 4 2.5-4 2.5Z" />
    </>
  ),
  devices: (
    <>
      <path d="M17 7.5V6a1.5 1.5 0 0 0-1.5-1.5h-10A1.5 1.5 0 0 0 4 6v8a1.5 1.5 0 0 0 1.5 1.5H9" />
      <path d="M7 19.5h4.5M9 15.5v4" />
      <rect x="14" y="9.5" width="6.5" height="10" rx="1.6" />
      <path d="M17.2 17h.1" />
    </>
  ),
  palette: (
    <>
      <path d="M12 3.5a8.5 8.5 0 0 0 0 17c1.3 0 2-.9 2-1.8 0-.5-.2-.9-.5-1.3-.3-.4-.5-.8-.5-1.3 0-1 .8-1.8 1.9-1.8H17a3.5 3.5 0 0 0 3.5-3.6C20.5 6.7 16.7 3.5 12 3.5Z" />
      <circle cx="7.6" cy="11.5" r="1" fill="currentColor" stroke="none" />
      <circle cx="10" cy="7.8" r="1" fill="currentColor" stroke="none" />
      <circle cx="14.3" cy="7.8" r="1" fill="currentColor" stroke="none" />
      <circle cx="16.8" cy="11" r="1" fill="currentColor" stroke="none" />
    </>
  ),
  help: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M9.6 9.7a2.5 2.5 0 1 1 3.6 2.2c-.8.4-1.2 1-1.2 1.8M12 16.6v.1" />
    </>
  ),
  gear: (
    <>
      <circle cx="12" cy="12" r="2.8" />
      <path d="M10.3 3.5h3.4l.5 2.4 1.6.9 2.3-.8 1.7 2.9-1.8 1.6v1.9l1.8 1.6-1.7 2.9-2.3-.8-1.6.9-.5 2.4h-3.4l-.5-2.4-1.6-.9-2.3.8-1.7-2.9 1.8-1.6v-1.9L4.2 8.9l1.7-2.9 2.3.8 1.6-.9Z" />
    </>
  ),
  eye: (
    <>
      <path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z" />
      <circle cx="12" cy="12" r="2.8" />
    </>
  ),
  eyeOff: (
    <>
      <path d="m4 4 16 16" />
      <path d="M9.9 5.8A9 9 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a16 16 0 0 1-2.6 3.4M6.3 7.4A16 16 0 0 0 2.5 12S6 18.5 12 18.5a9 9 0 0 0 4-.9" />
    </>
  ),
}

export type IconName = keyof typeof paths

export function Icon({name, size = 18, className}: {name: string; size?: number; className?: string}) {
  return (
    <svg
      className={className ? `icon ${className}` : 'icon'}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {paths[name] || paths.info}
    </svg>
  )
}

/** The Keepfin emblem at small sizes: the logo without its lettering, on a transparent background (generated by `npm run icons:build`). */
export function Mark({size = 28}: {size?: number}) {
  return <img src="./brand/mark.png" width={size} height={size} alt="" aria-hidden="true" draggable={false} className="mark" />
}

/** The stacked Keepfin logo on a transparent background; light themes use the version with ink lettering. */
export function Logo({size = 96}: {size?: number}) {
  return (
    <span className="logo" style={{width: size, height: size}} aria-hidden="true">
      <img src="./brand/logo.png" alt="" draggable={false} className="logo-plain" />
      <img src="./brand/logo-light.png" alt="" draggable={false} className="logo-light" />
    </span>
  )
}
