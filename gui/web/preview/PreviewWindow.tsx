// The one window every download preview opens in: cover, title and facts, a main tab and Extras, then Save to / Download on… / Download.
import {useId, useState, type ReactNode} from 'react'
import {extrasLabel} from '../../shared/library'
import {Dialog} from '../ui/Dialog'
import {IconButton, Tabs} from '../ui/controls'

type Section = 'main' | 'extras'

export function PreviewWindow({
  open,
  onClose,
  cover,
  title,
  facts,
  mainLabel,
  extras,
  footer,
  busy,
  error,
  children,
}: {
  open: boolean
  onClose: () => void
  /** The picture beside the title; omit for cases with none (several links, a link that could not be read). */
  cover?: ReactNode
  title: string
  facts?: ReactNode
  /** The main tab: "What to save", "Songs · 24", "Links · 5", "Photos · 6". Omit for a window with no tabs (reading, could not read). */
  mainLabel?: string
  /** The Extras tab: how many things were changed from the defaults, and what is in it. Omit when nothing applies. */
  extras?: {changed: number; content: ReactNode}
  footer?: ReactNode
  busy?: boolean
  /** Why Download cannot be pressed, shown on both tabs. */
  error?: string
  children: ReactNode
}) {
  const [section, setSection] = useState<Section>('main')
  const id = useId()
  const titleId = `${id}-title`
  const shown = extras ? section : 'main'
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      labelledBy={titleId}
      className={`preview${mainLabel ? ' has-tabs' : ''}`}
      footer={footer}
      header={
        <header className="preview-head">
          {cover}
          <div className="preview-title">
            <h2 id={titleId}>{title}</h2>
            {facts && <div className="preview-facts">{facts}</div>}
          </div>
          <IconButton icon="close" label="Close" onClick={onClose} />
        </header>
      }
    >
      {mainLabel && (
        <Tabs<Section>
          label="Sections"
          idBase={`${id}-section`}
          value={shown}
          onChange={setSection}
          tabs={[{value: 'main', label: mainLabel}, ...(extras ? [{value: 'extras' as const, label: extrasLabel(extras.changed)}] : [])]}
        />
      )}
      {error && (
        <p className="field-error preview-error" role="alert">
          {error}
        </p>
      )}
      <div className="preview-scroll" aria-busy={busy || undefined}>
        <div id={`${id}-section-panel`} role={mainLabel ? 'tabpanel' : undefined} aria-labelledby={mainLabel ? `${id}-section-tab-main` : undefined} hidden={shown !== 'main'} className="preview-panel">
          {children}
        </div>
        {extras && (
          <div role="tabpanel" aria-labelledby={`${id}-section-tab-extras`} hidden={shown !== 'extras'} className="preview-panel">
            {extras.content}
          </div>
        )}
      </div>
    </Dialog>
  )
}
