// Spaces v2 dialogs: add from the library (picker first), user tags, and follow settings.
import {useVirtualizer} from '@tanstack/react-virtual'
import {useDeferredValue, useMemo, useRef, useState} from 'react'
import {everyWords, followIntervals, normalizeTags, type CollectionFollow, type FollowMode, type LibraryItem, type LibraryMoveResult} from '../../shared/library'
import {captureText} from '../capture/useCapture'
import {extractLinks, plural} from '../format'
import {navigate} from '../nav'
import {toast} from '../toasts'
import {Button, IconButton, ProgressBar, Segmented, Select, Stepper, Switch, Tabs} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import {Icon} from '../ui/Icon'
import {closeDialog, openDialog} from './dialogState'
import {Field} from './Field'
import {getLibItem, libraryAct, useLibrary, useLibrarySettings} from './libStore'
import {matchesQuery, queryWords} from './model'
import {newUndoId, undoToast} from './undo'

// ---------------------------------------------------------------------------
// Add from the library
// ---------------------------------------------------------------------------

type PickTab = 'collections' | 'songs' | 'videos'
type Row = {key: string; title: string; sub: string}

export function AddFromDialog({space}: {space: string}) {
  const settings = useLibrarySettings()
  const lib = useLibrary()
  const target = settings?.spaces.find((entry) => entry.id === space)
  const [tab, setTab] = useState<PickTab>('songs')
  const [query, setQuery] = useState('')
  const [mode, setMode] = useState<'move' | 'copy'>('move')
  const [picked, setPicked] = useState<{items: ReadonlySet<string>; collections: ReadonlySet<string>}>({items: new Set(), collections: new Set()})
  const [busy, setBusy] = useState(false)
  const deferred = useDeferredValue(query)
  const words = useMemo(() => queryWords(deferred), [deferred])
  const spaceName = (id: string) => settings?.spaces.find((entry) => entry.id === id)?.name ?? ''

  const itemsFor = useMemo(() => {
    const wanted = tab === 'songs' ? 'audio' : 'video'
    return lib.snapshot.items.filter((item) => item.space !== space && !item.missing && item.kind === wanted && matchesQuery(item, words))
  }, [lib.snapshot.items, space, tab, words])
  const collections = useMemo(
    () =>
      lib.snapshot.collections.filter(
        (collection) => collection.itemIds.some((id) => getLibItem(id)?.space !== space && getLibItem(id)) && (!words.length || words.every((word) => collection.name.toLowerCase().includes(word))),
      ),
    [lib.snapshot.collections, space, words],
  )
  const rows: Row[] = useMemo(
    () =>
      tab === 'collections'
        ? collections.map((collection) => ({key: collection.id, title: collection.name, sub: `${collection.kind === 'own' ? 'Yours' : 'Collection'} · ${plural(collection.itemIds.length, 'item')}`}))
        : itemsFor.map((item: LibraryItem) => ({key: item.id, title: item.title, sub: [item.artist, spaceName(item.space)].filter(Boolean).join(' · ')})),
    [tab, collections, itemsFor], // eslint-disable-line react-hooks/exhaustive-deps
  )
  const chosen = tab === 'collections' ? picked.collections : picked.items
  const total = picked.items.size + picked.collections.size
  const toggle = (key: string) =>
    setPicked((previous) => {
      const field = tab === 'collections' ? 'collections' : 'items'
      const next = new Set(previous[field])
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return {...previous, [field]: next}
    })
  const selectShown = () =>
    setPicked((previous) => {
      const field = tab === 'collections' ? 'collections' : 'items'
      return {...previous, [field]: new Set([...previous[field], ...rows.map((row) => row.key)])}
    })

  const scroller = useRef<HTMLDivElement>(null)
  const virtualizer = useVirtualizer({count: rows.length, getScrollElement: () => scroller.current, estimateSize: () => 52, overscan: 8})

  const operation = lib.snapshot.operation
  async function run() {
    setBusy(true)
    // a move can be taken back; a copy leaves the originals where they are
    const undoId = mode === 'move' ? newUndoId() : undefined
    const result = await libraryAct<LibraryMoveResult>('libraryAddFrom', [...picked.items], {space, addMode: mode, collectionIds: [...picked.collections], undoId})
    setBusy(false)
    if (!result.ok) return
    const done = result.value?.moved?.length ?? total
    const failed = result.value?.failed ?? []
    const message = failed.length
      ? `${mode === 'move' ? 'Moved' : 'Copied'} ${plural(done, 'item')} to ${target?.name}. ${plural(failed.length, 'item')} couldn’t be added and stayed where they were. ${failed[0].message}`
      : mode === 'move'
        ? done === 1
          ? `Moved to ${target?.name}`
          : `Moved ${done} files to ${target?.name}`
        : `Copied ${plural(done, 'item')} to ${target?.name}.`
    if (undoId && done > 0) undoToast({undoId, message, tone: failed.length ? 'error' : 'success'})
    else toast({tone: failed.length ? 'error' : 'success', message})
    closeDialog()
  }
  const emptyWord = tab === 'collections' ? 'collections' : tab
  return (
    <Dialog
      title={`Add to ${target?.name ?? 'this space'}`}
      eyebrow="From your library"
      labelledBy="addfrom-title"
      className="dialog-wide dialog-picker"
      onClose={closeDialog}
      footer={
        <>
          <Button
            variant="ghost"
            icon="folder"
            className="addfrom-device"
            disabled={busy}
            onClick={() => {
              closeDialog()
              openDialog({type: 'import', space})
            }}
          >
            Choose files on this computer…
          </Button>
          <Button
            variant="ghost"
            icon="link"
            disabled={busy}
            onClick={() => {
              closeDialog()
              openDialog({type: 'addLink', space})
            }}
          >
            From a link…
          </Button>
          <Button variant="ghost" onClick={closeDialog} disabled={busy}>
            Cancel
          </Button>
          <Button variant="primary" icon={mode === 'move' ? 'move' : 'copy'} loading={busy} disabled={!total} onClick={() => void run()}>
            {mode === 'move' ? 'Move' : 'Copy'} {total ? plural(total, 'selection') : ''}
          </Button>
        </>
      }
    >
      <Tabs<PickTab>
        label="Add from"
        idBase="addfrom"
        value={tab}
        onChange={(next) => {
          setTab(next)
          setQuery('')
        }}
        tabs={[
          {value: 'collections', label: 'Collections'},
          {value: 'songs', label: 'Songs'},
          {value: 'videos', label: 'Videos'},
        ]}
      />
      <div className="addfrom-tools">
        <div className="search lib-search">
          <Icon name="search" size={15} />
          <input className="input" type="search" aria-label="Search the library" placeholder={`Search ${emptyWord}`} value={query} onChange={(event) => setQuery(event.target.value)} />
        </div>
        <Button size="sm" variant="ghost" disabled={!rows.length} onClick={selectShown}>
          Select all shown
        </Button>
      </div>
      <div id="addfrom-panel" role="tabpanel" aria-labelledby={`addfrom-tab-${tab}`}>
        <div className="addfrom-scroll" ref={scroller} role="list" aria-label={`Library ${emptyWord}`} tabIndex={0}>
          <div style={{height: virtualizer.getTotalSize(), position: 'relative'}}>
            {virtualizer.getVirtualItems().map((virtual) => {
              const row = rows[virtual.index]
              const on = chosen.has(row.key)
              return (
                <div key={row.key} role="listitem" className="addfrom-row" style={{position: 'absolute', top: 0, left: 0, right: 0, height: virtual.size, transform: `translateY(${virtual.start}px)`}}>
                  <button type="button" role="checkbox" aria-checked={on} className={on ? 'checked' : undefined} aria-label={`${row.title}${row.sub ? `, ${row.sub}` : ''}`} onClick={() => toggle(row.key)}>
                    <span className={`check-box${on ? ' on' : ''}`} aria-hidden="true">
                      {on && <Icon name="check" size={13} />}
                    </span>
                    <span className="pick-text">
                      <strong>{row.title}</strong>
                      <span>{row.sub}</span>
                    </span>
                  </button>
                </div>
              )
            })}
          </div>
          {!rows.length && <p className="muted pane-note">Nothing in your library to add here{query ? ` matches “${query}”` : ''}.</p>}
        </div>
      </div>
      <Field label="What happens to the files" help={mode === 'move' ? 'The files leave their current space and live in this one. If a move fails, the original stays.' : 'The originals stay where they are; this space gets its own copy.'}>
        <Segmented<'move' | 'copy'>
          label="Move or copy"
          value={mode}
          onChange={setMode}
          options={[
            {value: 'move', label: 'Move'},
            {value: 'copy', label: 'Copy'},
          ]}
        />
      </Field>
      {busy && operation && (
        <div className="addfrom-progress" role="status">
          <span>
            {operation.kind === 'copy' ? 'Copying' : 'Moving'} {operation.done} of {operation.total}
          </span>
          <ProgressBar value={operation.total ? (operation.done / operation.total) * 100 : 0} label="Progress" />
        </div>
      )}
    </Dialog>
  )
}

