import {flushSync} from 'react-dom'
import {prefersReducedMotion} from '../motion/spring'
import {setVideoOn} from './musicVideo'
import {setExpanded} from './playerStore'

export function openPlayer() {
  morph(() => setExpanded(true))
}

export function closePlayer() {
  morph(() => setExpanded(false))
}

/** The mini bar's Show video: Video becomes the choice and the full player opens with the picture in the cover's place. */
export function showPicture() {
  setVideoOn(true)
  openPlayer()
}

/** Applies `update` as a view transition where the browser has them and motion is allowed. */
function morph(update: () => void) {
  if (typeof document === 'undefined' || typeof document.startViewTransition !== 'function' || prefersReducedMotion()) {
    update()
    return
  }
  const transition = document.startViewTransition(() => flushSync(update))
  // A quick second open or close (Esc twice) skips this transition before it starts: `ready` then rejects with
  // "Transition was skipped". The state change itself still applies, so the rejection is expected, not an error.
  transition.ready.catch(() => {})
}
