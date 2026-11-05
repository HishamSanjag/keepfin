import {useId} from 'react'
import type {CopyHolds, CopyRow} from '../../../shared/copies'

/** What a copy holds: everything, or chosen spaces, favourites and playlists. Used by the wizard and by a card's editor. */
export function HoldsEditor({holds, rows, onChange, label = 'What this copy holds'}: {holds: CopyHolds; rows: CopyRow[]; onChange: (holds: CopyHolds) => void; label?: string}) {
  const name = useId()
  const chosen = (row: CopyRow) => (row.kind === 'space' ? holds.spaces.includes(row.id) : row.kind === 'playlist' ? holds.playlists.includes(row.id) : holds.favourites)
  function toggle(row: CopyRow) {
    if (row.kind === 'favourites') onChange({...holds, favourites: !holds.favourites})
    else if (row.kind === 'space') onChange({...holds, spaces: holds.spaces.includes(row.id) ? holds.spaces.filter((id) => id !== row.id) : [...holds.spaces, row.id]})
    else onChange({...holds, playlists: holds.playlists.includes(row.id) ? holds.playlists.filter((id) => id !== row.id) : [...holds.playlists, row.id]})
  }
  const groups: {title: string; rows: CopyRow[]}[] = [
    {title: 'Spaces', rows: rows.filter((r) => r.kind === 'space')},
    {title: 'Favourites', rows: rows.filter((r) => r.kind === 'favourites')},
    {title: 'Playlists', rows: rows.filter((r) => r.kind === 'playlist')},
  ].filter((g) => g.rows.length > 0)
  return (
    <fieldset className="copy-holds">
      <legend className="sr-only">{label}</legend>
      <label className="copy-choice">
        <input type="radio" name={name} checked={holds.all} onChange={() => onChange({all: true, spaces: [], playlists: [], favourites: false})} />
        <span>
          <strong>Everything</strong>
          <small>All of your library, including what you add later.</small>
        </span>
      </label>
      <label className="copy-choice">
        <input type="radio" name={name} checked={!holds.all} onChange={() => onChange({...holds, all: false})} />
        <span>
          <strong>Only some of it</strong>
          <small>Pick the spaces, favourites and playlists to keep here.</small>
        </span>
      </label>
      {!holds.all && (
        <div className="copy-pick">
          {groups.map((group) => (
            <div key={group.title} role="group" aria-label={group.title}>
              <h5>{group.title}</h5>
              <ul>
                {group.rows.map((row) => (
                  <li key={row.key}>
                    <label className="copy-check">
                      <input type="checkbox" checked={chosen(row)} onChange={() => toggle(row)} />
                      <span>{row.name}</span>
                    </label>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}
    </fieldset>
  )
}
