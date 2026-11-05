import {useCallback, useEffect, useRef, useState} from 'react'
import type {CopiesState} from '../../../shared/copies'
import {messageOf, onPush} from '../../bridge'
import {getCopies} from './copiesApi'

/** The copies state: read once, then replaced by every `copies` push (a sync moved on, a drive appeared, a decision is waiting). */
export function useCopies() {
  const [state, setState] = useState<CopiesState | undefined>()
  const [error, setError] = useState('')
  const live = useRef(true)
  const load = useCallback(() => {
    setError('')
    getCopies()
      .then((next) => live.current && setState(next))
      .catch((caught) => live.current && setError(messageOf(caught)))
  }, [])
  useEffect(() => {
    live.current = true
    load()
    const off = onPush((event) => {
      if (event.type === 'copies') setState(event.state)
    })
    return () => {
      live.current = false
      off()
    }
  }, [load])
  return {state, error, reload: load, set: setState}
}
