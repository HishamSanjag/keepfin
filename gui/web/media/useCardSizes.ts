// A page's card sizes: the shared core turns the page width and the Card size setting into one size per kind, written as CSS
// variables on the page root, so a resize re-lays every card without React work per card.
import {useEffect, useState} from 'react'
import {CARD_KINDS, cardSize} from '../../shared/core/layout'
import {useCardPrefs} from './layoutStore'

/** Returns the ref callback for the page's root element, which also carries `data-card-text`. */
export function useCardSizes() {
  const {scale, text} = useCardPrefs()
  const [root, setRoot] = useState<HTMLElement | null>(null)
  useEffect(() => {
    if (!root) return
    root.dataset.cardText = text
    const apply = () => {
      for (const kind of CARD_KINDS) root.style.setProperty(`--card-${kind}`, `${cardSize(kind, root.clientWidth, scale)}px`)
    }
    apply()
    let frame = 0
    const observer = new ResizeObserver(() => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(apply)
    })
    observer.observe(root)
    return () => {
      cancelAnimationFrame(frame)
      observer.disconnect()
    }
  }, [root, scale, text])
  return setRoot
}
