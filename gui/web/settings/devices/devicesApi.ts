import type {Operation} from '../../../shared/contracts'
import type {DeviceAction, DevicesArgs, DevicesState, PlayFromResult} from '../../../shared/devices'
import {request} from '../../bridge'

/** Typed wrappers over the devices* operations (gui/shared/devices.ts). Every call answers with the whole current state. */
const call = (op: Operation, devices?: DevicesArgs) => request<DevicesState>({op, devices})

export const getDevices = () => call('devicesState')
export const turnDevicesOn = () => call('devicesEnable')
export const turnDevicesOff = () => call('devicesDisable')
/** "Add a device" opened: this device answers pairing requests for two minutes and looks for others. */
export const openPairing = () => call('devicesPairingOpen')
export const closePairing = () => call('devicesPairingClose')
export const showQr = () => call('devicesQr')
/** An address (`host`, `host:port`, a Tailscale name) or a pasted `keepfin://pair?...` link. The pairing runs on; the state follows it. */
export const connectTo = (address: string) => call('devicesConnect', {address})
export const decidePairing = (pairingId: string, match: boolean) => call('devicesDecide', {pairingId, match})
export const dismissPairing = () => call('devicesDismiss')
/** Windows asks the person for permission once, then both firewall rules for Keepfin are added. No arguments: nothing from the page is ever put into the command. */
export const allowFirewall = () => call('devicesFirewallAllow')
/** The Devices page is open: Keepfin looks for devices (this network, Tailscale) until it closes. */
export const watchDevices = () => call('devicesWatch')
export const unwatchDevices = () => call('devicesWatchStop')
export const renameThisDevice = (name: string) => call('devicesRename', {name})
export const removeDevice = (deviceId: string) => call('devicesRemove', {deviceId})
/** Sync now / Retry: syncs at once when the device answers, else tries to reach it again now. */
export const syncDeviceNow = (deviceId: string) => call('devicesSyncNow', {deviceId})
export const setAccess = (deviceId: string, libraryId: string, actions: DeviceAction[]) => call('devicesAccess', {deviceId, libraryId, actions})
/** Offers a library of this device to a paired one: it may then sync it and take a copy of it ("Get this library"). */
export const shareLibrary = (deviceId: string, libraryId: string) => call('devicesShare', {deviceId, libraryId})
/** Makes a Keepfin folder at [path] for a library a paired device shares, and takes it in. The join runs on; the state follows it. */
export const joinLibrary = (deviceId: string, libraryId: string, path: string) => call('devicesJoin', {deviceId, libraryId, path})
export const cancelJoin = () => call('devicesJoinCancel')
/** The app restarts into the chosen library. */
export const openLibrary = (profileId: string) => call('devicesOpenLibrary', {profileId})
/** Download on…: asks a paired device to download a link. */
export const sendJob = (deviceId: string, args: {url: string; format: unknown; options?: unknown; space?: string; musicVideo?: boolean}) => call('devicesJob', {deviceId, ...args})
export const dismissRemoteJob = (requestId: string) => call('devicesJobDismiss', {requestId})
/** The folder picker of the setup window (a path, or null when cancelled). */
export const pickLibraryFolder = () => request<string | null>({op: 'storagePickFolder'})
/** Play from a paired device: a media URL for a file that is on a paired device only. */
export const playFromDevice = (libraryId: string, contentId: string, name: string) => request<PlayFromResult>({op: 'devicesPlayFrom', devices: {libraryId, contentId, name}})
