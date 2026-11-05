// The lyrics and queue panels every full-player style (and the Now playing side panel) shares.
import {useEffect, useRef, useState, useSyncExternalStore} from 'react'
import {untimedLyricsWords} from '../../../shared/core/lyrics'
import type {Lyrics} from '../../../shared/library'
import {messageOf} from '../../bridge'
import {formatDuration} from '../../format'
import {Art} from '../../library/Art'
import {findLyrics, findLyricsWords} from '../../library/findLyrics'
import {getLibItem, libraryRequest, useLibItem} from '../../library/libStore'
import {motion, prefersReducedMotion, useSpring} from '../../motion/spring'
import {Button, IconButton, Spinner} from '../../ui/controls'
import {Icon} from '../../ui/Icon'
import {clearQueue, jumpTo, moveInQueue, removeFromQueue, seekTo, usePlayer, useTime} from '../playerStore'

type LyricState = {id: string; lyrics?: Lyrics | null; error?: string}

// The lyrics panel, the current line and the "has lyrics?" check all ask for the same song at once: they share one request.
const recent = new Map<string, {at: number; promise: Promise<Lyrics | null>}>()
function fetchLyrics(id: string): Promise<Lyrics | null> {
  const known = recent.get(id)
  if (known && Date.now() - known.at < 3000) return known.promise
  const promise = libraryRequest<Lyrics | null>('libraryLyrics', id).then((lyrics) => (lyrics && Array.isArray(lyrics.lines) ? lyrics : null))
  recent.set(id, {at: Date.now(), promise})
  if (recent.size > 20) recent.delete(recent.keys().next().value as string)
  return promise
}

const changeListeners = new Set<(id: string) => void>()
/** The song's lyrics were just added (Find lyrics): every panel showing it looks again. */
export function lyricsChanged(id: string) {
  recent.delete(id)
  changeListeners.forEach((listener) => listener(id))
}

/** The song's lyrics, fetched once per song. `lyrics` is undefined while loading, null when there are none. */
function useLyrics(id: string): LyricState {
  const [state, setState] = useState<LyricState>({id: ''})
  const [again, setAgain] = useState(0)
  useEffect(() => {
    const listener = (changed: string) => changed === id && setAgain((count) => count + 1)
    changeListeners.add(listener)
    return () => void changeListeners.delete(listener)
  }, [id])
  useEffect(() => {
    let alive = true
    setState({id})
    fetchLyrics(id)
      .then((lyrics) => alive && setState({id, lyrics}))
      .catch((error) => alive && setState({id, error: messageOf(error)}))
    return () => {
      alive = false
    }
  }, [id, again])
  return state
}

/** "Find lyrics" in the panel of a song that has none; says what the search came to. */
function FindLyrics({id}: {id: string}) {
  const [state, setState] = useState<'idle' | 'looking' | string>('idle')
  const looking = state === 'looking'
  return (
    <>
      <Button size="sm" icon="search" loading={looking} disabled={looking} onClick={async () => {
        setState('looking')
        const found = await findLyrics(id)
        setState(found && found.outcome !== 'found' ? found.message : 'idle')
      }}>
        {findLyricsWords}
      </Button>
      {state !== 'idle' && !looking && <span role="status">{state}</span>}
    </>
  )
}

/** True or false once known, undefined while the lyrics are still being looked up. */
function useHasLyrics(id: string): boolean | undefined {
  const state = useLyrics(id)
  if (state.id !== id || (state.lyrics === undefined && !state.error)) return undefined
  return !!state.lyrics && state.lyrics.lines.length > 0
}

