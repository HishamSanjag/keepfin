// "Add to playlist" in the footer of a review: the playlist the finished download(s) join. The choice belongs to this review only.
import {playlistLabel, playlistWords} from '../../shared/library'
import {openDialog} from '../library/dialogState'
import {useLibrary, useLibraryEnabled} from '../library/libStore'
import {Icon} from '../ui/Icon'
import {Menu, type MenuItem} from '../ui/Menu'

export function AddToPlaylist({value, onChange}: {value?: string; onChange: (id: string | undefined) => void}) {
  const enabled = useLibraryEnabled()
  const {playlists} = useLibrary()
  if (!enabled) return null
  const chosen = playlists.find((playlist) => playlist.id === value)
  const items: MenuItem[] = [
    {label: playlistWords.none, checked: !chosen, onSelect: () => onChange(undefined)},
    ...playlists.map((playlist) => ({label: playlist.name, checked: playlist.id === chosen?.id, onSelect: () => onChange(playlist.id)})),
    'separator',
    {label: playlistWords.new, icon: 'plus', onSelect: () => openDialog({type: 'playlistName', onMade: onChange})},
  ]
  return (
    <span className="save-to">
      <Menu
        label={`${playlistLabel(chosen?.name)}. Choose a playlist for this download`}
        items={items}
        align="start"
        width={260}
        triggerClassName="save-chip"
        trigger={
          <>
            <Icon name="playlistAdd" size={15} />
            <strong>{playlistLabel(chosen?.name)}</strong>
            <Icon name="chevronDown" size={13} />
          </>
        }
      />
    </span>
  )
}
