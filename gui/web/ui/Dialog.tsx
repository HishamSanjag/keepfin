import {useEffect, useRef, type ReactNode} from 'react'

/**
 * The window over the page: a native modal `<dialog>` (focus is trapped, Esc asks to close). It stays mounted while `open` is
 * false so a window can be put away and brought back with what it holds; focus goes back to where it was each time it closes.
 */
export function Dialog({
  title,
  children,
  footer,
  onClose,
  labelledBy = 'dialog-title',
  className,
  eyebrow,
  open = true,
  header,
}: {
  title: ReactNode
  children: ReactNode
  footer?: ReactNode
  onClose: () => void
  labelledBy?: string
  className?: string
  eyebrow?: ReactNode
  open?: boolean
  /** Replaces the default heading; it must contain the element `labelledBy` points at. */
  header?: ReactNode
}) {
  const ref = useRef<HTMLDialogElement>(null)
  // Captured during the first render, before an autoFocus child inside the dialog takes focus.
  const returnTo = useRef(open && typeof document !== 'undefined' ? (document.activeElement as HTMLElement | null) : null)
  useEffect(() => {
    if (!open) return
    const element = ref.current!
    returnTo.current ??= document.activeElement as HTMLElement | null
    const wanted = element.contains(document.activeElement) ? (document.activeElement as HTMLElement) : null
    if (!element.open) element.showModal()
    // showModal() moves focus to the first focusable element; keep the child that asked for autoFocus.
    wanted?.focus()
    return () => {
      const previous = returnTo.current
      returnTo.current = null
      element.close()
      previous?.focus?.()
    }
  }, [open])
  return (
    <dialog
      ref={ref}
      className={`dialog${className ? ` ${className}` : ''}`}
      aria-labelledby={labelledBy}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          const bounds = event.currentTarget.getBoundingClientRect()
          if (
            event.clientX < bounds.left ||
            event.clientX > bounds.right ||
            event.clientY < bounds.top ||
            event.clientY > bounds.bottom
          )
            onClose()
        }
      }}
    >
      {header ?? (
        <header className="dialog-head">
          {eyebrow && <div className="dialog-eyebrow">{eyebrow}</div>}
          <h2 id={labelledBy}>{title}</h2>
        </header>
      )}
      <div className="dialog-body">{children}</div>
      {footer && <footer className="dialog-foot">{footer}</footer>}
    </dialog>
  )
}
