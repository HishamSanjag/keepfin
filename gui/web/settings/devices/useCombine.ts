import {useEffect, useRef, useState} from 'react'
import type {CombineState} from '../../../shared/combine'
import {messageOf, onPush} from '../../bridge'
import {getCombine} from './combineApi'

/** Every mounted user of the combine state (the device cards, the Combine step, the Separate dialog): an answer one of them gets reaches all of them. */
const holders = new Set<(state: CombineState) => void>()
let last: CombineState | undefined
export const broadcastCombine = (state: CombineState) => {
  last = state
  holders.forEach((hold) => hold(state))
}

/** The state of combining two libraries: read once when the page opens (a combine already running or recovering shows at once), then replaced by every `combine` push. */
export function useCombine() {
  const [state, setState] = useState<CombineState | undefined>(() => (holders.size > 0 ? last : undefined))
  const [error, setError] = useState('')
  const live = useRef(true)
  useEffect(() => {
    live.current = true
    // the first user on the page reads the state; the others (one per device card) share what it got
    const first = holders.size === 0
    holders.add(setState)
    if (first || !last) {
      getCombine()
        .then((next) => live.current && broadcastCombine(next))
        .catch((caught) => live.current && setError(messageOf(caught)))
    }
    const off = onPush((event) => {
      if (event.type === 'combine') broadcastCombine(event.state)
    })
    return () => {
      live.current = false
      holders.delete(setState)
      off()
    }
  }, [])
  return {state, error}
}
