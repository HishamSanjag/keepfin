// The two pictures of "How do you want to use Keepfin?": a small drawing of the app in each mode, from theme tokens only, so they
// read in light and dark and stay sharp at any size. No words inside; the option's text says what they show. 160 x 100 grid.

const tint = (pct: number) => `color-mix(in srgb, var(--accent) ${pct}%, var(--raised))`
const bar = 'color-mix(in srgb, var(--text-2) 35%, transparent)'

function Frame({children}: {children: React.ReactNode}) {
  return (
    <svg className="mode-picture" viewBox="0 0 160 100" aria-hidden="true" focusable="false">
      <rect x="1" y="1" width="158" height="98" rx="8" fill="var(--raised)" stroke="var(--line-strong)" strokeWidth="1.2" />
      {children}
    </svg>
  )
}

/** Downloader + library & player: a rail, a grid of covers and a player bar. */
export function LibraryPicture() {
  return (
    <Frame>
      <path d="M9 1h14v98H9a8 8 0 0 1-8-8V9a8 8 0 0 1 8-8z" fill={tint(25)} />
      {[0, 1, 2].map((i) => (
        <circle key={i} cx="12" cy={18 + i * 14} r="3" fill={i === 0 ? 'var(--accent)' : bar} />
      ))}
      <rect x="32" y="10" width="46" height="6" rx="3" fill={bar} />
      {[0, 1].map((row) =>
        [0, 1, 2].map((col) => <rect key={`${row}-${col}`} x={32 + col * 40} y={22 + row * 26} width="34" height="21" rx="4" fill={tint([25, 40, 60][(row + col) % 3])} />),
      )}
      <rect x="28" y="76" width="126" height="18" rx="5" fill={tint(25)} />
      <rect x="32" y="79" width="12" height="12" rx="3" fill={tint(60)} />
      <rect x="48" y="81" width="44" height="4" rx="2" fill={bar} />
      <rect x="48" y="87" width="28" height="3" rx="1.5" fill={bar} />
      <path d="M138 80l8 5-8 5z" fill="var(--accent)" />
    </Frame>
  )
}

/** Downloader only: a link box with a Download pill and three downloads in progress; no rail, no player. */
export function DownloaderPicture() {
  return (
    <Frame>
      <rect x="10" y="10" width="104" height="16" rx="8" fill="none" stroke="var(--line-strong)" strokeWidth="1.2" />
      <circle cx="19" cy="18" r="3" fill={bar} />
      <rect x="27" y="16" width="54" height="4" rx="2" fill={bar} />
      <rect x="118" y="10" width="32" height="16" rx="8" fill="var(--accent)" />
      {[1, 0.6, 0.25].map((done, i) => {
        const y = 34 + i * 21
        return (
          <g key={i}>
            <rect x="10" y={y} width="22" height="16" rx="3" fill={tint([25, 40, 60][i])} />
            <rect x="38" y={y + 1} width="60" height="4" rx="2" fill={bar} />
            <rect x="38" y={y + 7} width="36" height="3" rx="1.5" fill={bar} />
            <rect x="38" y={y + 12} width="112" height="3" rx="1.5" fill="var(--line-strong)" />
            <rect x="38" y={y + 12} width={112 * done} height="3" rx="1.5" fill="var(--accent)" />
          </g>
        )
      })}
    </Frame>
  )
}
