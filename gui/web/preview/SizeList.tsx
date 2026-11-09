// "What to save" for a video or a song: a Format row, then one list for that format with every real option once and no fold. Video lists
// every size the inspection found, best first; a lossy audio format lists its qualities with estimated sizes; Original lists the streams.
// A row is always selected, and the Download button names the size of the selected row. Nothing is listed that the engine did not offer.
import {useRef, type KeyboardEvent} from 'react'
import type {AudioQuality, MediaInfo} from '../../shared/contracts'
import {containerAfterPick, containerOffer, picksForTarget, shownFps} from '../../shared/core/choice'
import {audioQualities, audioTargets, estimate, picksOf, qualityApplies, selectedVideo, withPicks, type Choice} from '../capture/choice'
import {audioCodecLabel, formatBytes, languageName, videoCodecLabel, videoLabel} from '../format'
import {Badge, Segmented, Select} from '../ui/controls'

type Row = {key: string; label: string; detail: string; aria: string; badge?: string; note?: string; title?: string; size?: number; approx?: boolean; checked: boolean; select: () => void}

const sizeText = (row: Pick<Row, 'size' | 'approx'>) => {
  const text = formatBytes(row.size)
  return text ? `${row.approx ? '≈' : ''}${text}` : ''
}

/** Arrow-key radio list; selection follows focus like native radios. */
function Rows({label, rows}: {label: string; rows: Row[]}) {
  const refs = useRef<(HTMLDivElement | null)[]>([])
  const checkedIndex = rows.findIndex((row) => row.checked)
  const stop = checkedIndex >= 0 ? checkedIndex : 0
  const key = (event: KeyboardEvent, index: number) => {
    if (event.key === ' ' || event.key === 'Enter') {
      event.preventDefault()
      rows[index].select()
      return
    }
    const step = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 0
    if (!step) return
    event.preventDefault()
    const next = Math.max(0, Math.min(rows.length - 1, index + step))
    refs.current[next]?.focus()
    rows[next].select()
  }
  return (
    <div className="size-rows" role="radiogroup" aria-label={label}>
      {rows.map((row, index) => {
        const size = sizeText(row)
        return (
          <div
            key={row.key}
            ref={(element) => {
              refs.current[index] = element
            }}
            role="radio"
            aria-checked={row.checked}
            aria-label={row.aria}
            tabIndex={index === stop ? 0 : -1}
            className={`size-row${row.checked ? ' checked' : ''}`}
            data-row={row.key}
            title={row.title}
            onClick={row.select}
            onKeyDown={(event) => key(event, index)}
          >
            <span className="radio-dot" aria-hidden="true" />
            <strong className="size-row-label">
              {row.label}
              {row.note && <span className="size-row-note">{row.note}</span>}
              {row.badge && <Badge tone="accent">{row.badge}</Badge>}
            </strong>
            <span className="size-row-detail">{row.detail}</span>
            <span className="size-row-size tabular">{size || <span className="size-unknown">Size unknown</span>}</span>
          </div>
        )
      })}
    </div>
  )
}

function videoRows(media: MediaInfo, choice: Choice, onChange: (choice: Choice) => void): Row[] {
  const current = selectedVideo(media, choice)?.id
  return media.video.map((option, index) => ({
    key: option.id,
    label: `${option.height}p`,
    detail: [shownFps(option) ? `${shownFps(option)} fps` : '', option.hdr ? 'HDR' : '', videoCodecLabel(option.codec), option.ext.toUpperCase()].filter(Boolean).join(' · '),
    aria: `${videoLabel(option)}${option.convertedFrom ? ' converted' : ''}${option.hdr ? ' HDR' : ''}, ${videoCodecLabel(option.codec)}${option.size ? `, ${sizeText({size: option.size, approx: option.sizeApprox})}` : ''}`,
    badge: index === 0 ? 'Best' : undefined,
    note: option.convertedFrom ? 'converted' : undefined,
    title: option.convertedFrom ? `Keepfin makes this size from the ${option.convertedFrom}p version after downloading` : undefined,
    size: option.size || undefined,
    approx: option.sizeApprox,
    checked: current === option.id,
    select: () => onChange({...choice, tab: 'video', videoId: option.id, container: containerAfterPick(media, picksOf(choice), option.id)}),
  }))
}

const qualityLabel = (quality: AudioQuality) => (quality === 'best' ? 'Best' : `${quality} kbps`)

function qualityRows(media: MediaInfo, choice: Choice, onChange: (choice: Choice) => void): Row[] {
  return audioQualities.map(({value: quality}) => {
    const {size, approx} = estimate(media, {...choice, quality})
    const target = audioTargets.find((item) => item.value === choice.target)?.label ?? choice.target
    return {
      key: quality,
      label: qualityLabel(quality),
      detail: quality === 'best' ? `${target} · the most the source allows` : target,
      aria: `${qualityLabel(quality)}, ${target}${size ? `, ${sizeText({size, approx})}` : ''}`,
      size,
      approx,
      checked: choice.quality === quality,
      select: () => onChange({...choice, quality}),
    }
  })
}

