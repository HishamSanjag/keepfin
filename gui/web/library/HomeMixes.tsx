// Home: "Made from your listening", mix cards built from what the person plays. What a mix holds is the shared core logic
// (gui/shared/core/home.ts). The new-releases card of Home lives in Follow.tsx.
import type {SectionStyle} from '../../shared/core/layout'
import type {Mix} from '../../shared/core/home'
import {Section} from '../media/Section'
import {Icon} from '../ui/Icon'
import {OpenCard, sectionCell} from './cells'
import {getLibItem} from './libStore'

const covers = (mix: Mix) => mix.ids.filter((id) => getLibItem(id)?.hasArt).slice(0, 4)

function NothingPlayed({hasSongs}: {hasSongs: boolean}) {
  return (
    <div className="home-hint" role="note">
      <span className="home-hint-icon">
        <Icon name="music" size={18} />
      </span>
      <div>
        <div className="home-hint-title">Nothing played yet</div>
        <p className="muted small">
          {hasSongs
            ? 'Play a few songs and Keepfin builds On repeat, Forgotten favourites and more from what you play. It all stays on this computer.'
            : 'Keep some music and play it: Keepfin builds On repeat, Forgotten favourites and more from what you play. It all stays on this computer.'}
        </p>
      </div>
    </div>
  )
}

export function MixSection({mixes, style, onOpen, onSeeAll, hasSongs}: {mixes: Mix[]; style: SectionStyle; onOpen: (mix: Mix) => void; onSeeAll: () => void; hasSongs: boolean}) {
  return (
    <Section
      id="mixes"
      title="Made from your listening"
      blurb={mixes.length > 0 ? 'Mixes update as you play' : undefined}
      kind="mix"
      style={style}
      items={mixes}
      itemKey={(mix) => mix.id}
      onSeeAll={onSeeAll}
      seeAllLabel="See all mixes made from your listening"
      empty={<NothingPlayed hasSongs={hasSongs} />}
      renderItem={(mix, index) => (
        <OpenCard state={sectionCell(index)} kind="mix" title={mix.title} sub={mix.blurb} badge="MIX" art={covers(mix)} icon="music" label={`${mix.title}. ${mix.blurb}`} onOpen={() => onOpen(mix)} />
      )}
    />
  )
}
