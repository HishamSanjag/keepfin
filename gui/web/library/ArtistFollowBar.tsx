// The artist list, with Following (GitHub #51): the All / Following (n) chips, what the Following view lists, the "Checked ... · Check now"
// line and the calm note for an artist whose last check failed. Never shows engine text: a failed check always reads the same.
import {useMemo} from 'react'
import {artistKey as keyOf} from '../../shared/core/home'
import {whereAgo} from '../../shared/core/spaces'
import type {FollowingState} from '../../shared/following'
import {checkFollowing, useFollowing} from './following'
import {setArtistFilter, useArtistFilter} from './libNav'
import {norm, type Artist} from './model'

export const CHECK_FAILED_NOTE = 'Couldn’t check · will try again'
const STUB = 'follow:'

/** What the artist list shows: the library artists (All), or the followed ones (Following), including followed artists not yet in the library. */
export function useArtistRows(artists: Artist[], query: string) {
  const state = useFollowing()
  const filter = useArtistFilter()
  return useMemo(() => {
    if (filter === 'all') return {filter, rows: artists, failed: new Set<string>()}
    const followed = new Set(state.artists.map((artist) => artist.key))
    const inLibrary = new Set<string>()
    const rows = artists.filter((artist) => {
      const key = keyOf(artist.name)
      if (!followed.has(key)) return false
      inLibrary.add(key)
      return true
    })
    const q = norm(query)
    const stubs = state.artists
      .filter((artist) => !inLibrary.has(artist.key) && (!q || norm(artist.name).includes(q)))
      .map((artist): Artist => ({key: STUB + artist.key, name: artist.name, songs: [], albums: []}))
    return {filter, rows: [...rows, ...stubs], failed: new Set(state.artists.filter((artist) => artist.error).map((artist) => artist.key))}
  }, [artists, filter, query, state.artists])
}

/** A followed artist that is not in the library has no page to open. */
export const isStub = (artist: Artist) => artist.key.startsWith(STUB)
export const checkFailed = (failed: Set<string>, artist: Artist) => failed.has(isStub(artist) ? artist.key.slice(STUB.length) : keyOf(artist.name))

const checkedWords = (state: FollowingState) => {
  if (state.checking) return 'Checking…'
  const times = state.artists.map((artist) => artist.checkedAt ?? 0)
  const latest = Math.max(0, ...times)
  return latest ? `Checked ${whereAgo(latest, Date.now())}` : 'Not checked yet'
}

export function ArtistFilterBar() {
  const state = useFollowing()
  const filter = useArtistFilter()
  const count = state.artists.length
  return (
    <>
      <div className="chips" role="group" aria-label="Show artists">
        <button type="button" className={`chip${filter === 'all' ? ' on' : ''}`} aria-pressed={filter === 'all'} onClick={() => setArtistFilter('all')}>
          All
        </button>
        <button type="button" className={`chip${filter === 'following' ? ' on' : ''}`} aria-pressed={filter === 'following'} onClick={() => setArtistFilter('following')}>
          Following ({count})
        </button>
      </div>
      {filter === 'following' && (
        <div className="follow-bar">
          {count === 0 ? (
            <p className="muted small follow-checked">You are not following anyone yet. Press + Follow on an artist.</p>
          ) : (
            <p className="muted small follow-checked">
              {checkedWords(state)} ·{' '}
              <button type="button" className="link-btn" disabled={state.checking} onClick={() => void checkFollowing()}>
                Check now
              </button>
            </p>
          )}
        </div>
      )}
    </>
  )
}
