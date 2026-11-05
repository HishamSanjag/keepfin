// A link brought in from a space ("Add > From a link"). It runs through the normal capture and review flow on the
// Downloads tab with Save to preset to that space, and after the download starts the person lands back in the space.
import {navigate} from '../nav'
import {setLibView} from '../library/libNav'

let returnSpace: string | undefined

/** Remembers which space asked for the link. */
export function startIntake(space: string) {
  returnSpace = space
}
export function clearIntake() {
  returnSpace = undefined
}
export const intakeSpace = () => returnSpace

/**
 * Called once a download has started towards `target`. When that is the space the link came from, shows that space
 * again and returns true. A different target (the person changed Save to) keeps them where they are.
 */
export function landBack(target: string | undefined): boolean {
  const space = returnSpace
  returnSpace = undefined
  if (!space || target !== space) return false
  navigate('Library')
  setLibView({type: 'space', id: space})
  return true
}
