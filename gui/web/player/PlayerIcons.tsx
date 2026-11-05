// Icons for the video player's media-chrome buttons, slotted as the <svg> itself (media-chrome sizes `::slotted(svg)`).
// Play and pause are filled with rounded corners (as every current player draws them); the rest use the app's 1.7 stroke.
import type {ReactNode} from 'react'

type Slotted = {slot?: string}

function Glyph({slot, children, filled = false}: Slotted & {children: ReactNode; filled?: boolean}) {
  return (
    <svg
      slot={slot}
      viewBox="0 0 24 24"
      // inline, because media-chrome's `::slotted(svg)` rule fills every icon (the attribute alone loses to it)
      style={{fill: filled ? 'currentColor' : 'none'}}
      stroke={filled ? 'none' : 'currentColor'}
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  )
}

export const PlayGlyph = ({slot}: Slotted) => (
  <Glyph slot={slot} filled>
    <path d="M7.5 5.6v12.8a1.3 1.3 0 0 0 2 1.1l10-6.4a1.3 1.3 0 0 0 0-2.2l-10-6.4a1.3 1.3 0 0 0-2 1.1Z" />
  </Glyph>
)

export const PauseGlyph = ({slot}: Slotted) => (
  <Glyph slot={slot} filled>
    <rect x="6" y="5" width="4.2" height="14" rx="1.4" />
    <rect x="13.8" y="5" width="4.2" height="14" rx="1.4" />
  </Glyph>
)

/** A round arrow with the seconds inside: back turns left, forward turns right. */
export const SeekGlyph = ({slot, dir}: Slotted & {dir: -1 | 1}) => (
  <Glyph slot={slot}>
    {dir < 0 ? <path d="M12 5.5A7.5 7.5 0 1 1 4.5 13M14.4 3.1 12 5.5l2.4 2.4" /> : <path d="M12 5.5A7.5 7.5 0 1 0 19.5 13M9.6 3.1 12 5.5 9.6 7.9" />}
    <text x="12" y="13.4" textAnchor="middle" dominantBaseline="central" fill="currentColor" stroke="none" fontSize="7.2" fontWeight="700" fontFamily="var(--font)">
      10
    </text>
  </Glyph>
)

const speaker = <path d="M4 9.5h3.5L12 6v12l-4.5-3.5H4Z" />
export const VolumeGlyph = ({slot, level}: Slotted & {level: 'off' | 'low' | 'high'}) => (
  <Glyph slot={slot}>
    {speaker}
    {level === 'off' ? <path d="m15.5 9.5 5 5m0-5-5 5" /> : <path d={level === 'low' ? 'M15.5 9a4 4 0 0 1 0 6' : 'M15.5 9a4 4 0 0 1 0 6M18 6.5a8 8 0 0 1 0 11'} />}
  </Glyph>
)

export const PipGlyph = ({slot}: Slotted) => (
  <Glyph slot={slot}>
    <rect x="3" y="5" width="18" height="14" rx="2" />
    <rect x="12" y="12" width="7" height="5" rx="1" fill="currentColor" stroke="none" />
  </Glyph>
)

export const FullscreenGlyph = ({slot, exit = false}: Slotted & {exit?: boolean}) => (
  <Glyph slot={slot}>
    <path d={exit ? 'M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5' : 'M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5'} />
  </Glyph>
)
