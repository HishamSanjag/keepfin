// A music video as a card (shelves) or a row (lists). The cover is the song's; clicking plays the video alone.
import '../../styles/musicvideos.css'
import {memo, useState} from 'react'
import {musicVideoWords, type MusicVideoRow} from '../../../shared/library'
import {formatBytes, formatDuration} from '../../format'
import {openMusicVideo} from '../../player/play'
import {Icon} from '../../ui/Icon'
import {ContextMenu, Menu} from '../../ui/Menu'
import {Art} from '../Art'
import {useLibItem} from '../libStore'
import {musicVideoMenu} from './actions'

type OnMenu = (row: MusicVideoRow, at: {x: number; y: number}) => void

/** A square card with the song's cover; Play opens the video. */
export const MusicVideoCard = memo(function MusicVideoCard({row, onMenu}: {row: MusicVideoRow; onMenu: OnMenu}) {
  const song = useLibItem(row.itemId)
  return (
    <div className="card" onContextMenu={(event) => (event.preventDefault(), onMenu(row, {x: event.clientX, y: event.clientY}))}>
      <button type="button" className="card-main" data-focus aria-label={`${row.title}, ${musicVideoWords.label}`} onClick={() => openMusicVideo(row.itemId)}>
        <span className="card-art square">
          <Art id={row.itemId} hasArt={song?.hasArt} kind="audio" label={row.title} size={30} space={song?.space} />
          {row.duration ? <span className="card-time tabular">{formatDuration(row.duration)}</span> : null}
          <span className="card-play" aria-hidden="true">
            <Icon name="play" size={18} />
          </span>
        </span>
        <span className="card-title" title={row.title}>
          {row.title}
        </span>
        <span className="card-sub">
          <span className="row-tag">{musicVideoWords.label}</span>
          {row.artist}
        </span>
      </button>
      <span className="card-actions">
        <Menu label={`More actions for ${row.title}`} items={musicVideoMenu(row)} tabbable={false} />
      </span>
    </div>
  )
})

/** A list row: cover, title, artist, the video's size and length, with the small "Music video" label. */
export const MusicVideoRowItem = memo(function MusicVideoRowItem({row, onMenu}: {row: MusicVideoRow; onMenu: OnMenu}) {
  const song = useLibItem(row.itemId)
  return (
    <div className="song-row mv-row" onContextMenu={(event) => (event.preventDefault(), onMenu(row, {x: event.clientX, y: event.clientY}))}>
      <button type="button" className="row-main" data-focus aria-label={`${row.title}${row.artist ? `, ${row.artist}` : ''}, ${musicVideoWords.label}`} onClick={() => openMusicVideo(row.itemId)}>
        <Art id={row.itemId} hasArt={song?.hasArt} kind="audio" label={row.title} className="row-art" size={18} space={song?.space} />
        <span className="row-text">
          <span className="row-title" title={row.title}>
            {row.title}
          </span>
          <span className="row-sub">
            <span className="row-tag">{musicVideoWords.label}</span>
            {row.artist}
          </span>
        </span>
        {row.size ? <span className="row-col row-size tabular">{formatBytes(row.size)}</span> : null}
        <span className="row-col row-time tabular">{row.duration ? formatDuration(row.duration) : ''}</span>
      </button>
      <span className="row-actions">
        <Menu label={`More actions for ${row.title}`} items={musicVideoMenu(row)} tabbable={false} />
      </span>
    </div>
  )
})

/** The right-click menu of a list of rows: `openMenu` goes to each cell, `menuNode` is rendered once beside the list. */
export function useRowMenu() {
  const [menu, setMenu] = useState<{row: MusicVideoRow; at: {x: number; y: number}}>()
  const menuNode = <ContextMenu label="Music video actions" items={menu ? musicVideoMenu(menu.row) : []} at={menu?.at} onClose={() => setMenu(undefined)} />
  return {openMenu: (row: MusicVideoRow, at: {x: number; y: number}) => setMenu({row, at}), menuNode}
}
