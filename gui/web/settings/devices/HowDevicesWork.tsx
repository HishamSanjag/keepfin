import {useEffect, useRef} from 'react'
import type {DevicesState} from '../../../shared/devices'
import {Button} from '../../ui/controls'
import {Dialog} from '../../ui/Dialog'
import {AwayFromHome} from './AwayFromHome'

export type GuidePart = 'how' | 'tailscale'

/**
 * One guide for the whole page, opened by the "?" at the top: how finding, pairing and syncing work, and Away from home (the Tailscale
 * steps). Opening it from the Tailscale line on the page scrolls straight to the Away from home part.
 */
export function DevicesGuide({tailscale, part, onClose}: {tailscale: DevicesState['tailscale']; part: GuidePart; onClose: () => void}) {
  const away = useRef<HTMLElement>(null)
  useEffect(() => {
    if (part === 'tailscale') away.current?.scrollIntoView?.({block: 'start'})
  }, [part])
  return (
    <Dialog
      title="How devices work"
      labelledBy="devices-guide-title"
      className="devices-guide"
      onClose={onClose}
      footer={
        <Button variant="primary" onClick={onClose}>
          Close
        </Button>
      }
    >
      <div className="devices-guide-body">
        <ol className="devices-how" aria-label="How devices work">
          <li>
            <span className="devices-how-step" aria-hidden="true">
              1
            </span>
            <strong>Find each other</strong>
            <span className="small muted">Open Keepfin on both devices. On the same Wi-Fi they show up under Available devices within a few seconds. Away from home, Tailscale on both does the same.</span>
          </li>
          <li>
            <span className="devices-how-step" aria-hidden="true">
              2
            </span>
            <strong>Pair once</strong>
            <span className="small muted">Press Connect on one device. Both show the same six-digit code: check that they match, then tap Codes match on both devices. Or scan the code with your phone. Only paired devices are let in, and nothing is shared before that.</span>
          </li>
          <li>
            <span className="devices-how-step" aria-hidden="true">
              3
            </span>
            <strong>Stay in step</strong>
            <span className="small muted">While both are connected, a change on one shows on the other within seconds. A device that was off catches up when it connects again.</span>
          </li>
        </ol>
        <dl className="devices-what">
          <div>
            <dt>Syncs by itself</dt>
            <dd>Your library: what you have, titles and tags, playlists and favourites.</dd>
          </div>
          <div>
            <dt>Stays where it is</dt>
            <dd>The files. Each one stays on the device that saved it; the other lists it as “On the other device” and plays it over the network when you allow “Play files from this device” on the device that has them.</dd>
          </div>
          <div>
            <dt>Files on both</dt>
            <dd>Add a copy: Storage &amp; copies → Add a copy → A paired device.</dd>
          </div>
          <div>
            <dt>Where it goes</dt>
            <dd>Straight from one device to the other, on your network or through Tailscale. No account and no server in between.</dd>
          </div>
        </dl>
        <section ref={away} className="devices-guide-away" aria-label="Away from home">
          <h3>Away from home</h3>
          <AwayFromHome tailscale={tailscale} />
        </section>
      </div>
    </Dialog>
  )
}
