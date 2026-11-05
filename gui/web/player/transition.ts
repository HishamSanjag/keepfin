import {flushSync} from 'react-dom'
import {prefersReducedMotion} from '../motion/spring'
import {setVideoOn} from './musicVideo'
import {setExpanded} from './playerStore'

export function openPlayer() {
  if (typeof document === 'undefined' || typeof document.startViewTransition !== 'function' || prefersReducedMotion()) {
    setExpanded(true)
    return
  }
  document.startViewTransition(() => {
    flushSync(() => {
      setExpanded(true)
    })
  })
}

export function closePlayer() {
  if (typeof document === 'undefined' || typeof document.startViewTransition !== 'function' || prefersReducedMotion()) {
    setExpanded(false)
    return
  }
  document.startViewTransition(() => {
    flushSync(() => {
      setExpanded(false)
    })
  })
}

/** The mini bar's Show video: Video becomes the choice and the full player opens with the picture in the cover's place. */
export function showPicture() {
  setVideoOn(true)
  openPlayer()
}
