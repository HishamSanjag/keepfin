import {useId, useState, type ChangeEvent, type ReactNode} from 'react'
import {defaultSpaceFolder, HOME_SPACE_LIMIT, isInFolder, isKeptSpace, isPinned, spaceColorOf, spaceColors, spaceIconOf, spaceIcons, type Collection, type FolderLayout, type LibraryDeleteResult, type LibraryImportResult, type LibraryItem, type LibraryMoveResult, type SmartRules, type Space, type SpaceColor, type SpaceIcon, type SpaceKind, type SpaceNaming} from '../../shared/library'
import {cleanRules, SmartRulesEditor} from './SmartRulesEditor'
import {colorNames, iconNames} from './spacesModel'
import {AddFromDialog, AddLinkDialog, FollowDialog, TagsDialog} from './SpaceDialogs'
import {fileName, formatBytes, plural} from '../format'
import {toast} from '../toasts'
import {newUndoId, undoToast} from './undo'
import {Button, InlineAlert, ProgressBar, Segmented, Select, Switch} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import {Icon} from '../ui/Icon'
import {remapQueueId} from '../player/playerStore'
import {BringInDialog} from '../settings/BringIn'
import {useKeepfinRoot} from '../setup/storageApi'
import {Field} from './Field'
import {playableKind} from './model'
import {ItemDetailsDialog} from './ItemDetails'
import {MusicVideoDialog, RemoveMusicVideoDialog} from './MusicVideoDialogs'
import {closeDialog, useDialog, type LibraryDialog} from './dialogState'
import {getLibItem, getLibrary, libraryAct, useLibrary, useLibrarySettings} from './libStore'

// ---------------------------------------------------------------------------
// Move to…
// ---------------------------------------------------------------------------

function MoveDialog({ids: givenIds, mode = 'move', collection}: {ids: string[]; mode?: 'move' | 'copy'; collection?: {id: string; name: string}}) {
  const settings = useLibrarySettings()
  const lib = useLibrary()
  const ids = collection ? (lib.snapshot.collections.find((entry) => entry.id === collection.id)?.itemIds ?? []) : givenIds
  const items = ids.map(getLibItem).filter((item): item is LibraryItem => !!item)
  const here = new Set(items.map((item) => item.space))
  const [target, setTarget] = useState('')
  const [busy, setBusy] = useState(false)
  const copying = mode === 'copy'
  const verb = copying ? 'Copy' : 'Move'
  const spaces = (settings?.spaces || []).filter((space) => space.type !== 'smart' && !space.deviceOnly && !(here.size === 1 && here.has(space.id)))
  const name = collection ? `“${collection.name}”` : items.length === 1 ? `“${items[0].title}”` : plural(items.length, 'item')
  const operation = lib.snapshot.operation
  return (
    <Dialog
      title={collection ? `${verb} to space…` : `${verb} to…`}
      eyebrow={name}
      labelledBy="move-title"
      onClose={busy ? () => undefined : closeDialog}
      footer={
        <>
          <Button variant="ghost" onClick={closeDialog} disabled={busy}>
            Cancel
          </Button>
          <Button
            variant="primary"
            icon={copying ? 'copy' : 'move'}
            disabled={!target || !items.length}
            loading={busy}
            onClick={async () => {
              setBusy(true)
              // a move can be taken back (a copy leaves the originals, so it needs no Undo)
              const undoId = copying ? undefined : newUndoId()
              const result =
                collection || copying
                  ? await libraryAct<LibraryMoveResult>('libraryAddFrom', collection ? [] : ids, {space: target, addMode: mode, collectionIds: collection ? [collection.id] : [], undoId})
                  : await libraryAct<LibraryMoveResult>('libraryMove', ids, {space: target, undoId})
              setBusy(false)
              if (result.ok) {
                const done = result.value?.moved?.length ?? ids.length
                const failed = result.value?.failed ?? []
                const where = spaces.find((space) => space.id === target)?.name
                const past = copying ? 'copied' : 'moved'
                const message = failed.length
                  ? `${copying ? 'Copied' : 'Moved'} ${plural(done, 'item')} to ${where}. ${plural(failed.length, 'item')} couldn’t be ${past} and stayed where they were. ${failed[0].message}`
                  : result.value?.cancelled
                    ? `Stopped. ${plural(done, 'item')} ${past} to ${where}.`
                    : copying
                      ? `Copied ${plural(done, 'item')} to ${where}.`
                      : done === 1
                        ? `Moved to ${where}`
                        : `Moved ${done} files to ${where}`
                if (undoId && done > 0) undoToast({message, undoId, tone: failed.length ? 'error' : result.value?.cancelled ? 'info' : 'success'})
                else toast({tone: failed.length ? 'error' : result.value?.cancelled ? 'info' : 'success', message})
                closeDialog()
              }
            }}
          >
            {verb}
          </Button>
        </>
      }
    >
      <p>{copying ? 'Copies go to the space’s folder and the originals stay where they are.' : 'Files move to the space’s folder. If the move fails, the original stays where it is.'}</p>
      {busy && operation && (
        <div className="addfrom-progress" role="status">
          <span className="tabular">
            {copying ? 'Copying' : 'Moving'} {operation.done} of {operation.total}
          </span>
          <ProgressBar value={operation.total ? (operation.done / operation.total) * 100 : 0} label="Progress" />
        </div>
      )}
      <div className="pick-list" role="radiogroup" aria-label="Destination space">
        {spaces.map((space) => (
          <button key={space.id} type="button" role="radio" aria-checked={target === space.id} className={target === space.id ? 'checked' : undefined} onClick={() => setTarget(space.id)}>
            <Icon name={space.kind === 'music' ? 'music' : space.kind === 'video' ? 'video' : 'folder'} size={17} />
            <span className="pick-text">
              <strong>{space.name}</strong>
              <span title={space.folder}>{space.folder}</span>
            </span>
            {target === space.id && <Icon name="check" size={16} />}
          </button>
        ))}
        {!spaces.length && <p className="muted">There is no other space to move to. Create one from Library › New space.</p>}
      </div>
    </Dialog>
  )
}

