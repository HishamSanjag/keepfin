// What a music video row does: play alone, go to its song, delete only the video, show its file.
import {musicVideoWords, type MusicVideoRow} from '../../../shared/library'
import {navigate} from '../../nav'
import {libraryAct, getLibItem} from '../libStore'
import {openMusicVideo} from '../../player/play'
import type {MenuItem} from '../../ui/Menu'
import {openDialog} from '../dialogState'
import {setLibView} from '../libNav'
import {norm} from '../model'

/** The song's album page, else its artist's, else its space: where the song is in the library. */
export function goToSong(itemId: string) {
  const song = getLibItem(itemId)
  if (!song) return
  const artist = song.artist || song.albumArtist
  if (song.album) setLibView({type: 'album', key: `${norm(song.album)}|${norm(song.albumArtist)}`})
  else if (artist) setLibView({type: 'artist', key: norm(artist)})
  else setLibView({type: 'space', id: song.space})
  navigate('Library')
}

/** The row's menu. Delete video is the song's Remove music video: the same confirmation, the song stays. */
export const musicVideoMenu = (row: MusicVideoRow): MenuItem[] => [
  {label: 'Play', icon: 'play', onSelect: () => openMusicVideo(row.itemId)},
  {label: musicVideoWords.goToSong, icon: 'music', onSelect: () => goToSong(row.itemId)},
  {label: musicVideoWords.delete, icon: 'trash', tone: 'danger', onSelect: () => openDialog({type: 'removeMusicVideo', id: row.itemId})},
  'separator',
  {label: 'Show in folder', icon: 'folder', onSelect: () => void libraryAct('libraryReveal', row.itemId, {video: true})},
]
