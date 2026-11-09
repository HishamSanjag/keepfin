import {useEffect, useState} from 'react'
import '../../styles/copies.css'
import '../../styles/where.css'
import {useNav, navigate} from '../../nav'
import type {CopiesState, CopyMode, CopyPlaceView} from '../../../shared/copies'
import {librarySafety, type OnePlace} from '../../../shared/core/safety'
import type {WhereKind, WherePlace} from '../../../shared/where'
import {messageOf} from '../../bridge'
import {plural} from '../../format'
import {Button, InlineAlert} from '../../ui/controls'
import {Dialog} from '../../ui/Dialog'
import {Icon} from '../../ui/Icon'
import {Group} from '../../ui/SettingCard'
import {agoWords, copyLine, holdsWords, sizeWords, statusTone, statusWords} from '../whereText'
import {useNow, useWhere} from '../where/useWhere'
import {AddCopy} from './AddCopy'
import {syncCopies} from './copiesApi'
import {copyFiles, holdsText, modeWords, statusWords as copyStatus} from './copyText'
import {CloudAccountCards, type CloudNote} from './CloudAccounts'
import {CopyDialog} from './CopyDialog'
import {CopyMatrix} from './CopyMatrix'
import {ReviewDialog} from './NeedsReview'
import type {useCopies} from './useCopies'

type Copies = ReturnType<typeof useCopies>
/** Settings anchor that opens Keep a copy with a space chosen (Devices › Where your files are). */
const keepCopyPrefix = 'keep-copy:'
const iconOf: Record<WhereKind, string> = {this: 'home', pc: 'sidebar', phone: 'pip', drive: 'folder', cloud: 'globe'}
const toneIcon = {safe: 'check', warn: 'warning', quiet: 'info'} as const
const THIS = 'this PC'

type Open = {copy: string} | {add: {path?: string; spaces?: string[]; reason?: string}} | {review: true} | {compare: true} | null

/**
 * Settings → Storage › Your library: one sentence on whether every file is kept in more than one place (decided in the shared core),
 * the places that keep it with the main storage first, and the dialogs they open: a copy, Keep a copy…, the review and the comparison.
 */
