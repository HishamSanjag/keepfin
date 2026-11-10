// The one page header (dev-docs/plans/PAGE-LAYOUT.md): an optional breadcrumb, exactly one h1 (26/32), an optional one-line caption and
// the page's actions on the right (at most two buttons plus a ⋯ menu, one of them primary). Every page renders this, so the
// title sits at the same place, at the same size, on every page. Detail pages (album, artist, playlist, collection) pass the cover
// as `art`; it sits at the right so the title keeps its place.
import type {CSSProperties, ReactNode} from 'react'
import {canGoBack, goBack} from '../history'
import {Icon} from '../ui/Icon'

export type Crumb = {label: string; onSelect?: () => void}

/**
 * "‹ Music › Albums": every parent is a link back; the page itself is the h1 below. The arrow goes back like the mouse's back button
 * (to where the page was scrolled); with nothing to go back to, it opens the nearest parent.
 */
export function PageCrumbs({items, onBack}: {items: Crumb[]; onBack?: () => void}) {
  if (!items.length) return null
  const parent = [...items].reverse().find((item) => item.onSelect)
  return (
    <nav className="page-crumbs" aria-label="Breadcrumb">
      <button
        type="button"
        className="crumb-back"
        aria-label="Back"
        title="Back (Alt+Left)"
        onClick={() => (onBack ? onBack() : canGoBack() ? goBack() : parent?.onSelect?.())}
      >
        <Icon name="arrowLeft" size={15} />
      </button>
      <ol>
        {items.map((item, index) => (
          <li key={`${index}-${item.label}`}>
            {index > 0 && (
              <span className="crumb-sep" aria-hidden="true">
                ›
              </span>
            )}
            {item.onSelect ? (
              <button type="button" className="crumb-link" onClick={item.onSelect}>
                {item.label}
              </button>
            ) : (
              <span>{item.label}</span>
            )}
          </li>
        ))}
      </ol>
    </nav>
  )
}

export function PageHeader({
  title,
  caption,
  crumbs,
  actions,
  art,
  band,
  children,
  label,
  onBack,
}: {
  title: ReactNode
  caption?: ReactNode
  crumbs?: Crumb[]
  /** Replaces the back arrow's history step, for views the history does not record. */
  onBack?: () => void
  actions?: ReactNode
  /** Detail pages: the cover (or a mosaic), shown at the right of the header. */
  art?: ReactNode
  /** Detail pages: the cover's colour, as an `r, g, b` triple, for the soft glow behind the cover. */
  band?: string
  /** Extra rows under the caption and actions (a collection's follow card). */
  children?: ReactNode
  /** Accessible name of the header region, when the title alone is not enough. */
  label?: string
}) {
  const style = band ? ({'--hero-rgb': band} as CSSProperties) : undefined
  return (
    <section className={`page-header${art ? ' has-art' : ''}${band ? ' has-art-band' : ''}`} style={style} aria-label={label}>
      {crumbs && <PageCrumbs items={crumbs} onBack={onBack} />}
      <div className="page-header-main">
        <div className="page-titles">
          <h1 className="page-title">{title}</h1>
          {caption && <p className="page-caption tabular">{caption}</p>}
        </div>
        {actions && <div className="page-actions">{actions}</div>}
      </div>
      {children}
      {art && <div className="page-art">{art}</div>}
    </section>
  )
}
