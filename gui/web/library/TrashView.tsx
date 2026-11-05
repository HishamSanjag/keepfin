// The Trash page: what was deleted from the Keepfin folder waits here for 30 days. Restore is a button on each row; Delete for good is in
// the row's menu and always asks first, as does Empty trash. The list is read from the trash folders by the main process, so it is
// the same after a restart.
import {useEffect, useState} from 'react'
import type {TrashEntry, TrashResult} from '../../shared/library'
import {formatBytes, plural} from '../format'
import {navigate} from '../nav'
import {PageHeader} from '../shell/PageHeader'
import {PageToolbar} from '../shell/PageToolbar'
import '../styles/trash.css'
import {toast} from '../toasts'
import {Button, EmptyState, Spinner} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import {Icon} from '../ui/Icon'
import {Menu} from '../ui/Menu'
import {setLibView} from './libNav'
import {libraryAct, spaceById} from './libStore'
import {daysLeftWords, deletedWords, refreshTrash, useTrash} from './trashStore'

/** The nearest a row gets to urgent: this many days or fewer left. */
const SOON = 3

type Confirm = {kind: 'erase'; entries: TrashEntry[]} | {kind: 'empty'}

function kindIcon(entry: TrashEntry) {
  return entry.kind === 'video' ? 'video' : entry.kind === 'image' ? 'image' : 'music'
}

function Row({entry, selecting, selected, onToggle, onRestore, onErase, busy}: {entry: TrashEntry; selecting: boolean; selected: boolean; onToggle: () => void; onRestore: () => void; onErase: () => void; busy: boolean}) {
  const space = entry.space ? spaceById(entry.space)?.name : undefined
  const soon = entry.daysLeft <= SOON
  return (
    <li className={`trash-row${selected ? ' is-selected' : ''}`}>
      {selecting && (
        <input type="checkbox" className="trash-check" checked={selected} onChange={onToggle} aria-label={`Select ${entry.title}`} />
      )}
      <span className="trash-kind" aria-hidden="true">
        <Icon name={kindIcon(entry)} size={16} />
      </span>
      <div className="trash-text">
        <strong className="trash-name" title={entry.fileName}>
          {entry.title}
        </strong>
        <span className="trash-meta muted small tabular">
          {[space, formatBytes(entry.size)].filter(Boolean).join(' · ')}
        </span>
        <span className={`trash-when small tabular${soon ? ' is-soon' : ' muted'}`}>
          {deletedWords(entry.deletedAt)} ·{' '}
          <span className="trash-left">
            {soon && <Icon name="warning" size={13} />}
            {daysLeftWords(entry.daysLeft)}
          </span>
        </span>
      </div>
      {!selecting && (
        <div className="trash-actions">
          <Button size="sm" variant="secondary" disabled={busy} aria-label={`Restore ${entry.title}`} onClick={onRestore}>
            Restore
          </Button>
          <Menu label={`More actions for ${entry.title}`} items={[{label: 'Delete for good…', icon: 'trash', tone: 'danger', onSelect: onErase}]} />
        </div>
      )}
    </li>
  )
}

