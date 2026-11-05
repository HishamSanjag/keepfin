import {useEffect, useRef} from 'react'
import type {DevicesState} from '../../../shared/devices'
import {Button} from '../../ui/controls'
import {Group} from '../../ui/SettingCard'
import {showQr} from './devicesApi'
import {useNow} from './useDevices'

/** A new code this often, and again as soon as the shown one has expired. */
export const QR_REFRESH_MS = 2 * 60 * 1000
const MIN_GAP_MS = 5000

/**
 * "Or scan with your phone": a QR code a phone scans to connect at once. While the page is visible and Devices is on, Keepfin asks for
 * a new code every two minutes, and when the shown one expires. Under it, the same connection as a typed code for someone with no camera.
 */
export function QrCard({state, onState}: {state: DevicesState; onState: (state: DevicesState) => void}) {
  const now = useNow(true)
  const qr = state.qr
  const expired = !qr || qr.expiresAt <= now
  const lastAsk = useRef(0)
  const ask = useRef<() => void>(() => undefined)
  ask.current = () => {
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
    if (Date.now() - lastAsk.current < MIN_GAP_MS) return
    lastAsk.current = Date.now()
    void showQr().then(onState, () => undefined)
  }
  // the first code, and a new one when the shown one has expired
  useEffect(() => {
    if (expired) ask.current()
  }, [expired])
  // a fresh code every two minutes, and one when the page comes back into view with an expired code
  useEffect(() => {
    const timer = setInterval(() => ask.current(), QR_REFRESH_MS)
    const visible = () => {
      if (document.visibilityState === 'visible' && (!state.qr || state.qr.expiresAt <= Date.now())) ask.current()
    }
    document.addEventListener('visibilitychange', visible)
    return () => {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', visible)
    }
  }, [state.qr])
  const name = state.thisDevice?.name ?? 'this PC'
  return (
    <Group id="scan" cards title="Scan with your phone" description="Connects at once.">
    <div className="devices-qr-card devices-panel">
      <div className="devices-qr-image">
        {qr && !expired ? <img src={qr.image} width={132} height={132} alt={`QR code to connect a phone to ${name}`} /> : <span className="small muted">Getting a code…</span>}
      </div>
      <div className="devices-qr-text">
        <p className="small muted">Use the phone’s camera, or Keepfin › Devices › Scan. A new code every 2 minutes.</p>
        {qr && !expired && (
          <Button size="sm" variant="ghost" icon="copy" onClick={() => void navigator.clipboard?.writeText(qr.link)}>
            Copy link
          </Button>
        )}
        {qr && !expired && qr.code && (
          <div className="devices-qr-code" role="group" aria-label="Code to type">
            <p className="small muted">No camera? Type this code on the other device</p>
            <div className="devices-qr-code-row">
              <strong className="devices-qr-code-text">
                {qr.code}
              </strong>
              <Button size="sm" variant="ghost" icon="copy" aria-label="Copy code" onClick={() => void navigator.clipboard?.writeText(qr.code ?? '')}>
                Copy
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
    </Group>
  )
}