export function Places({copies}: {copies: Copies}) {
  const {state, error, reload, set} = copies
  const where = useWhere()
  const now = useNow()
  const [open, setOpen] = useState<Open>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState('')

  // links from elsewhere (Devices, the attention list) open the dialog they name
  const nav = useNav()
  useEffect(() => {
    const anchor = nav.anchor ?? ''
    if (anchor.startsWith(keepCopyPrefix)) setOpen({add: {spaces: anchor.slice(keepCopyPrefix.length) ? [anchor.slice(keepCopyPrefix.length)] : undefined}})
    else if (anchor.startsWith('copy-')) setOpen({copy: anchor.slice('copy-'.length)})
    else if (anchor === 'review') setOpen({review: true})
  }, [nav.anchor, nav.focus])

  if (error && !state)
    return (
      <Group title="Your library">
        <InlineAlert tone="error" title="Could not read the copies" actions={<Button onClick={reload}>Try again</Button>}>
          {error}
        </InlineAlert>
      </Group>
    )
  if (!state || !state.available) return null

  async function syncAll() {
    setBusy(true)
    setActionError('')
    try {
      set(await syncCopies())
    } catch (caught) {
      setActionError(messageOf(caught))
    } finally {
      setBusy(false)
    }
  }

  const here = where?.available ? where : undefined
  const only: OnePlace[] = (here?.singles ?? []).map((s) => ({kind: s.kind, count: s.count, place: s.placeName, here: s.placeId === 'this'}))
  const main = here?.places.find((p) => p.thisDevice)
  const safety = here && main ? librarySafety(here.places.length, main.holds.files?.of ?? main.items, only, THIS) : undefined
  const keepSpaces = [...new Set((here?.singles ?? []).filter((s) => s.placeId === 'this' && s.spaceId).map((s) => s.spaceId!))]
  const onlyHere = only.filter((o) => o.here).reduce((sum, o) => sum + o.count, 0)
  const alone = (here?.places.length ?? 0) <= 1
  const keepCopy = () =>
    setOpen({add: {spaces: alone ? [] : keepSpaces, reason: alone ? `For your library, which is only on ${THIS}` : onlyHere ? `For the ${plural(onlyHere, 'file')} that are only on ${THIS}` : undefined}})
  const copyOpen = open && 'copy' in open ? state.places.find((p) => p.id === open.copy) : undefined
  const reviewCount = state.review.length + state.reviewMore

  return (
    <>
      {/* cards: the summary and each place are cards of their own, not cards inside a box */}
      <Group id="copies" title="Your library" description="Where your library is kept: the main storage on this PC and its copies." cards>
        {safety && (
          <div className="safety" data-tone={safety.tone} role="status">
            <span className="safety-icon" aria-hidden="true">
              <Icon name={toneIcon[safety.tone]} size={22} />
            </span>
            <div className="safety-text">
              <h3>{safety.title}</h3>
              <p>{safety.detail}</p>
            </div>
            {safety.keepCopy ? (
              <Button variant="primary" icon="plus" onClick={keepCopy}>
                Keep a copy…
              </Button>
            ) : null}
          </div>
        )}
        <ul className="where-list" aria-label="Places that keep your library">
          {(here?.places ?? []).map((place) => (
            <li key={place.id}>
              <PlaceRow place={place} now={now} mode={place.link?.to === 'copy' ? state.places.find((p) => p.id === place.link!.id)?.mode : undefined} removal={place.link?.to === 'copy' ? state.places.find((p) => p.id === place.link!.id)?.removal : undefined} onOpen={() => (place.link?.to === 'device' ? navigate('Settings', 'devices', `device-${place.link.id}`) : place.link && setOpen({copy: place.link.id}))} />
            </li>
          ))}
          {/* before "where" has answered: the copies alone */}
          {!here &&
            state.places.map((place) => {
              const status = copyStatus[place.status]
              const counted = copyFiles(place)
              const total = counted.kept + counted.waiting
              const holds = [modeWords[place.mode].name, holdsText(place.holds, state.rows), place.path || place.cloud?.providerName, total > 0 ? (counted.waiting === 0 ? plural(total, 'file') : `${counted.kept.toLocaleString()} of ${plural(total, 'file')}`) : ''].filter(Boolean).join(' · ')
              return (
                <li key={place.id}>
                  <button type="button" className="where-row" aria-label={`${place.name}: ${holds}, ${status.label}. Open the copy`} onClick={() => setOpen({copy: place.id})}>
                    <span className="where-icon" aria-hidden="true">
                      <Icon name={place.cloud ? 'globe' : 'folder'} size={18} />
                    </span>
                    <span className="where-text">
                      <span className="where-name">
                        <strong>{place.name}</strong>
                      </span>
                      <span className="small muted where-line" title={holds}>{holds}</span>
                    </span>
                    <span className="where-state small">
                      <span className="where-status" data-tone={place.removal ? (place.removal.state === 'failed' ? 'warn' : 'quiet') : status.tone === 'success' ? 'good' : status.tone === 'warning' || status.tone === 'danger' ? 'warn' : 'quiet'}>
                        {place.removal ? removalWords(place.removal) : place.status === 'problem' && place.statusText ? place.statusText : status.label}
                      </span>
                    </span>
                    <span className="where-chevron" aria-hidden="true">
                      <Icon name="chevronRight" size={16} />
                    </span>
                  </button>
                </li>
              )
            })}
        </ul>
        {!here && state.places.length === 0 && (
          <p className="muted small">No copies yet. Add a drive, a folder, a cloud account or a paired device and Keepfin keeps it up to date. It can hold everything or just a few spaces and playlists.</p>
        )}
        {state.found && state.found.length > 0 && (
          <InlineAlert tone="info" title="A drive with a Keepfin copy of this library is connected" actions={<Button size="sm" onClick={() => setOpen({add: {path: state.found![0].path}})}>Add it</Button>}>
            {state.found[0].name} is not one of your copies yet.
          </InlineAlert>
        )}
        <div className="copy-bar">
          <Button icon="plus" onClick={() => setOpen({add: {}})}>
            Add a copy
          </Button>
          {state.places.length > 0 && (
            <>
              <Button icon="refresh" loading={busy || state.syncing} disabled={!state.places.some((p) => p.connected) || state.syncing} onClick={() => void syncAll()}>
                {state.syncing ? 'Syncing…' : 'Sync all'}
              </Button>
              <Button variant="ghost" onClick={() => setOpen({compare: true})}>
                Compare copies
              </Button>
            </>
          )}
        </div>
        {actionError && <InlineAlert tone="error">{actionError}</InlineAlert>}
        {/* what the last Sync all or Sync now did, also sent as a notification */}
        {state.report && !state.syncing && (
          <InlineAlert tone={state.report.warn ? 'warning' : 'info'} title={state.report.title}>
            {state.report.detail} <span className="muted">· {agoWords(state.report.at, now)}</span>
          </InlineAlert>
        )}
        {reviewCount > 0 && (
          <button type="button" className="copy-review-link" onClick={() => setOpen({review: true})}>
            <Icon name="warning" size={17} />
            <span>
              <strong>{reviewCount === 1 ? '1 file on your copies needs a decision.' : `${reviewCount.toLocaleString()} files on your copies need a decision.`}</strong> <span className="muted">Nothing is lost while they wait.</span>
            </span>
            <span className="copy-review-link-action">Review…</span>
          </button>
        )}
      </Group>

      {copyOpen && <CopyDialog key={copyOpen.id} place={copyOpen} state={state} onState={set} onReview={() => setOpen({review: true})} onClose={() => setOpen(null)} />}
      {open && 'add' in open && (
        <AddCopy
          state={state}
          initialPath={open.add.path}
          initialSpaces={open.add.spaces}
          reason={open.add.reason}
          onClose={() => setOpen(null)}
          onState={set}
          onAdded={(next) => {
            set(next)
            setOpen(null)
          }}
        />
      )}
      {open && 'review' in open && <ReviewDialog state={state} onState={set} onClose={() => setOpen(null)} />}
      {open && 'compare' in open && (
        <Dialog className="compare-dialog" labelledBy="compare-title" title="Compare copies" onClose={() => setOpen(null)} footer={<Button onClick={() => setOpen(null)}>Done</Button>}>
          <CopyMatrix state={state} onState={set} />
        </Dialog>
      )}
    </>
  )
}

