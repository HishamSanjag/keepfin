import {useVirtualizer} from '@tanstack/react-virtual'
import {useCallback, useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode} from 'react'
import {cardSize, type CardKind} from '../../shared/core/layout'
import {useCardPrefs} from '../media/layoutStore'

export type CellState = {index: number; focused: boolean; tabIndex: 0 | -1}

/** One line of title or info under a card, in px; the Text setting takes them away. */
const TEXT_LINE = 20
const textHeight = (full: number, text: 'full' | 'title' | 'none') => (text === 'full' ? full : text === 'title' ? Math.max(0, full - TEXT_LINE) : 0)

/**
 * A virtualised list or grid with one tab stop and arrow-key navigation (roving focus). Cells render their main
 * control with `data-focus` and the given tabIndex; everything else in a cell stays out of the tab order and is
 * reachable from the item's context menu.
 */
export function VirtualCollection({
  count,
  mode,
  rowHeight,
  cardKind = 'square',
  cellAspect = 1,
  cellExtra = 56,
  gap = 16,
  keyOf,
  renderCell,
  header,
  label,
  className = '',
  onContextKey,
  focusable,
  onSelectAll,
  density,
}: {
  count: number
  mode: 'list' | 'grid'
  /** List row height in px. */
  rowHeight: number
  /** Grid: the kind of card, whose width follows the Card size setting. */
  cardKind?: CardKind
  /** Grid: height of the media part as a fraction of the cell width. */
  cellAspect?: number
  /** Grid: px of text under the media part. */
  cellExtra?: number
  gap?: number
  keyOf: (index: number) => string
  renderCell: (state: CellState) => ReactNode
  header?: ReactNode
  label: string
  className?: string
  /** Called for the Menu key / Shift+F10 on the focused cell. */
  onContextKey?: (index: number, anchor: DOMRect) => void
  /** Cells that cannot take focus (section headings) are skipped by the arrow keys. */
  focusable?: (index: number) => boolean
  /** Ctrl+A (Cmd+A) while focus is inside the list, including its header: select everything the list shows. */
  onSelectAll?: () => void
  density?: string
}) {
  const {scale, text} = useCardPrefs()
  const scroller = useRef<HTMLDivElement>(null)
  const headerBox = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  const [margin, setMargin] = useState(0)
  const [focusIndex, setFocusIndex] = useState(0)
  const pendingFocus = useRef<number | null>(null)

  useLayoutEffect(() => {
    const el = scroller.current
    if (!el) return
    const read = () => setWidth(el.clientWidth)
    read()
    const observer = new ResizeObserver(read)
    observer.observe(el)
    return () => observer.disconnect()
  }, [])
  useLayoutEffect(() => {
    const el = headerBox.current
    if (!el) return setMargin(0)
    const read = () => setMargin(el.offsetHeight)
    read()
    const observer = new ResizeObserver(read)
    observer.observe(el)
    return () => observer.disconnect()
  }, [!!header])

  const inner = Math.max(0, width - 32)
  const cellWidth = mode === 'list' ? inner : Math.round(cardSize(cardKind, inner, scale))
  const cols = mode === 'list' ? 1 : Math.max(1, Math.floor((inner + gap) / (cellWidth + gap)))
  const rowH = mode === 'list' ? rowHeight : Math.round(cellWidth * cellAspect + textHeight(cellExtra, text) + gap)
  const rows = Math.ceil(count / cols)

  const virtualizer = useVirtualizer({
    count: rows,
    getScrollElement: () => scroller.current,
    estimateSize: () => rowH || 1,
    overscan: 6,
    scrollMargin: margin,
  })
  useEffect(() => {
    virtualizer.measure()
  }, [rowH, rows, virtualizer])

  const focusCell = useCallback(
    (index: number) => {
      const clamped = Math.max(0, Math.min(count - 1, index))
      setFocusIndex(clamped)
      pendingFocus.current = clamped
      virtualizer.scrollToIndex(Math.floor(clamped / cols), {align: 'auto'})
    },
    [count, cols, virtualizer],
  )
  // After the target row has rendered, move DOM focus to its control.
  useEffect(() => {
    if (pendingFocus.current === null) return
    const target = scroller.current?.querySelector<HTMLElement>(`[data-idx="${pendingFocus.current}"] [data-focus]`)
    if (target) {
      target.focus({preventScroll: true})
      pendingFocus.current = null
    }
  })
  useEffect(() => {
    if (focusIndex >= count && count > 0) setFocusIndex(count - 1)
    else if (focusable && count > 0 && !focusable(focusIndex)) {
      for (let i = 0; i < count; i++)
        if (focusable(i)) {
          setFocusIndex(i)
          break
        }
    }
  }, [count, focusIndex, focusable])

  function onKeyDown(event: KeyboardEvent) {
    if ((event.target as HTMLElement).closest('input, textarea, select')) return
    if (onSelectAll && (event.ctrlKey || event.metaKey) && !event.shiftKey && !event.altKey && event.key.toLowerCase() === 'a') {
      event.preventDefault()
      onSelectAll()
      return
    }
    const current = Number((event.target as HTMLElement).closest('[data-idx]')?.getAttribute('data-idx') ?? focusIndex)
    const page = Math.max(1, Math.floor((scroller.current?.clientHeight || 400) / Math.max(1, rowH))) * cols
    let target: number | undefined
    switch (event.key) {
      case 'ArrowDown':
        target = current + cols
        break
      case 'ArrowUp':
        target = current - cols
        break
      case 'ArrowRight':
        if (mode === 'grid') target = current + 1
        break
      case 'ArrowLeft':
        if (mode === 'grid') target = current - 1
        break
      case 'Home':
        target = 0
        break
      case 'End':
        target = count - 1
        break
      case 'PageDown':
        target = current + page
        break
      case 'PageUp':
        target = current - page
        break
      case 'ContextMenu':
      case 'F10':
        if (event.key === 'ContextMenu' || event.shiftKey) {
          const cell = scroller.current?.querySelector<HTMLElement>(`[data-idx="${current}"]`)
          if (cell && onContextKey) {
            event.preventDefault()
            onContextKey(current, cell.getBoundingClientRect())
          }
        }
        return
    }
    if (target === undefined) return
    event.preventDefault()
    if (focusable) {
      const step = target >= current ? 1 : -1
      while (target >= 0 && target < count && !focusable(target)) target += step
    }
    if (target >= 0 && target < count) focusCell(target)
    else if (target < 0 && current > 0) focusCell(0)
    else if (target >= count && current < count - 1) focusCell(count - 1)
  }

  const items = virtualizer.getVirtualItems()
  return (
    <div ref={scroller} className={`vc-scroll ${className}`} onKeyDown={onKeyDown}
      onFocus={(event) => {
        const at = (event.target as HTMLElement).closest('[data-idx]')?.getAttribute('data-idx')
        if (at != null && Number(at) !== focusIndex) setFocusIndex(Number(at))
      }}
      data-mode={mode}
      data-density={density}
      data-card-text={text}
    >
      {header && (
        <div ref={headerBox} className="vc-header">
          {header}
        </div>
      )}
      <div role="list" aria-label={label} aria-rowcount={count} className="vc-body" style={{height: virtualizer.getTotalSize() - margin, ['--vc-gap' as string]: `${gap}px`}}>
        {rowH > 0 &&
          items.map((row) => {
            const first = row.index * cols
            const cells = []
            for (let i = first; i < Math.min(count, first + cols); i++)
              cells.push(
                <div key={keyOf(i)} role="listitem" data-idx={i} className="vc-cell">
                  {renderCell({index: i, focused: i === focusIndex, tabIndex: i === focusIndex ? 0 : -1})}
                </div>,
              )
            return (
              <div
                key={row.key}
                className={`vc-row vc-row-${mode}`}
                style={{
                  height: rowH,
                  transform: `translateY(${row.start - margin}px)`,
                  gridTemplateColumns: mode === 'grid' ? `repeat(${cols}, ${cellWidth}px)` : undefined,
                }}
              >
                {cells}
              </div>
            )
          })}
      </div>
    </div>
  )
}
