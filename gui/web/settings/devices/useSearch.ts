import {useCallback, useEffect, useRef, useState} from 'react'
import type {DevicesState, DiscoveredDevice} from '../../../shared/devices'
import {unwatchDevices, watchDevices} from './devicesApi'
import {SEARCH_MS} from './devicesLogic'

/**
 * Looking for devices, only while the Devices page is open and for a minute at a time. It starts when the page opens (and Devices is
 * on), stops when the page closes, when the minute is up or when the person presses Stop, and starts again with Search again. Paired
 * devices reconnect in the background as always; that is not this. What was found stays listed after the search stops.
 */
export function useSearch(enabled: boolean, discovered: DiscoveredDevice[], paired: DevicesState['paired'], set: (state: DevicesState) => void) {
  const [ended, setEnded] = useState<null | 'stopped' | 'finished'>(null)
  const searching = enabled && ended === null
  const seen = useRef<DiscoveredDevice[]>([])

  useEffect(() => {
    if (!searching) return
    void watchDevices().then(set, () => undefined)
    const timer = setTimeout(() => setEnded('finished'), SEARCH_MS)
    return () => {
      clearTimeout(timer)
      void unwatchDevices().catch(() => undefined)
    }
  }, [searching, set])

  const stop = useCallback(() => setEnded('stopped'), [])
  const again = useCallback(() => setEnded(null), [])

  if (searching) seen.current = discovered
  const devices = searching ? discovered : seen.current.filter((device) => !paired.some((item) => item.deviceId.startsWith(device.hint)))
  return {searching, ended, devices, stop, again}
}
