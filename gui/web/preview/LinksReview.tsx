// The preview of several links pasted together: each is read on its own, with one choice of quality for all and a different one per link if wanted.
import {memo, useState} from 'react'
import {downloadPresets, type DownloadPreset} from '../../shared/contracts'
import {choiceForPreset, estimate, estimateLabel, formatRequest, fromQuickValue, quickOptions, quickValue} from '../capture/choice'
import {SaveTo} from '../capture/SaveTo'
import type {CaptureEntry, CaptureModel} from '../capture/useCapture'
import {DownloadOn} from '../devices/DownloadOn'
import {formatBytes, formatDuration, hostOf, plural} from '../format'
import {defaultSpaceFor} from '../../shared/library'
import {getMeta, useMeta} from '../store'
import {Badge, Button, IconButton, Select, Spinner} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {Thumbnail} from '../ui/Thumbnail'
import {AddToPlaylist} from './AddToPlaylist'
import {ExtrasGroups} from './Extras'
import {extrasChanged, validateOptions} from './extrasModel'
import {FormatQuality} from './FormatQuality'
import {PreviewWindow} from './PreviewWindow'

const defaultFor = (url: string) => (getMeta().settings.library ? defaultSpaceFor(url, getMeta().settings.library) : '')
const isAudio = (preset: DownloadPreset) => preset.startsWith('audio-')

const LinkRow = memo(function LinkRow({entry, capture: c, busy}: {entry: CaptureEntry; capture: CaptureModel; busy: boolean}) {
  if (entry.state === 'waiting' || entry.state === 'inspecting')
    return (
      <li className="batch-row is-pending">
        <div className="thumb thumb-sm sk" />
        <div className="batch-text">
          <span className="batch-title muted">{entry.url}</span>
          <span className="batch-meta">
            {entry.state === 'inspecting' ? (
              <>
                <Spinner size={12} /> Reading formats
              </>
            ) : (
              'Waiting'
            )}
          </span>
        </div>
      </li>
    )
  if (entry.state === 'error')
    return (
      <li className="batch-row is-error">
        <span className="thumb thumb-sm thumb-error">
          <Icon name="warning" size={18} />
        </span>
        <div className="batch-text">
          <span className="batch-title">{entry.url}</span>
          <span className="batch-meta error-text">{entry.error}</span>
        </div>
        <div className="batch-actions">
          <Button size="sm" icon="retry" onClick={() => c.retry(entry.url)} disabled={busy}>
            Try again
          </Button>
          <IconButton icon="close" size="sm" label={`Remove ${entry.url}`} onClick={() => c.removeEntry(entry.url)} />
        </div>
      </li>
    )
  if (entry.state === 'collection')
    return (
      <li className="batch-row">
        <span className="thumb thumb-sm thumb-collection">
          <Icon name="stack" size={18} />
        </span>
        <div className="batch-text">
          <span className="batch-title">{entry.collection?.title || 'Collection'}</span>
          <span className="batch-meta">{[entry.collection?.uploader, hostOf(entry.url), 'Playlist or collection'].filter(Boolean).join(' · ')}</span>
        </div>
        <div className="batch-actions">
          <Button size="sm" onClick={() => c.openCollection(entry.collection!.url, entry.collection!.title)}>
            Review entries
          </Button>
          <IconButton icon="close" size="sm" label="Remove collection" onClick={() => c.removeEntry(entry.url)} />
        </div>
      </li>
    )
  const media = entry.media!
  const choice = entry.choice!
  const size = estimateLabel(media, choice)
  return (
    <li className="batch-row">
      <Thumbnail url={media.thumbnail} kind={media.video.length ? 'video' : 'audio'} label={media.title} className="thumb-sm" />
      <div className="batch-text">
        <span className="batch-title" title={media.title}>
          {media.title}
        </span>
        <span className="batch-meta">
          <Badge>{media.provider}</Badge>
          {[media.uploader, formatDuration(media.duration)].filter(Boolean).join(' · ')}
          {media.match && <span className="accent-text"> · {media.match.source === 'youtube' ? 'Another upload' : 'Matched from YouTube Music'}</span>}
          {entry.error && <span className="error-text"> · {entry.error}</span>}
        </span>
      </div>
      <div className="batch-actions">
        <Select
          label={`Format for ${media.title}`}
          value={quickValue(choice)}
          options={quickOptions(media)}
          onChange={(value) => c.setChoice(entry.url, fromQuickValue(media, choice, value))}
          disabled={busy}
        />
        <span className="batch-size tabular">{entry.state === 'adding' ? <Spinner size={13} /> : size || '—'}</span>
        <IconButton icon="close" size="sm" label={`Remove ${media.title}`} onClick={() => c.removeEntry(entry.url)} disabled={busy} />
      </div>
    </li>
  )
})

