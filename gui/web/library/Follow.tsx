// Following, option B (GitHub #51): one slim card on Home while artists you follow have releases you have not seen, the Review window
// behind it, and the Follow controls (the toggle on every artist row and the artist page). Whether the card shows, which releases are
// unseen and how they are grouped are the shared core rules (gui/shared/core/home.ts). Nothing downloads without a tap on Keep it.
import {useMemo, useState} from 'react'
import {ageWords, artistKey as keyOf, releaseCard, unseenGroups, type FeedRelease} from '../../shared/core/home'
import type {ArtistRelease, FollowedArtist, FollowingState} from '../../shared/following'
import {SectionFrame} from '../media/Section'
import {navigate} from '../nav'
import {updateSettings, useMeta} from '../store'
import {toast} from '../toasts'
import {Button} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import {Icon} from '../ui/Icon'
import {Thumbnail} from '../ui/Thumbnail'
import {followArtist, keepRelease, markReleasesSeen, unfollowArtist, useFollowing} from './following'
import {setArtistFilter, setLibFilter, setLibView} from './libNav'

const feedOf = (state: FollowingState): FeedRelease[] =>
  state.releases.map((release) => ({id: release.id, artistKey: state.artists.find((artist) => artist.id === release.artistId)?.key ?? '', title: release.title, foundAt: release.foundAt, kept: release.kept}))

/** The artist page Follow button: "+ Follow", or "Following" (press to unfollow). */
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

/** The one-tap toggle of an artist, in the list and under a grid tile's name. Same labels as the artist page button. */
export function FollowToggle({name}: {name: string}) {
  const following = useFollowing()
  const found = following.artists.find((artist) => artist.key === keyOf(name))
  const label = found ? `Following ${found.name}. Press to unfollow` : `Follow ${name}`
  const onClick = () => void (found ? unfollowArtist(found.id, found.name) : followArtist(name))
  return (
    <button type="button" className={`follow-toggle${found ? ' on' : ''}`} aria-pressed={!!found} aria-label={label} onClick={onClick}>
      {found ? '✓ Following' : '+ Follow'}
    </button>
  )
}

/** Opens Library > Artists on its Following view (the card Review window, "Manage who you follow"). */
export function openFollowing() {
  setLibView({type: 'home'})
  setLibFilter('artists')
  setArtistFilter('following')
  navigate('Library')
}

const namesLine = (names: string[]) => (names.length <= 2 ? names.join(', ') : `${names.slice(0, 2).join(', ')} and ${names.length - 2} more`)

/** Home card: only while the setting is on and there are unseen new releases. x marks the current ones seen; "Don't show on Home" turns it off. */
export function NewReleasesCard() {
  const state = useFollowing()
  const {settings} = useMeta()
  const [reviewing, setReviewing] = useState(false)
  const card = useMemo(() => releaseCard(feedOf(state), state.seenReleases, settings.newReleasesOnHome), [state, settings.newReleasesOnHome])
  if (!card.show && !reviewing) return null
  const byId = new Map(state.releases.map((release) => [release.id, release]))
  const names = card.artistKeys.map((key) => state.artists.find((artist) => artist.key === key)?.name).filter((name): name is string => !!name)
  return (
    <>
      {card.show && (
        <SectionFrame id="new-releases" title="New from artists you follow">
          <div className="release-card">
            <span className="release-covers" aria-hidden="true">
              {card.ids.slice(0, 3).map((id) => (
                <span key={id} className="release-cover">
                  <Thumbnail url={byId.get(id)?.thumbnail} kind="audio" lazy label="" />
                </span>
              ))}
            </span>
            <span className="release-card-text">
              <span className="release-card-title">{card.title}</span>
              <span className="release-card-sub">
                <span>{namesLine(names)}</span>
                <button
                  type="button"
                  className="link-btn"
                  onClick={() => {
                    void updateSettings({newReleasesOnHome: false})
                    toast({tone: 'info', message: 'New releases are off on Home. You can turn them back on in Settings, under Library.'})
                  }}
                >
                  Don’t show on Home
                </button>
              </span>
            </span>
            <Button size="sm" variant="primary" onClick={() => setReviewing(true)}>
              Review
            </Button>
            <button type="button" className="icon-btn" aria-label="Dismiss new releases" onClick={() => void markReleasesSeen()}>
              <Icon name="close" size={16} />
            </button>
          </div>
        </SectionFrame>
      )}
      <ReviewReleases open={reviewing} state={state} onClose={() => setReviewing(false)} />
    </>
  )
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

/** The Review window: the unseen releases with Keep it each, Mark all seen and a way to Library > Artists > Following. */
function ReviewReleases({open, state, onClose}: {open: boolean; state: FollowingState; onClose: () => void}) {
  const groups = useMemo(() => (open ? unseenGroups(feedOf(state), state.seenReleases) : []), [open, state])
  const byId = new Map(state.releases.map((release) => [release.id, release]))
  const artistById = new Map(state.artists.map((artist) => [artist.id, artist]))
  return (
    <Dialog
      open={open}
      onClose={onClose}
      className="review-releases"
      title="New releases"
      footer={
        <>
          <button
            type="button"
            className="link-btn"
            onClick={() => {
              onClose()
              openFollowing()
            }}
          >
            Manage who you follow
          </button>
          <Button
            onClick={() => {
              onClose()
              void markReleasesSeen()
            }}
          >
            Mark all seen
          </Button>
        </>
      }
    >
      {groups.length === 0 ? (
        <p className="muted">Nothing new right now.</p>
      ) : (
        <div className="release-groups">
          {groups.map((group) => (
            <div key={group.label} role="group" aria-label={group.label}>
              <h3 className="release-group-label">{group.label}</h3>
              <ul className="release-list">
                {group.ids.map((id) => {
                  const release = byId.get(id)!
                  return <ReleaseRow key={id} release={release} artist={artistById.get(release.artistId)} onKeep={() => void keepRelease(release.id, release.title)} />
                })}
              </ul>
            </div>
          ))}
        </div>
      )}
    </Dialog>
  )
}
