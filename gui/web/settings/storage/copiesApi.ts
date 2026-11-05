import type {Operation} from '../../../shared/contracts'
import type {CloudFoundReplica, CloudProviderId, CloudRevokeResult, CloudSignInResult, CopiesArgs, CopiesState, CopyDraft, CopyExports, CopyFolderCheck, CopyHolds, CopyPreview, CopyReviewAction, CopySchedule, CopyVersion} from '../../../shared/copies'
import {request} from '../../bridge'

/** Typed wrappers over the copies* operations (gui/shared/copies.ts). */
const call = <T>(op: Operation, copies?: CopiesArgs) => request<T>({op, copies})

export const getCopies = () => call<CopiesState>('copiesState')
/** The folder the user picked in the native dialog, or null when they cancelled. */
export const pickCopyFolder = () => call<string | null>('copiesPickFolder')
export const checkCopyFolder = (path: string) => call<CopyFolderCheck>('copiesCheckFolder', {path})
export const previewCopy = (draft: CopyDraft, placeId?: string) => call<CopyPreview>('copiesPreview', {draft, placeId})
export const cancelCopyPreview = (placeId: string) => call<null>('copiesCancelPreview', {placeId})
export const addCopy = (placeId: string) => call<CopiesState>('copiesAdd', {placeId})
export const syncCopies = (placeId?: string) => call<CopiesState>('copiesSync', {placeId})
export const pauseCopies = (placeId?: string) => call<CopiesState>('copiesPause', {placeId})
export const editCopy = (placeId: string, change: {name?: string; holds?: CopyHolds; schedule?: CopySchedule; exports?: CopyExports; companions?: boolean; musicVideos?: boolean}) => call<CopiesState>('copiesEdit', {placeId, ...change})
export const removeCopy = (placeId: string, deleteFiles = false) => call<CopiesState>('copiesRemove', {placeId, deleteFiles})
export const resolveReview = (reviewId: string, action: CopyReviewAction) => call<CopiesState>('copiesResolve', {reviewId, action})
export const listVersions = (placeId: string) => call<CopyVersion[]>('copiesVersions', {placeId})
export const restoreVersion = (placeId: string, entryId: string) => call<CopiesState>('copiesRestore', {placeId, entryId})
export const cleanUpCopy = (placeId: string) => call<CopiesState>('copiesCleanup', {placeId})

// Cloud accounts (Google Drive, OneDrive, Dropbox, WebDAV)
/** Opens the browser and waits for the sign-in (up to five minutes); `accountId` signs an existing account in again. */
export const signInToCloud = (provider: Exclude<CloudProviderId, 'webdav'>, accountId?: string) => call<CloudSignInResult>('copiesCloudSignIn', {cloud: {provider, accountId}})
export const cancelCloudSignIn = () => call<null>('copiesCloudCancel')
export const connectWebdav = (url: string, user: string, password: string) => call<CloudSignInResult>('copiesCloudWebdav', {cloud: {url, user, password}})
export const findCloudCopies = (accountId: string, folder?: string) => call<CloudFoundReplica[]>('copiesCloudFind', {cloud: {accountId, folder}})
export const attachCloudCopy = (accountId: string, folder: string) => call<CopiesState>('copiesCloudAttach', {cloud: {accountId, folder}})
export const disconnectCloud = (accountId: string) => call<CopiesState>('copiesCloudDisconnect', {cloud: {accountId}})
export const revokeCloud = (accountId: string) => call<CloudRevokeResult>('copiesCloudRevoke', {cloud: {accountId}})