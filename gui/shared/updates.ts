import {isPrerelease, type UpdateChannel} from './core/versions.js'

// Version order, channel selection and release-notes highlights are decided by the shared core
// (core/, see ./core/versions.ts); they are re-exported here so call sites keep importing from this module.
export {compareReleases, isPrerelease, isValidVersion, pickRelease, releaseHighlights, whatsNewDue} from './core/versions.js'
export type {UpdateChannel}
export const updateChannels: UpdateChannel[] = ['stable', 'beta']

export type UpdateState = {
  current: string
  /** Which releases are offered: stable only, or the newest of stable and beta. */
  channel: UpdateChannel
  /** installed = the Setup edition, the only one that updates itself; portable and msi are updated from Releases. */
  mode: 'installed' | 'portable' | 'msi' | 'development' | 'unsupported'
  phase:
    | 'idle'
    | 'checking'
    | 'available'
    | 'current'
    | 'downloading'
    | 'ready'
    | 'error'
  version?: string
  notes?: string
  notesVersion?: string
  percent?: number
  checkedAt?: number
  message?: string
}
export const releasePage =
  'https://github.com/HishamSanjag/keepfin/releases/latest'

/** Shown when a beta build has Stable selected and nothing newer than it exists on Stable. */
export function betaStableNote(state: Pick<UpdateState, 'current' | 'channel' | 'phase'>): string | undefined {
  if (state.channel !== 'stable' || !isPrerelease(state.current) || state.phase !== 'current') return
  return `You’re on beta ${state.current}. You’ll move to the next stable version automatically when it’s released. Your library and settings stay.`
}
