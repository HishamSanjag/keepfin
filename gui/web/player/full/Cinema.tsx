import {Art} from '../../library/Art'
import {usePlayer} from '../playerStore'
import {CurrentLyric} from './lyricsQueue'
import {CoverStage, FavoriteButton, PlayError, PlayerHead, SidePanel, songLine, TransportBlock, VolumeControl} from './parts'
import type {LayoutProps} from './layouts'

/** Cinema: a wide 16:9 stage with the current lyric line over the picture (or cover), and a side panel for the queue and lyrics. */
export function Cinema({item}: LayoutProps) {
  const from = usePlayer((s) => s.from)
  return (
    <div className="cinema">
      <div className="cinema-main">
        <PlayerHead />
        <div className="cinema-stage">
          <div className="full-art cinema-frame">
            <CoverStage item={item} shape="wide">
              <CurrentLyric id={item.id} />
            </CoverStage>
          </div>
        </div>
        <PlayError item={item} />
        <div className="full-controls cinema-controls">
          <div className="cinema-song">
            <Art id={item.id} hasArt={item.hasArt} kind="audio" label={item.title} className="cinema-thumb" size={20} space={item.space} />
            <div className="full-meta">
              <h2 title={item.title}>{item.title}</h2>
              <p>{songLine(item, from)}</p>
            </div>
            <FavoriteButton item={item} />
          </div>
          <TransportBlock />
          <div className="fc-side fc-end">
            <VolumeControl />
          </div>
        </div>
      </div>
      <aside className="cinema-side full-side" aria-label="Queue and lyrics">
        <SidePanel item={item} idBase="cinema" />
      </aside>
    </div>
  )
}