// ---------------------------------------------------------------------------
// Add from a link
// ---------------------------------------------------------------------------

/**
 * A playlist, album, channel or single link for this space. It goes through the same inspection and review as the
 * Downloads tab, with Save to set to this space, and the person returns here once the download starts.
 */
export function AddLinkDialog({space}: {space: string}) {
  const settings = useLibrarySettings()
  const target = settings?.spaces.find((entry) => entry.id === space)
  const [text, setText] = useState('')
  const [error, setError] = useState('')
  const links = extractLinks(text)
  function submit() {
    if (!links.length) {
      setError('Paste a link to a playlist, album, channel or single video or song.')
      return
    }
    closeDialog()
    navigate('Downloads')
    captureText(links.join('\n'), space)
  }
  return (
    <Dialog
      title={`Add to ${target?.name ?? 'this space'}`}
      eyebrow="From a link"
      labelledBy="addlink-title"
      onClose={closeDialog}
      footer={
        <>
          <Button variant="ghost" onClick={closeDialog}>
            Cancel
          </Button>
          <Button variant="primary" icon="search" disabled={!text.trim()} onClick={submit}>
            Review link
          </Button>
        </>
      }
    >
      <form
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <Field label="Link" htmlFor="addlink-input" error={error} help={`Keepfin opens it for review with Save to set to ${target?.name ?? 'this space'}. Playlists and channels can keep this space in sync.`}>
          <input
            id="addlink-input"
            className="input"
            type="url"
            inputMode="url"
            autoFocus
            spellCheck={false}
            placeholder="https://"
            value={text}
            onChange={(event) => {
              setText(event.target.value)
              setError('')
            }}
          />
        </Field>
      </form>
    </Dialog>
  )
}

