import {useEffect, useRef, useState} from 'react'
import {dismissToast, holdToast, resumeToast, useToasts} from '../toasts'
import {Icon} from '../ui/Icon'

/** Whether a modal window is open: everything outside it is behind it and out of reach. */
function useModalOpen() {
  const [open, setOpen] = useState(false)
  useEffect(() => {
    const check = () => setOpen(!!document.querySelector('dialog[open]'))
    check()
    const observer = new MutationObserver(check)
    observer.observe(document.body, {subtree: true, childList: true, attributes: true, attributeFilter: ['open']})
    return () => observer.disconnect()
  }, [])
  return open
}

export function Toasts() {
  const toasts = useToasts()
  const host = useRef<HTMLDivElement>(null)
  const modal = useModalOpen()
  // While a window is open the notices go in the top layer, so a failed download or "Sent to …" shows above it and not behind it.
  useEffect(() => {
    const element = host.current
    if (!modal || !element?.showPopover) return
    if (element.matches(':popover-open')) element.hidePopover()
    element.showPopover()
  }, [modal, toasts])
  return (
    <div ref={host} popover={modal ? 'manual' : undefined} className="toasts" role="region" aria-label="Notifications" aria-live="polite">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className={`toast toast-${toast.tone}`}
          role={toast.tone === 'error' ? 'alert' : 'status'}
          onMouseEnter={() => holdToast(toast.id)}
          onMouseLeave={() => resumeToast(toast.id)}
          onFocus={() => holdToast(toast.id)}
          onBlur={(event) => {
            if (!event.currentTarget.contains(event.relatedTarget as Node | null)) resumeToast(toast.id)
          }}
        >
          <Icon name={toast.tone === 'success' ? 'check' : toast.tone === 'error' ? 'warning' : 'info'} size={16} />
          <span className="toast-text">{toast.message}</span>
          {toast.action && (
            <button
              type="button"
              className="link-btn"
              onClick={() => {
                // dismissed first: a notice the action shows must not count as this one going without its button
                dismissToast(toast.id, true)
                toast.action!.run()
              }}
            >
              {toast.action.label}
            </button>
          )}
          {toast.secondary && (
            <button
              type="button"
              className="link-btn"
              onClick={() => {
                toast.secondary!.run()
                dismissToast(toast.id)
              }}
            >
              {toast.secondary.label}
            </button>
          )}
          <button type="button" className="icon-btn icon-btn-sm" aria-label="Dismiss notification" onClick={() => dismissToast(toast.id)}>
            <Icon name="close" size={14} />
          </button>
        </div>
      ))}
    </div>
  )
}