export type Side = 'lyrics' | 'queue'
// What the person picked this session (Lyrics or Queue), kept across songs and styles. Until they pick, a song without lyrics shows the queue.
let picked: Side | undefined
const pickListeners = new Set<() => void>()
export function pickSide(side: Side) {
  picked = side
  pickListeners.forEach((listener) => listener())
}
/** Which of Lyrics and Queue a style shows for this song: the person's pick, else `first`, else the queue when the song has no lyrics. */
export function useSide(id: string, first: Side): Side {
  const has = useHasLyrics(id)
  const choice = useSyncExternalStore(
    (listener) => {
      pickListeners.add(listener)
      return () => {
        pickListeners.delete(listener)
      }
    },
    () => picked,
  )
  return choice ?? (has === false ? 'queue' : first)
}
/** The index of the synced line being sung at `position`, or -1. */
function activeLine(lyrics: Lyrics | null | undefined, position: number): number {
  let active = -1
  if (lyrics?.synced) {
    for (let i = 0; i < lyrics.lines.length; i++) {
      if ((lyrics.lines[i].time ?? Infinity) <= position + 0.15) active = i
    }
  }
  return active
}

/** The line being sung now (empty between lines and for songs without synced lyrics): Cinema paints it over the picture. */
export function CurrentLyric({id}: {id: string}) {
  const state = useLyrics(id)
  const {position} = useTime()
  const lyrics = state.id === id ? state.lyrics : undefined
  const line = lyrics?.synced ? lyrics.lines[activeLine(lyrics, position)]?.text : ''
  if (!line) return null
  return (
    <p className="cinema-line" aria-hidden="true">
      <span className="cinema-note">♪</span> {line}
    </p>
  )
}

export function LyricsPanel({id}: {id: string}) {
  const state = useLyrics(id)
  const {position} = useTime()
  const box = useRef<HTMLDivElement>(null)
  const item = useLibItem(id)
  const userPauseUntil = useRef<number>(0)

  const spring = useSpring(0, motion.smooth, (v) => {
    if (box.current) box.current.scrollTop = v
  })

  const lyrics = state.id === id ? state.lyrics : undefined
  const active = activeLine(lyrics, position)

  useEffect(() => {
    if (active < 0 || !box.current) return
    if (Date.now() < userPauseUntil.current) return
    const line = box.current.querySelector<HTMLElement>(`[data-line="${active}"]`)
    if (!line) return
    const maxScroll = box.current.scrollHeight - box.current.clientHeight
    const rawTarget = line.offsetTop - box.current.clientHeight / 2 + line.offsetHeight / 2
    const target = Math.max(0, Math.min(maxScroll, rawTarget))
    if (prefersReducedMotion()) {
      box.current.scrollTop = target
      spring.set(target)
    } else {
      spring.set(box.current.scrollTop)
      spring.to(target)
    }
  }, [active, spring])

  const handleUserScroll = () => {
    if (!box.current) return
    userPauseUntil.current = Date.now() + 4000
    spring.set(box.current.scrollTop)
  }

  if (state.id !== id || (lyrics === undefined && !state.error))
    return (
      <div className="panel-note" aria-busy="true">
        <Spinner size={16} /> Looking for lyrics…
      </div>
    )
  if (state.error) return <div className="panel-note" role="alert">Couldn’t load lyrics. {state.error}</div>
  if (!lyrics || !lyrics.lines.length)
    return (
      <div className="panel-note">
        <Icon name="lyrics" size={22} />
        <strong>No lyrics for this song</strong>
        <span>Keepfin shows lyrics from the file’s tags or a .lrc file with the same name next to it.</span>
        {item?.kind === 'audio' && <FindLyrics id={id} />}
      </div>
    )
  return (
    <div
      className="lyrics"
      ref={box}
      tabIndex={0}
      aria-label={`Lyrics for ${item?.title || 'this song'}${lyrics.synced ? ', synced' : ''}`}
      onWheel={handleUserScroll}
      onPointerDown={handleUserScroll}
    >
      {!lyrics.synced && <p className="panel-note">{untimedLyricsWords()}</p>}
      {lyrics.lines.map((line, index) =>
        lyrics.synced && line.time !== undefined ? (
          <button
            key={index}
            type="button"
            data-line={index}
            className={`lyric${index === active ? ' active' : index < active ? ' past' : ''}`}
            style={{'--d': Math.min(3, Math.abs(index - active))} as React.CSSProperties}
            aria-current={index === active ? 'true' : undefined}
            onClick={() => {
              userPauseUntil.current = 0
              seekTo(line.time!)
            }}
          >
            {line.text || '♪'}
          </button>
        ) : (
          <p key={index} className="lyric plain">
            {line.text || ' '}
          </p>
        ),
      )}
    </div>
  )
}

