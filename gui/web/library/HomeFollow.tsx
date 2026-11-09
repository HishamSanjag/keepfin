// Home's two follow-related sections: "Made from your listening" (mix tiles, from the person's own plays) and "New from artists you follow"
// (a dated feed with a one-tap Keep it, plus the Following panel and its manager). What a mix holds, how the feed is grouped and who gets
// the dot are the shared core's (gui/shared/core/home.ts). Nothing downloads without a tap on Keep it.
import {useMemo, useState, type FormEvent} from 'react'
import {ageWords, artistKey as keyOf, artistsWithNew, feedGroups, type FeedRelease, type Mix} from '../../shared/core/home'
import type {ArtistRelease, FollowedArtist, FollowingState} from '../../shared/following'
import {navigate} from '../nav'
import {Button, EmptyState} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import {Icon} from '../ui/Icon'
import {Thumbnail} from '../ui/Thumbnail'
import {Art, initialsOf} from './Art'
import {checkFollowing, followArtist, keepRelease, unfollowArtist, useFollowing} from './following'
import {setLibFilter, setLibView} from './libNav'
import {useLibItem} from './libStore'

/** The artist page's Follow button: "+ Follow", or "Following" (press to unfollow). */
export function FollowButton({name}: {name: string}) {
  const following = useFollowing()
  const found = following.artists.find((artist) => artist.key === keyOf(name))
  if (found)
    return (
      <Button icon="check" aria-pressed onClick={() => void unfollowArtist(found.id, found.name)} aria-label={`Following ${found.name}. Press to unfollow`}>
        Following
      </Button>
    )
  return (
    <Button variant="primary" icon="plus" onClick={() => void followArtist(name)} aria-label={`Follow ${name}`}>
      Follow
    </Button>
  )
}


function MixTile({mix, onOpen}: {mix: Mix; onOpen: () => void}) {
  const first = useLibItem(mix.ids[0])
  return (
    <button type="button" className="mix-tile" onClick={onOpen} aria-label={`${mix.title}. ${mix.blurb}`}>
      <span className="mix-art">{first && <Art id={first.id} hasArt={first.hasArt} kind={first.kind} size={20} />}</span>
      <span className="mix-text">
        <span className="mix-title">{mix.title}</span>
        <span className="mix-sub">{mix.blurb}</span>
      </span>
    </button>
  )
}

export function MixSection({mixes, onOpen, onSeeAll, hasSongs}: {mixes: Mix[]; onOpen: (mix: Mix) => void; onSeeAll: () => void; hasSongs: boolean}) {
  return (
    <section className="home-group" aria-label="Made from your listening">
      <header className="shelf-head">
        <div className="shelf-heading">
          <h3 className="group-title">Made from your listening</h3>
          {mixes.length > 0 && <p className="muted small">Mixes update as you play</p>}
        </div>
        {mixes.length > 0 && (
          <button type="button" className="link-btn" onClick={onSeeAll} aria-label="See all mixes made from your listening">
            See all
          </button>
        )}
      </header>
      {mixes.length > 0 ? (
        <div className="mix-grid" role="list" aria-label="Mixes">
          {mixes.map((mix) => (
            <div key={mix.id} role="listitem">
              <MixTile mix={mix} onOpen={() => onOpen(mix)} />
            </div>
          ))}
        </div>
      ) : (
        <div className="home-hint" role="note">
          <span className="home-hint-icon"><Icon name="music" size={18} /></span>
          <div>
            <div className="home-hint-title">Nothing played yet</div>
            <p className="muted small">
              {hasSongs
                ? 'Play a few songs and Keepfin builds On repeat, Forgotten favourites and more from what you play. It all stays on this computer.'
                : 'Keep some music and play it: Keepfin builds On repeat, Forgotten favourites and more from what you play. It all stays on this computer.'}
            </p>
          </div>
        </div>
      )}
    </section>
  )
}

const hue = (text: string) => [...text].reduce((sum, ch) => (sum * 31 + ch.charCodeAt(0)) % 360, 7)
function Avatar({artist, dot}: {artist: FollowedArtist; dot?: boolean}) {
  return (
    <span className="follow-avatar" style={{background: `linear-gradient(135deg, hsl(${hue(artist.key)} 62% 58%), hsl(${(hue(artist.key) + 50) % 360} 58% 50%))`}} aria-hidden="true">
      {initialsOf(artist.name)}
      {dot && <span className="follow-dot" />}
    </span>
  )
}

