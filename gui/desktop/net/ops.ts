// The devices* requests of the renderer, mapped onto the service. Every answer is the current DevicesState, so the page needs no
// other call. The renderer sends structured intent only: an address or a link as text, a pairing id, a device id, a list of actions.
import type {DevicesArgs, DevicesState, PlayFromResult} from '../../shared/devices.js'
import type {NetService} from './service.js'

export const unavailableState = (why: string): DevicesState => ({
  available: false,
  unavailable: why,
  enabled: false,
  libraries: [],
  paired: [],
  discovered: [],
  pairingUntil: null,
  pairing: null,
  notes: [],
  offers: [],
  join: null,
  remoteJobs: [],
  firewall: {blocked: false, category: 'unknown'},
  tailscale: {state: 'off'},
  lan: false,
  scanning: false,
})

export async function handleDevices(service: NetService | undefined, op: string, args: DevicesArgs, unavailable: string): Promise<DevicesState | PlayFromResult> {
  if (!service) return unavailableState(unavailable)
  switch (op) {
    case 'devicesShare':
      if (typeof args.deviceId !== 'string' || typeof args.libraryId !== 'string') throw new Error('Invalid request.')
      await service.shareLibrary(args.deviceId, args.libraryId)
      break
    case 'devicesJoin':
      if (typeof args.deviceId !== 'string' || typeof args.libraryId !== 'string' || typeof args.path !== 'string' || !args.path.trim()) throw new Error('Choose a folder for the library.')
      // the join runs on in the background; the page follows it through the state pushes
      void service.joinLibrary(args.deviceId, args.libraryId, args.path.trim()).catch(() => undefined)
      break
    case 'devicesJoinCancel':
      service.cancelJoin()
      break
    case 'devicesJob':
      if (typeof args.deviceId !== 'string' || typeof args.url !== 'string') throw new Error('Invalid request.')
      await service.sendJob(args.deviceId, {url: args.url, format: args.format, options: args.options, space: typeof args.space === 'string' ? args.space : undefined})
      break
    case 'devicesJobDismiss':
      if (typeof args.requestId !== 'string') throw new Error('Invalid request.')
      await service.dismissJob(args.requestId)
      break
    case 'devicesPlayFrom':
      if (typeof args.libraryId !== 'string' || typeof args.contentId !== 'string') throw new Error('Invalid request.')
      return service.playFrom(args.libraryId, args.contentId, typeof args.name === 'string' ? args.name : 'media')
    case 'devicesState':
      break
    case 'devicesEnable':
      await service.enable()
      break
    case 'devicesDisable':
      await service.disable()
      break
    case 'devicesPairingOpen':
      service.openPairing()
      break
    case 'devicesPairingClose':
      service.closePairing()
      break
    case 'devicesQr':
      await service.showQr()
      break
    case 'devicesConnect': {
      const text = typeof args.address === 'string' ? args.address : args.host ? `${args.host}${args.port ? `:${args.port}` : ''}` : ''
      if (!text.trim()) throw new Error('Type the address of the other device.')
      // the pairing runs on in the background; the page follows it through the state pushes
      service.beginAsk(text)
      break
    }
    case 'devicesDecide':
      if (typeof args.pairingId !== 'string' || typeof args.match !== 'boolean') throw new Error('Invalid request.')
      service.decide(args.pairingId, args.match)
      break
    case 'devicesDismiss':
      service.dismissPairing()
      break
    case 'devicesFirewallAllow':
      // no arguments: the service adds the two rules for its own program, and nothing else is ever put into the command
      await service.allowFirewall()
      break
    case 'devicesWatch':
      // the Devices page is open: look for devices (DNS-SD, the scan, Tailscale) until it closes
      service.watchNearby()
      break
    case 'devicesWatchStop':
      service.unwatchNearby()
      break
    case 'devicesRename':
      if (typeof args.name !== 'string') throw new Error('Invalid request.')
      await service.rename(args.name)
      break
    case 'devicesRemove':
      if (typeof args.deviceId !== 'string') throw new Error('Invalid request.')
      await service.remove(args.deviceId)
      break
    case 'devicesSyncNow':
      if (typeof args.deviceId !== 'string') throw new Error('Invalid request.')
      service.syncNow(args.deviceId)
      break
    case 'devicesAccess':
      if (typeof args.deviceId !== 'string' || typeof args.libraryId !== 'string' || !Array.isArray(args.actions)) throw new Error('Invalid request.')
      await service.setAccess(args.deviceId, args.libraryId, args.actions)
      break
    default:
      throw new Error('Unknown operation.')
  }
  return service.state()
}
