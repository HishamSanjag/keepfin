import {useEffect, useState} from 'react'
import {emptyWhere, type WhereState} from '../../../shared/where'
import {onPush} from '../../bridge'
import {getWhere} from './whereApi'

/** Where the files are: read once, then replaced by every `where` push (a library change, a copy or a device moved on). Undefined while loading. */
export function useWhere(): WhereState | undefined {
  const [state, setState] = useState<WhereState | undefined>()
  useEffect(() => {
    let live = true
    getWhere()
      .then((next) => live && setState(next))
      // the browser preview has no library: nothing to show
      .catch(() => live && setState(emptyWhere))
    const off = onPush((event) => {
      if (event.type === 'where') setState(event.state)
    })
    // main keeps pushing for ten minutes after a look: ask again now and then so a long visit keeps going
    const again = window.setInterval(() => void getWhere().then((next) => live && setState(next)).catch(() => undefined), 5 * 60_000)
    return () => {
      live = false
      off()
      window.clearInterval(again)
    }
  }, [])
  return state
}

/** The current time, renewed every half minute, so "2 min ago" stays true while the page is open. */
export function useNow(): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000)
    return () => window.clearInterval(timer)
  }, [])
  return now
}