// ---------------------------------------------------------------------------
// Tags
// ---------------------------------------------------------------------------

export function TagsDialog({ids}: {ids: string[]}) {
  useLibrary()
  const items = ids.map(getLibItem).filter((item): item is LibraryItem => !!item)
  const [text, setText] = useState('')
  const counts = new Map<string, number>()
  for (const item of items) for (const tag of item.userTags ?? []) counts.set(tag, (counts.get(tag) ?? 0) + 1)
  const sourceTags = [...new Set(items.flatMap((item) => item.tags ?? []))].slice(0, 12)
  const name = items.length === 1 ? `“${items[0].title}”` : plural(items.length, 'item')
  async function add() {
    const tags = normalizeTags(text, 10)
    if (!tags.length) return
    setText('')
    await change({add: tags}, tags.some((tag) => items.some((item) => !(item.userTags ?? []).includes(tag))))
  }
  /** Saves a tag change as one action that Undo reverses per item. A change that alters nothing has no Undo. */
  async function change(tags: {add?: string[]; remove?: string[]}, changes: boolean) {
    const undoId = changes ? newUndoId() : undefined
    const result = await libraryAct('libraryTag', ids, {tags, undoId})
    if (!result.ok) return
    const message = ids.length === 1 ? 'Tags saved' : `Tags saved on ${ids.length} items`
    if (undoId) undoToast({undoId, message})
    else toast({tone: 'success', message})
  }
  return (
    <Dialog title="Tags" eyebrow={name} labelledBy="tags-title" onClose={closeDialog} footer={<Button variant="primary" onClick={closeDialog}>Done</Button>}>
      <form
        className="tag-add"
        onSubmit={(event) => {
          event.preventDefault()
          void add()
        }}
      >
        <input className="input" aria-label="New tag" placeholder="Add a tag, such as workout" maxLength={30} autoFocus value={text} onChange={(event) => setText(event.target.value)} />
        <Button type="submit" icon="plus" disabled={!normalizeTags(text).length}>
          Add tag
        </Button>
      </form>
      <h3 className="shelf-title">Your tags</h3>
      {counts.size ? (
        <ul className="value-chips" aria-label="Your tags on this selection">
          {[...counts].map(([tag, count]) => (
            <li key={tag} className="value-chip">
              <Icon name="tag" size={13} />
              <span>
                {tag}
                {items.length > 1 ? ` · ${count} of ${items.length}` : ''}
              </span>
              <IconButton icon="close" size="sm" label={`Remove tag ${tag}`} onClick={() => void change({remove: [tag]}, true)} />
            </li>
          ))}
        </ul>
      ) : (
        <p className="muted small">No tags of your own yet.</p>
      )}
      {sourceTags.length > 0 && (
        <>
          <h3 className="shelf-title">From the source</h3>
          <p className="muted small">{sourceTags.join(', ')}. These come with the download and stay as they are.</p>
        </>
      )}
    </Dialog>
  )
}

