import {useEffect, useRef, useState, type FormEvent} from 'react'
import type {DevicesState} from '../../../shared/devices'
import {messageOf} from '../../bridge'
import {Button, InlineAlert} from '../../ui/controls'
import {closePairing, connectTo, openPairing} from './devicesApi'
import {useNow} from './useDevices'

type Props = {state: DevicesState; onState: (state: DevicesState) => void; onClose: () => void}

const clock = (ms: number) => {
  const total = Math.max(0, Math.ceil(ms / 1000))
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

/**
 * Add by address: this device answers pairing requests for two minutes and takes an address, a pairing link or a typed code.
 * (Devices found on the network are under Available devices; the pairing itself is the dialog of PairingHost.)
 */
export function AddDevice({state, onState, onClose}: Props) {
  const [address, setAddress] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState('')
  const opened = useRef(false)
  const now = useNow(state.pairingUntil !== null)

  useEffect(() => {
    // opening the page opens the two-minute window; leaving it closes the window again
    if (!opened.current) {
      opened.current = true
      openPairing().then(onState, (caught) => setError(messageOf(caught)))
    }
    return () => {
      void closePairing().then(onState, () => undefined)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function act(label: string, work: () => Promise<DevicesState>) {
    setBusy(label)
    setError('')
    try {
      onState(await work())
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setBusy('')
    }
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (address.trim()) void act('connect', () => connectTo(address.trim()))
  }
  const left = state.pairingUntil !== null ? state.pairingUntil - now : 0
  return (
    <div className="devices-add">
      <header className="devices-add-head">
        <div>
          <h4>Add by address</h4>
          <p className="small muted" role="status">
            {state.pairingUntil !== null && left > 0 ? <>This device is open to new devices for {clock(left)}. Open Devices on the other one too.</> : <>This device is closed to new devices.</>}
          </p>
        </div>
        <div className="devices-add-head-actions">
          {(state.pairingUntil === null || left <= 0) && (
            <Button size="sm" onClick={() => void act('open', openPairing)}>
              Open again
            </Button>
          )}
          <Button size="sm" variant="ghost" onClick={onClose}>
            Close
          </Button>
        </div>
      </header>
      {error && <InlineAlert>{error}</InlineAlert>}
      <div className="devices-add-grid">
        <section aria-label="Enter a code or address">
          <label className="devices-field-label" htmlFor="devices-address-field">
            Enter the code or address shown on your other device
          </label>
          <form className="devices-address" onSubmit={submit}>
            <input
              id="devices-address-field"
              className="input"
              value={address}
              placeholder="192.168.1.20, a name, or a code like K7QPM-4X2TA"
              onChange={(event) => setAddress(event.target.value)}
              spellCheck={false}
              autoCapitalize="off"
            />
            <Button type="submit" variant="primary" disabled={!address.trim()} loading={busy === 'connect'}>
              Connect
            </Button>
          </form>
          <p className="small muted">Use this for a Tailscale name or number, or when the network does not show devices. You can also paste a pairing link, or type the code shown under the QR on the other device.</p>
        </section>
      </div>
    </div>
  )
}