export function QueuePanel() {
  const queue = usePlayer((s) => s.queue)
  const index = usePlayer((s) => s.index)
  const playing = usePlayer((s) => s.playing)
  const [from, setFrom] = useState<number | null>(null)
  const [over, setOver] = useState<number | null>(null)
  return (
    <div className="queue-panel">
      <div className="queue-head">
        <strong>
          Queue <span className="muted tabular">{queue.length}</span>
        </strong>
        <Button size="sm" variant="ghost" icon="trash" disabled={queue.length < 2} onClick={clearQueue}>
          Clear upcoming
        </Button>
      </div>
      <ol className="queue-items" aria-label="Play queue">
        {queue.map((id, at) => (
          <QueueRow key={`${id}:${at}`} id={id} at={at} current={at === index} playing={playing && at === index} dragging={from === at} dropHere={over === at && from !== null && from !== at} onStart={() => setFrom(at)} onOver={() => setOver(at)} onEnd={() => { setFrom(null); setOver(null) }} onDrop={() => { if (from !== null) moveInQueue(from, at); setFrom(null); setOver(null) }} />
        ))}
      </ol>
    </div>
  )
}

function QueueRow({id, at, current, playing, dragging, dropHere, onStart, onOver, onEnd, onDrop}: {id: string; at: number; current: boolean; playing: boolean; dragging: boolean; dropHere: boolean; onStart: () => void; onOver: () => void; onEnd: () => void; onDrop: () => void}) {
  const item = getLibItem(id)
  if (!item) return null
  return (
    <li
      className={`queue-row${current ? ' is-current' : ''}${dragging ? ' dragging' : ''}${dropHere ? ' drop-here' : ''}`}
      onDragOver={(event) => {
        event.preventDefault()
        onOver()
      }}
      onDrop={(event) => {
        event.preventDefault()
        onDrop()
      }}
      onKeyDown={(event) => {
        if (event.altKey && (event.key === 'ArrowUp' || event.key === 'ArrowDown')) {
          event.preventDefault()
          moveInQueue(at, at + (event.key === 'ArrowUp' ? -1 : 1))
          requestAnimationFrame(() => (event.currentTarget.parentElement?.children[Math.max(0, at + (event.key === 'ArrowUp' ? -1 : 1))]?.querySelector('.q-main') as HTMLElement | null)?.focus())
        }
      }}
    >
      <span className="drag-handle" draggable aria-hidden="true" title="Drag to reorder" onDragStart={(event) => { event.dataTransfer.setData('text/plain', id); event.dataTransfer.effectAllowed = 'move'; onStart() }} onDragEnd={onEnd}>
        <Icon name="drag" size={16} />
      </span>
      <button type="button" className="q-main" aria-label={`${item.title}${item.artist ? `, ${item.artist}` : ''}${current ? ', playing now' : ''}`} onClick={() => jumpTo(at)}>
        {playing ? (
          <span className="eq" aria-hidden="true"><i /><i /><i /></span>
        ) : (
          <Art id={item.id} hasArt={item.hasArt} kind="audio" className="q-art" size={14} />
        )}
        <span className="row-text">
          <span className="row-title">{item.title}</span>
          <span className="row-sub">{item.artist || item.albumArtist}</span>
        </span>
        <span className="row-col row-time tabular">{formatDuration(item.duration)}</span>
      </button>
      <IconButton icon="close" size="sm" label={`Remove ${item.title} from the queue`} onClick={() => removeFromQueue(at)} />
    </li>
  )
}
