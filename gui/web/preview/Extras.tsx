// The Extras tab: one group per thing, in plain words. The same groups fill the download defaults in Settings.
import {useEffect, useState, type ReactNode} from 'react'
import {subtitleLanguagePattern, type DownloadOptions, type SponsorBlockMode, type SubtitleTrack} from '../../shared/contracts'
import {formatDuration, languageName, parseClock} from '../format'
import {Segmented, Switch} from '../ui/controls'
import {noSubtitles} from './extrasModel'

const commonLanguages = ['en', 'es', 'fr', 'de', 'pt', 'ja', 'ko', 'ar']

function Group({name, note, children}: {name: string; note?: ReactNode; children: ReactNode}) {
  return (
    <section className="extra" aria-label={name}>
      <strong className="extra-name">{name}</strong>
      {note && <p className="extra-note">{note}</p>}
      <div className="extra-body">{children}</div>
    </section>
  )
}

/** Choices that can each be on or off: a row of pressed or unpressed buttons. */
function Pills({label, items}: {label: string; items: {label: string; on: boolean; set: (on: boolean) => void}[]}) {
  return (
    <div className="chips extra-pills" role="group" aria-label={label}>
      {items.map((item) => (
        <button key={item.label} type="button" className={`chip${item.on ? ' on' : ''}`} aria-pressed={item.on} onClick={() => item.set(!item.on)}>
          {item.label}
        </button>
      ))}
    </div>
  )
}

function ClockInput({label, value, onCommit, placeholder}: {label: string; value?: number; onCommit: (seconds: number | undefined) => void; placeholder: string}) {
  const [text, setText] = useState(value === undefined ? '' : formatDuration(value) || '0:00')
  useEffect(() => setText(value === undefined ? '' : formatDuration(value) || '0:00'), [value])
  const parsed = parseClock(text)
  const bad = parsed !== undefined && !Number.isFinite(parsed)
  return (
    <label className="clock-input">
      <span className="field-label">{label}</span>
      <input
        className={`input tabular${bad ? ' invalid' : ''}`}
        inputMode="numeric"
        placeholder={placeholder}
        value={text}
        aria-invalid={bad}
        onChange={(event) => {
          setText(event.target.value)
          onCommit(parseClock(event.target.value))
        }}
      />
    </label>
  )
}

function SubtitleChoices({options, set, tracks}: {options: DownloadOptions; set: (change: Partial<DownloadOptions>) => void; tracks: SubtitleTrack[]}) {
  const subs = options.subtitles ?? noSubtitles
  const change = (next: Partial<typeof subs>) => set({subtitles: {...subs, ...next}})
  const picked = subs.languages.split(',').map((item) => item.trim()).filter(Boolean)
  const available = [...new Set(tracks.filter((track) => !track.auto).map((track) => track.lang))]
  const chips = [...new Set([...available.slice(0, 8), ...commonLanguages])].slice(0, 10)
  const toggle = (code: string) => change({languages: (picked.includes(code) ? picked.filter((item) => item !== code) : [...picked, code]).join(',')})
  return (
    <>
      <Segmented<'off' | 'on'> label="Subtitles" value={subs.enabled ? 'on' : 'off'} onChange={(value) => change({enabled: value === 'on'})} options={[{value: 'off', label: 'Off'}, {value: 'on', label: 'Save subtitles'}]} />
      {subs.enabled && (
        <div className="extra-more">
          <label className="field">
            <span className="field-label">Languages</span>
            <input
              className={`input${subtitleLanguagePattern.test(subs.languages || '') ? '' : ' invalid'}`}
              value={subs.languages}
              spellCheck={false}
              placeholder="en,es or all"
              aria-invalid={!subtitleLanguagePattern.test(subs.languages || '')}
              onChange={(event) => change({languages: event.target.value.replace(/\s+/g, '')})}
            />
          </label>
          <div className="chips" role="group" aria-label="Common subtitle languages">
            {chips.map((code) => (
              <button key={code} type="button" className={`chip${picked.includes(code) ? ' on' : ''}`} aria-pressed={picked.includes(code)} title={languageName(code)} onClick={() => toggle(code)}>
                {code}
                {available.includes(code) && <span className="chip-dot" aria-label="available" />}
              </button>
            ))}
            <button type="button" className={`chip${picked.includes('all') ? ' on' : ''}`} aria-pressed={picked.includes('all')} onClick={() => change({languages: picked.includes('all') ? 'en' : 'all'})}>
              all
            </button>
          </div>
          <label className="extra-switch">
            <Switch label="Include automatic captions" checked={subs.auto} onChange={(auto) => change({auto})} />
            <span>Automatic captions too</span>
          </label>
          <label className="extra-switch">
            <Switch label="Put subtitles inside the file" checked={subs.embed} onChange={(embed) => change({embed})} />
            <span>Put them inside the file</span>
          </label>
          {tracks.length > 0 ? (
            <p className="extra-note">
              {available.length ? `Available: ${available.slice(0, 6).map((code) => languageName(code)).join(', ')}${available.length > 6 ? '…' : ''}` : 'Only automatic captions are available.'}
            </p>
          ) : null}
        </div>
      )}
    </>
  )
}

