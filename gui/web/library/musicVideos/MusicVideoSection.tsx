// "Music videos": a section of cards that shows only when some song has a video; See all opens the list where the page can.
import {musicVideoWords, type MusicVideoRow} from '../../../shared/library'
import type {SectionStyle} from '../../../shared/core/layout'
import {formatDuration} from '../../format'
import {Section} from '../../media/Section'
import {openMusicVideo} from '../../player/play'
import {Menu} from '../../ui/Menu'
import {OpenCard, sectionCell} from '../cells'
import {getLibItem} from '../libStore'
import {musicVideoMenu} from './actions'
import {useRowMenu} from './cells'

export function MusicVideoSection({rows, style, onSeeAll}: {rows: readonly MusicVideoRow[]; style?: SectionStyle; onSeeAll?: () => void}) {
  const {openMenu, menuNode} = useRowMenu()
  return (
    <>
      <Section
        id="music-videos"
        title={musicVideoWords.shelf}
        kind="square"
        style={style}
        items={rows}
        itemKey={(row) => row.id}
        onSeeAll={onSeeAll}
        seeAllLabel={`${musicVideoWords.seeAll} ${musicVideoWords.shelf}`}
        renderItem={(row, index) => (
          <OpenCard
            state={sectionCell(index)}
            play
            title={row.title}
            tag={musicVideoWords.label}
            sub={row.artist}
            art={getLibItem(row.itemId)?.hasArt ? [row.itemId] : []}
            icon="video"
            badge={row.duration ? formatDuration(row.duration) : undefined}
            label={`${row.title}, ${musicVideoWords.label}`}
            onOpen={() => openMusicVideo(row.itemId)}
            onContextMenu={(at) => openMenu(row, at)}
            menu={() => <Menu label={`More actions for ${row.title}`} items={musicVideoMenu(row)} tabbable={false} />}
          />
        )}
      />
      {menuNode}
    </>
  )
}