// ---------------------------------------------------------------------------
// Playlists
// ---------------------------------------------------------------------------

function PlaylistAddDialog({ids}: {ids: string[]}) {
  const {playlists} = useLibrary()
  const [busy, setBusy] = useState<string>()
  const songs = ids.filter((id) => playableKind(getLibItem(id)))
  async function add(playlistId: string | undefined, name: string) {
    setBusy(playlistId || 'new')
    const undoId = newUndoId()
    // An existing playlist gets the songs added to it as it is saved now (never a list this page held); a new one is made with them.
    const result = playlistId
      ? await libraryAct('collectionAdd', undefined, {id: playlistId, ids: songs, undoId})
      : await libraryAct('collectionSave', undefined, {collection: {name, itemIds: songs}, undoId})
    setBusy(undefined)
    if (result.ok) {
      undoToast({
        undoId,
        message: playlistId ? (songs.length === 1 ? `Added to “${name}”` : `Added ${songs.length} songs to “${name}”`) : `Made the collection “${name}”`,
      })
      closeDialog()
    }
  }
  const [name, setName] = useState('')
  const id = useId()
  return (
    <Dialog title="Add to playlist" eyebrow={plural(songs.length, 'item')} labelledBy="playlist-add-title" onClose={closeDialog} footer={<Button variant="ghost" onClick={closeDialog}>Cancel</Button>}>
      {songs.length === 0 ? (
        <p>Playlists hold songs and videos. Pick some to add them.</p>
      ) : (
        <>
          <div className="pick-list" role="list" aria-label="Your playlists">
            {playlists.map((playlist) => (
              <button key={playlist.id} type="button" role="listitem" disabled={!!busy} onClick={() => void add(playlist.id, playlist.name)}>
                <Icon name="playlistAdd" size={17} />
                <span className="pick-text">
                  <strong>{playlist.name}</strong>
                  <span>{plural(playlist.itemIds.length, 'song')}</span>
                </span>
                {busy === playlist.id && <Icon name="refresh" size={16} />}
              </button>
            ))}
            {!playlists.length && <p className="muted">No playlists yet. Name your first one below.</p>}
          </div>
          <form
            className="inline-form new-playlist"
            onSubmit={(event) => {
              event.preventDefault()
              if (name.trim()) void add(undefined, name.trim())
            }}
          >
            <label className="sr-only" htmlFor={id}>
              New playlist name
            </label>
            <input id={id} className="input grow" placeholder="New playlist name" value={name} maxLength={80} onChange={(event) => setName(event.target.value)} />
            <Button type="submit" icon="plus" disabled={!name.trim() || !!busy} loading={busy === 'new'}>
              Create and add
            </Button>
          </form>
        </>
      )}
    </Dialog>
  )
}

function PlaylistNameDialog({id, ids, onMade}: {id?: string; ids?: string[]; onMade?: (id: string) => void}) {
  const {playlistsById} = useLibrary()
  const existing = id ? playlistsById.get(id) : undefined
  const [name, setName] = useState(existing?.name || '')
  const [busy, setBusy] = useState(false)
  const field = useId()
  const clash = getLibrary().playlists.some((playlist) => playlist.id !== id && playlist.name.toLowerCase() === name.trim().toLowerCase())
  return (
    <Dialog
      title={id ? 'Rename playlist' : 'New playlist'}
      labelledBy="playlist-name-title"
      onClose={closeDialog}
      footer={
        <>
          <Button variant="ghost" onClick={closeDialog}>
            Cancel
          </Button>
          <Button
            variant="primary"
            type="submit"
            form="playlist-name-form"
            loading={busy}
            disabled={!name.trim() || clash}
          >
            {id ? 'Save' : 'Create'}
          </Button>
        </>
      }
    >
      <form
        id="playlist-name-form"
        onSubmit={async (event) => {
          event.preventDefault()
          if (!name.trim() || clash) return
          if (id && name.trim() === existing?.name) return closeDialog()
          setBusy(true)
          const undoId = newUndoId()
          // A rename sends only the name (the songs are not this page's to save); a new playlist is made with the songs picked for it.
          const result = await libraryAct<Collection>('collectionSave', undefined, {collection: id ? {id, name: name.trim()} : {name: name.trim(), itemIds: ids || []}, undoId})
          setBusy(false)
          if (result.ok) {
            if (!id && result.value?.id) onMade?.(result.value.id)
            undoToast({undoId, message: id ? `Renamed to “${name.trim()}”` : `Made the collection “${name.trim()}”`})
            closeDialog()
          }
        }}
      >
        <Field label="Name" htmlFor={field} error={clash ? 'You already have a playlist with this name.' : undefined}>
          <input id={field} className={`input${clash ? ' invalid' : ''}`} autoFocus value={name} maxLength={80} onChange={(event) => setName(event.target.value)} />
        </Field>
      </form>
    </Dialog>
  )
}