// ---------------------------------------------------------------------------
// Follow settings
// ---------------------------------------------------------------------------

export function FollowDialog({id}: {id: string}) {
  const lib = useLibrary()
  const collection = lib.snapshot.collections.find((entry) => entry.id === id)
  const current: CollectionFollow | undefined = collection?.follow
  const [mode, setMode] = useState<FollowMode>(current?.mode ?? 'auto')
  const [hours, setHours] = useState<CollectionFollow['intervalHours']>(current?.intervalHours ?? 6)
  const [notify, setNotify] = useState(current?.notify ?? true)
  const [above, setAbove] = useState(current?.confirmAbove ?? 20)
  const [busy, setBusy] = useState(false)
  if (!collection) return null
  const modes: {value: FollowMode; title: string; help: string}[] = [
    {value: 'auto', title: 'Automatically', help: 'Keepfin checks on a schedule and downloads new items.'},
    {value: 'manual', title: 'When I click Check now', help: 'Nothing happens until you ask.'},
    {value: 'off', title: 'Don’t follow', help: 'Keeps what you have and stops looking for more.'},
  ]
  return (
    <Dialog
      title="Follow settings"
      eyebrow={collection.name}
      labelledBy="follow-title"
      className="dialog-wide"
      onClose={closeDialog}
      footer={
        <>
          <Button variant="ghost" onClick={closeDialog}>
            Cancel
          </Button>
          <Button
            variant="primary"
            loading={busy}
            onClick={async () => {
              setBusy(true)
              const result = await libraryAct('collectionFollow', undefined, {id, follow: {mode, intervalHours: hours, notify, confirmAbove: above}})
              setBusy(false)
              if (result.ok) {
                toast({tone: 'success', message: mode === 'off' ? `Stopped following ${collection.name}.` : `Saved how Keepfin follows ${collection.name}.`})
                closeDialog()
              }
            }}
          >
            Save
          </Button>
        </>
      }
    >
      <div className="form-grid">
        {collection.source && (
          <Field label="Link" htmlFor="follow-link">
            <input id="follow-link" className="input path-input" readOnly value={collection.source.url} onFocus={(event) => event.currentTarget.select()} />
          </Field>
        )}
        <div className="pick-list" role="radiogroup" aria-label="How to follow">
          {modes.map((option) => (
            <button key={option.value} type="button" role="radio" aria-checked={mode === option.value} className={mode === option.value ? 'checked' : undefined} onClick={() => setMode(option.value)}>
              <span className="pick-text">
                <strong>{option.title}</strong>
                <span>{option.help}</span>
              </span>
              {mode === option.value && <Icon name="check" size={16} />}
            </button>
          ))}
        </div>
        {mode === 'auto' && (
          <Field label="Check" help={`Checks run while Keepfin is open, ${everyWords(hours)}.`}>
            <Select<string>
              label="Check interval"
              value={String(hours)}
              onChange={(value) => setHours(Number(value) as CollectionFollow['intervalHours'])}
              options={followIntervals.map((hoursOption) => ({value: String(hoursOption), label: everyWords(hoursOption)}))}
            />
          </Field>
        )}
        {mode !== 'off' && (
          <>
            <div className="switch-line">
              <div>
                <strong>Notify me</strong>
                <p className="field-help">A notification when new items are found.</p>
              </div>
              <Switch label="Notify me about new items" checked={notify} onChange={setNotify} />
            </div>
            <div className="switch-line">
              <div>
                <strong>Ask me above</strong>
                <p className="field-help">If more new items than this turn up, they wait for your review.</p>
              </div>
              <Stepper label="Ask above this many new items" value={above} min={1} max={500} onChange={setAbove} />
            </div>
            <p className="field-help">Checks run while Keepfin is open.</p>
          </>
        )}
      </div>
    </Dialog>
  )
}
