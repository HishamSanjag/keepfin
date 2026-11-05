// The preview of a playlist, album or channel: choose what to keep, follow it, filter and tick the entries, then download them.
// Entries are found a page at a time; their formats are only resolved when the selected downloads start.
import {useCallback, useEffect, useRef} from 'react'
import {downloadPresets, type DownloadPreset} from '../../shared/contracts'
import {SaveTo} from '../capture/SaveTo'
import {DownloadOn, presetToFormat} from '../devices/DownloadOn'
import {hostOf, plural, providerFromUrl} from '../format'
import {spaceName} from '../library/libStore'
import {useCollection, type CollectionSource} from '../collection/useCollection'
import {useMeta} from '../store'
import {Button, InlineAlert, Spinner} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {Thumbnail} from '../ui/Thumbnail'
import {AddToPlaylist} from './AddToPlaylist'
import {EntryRow} from './EntryRow'
import {ExtrasGroups} from './Extras'
import {extrasChanged, validateOptions} from './extrasModel'
import {FormatQuality} from './FormatQuality'
import {PreviewWindow} from './PreviewWindow'
import {SyncRow} from './SyncRow'

type Mode = 'video' | 'audio' | 'both'

const isAudio = (preset: DownloadPreset) => preset.startsWith('audio-')

export function ListReview({source, open, onClose, onDone, initialSpace}: {source: CollectionSource; open: boolean; onClose: () => void; onDone: () => void; initialSpace?: string}) {
  const c = useCollection(source, initialSpace)
  const meta = useMeta()
  const toggleRef = useRef(c.toggle)
  toggleRef.current = c.toggle
  const onToggle = useCallback((url: string, index: number, range: boolean) => toggleRef.current(url, index, range), [])
  const headAll = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (headAll.current) headAll.current.indeterminate = c.someVisibleSelected && !c.allVisibleSelected
  }, [c.someVisibleSelected, c.allVisibleSelected])
  const d = c.discovery
  const title = d?.title || source.title || 'Collection'
  const uploader = d?.uploader || source.uploader
  const provider = providerFromUrl(source.url) || hostOf(source.url)
  const optionError = validateOptions(c.options)
  const count = c.entries.length
  const audio = isAudio(c.preset)
  const mode: Mode = audio ? (c.offerVideos && c.keepVideos ? 'both' : 'audio') : 'video'
  const presets = downloadPresets.filter((preset) => isAudio(preset.id) === audio)
  const pick = (next: Mode) => {
    if (next === 'video') c.setPreset(meta.settings.defaultPreset && !isAudio(meta.settings.defaultPreset) ? meta.settings.defaultPreset : 'best')
    else if (!audio) c.setPreset('audio-mp3')
    c.setKeepVideos(next === 'both')
  }
  const status = !d
    ? c.startError
      ? 'Couldn’t open this collection.'
      : 'Opening collection…'
    : d.state === 'discovering'
      ? `Found ${count.toLocaleString()} · still searching…`
      : d.state === 'cancelled'
        ? `Stopped · ${plural(count, 'entry', 'entries')} found`
        : d.state === 'failed'
          ? `${plural(count, 'entry', 'entries')} found before an error`
          : plural(count, 'entry', 'entries')
  const art = c.entries[0]?.thumbnail
  return (
    <PreviewWindow
      open={open}
      onClose={onClose}
      cover={
        art ? (
          <Thumbnail url={art} kind={c.music ? 'audio' : 'video'} label={title} className="preview-cover is-square" />
        ) : (
          <span className="thumb preview-cover is-square">
            <Icon name="stack" size={24} />
          </span>
        )
      }
      title={title}
      facts={
        <>
          {[provider, c.music ? 'Album or playlist' : 'Collection', uploader].filter(Boolean).join(' · ')}
          <span className="preview-status" role="status">
            {(c.discovering || (!d && !c.startError)) && <Spinner size={13} />}
            <span className="tabular">{status}</span>
            {c.discovering && (
              <Button size="sm" variant="ghost" icon="stop" onClick={() => void c.stop()}>
                Stop
              </Button>
            )}
          </span>
        </>
      }
      mainLabel={count ? `${c.music ? 'Songs' : 'Videos'} · ${count.toLocaleString()}` : c.music ? 'Songs' : 'Videos'}
      extras={{
        changed: extrasChanged(c.options, c.baseOptions, !c.grouped),
        content: <ExtrasGroups mode="collection" options={c.options} onChange={c.setOptions} queue={{grouped: c.grouped, onChange: c.setGrouped, disabled: c.adding}} />,
      }}
      error={optionError}
      busy={c.adding}
      footer={
        <>
          <SaveTo url={source.url} value={c.space} onChange={c.setSpace} />
          <AddToPlaylist value={c.playlist} onChange={c.setPlaylist} />
          <span className="foot-gap" />
          <DownloadOn
            jobs={() => c.entries.filter((entry) => c.selected.has(entry.url) && !c.queued.has(entry.url)).map((entry) => ({url: entry.url, format: presetToFormat(c.preset), options: c.options}))}
            onSent={onDone}
            disabled={!c.selectedCount || !!optionError}
          />
          <Button variant="primary" size="lg" icon="download" className="download-btn" loading={c.adding} disabled={!c.selectedCount || !!optionError} onClick={() => void c.enqueue().then((added) => added && onDone())}>
            {c.selectedCount ? `Download ${c.selectedCount.toLocaleString()} selected` : 'Select entries to download'}
          </Button>
        </>
      }
    >
      {mode === 'both' && <p className="preview-hint">Music videos are kept where there is one. They are large, about 50 to 100 MB each.</p>}
      {c.matched && (
        <InlineAlert tone="info">
          {provider} audio is DRM-protected. Each track is matched on YouTube Music and tagged with the original title, artist and cover.
        </InlineAlert>
      )}
      {d?.notice && <InlineAlert tone="info">{d.notice}</InlineAlert>}
      {c.startError && <InlineAlert title="Couldn’t open this collection">{c.startError}</InlineAlert>}
      {d?.error && (
        <InlineAlert tone="error" title="Discovery failed">
          {d.error} Entries found so far are kept.
        </InlineAlert>
      )}
      {c.syncable && <SyncRow on={c.keepInSync} onChange={c.setKeepInSync} hours={c.syncHours} onHours={c.setSyncHours} disabled={c.adding} />}
      <FormatQuality
        format={mode}
        formatOptions={[
          {value: 'video', label: 'Video', disabled: c.matched, title: c.matched ? `${provider} songs are saved as audio.` : undefined},
          {value: 'audio', label: 'Audio'},
          ...(c.canKeepVideos ? [{value: 'both', label: 'Both'}] : []),
        ]}
        onFormat={(value) => pick(value as Mode)}
        quality={c.preset}
        qualityOptions={presets.map((preset) => ({value: preset.id, label: preset.label}))}
        onQuality={(value) => c.setPreset(value as DownloadPreset)}
      />
      <p className="preview-hint">Formats resolve per item when each download starts.</p>
      {c.existingCount > 0 && (
        <InlineAlert
          tone="info"
          actions={
            <Button size="sm" onClick={() => c.setIncludeExisting(!c.includeExisting)} aria-pressed={c.includeExisting}>
              {c.includeExisting ? 'Skip them again' : 'Select all anyway'}
            </Button>
          }
        >
          {c.existingCount.toLocaleString()} {c.existingCount === 1 ? 'song is' : 'songs are'} already in {spaceName(c.targetSpace)} and left out of Select all.
          {c.includeExisting ? ' Select all now includes them.' : ' You can still tick any of them yourself.'}
        </InlineAlert>
      )}
      {count > 0 && (
        <>
          <div className="collection-toolbar">
            <label className="select-all">
              <input ref={headAll} type="checkbox" className="check" checked={c.allVisibleSelected} disabled={c.adding} onChange={(event) => c.selectAll(event.target.checked)} />
              <span>{c.query ? 'Select matches' : 'Select all'}</span>
            </label>
            <div className="search">
              <Icon name="search" size={15} />
              <input
                className="input"
                type="search"
                aria-label="Filter entries"
                placeholder={`Filter ${count.toLocaleString()} entries`}
                value={c.query}
                onChange={(event) => c.setQuery(event.target.value)}
              />
            </div>
            <span className="toolbar-count tabular">{c.selectedCount ? `${c.selectedCount.toLocaleString()} selected` : 'Shift-click selects a range'}</span>
            {c.selectedCount > 0 && (
              <button type="button" className="link-btn" onClick={() => c.selectAll(false)}>
                Clear
              </button>
            )}
          </div>
          <ul className="entries" aria-label="Collection entries">
            {c.filtered.map((entry, index) => (
              <EntryRow key={entry.url} entry={entry} index={index} checked={c.selected.has(entry.url)} queued={c.queued.has(entry.url)} disabled={c.adding} music={c.music} existing={c.existing[entry.url]} onToggle={onToggle} />
            ))}
            {c.discovering && (
              <li className="entry entry-loading" aria-hidden="true">
                <span className="check-placeholder" />
                <span className="sk sk-line" style={{width: '40%'}} />
              </li>
            )}
          </ul>
          {!c.filtered.length && <p className="empty-filter">No entries match “{c.query}”.</p>}
          {d?.truncated && !c.discovering && (
            <div className="load-more">
              <span>
                Showing positions 1–{d.limit.toLocaleString()}. {c.music ? 'More tracks' : 'More entries'} are available.
              </span>
              <Button size="sm" icon="plus" loading={c.loadingMore} onClick={() => void c.more()}>
                Load more
              </Button>
            </div>
          )}
        </>
      )}
      {!count && d && !c.discovering && !d.error && <InlineAlert tone="warning">This collection has no downloadable entries.</InlineAlert>}
    </PreviewWindow>
  )
}
