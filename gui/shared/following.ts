// Followed artists and the releases found for them (Home's "New from artists you follow"). The state lives in main (userData/following.json);
// the renderer asks for it (followingState) and is pushed changes ({type: 'following'}).

/** An artist the person follows. */
export type FollowedArtist = {
  id: string
  name: string
  /** The folded first credited artist; one follow per key. */
  key: string
  /** Release links already read: the first check only records these. */
  seen: string[]
  checkedOnce: boolean
  /** Resolved channel releases page; absent until the first check finds it. */
  url?: string
  checkedAt?: number
  /** Friendly message, no tool names. */
  error?: string
}

/** A release found for a followed artist; `id` is its link, `foundAt` when Keepfin first saw it. */
export type ArtistRelease = {id: string; artistId: string; title: string; kind?: string; url: string; thumbnail?: string; foundAt: number; kept: boolean}

/** `seenReleases` are the release ids the person has seen (the card's x, Mark all seen): Home's card returns only for newer ones. */
export type FollowingState = {artists: FollowedArtist[]; releases: ArtistRelease[]; seenReleases: string[]; checking: boolean}

export const noFollowing: FollowingState = {artists: [], releases: [], seenReleases: [], checking: false}