export function ExtrasGroups({
  options,
  onChange,
  mode = 'media',
  duration,
  subtitles,
  chapters = 0,
  queue,
}: {
  options: DownloadOptions
  onChange: (options: DownloadOptions) => void
  /** media: one item; collection: many, so no cut and no per-item facts; defaults: the person's settings. */
  mode?: 'media' | 'collection' | 'defaults'
  duration?: number
  /** The subtitle tracks the link lists (media only). */
  subtitles?: SubtitleTrack[]
  chapters?: number
  /** Collections only: how the downloads are queued. */
  queue?: {grouped: boolean; onChange: (grouped: boolean) => void; disabled?: boolean}
}) {
  const set = (change: Partial<DownloadOptions>) => onChange({...options, ...change})
  const cut = options.section?.start !== undefined || options.section?.end !== undefined
  const [cutting, setCutting] = useState(cut)
  const embeds = [options.embedThumbnail && 'Cover', options.embedMetadata && 'tags', options.embedChapters && 'chapters'].filter(Boolean) as string[]
  const inFile = embeds.length ? `${embeds.join(', ').replace(/, ([^,]*)$/, ' and $1')} ${embeds.length > 1 ? 'are' : 'is'} on.` : 'Nothing extra goes in the file.'
  return (
    <div className="extras">
      {queue && (
        <Group name="In the queue" note="How the downloads show while they run.">
          <Segmented<'together' | 'separate'>
            label="How to queue the selected entries"
            value={queue.grouped ? 'together' : 'separate'}
            disabled={queue.disabled}
            onChange={(value) => queue.onChange(value === 'together')}
            options={[
              {value: 'together', label: 'Keep together', title: 'One collapsible set in the queue and library'},
              {value: 'separate', label: 'Separate items', title: 'Each entry is its own download'},
            ]}
          />
        </Group>
      )}
      <Group name="Subtitles" note="Saved beside the video, or inside it.">
        <SubtitleChoices options={options} set={set} tracks={mode === 'media' ? subtitles ?? [] : []} />
      </Group>
      {mode === 'media' && (
        <Group name="Cut a part" note="Keep only the part you want.">
          <Segmented<'whole' | 'part'>
            label="Cut a part"
            value={cutting || cut ? 'part' : 'whole'}
            onChange={(value) => {
              setCutting(value === 'part')
              if (value === 'whole') set({section: undefined})
            }}
            options={[{value: 'whole', label: 'Whole'}, {value: 'part', label: 'Choose start and end'}]}
          />
          {(cutting || cut) && (
            <div className="clock-pair">
              <ClockInput label="Start" placeholder="0:00" value={options.section?.start} onCommit={(start) => set({section: {...options.section, start}})} />
              <span className="clock-dash" aria-hidden="true">–</span>
              <ClockInput label="End" placeholder={formatDuration(duration) || 'end'} value={options.section?.end} onCommit={(end) => set({section: {...options.section, end}})} />
            </div>
          )}
        </Group>
      )}
      <Group name="Sponsor parts" note="Parts other viewers marked as sponsored on YouTube. Mark adds chapters; Remove cuts them out.">
        <Segmented<SponsorBlockMode>
          label="Sponsor parts"
          value={options.sponsorBlock || 'off'}
          onChange={(sponsorBlock) => set({sponsorBlock})}
          options={[{value: 'off', label: 'Keep'}, {value: 'mark', label: 'Mark'}, {value: 'remove', label: 'Remove'}]}
        />
      </Group>
      <Group name="Chapters" note={mode === 'media' ? (chapters ? `This one has ${chapters} chapters.` : 'Split a long recording into one file per chapter.') : 'Split long mixes and albums by chapter.'}>
        <Segmented<'one' | 'each'>
          label="Split into files by chapter"
          value={options.splitChapters ? 'each' : 'one'}
          onChange={(value) => set({splitChapters: value === 'each'})}
          options={[{value: 'one', label: 'One file'}, {value: 'each', label: 'One file per chapter'}]}
        />
      </Group>
      <Group name="What goes in the file" note={inFile}>
        <Pills
          label="What goes in the file"
          items={[
            {label: 'Cover', on: !!options.embedThumbnail, set: (embedThumbnail) => set({embedThumbnail})},
            {label: 'Tags', on: !!options.embedMetadata, set: (embedMetadata) => set({embedMetadata})},
            {label: 'Chapters', on: !!options.embedChapters, set: (embedChapters) => set({embedChapters})},
          ]}
        />
      </Group>
      <Group name="Also save beside the file" note="Extra files next to the download.">
        <Pills
          label="Also save beside the file"
          items={[
            {label: 'Cover image', on: !!options.writeThumbnail, set: (writeThumbnail) => set({writeThumbnail})},
            {label: 'Description', on: !!options.writeDescription, set: (writeDescription) => set({writeDescription})},
            {label: 'Info file', on: !!options.writeInfoJson, set: (writeInfoJson) => set({writeInfoJson})},
          ]}
        />
      </Group>
    </div>
  )
}
