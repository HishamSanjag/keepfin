import {useCallback, useEffect, useRef, useState} from 'react'
import type {CopiesState} from '../../../shared/copies'
import {messageOf, onPush} from '../../bridge'
import {getCopies} from '../storage/copiesApi'

/** Every mounted user of the copies state on the Devices side (the page, the setup): an answer one of them gets reaches all of them. */
const holders = new Set<(state: CopiesState) => void>()
export const broadcastCopies = (state: CopiesState) => holders.forEach((hold) => hold(state))

/** The copies state for the device cards and the setup: read once, then replaced by every `copies` push. */
export function useDeviceCopies() {
  const [state, setState] = useState<CopiesState | undefined>()
  const [error, setError] = useState('')
  const live = useRef(true)
  const load = useCallback(() => {
    setError('')
    getCopies()
      .then((next) => live.current && broadcastCopies(next))
      .catch((caught) => live.current && setError(messageOf(caught)))
  }, [])
  useEffect(() => {
    live.current = true
    holders.add(setState)
    load()
    const off = onPush((event) => {
      if (event.type === 'copies') setState(event.state)
    })
    return () => {
      live.current = false
      holders.delete(setState)
      off()
    }
  }, [load])
  return {state, error, reload: load}
}
