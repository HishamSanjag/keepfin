// "Music videos": a shelf of cards that shows only when some song has a video; with See all where the page can open.
import {musicVideoWords, type MusicVideoRow} from '../../../shared/library'
import {MusicVideoCard, useRowMenu} from './cells'

const SHELF = 14

/** The cards alone (Search puts them among the videos it found). */
export function MusicVideoCards({rows, limit = SHELF}: {rows: readonly MusicVideoRow[]; limit?: number}) {
  const {openMenu, menuNode} = useRowMenu()
  return (
    <>
      <div className="shelf shelf-posters shelf-square" data-shape="square" role="list" aria-label={musicVideoWords.shelf}>
        {rows.slice(0, limit).map((row) => (
          <div key={row.id} role="listitem" className="shelf-item">
            <MusicVideoCard row={row} onMenu={openMenu} />
          </div>
        ))}
      </div>
      {menuNode}
    </>
  )
}

export function MusicVideoShelf({rows, onSeeAll}: {rows: readonly MusicVideoRow[]; onSeeAll?: () => void}) {
  if (!rows.length) return null
  return (
    <section className="home-section lib-section" aria-label={musicVideoWords.shelf}>
      <header className="shelf-head">
        <h3 className="shelf-title">{musicVideoWords.shelf}</h3>
        {onSeeAll && (
          <button type="button" className="link-btn" onClick={onSeeAll} aria-label={`${musicVideoWords.seeAll} ${musicVideoWords.shelf}`}>
            {musicVideoWords.seeAll}
          </button>
        )}
      </header>
      <MusicVideoCards rows={rows} />
    </section>
  )
}
