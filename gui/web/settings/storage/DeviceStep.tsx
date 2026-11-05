import type {CopyDeviceChoice} from '../../../shared/copies'

/**
 * "A paired device" in the copies wizard: the copy is kept in that device's Keepfin folder, written by the device itself. The device must
 * allow it (its own switch, "Keep a copy on this device"), and be connected while the copy syncs.
 */
export function DeviceStep({devices, value, onChange}: {devices: CopyDeviceChoice[]; value: string; onChange: (device: CopyDeviceChoice) => void}) {
  return (
    <fieldset className="copy-holds" aria-label="Paired devices">
      <legend className="sr-only">Choose a paired device</legend>
      {devices.map((device) => (
        <label key={device.deviceId} className="copy-choice" data-disabled={!device.allowed || undefined}>
          <input type="radio" name="copy-device" checked={value === device.deviceId} disabled={!device.allowed} onChange={() => onChange(device)} />
          <span>
            <strong>{device.name}</strong>
            <small>{device.online ? 'Connected' : 'Not connected'}</small>
            <small>{device.allowed ? 'The copy is kept in its Keepfin folder. The device itself writes the files, and it keeps the bytes of anything it replaces.' : device.why}</small>
          </span>
        </label>
      ))}
      <p className="small muted">The copy continues whenever the device is connected. Over a VPN such as Tailscale it works away from home too.</p>
    </fieldset>
  )
}