function statusOf(artist: FollowedArtist, fresh: number, checking: boolean) {
  if (artist.error) return artist.error
  if (!artist.checkedAt) return checking ? 'Checking…' : 'Not checked yet'
  return fresh ? `${fresh} new release${fresh > 1 ? 's' : ''}` : 'Up to date'
}

export function FollowingPanel({state, withNew, onManage}: {state: FollowingState; withNew: Set<string>; onManage: () => void}) {
  return (
    <aside className="follow-panel" aria-label="Following">
      <header className="shelf-head">
        <h3 className="group-title">Following</h3>
        <button type="button" className="link-btn" onClick={onManage}>
          Manage
        </button>
      </header>
      <ul className="follow-list">
        {state.artists.slice(0, 6).map((artist) => (
          <li key={artist.id} className="follow-row">
            <Avatar artist={artist} />
            <span className="follow-name">{artist.name}</span>
            {withNew.has(artist.key) && <span className="follow-new" role="img" aria-label="New release" />}
          </li>
        ))}
      </ul>
      {state.artists.length > 6 && <p className="muted small">and {state.artists.length - 6} more</p>}
    </aside>
  )
}

const goFindArtist = () => {
  setLibView({type: 'home'})
  setLibFilter('artists')
  navigate('Library')
}

/** The artists with the most plays: what "Follow your top 5 artists" follows. */
export function topArtistNames(songs: readonly {artist?: string; plays?: number}[], count = 5): string[] {
  const totals = new Map<string, {name: string; plays: number}>()
  for (const song of songs) {
    const key = keyOf(song.artist)
    if (!key || !song.artist) continue
    const entry = totals.get(key) ?? {name: song.artist.replace(/\s+-\s+Topic$/i, '').trim(), plays: 0}
    entry.plays += song.plays ?? 0
    totals.set(key, entry)
  }
  return [...totals.values()].sort((a, b) => b.plays - a.plays || a.name.localeCompare(b.name)).slice(0, count).map((entry) => entry.name)
}

function ReleaseRow({release, artist, onKeep}: {release: ArtistRelease; artist?: FollowedArtist; onKeep: () => void}) {
  return (
    <li className="release-row">
      <span className="release-art">
        <Thumbnail url={release.thumbnail} kind="audio" lazy label={release.title} />
      </span>
      <span className="release-text">
        <span className="release-title">{release.title}</span>
        <span className="release-sub">{[artist?.name, release.kind, ageWords(release.foundAt)].filter(Boolean).join(' · ')}</span>
      </span>
      <Button size="sm" variant="primary" onClick={onKeep} aria-label={`Keep ${release.title}`}>
        Keep it
      </Button>
    </li>
  )
}

export function ReleaseSection({state, songs, onManage}: {state: FollowingState; songs: readonly {artist?: string; plays?: number}[]; onManage: () => void}) {
  const feed = useMemo(() => {
    const releases: FeedRelease[] = state.releases.map((release) => ({id: release.id, artistKey: state.artists.find((artist) => artist.id === release.artistId)?.key ?? '', title: release.title, foundAt: release.foundAt, kept: release.kept}))
    return {groups: feedGroups(releases), withNew: artistsWithNew(releases)}
  }, [state])
  const byId = new Map(state.releases.map((release) => [release.id, release]))
  const artistById = new Map(state.artists.map((artist) => [artist.id, artist]))
  const none = state.artists.length === 0
  const top = useMemo(() => topArtistNames(songs), [songs])
  return (
    <section className="home-group" aria-label="New from artists you follow">
      <header className="shelf-head">
        <div className="shelf-heading">
          <h3 className="group-title">New from artists you follow</h3>
          {!none && <p className="muted small">Newest first</p>}
        </div>
      </header>
      {none ? (
        <EmptyState
          icon="user"
          title="Follow artists, get their new releases"
          actions={
            <>
              {top.length > 0 && (
                <Button variant="primary" onClick={() => void Promise.all(top.map((name) => followArtist(name)))}>
                  {top.length === 1 ? 'Follow your top artist' : `Follow your top ${top.length} artists`}
                </Button>
              )}
              <Button onClick={goFindArtist}>Find an artist</Button>
            </>
          }
        >
          Open any artist and press Follow, or follow the artists you already keep. Keepfin lists what is new; nothing downloads until you press Keep it.
        </EmptyState>
      ) : (
        <div className="home-follow">
          <div className="feed" aria-label="New releases">
            {feed.groups.length === 0 ? (
              <p className="muted pane-note">
                {state.checking ? 'Looking for new releases…' : 'No new releases from the artists you follow yet. Keepfin checks about every 12 hours.'}{' '}
                <button type="button" className="link-btn" onClick={() => void checkFollowing()}>
                  Check now
                </button>
              </p>
            ) : (
              feed.groups.map((group) => (
                <div key={group.label} role="group" aria-label={group.label}>
                  <h4 className="feed-label">{group.label}</h4>
                  <ul className="release-list">
                    {group.ids.map((id) => {
                      const release = byId.get(id)!
                      return <ReleaseRow key={id} release={release} artist={artistById.get(release.artistId)} onKeep={() => void keepRelease(release.id, release.title)} />
                    })}
                  </ul>
                </div>
              ))
            )}
          </div>
          <FollowingPanel state={state} withNew={feed.withNew} onManage={onManage} />
        </div>
      )}
    </section>
  )
}

