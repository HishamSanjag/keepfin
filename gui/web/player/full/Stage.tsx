import {usePlayer} from '../playerStore'
import {CoverStage, FavoriteButton, FormatChip, NextUp, PlayError, PlayerHead, SidePanel, songLine, TransportBlock, VolumeControl} from './parts'
import type {LayoutProps} from './layouts'

/** Stage: a big cover (or its picture) on the left, large lyrics on the right, one control bar along the bottom. */
export function Stage({item}: LayoutProps) {
  const from = usePlayer((s) => s.from)
  return (
    <>
      <PlayerHead />
      <div className="full-body">
        <section className="full-main" aria-label="Current song">
          <div className="full-art">
            <CoverStage item={item} />
          </div>
          <div className="full-title">
            <div className="full-meta">
              <h2 title={item.title}>{item.title}</h2>
              <p>{songLine(item, from)}</p>
            </div>
            <FavoriteButton item={item} />
          </div>
          <PlayError item={item} />
        </section>
        <section className="full-side" aria-label="Lyrics and queue">
          <SidePanel item={item} />
        </section>
      </div>
      {/* The controls sit on an opaque layer, so they stay legible whatever the artwork wash behind them looks like. */}
      <div className="full-controls">
        <div className="fc-side">
          <FormatChip item={item} />
          <NextUp />
        </div>
        <TransportBlock />
        <div className="fc-side fc-end">
          <VolumeControl />
        </div>
      </div>
    </>
  )
}
