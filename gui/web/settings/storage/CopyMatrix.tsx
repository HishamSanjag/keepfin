import {useState} from 'react'
import type {CopiesState, CopyCell, CopyHolds, CopyPlaceView, CopyRow} from '../../../shared/copies'
import {messageOf} from '../../bridge'
import {InlineAlert} from '../../ui/controls'
import {Icon} from '../../ui/Icon'
import {editCopy} from './copiesApi'
import {cellWords} from './copyText'

const glyph: Record<CopyCell, string> = {kept: 'check', waiting: 'refresh', review: 'warning', notHeld: 'plus'}

/** The holds of a place after one cell is clicked: its row is added, or left out. `all` is spelled out first so one row can leave. */
export function toggledHolds(place: Pick<CopyPlaceView, 'holds'>, row: CopyRow, rows: CopyRow[]): CopyHolds {
  let h: CopyHolds = {...place.holds, spaces: [...place.holds.spaces], playlists: [...place.holds.playlists], smart: [...(place.holds.smart ?? [])]}
  if (h.all) {
    h = {
      all: false,
      spaces: rows.filter((r) => r.kind === 'space').map((r) => r.id),
      playlists: rows.filter((r) => r.kind === 'playlist').map((r) => r.id),
      favourites: true,
      smart: rows.filter((r) => r.kind === 'smart').map((r) => r.id),
    }
  }
  if (row.kind === 'favourites') h.favourites = !h.favourites
  else if (row.kind === 'space') h.spaces = h.spaces.includes(row.id) ? h.spaces.filter((id) => id !== row.id) : [...h.spaces, row.id]
  else if (row.kind === 'smart') h.smart = (h.smart ?? []).includes(row.id) ? (h.smart ?? []).filter((id) => id !== row.id) : [...(h.smart ?? []), row.id]
  else h.playlists = h.playlists.includes(row.id) ? h.playlists.filter((id) => id !== row.id) : [...h.playlists, row.id]
  return h
}

/** Option B: rows are what can be held, columns are the copies, each cell says what the copy does with it. A click edits `holds`. */
export function CopyMatrix({state, onState}: {state: CopiesState; onState: (state: CopiesState) => void}) {
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  async function toggle(place: CopyPlaceView, row: CopyRow) {
    const holds = toggledHolds(place, row, state.rows)
    if (!holds.spaces.length && !holds.playlists.length && !holds.favourites) {
      setError('A copy has to hold something. Remove the copy instead if you no longer want it.')
      return
    }
    setBusy(`${place.id}|${row.key}`)
    setError('')
    try {
      onState(await editCopy(place.id, {holds}))
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setBusy('')
    }
  }
  return (
    <div className="copy-matrix-wrap">
      <table className="copy-matrix" aria-label="What each copy holds">
        <thead>
          <tr>
            <th scope="col">
              <span className="sr-only">Held in your library</span>
            </th>
            {state.places.map((place) => (
              <th key={place.id} scope="col">
                <span className="copy-matrix-name">{place.name}</span>
                <small className="muted">{place.mode === 'twoWay' ? 'Two-way' : place.mode === 'backup' ? 'Backup' : 'Mirror'}</small>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {state.rows.map((row) => (
            <tr key={row.key}>
              <th scope="row">
                <span className="copy-matrix-row">{row.name}</span>
                <small className="muted">{row.kind === 'space' ? 'Space' : row.kind === 'playlist' ? 'Playlist' : row.kind === 'smart' ? 'Smart space' : 'Smart set'}</small>
              </th>
              {state.places.map((place) => {
                const cell: CopyCell = place.cells[row.key] ?? (place.holds.all ? 'waiting' : 'notHeld')
                return (
                  <td key={place.id}>
                    <button
                      type="button"
                      className={`copy-cell copy-cell-${cell}`}
                      disabled={busy === `${place.id}|${row.key}`}
                      aria-label={`${row.name} on ${place.name}: ${cellWords(cell)}. ${cell === 'notHeld' ? 'Press to hold it.' : 'Press to stop holding it.'}`}
                      onClick={() => void toggle(place, row)}
                    >
                      <Icon name={glyph[cell]} size={15} />
                      <span className="copy-cell-text" aria-hidden="true">
                        {cell === 'kept' ? 'Kept' : cell === 'waiting' ? 'Waiting' : cell === 'review' ? 'Review' : 'Not held'}
                      </span>
                    </button>
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="small muted copy-legend">
        Kept: verified on the copy. Waiting: will be copied at the next sync. Review: needs a decision. Not held: this copy does not hold it. Leaving a row out never deletes anything.
      </p>
      {error && <InlineAlert tone="error">{error}</InlineAlert>}
    </div>
  )
}
