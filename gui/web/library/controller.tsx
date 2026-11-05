import {useCallback, useEffect, useMemo, useRef, useState, type MouseEvent} from 'react'
import type {LibraryItem} from '../../shared/library'
import {readPreference, savePreference} from '../prefs'
import {playItem} from '../player/play'
import {ContextMenu} from '../ui/Menu'
import type {CollectionApi} from './cells'
import {itemMenu} from './itemMenu'
import {getLibItem} from './libStore'

export type Selection = {
  selecting: boolean
  selected: ReadonlySet<string>
  setSelecting: (on: boolean) => void
  clear: () => void
  selectAll: () => void
  toggle: (id: string) => void
}

/**
 * Selection, click/context behaviour and drag ids for one list of items. `ordered` is the list as shown (it is
 * the context for Play, so a song's neighbours become the queue). The caller renders `menu` once.
 */
export function useCollectionController(
  ordered: LibraryItem[],
  options: {onRemoveFromPlaylist?: (ids: string[]) => void; selecting?: boolean; onSelectingChange?: (on: boolean) => void; removed?: ReadonlySet<string>} = {},
) {
  const [internalSelecting, setInternalSelecting] = useState(false)
  const selecting = options.selecting ?? internalSelecting
  const changeRef = useRef(options.onSelectingChange)
  changeRef.current = options.onSelectingChange
  const setSelectingState = useCallback((on: boolean) => (changeRef.current ? changeRef.current(on) : setInternalSelecting(on)), [])
  const [selected, setSelected] = useState<ReadonlySet<string>>(() => new Set())
  const [menu, setMenu] = useState<{ids: string[]; at: {x: number; y: number}}>()
  const anchor = useRef<number | null>(null)
  const orderedRef = useRef(ordered)
  orderedRef.current = ordered
  const removeRef = useRef(options.onRemoveFromPlaylist)
  removeRef.current = options.onRemoveFromPlaylist

  // Selected items that are no longer in the list (filtered out, deleted, moved) leave the selection.
  useEffect(() => {
    setSelected((previous) => {
      if (!previous.size) return previous
      const present = new Set(ordered.map((item) => item.id))
      const next = new Set([...previous].filter((id) => present.has(id)))
      return next.size === previous.size ? previous : next
    })
  }, [ordered])

  const setSelecting = useCallback(
    (on: boolean) => {
      setSelectingState(on)
      if (!on) {
        setSelected(new Set())
        anchor.current = null
      }
    },
    [setSelectingState],
  )
  // Leaving select mode from outside (the toolbar) also clears what was picked.
  useEffect(() => {
    if (!selecting) {
      setSelected((previous) => (previous.size ? new Set() : previous))
      anchor.current = null
    }
  }, [selecting])
  const toggle = useCallback((id: string) => {
    setSelected((previous) => {
      const next = new Set(previous)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }, [])

  const selectedRef = useRef(selected)
  selectedRef.current = selected
  const selectingRef = useRef(selecting)
  selectingRef.current = selecting

  const api = useMemo<CollectionApi>(
    () => ({
      selecting,
      selected,
      activate: (id: string, index: number, event: MouseEvent | {shiftKey: boolean; ctrlKey: boolean; metaKey: boolean}) => {
        const modifier = event.ctrlKey || event.metaKey
        if (selectingRef.current || modifier || event.shiftKey) {
          if (!selectingRef.current) setSelectingState(true)
          if (event.shiftKey && anchor.current !== null) {
            const [from, to] = [anchor.current, index].sort((a, b) => a - b)
            setSelected((previous) => {
              const next = new Set(previous)
              for (const item of orderedRef.current.slice(from, to + 1)) next.add(item.id)
              return next
            })
          } else {
            toggle(id)
            anchor.current = index
          }
          return
        }
        const item = getLibItem(id)
        if (item) playItem(item, orderedRef.current)
      },
      menuAt: (id: string, at: {x: number; y: number}) => {
        const chosen = selectedRef.current
        setMenu({ids: chosen.has(id) && chosen.size > 1 ? [...chosen] : [id], at})
      },
      dragIds: (id: string) => (selectedRef.current.has(id) ? [...selectedRef.current] : [id]),
      removed: options.removed,
    }),
    [selecting, selected, toggle, options.removed],
  )

  const selection: Selection = useMemo(
    () => ({
      selecting,
      selected,
      setSelecting,
      clear: () => setSelected(new Set()),
      selectAll: () => {
        setSelectingState(true)
        setSelected(new Set(orderedRef.current.map((item) => item.id)))
      },
      toggle,
    }),
    [selecting, selected, setSelecting, toggle],
  )

  const openMenuFor = useCallback((index: number, rect: DOMRect) => {
    const item = orderedRef.current[index]
    if (item) setMenu({ids: [item.id], at: {x: rect.left + 24, y: rect.top + rect.height / 2}})
  }, [])

  const menuItems = useMemo(() => {
    if (!menu) return []
    const items = menu.ids.map(getLibItem).filter((item): item is LibraryItem => !!item)
    return itemMenu(items, {
      context: orderedRef.current,
      onRemoveFromPlaylist: removeRef.current ? () => removeRef.current?.(menu.ids) : undefined,
    })
  }, [menu])

  const menuNode = <ContextMenu label="Item actions" items={menuItems} at={menu?.at} onClose={() => setMenu(undefined)} />
  return {api, selection, menuNode, openMenuFor}
}

/** A per-space preference stored in localStorage (never throws). */
export function useStoredChoice<T extends string>(key: string, fallback: T, allowed: readonly T[]): [T, (value: T) => void] {
  const [value, setValue] = useState<T>(() => readPreference<T>(key, fallback, (v) => allowed.includes(v as T)))
  // A different space (new key) reads its own saved choice.
  const last = useRef(key)
  useEffect(() => {
    if (last.current !== key) {
      last.current = key
      setValue(readPreference<T>(key, fallback, (v) => allowed.includes(v as T)))
    }
  }, [key])
  return [
    value,
    (next: T) => {
      setValue(next)
      savePreference(key, next)
    },
  ]
}

export type TileSelect = {selecting: boolean; selected: boolean; toggle: () => void}

/**
 * Selection for tiles that stand for several items (albums, artists, playlists, folders). Selecting a tile
 * selects everything inside it; `ids` is the de-duplicated list of items the bar's actions apply to.
 */
export function useTileSelection(
  tiles: {key: string; items: LibraryItem[]}[],
  options: {selecting: boolean; onSelectingChange: (on: boolean) => void},
) {
  const {selecting, onSelectingChange} = options
  const [selected, setSelected] = useState<ReadonlySet<string>>(() => new Set())
  const tilesRef = useRef(tiles)
  tilesRef.current = tiles
  // Tiles that disappear (search, filters, deletion) leave the selection; leaving select mode clears it.
  useEffect(() => {
    setSelected((previous) => {
      if (!previous.size) return previous
      const present = new Set(tiles.map((tile) => tile.key))
      const next = selecting ? new Set([...previous].filter((key) => present.has(key))) : new Set<string>()
      return next.size === previous.size ? previous : next
    })
  }, [tiles, selecting])
  const toggle = useCallback(
    (key: string) => {
      if (!selecting) onSelectingChange(true)
      setSelected((previous) => {
        const next = new Set(previous)
        if (next.has(key)) next.delete(key)
        else next.add(key)
        return next
      })
    },
    [selecting, onSelectingChange],
  )
  const selectAll = useCallback(() => {
    onSelectingChange(true)
    setSelected(new Set(tilesRef.current.map((tile) => tile.key)))
  }, [onSelectingChange])
  const clear = useCallback(() => setSelected(new Set()), [])
  const ids = useMemo(() => {
    const out = new Set<string>()
    for (const tile of tiles) if (selected.has(tile.key)) for (const item of tile.items) out.add(item.id)
    return [...out]
  }, [tiles, selected])
  const forTile = useCallback((key: string): TileSelect => ({selecting, selected: selected.has(key), toggle: () => toggle(key)}), [selecting, selected, toggle])
  return {selected, count: selected.size, total: tiles.length, ids, selectAll, clear, forTile, selecting}
}
