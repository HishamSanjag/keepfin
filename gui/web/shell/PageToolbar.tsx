// The optional toolbar row of a page (dev-docs/plans/PAGE-LAYOUT.md): 32px controls, tabs and filters on the left, sort, view, select
// and add on the right. A list that filters itself keeps its small "Filter <scope>" field here, never above the title.
import type {ReactNode} from 'react'

export function PageToolbar({label, left, right, className}: {label: string; left?: ReactNode; right?: ReactNode; className?: string}) {
  if (!left && !right) return null
  return (
    <div className={`page-toolbar${className ? ` ${className}` : ''}`} role="toolbar" aria-label={label}>
      {left && <div className="page-toolbar-left">{left}</div>}
      {right && <div className="page-toolbar-right">{right}</div>}
    </div>
  )
}
