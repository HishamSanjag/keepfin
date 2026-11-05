// Bring spaces into the Keepfin folder (dev-docs/plans/KEEPFIN-FOLDER-MOVE.md, D3/D5): Storage's "outside your Keepfin folder" notice
// and Edit space › Use the default folder. Each space is pointed at its default folder, then its files move (or copy) there with
// Move to space, which keeps every item, its playlists, favourite and plays.
import {useState} from 'react'
import {defaultSpaceFolder, isInFolder, type Space} from '../../shared/library'
import {plural} from '../format'
import {getLibrary, libraryRequest} from '../library/libStore'
import {messageOf} from '../bridge'
import {toast} from '../toasts'
import {Button, InlineAlert, Segmented} from '../ui/controls'
import {Dialog} from '../ui/Dialog'

type Choice = 'move' | 'copy' | 'leave'
const words: Record<Choice, string> = {move: 'Move in', copy: 'Copy in', leave: 'Leave'}

/** The folder spaces whose folder is not inside the Keepfin folder at `root`. */
export const outsideSpaces = (spaces: Space[], root: string) => spaces.filter((space) => space.type !== 'smart' && space.folder && !isInFolder(root, space.folder))

export function BringInDialog({spaces, root, onClose}: {spaces: Space[]; root: string; onClose: () => void}) {
  const [choices, setChoices] = useState<Record<string, Choice>>(() => Object.fromEntries(spaces.map((space) => [space.id, 'move'])))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const files = (space: Space) => getLibrary().snapshot.items.filter((item) => item.space === space.id && !isInFolder(defaultSpaceFolder(root, space), item.path))
  async function start() {
    setBusy(true)
    setError('')
    try {
      let done = 0
      const failed: string[] = []
      for (const space of spaces) {
        const choice = choices[space.id]
        const folder = defaultSpaceFolder(root, space)
        // new files go to the default folder whatever the choice; Leave keeps the existing ones where they are
        const items = files(space)
        await libraryRequest('spaceSave', undefined, {spaceDef: {...space, folder}})
        if (choice === 'leave' || !items.length) continue
        const result = await libraryRequest<{moved: string[]; failed: {message: string}[]}>('libraryMove', items.map((item) => item.id), {space: space.id, copy: choice === 'copy'})
        done += result.moved.length
        failed.push(...result.failed.map((entry) => entry.message))
      }
      toast({
        tone: failed.length ? 'error' : 'success',
        message: failed.length
          ? `Brought ${plural(done, 'file')} into your Keepfin folder. ${plural(failed.length, 'file')} stayed where ${failed.length === 1 ? 'it was' : 'they were'}: ${failed[0]}`
          : done
            ? `Brought ${plural(done, 'file')} into your Keepfin folder.`
            : 'New files now go into your Keepfin folder.',
      })
      onClose()
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog
      title={spaces.length === 1 ? `Bring ${spaces[0].name} into your Keepfin folder` : 'Bring these spaces into your Keepfin folder'}
      labelledBy="bring-in-title"
      onClose={() => !busy && onClose()}
      footer={
        <>
          <Button variant="ghost" disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" loading={busy} onClick={() => void start()}>
            Start
          </Button>
        </>
      }
    >
      <p>Each space saves its new files in your Keepfin folder. Choose what happens to the files it already has.</p>
      <ul className="bring-in-list">
        {spaces.map((space) => {
          const count = files(space).length
          return (
            <li key={space.id}>
              <div>
                <strong>{space.name}</strong>
                <span className="muted small">
                  {space.folder} → {defaultSpaceFolder(root, space)}
                  {` · ${plural(count, 'file')}`}
                </span>
              </div>
              <Segmented
                label={`What happens to the files in ${space.name}`}
                size="sm"
                disabled={busy || !count}
                value={choices[space.id]}
                options={(Object.keys(words) as Choice[]).map((value) => ({value, label: words[value]}))}
                onChange={(value) => setChoices((current) => ({...current, [space.id]: value}))}
              />
            </li>
          )
        })}
      </ul>
      <p className="muted small">Move in checks each copy before the original goes to the Recycle Bin. Copy in keeps the originals. Leave keeps them where they are.</p>
      {error && <InlineAlert tone="error">{error}</InlineAlert>}
    </Dialog>
  )
}