function PlaylistDeleteDialog({id}: {id: string}) {
  const {playlistsById} = useLibrary()
  const playlist = playlistsById.get(id)
  const [busy, setBusy] = useState(false)
  return (
    <Dialog
      title="Delete this playlist?"
      labelledBy="playlist-delete-title"
      onClose={closeDialog}
      footer={
        <>
          <Button variant="ghost" onClick={closeDialog} autoFocus>
            Keep playlist
          </Button>
          <Button
            variant="danger"
            icon="trash"
            loading={busy}
            onClick={async () => {
              setBusy(true)
              const undoId = newUndoId()
              const result = await libraryAct('collectionDelete', undefined, {id, undoId})
              setBusy(false)
              if (result.ok) {
                undoToast({undoId, message: `Deleted “${playlist?.name || 'the collection'}”. The songs stay in your library.`})
                closeDialog()
              }
            }}
          >
            Delete playlist
          </Button>
        </>
      }
    >
      <p className="delete-name">{playlist?.name}</p>
      <p>The playlist is removed. The songs stay in your library and on disk.</p>
    </Dialog>
  )
}

// ---------------------------------------------------------------------------
// Edit info / Rename / Delete
// ---------------------------------------------------------------------------

function EditInfoDialog({id}: {id: string}) {
  const item = getLibItem(id)
  const [form, setForm] = useState(() => ({
    title: item?.title || '',
    artist: item?.artist || '',
    album: item?.album || '',
    albumArtist: item?.albumArtist || '',
    track: item?.track ? String(item.track) : '',
    disc: item?.disc ? String(item.disc) : '',
    year: item?.year ? String(item.year) : '',
    genre: item?.genre || '',
    cover: '',
  }))
  const [busy, setBusy] = useState(false)
  if (!item) return null
  const numeric = (value: string, max: number) => value === '' || (/^\d{1,4}$/.test(value) && Number(value) <= max)
  const coverOk = !form.cover || /^https:\/\/\S+$/i.test(form.cover.trim())
  const invalid = !form.title.trim() || !numeric(form.track, 9999) || !numeric(form.disc, 99) || !numeric(form.year, 2999) || !coverOk
  const set = (key: keyof typeof form) => (event: ChangeEvent<HTMLInputElement>) => setForm((previous) => ({...previous, [key]: event.target.value}))
  async function save() {
    const info: Record<string, string | number> = {}
    const text = (key: 'title' | 'artist' | 'album' | 'albumArtist' | 'genre') => {
      if (form[key].trim() !== (item![key] || '')) info[key] = form[key].trim()
    }
    ;(['title', 'artist', 'album', 'albumArtist', 'genre'] as const).forEach(text)
    const number = (key: 'track' | 'disc' | 'year') => {
      if (form[key] !== (item![key] ? String(item![key]) : '') && form[key] !== '') info[key] = Number(form[key])
    }
    ;(['track', 'disc', 'year'] as const).forEach(number)
    if (form.cover.trim()) info.cover = form.cover.trim()
    if (!Object.keys(info).length) return closeDialog()
    setBusy(true)
    // A new cover cannot be put back exactly, so an edit that replaces it has no Undo
    const undoId = info.cover ? undefined : newUndoId()
    const result = await libraryAct('libraryEditInfo', id, {info, undoId})
    setBusy(false)
    if (result.ok) {
      if (undoId) undoToast({undoId, message: 'Info saved'})
      else toast({tone: 'success', message: 'Info saved'})
      closeDialog()
    }
  }
  return (
    <Dialog
      title="Edit info"
      eyebrow={fileName(item.path)}
      labelledBy="edit-info-title"
      className="dialog-wide"
      onClose={closeDialog}
      footer={
        <>
          <Button variant="ghost" onClick={closeDialog}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" form="edit-info-form" loading={busy} disabled={invalid}>
            Save
          </Button>
        </>
      }
    >
      <form
        id="edit-info-form"
        className="form-grid"
        onSubmit={(event) => {
          event.preventDefault()
          if (!invalid) void save()
        }}
      >
        <Field label="Title" htmlFor="ei-title">
          <input id="ei-title" className="input" autoFocus value={form.title} onChange={set('title')} />
        </Field>
        <Field label="Artist" htmlFor="ei-artist">
          <input id="ei-artist" className="input" value={form.artist} onChange={set('artist')} />
        </Field>
        <Field label="Album" htmlFor="ei-album">
          <input id="ei-album" className="input" value={form.album} onChange={set('album')} />
        </Field>
        <Field label="Album artist" htmlFor="ei-aa" help="Groups compilations under one name.">
          <input id="ei-aa" className="input" value={form.albumArtist} onChange={set('albumArtist')} />
        </Field>
        <div className="form-row">
          <Field label="Track" htmlFor="ei-track">
            <input id="ei-track" className="input tabular" inputMode="numeric" value={form.track} onChange={set('track')} />
          </Field>
          <Field label="Disc" htmlFor="ei-disc">
            <input id="ei-disc" className="input tabular" inputMode="numeric" value={form.disc} onChange={set('disc')} />
          </Field>
          <Field label="Year" htmlFor="ei-year">
            <input id="ei-year" className="input tabular" inputMode="numeric" value={form.year} onChange={set('year')} />
          </Field>
        </div>
        <Field label="Genre" htmlFor="ei-genre">
          <input id="ei-genre" className="input" value={form.genre} onChange={set('genre')} />
        </Field>
        <Field label="New cover" htmlFor="ei-cover" help="Optional. An https:// link to an image; it is embedded in the file." error={coverOk ? undefined : 'Use a full https:// image link.'}>
          <input id="ei-cover" className={`input${coverOk ? '' : ' invalid'}`} placeholder="https://…" value={form.cover} onChange={set('cover')} />
        </Field>
      </form>
      <p className="field-help">Changes are written into the file’s tags, so other players see them too.</p>
    </Dialog>
  )
}