export function TrashView() {
  const trash = useTrash()
  const [selecting, setSelecting] = useState(false)
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const [confirm, setConfirm] = useState<Confirm>()
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    void refreshTrash()
  }, [])
  // a selection never keeps entries that have left the trash
  useEffect(() => {
    setPicked((current) => {
      const live = new Set(trash.entries.map((entry) => entry.id))
      const next = new Set([...current].filter((id) => live.has(id)))
      return next.size === current.size ? current : next
    })
  }, [trash.entries])

  const entries = trash.entries
  const totalSize = entries.reduce((sum, entry) => sum + entry.size, 0)
  const chosen = entries.filter((entry) => picked.has(entry.id))

  async function restore(list: TrashEntry[]) {
    if (!list.length || busy) return
    setBusy(true)
    const result = await libraryAct<TrashResult>('libraryTrashRestore', list.map((entry) => entry.id))
    setBusy(false)
    if (result.ok) {
      const message = result.value?.message ?? (list.length === 1 ? `Restored “${list[0].title}”` : `Restored ${list.length} files`)
      toast({tone: result.value?.failed ? 'error' : 'success', message})
      setPicked(new Set())
    }
    await refreshTrash()
  }

  async function erase(op: 'libraryTrashErase' | 'libraryTrashEmpty', list: TrashEntry[]) {
    setBusy(true)
    const result = await libraryAct<TrashResult>(op, op === 'libraryTrashErase' ? list.map((entry) => entry.id) : undefined)
    setBusy(false)
    setConfirm(undefined)
    if (result.ok) {
      toast({tone: result.value?.failed ? 'error' : 'success', message: result.value?.message ?? 'Deleted for good'})
      setPicked(new Set())
    }
    await refreshTrash()
  }

  const headerActions = (
    <Button size="sm" variant="secondary" icon="trash" disabled={!entries.length || busy} onClick={() => setConfirm({kind: 'empty'})}>
      Empty trash…
    </Button>
  )

  return (
    <div className="space-view trash-view">
      <PageHeader
        title="Trash"
        caption={
          !trash.hasFolder
            ? 'The trash belongs to your Keepfin folder.'
            : entries.length
              ? `${plural(entries.length, 'file')} · ${formatBytes(totalSize)}. Files are erased for good after 30 days.`
              : 'Files deleted from your Keepfin folder wait here for 30 days.'
        }
        crumbs={[
          {
            label: 'Storage & copies',
            onSelect: () => {
              setLibView({type: 'home'})
              navigate('Settings', 'storage')
            },
          },
        ]}
        actions={trash.hasFolder ? headerActions : undefined}
      />
      {entries.length > 0 && (
        <PageToolbar
          label="Trash toolbar"
          left={
            selecting ? (
              <>
                <span className="trash-count tabular" role="status">
                  {chosen.length ? `${chosen.length} selected` : 'Choose files'}
                </span>
                <Button size="sm" variant="secondary" disabled={!chosen.length || busy} onClick={() => void restore(chosen)}>
                  Restore
                </Button>
                <Button size="sm" variant="danger" icon="trash" disabled={!chosen.length || busy} onClick={() => setConfirm({kind: 'erase', entries: chosen})}>
                  Delete for good…
                </Button>
              </>
            ) : undefined
          }
          right={
            <Button
              size="sm"
              variant="secondary"
              icon="check"
              aria-pressed={selecting}
              onClick={() => {
                setSelecting(!selecting)
                setPicked(new Set())
              }}
            >
              {selecting ? 'Done' : 'Select'}
            </Button>
          }
        />
      )}
      <div className="space-body">
        {!trash.loaded ? null : !trash.hasFolder ? (
          <EmptyState icon="folder" title="No Keepfin folder">
            The trash belongs to your Keepfin folder. Files deleted from it appear here once your library is in one.
          </EmptyState>
        ) : !entries.length ? (
          <EmptyState icon="trash" title="Trash is empty">
            Files deleted from your Keepfin folder appear here for 30 days.
          </EmptyState>
        ) : (
          <ul className="trash-list" aria-label="Deleted files">
            {entries.map((entry) => (
              <Row
                key={entry.id}
                entry={entry}
                selecting={selecting}
                selected={picked.has(entry.id)}
                busy={busy}
                onToggle={() =>
                  setPicked((current) => {
                    const next = new Set(current)
                    if (!next.delete(entry.id)) next.add(entry.id)
                    return next
                  })
                }
                onRestore={() => void restore([entry])}
                onErase={() => setConfirm({kind: 'erase', entries: [entry]})}
              />
            ))}
          </ul>
        )}
      </div>
      {confirm && (
        <ConfirmErase
          confirm={confirm}
          count={confirm.kind === 'empty' ? entries.length : confirm.entries.length}
          size={confirm.kind === 'empty' ? totalSize : confirm.entries.reduce((sum, entry) => sum + entry.size, 0)}
          busy={busy}
          onCancel={() => setConfirm(undefined)}
          onConfirm={() => void (confirm.kind === 'empty' ? erase('libraryTrashEmpty', entries) : erase('libraryTrashErase', confirm.entries))}
        />
      )}
    </div>
  )
}

function ConfirmErase({confirm, count, size, busy, onCancel, onConfirm}: {confirm: Confirm; count: number; size: number; busy: boolean; onCancel: () => void; onConfirm: () => void}) {
  const empty = confirm.kind === 'empty'
  const single = !empty && confirm.entries.length === 1 ? confirm.entries[0] : undefined
  return (
    <Dialog
      title={empty ? 'Empty trash?' : single ? 'Delete for good?' : `Delete ${count} files for good?`}
      labelledBy="trash-confirm-title"
      onClose={busy ? () => undefined : onCancel}
      footer={
        <>
          <Button variant="ghost" onClick={onCancel} disabled={busy} autoFocus>
            Cancel
          </Button>
          <Button variant="danger" icon="trash" loading={busy} onClick={onConfirm}>
            {empty ? 'Empty trash' : 'Delete for good'}
          </Button>
        </>
      }
    >
      <p>
        {empty
          ? `Erase ${plural(count, 'file')} (${formatBytes(size)}) for good? This can’t be undone.`
          : single
            ? `“${single.title}” will be erased from this PC (${formatBytes(size)}). This can’t be undone.`
            : `Erase ${plural(count, 'file')} (${formatBytes(size)}) for good? This can’t be undone.`}
      </p>
      {busy && (
        <p className="muted small" role="status">
          <Spinner /> {empty ? 'Emptying the trash…' : 'Deleting…'}
        </p>
      )}
    </Dialog>
  )
}
