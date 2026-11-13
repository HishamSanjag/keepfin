// The item menu for songs of Music on this PC. They stay where they are: nothing that moves, renames or retags them.
import {DEVICE_SPACE_ID, type LibraryItem} from '../../shared/library'
import {plural} from '../format'
import {playAll, playItem, revealInFolder} from '../player/play'
import {addToQueue, playNext} from '../player/playerStore'
import {toast} from '../toasts'
import type {MenuItem} from '../ui/Menu'
import {openDialog} from './dialogState'
import {libraryAct, toggleFavorite} from './libStore'

export const isDeviceItem = (item: Pick<LibraryItem, 'space'>) => item.space === DEVICE_SPACE_ID

/** "Remove from Keepfin": the songs are hidden here (Settings brings them back); their files are not touched. */
export async function hideDeviceSongs(ids: string[]) {
  const result = await libraryAct('libraryHide', ids)
  if (result.ok) toast({tone: 'info', message: `Removed ${plural(ids.length, 'song')} from Keepfin. The files are still on this PC.`})
}

export function deviceItemMenu(items: LibraryItem[], options: {context?: LibraryItem[]; onRemoveFromPlaylist?: () => void} = {}): MenuItem[] {
  const ids = items.map((item) => item.id)
  const menu: MenuItem[] = [{label: 'Play', icon: 'play', onSelect: () => (items.length > 1 ? playAll(items) : playItem(items[0], options.context || items))}]
  menu.push(
    {label: 'Play next', icon: 'skipNext', onSelect: () => playNext(ids)},
    {label: 'Add to queue', icon: 'queue', onSelect: () => addToQueue(ids)},
    {label: 'Add to playlist', icon: 'playlistAdd', onSelect: () => openDialog({type: 'playlistAdd', ids})},
  )
  if (items.length === 1) {
    const [item] = items
    menu.push({label: item.favorite ? 'Remove from favorites' : 'Add to favorites', icon: item.favorite ? 'heartFill' : 'heart', onSelect: () => void toggleFavorite(item.id)})
    if (options.onRemoveFromPlaylist) menu.push({label: 'Remove from playlist', icon: 'close', onSelect: options.onRemoveFromPlaylist})
    menu.push('separator', {label: 'Details…', icon: 'info', onSelect: () => openDialog({type: 'details', id: item.id})}, {label: 'Show in folder', icon: 'folder', onSelect: () => revealInFolder(item.id)})
  } else if (options.onRemoveFromPlaylist) menu.push({label: 'Remove from playlist', icon: 'close', onSelect: options.onRemoveFromPlaylist})
  menu.push(
    'separator',
    {label: 'Remove from Keepfin', icon: 'close', onSelect: () => void hideDeviceSongs(ids)},
    {label: 'Delete file…', icon: 'trash', tone: 'danger', onSelect: () => openDialog({type: 'deleteFiles', ids})},
  )
  return menu
}