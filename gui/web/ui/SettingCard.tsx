import {useEffect, useId, useState, type ReactNode} from 'react'
import {useNav} from '../nav'
import {Icon} from './Icon'

/**
 * A setting as a card: an icon, a title, one line of help and the control on the right. At narrow widths the control moves
 * under the text. [anchor] makes it a target of Settings search and of links from elsewhere (`id="setting-<anchor>"`).
 */
export function SettingCard({
  anchor,
  icon,
  title,
  description,
  control,
  children,
  disabled,
}: {
  anchor?: string
  icon?: string
  title: ReactNode
  description?: ReactNode
  control?: ReactNode
  children?: ReactNode
  disabled?: boolean
}) {
  return (
    <div className={`setting-card${disabled ? ' is-disabled' : ''}`} id={anchor ? `setting-${anchor}` : undefined}>
      <div className="setting-card-main">
        {icon && (
          <span className="setting-card-icon" aria-hidden="true">
            <Icon name={icon} size={18} />
          </span>
        )}
        <div className="setting-card-text">
          <div className="setting-card-title">{title}</div>
          {description && <div className="setting-card-desc">{description}</div>}
        </div>
        {control && <div className="setting-card-control">{control}</div>}
      </div>
      {children && <div className="setting-card-extra">{children}</div>}
    </div>
  )
}

/**
 * A card that opens to show less-used settings (one level only). [contains] lists the anchors inside, so a search result or a link
 * that points at one of them opens the card first.
 */
export function SettingExpander({
  anchor,
  icon,
  title,
  description,
  contains = [],
  defaultOpen = false,
  children,
}: {
  anchor: string
  icon?: string
  title: string
  description?: ReactNode
  contains?: string[]
  /** Open when the page first shows it (the person can still close it). */
  defaultOpen?: boolean
  children: ReactNode
}) {
  const [open, setOpen] = useState(defaultOpen)
  const panel = useId()
  const nav = useNav()
  const wanted = nav.anchor === anchor || (nav.anchor !== undefined && contains.includes(nav.anchor))
  useEffect(() => {
    if (wanted) setOpen(true)
  }, [wanted, nav.focus])
  return (
    <div className={`setting-expander${open ? ' is-open' : ''}`} id={`setting-${anchor}`}>
      <button type="button" className="setting-card setting-expander-head" aria-expanded={open} aria-controls={panel} onClick={() => setOpen((value) => !value)}>
        <span className="setting-card-main">
          {icon && (
            <span className="setting-card-icon" aria-hidden="true">
              <Icon name={icon} size={18} />
            </span>
          )}
          <span className="setting-card-text">
            <span className="setting-card-title">{title}</span>
            {description && <span className="setting-card-desc">{description}</span>}
          </span>
          <span className="setting-expander-chevron" aria-hidden="true">
            <Icon name="chevronDown" size={16} />
          </span>
        </span>
      </button>
      {open && (
        <div className="setting-expander-body" id={panel} role="group" aria-label={title}>
          {children}
        </div>
      )}
    </div>
  )
}

/**
 * A short heading over a set of settings. By default the content sits in one panel (for lists, copies, devices);
 * with [cards] every child is its own setting card.
 */
export function Group({
  id,
  title,
  description,
  cards,
  hideHeading,
  children,
}: {
  id?: string
  title: string
  description?: ReactNode
  cards?: boolean
  /** The content says its own title: the heading stays for screen readers only. */
  hideHeading?: boolean
  children: ReactNode
}) {
  return (
    <section className="settings-group" aria-label={title} id={id ? `setting-${id}` : undefined}>
      <header className={hideHeading ? 'sr-only' : undefined}>
        <h3>{title}</h3>
        {description && <p>{description}</p>}
      </header>
      <div className={cards ? 'settings-cards' : 'settings-rows'}>{children}</div>
    </section>
  )
}
