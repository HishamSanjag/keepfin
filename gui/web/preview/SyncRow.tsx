import {everyWords, followIntervals, type CollectionFollow} from '../../shared/library'
import {Select, Switch} from '../ui/controls'

const label = (hours: number) => {
  const words = everyWords(hours)
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/**
 * "Keep in sync" for a playlist or channel being downloaded, on the main tab: off by default (following is opt-in). When on, the
 * collection checks for new items at the chosen interval and downloads them.
 */
export function SyncRow({
  on,
  onChange,
  hours,
  onHours,
  disabled,
}: {
  on: boolean
  onChange: (value: boolean) => void
  hours: CollectionFollow['intervalHours']
  onHours: (value: CollectionFollow['intervalHours']) => void
  disabled?: boolean
}) {
  return (
    <section className={`sync-row${on ? ' is-on' : ''}`} aria-label="Keep in sync">
      <div className="sync-text">
        <label className="sync-title" htmlFor="keep-in-sync">
          Keep in sync
        </label>
        <span className="sync-desc">New ones download by themselves</span>
      </div>
      {on && (
        <span className="sync-interval">
          <Select<string>
            label="How often to check for new ones"
            value={String(hours)}
            disabled={disabled}
            onChange={(value) => onHours(Number(value) as CollectionFollow['intervalHours'])}
            options={followIntervals.map((option) => ({value: String(option), label: label(option)}))}
          />
        </span>
      )}
      <Switch id="keep-in-sync" label="Keep in sync" checked={on} onChange={onChange} disabled={disabled} />
    </section>
  )
}