export function LinksReview({capture: c, open, onClose}: {capture: CaptureModel; open: boolean; onClose: () => void}) {
  const meta = useMeta()
  const [bulk, setBulk] = useState<DownloadPreset>('best')
  const ready = c.entries.filter((entry) => entry.state === 'media' || entry.state === 'adding')
  const failed = c.entries.filter((entry) => entry.state === 'error').length
  const pending = c.entries.filter((entry) => entry.state === 'waiting' || entry.state === 'inspecting').length
  const total = ready.reduce((sum, entry) => sum + (estimate(entry.media!, entry.choice!).size || 0), 0)
  const optionError = validateOptions(c.options)
  const audio = ready.length > 0 && ready.every((entry) => entry.choice!.tab === 'audio')
  const presets = downloadPresets.filter((preset) => isAudio(preset.id) === audio)
  const apply = (preset: DownloadPreset) => {
    setBulk(preset)
    for (const entry of ready) c.setChoice(entry.url, choiceForPreset(entry.media!, entry.choice!, preset))
  }
  return (
    <PreviewWindow
      open={open}
      onClose={onClose}
      title={plural(c.entries.length, 'link')}
      facts={<span role="status">{[`${ready.length} ready`, pending ? `${pending} reading` : '', failed ? `${failed} could not be read` : ''].filter(Boolean).join(' · ')}</span>}
      mainLabel={`Links · ${c.entries.length}`}
      extras={{changed: extrasChanged(c.options, meta.settings.options), content: <ExtrasGroups mode="collection" options={c.options} onChange={c.setOptions} />}}
      error={optionError}
      busy={c.adding}
      footer={
        <>
          <SaveTo url={ready[0]?.media?.url} value={c.space} onChange={c.setSpace} mixed={new Set(ready.map((entry) => entry.media && defaultFor(entry.media.url))).size > 1} />
          <AddToPlaylist value={c.playlist} onChange={c.setPlaylist} />
          <span className="foot-gap" />
          <DownloadOn jobs={() => ready.map((entry) => ({url: entry.media!.url, format: formatRequest(entry.media!, entry.choice!), options: c.options}))} onSent={c.dismiss} disabled={!ready.length || !!optionError} />
          <Button variant="primary" size="lg" icon="download" className="download-btn" disabled={!ready.length || !!optionError} loading={c.adding} onClick={() => void c.enqueue()}>
            Download {ready.length > 1 ? `all ${ready.length}` : ready.length ? '1' : ''}
            {total > 0 && (
              <>
                {' '}
                <span className="btn-sep">·</span> <span className="tabular">≈{formatBytes(total)}</span>
              </>
            )}
          </Button>
        </>
      }
    >
      <FormatQuality
        format={audio ? 'audio' : 'video'}
        formatOptions={[{value: 'video', label: 'Video'}, {value: 'audio', label: 'Audio'}]}
        onFormat={(value) => apply(value === 'audio' ? 'audio-mp3' : 'best')}
        quality={presets.some((preset) => preset.id === bulk) ? bulk : presets[0].id}
        qualityOptions={presets.map((preset) => ({value: preset.id, label: preset.label}))}
        onQuality={(value) => apply(value as DownloadPreset)}
        disabled={!ready.length}
      />
      <ul className="batch-list" aria-label="Links">
        {c.entries.map((entry) => (
          <LinkRow key={entry.url} entry={entry} capture={c} busy={c.adding} />
        ))}
      </ul>
      {(pending > 0 || failed > 0) && <p className="preview-hint">{pending > 0 ? 'Ready links can be added now; the rest keep reading.' : 'Links that could not be read stay here to try again.'}</p>}
      <Button variant="ghost" onClick={c.dismiss} className="dismiss-all">
        Dismiss all
      </Button>
    </PreviewWindow>
  )
}