function RenameDialog({id}: {id: string}) {
  const item = getLibItem(id)
  const original = fileName(item?.path)
  const dot = original.lastIndexOf('.')
  const ext = dot > 0 ? original.slice(dot) : ''
  const base = dot > 0 ? original.slice(0, dot) : original
  const [name, setName] = useState(base)
  const [busy, setBusy] = useState(false)
  const bad = /[\\/:*?"<>|]/.test(name) || !name.trim() || name.length > 200
  if (!item) return null
  return (
    <Dialog
      title="Rename file"
      eyebrow={original}
      labelledBy="rename-title"
      onClose={closeDialog}
      footer={
        <>
          <Button variant="ghost" onClick={closeDialog}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" form="rename-form" loading={busy} disabled={bad || name === base}>
            Rename
          </Button>
        </>
      }
    >
      <form
        id="rename-form"
        onSubmit={async (event) => {
          event.preventDefault()
          if (bad || name === base) return
          setBusy(true)
          const undoId = newUndoId()
          const result = await libraryAct<{id?: string}>('libraryRename', id, {text: name.trim(), undoId})
          setBusy(false)
          if (result.ok) {
            if (result.value?.id && result.value.id !== id) remapQueueId(id, result.value.id)
            undoToast({undoId, message: 'Renamed'})
            closeDialog()
          }
        }}
      >
        <Field label="File name" htmlFor="rn" help="Only the file is renamed. The title in the tags stays the same." error={name && bad ? 'File names can’t be empty or contain \\ / : * ? " < > |.' : undefined}>
          <div className="name-edit">
            <input id="rn" className={`input grow${name && bad ? ' invalid' : ''}`} autoFocus value={name} onChange={(event) => setName(event.target.value)} />
            {ext && <span className="name-ext">{ext}</span>}
          </div>
        </Field>
      </form>
    </Dialog>
  )
}

function DeleteFilesDialog({ids}: {ids: string[]}) {
  // The list is fixed when the dialog opens: items leave the library while they are deleted.
  const [items] = useState(() => ids.map(getLibItem).filter((item): item is LibraryItem => !!item))
  const [busy, setBusy] = useState(false)
  // Delete for good: nothing waits in the Keepfin trash or the Recycle Bin (Undo still works while its notice shows)
  const [forGood, setForGood] = useState(false)
  const operation = useLibrary().snapshot.operation
  const progress = busy && operation?.kind === 'delete' ? operation : undefined
  const size = items.reduce((sum, item) => sum + item.size, 0)
  const close = () => {
    if (!busy) closeDialog()
  }
  return (
    <Dialog
      title={items.length === 1 ? 'Delete file from disk?' : `Delete ${items.length} files from disk?`}
      labelledBy="delete-files-title"
      onClose={close}
      footer={
        <>
          <Button variant="ghost" onClick={close} disabled={busy} autoFocus>
            Keep {items.length === 1 ? 'file' : 'files'}
          </Button>
          <Button
            variant="danger"
            icon="trash"
            loading={busy}
            disabled={busy}
            onClick={async () => {
              setBusy(true)
              const undoId = newUndoId()
              const result = await libraryAct<LibraryDeleteResult>('libraryDeleteFile', items.map((item) => item.id), {undoId, ...(forGood ? {forGood: true} : {})})
              setBusy(false)
              if (result.ok) {
                const done = result.value?.deleted?.length ?? items.length
                const failed = result.value?.failed ?? []
                const undoable = result.value?.undoable?.length ?? 0
                // Files in the Keepfin folder wait in its trash and Undo brings them back; the others went to the Recycle Bin (or, for good, nowhere).
                const bin = done - undoable
                const what = done === 1 ? `“${items[0]?.title ?? 'the file'}”` : `${done} files`
                const where = forGood
                  ? bin === 0 ? ' for good. Undo works until this notice closes.' : undoable === 0 ? ' for good.' : ` for good. Undo works until this notice closes for ${plural(undoable, 'file')} in your Keepfin folder.`
                  : bin === 0 ? '' : undoable === 0 ? '. It went to the Recycle Bin.' : `. ${plural(bin, 'file')} went to the Recycle Bin and cannot be brought back here.`
                const message = failed.length
                  ? `Deleted ${plural(done, 'file')}. ${plural(failed.length, 'file')} couldn’t be deleted and stayed. ${failed[0].message}${where.replace(/^\./, '')}`
                  : `Deleted ${what}${where}`
                if (undoable) undoToast({undoId, message, tone: failed.length ? 'error' : 'success', eraseWhenGone: forGood})
                else toast({tone: failed.length ? 'error' : 'success', message})
                closeDialog()
              }
            }}
          >
            Delete {items.length === 1 ? 'file' : `${items.length} files`}
          </Button>
        </>
      }
    >
      <ul className="delete-list">
        {items.slice(0, 5).map((item) => (
          <li key={item.id}>
            <span className="delete-name">{fileName(item.path)}</span>
            <span className="muted small tabular">{formatBytes(item.size)}</span>
          </li>
        ))}
      </ul>
      {items.length > 5 && <p className="muted small">and {plural(items.length - 5, 'more file')} · {formatBytes(size)} in total</p>}
      {busy ? (
        <div className="delete-progress" role="status">
          <ProgressBar value={progress && progress.total ? (progress.done / progress.total) * 100 : 0} label="Deleting files" />
          <span className="muted small tabular">
            Deleting {progress ? `${progress.done} of ${progress.total}` : '…'}
            {progress?.current ? ` · ${progress.current}` : ''}
          </span>
        </div>
      ) : (
        <>
          <p>
            {forGood
              ? `${items.length === 1 ? 'The file is' : 'The files are'} erased from this PC: no Keepfin trash and no Recycle Bin. For files in your Keepfin folder, Undo works until its notice closes. Playlists and history keep their entries, without the file.`
              : `${items.length === 1 ? 'A file' : 'Files'} in your Keepfin folder ${items.length === 1 ? 'goes' : 'go'} to the Keepfin trash for 30 days, and Undo can bring ${items.length === 1 ? 'it' : 'them'} back right after. Any other file goes to the Recycle Bin. Playlists and history keep their entries, without the file.`}
          </p>
          <label className="delete-for-good">
            <input type="checkbox" checked={forGood} onChange={(event) => setForGood(event.target.checked)} />
            <span>
              <strong>Delete for good</strong>
              <span className="muted small">Don’t keep {items.length === 1 ? 'it' : 'them'} in the Keepfin trash or the Recycle Bin</span>
            </span>
          </label>
        </>
      )}
    </Dialog>
  )
}

// ---------------------------------------------------------------------------
// Add files
// ---------------------------------------------------------------------------

function ImportDialog({space, paths}: {space: string; paths?: string[]}) {
  const settings = useLibrarySettings()
  const target = settings?.spaces.find((entry) => entry.id === space)
  const [mode, setMode] = useState<'copy' | 'move'>('copy')
  const [pick, setPick] = useState<'files' | 'folder'>('files')
  const [busy, setBusy] = useState(false)
  async function run(pick: 'files' | 'folder') {
    setBusy(true)
    const result = await libraryAct<LibraryImportResult | null>('libraryImport', undefined, {space, importMode: mode, ...(paths ? {paths} : {pick})})
    setBusy(false)
    if (!result.ok) return
    const value = result.value
    if (!value || value.cancelled) return
    closeDialog()
    const parts = [
      value.added.length ? `Added ${plural(value.added.length, 'file')} to ${target?.name}.` : '',
      value.duplicates.length ? `${plural(value.duplicates.length, 'file')} already in this space were skipped.` : '',
      value.failed.length ? `${plural(value.failed.length, 'file')} couldn’t be added.` : '',
    ].filter(Boolean)
    toast({tone: value.failed.length ? 'error' : value.added.length ? 'success' : 'info', message: parts.join(' ') || 'No new files were added.'})
  }
  return (
    <Dialog
      title={`Add files to ${target?.name || 'this space'}`}
      eyebrow={paths ? plural(paths.length, 'file') + ' dropped' : undefined}
      labelledBy="import-title"
      onClose={closeDialog}
      footer={
        <>
          <Button variant="ghost" onClick={closeDialog}>
            Cancel
          </Button>
          {!paths && (
            <Button
              icon="folderPlus"
              loading={busy && pick === 'folder'}
              onClick={() => {
                setPick('folder')
                void run('folder')
              }}
            >
              Choose a folder…
            </Button>
          )}
          <Button
            variant="primary"
            icon={paths ? 'plus' : 'folder'}
            loading={busy && pick === 'files'}
            onClick={() => {
              setPick('files')
              void run('files')
            }}
          >
            {paths ? 'Add' : 'Choose files…'}
          </Button>
        </>
      }
    >
      <div className="pick-list" role="radiogroup" aria-label="How to add">
        <button type="button" role="radio" aria-checked={mode === 'copy'} className={mode === 'copy' ? 'checked' : undefined} onClick={() => setMode('copy')}>
          <Icon name="copy" size={17} />
          <span className="pick-text">
            <strong>Copy into the space</strong>
            <span>The originals stay where they are. Recommended.</span>
          </span>
          {mode === 'copy' && <Icon name="check" size={16} />}
        </button>
        <button type="button" role="radio" aria-checked={mode === 'move'} className={mode === 'move' ? 'checked' : undefined} onClick={() => setMode('move')}>
          <Icon name="move" size={17} />
          <span className="pick-text">
            <strong>Move into the space</strong>
            <span>The originals are removed from their current folder.</span>
          </span>
          {mode === 'move' && <Icon name="check" size={16} />}
        </button>
      </div>
      {target && <p className="muted small" title={target.folder}>Saved in {target.folder}</p>}
    </Dialog>
  )
}

// ---------------------------------------------------------------------------
// Space editor
// ---------------------------------------------------------------------------

const layoutOptions: {value: FolderLayout; label: string; music?: boolean}[] = [
  {value: 'collection', label: 'A folder for each playlist or album'},
  {value: 'flat', label: 'Everything in one folder'},
  {value: 'platform', label: 'A folder for each site'},
  {value: 'artist-album', label: 'Artist / Album folders', music: true},
]
const namingOptions: {value: SpaceNaming; label: string; music?: boolean}[] = [
  {value: 'title', label: 'Title'},
  {value: 'title-uploader', label: 'Title – Creator'},
  {value: 'uploader-title', label: 'Creator – Title'},
  {value: 'date-title', label: 'Date – Title'},
  {value: 'artist-title', label: 'Artist – Title', music: true},
]
const kindHelp: Record<SpaceKind, string> = {
  music: 'Songs, albums, artists and playlists, with the music player.',
  video: 'A poster wall with Continue watching and the video player.',
  mixed: 'Everything in the folder, organised by folder.',
}

/** A radio grid of swatches or icons with arrow-key movement. */
function ChoiceGrid<T extends string>({label, value, options, onChange, className}: {label: string; value: T; options: {value: T; label: string; node: ReactNode; className?: string}[]; onChange: (value: T) => void; className: string}) {
  return (
    <div className={className} role="radiogroup" aria-label={label}>
      {options.map((option, index) => (
        <button
          key={option.value}
          type="button"
          role="radio"
          aria-checked={option.value === value}
          aria-label={option.label}
          title={option.label}
          tabIndex={option.value === value ? 0 : -1}
          className={`choice ${option.className ?? ''}${option.value === value ? ' checked' : ''}`}
          onClick={() => onChange(option.value)}
          onKeyDown={(event) => {
            const step = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 0
            if (!step) return
            event.preventDefault()
            const group = event.currentTarget.parentElement
            const next = options[(index + step + options.length) % options.length]
            onChange(next.value)
            requestAnimationFrame(() => (group?.querySelectorAll<HTMLElement>('[role=radio]')[options.indexOf(next)])?.focus())
          }}
        >
          {option.node}
        </button>
      ))}
    </div>
  )
}

export function SpaceEditorDialog({space, draft: seed, section}: {space?: Space; draft?: Partial<Space>; section?: 'rules'}) {
  const settings = useLibrarySettings()
  const adding = !space
  const customCount = (settings?.spaces || []).filter((entry) => !isKeptSpace(entry.id)).length
  const [draft, setDraft] = useState<Space>(
    () =>
      space ?? {
        id: crypto.randomUUID(),
        name: '',
        kind: 'mixed',
        folder: '',
        layout: 'collection',
        naming: 'title',
        skipExisting: false,
        type: 'folder',
        color: spaceColorOf({id: 'new'}, customCount),
        pinned: (settings?.spaces || []).filter(isPinned).length < HOME_SPACE_LIMIT,
        ...seed,
      },
  )
  const smart = draft.type === 'smart'
  const rules: SmartRules = draft.rules ?? {match: 'any', rules: []}
  const [more, setMore] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [ignore, setIgnore] = useState((space?.ignore || []).join('\n'))
  const taken = (settings?.spaces || []).some((entry) => entry.id !== draft.id && entry.name.trim().toLowerCase() === draft.name.trim().toLowerCase())
  const music = draft.kind === 'music'
  const root = useKeepfinRoot()
  const [bringing, setBringing] = useState(false)
  // a new space lives in the Keepfin folder unless another folder is chosen (then it is linked)
  const suggested = adding && root && draft.name.trim() ? defaultSpaceFolder(root, {id: draft.id, name: draft.name.trim()}) : ''
  const folder = draft.folder || suggested
  const homeFolder = root && space && !smart ? defaultSpaceFolder(root, space) : ''
  const canSave = !!draft.name.trim() && !taken && (smart ? cleanRules(rules).rules.length > 0 : !!folder)
  async function save() {
    setBusy(true)
    setError('')
    const ignored = ignore.split('\n').map((line) => line.trim().replace(/^\/+|\/+$/g, '')).filter(Boolean)
    const layout = !music && draft.layout === 'artist-album' ? 'collection' : draft.layout
    const naming = !music && draft.naming === 'artist-title' ? 'title' : draft.naming
    const def: Space = smart
      ? {...draft, name: draft.name.trim(), kind: 'mixed', folder: '', layout: 'flat', naming: 'title', skipExisting: false, rules: cleanRules(rules), ignore: undefined}
      : {...draft, name: draft.name.trim(), folder, layout, naming, ignore: ignored.length ? ignored : undefined}
    const result = await libraryAct('spaceSave', undefined, {spaceDef: def})
    setBusy(false)
    if (result.ok) {
      toast({tone: 'success', message: adding ? `Created the space ${draft.name.trim()}.` : `Saved ${draft.name.trim()}.`})
      closeDialog()
    }
  }
  return (
    <Dialog
      title={adding ? 'New space' : `Edit ${space!.name}`}
      eyebrow={!adding ? (smart ? 'Smart space' : 'Folder space') : undefined}
      labelledBy="space-title"
      className="dialog-wide"
      onClose={closeDialog}
      footer={
        <>
          <Button variant="ghost" onClick={closeDialog}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" form="space-form" loading={busy} disabled={!canSave}>
            {adding ? 'Create space' : 'Save'}
          </Button>
        </>
      }
    >
      <form
        id="space-form"
        className="form-grid"
        onSubmit={(event) => {
          event.preventDefault()
          if (canSave) void save()
        }}
      >
        <Field label="Name" htmlFor="sp-name" error={taken ? 'You already have a space with this name.' : undefined}>
          <input id="sp-name" className={`input${taken ? ' invalid' : ''}`} autoFocus maxLength={40} placeholder="Watch later" value={draft.name} onChange={(event) => setDraft({...draft, name: event.target.value})} />
        </Field>
        {adding ? (
          <Field label="Type" help={smart ? 'A smart space stores nothing. It gathers items from every space by rules, and updates by itself.' : 'A folder space stores files and receives downloads.'}>
            <Segmented<'folder' | 'smart'>
              label="Space type"
              value={draft.type}
              onChange={(type) => setDraft({...draft, type, icon: type === 'smart' ? 'star' : undefined, rules: type === 'smart' ? (draft.rules ?? {match: 'any', rules: []}) : undefined})}
              options={[
                {value: 'folder', label: 'Folder'},
                {value: 'smart', label: 'Smart'},
              ]}
            />
          </Field>
        ) : (
          <p className="field-help">{smart ? 'Smart space: gathers items by rules.' : 'Folder space: stores files.'} The type cannot be changed after the space is created.</p>
        )}
        <Field label="Color">
          <ChoiceGrid<SpaceColor>
            className="swatches"
            label="Space color"
            value={spaceColorOf(draft)}
            onChange={(color) => setDraft({...draft, color})}
            options={spaceColors.map((color) => ({value: color, label: colorNames[color], className: `sp-${color}`, node: <span className="swatch">{spaceColorOf(draft) === color && <Icon name="check" size={14} />}</span>}))}
          />
        </Field>
        <Field label="Icon">
          <ChoiceGrid<SpaceIcon>
            className={`icon-grid sp-${spaceColorOf(draft)}`}
            label="Space icon"
            value={spaceIconOf(draft)}
            onChange={(icon) => setDraft({...draft, icon})}
            options={spaceIcons.map((icon) => ({value: icon, label: `${iconNames[icon]} icon`, node: <Icon name={icon} size={18} />}))}
          />
        </Field>
        <div className="switch-line">
          <div>
            <strong>Show on Home</strong>
            <p className="field-help">Pinned spaces appear on Home and in the sidebar.</p>
          </div>
          <Switch label="Show on Home" checked={isPinned(draft)} onChange={(pinned) => setDraft({...draft, pinned})} />
        </div>
        {smart && (
          <section
            aria-label="Rules"
            ref={(node) => {
              if (node && section === 'rules') node.scrollIntoView?.({block: 'nearest'})
            }}
          >
            <h3 className="shelf-title">Rules</h3>
            <SmartRulesEditor rules={rules} onChange={(next) => setDraft({...draft, rules: next})} spaceId={draft.id} spaces={settings?.spaces || []} />
          </section>
        )}
        {!smart && (
          <>
        <Field label="What goes here" help={kindHelp[draft.kind]}>
          <Segmented<SpaceKind>
            label="Space kind"
            value={draft.kind}
            onChange={(kind) => setDraft({...draft, kind, skipExisting: kind === 'music' ? true : draft.skipExisting})}
            options={[
              {value: 'music', label: 'Music'},
              {value: 'video', label: 'Videos'},
              {value: 'mixed', label: 'Mixed'},
            ]}
          />
        </Field>
        <Field
          label="Folder"
          htmlFor="sp-folder"
          help={adding ? 'In your Keepfin folder unless you choose another one. Removing the space never deletes its files.' : 'Keepfin saves and looks for files here. Removing the space never deletes them.'}
        >
          <input
            id="sp-folder"
            className="input path-input"
            spellCheck={false}
            placeholder={suggested || 'A folder in your Keepfin folder'}
            aria-label="Folder"
            value={draft.folder}
            onChange={(event) => setDraft({...draft, folder: event.target.value})}
          />
          <Button
            size="sm"
            icon="folder"
            onClick={async () => {
              const result = await libraryAct<{path: string} | null>('chooseFolder', undefined, {space: draft.id || 'new', text: draft.folder})
              if (result.ok && result.value) setDraft({...draft, folder: result.value.path})
            }}
          >
            Choose…
          </Button>
          {homeFolder && space && !(isInFolder(homeFolder, space.folder) && isInFolder(space.folder, homeFolder)) && (
            <Button size="sm" onClick={() => setBringing(true)}>
              Use the default folder
            </Button>
          )}
        </Field>
        {bringing && space && root && <BringInDialog spaces={[space]} root={root} onClose={() => (setBringing(false), closeDialog())} />}
        <button type="button" className="disclosure" aria-expanded={more} onClick={() => setMore(!more)}>
          <Icon name="chevron" size={15} />
          More options
        </button>
        {more && (
          <div className="more-options">
            <Field label="Subfolders" help="How downloads are arranged inside the folder.">
              <Select<FolderLayout> label="Subfolders" value={draft.layout} onChange={(layout) => setDraft({...draft, layout})} options={layoutOptions.filter((option) => !option.music || music)} />
            </Field>
            <Field label="File names" help="Names stay readable and never overwrite an existing file.">
              <Select<SpaceNaming> label="File names" value={draft.naming} onChange={(naming) => setDraft({...draft, naming})} options={namingOptions.filter((option) => !option.music || music)} />
            </Field>
            <div className="switch-line">
              <div>
                <strong>Skip {music ? 'songs' : 'items'} already in this space</strong>
                <p className="field-help">Checked before downloading. Matches are flagged, never skipped silently.</p>
              </div>
              <Switch label={`Skip ${music ? 'songs' : 'items'} already in this space`} checked={draft.skipExisting} onChange={(skipExisting) => setDraft({...draft, skipExisting})} />
            </div>
            <div className="switch-line">
              <div>
                <strong>Show other files in this folder</strong>
                <p className="field-help">Also lists files already in the folder that Keepfin did not download. Good for a music folder you already keep.</p>
              </div>
              <Switch label="Show other files in this folder" checked={!!draft.scanFolder} onChange={(scanFolder) => setDraft({...draft, scanFolder})} />
            </div>
            <Field label="Ignored folders" htmlFor="sp-ignore" help="One subfolder per line, such as Samples. Folders with a .nomedia file are always ignored.">
              <textarea id="sp-ignore" className="input" rows={3} spellCheck={false} value={ignore} onChange={(event) => setIgnore(event.target.value)} />
            </Field>
          </div>
        )}
          </>
        )}
        {error && <InlineAlert>{error}</InlineAlert>}
      </form>
    </Dialog>
  )
}

function RemoveSpaceDialog({space}: {space: Space}) {
  const settings = useLibrarySettings()
  const [busy, setBusy] = useState(false)
  const items = getLibrary().bySpace.get(space.id)?.length || 0
  return (
    <Dialog
      title={`Remove ${space.name}?`}
      labelledBy="remove-space-title"
      onClose={closeDialog}
      footer={
        <>
          <Button variant="ghost" onClick={closeDialog} autoFocus>
            Keep space
          </Button>
          <Button
            variant="danger"
            loading={busy}
            onClick={async () => {
              setBusy(true)
              const undoId = newUndoId()
              const result = await libraryAct('spaceDelete', undefined, {id: space.id, undoId})
              setBusy(false)
              if (result.ok) {
                undoToast({undoId, message: space.type === 'smart' ? `Removed the space “${space.name}”.` : `Removed the space “${space.name}”. Its files stay where they are.`})
                closeDialog()
              }
            }}
          >
            Remove space
          </Button>
        </>
      }
    >
      {space.type === 'smart' && <p>Nothing is deleted. A smart space only gathers items from your other spaces.</p>}
      {space.type !== 'smart' && <p>
        Your files stay exactly where they are{items ? ` (${plural(items, 'item')} in ${space.folder})` : ''}. They only leave Keepfin’s library until you add the folder as a space again.
      </p>}
      {settings && (settings.routes.music === space.id || settings.routes.other === space.id) && (
        <p>Downloads that were going here will use your first remaining space instead.</p>
      )}
    </Dialog>
  )
}

// ---------------------------------------------------------------------------
// Host
// ---------------------------------------------------------------------------

export function LibraryDialogs() {
  const dialog = useDialog()
  if (!dialog) return null
  return <DialogSwitch dialog={dialog} />
}
function DialogSwitch({dialog}: {dialog: LibraryDialog}) {
  switch (dialog.type) {
    case 'move':
      return <MoveDialog ids={dialog.ids} mode={dialog.mode} collection={dialog.collection} />
    case 'playlistAdd':
      return <PlaylistAddDialog ids={dialog.ids} />
    case 'playlistName':
      return <PlaylistNameDialog id={dialog.id} ids={dialog.ids} onMade={dialog.onMade} />
    case 'playlistDelete':
      return <PlaylistDeleteDialog id={dialog.id} />
    case 'editInfo':
      return <EditInfoDialog id={dialog.id} />
    case 'details':
      return <ItemDetailsDialog id={dialog.id} />
    case 'rename':
      return <RenameDialog id={dialog.id} />
    case 'musicVideo':
      return <MusicVideoDialog id={dialog.id} />
    case 'removeMusicVideo':
      return <RemoveMusicVideoDialog id={dialog.id} />
    case 'deleteFiles':
      return <DeleteFilesDialog ids={dialog.ids} />
    case 'import':
      return <ImportDialog space={dialog.space} paths={dialog.paths} />
    case 'space':
      return <SpaceEditorDialog space={dialog.space} draft={dialog.draft} section={dialog.section} />
    case 'addFrom':
      return <AddFromDialog space={dialog.space} />
    case 'addLink':
      return <AddLinkDialog space={dialog.space} />
    case 'tags':
      return <TagsDialog ids={dialog.ids} />
    case 'follow':
      return <FollowDialog id={dialog.id} />
    case 'removeSpace':
      return <RemoveSpaceDialog space={dialog.space} />
  }
}
