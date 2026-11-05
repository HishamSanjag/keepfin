import {useCallback, useEffect, useRef, useState} from 'react'
import type {DevicesState} from '../../../shared/devices'
import {messageOf, onPush} from '../../bridge'
import {getDevices} from './devicesApi'

/** Every mounted user of the devices state (the page, the pairing dialog, the sidebar…): an answer one of them gets reaches all of them. */
const holders = new Set<(state: DevicesState) => void>()
const broadcast = (state: DevicesState) => holders.forEach((hold) => hold(state))

/** The devices state: read once, then replaced by every `devices` push (a device appeared, the numbers are ready, a device came or went). */
export function useDevices() {
  const [state, setState] = useState<DevicesState | undefined>()
  const [error, setError] = useState('')
  const live = useRef(true)
  const load = useCallback(() => {
    setError('')
    getDevices()
      .then((next) => live.current && broadcast(next))
      .catch((caught) => live.current && setError(messageOf(caught)))
  }, [])
  useEffect(() => {
    live.current = true
    holders.add(setState)
    load()
    const off = onPush((event) => {
      if (event.type === 'devices') setState(event.state)
    })
    return () => {
      live.current = false
      holders.delete(setState)
      off()
    }
  }, [load])
  return {state, error, reload: load, set: broadcast}
}

/** The current time, refreshed every second while [active] (for the pairing countdown). */
export function useNow(active: boolean): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active) return
    setNow(Date.now())
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [active])
  return now
}
