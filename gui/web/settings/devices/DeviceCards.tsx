import {useId, useState} from 'react'
import {ACTION_LABELS, type DeviceAction, type DevicesState, type PairedDevice} from '../../../shared/devices'
import {messageOf} from '../../bridge'
import {Button, ConfirmInline, InlineAlert} from '../../ui/controls'
import {Icon} from '../../ui/Icon'
import type {CopiesState} from '../../../shared/copies'
import {navigate} from '../../nav'
import {CombineCardBlock, SeparateDialog} from './CombineDialogs'
import {openDeviceSetup} from './DeviceSetup'
import {deviceStatus, reachLine, statusLine} from './devicesLogic'
import {copyOnDevice, copyStatusLine} from './setupLogic'
import {removeDevice, setAccess, shareLibrary, syncDeviceNow} from './devicesApi'

type Props = {state: DevicesState; onState: (state: DevicesState) => void}

/** What a paired device may do beyond syncing, per library, as short chip labels. Syncing itself is the default and is not offered. */
const CHIPS: {action: DeviceAction; label: string}[] = [
  {action: 'inventory.read', label: 'See files'},
  {action: 'media.read', label: 'Play from here'},
  {action: 'copy.mutate', label: 'Keep a copy here'},
  {action: 'jobs', label: 'Download here'},
  {action: 'membership.admin', label: 'Add devices to the library'},
]

/** Your devices: one card per paired device, collapsed to one row. Several can be open; the page keeps which. */
export function YourDevices({state, onState, open, onToggle, copies}: {state: DevicesState; onState: Props['onState']; open: Set<string>; onToggle: (deviceId: string) => void; copies?: CopiesState}) {
  return (
    <ul className="devices-cards-list" aria-label="Paired devices">
      {state.paired.map((device) => (
        <li key={device.deviceId}>
          <DeviceCard device={device} onState={onState} open={open.has(device.deviceId)} onToggle={() => onToggle(device.deviceId)} copies={copies} />
        </li>
      ))}
    </ul>
  )
}

