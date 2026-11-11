// The frame every card section sits in: a header (title, ‹ › for a row, See all) over a Row (one sideways line) or a Grid (wraps,
// at most three rows, then See all). Card widths come from the --card-<kind> variables of the page (useCardSizes).
import {useCallback, useContext, useLayoutEffect, useRef, useState, type ReactNode, type RefObject} from 'react'
import type {CardKind, SectionStyle} from '../../shared/core/layout'
import {IconButton} from '../ui/controls'
import {SectionEditTools} from './sectionTools'

/** Cards one section holds; the rest is behind See all. */
export const SECTION_LIMIT = 24
const GRID_ROWS = 3

type FrameProps = {
  id: string
  title: string
  count?: number
  blurb?: string
  seeAllLabel?: string
  onSeeAll?: () => void
  tools?: ReactNode
  children: ReactNode
}

/** The header and body of a section whose body is not a list of cards (Downloading now, the new-releases card, the spaces). */
export function SectionFrame({id, title, count, blurb, seeAllLabel, onSeeAll, tools, children}: FrameProps) {
  const editTools = useContext(SectionEditTools)
  return (
    <section className="media-section" data-section={id} aria-label={title}>
      <header className="shelf-head">
        <div className="shelf-heading">
          <h3 className="shelf-title">
            {title}
            {count !== undefined && <span className="muted small tabular"> {count}</span>}
          </h3>
          {blurb && <p className="muted small">{blurb}</p>}
        </div>
        <div className="section-tools">
          {editTools}
          {tools}
          {onSeeAll && (
            <button type="button" className="link-btn" onClick={onSeeAll} aria-label={seeAllLabel ?? `See all ${title}`}>
              See all
            </button>
          )}
        </div>
      </header>
      {children}
    </section>
  )
}

/** Pages a row by its visible width; the buttons stop at the ends so there is no scrolling into empty space. */
function useRowPager(count: number) {
  const row = useRef<HTMLDivElement>(null)
  const [reach, setReach] = useState({start: true, end: true})
  const measure = useCallback(() => {
    const el = row.current
    if (el)
      setReach({
        start: el.scrollLeft < 1,
        end: el.scrollLeft + el.clientWidth >= el.scrollWidth - 1,
      })
  }, [])
  useLayoutEffect(() => {
    const el = row.current
    if (!el) return
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    el.addEventListener('scroll', measure, {passive: true})
    return () => {
      observer.disconnect()
      el.removeEventListener('scroll', measure)
    }
  }, [measure, count])
  const page = (direction: -1 | 1) =>
    row.current?.scrollBy({
      left: direction * row.current.clientWidth * 0.9,
      behavior: 'smooth',
    })
  return {row, reach, page}
}

/** Hides the cards past the third row of a wrapping body, so the rest waits behind See all. */
function useGridRows(body: RefObject<HTMLDivElement | null>, count: number) {
  useLayoutEffect(() => {
    const el = body.current
    if (!el) return
    const trim = () => {
      const cards = Array.from(el.children) as HTMLElement[]
      cards.forEach((card) => (card.hidden = false))
      const tops = [...new Set(cards.map((card) => card.offsetTop))].sort((a, b) => a - b)
      const cutoff = tops[GRID_ROWS]
      if (cutoff !== undefined) cards.forEach((card) => (card.hidden = card.offsetTop >= cutoff))
    }
    trim()
    const observer = new ResizeObserver(trim)
    observer.observe(el)
    return () => observer.disconnect()
  }, [body, count])
}

type SectionProps<T> = {
  id: string
  title: string
  count?: number
  blurb?: string
  kind: CardKind
  items: readonly T[]
  itemKey: (item: T) => string
  renderItem: (item: T, index: number) => ReactNode
  style?: SectionStyle
  onSeeAll?: () => void
  seeAllLabel?: string
  /** Shown instead of the cards while there are none; without it an empty section does not show. */
  empty?: ReactNode
}

/** A titled list of cards of one kind. */
export function Section<T>({id, title, count, blurb, kind, items, itemKey, renderItem, style = 'row', onSeeAll, seeAllLabel, empty}: SectionProps<T>) {
  const grid = useRef<HTMLDivElement>(null)
  const isRow = style === 'row'
  const shown = items.slice(0, SECTION_LIMIT)
  const {row, reach, page} = useRowPager(isRow ? shown.length : 0)
  useGridRows(grid, isRow ? 0 : shown.length)
  if (!items.length && !empty) return null
  const pager = isRow && items.length > 0 && (
    <>
      <IconButton icon="chevronLeft" size="sm" label={`Earlier in ${title}`} disabled={reach.start} onClick={() => page(-1)} />
      <IconButton icon="chevron" size="sm" label={`Later in ${title}`} disabled={reach.end} onClick={() => page(1)} />
    </>
  )
  return (
    <SectionFrame id={id} title={title} count={count} blurb={blurb} onSeeAll={onSeeAll} seeAllLabel={seeAllLabel} tools={pager}>
      {items.length ? (
        <div ref={isRow ? row : grid} className={`section-body section-${style}`} data-kind={kind} role="list" aria-label={title}>
          {shown.map((item, index) => (
            <div key={itemKey(item)} role="listitem" className="section-item">
              {renderItem(item, index)}
            </div>
          ))}
        </div>
      ) : (
        empty
      )}
    </SectionFrame>
  )
}
