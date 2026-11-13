import {useEffect, useState, type DragEvent} from 'react'
import {toast} from '../toasts'
import {native} from '../bridge'
import {pruneQueue, restorePlayer} from '../player/playerStore'
import {Button, EmptyState, ProgressBar} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {openDialog} from './dialogState'
import {LibraryDetail} from './LibraryDetail'
import {Overview} from './Overview'
import {setLibView, useLibView} from './libNav'
import {libraryAct, loadLibrary, useLibrary, useLibraryEnabled, useLibrarySettings} from './libStore'
import {MusicVideosPage} from './musicVideos/MusicVideosPage'
import {SmartView} from './SmartView'
import {SmartSpaceView} from './SmartSpaceView'
import {AllSpaces} from './SpacesHub'
import {SpaceView} from './SpaceView'
import {TrashView} from './TrashView'

const hasFiles = (event: DragEvent) => [...(event.dataTransfer?.types || [])].includes('Files')
function pathsOf(event: DragEvent): string[] {
  const bridge = window.keepfin?.pathForFile
  if (!bridge) return []
  const paths: string[] = []
  for (const file of Array.from(event.dataTransfer.files)) {
    try {
      const path = bridge(file)
      if (path) paths.push(path)
    } catch {
      /* A file the bridge can't resolve is skipped. */
    }
  }
  return paths
}

const opWords = {move: 'Moving files', copy: 'Copying files', import: 'Adding files', edit: 'Saving tags', delete: 'Deleting files'} as const
function OperationBar() {
  const {snapshot} = useLibrary()
  const op = snapshot.operation
  if (!op) return null
  return (
    <div className="op-bar" role="status">
      <Icon name="refresh" size={15} className="spin" />
      <span className="op-text">
        {opWords[op.kind]} <span className="tabular">{op.done} of {op.total}</span>
        {op.current ? ` · ${op.current}` : ''}
      </span>
      <ProgressBar value={op.total ? (op.done / op.total) * 100 : 0} label={opWords[op.kind]} />
      <Button size="sm" variant="ghost" onClick={() => void libraryAct('libraryCancel')}>
        Cancel
      </Button>
    </div>
  )
}

/** Loads the library once and keeps the player in step with it. Mounted by App whenever the library is on. */
export function useLibraryBoot(enabled: boolean) {
  const lib = useLibrary()
  useEffect(() => {
    if (enabled) void loadLibrary()
  }, [enabled])
  useEffect(() => {
    if (!enabled || !lib.loaded) return
    restorePlayer()
    pruneQueue()
  }, [enabled, lib.loaded, lib.snapshot.items])
}

export function Library() {
  const enabled = useLibraryEnabled()
  // Downloader-only mode never shows this tab; the guard keeps a stale route from rendering library surfaces.
  if (!enabled) return null
  return <LibraryTab />
}

function LibraryTab() {
  const settings = useLibrarySettings()!
  const view = useLibView()
  const spaces = settings.spaces
  const selectedSpace = view.type === 'space' ? spaces.find((space) => space.id === view.id) : undefined
  const selectedFolder = selectedSpace && selectedSpace.type !== 'smart' ? selectedSpace : undefined
  // files are dropped into a space that holds files, not into Music on this PC
  const dropFolder = selectedFolder?.deviceOnly ? undefined : selectedFolder
  // A removed space sends the user home instead of leaving a blank pane.
  useEffect(() => {
    if (view.type === 'space' && !selectedSpace) setLibView({type: 'home'})
  }, [view, selectedSpace])
  const [dropping, setDropping] = useState(false)

  return (
    <div className="screen library lib-shell">
      <div
        className={`lib-main${dropping ? ' is-dropping' : ''}`}
        role="region"
        aria-label="Library content"
        onDragEnter={(event) => dropFolder && hasFiles(event) && (event.preventDefault(), setDropping(true))}
        onDragOver={(event) => dropFolder && hasFiles(event) && (event.preventDefault(), (event.dataTransfer.dropEffect = 'copy'))}
        onDragLeave={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget as Node)) setDropping(false)
        }}
        onDrop={(event) => {
          setDropping(false)
          if (!dropFolder || !hasFiles(event)) return
          event.preventDefault()
          const paths = pathsOf(event)
          if (paths.length) openDialog({type: 'import', space: dropFolder.id, paths})
          else toast({tone: 'info', message: 'Keepfin couldn’t read the dropped files. Use Add files instead.'})
        }}
      >
        {dropping && dropFolder && (
          <div className="drop-hint" aria-hidden="true">
            <Icon name="plus" size={22} />
            <strong>Drop to add to {dropFolder.name}</strong>
          </div>
        )}
        <OperationBar />
        {view.type === 'home' && <Overview />}
        {view.type === 'space' && selectedFolder && <SpaceView key={selectedFolder.id} space={selectedFolder} />}
        {view.type === 'space' && selectedSpace?.type === 'smart' && <SmartSpaceView key={selectedSpace.id} space={selectedSpace} />}
        {view.type === 'spaces' && <AllSpaces />}
        {view.type === 'trash' && <TrashView />}
        {view.type === 'musicVideos' && <MusicVideosPage />}
        {view.type === 'smart' && <SmartView id={view.id} />}
        {(view.type === 'album' || view.type === 'artist' || view.type === 'playlist' || view.type === 'collection') && <LibraryDetail view={view} />}
        {view.type === 'space' && !selectedSpace && (
          <EmptyState icon="folder" title="Space not found">
            <Button onClick={() => setLibView({type: 'home'})}>Go to Home</Button>
          </EmptyState>
        )}
      </div>
    </div>
  )
}
