import type {CSSProperties} from 'react'
import {useArtColor} from '../../library/artColor'
import {Segmented} from '../../ui/controls'
import {SeekBar, Transport} from '../NowPlaying'
import {usePlayer} from '../playerStore'
import {darkenedTint} from '../tint'
import {LyricsPanel, pickSide, QueuePanel, useSide, type Side} from './lyricsQueue'
import {CoverStage, FavoriteButton, PlayError, PlayerHead, songLine, VolumeControl} from './parts'
import {Visualizer} from './Visualizer'
import type {LayoutProps} from './layouts'

/** The glow behind everything: the cover's own colour, soft and large (capped in brightness so the lyrics keep AA contrast). It drifts slowly (not at all with reduced motion). */
function Glow({item}: LayoutProps) {
  const color = darkenedTint(useArtColor(item.hasArt ? item.id : undefined), 0.16)
  return <div className="afterglow-glow" aria-hidden="true" style={color ? ({['--glow' as string]: color} as CSSProperties) : undefined} />
}

/**
 * Afterglow: a glow from the cover's colours, large karaoke lyrics, and a visualizer under the cover. The header's Lyrics | Up next
 * control swaps the big centre area between the lyrics and the whole queue; a song without lyrics opens on Up next.
 */
export function Afterglow({item}: LayoutProps) {
  const from = usePlayer((s) => s.from)
  const view = useSide(item.id, 'lyrics')
  return (
    <>
      <Glow item={item} />
      <PlayerHead>
        <Segmented<Side>
          label="Player view"
          size="sm"
          value={view}
          onChange={pickSide}
          options={[
            {value: 'lyrics', label: 'Lyrics'},
            {value: 'queue', label: 'Up next'},
          ]}
        />
      </PlayerHead>
      <div className="afterglow-body">
        <section className="afterglow-main" aria-label="Current song">
          <div className="full-art afterglow-art">
            <CoverStage item={item} />
          </div>
          <Visualizer />
          <div className="full-meta">
            <h2 title={item.title}>{item.title}</h2>
            <p>{songLine(item, from)}</p>
          </div>
          <SeekBar />
          <Transport large />
          <div className="afterglow-extras">
            <FavoriteButton item={item} />
            <VolumeControl />
          </div>
          <PlayError item={item} />
        </section>
        {view === 'lyrics' ? (
          <section className="afterglow-lyrics full-panel" aria-label="Lyrics">
            <LyricsPanel id={item.id} />
          </section>
        ) : (
          <section className="afterglow-queue full-panel" aria-label="Up next">
            <QueuePanel />
          </section>
        )}
      </div>
    </>
  )
}
