// Home: "Made from your listening", mix tiles built from what the person plays. What a mix holds is the shared core logic
// (gui/shared/core/home.ts). The new-releases card of Home lives in Follow.tsx.
import type {Mix} from '../../shared/core/home'
import {Icon} from '../ui/Icon'
import {Art} from './Art'
import {useLibItem} from './libStore'

function MixTile({mix, onOpen}: {mix: Mix; onOpen: () => void}) {
  const first = useLibItem(mix.ids[0])
  return (
    <button type="button" className="mix-tile" onClick={onOpen} aria-label={`${mix.title}. ${mix.blurb}`}>
      <span className="mix-art">{first && <Art id={first.id} hasArt={first.hasArt} kind={first.kind} size={20} />}</span>
      <span className="mix-text">
        <span className="mix-title">{mix.title}</span>
        <span className="mix-sub">{mix.blurb}</span>
      </span>
    </button>
  )
}

export function MixSection({mixes, onOpen, onSeeAll, hasSongs}: {mixes: Mix[]; onOpen: (mix: Mix) => void; onSeeAll: () => void; hasSongs: boolean}) {
  return (
    <section className="home-group" aria-label="Made from your listening">
      <header className="shelf-head">
        <div className="shelf-heading">
          <h3 className="group-title">Made from your listening</h3>
          {mixes.length > 0 && <p className="muted small">Mixes update as you play</p>}
        </div>
        {mixes.length > 0 && (
          <button type="button" className="link-btn" onClick={onSeeAll} aria-label="See all mixes made from your listening">
            See all
          </button>
        )}
      </header>
      {mixes.length > 0 ? (
        <div className="mix-grid" role="list" aria-label="Mixes">
          {mixes.map((mix) => (
            <div key={mix.id} role="listitem">
              <MixTile mix={mix} onOpen={() => onOpen(mix)} />
            </div>
          ))}
        </div>
      ) : (
        <div className="home-hint" role="note">
          <span className="home-hint-icon"><Icon name="music" size={18} /></span>
          <div>
            <div className="home-hint-title">Nothing played yet</div>
            <p className="muted small">
              {hasSongs
                ? 'Play a few songs and Keepfin builds On repeat, Forgotten favourites and more from what you play. It all stays on this computer.'
                : 'Keep some music and play it: Keepfin builds On repeat, Forgotten favourites and more from what you play. It all stays on this computer.'}
            </p>
          </div>
        </div>
      )}
    </section>
  )
}
