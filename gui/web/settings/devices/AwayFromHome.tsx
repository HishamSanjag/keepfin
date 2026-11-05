import type {DevicesState} from '../../../shared/devices'
import {Badge} from '../../ui/controls'
import {Icon} from '../../ui/Icon'

/**
 * Away from home (inside the guide): Keepfin needs no account of ours and no server. Tailscale puts your devices on one private network
 * wherever they are, and paired devices simply reconnect over it. Says whether Tailscale is on here and the three steps to set it up.
 */
export function AwayFromHome({tailscale}: {tailscale: DevicesState['tailscale']}) {
  const on = tailscale.state === 'on'
  return (
    <div className="devices-away-card">
      <p className="devices-away-status" role="status">
        <Icon name={on ? 'check' : 'info'} size={16} />
        <strong>{on ? `Tailscale is on: ${tailscale.name ?? tailscale.address ?? 'this PC'}` : 'Tailscale not detected'}</strong>
        <Badge tone={on ? 'success' : 'neutral'}>{on ? 'On' : 'Not detected'}</Badge>
      </p>
      <ol className="small devices-away-steps">
        <li>Install Tailscale on both devices (tailscale.com/download).</li>
        <li>Sign in with the same account on both.</li>
        <li>Keep it on. Paired devices then reconnect from anywhere.</li>
      </ol>
      <p className="small muted">Devices you pair while both are on Tailscale show “via Tailscale” under Available devices. Devices you already paired reconnect by themselves.</p>
      <p>
        <a className="btn btn-secondary btn-sm" href="https://tailscale.com/download" target="_blank" rel="noreferrer">
          Get Tailscale
        </a>
      </p>
    </div>
  )
}
