import type {ReactNode} from 'react'
import {formatBytes} from '../../shared/contracts'
import {Icon, Logo} from '../ui/Icon'

/** The folder shape the pictures use, 26 x 21. */
const folderShape = 'M0 3a3 3 0 0 1 3-3h7l3 3h10a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H3a3 3 0 0 1-3-3Z'

/**
 * A small drawing of what a Keepfin folder is: one folder with Music, Videos, Photos and a hidden `.keepfin` inside it.
 * Decorative: the sentence beside it says the same in words.
 */
export function FolderTreeArt() {
  const rows = [
    {y: 18, name: 'Music', hidden: false},
    {y: 58, name: 'Videos', hidden: false},
    {y: 98, name: 'Photos', hidden: false},
    {y: 138, name: '.keepfin', hidden: true},
  ]
  return (
    <svg className="tree-art" viewBox="0 0 300 168" aria-hidden="true" focusable="false">
      <g transform="translate(6 62)">
        <path className="tree-folder tree-root" d={folderShape} transform="scale(1.5)" />
        <text className="tree-label tree-root-label" x="46" y="25">
          Keepfin
        </text>
      </g>
      <path className="tree-line" d="M112 84H130M130 28V148" fill="none" />
      {rows.map((row) => (
        <g key={row.name}>
          <path className="tree-line" d={`M130 ${row.y + 10}H144`} fill="none" />
          <path className={`tree-folder${row.hidden ? ' tree-hidden' : ''}`} d={folderShape} transform={`translate(146 ${row.y})`} />
          <text className={`tree-label${row.hidden ? ' tree-label-hidden' : ''}`} x="182" y={row.y + 15}>
            {row.name}
          </text>
        </g>
      ))}
    </svg>
  )
}

/** The Keepfin mark, the title and one sentence, with the folder picture beside them. */
export function SetupHero({title, children}: {title?: ReactNode; children: ReactNode}) {
  return (
    <div className="setup-hero">
      <div className="setup-hero-text">
        <Logo size={96} />
        {title && <strong>{title}</strong>}
        <p>{children}</p>
      </div>
      <FolderTreeArt />
    </div>
  )
}

/** A path as breadcrumbs: `F: › Keepfin`. */
export function PathCrumbs({path, label = 'Where the Keepfin folder will be'}: {path: string; label?: string}) {
  const parts = path.split(/[\\/]+/).filter(Boolean)
  if (!parts.length) return null
  return (
    <nav aria-label={label} className="setup-crumbs">
      <ol>
        {parts.map((part, index) => (
          <li key={`${index}-${part}`} data-last={index === parts.length - 1 || undefined}>
            {index > 0 && <Icon name="chevronRight" size={12} />}
            <span>{part}</span>
          </li>
        ))}
      </ol>
    </nav>
  )
}

/** How much a library needs against what is free: a bar and two numbers. Red when it does not fit. */
export function SpaceBar({needed, free, neededLabel = 'Needs', label = 'Space needed against space free'}: {needed: number; free: number; neededLabel?: string; label?: string}) {
  const fits = needed <= free
  const percent = free > 0 ? Math.min(100, (needed / free) * 100) : needed > 0 ? 100 : 0
  return (
    <div className="space-bar" data-fits={fits} role="img" aria-label={`${label}: ${formatBytes(needed) || '0 B'} of ${formatBytes(free) || '0 B'} free`}>
      <div className="space-bar-track">
        <span className="space-bar-fill" style={{width: `${percent}%`}} />
      </div>
      <div className="space-bar-legend tabular">
        <span>
          {neededLabel} {formatBytes(needed) || '0 B'}
        </span>
        <span>{formatBytes(free) || '0 B'} free</span>
      </div>
    </div>
  )
}

/** A big selectable card: an icon, a title, one line, and a button that fills the whole card. */
export function ChoiceCard({icon, title, text, children}: {icon: string; title: string; text: string; children: ReactNode}) {
  return (
    <div className="setup-choice">
      <span className="setup-choice-icon" aria-hidden="true">
        <Icon name={icon} size={30} />
      </span>
      <div className="setup-choice-text">
        <strong>{title}</strong>
        <span>{text}</span>
      </div>
      <div className="setup-choice-action">{children}</div>
    </div>
  )
}

/** The icon for a source folder by what it holds. */
export function sourceIcon(name: string): string {
  const key = name.toLowerCase()
  if (/music|song|audio/.test(key)) return 'music'
  if (/video|movie|film/.test(key)) return 'video'
  if (/photo|picture|image/.test(key)) return 'image'
  if (/download/.test(key)) return 'download'
  return 'folder'
}
