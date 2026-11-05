// On-screen feedback for the video player (D1). It owns its own transient state, so a skip, a volume step or a
// play/pause flash re-renders only this small component, never the whole player.
import {forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState} from 'react'
import {Icon} from '../ui/Icon'
import {PauseGlyph, PlayGlyph} from './PlayerIcons'
import {accumulateSkip, skipAnnouncement, skipLabel, type SkipRun as Skip} from '../../shared/core/playback'

export type HudHandle = {
  /** A skip of `delta` seconds; repeated skips the same way within a second add up. */
  skip: (delta: number) => void
  /** A short note at the top centre ("Volume 45 %"). `hold` keeps it until `release()`. */
  pill: (text: string, announce?: string) => void
  hold: (text: string) => void
  release: () => void
  /** The play or pause icon flashed in the centre. */
  flash: (kind: 'play' | 'pause') => void
}

export const VideoHud = forwardRef<HudHandle>(function VideoHud(_props, ref) {
  const [bubble, setBubble] = useState<{side: 'left' | 'right'; text: string}>()
  const [pill, setPill] = useState<string>()
  const [flash, setFlash] = useState<{kind: 'play' | 'pause'; key: number}>()
  const [live, setLive] = useState('')
  const skipState = useRef<Skip | undefined>(undefined)
  const timers = useRef<Record<string, ReturnType<typeof setTimeout> | undefined>>({})
  const held = useRef(false)
  const flip = useRef(false)

  const after = useCallback((name: string, ms: number, run: () => void) => {
    clearTimeout(timers.current[name])
    timers.current[name] = setTimeout(run, ms)
  }, [])
  // one polite announcement of the final value, a moment after the last change
  const announce = useCallback(
    (text: string) => {
      after('live', 700, () => {
        flip.current = !flip.current
        setLive(flip.current ? text : `${text}​`)
      })
    },
    [after],
  )

  useImperativeHandle(
    ref,
    () => ({
      skip(delta) {
        const next = accumulateSkip(skipState.current, delta, Date.now())
        skipState.current = next
        setBubble({side: next.total < 0 ? 'left' : 'right', text: skipLabel(next.total)})
        after('bubble', 900, () => setBubble(undefined))
        announce(skipAnnouncement(next.total))
      },
      pill(text, announcement) {
        setPill(text)
        if (!held.current) after('pill', 1300, () => setPill(undefined))
        announce(announcement || text)
      },
      hold(text) {
        held.current = true
        clearTimeout(timers.current.pill)
        setPill(text)
        announce(text)
      },
      release() {
        held.current = false
        after('pill', 200, () => setPill(undefined))
      },
      flash(kind) {
        setFlash({kind, key: Date.now()})
        after('flash', 550, () => setFlash(undefined))
      },
    }),
    [after, announce],
  )
  useEffect(() => () => Object.values(timers.current).forEach((timer) => clearTimeout(timer)), [])

  return (
    <div className="video-hud" aria-hidden="false">
      {bubble && (
        <div className={`hud-bubble hud-${bubble.side}`} aria-hidden="true">
          <span className="hud-arrows">
            <Icon name={bubble.side === 'left' ? 'chevronLeft' : 'chevronRight'} size={20} />
            <Icon name={bubble.side === 'left' ? 'chevronLeft' : 'chevronRight'} size={20} />
          </span>
          {bubble.text}
        </div>
      )}
      {pill && (
        <div className="hud-pill" aria-hidden="true">
          {pill}
        </div>
      )}
      {flash && (
        <div className="hud-flash" key={flash.key} aria-hidden="true">
          {flash.kind === 'play' ? <PlayGlyph /> : <PauseGlyph />}
        </div>
      )}
      <div className="sr-only" role="status" aria-live="polite">
        {live}
      </div>
    </div>
  )
})