/** The side panel: who is followed, unfollow, follow someone by name, and check now. */
export function ManageFollowing({open, onClose}: {open: boolean; onClose: () => void}) {
  const state = useFollowing()
  const [name, setName] = useState('')
  const withNew = useMemo(() => {
    const releases: FeedRelease[] = state.releases.map((release) => ({id: release.id, artistKey: state.artists.find((artist) => artist.id === release.artistId)?.key ?? '', title: release.title, foundAt: release.foundAt, kept: release.kept}))
    const counts = new Map<string, number>()
    const live = new Set(feedGroups(releases).flatMap((group) => group.ids))
    for (const release of state.releases) if (live.has(release.id)) counts.set(release.artistId, (counts.get(release.artistId) ?? 0) + 1)
    return counts
  }, [state])
  const add = (event: FormEvent) => {
    event.preventDefault()
    const text = name.trim()
    if (!text) return
    setName('')
    void followArtist(text)
  }
  return (
    <Dialog
      open={open}
      onClose={onClose}
      className="side-panel"
      labelledBy="following-title"
      title="Following"
      header={
        <div className="dialog-head side-head">
          <h2 id="following-title">Following{state.artists.length ? ` · ${state.artists.length}` : ''}</h2>
          <button type="button" className="icon-btn" aria-label="Close" onClick={onClose}>
            <Icon name="close" size={16} />
          </button>
        </div>
      }
      footer={
        <>
          <Button onClick={() => void checkFollowing()} loading={state.checking} disabled={state.artists.length === 0}>
            Check now
          </Button>
          <Button variant="primary" onClick={onClose}>
            Done
          </Button>
        </>
      }
    >
      <form className="follow-add" onSubmit={add}>
        <input type="text" value={name} onChange={(event) => setName(event.target.value)} placeholder="Find an artist to follow" aria-label="Artist to follow" />
        <Button type="submit" disabled={!name.trim()}>
          Follow
        </Button>
      </form>
      {state.artists.length === 0 ? (
        <div className="home-hint" role="note">
          <div>
            <div className="home-hint-title">Nobody yet</div>
            <p className="muted small">Type an artist above, or press Follow on an artist page.</p>
          </div>
        </div>
      ) : (
        <ul className="follow-manage">
          {state.artists.map((artist) => (
            <li key={artist.id} className="follow-manage-row">
              <Avatar artist={artist} />
              <span className="follow-manage-text">
                <span className="follow-name">{artist.name}</span>
                <span className={`muted small${artist.error ? ' follow-error' : ''}`}>{statusOf(artist, withNew.get(artist.id) ?? 0, state.checking)}</span>
              </span>
              <Button size="sm" icon="check" aria-pressed onClick={() => void unfollowArtist(artist.id, artist.name)} aria-label={`Following ${artist.name}. Press to unfollow`}>
                Following
              </Button>
            </li>
          ))}
        </ul>
      )}
      <p className="muted small">Press Following to unfollow. Keepfin checks about every 12 hours; Check now looks straight away.</p>
    </Dialog>
  )
}
