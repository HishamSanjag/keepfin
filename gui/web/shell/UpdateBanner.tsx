import {releaseHighlights} from '../../shared/updates'
import {native} from '../bridge'
import {covered, hidePill, runUpdate, useUpdates} from '../updates'
import {Button, IconButton} from '../ui/controls'
import {Icon} from '../ui/Icon'

/**
 * A dismissible notice at the top of Downloads while an update waits: what it is, Update, and a close button.
 * Closing it hides this version everywhere (the header pill too); a newer version shows it again.
 */
export function UpdateBanner() {
  const {state, pending, skipped, hidden} = useUpdates()
  const version = state?.version
  if (!native || !state || !version || state.phase !== 'available' || covered(version, skipped) || covered(version, hidden)) return null
  const installed = state.mode === 'installed'
  const [first] = releaseHighlights(state.notes, 'windows', 1)
  return (
    <section className="update-banner" aria-label="App update">
      <Icon name="download" size={20} />
      <span className="update-banner-text">
        <strong>Keepfin {version} is available</strong>
        {first && <span>{first}</span>}
      </span>
      <Button variant="primary" size="sm" disabled={pending} onClick={() => void runUpdate(installed ? 'updateDownload' : 'updateReleases')}>
        Update
      </Button>
      <IconButton icon="close" label={`Hide update banner for v${version}`} onClick={() => hidePill(version)} />
    </section>
  )
}
