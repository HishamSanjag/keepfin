import {useEffect, useState} from 'react'
import type {StorageState} from '../../shared/storage'
import {onPush, request} from '../bridge'

/**
 * Which storage screen the app shows first: first start, the setup window, or the app itself.
 * A main process without the storage operations (or the browser preview) answers with an error;
 * that keeps today's behaviour ('ready'), so nothing is ever blocked behind a screen that cannot finish.
 */
export function useStorageGate(): {state: StorageState | undefined; done: () => void} {
  const [state, setState] = useState<StorageState | undefined>()
  useEffect(() => {
    let live = true
    request<StorageState>({op: 'storageState'})
      .then((next) => live && setState(next))
      .catch(() => live && setState({phase: 'ready', defaultPath: ''}))
    const off = onPush((event) => {
      if (event.type === 'storage') setState(event.state)
    })
    return () => {
      live = false
      off()
    }
  }, [])
  const done = () => {
    request<StorageState>({op: 'storageState'})
      .then(setState)
      .catch(() => setState({phase: 'ready', defaultPath: ''}))
  }
  return {state, done}
}
