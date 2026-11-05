import {useEffect, useRef, useState} from 'react'
import type {DevicesState, PairingView} from '../../../shared/devices'
import {messageOf} from '../../bridge'
import {toast} from '../../toasts'
import {Badge, Button, InlineAlert, Spinner} from '../../ui/controls'
import {Dialog} from '../../ui/Dialog'
import {DeviceSetupHost, openDeviceSetup} from './DeviceSetup'
import {decidePairing, dismissPairing, removeDevice} from './devicesApi'
import {useDevices} from './useDevices'

/** Six digits as `482 193`. */
export const groupDigits = (sas: string) => (sas.length === 6 ? `${sas.slice(0, 3)} ${sas.slice(3)}` : sas)

/** How long the "connected" note keeps its Undo. */
export const UNDO_MS = 10_000

/**
 * Pairing, on any screen. Lives at the app level so a request that comes in is answered wherever the person is: the dialog for a
 * request ("<name> wants to connect") and for one this device started ("Connecting to <name>…"), and, when a scanned code connected
 * a device at once, a note "<name> connected" with Undo. Nothing here shows for an `autoApproved` request.
 */
export function PairingHost() {
  const {state, set} = useDevices()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const noted = useRef('')
  const pairing = state?.available && state.enabled ? state.pairing : null

  // a code that connected at once: say so, offer Undo for ten seconds, and close the pairing
  useEffect(() => {
    if (!state || !pairing || pairing.stage !== 'done' || !pairing.autoApproved || noted.current === pairing.id) return
    noted.current = pairing.id
    const device = state.paired.find((item) => item.fingerprint === pairing.peerFingerprint) ?? state.paired.find((item) => item.name === pairing.peerName)
    toast({
      tone: 'success',
      message: `${pairing.peerName} connected`,
      key: `paired-${pairing.id}`,
      duration: UNDO_MS,
      action: device ? {label: 'Undo', run: () => void removeDevice(device.deviceId).then(set, (caught) => toast({tone: 'error', message: messageOf(caught)}))} : undefined,
    })
    void dismissPairing().then(set, () => undefined)
  }, [pairing, state, set])

  const shown = !!pairing && !(pairing.stage === 'done' && pairing.autoApproved)
  // once connected, either device offers to choose what stays up to date (the one asked as well as the one that pressed Connect)
  const peerDevice =
    pairing && state && pairing.stage === 'done'
      ? (state.paired.find((item) => item.fingerprint === pairing.peerFingerprint) ?? state.paired.find((item) => item.name === pairing.peerName))
      : undefined
  const close = () => {
    setError('')
    void dismissPairing().then(set, (caught) => setError(messageOf(caught)))
  }
  return (
    <>
      {shown && pairing && (
        <PairingDialog
          key={pairing.id}
          pairing={pairing}
          busy={busy}
          error={error}
          onDecide={(match) => {
            setBusy(true)
            setError('')
            decidePairing(pairing.id, match)
              .then(set, (caught) => setError(messageOf(caught)))
              .finally(() => setBusy(false))
          }}
          onSetUp={
            peerDevice
              ? () => {
                  openDeviceSetup(peerDevice.deviceId, peerDevice.name)
                  close()
                }
              : undefined
          }
          onClose={close}
        />
      )}
      <DeviceSetupHost />
    </>
  )
}