function streamRows(media: MediaInfo, choice: Choice, onChange: (choice: Choice) => void): Row[] {
  const current = choice.audioSource ?? media.audio[0]?.formatId
  return media.audio.map((option, index) => {
    const label = option.abr ? `${Math.round(option.abr)} kbps` : option.ext.toUpperCase()
    const detail = [languageName(option.language) || 'Default language', option.note, audioCodecLabel(option.codec), option.ext.toUpperCase()].filter(Boolean).join(' · ')
    return {
      key: option.formatId,
      label,
      detail,
      aria: `${label}, ${detail}${option.size ? `, ${sizeText({size: option.size, approx: option.sizeApprox})}` : ''}`,
      badge: index === 0 ? 'Best' : undefined,
      size: option.size || undefined,
      approx: option.sizeApprox,
      checked: current === option.formatId,
      select: () => onChange({...choice, audioSource: index === 0 ? undefined : option.formatId}),
    }
  })
}

function VideoFormat({media, choice, onChange}: {media: MediaInfo; choice: Choice; onChange: (choice: Choice) => void}) {
  const current = selectedVideo(media, choice)
  const tracks = media.audio.filter((option) => option.language || option.note)
  const showTracks = current && !current.hasAudio && media.audio.length > 1 && tracks.length > 0
  const labels = {mp4: 'MP4', mkv: 'MKV', webm: 'WebM'}
  return (
    <div className="format-row">
      <span className="field-label">Format</span>
      <Segmented
        label="Container"
        size="sm"
        value={choice.container}
        onChange={(container) => onChange({...choice, container})}
        options={containerOffer(media, picksOf(choice)).map((item) => ({value: item.value, label: labels[item.value], disabled: !item.allowed, title: item.reason}))}
      />
      {showTracks && (
        <Select
          label="Audio track"
          value={choice.audioTrack || ''}
          onChange={(audioTrack) => onChange({...choice, audioTrack: audioTrack || undefined})}
          options={[
            {value: '', label: 'Audio track: best available'},
            ...media.audio.map((option) => ({
              value: option.formatId,
              label: [languageName(option.language) || 'Unknown language', option.note, audioCodecLabel(option.codec), option.abr ? `${Math.round(option.abr)} kbps` : ''].filter(Boolean).join(' · '),
            })),
          ]}
        />
      )}
    </div>
  )
}

function AudioFormat({choice, onChange}: {choice: Choice; onChange: (choice: Choice) => void}) {
  // the order the owner asked for: Original first, then the lossy and lossless formats
  const order = ['original', 'mp3', 'm4a', 'opus', 'flac', 'wav']
  const targets = order.map((value) => audioTargets.find((item) => item.value === value)!)
  return (
    <div className="format-row">
      <span className="field-label">Format</span>
      <Segmented
        label="Audio format"
        value={choice.target}
        onChange={(target) => onChange(withPicks(choice, picksForTarget(picksOf(choice), target)))}
        options={targets.map((item) => ({value: item.value, label: item.label}))}
      />
    </div>
  )
}

/** The Format row and the one list under it for the Video or Audio choice. */
export function SizeList({media, choice, onChange}: {media: MediaInfo; choice: Choice; onChange: (choice: Choice) => void}) {
  if (choice.tab === 'video') {
    const current = selectedVideo(media, choice)
    return (
      <div className="sizes">
        <VideoFormat media={media} choice={choice} onChange={onChange} />
        {media.video.length ? (
          <Rows label="Video quality" rows={videoRows(media, choice, onChange)} />
        ) : (
          <p className="picker-note">This link doesn’t list its formats; the best available video and audio are saved.</p>
        )}
        {current?.hasAudio && <p className="picker-note">This quality is a single stream with audio included.</p>}
      </div>
    )
  }
  const lossy = qualityApplies(choice.target)
  const streams = choice.target === 'original' && media.audio.length > 0
  const target = audioTargets.find((item) => item.value === choice.target)
  return (
    <div className="sizes">
      <AudioFormat choice={choice} onChange={onChange} />
      {lossy && <Rows label="Audio quality" rows={qualityRows(media, choice, onChange)} />}
      {streams && <Rows label="Original audio streams" rows={streamRows(media, choice, onChange)} />}
      {!lossy && !streams && (
        <p className="picker-note">
          {choice.target === 'original' ? 'Keeps the source stream exactly as published. No re-encoding.' : `${target?.label} is lossless: it preserves the source quality, and file size depends on the source.`}
        </p>
      )}
    </div>
  )
}
