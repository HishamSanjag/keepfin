import {useEffect, useState} from 'react'
import {musicVideoWords, type MusicVideoFound} from '../../shared/library'
import {formatDuration} from '../format'
import {toast} from '../toasts'
import {Button, InlineAlert, Spinner} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import {closeDialog} from './dialogState'
import {getLibItem, libraryAct, libraryRequest} from './libStore'

/** "3.2B views", "840K views": a short count for the card. */
export function viewsLabel(views: number): string {
  const short = (value: number, unit: string) => `${value >= 10 ? Math.round(value) : Math.round(value * 10) / 10}${unit} views`
  if (views >= 1e9) return short(views / 1e9, 'B')
  if (views >= 1e6) return short(views / 1e6, 'M')
  if (views >= 1e3) return short(views / 1e3, 'K')
  return `${views} ${views === 1 ? 'view' : 'views'}`
}

type Look = {state: 'looking'} | {state: 'found'; video: Extract<MusicVideoFound, {videoId: string}>} | {state: 'none'} | {state: 'error'; message: string}

/** Get the music video…: finds the song's official video, shows what was found, and downloads it beside the song on Download. */
export function MusicVideoDialog({id}: {id: string}) {
  const item = getLibItem(id)
  const [look, setLook] = useState<Look>({state: 'looking'})
  const [attempt, setAttempt] = useState(0)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    let current = true
    setLook({state: 'looking'})
    libraryRequest<MusicVideoFound>('libraryFindMusicVideo', id).then(
      (found) => current && setLook('none' in found ? {state: 'none'} : {state: 'found', video: found}),
      (error: unknown) => current && setLook({state: 'error', message: error instanceof Error ? error.message : 'Keepfin could not look for the music video.'}),
    )
    return () => {
      current = false
    }
  }, [id, attempt])
  if (!item) return null
  const title = item.title
  return (
    <Dialog
      title="Get the music video"
      eyebrow={title}
      labelledBy="music-video-title"
      onClose={closeDialog}
      footer={
        look.state === 'found' ? (
          <>
            <Button variant="ghost" onClick={closeDialog} disabled={busy}>
              Cancel
            </Button>
            <Button
              variant="primary"
              icon="download"
              loading={busy}
              onClick={async () => {
                setBusy(true)
                const result = await libraryAct('libraryGetMusicVideo', id, {videoId: look.video.videoId})
                setBusy(false)
                if (result.ok) {
                  toast({tone: 'success', message: `Downloading the music video for “${title}”`})
                  closeDialog()
                }
              }}
            >
              Download
            </Button>
          </>
        ) : look.state === 'error' ? (
          <>
            <Button variant="ghost" onClick={closeDialog}>
              Close
            </Button>
            <Button variant="primary" onClick={() => setAttempt((n) => n + 1)}>
              Try again
            </Button>
          </>
        ) : (
          <Button variant={look.state === 'none' ? 'primary' : 'ghost'} onClick={closeDialog}>
            {look.state === 'none' ? 'Close' : 'Cancel'}
          </Button>
        )
      }
    >
      {look.state === 'looking' && (
        <p className="mv-looking" role="status">
          <Spinner /> Looking for the official music video…
        </p>
      )}
      {look.state === 'found' && (
        <div className="mv-card">
          <strong className="mv-title">{look.video.title}</strong>
          <span className="mv-meta">
            {[look.video.channel, look.video.views != null ? viewsLabel(look.video.views) : undefined, look.video.durationSec ? formatDuration(look.video.durationSec) : undefined].filter(Boolean).join(' · ')}
          </span>
          <span className="field-help">It is saved next to the song, and the player can switch between the two.</span>
        </div>
      )}
      {look.state === 'none' && <p>No official music video was found for “{title}”.</p>}
      {look.state === 'error' && <InlineAlert>{look.message}</InlineAlert>}
    </Dialog>
  )
}

/** Remove music video…: only the video file goes (to the Recycle Bin); the song stays. */
export function RemoveMusicVideoDialog({id}: {id: string}) {
  const item = getLibItem(id)
  const [busy, setBusy] = useState(false)
  if (!item) return null
  return (
    <Dialog
      title={musicVideoWords.deleteAsk}
      eyebrow={item.title}
      labelledBy="remove-music-video-title"
      onClose={busy ? () => undefined : closeDialog}
      footer={
        <>
          <Button variant="ghost" onClick={closeDialog} disabled={busy} autoFocus>
            Keep it
          </Button>
          <Button
            variant="danger"
            icon="trash"
            loading={busy}
            onClick={async () => {
              setBusy(true)
              const result = await libraryAct('libraryRemoveMusicVideo', id)
              setBusy(false)
              if (result.ok) {
                toast({tone: 'success', message: `Removed the music video for “${item.title}”. It went to the Recycle Bin.`})
                closeDialog()
              }
            }}
          >
            Remove video
          </Button>
        </>
      }
    >
      <p>The video file goes to the Recycle Bin. The song stays in your library.</p>
    </Dialog>
  )
}