/** The dialog for one pairing, from either side. */
export function PairingDialog({pairing, busy, error, onDecide, onClose, onSetUp}: {pairing: PairingView; busy: boolean; error: string; onDecide: (match: boolean) => void; onClose: () => void; onSetUp?: () => void}) {
  const peer = pairing.peerName
  const asking = pairing.role === 'asking'
  // connected: what stays up to date is the next thing to choose, on both devices
  const offerSetup = pairing.stage === 'done' && !!onSetUp
  // both people compare the code and tap Codes match; whoever taps first waits for the other (the code stays on screen)
  const numbers = !!pairing.sas && (pairing.stage === 'comparing' || (asking && pairing.stage === 'waiting'))
  const waitingOther = numbers && (pairing.stage === 'waiting' || !!pairing.confirmed)
  const comparing = numbers && !waitingOther
  const title = numbers && !asking ? `${peer} wants to connect` : pairing.stage === 'approving' ? `${peer} scanned your code` : offerSetup ? `${peer} is connected` : pairing.stage === 'done' ? `Connected to ${peer}` : pairing.stage === 'failed' ? 'Nothing was connected' : asking ? `Connecting to ${peer}…` : `${peer} wants to connect`
  const fingerprint = pairing.peerFingerprint && (
    <p className="small muted tabular" aria-label="Fingerprint of the other device">
      {peer}: {pairing.peerFingerprint}
    </p>
  )
  let footer
  if (comparing) {
    footer = (
      <>
        <Button variant="primary" onClick={() => onDecide(true)} loading={busy}>
          Codes match · Connect
        </Button>
        <Button onClick={() => onDecide(false)} disabled={busy}>
          They don't match
        </Button>
      </>
    )
  } else if (waitingOther) {
    footer = <Button onClick={() => onDecide(false)}>Cancel</Button>
  } else if (pairing.stage === 'approving') {
    footer = (
      <>
        <Button variant="primary" onClick={() => onDecide(true)} loading={busy}>
          Add {peer}
        </Button>
        <Button onClick={() => onDecide(false)} disabled={busy}>
          Decline
        </Button>
      </>
    )
  } else if (offerSetup) {
    footer = (
      <>
        <Button onClick={onClose}>Later</Button>
        <Button variant="primary" onClick={onSetUp}>
          Set up
        </Button>
      </>
    )
  } else if (pairing.stage === 'done') {
    footer = (
      <Button variant="primary" onClick={onClose}>
        Done
      </Button>
    )
  } else if (pairing.stage === 'failed') {
    footer = (
      <Button variant="primary" onClick={onClose}>
        OK
      </Button>
    )
  } else {
    footer = <Button onClick={onClose}>Cancel</Button>
  }
  return (
    <Dialog title={title} onClose={(comparing && !busy) || waitingOther ? () => onDecide(false) : onClose} labelledBy="devices-pairing-title" className="devices-dialog" footer={<div className="devices-dialog-actions">{footer}</div>}>
      <div aria-live="polite" className="devices-dialog-body">
        {error && <InlineAlert>{error}</InlineAlert>}
        {numbers && (
          <>
            <p>{waitingOther ? 'You confirmed the code on this PC.' : 'Check that both devices show this code, then tap Codes match on both.'}</p>
            <output className="devices-sas tabular" aria-label="Pairing code">
              {groupDigits(pairing.sas!)}
            </output>
            {fingerprint}
            {waitingOther && (
              <>
                <p>On {peer}, tap Codes match to finish.</p>
                <p className="small muted devices-waiting" role="status">
                  <Spinner /> Waiting for {peer}…
                </p>
              </>
            )}
            <p className="small muted">If the codes differ, someone else may be on your network. Do not connect.</p>
          </>
        )}
        {pairing.stage === 'connecting' && (
          <p>
            <Spinner /> {asking ? `Connecting to ${peer}…` : `${peer} is connecting…`}
          </p>
        )}
        {pairing.stage === 'waitingForPeer' && (
          <p>
            <Spinner /> Waiting for {/^[\d.:]+$/.test(peer) ? 'the other device' : peer} to accept…
          </p>
        )}
        {pairing.stage === 'waiting' && !numbers && (
          <p>
            <Spinner /> Waiting for {peer} to accept…
          </p>
        )}
        {pairing.stage === 'comparing' && !pairing.sas && (
          <p>
            <Spinner /> Connecting to {peer}…
          </p>
        )}
        {pairing.stage === 'approving' && (
          <>
            <p>Add it only if you scanned the code yourself, just now.</p>
            {fingerprint}
          </>
        )}
        {offerSetup && (
          <p>
            <Badge tone="success">Connected</Badge> Choose what stays up to date between this device and {peer}.
          </p>
        )}
        {pairing.stage === 'done' && !offerSetup && (
          <p>
            <Badge tone="success">Done</Badge> The two libraries now stay in step. {peer} can sync the library; you can allow more from its card.
          </p>
        )}
        {pairing.stage === 'failed' && <InlineAlert title="Nothing was connected">{pairing.error ?? 'The connection did not finish.'}</InlineAlert>}
      </div>
    </Dialog>
  )
}
