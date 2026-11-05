import {useEffect, useId, useMemo, useRef, useState, type KeyboardEvent} from 'react'
import {navigate} from '../nav'
import {Icon} from '../ui/Icon'
import {categories, type SettingsCategory} from './sections'
import {entryPath, searchSettings, type SettingEntry} from './searchIndex'

/** The left rail: a search field over a static index of every setting, then the categories. */
export function SettingsRail({current, onSelect}: {current: SettingsCategory; onSelect: () => void}) {
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const input = useRef<HTMLInputElement>(null)
  const listId = useId()
  const results = useMemo(() => searchSettings(query), [query])
  const searching = query.trim().length > 0
  const nav = useRef<HTMLElement>(null)
  // In a narrow window the categories are one sideways-scrolling row: keep the current one in view.
  useEffect(() => {
    const list = nav.current
    if (list && list.scrollWidth > list.clientWidth) list.querySelector('[aria-current="page"]')?.scrollIntoView({block: 'nearest', inline: 'nearest'})
  }, [current])

  function pick(entry: SettingEntry) {
    setQuery('')
    setActive(0)
    navigate('Settings', entry.category, entry.anchor)
  }

  function key(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape' && query) {
      event.preventDefault()
      event.stopPropagation()
      setQuery('')
      setActive(0)
    } else if (event.key === 'ArrowDown' && results.length) {
      event.preventDefault()
      setActive((value) => (value + 1) % results.length)
    } else if (event.key === 'ArrowUp' && results.length) {
      event.preventDefault()
      setActive((value) => (value - 1 + results.length) % results.length)
    } else if (event.key === 'Enter' && results.length) {
      event.preventDefault()
      pick(results[Math.min(active, results.length - 1)])
    }
  }

  return (
    <aside className="settings-rail">
      <div className="settings-search" role="search">
        <Icon name="search" size={15} />
        <input
          ref={input}
          className="settings-search-input"
          type="text"
          role="combobox"
          aria-label="Search settings"
          aria-autocomplete="list"
          aria-expanded={searching}
          aria-controls={listId}
          aria-activedescendant={searching && results.length ? `${listId}-${Math.min(active, results.length - 1)}` : undefined}
          placeholder="Search settings"
          autoComplete="off"
          spellCheck={false}
          value={query}
          onChange={(event) => {
            setQuery(event.target.value)
            setActive(0)
          }}
          onKeyDown={key}
        />
        {searching && (
          <button
            type="button"
            className="settings-search-clear"
            aria-label="Clear search"
            onClick={() => {
              setQuery('')
              input.current?.focus()
            }}
          >
            <Icon name="close" size={13} />
          </button>
        )}
        {searching && (
          <ul id={listId} className="settings-search-results" role="listbox" aria-label="Matching settings">
            {results.map((entry, index) => (
              <li
                key={entry.anchor}
                id={`${listId}-${index}`}
                role="option"
                aria-selected={index === Math.min(active, results.length - 1)}
                className={index === Math.min(active, results.length - 1) ? 'is-active' : undefined}
                onMouseDown={(event) => {
                  event.preventDefault()
                  pick(entry)
                }}
                onMouseMove={() => setActive(index)}
              >
                <strong>{entry.title}</strong>
                <span className="small muted">{entryPath(entry)}</span>
              </li>
            ))}
            {results.length === 0 && (
              <li className="settings-search-empty" role="option" aria-selected="false" aria-disabled="true">
                No setting matches “{query.trim()}”
              </li>
            )}
          </ul>
        )}
      </div>
      <nav ref={nav} className="settings-nav" aria-label="Settings sections">
        {categories.map((item) => (
          <button
            key={item.id}
            type="button"
            className={item.id === current ? 'selected' : undefined}
            aria-current={item.id === current ? 'page' : undefined}
            onClick={() => {
              navigate('Settings', item.id)
              onSelect()
            }}
          >
            <Icon name={item.icon} size={16} />
            <span>{item.label}</span>
          </button>
        ))}
      </nav>
    </aside>
  )
}
