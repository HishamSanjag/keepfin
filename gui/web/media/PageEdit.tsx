// Edit mode for Home and Library: the header button, a handle, an eye and Row/Grid on each section, drag (or the arrow keys on
// the handle) to reorder, and the bar at the bottom with Card size, Text, Reset and Done. Changes save as they are made.
import {Fragment, useEffect, useRef, useState, type DragEvent, type KeyboardEvent, type ReactNode} from 'react'
import {
  moveSection,
  stepSection,
  styleSection,
  toggleSection,
  type LayoutPage,
  type PageLayout,
  type SectionPref,
  type SectionStyle,
} from '../../shared/core/layout'
import {Button, IconButton, Segmented} from '../ui/controls'
import {CardSizeSlider, CardTextChoice} from './CardControls'
import {dropsAfter} from './dropPosition'
import {resetPageLayout, updatePageLayout, usePageLayout} from './layoutStore'
import {SectionEditTools} from './sectionTools'

export type PageEdit = {
  page: LayoutPage
  layout: PageLayout
  editing: boolean
  setEditing: (editing: boolean) => void
}
/** What one section of a page shows: its name (for the edit controls) and how it draws itself in the saved style. */
export type SectionView = {
  title: string
  render: (style: SectionStyle) => ReactNode
}

/** A page's edit state. It lives with the page, so leaving the page ends editing; Esc ends it too. */
export function usePageEdit(page: LayoutPage): PageEdit {
  const layout = usePageLayout(page)
  const [editing, setEditing] = useState(false)
  useEffect(() => {
    if (!editing) return
    const key = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented && !document.querySelector('dialog[open]')) setEditing(false)
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [editing])
  return {page, layout, editing, setEditing}
}

/** The pencil in the page header, the same one as Edit sidebar. */
export function EditPageButton({edit, name}: {edit: PageEdit; name: string}) {
  return (
    <Button size="sm" variant="ghost" icon="edit" aria-pressed={edit.editing} onClick={() => edit.setEditing(!edit.editing)}>
      Edit {name}
    </Button>
  )
}

/** Sticks to the bottom of the page while editing. */
export function EditBar({edit}: {edit: PageEdit}) {
  if (!edit.editing) return null
  return (
    <div className="edit-bar" role="group" aria-label="Edit page layout">
      <div className="edit-bar-field">
        <span className="edit-bar-label">Card size</span>
        <CardSizeSlider />
      </div>
      <div className="edit-bar-field">
        <span className="edit-bar-label">Text</span>
        <CardTextChoice />
      </div>
      <Button size="sm" variant="ghost" onClick={() => resetPageLayout(edit.page)}>
        Reset
      </Button>
      <Button size="sm" variant="primary" onClick={() => edit.setEditing(false)}>
        Done
      </Button>
    </div>
  )
}

type Drag = {start: (id: string) => void; end: () => void}

function SectionTools({edit, section, title, drag}: {edit: PageEdit; section: SectionPref; title: string; drag: Drag}) {
  const change = (next: (layout: PageLayout) => PageLayout) => updatePageLayout(edit.page, next)
  const press = (event: KeyboardEvent<HTMLButtonElement>) => {
    const delta = event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0
    if (!delta) return
    event.preventDefault()
    const handle = event.currentTarget
    change((layout) => stepSection(layout, section.id, delta))
    // moving the section in the page can drop the focus; the next frame puts it back on the handle
    requestAnimationFrame(() => handle.focus())
  }
  const grab = (event: DragEvent<HTMLButtonElement>) => {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('application/x-keepfin-section', section.id)
    const slot = event.currentTarget.closest('.edit-slot')
    if (slot) event.dataTransfer.setDragImage(slot, 0, 0)
    drag.start(section.id)
  }
  return (
    <div className="edit-tools">
      {section.canStyle && (
        <Segmented<SectionStyle>
          label={`${title} layout`}
          size="sm"
          value={section.style}
          onChange={(style) => change((layout) => styleSection(layout, section.id, style))}
          options={[
            {value: 'row', label: 'Row'},
            {value: 'grid', label: 'Grid'},
          ]}
        />
      )}
      <IconButton
        icon={section.visible ? 'eye' : 'eyeOff'}
        size="sm"
        label={`${section.visible ? 'Hide' : 'Show'} ${title}`}
        onClick={() => change((layout) => toggleSection(layout, section.id))}
      />
      <IconButton icon="drag" size="sm" className="edit-handle" label={`Move ${title}`} draggable onDragStart={grab} onDragEnd={drag.end} onKeyDown={press} />
    </div>
  )
}

type DropProps = {
  className: string
  onDragOver: (event: DragEvent<HTMLElement>) => void
  onDrop: (event: DragEvent<HTMLElement>) => void
  onDragLeave: () => void
}

/** One section while editing: its frame shows the tools in its header; one with no frame (or nothing to show) gets a bar of its own. */
function SectionSlot({
  edit,
  section,
  title,
  drop,
  drag,
  children,
}: {
  edit: PageEdit
  section: SectionPref
  title: string
  drop: DropProps
  drag: Drag
  children: ReactNode
}) {
  const tools = <SectionTools edit={edit} section={section} title={title} drag={drag} />
  return (
    <div
      className={`edit-slot${section.visible ? '' : ' is-hidden'}${drop.className}`}
      onDragOver={drop.onDragOver}
      onDrop={drop.onDrop}
      onDragLeave={drop.onDragLeave}
    >
      <SectionEditTools.Provider value={tools}>
        <div className="edit-slot-bar">
          <h3 className="shelf-title">{title}</h3>
          <p className="muted small edit-slot-empty">Nothing to show here yet. You can still move or hide it.</p>
          {tools}
        </div>
        {children}
      </SectionEditTools.Provider>
    </div>
  )
}

/** The page's sections in the saved order; while editing the hidden ones show too (dimmed) and each can be moved. */
export function PageSections({edit, views}: {edit: PageEdit; views: Record<string, SectionView>}) {
  const dragging = useRef<string | undefined>(undefined)
  const [over, setOver] = useState<{id: string; after: boolean}>()
  const end = () => {
    dragging.current = undefined
    setOver(undefined)
  }
  const drag: Drag = {start: (id) => (dragging.current = id), end}
  const dropFor = (id: string): DropProps => ({
    className: over?.id === id ? (over.after ? ' drop-after' : ' drop-before') : '',
    onDragOver: (event) => {
      if (!dragging.current || dragging.current === id) return
      event.preventDefault()
      event.dataTransfer.dropEffect = 'move'
      setOver({
        id,
        after: dropsAfter(event.clientY, event.currentTarget.getBoundingClientRect()),
      })
    },
    onDrop: (event) => {
      const from = dragging.current
      if (!from || from === id) return
      event.preventDefault()
      const after = dropsAfter(event.clientY, event.currentTarget.getBoundingClientRect())
      updatePageLayout(edit.page, (layout) => moveSection(layout, from, id, after))
      end()
    },
    onDragLeave: () => setOver((current) => (current?.id === id ? undefined : current)),
  })
  const known = edit.layout.sections.filter((section) => views[section.id])
  if (!edit.editing)
    return (
      <>
        {known
          .filter((section) => section.visible)
          .map((section) => (
            <Fragment key={section.id}>{views[section.id].render(section.style)}</Fragment>
          ))}
      </>
    )
  return (
    <>
      {known.map((section) => (
        <SectionSlot key={section.id} edit={edit} section={section} title={views[section.id].title} drop={dropFor(section.id)} drag={drag}>
          {views[section.id].render(section.style)}
        </SectionSlot>
      ))}
    </>
  )
}
