// The app bar, identical on every page and rendered once by the shell (dev-docs/plans/PAGE-LAYOUT.md): the global search on the left,
// the update indicator and Activity on the right. Settings lives in the sidebar (Ctrl+, opens it); no page has a bar of its own.
import {useRef, type ClipboardEvent, type FormEvent, type KeyboardEvent} from 'react'
import {extractLinks} from '../format'
import {routeLinks} from '../library/OmniBox'
import {setSearchQuery, useSearchQuery} from '../library/searchStore'
import {navigate, useNav} from '../nav'
import {Icon} from '../ui/Icon'
import {Activity} from './Activity'
import {UpdateIndicator} from './UpdateIndicator'

export const SEARCH_ID = 'app-search'

/** Moves the cursor into the global search (Ctrl+K and "/"). Returns false when this mode has no search. */
export function focusSearch() {
  const input = document.getElementById(SEARCH_ID) as HTMLInputElement | null
  if (!input) return false
  input.focus()
  input.select()
  return true
}

/** The global search: text opens Search as you type, a link goes to the download picker. */
function GlobalSearch() {
  const query = useSearchQuery()
  const {tab} = useNav()
  const input = useRef<HTMLInputElement>(null)
  const change = (value: string) => {
    setSearchQuery(value)
    if (value.trim() && !extractLinks(value).length && tab !== 'Search') navigate('Search')
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const text = query.trim()
    if (!text) return
    if (routeLinks(text)) setSearchQuery('')
    else if (tab !== 'Search') navigate('Search')
  }
  const paste = (event: ClipboardEvent<HTMLInputElement>) => {
    const text = event.clipboardData.getData('text/plain')
    if (!extractLinks(text).length) return
    event.preventDefault()
    routeLinks(text)
    setSearchQuery('')
  }
  const keys = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== 'Escape') return
    if (query) {
      event.stopPropagation()
      setSearchQuery('')
    } else input.current?.blur()
  }
  return (
    <form className="app-search" role="search" aria-label="Search" onSubmit={submit}>
      <Icon name={extractLinks(query).length ? 'link' : 'search'} size={16} />
      <input
        ref={input}
        id={SEARCH_ID}
        type="text"
        className="app-search-input"
        aria-label="Search your library, or paste a link"
        placeholder="Search your library, or paste a link"
        autoComplete="off"
        spellCheck={false}
        value={query}
        onChange={(event) => change(event.target.value)}
        onPaste={paste}
        onKeyDown={keys}
      />
      {query && (
        <button type="button" className="icon-btn app-search-clear" aria-label="Clear search" onClick={() => (setSearchQuery(''), input.current?.focus())}>
          <Icon name="close" size={14} />
        </button>
      )}
    </form>
  )
}

export function AppBar({search}: {search: boolean}) {
  return (
    <div className="app-bar">
      {search ? <GlobalSearch /> : <span className="app-bar-fill" />}
      <div className="app-bar-end">
        <UpdateIndicator />
        <Activity />
      </div>
    </div>
  )
}
