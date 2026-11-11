// Card size and Text under cards: the same two controls on a page's edit bar and in Settings > Appearance.
import {MAX_CARD_SCALE, MIN_CARD_SCALE, type CardText} from '../../shared/core/layout'
import {Segmented} from '../ui/controls'
import {setCardScale, setCardText, useCardPrefs} from './layoutStore'

const MIN_PERCENT = Math.round(MIN_CARD_SCALE * 100)
const MAX_PERCENT = Math.round(MAX_CARD_SCALE * 100)
const textOptions: {value: CardText; label: string}[] = [
  {value: 'full', label: 'Title and info'},
  {value: 'title', label: 'Title'},
  {value: 'none', label: 'None'},
]

export function CardSizeSlider() {
  const percent = Math.round(useCardPrefs().scale * 100)
  return (
    <div className="card-size">
      <span className="card-size-glyph small" aria-hidden="true" />
      <input
        type="range"
        className="range"
        min={MIN_PERCENT}
        max={MAX_PERCENT}
        step={5}
        value={percent}
        style={{
          ['--fill' as string]: `${((percent - MIN_PERCENT) / (MAX_PERCENT - MIN_PERCENT)) * 100}%`,
        }}
        aria-label="Card size"
        aria-valuetext={`${percent}%`}
        onChange={(event) => setCardScale(Number(event.target.value) / 100)}
      />
      <span className="card-size-glyph large" aria-hidden="true" />
      <span className="tabular card-size-value">{percent}%</span>
    </div>
  )
}

export function CardTextChoice() {
  return <Segmented<CardText> label="Text under cards" size="sm" value={useCardPrefs().text} onChange={setCardText} options={textOptions} />
}
