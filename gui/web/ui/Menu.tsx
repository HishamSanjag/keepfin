import {useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode} from 'react'
import {createPortal} from 'react-dom'
import {Icon} from './Icon'

export type MenuItem =
  | {
      label: string
      icon?: string
      onSelect: () => void
      tone?: 'danger'
      disabled?: boolean
      /** Makes the entry a radio-style choice (role menuitemradio) that shows a check when true. */
      checked?: boolean
      /** Muted second line, e.g. a folder path. */
      detail?: string
    }
  | {heading: string}
  /** A compact row of radio choices (speeds, timer lengths); `group` names the row for screen readers. */
  | {group: string; choices: MenuChoice[]; columns?: number}
  | 'separator'

export type MenuChoice = {
  /** Short visible text, e.g. "1.5×". */
  label: string
  /** Full accessible name when the visible text is abbreviated, e.g. "15 minutes" for "15m". */
  name?: string
  checked: boolean
  onSelect: () => void
}

type Anchor = {x: number; y: number} | {rect: DOMRect; align?: 'start' | 'end'}

/** The floating panel. Positioned in fixed coordinates so scrolling lists never clip it. */
function MenuPanel({label, items, anchor, onClose, width = 232, ignore}: {label: string; items: MenuItem[]; anchor: Anchor; onClose: (refocus: boolean) => void; width?: number; ignore?: HTMLElement | null}) {
  const menu = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<{top: number; left: number; up: boolean; maxHeight: number}>()

  useLayoutEffect(() => {
    const zoom = Number(getComputedStyle(document.documentElement).zoom) || 1
    const viewportW = window.innerWidth / zoom
    const viewportH = window.innerHeight / zoom
    const height = menu.current?.offsetHeight || 260
    if ('rect' in anchor) {
      const rect = anchor.rect
      const below = rect.bottom / zoom + 6
      const spaceBelow = viewportH - below - 8
      const up = spaceBelow < Math.min(height, 260) && rect.top / zoom > spaceBelow
      const left = anchor.align === 'start' ? rect.left / zoom : rect.right / zoom - width
      setPosition({
        left: Math.max(8, Math.min(left, viewportW - width - 8)),
        top: up ? rect.top / zoom - 6 : below,
        up,
        maxHeight: Math.max(160, (up ? rect.top / zoom - 14 : spaceBelow) - 0),
      })
    } else {
      const x = anchor.x / zoom
      const y = anchor.y / zoom
      const top = Math.max(8, Math.min(y, viewportH - height - 8))
      setPosition({left: Math.max(8, Math.min(x, viewportW - width - 8)), top, up: false, maxHeight: viewportH - 16})
    }
  }, [anchor, width])

  useEffect(() => {
    if (!position) return
    menu.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
    const close = (event: MouseEvent) => {
      if (!menu.current?.contains(event.target as Node) && !ignore?.contains(event.target as Node)) onClose(false)
    }
    const dismiss = () => onClose(false)
    // A scroll closes the menu only when it moved the button the menu hangs from. The scroll that brings a clicked
    // button into view can arrive just after the menu opened, and must not close it.
    const scrolled = () => {
      if (ignore && 'rect' in anchor) {
        const now = ignore.getBoundingClientRect()
        if (Math.abs(now.top - anchor.rect.top) < 1 && Math.abs(now.left - anchor.rect.left) < 1) return
      }
      dismiss()
    }
    window.addEventListener('mousedown', close)
    window.addEventListener('resize', dismiss)
    window.addEventListener('scroll', scrolled, true)
    window.addEventListener('blur', dismiss)
    return () => {
      window.removeEventListener('mousedown', close)
      window.removeEventListener('resize', dismiss)
      window.removeEventListener('scroll', scrolled, true)
      window.removeEventListener('blur', dismiss)
    }
  }, [!!position])

  function onKey(event: KeyboardEvent) {
    const buttons = [...(menu.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') || [])]
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
    if (event.key === 'Escape' || event.key === 'Tab') {
      event.preventDefault()
      event.stopPropagation()
      onClose(true)
    } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp' || event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault()
      const step = event.key === 'ArrowDown' || event.key === 'ArrowRight' ? 1 : -1
      buttons[(index + step + buttons.length) % buttons.length]?.focus()
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      buttons[event.key === 'Home' ? 0 : buttons.length - 1]?.focus()
    }
  }

  return createPortal(
    <div
      ref={menu}
      className={`menu${position?.up ? ' menu-up' : ''}`}
      role="menu"
      aria-label={label}
      style={{left: position?.left ?? 0, top: position?.top ?? 0, width, maxHeight: position?.maxHeight, visibility: position ? 'visible' : 'hidden'}}
      onKeyDown={onKey}
      onContextMenu={(event) => event.preventDefault()}
    >
      {items.map((item, index) =>
        item === 'separator' ? (
          <div key={index} className="menu-sep" role="separator" />
        ) : 'heading' in item ? (
          <div key={index} className="menu-heading" role="presentation">
            {item.heading}
          </div>
        ) : 'choices' in item ? (
          <div key={index} className="menu-choices" role="group" aria-label={item.group} style={item.columns ? {display: 'grid', gridTemplateColumns: `repeat(${item.columns}, 1fr)`} : undefined}>
            {item.choices.map((choice) => (
              <button
                key={choice.label}
                type="button"
                role="menuitemradio"
                aria-checked={choice.checked}
                aria-label={choice.name}
                title={choice.name}
                className={choice.checked ? 'is-checked' : undefined}
                onClick={() => {
                  onClose(true)
                  choice.onSelect()
                }}
              >
                {choice.label}
              </button>
            ))}
          </div>
        ) : (
          <button
            key={`${index}:${item.label}`}
            type="button"
            role={item.checked === undefined ? 'menuitem' : 'menuitemradio'}
            aria-checked={item.checked}
            disabled={item.disabled}
            className={item.tone === 'danger' ? 'danger' : undefined}
            onClick={() => {
              onClose(true)
              item.onSelect()
            }}
          >
            {item.checked !== undefined ? <Icon name={item.checked ? 'check' : 'blank'} size={16} /> : item.icon ? <Icon name={item.icon} size={16} /> : null}
            <span className="menu-text">
              <span>{item.label}</span>
              {item.detail && <span className="menu-detail">{item.detail}</span>}
            </span>
          </button>
        ),
      )}
    </div>,
    // A menu opened inside a window goes in that window: everything outside an open modal dialog is out of reach.
    ignore?.closest('dialog[open]') || (document.fullscreenElement as HTMLElement | null) || document.getElementById('root') || document.body,
  )
}

/** Action menu opened by an icon button (default) or by custom trigger content. */
export function Menu({
  label,
  items,
  icon = 'more',
  trigger,
  triggerClassName,
  align = 'end',
  width,
  tabbable = true,
  onOpenChange,
}: {
  label: string
  items: MenuItem[]
  icon?: string
  /** When given, the trigger is a text button with this content instead of an icon button. */
  trigger?: ReactNode
  triggerClassName?: string
  align?: 'start' | 'end'
  width?: number
  /** False removes the trigger from the tab order (rows in a roving-focus list). */
  tabbable?: boolean
  /** Told when the menu opens or closes (the video player keeps its controls visible while one is open). */
  onOpenChange?: (open: boolean) => void
}) {
  const [anchor, setAnchor] = useState<Anchor>()
  const button = useRef<HTMLButtonElement>(null)
  const open = !!anchor
  const notify = useRef(onOpenChange)
  notify.current = onOpenChange
  const told = useRef(false)
  useEffect(() => {
    if (open !== told.current) {
      told.current = open
      notify.current?.(open)
    }
  }, [open])
  useEffect(
    () => () => {
      if (told.current) notify.current?.(false)
    },
    [],
  )
  return (
    <>
      <button
        ref={button}
        type="button"
        className={trigger ? `menu-trigger${open ? ' is-open' : ''}${triggerClassName ? ` ${triggerClassName}` : ''}` : `icon-btn icon-btn-md${open ? ' is-open' : ''}`}
        aria-label={label}
        title={label}
        aria-haspopup="menu"
        aria-expanded={open}
        tabIndex={tabbable ? undefined : -1}
        onClick={() => setAnchor(open ? undefined : {rect: button.current!.getBoundingClientRect(), align})}
      >
        {trigger ?? <Icon name={icon} size={17} />}
      </button>
      {anchor && (
        <MenuPanel
          label={label}
          items={items}
          anchor={anchor}
          width={width}
          ignore={button.current}
          onClose={(refocus) => {
            setAnchor(undefined)
            if (refocus) button.current?.focus()
          }}
        />
      )}
    </>
  )
}

/** Right-click menu at the pointer. `at` is set by the owner's onContextMenu handler. */
export function ContextMenu({label, items, at, onClose}: {label: string; items: MenuItem[]; at?: {x: number; y: number}; onClose: () => void}) {
  if (!at) return null
  return <MenuPanel label={label} items={items} anchor={at} onClose={onClose} />
}
