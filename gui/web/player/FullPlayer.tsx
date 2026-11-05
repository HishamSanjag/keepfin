import {useEffect, useRef, type PointerEvent, type WheelEvent} from 'react'
import {useLibItem} from '../library/libStore'
import {installDismissOnNavigate} from './dismissOnNavigate'
import {layoutById, usePlayerLayout} from './full/layouts'
import {usePlayer} from './playerStore'
import {closePlayer} from './transition'
import {ArtWash} from './Wash'

/**
 * The expanded music view. It owns what every style shares: the dialog, Esc and drag or scroll to collapse, focus return and the
 * artwork wash. The chosen style (player/full/layouts.ts) arranges the cover, lyrics, queue and controls inside it.
 */
export function FullPlayer() {
  const expanded = usePlayer((s) => s.expanded)
  const id = usePlayer((s) => s.currentId)
  const item = useLibItem(id)
  const [layoutId] = usePlayerLayout()
  const root = useRef<HTMLDivElement>(null)
  const wheel = useRef({sum: 0, last: 0, fired: false})
  const drag = useRef<{y: number} | null>(null)
  useEffect(() => installDismissOnNavigate(), [])

  // Scrolling down over the parts that do not scroll (cover, title, header) pulls the player back down to the bar. Lists that
  // scroll (lyrics, queue), the volume and the sliders keep their wheel.
  const onWheel = (event: WheelEvent<HTMLDivElement>) => {
    if (event.deltaY <= 0 || event.nativeEvent.defaultPrevented || document.fullscreenElement) return
    const target = event.target as HTMLElement
    // a dialog opened from the player (the equalizer) keeps its own wheel
    if (target.closest('dialog, .full-panel, .lyrics, .queue-items, .volume, .range, .menu, input, select, textarea')) return
    for (let node: HTMLElement | null = target; node && node !== event.currentTarget; node = node.parentElement)
      if (node.scrollHeight > node.clientHeight + 1 && /auto|scroll/.test(getComputedStyle(node).overflowY)) return
    const state = wheel.current
    const now = performance.now()
    if (now - state.last > 300) {
      state.sum = 0
      state.fired = false
    }
    state.last = now
    if (state.fired) return
    state.sum += event.deltaY
    if (state.sum >= 60) {
      state.fired = true
      closePlayer()
    }
  }
  // Dragging the header down does the same.
  const onHeadDown = (event: PointerEvent<HTMLElement>) => {
    const target = event.target as HTMLElement
    if (event.button !== 0 || !target.closest('.full-head') || target.closest('button, a, input, .menu')) return
    drag.current = {y: event.clientY}
    event.currentTarget.setPointerCapture(event.pointerId)
  }
  const onHeadMove = (event: PointerEvent<HTMLElement>) => {
    if (drag.current && event.clientY - drag.current.y > 72) {
      drag.current = null
      closePlayer()
    }
  }
  useEffect(() => {
    if (!expanded) return
    const returnTo = document.activeElement as HTMLElement | null
    root.current?.querySelector<HTMLElement>('.full-close')?.focus()
    const key = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || document.querySelector('.menu, dialog[open]')) return
      event.stopPropagation()
      // Esc on the full-screen picture goes back to the player, not out of it
      if (document.fullscreenElement) void document.exitFullscreen().catch(() => {})
      else closePlayer()
    }
    window.addEventListener('keydown', key)
    return () => {
      window.removeEventListener('keydown', key)
      returnTo?.focus?.()
    }
  }, [expanded])
  // choosing a style swaps the whole layout, so the menu button that was used is gone: focus the same button in the new one
  const shown = useRef(layoutId)
  useEffect(() => {
    if (shown.current === layoutId) return
    shown.current = layoutId
    root.current?.querySelector<HTMLElement>('.full-head button[aria-label="Player style"]')?.focus()
  }, [layoutId])
  if (!expanded || !item) return null
  const Layout = layoutById(layoutId).component
  return (
    <div
      className={`full-player has-wash layout-${layoutId}`}
      ref={root}
      role="dialog"
      aria-label="Now playing"
      aria-modal="false"
      onWheel={onWheel}
      onPointerDown={onHeadDown}
      onPointerMove={onHeadMove}
      onPointerUp={() => (drag.current = null)}
      onPointerCancel={() => (drag.current = null)}
    >
      <ArtWash id={item.id} hasArt={item.hasArt} space={item.space} />
      <Layout item={item} />
    </div>
  )
}
