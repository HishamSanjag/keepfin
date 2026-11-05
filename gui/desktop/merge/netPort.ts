// The network side of a merge (types.ts MergeNet) over the NetService.
import type {NetService} from '../net/service.js'
import type {MergeNet} from './types.js'

export function netPortFor(service: () => NetService | undefined, held: () => {libraryId: string; name: string}[]): MergeNet {
  const need = () => {
    const s = service()
    if (!s) throw new Error('Devices on the network are not available.')
    return s
  }
  return {
    offer: (deviceId) => service()?.offerFrom(deviceId),
    deviceName: (deviceId) => service()?.peerName(deviceId) ?? '',
    online: (deviceId) => service()?.peerOnline(deviceId) ?? false,
    gap: (deviceId) => service()?.offerGap(deviceId),
    join: (deviceId, libraryId, _name, target, onProgress, signal) => need().joinLibraryInto(deviceId, libraryId, target, onProgress, signal),
    holds: (libraryId) => held().find((l) => l.libraryId === libraryId)?.name,
    published: (libraryId, actor, seq, timeoutMs) => need().confirmPublished(libraryId, actor, seq, timeoutMs),
    leave: (deviceId, libraryId, actor) => need().leaveLibrary(deviceId, libraryId, actor),
    reachable: (libraryId) => service()?.reachableFor(libraryId) ?? false,
  }
}
