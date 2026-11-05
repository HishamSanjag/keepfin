// Tag chips for the items in scope, with Any/All, "Play N" and "Save as smart space".
import {useMemo, useState} from 'react'
import {tagFilterName, tagFilterRules, type LibraryItem} from '../../shared/library'
import {playAll} from '../player/play'
import {Button, Segmented} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {openDialog} from './dialogState'
import {tagsInScope} from './spacesModel'

const SHOWN = 14

export type TagFilterState = {tags: string[]; mode: 'any' | 'all'}
export const noTagFilter: TagFilterState = {tags: [], mode: 'any'}

export function TagFilter({scope, state, onChange, result, spaceId}: {scope: readonly LibraryItem[]; state: TagFilterState; onChange: (state: TagFilterState) => void; result: readonly LibraryItem[]; spaceId?: string}) {
  const tags = useMemo(() => tagsInScope(scope), [scope])
  const [all, setAll] = useState(false)
  if (!tags.length) return null
  const visible = all ? tags : tags.filter((entry, index) => index < SHOWN || state.tags.includes(entry.tag))
  const toggle = (tag: string) => onChange({...state, tags: state.tags.includes(tag) ? state.tags.filter((entry) => entry !== tag) : [...state.tags, tag]})
  const playable = result.filter((item) => item.kind === 'audio' || item.kind === 'video')
  return (
    <section className="tag-filter" aria-label="Filter by tag">
      <div className="chips" role="group" aria-label="Tags">
        {visible.map((entry) => (
          <button key={entry.tag} type="button" className={`chip chip-lg${state.tags.includes(entry.tag) ? ' on' : ''}${entry.user ? ' chip-user' : ''}`} aria-pressed={state.tags.includes(entry.tag)} onClick={() => toggle(entry.tag)}>
            {entry.user && <Icon name="tag" size={12} />}
            {entry.tag}
            <span className="chip-count tabular">{entry.count}</span>
          </button>
        ))}
        {tags.length > visible.length && (
          <button type="button" className="link-btn" onClick={() => setAll(true)}>
            All {tags.length} tags
          </button>
        )}
        {all && tags.length > SHOWN && (
          <button type="button" className="link-btn" onClick={() => setAll(false)}>
            Fewer tags
          </button>
        )}
      </div>
      {state.tags.length > 0 && (
        <div className="tag-actions">
          {state.tags.length > 1 && (
            <Segmented<'any' | 'all'>
              label="Match tags"
              size="sm"
              value={state.mode}
              onChange={(mode) => onChange({...state, mode})}
              options={[
                {value: 'any', label: 'Any'},
                {value: 'all', label: 'All'},
              ]}
            />
          )}
          <Button size="sm" variant="primary" icon="play" disabled={!playable.length} onClick={() => playAll(playable)}>
            Play {playable.length}
          </Button>
          <Button
            size="sm"
            icon="smart"
            onClick={() =>
              openDialog({type: 'space', draft: {type: 'smart', name: tagFilterName(state.tags), icon: 'star', rules: tagFilterRules(state.tags, state.mode, spaceId)}})
            }
          >
            Save as smart space
          </Button>
          <Button size="sm" variant="ghost" onClick={() => onChange(noTagFilter)}>
            Clear tags
          </Button>
        </div>
      )}
    </section>
  )
}
