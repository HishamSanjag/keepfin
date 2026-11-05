import {extensionOf, notOnThisPc, songWords, taggable, type LibraryItem} from '../../shared/library'
import type {MenuItem} from '../ui/Menu'
import {openExternally, watchVideoSong, playAll, playAsAudio, playItem, removeFromContinue, revealInFolder} from '../player/play'
import {addToQueue, playNext} from '../player/playerStore'
import {openDialog} from './dialogState'
import {navigate} from '../nav'
import {setLibView} from './libNav'
import {findLyricsFromMenu, findLyricsWords} from './findLyrics'
import {isVideoSong, toggleFavorite} from './libStore'
import {inProgress, norm, playableKind} from './model'

/** The item context menu. The words and order are the same for one item and for a selection. */
export function itemMenu(items: LibraryItem[], options: {context?: LibraryItem[]; onRemoveFromPlaylist?: () => void} = {}): MenuItem[] {
  if (!items.length) return []
  const ids = items.map((item) => item.id)
  // Items that live on another device: only Play is offered (removing one would remove it from the shared library)
  if (items.every((item) => notOnThisPc(item))) return [{label: 'Play', icon: 'play', onSelect: () => playItem(items[0], options.context || items)}]
  if (items.every((item) => item.missing))
    return [{label: 'Remove from library', icon: 'close', onSelect: () => openDialog({type: 'deleteFiles', ids})}]
  const songs = items.filter(playableKind).map((item) => item.id)
  if (items.length > 1) {
    const menu: MenuItem[] = [{label: 'Play', icon: 'play', onSelect: () => (songs.length ? playAll(items) : playItem(items[0], items))}]
    if (songs.length) {
      menu.push(
        {label: 'Play next', icon: 'skipNext', onSelect: () => playNext(songs)},
        {label: 'Add to queue', icon: 'queue', onSelect: () => addToQueue(songs)},
        {label: 'Add to playlist', icon: 'playlistAdd', onSelect: () => openDialog({type: 'playlistAdd', ids: songs})},
      )
    }
    menu.push({label: 'Tags…', icon: 'tag', onSelect: () => openDialog({type: 'tags', ids})})
    menu.push('separator', {label: 'Move to…', icon: 'move', onSelect: () => openDialog({type: 'move', ids})}, {label: 'Copy to…', icon: 'copy', onSelect: () => openDialog({type: 'move', ids, mode: 'copy'})})
    if (options.onRemoveFromPlaylist) menu.push({label: 'Remove from playlist', icon: 'close', onSelect: options.onRemoveFromPlaylist})
    menu.push('separator', {label: 'Delete file…', icon: 'trash', tone: 'danger', onSelect: () => openDialog({type: 'deleteFiles', ids})})
    return menu
  }
  const item = items[0]
  const menu: MenuItem[] = [{label: 'Play', icon: 'play', onSelect: () => playItem(item, options.context || [item])}]
  // The same actions in the same order for every item; the ones that do not apply say why instead of vanishing.
  const playable = playableKind(item)
  const why = playable ? undefined : 'Only songs and videos have this'
  const editable = item.kind === 'audio' || (item.kind === 'video' && taggable.has(extensionOf(item.path)))
  // a video kept in a music space plays as a song: its picture is one step away
  if (isVideoSong(item)) menu.push({label: songWords.watchVideo, icon: 'video', onSelect: () => watchVideoSong(item)})
  else if (item.kind === 'video') menu.push({label: 'Play as audio', icon: 'music', onSelect: () => playAsAudio(item)})
  menu.push(
    {label: 'Play next', icon: 'skipNext', disabled: !playable, detail: why, onSelect: () => playNext([item.id])},
    {label: 'Add to queue', icon: 'queue', disabled: !playable, detail: why, onSelect: () => addToQueue([item.id])},
    {label: 'Add to playlist', icon: 'playlistAdd', disabled: !playable, detail: why, onSelect: () => openDialog({type: 'playlistAdd', ids: [item.id]})},
  )
  const albumKey = item.album ? `${norm(item.album)}|${norm(item.albumArtist)}` : undefined
  const artistName = item.artist || item.albumArtist
  menu.push(
    {
      label: 'Go to album',
      icon: 'disc',
      disabled: !albumKey,
      detail: albumKey ? undefined : 'No album tag',
      onSelect: () => (setLibView({type: 'album', key: albumKey!}), navigate('Library')),
    },
    {
      label: 'Go to artist',
      icon: 'user',
      disabled: !artistName,
      detail: artistName ? undefined : 'No artist tag',
      onSelect: () => (setLibView({type: 'artist', key: norm(artistName)}), navigate('Library')),
    },
  )
  if (playable)
    menu.push({label: item.favorite ? 'Remove from favorites' : 'Add to favorites', icon: item.favorite ? 'heartFill' : 'heart', onSelect: () => void toggleFavorite(item.id)})
  if (item.kind === 'audio') menu.push(item.musicVideo ? {label: 'Remove music video…', icon: 'trash', onSelect: () => openDialog({type: 'removeMusicVideo', id: item.id})} : {label: 'Get the music video…', icon: 'video', onSelect: () => openDialog({type: 'musicVideo', id: item.id})})
  if (item.kind === 'audio' && !item.hasLyrics) menu.push({label: findLyricsWords, icon: 'lyrics', onSelect: () => void findLyricsFromMenu(item)})
  if (inProgress(item)) menu.push({label: 'Remove from Continue watching', icon: 'close', onSelect: () => removeFromContinue(item)})
  if (options.onRemoveFromPlaylist) menu.push({label: 'Remove from playlist', icon: 'close', onSelect: options.onRemoveFromPlaylist})
  menu.push('separator')
  menu.push({label: 'Details…', icon: 'info', onSelect: () => openDialog({type: 'details', id: item.id})})
  menu.push({label: 'Edit info…', icon: 'edit', disabled: !editable, detail: editable ? undefined : playable ? 'This file’s format can’t be edited' : why, onSelect: () => openDialog({type: 'editInfo', id: item.id})})
  menu.push(
    {label: 'Tags…', icon: 'tag', onSelect: () => openDialog({type: 'tags', ids: [item.id]})},
    {label: 'Rename…', icon: 'edit', onSelect: () => openDialog({type: 'rename', id: item.id})},
    {label: 'Move to…', icon: 'move', onSelect: () => openDialog({type: 'move', ids: [item.id]})},
    {label: 'Copy to…', icon: 'copy', onSelect: () => openDialog({type: 'move', ids: [item.id], mode: 'copy'})},
    'separator',
    {label: 'Open in another app', icon: 'external', onSelect: () => openExternally([item.id])},
    {label: 'Show in folder', icon: 'folder', onSelect: () => revealInFolder(item.id)},
    'separator',
    {label: 'Delete file…', icon: 'trash', tone: 'danger', onSelect: () => openDialog({type: 'deleteFiles', ids: [item.id]})},
  )
  return menu
}