/** A copy being removed, or that could not be: the row says so in place of its sync status. */
const removalWords = (removal: NonNullable<CopyPlaceView['removal']>) => (removal.state === 'removing' ? `Removing · ${removal.text}` : 'Not removed')

function PlaceRow({place, now, mode, removal, onOpen}: {place: WherePlace; now: number; mode?: CopyMode; removal?: CopyPlaceView['removal']; onOpen: () => void}) {
  const size = sizeWords(place)
  // every row counts the same thing: the person's files held there (the main storage says how many the library has)
  const count = place.thisDevice && place.holds.files ? place.holds.files.of : place.items
  const status = removal ? removalWords(removal) : statusWords(place, now)
  const type = mode ? modeWords[mode].name : undefined
  const body = (
    <>
      <span className="where-icon" aria-hidden="true">
        <Icon name={iconOf[place.kind]} size={18} />
      </span>
      <span className="where-text">
        <span className="where-name">
          <strong>{place.thisDevice ? 'Main storage' : place.name}</strong>
          {place.thisDevice && <span className="badge badge-accent">Main</span>}
          {type && (
            <span className="badge badge-solid" title={modeWords[mode!].short}>
              {type}
            </span>
          )}
        </span>
        <span className="small muted where-line" title={place.thisDevice ? undefined : copyLine(place)}>{place.thisDevice ? `Your Keepfin folder on ${THIS} · every copy is made from it` : copyLine(place)}</span>
      </span>
      <span className="where-state small">
        {count > 0 && <span className="tabular where-size" >{plural(count, 'file')}{size && size !== plural(count, 'file') ? ` · ${size}` : ''}</span>}
        <span className="where-status" data-tone={removal ? (removal.state === 'failed' ? 'warn' : 'quiet') : statusTone(place.status)} {...(removal ? {role: 'status', title: removal.text} : {})}>
          {status}
        </span>
      </span>
      {place.link ? (
        <span className="where-chevron" aria-hidden="true">
          <Icon name="chevronRight" size={16} />
        </span>
      ) : (
        <span />
      )}
    </>
  )
  return place.link ? (
    <button type="button" className="where-row" data-state={place.status.state} aria-label={`${place.thisDevice ? 'Main storage' : place.name}${type ? ` (${type})` : ''}: ${holdsWords(place.holds)}${size ? `, ${size}` : ''}, ${status}. ${place.link.to === 'device' ? 'Open the device' : 'Open the copy'}`} onClick={onOpen}>
      {body}
    </button>
  ) : (
    <div className="where-row" data-state={place.status.state}>
      {body}
    </div>
  )
}

/** Settings → Storage › Cloud accounts: the accounts this computer is signed in to, shown once there is one. */
export function CloudAccountsGroup({copies}: {copies: Copies}) {
  const [note, setNote] = useState<CloudNote | null>(null)
  const state: CopiesState | undefined = copies.state
  if (!state?.available || ((state.cloud?.accounts.length ?? 0) === 0 && !note)) return null
  return (
    <Group id="cloud" title="Cloud accounts" description="The accounts Keepfin is signed in to on this computer. Each device signs in on its own; nothing here is shared.">
      <CloudAccountCards state={state} onState={copies.set} note={note} onNote={setNote} />
    </Group>
  )
}