function DeviceCard({device, onState, open, onToggle, copies}: {device: PairedDevice; onState: Props['onState']; open: boolean; onToggle: () => void; copies?: CopiesState}) {
  const place = copyOnDevice(copies, device.deviceId)
  const [error, setError] = useState('')
  const [pending, setPending] = useState('')
  const [separating, setSeparating] = useState(false)
  const panel = useId()
  const status = deviceStatus(device)

  async function toggle(libraryId: string, action: DeviceAction, on: boolean, current: DeviceAction[]) {
    setError('')
    setPending(`${libraryId}:${action}`)
    try {
      // keeping a copy needs to see the folder and read its files: those two come with it
      const needs: DeviceAction[] = action === 'copy.mutate' ? ['inventory.read', 'media.read'] : []
      const next = on ? [...new Set([...current, action, ...needs])] : current.filter((a) => a !== action)
      onState(await setAccess(device.deviceId, libraryId, next))
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setPending('')
    }
  }

  const syncing = status === 'Syncing…'
  // reachable: sync now; not reachable: try to reach it again now (the link waits between tries otherwise)
  const syncLabel = device.online ? 'Sync now' : 'Retry'
  async function sync() {
    setError('')
    setPending('sync')
    try {
      onState(await syncDeviceNow(device.deviceId))
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setPending('')
    }
  }
  const syncButton = (label: string, className?: string) => (
    <Button size="sm" className={className} loading={pending === 'sync'} disabled={syncing} aria-label={`${label}: ${device.name}`} onClick={() => void sync()}>
      {label}
    </Button>
  )

  return (
    <article className="devices-card" id={`setting-device-${device.deviceId}`} aria-label={device.name} data-online={device.online || undefined} data-open={open || undefined}>
      <div className="devices-card-head">
      <button type="button" className="devices-card-row" aria-expanded={open} aria-controls={panel} onClick={onToggle}>
        <span className="devices-row-icon" aria-hidden="true">
          <Icon name={device.platform === 'android' ? 'pip' : 'sidebar'} size={18} />
        </span>
        <span className="devices-row-text">
          <strong>{device.name}</strong>
          <span className="small muted">{reachLine(device)}</span>
        </span>
        <span className="devices-card-status small" data-status={status}>
          {status}
        </span>
        <span className="devices-card-chevron" aria-hidden="true">
          <Icon name={open ? 'chevronUp' : 'chevronDown'} size={16} />
        </span>
      </button>
      {/* the small pill on the collapsed row; open, the status line carries the same button */}
      {!open && !syncing ? syncButton(device.online ? 'Sync' : 'Retry', 'devices-card-pill') : <span className="devices-card-pill-slot" aria-hidden="true" />}
      </div>
      {/* combining two libraries: visible on the collapsed card too, so a combine that runs or recovers is never hidden */}
      <CombineCardBlock device={{deviceId: device.deviceId, name: device.name}} open={open} onSeparate={() => setSeparating(true)} onReview={() => openDeviceSetup(device.deviceId, device.name)} />
      {separating && <SeparateDialog device={{deviceId: device.deviceId, name: device.name}} onClose={() => setSeparating(false)} />}
      {open && (
        <div className="devices-card-body" id={panel}>
          <div className="devices-card-statusrow">
            <p className="small devices-card-line" data-synced={(device.online && !!device.lastSync) || undefined}>
              {statusLine(device)}
            </p>
            {!syncing && syncButton(syncLabel)}
          </div>
          {place ? (
            <div className="devices-card-statusrow">
              <p className="small devices-copy-line" aria-label="Files kept up to date" data-copy={place.status}>
                {copyStatusLine(place, device.name)}
              </p>
              {place.review > 0 && (
                <Button size="sm" aria-label={`Review: files on ${device.name}`} onClick={() => navigate('Settings', 'storage', 'review')}>
                  Review
                </Button>
              )}
            </div>
          ) : (
            copies?.available && (
              <div className="devices-card-statusrow">
                <p className="small muted devices-copy-line">No files are kept up to date on {device.name} yet.</p>
                <Button size="sm" onClick={() => openDeviceSetup(device.deviceId, device.name)}>
                  Choose what stays up to date
                </Button>
              </div>
            )
          )}
          {device.libraries.length === 0 && device.shareable.length === 0 && <p className="small muted">No library is shared with this device yet.</p>}
          {device.shareable.map((lib) => (
            <div key={lib.libraryId} className="devices-share">
              <span className="small">
                <strong>{lib.name}</strong> is not shared with this device.
              </span>
              <Button
                size="sm"
                loading={pending === `share:${lib.libraryId}`}
                onClick={() => {
                  setError('')
                  setPending(`share:${lib.libraryId}`)
                  shareLibrary(device.deviceId, lib.libraryId)
                    .then(onState)
                    .catch((caught) => setError(messageOf(caught)))
                    .finally(() => setPending(''))
                }}
              >
                Share with {device.name}
              </Button>
            </div>
          ))}
          {device.libraries.map((lib) => {
            const lead = device.libraries.length > 1 ? `${lib.name}: allowed for ${device.name}` : `Allowed for ${device.name}`
            return (
              <div key={lib.libraryId} className="devices-chips" role="group" aria-label={lead}>
                <span className="small muted devices-chips-lead">{lead}</span>
                {CHIPS.map(({action, label}) => {
                  const on = lib.actions.includes(action)
                  return (
                    <button
                      key={action}
                      type="button"
                      className="devices-chip"
                      data-on={on || undefined}
                      aria-pressed={on}
                      title={ACTION_LABELS[action]}
                      disabled={pending === `${lib.libraryId}:${action}`}
                      onClick={() => void toggle(lib.libraryId, action, !on, lib.actions)}
                    >
                      {on && <Icon name="check" size={13} />}
                      {label}
                    </button>
                  )
                })}
              </div>
            )
          })}
          {error && <InlineAlert>{error}</InlineAlert>}
          <div className="devices-card-meta">
            <span className="small muted tabular" aria-label="Fingerprint">
              Fingerprint {device.fingerprint}
              {device.address ? ` · ${device.address}` : ''}
            </span>
            <ConfirmInline
              label="Unpair"
              confirmLabel="Unpair this device"
              prompt={
                <>
                  Unpair {device.name}? It stops syncing with this device and what it writes to the library afterwards is ignored. Copies already made stay where they are, and a device that is switched off only learns this when it next syncs.
                </>
              }
              onConfirm={async () => {
                try {
                  onState(await removeDevice(device.deviceId))
                } catch (caught) {
                  setError(messageOf(caught))
                }
              }}
            />
          </div>
        </div>
      )}
    </article>
  )
}
