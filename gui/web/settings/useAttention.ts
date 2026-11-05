import {useEffect, useMemo, useState} from 'react'
import type {CopiesState} from '../../shared/copies'
import type {DevicesState} from '../../shared/devices'
import type {StorageOverview} from '../../shared/storage'
import {native, onPush} from '../bridge'
import {useUpdates} from '../updates'
import {getOverview} from '../setup/storageApi'
import {getDevices} from './devices/devicesApi'
import {getCopies} from './storage/copiesApi'
import {deriveAttention, type AttentionItem} from './attention'

/**
 * The problems to show at the top of Settings. It only reads state the app already has (devices, the Keepfin folder, copies,
 * updates), once when Settings opens, again whenever the category changes, and whenever main pushes a change.
 * A read that fails shows nothing: a problem is never guessed.
 */
export function useAttention(refreshKey: string): AttentionItem[] {
  const [devices, setDevices] = useState<DevicesState>()
  const [overview, setOverview] = useState<StorageOverview>()
  const [copies, setCopies] = useState<CopiesState>()
  const update = useUpdates().state

  useEffect(() => {
    if (!native) return
    let live = true
    const grab = <T,>(work: Promise<T>, set: (value: T) => void) =>
      work.then((value) => live && set(value)).catch(() => undefined)
    void grab(getDevices(), setDevices)
    void grab(getOverview(), setOverview)
    void grab(getCopies(), setCopies)
    const off = onPush((event) => {
      if (event.type === 'devices') setDevices(event.state)
      else if (event.type === 'copies') setCopies(event.state)
      else if (event.type === 'storage') void grab(getOverview(), setOverview)
    })
    return () => {
      live = false
      off()
    }
  }, [refreshKey])

  return useMemo(() => deriveAttention({devices, overview, copies, update}), [devices, overview, copies, update])
}
