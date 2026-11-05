// Rules of a smart space: match any/all, one row per rule with value pickers fed from the library, the kind and
// space exclusions, and a live count of what the rules gather right now.
import {useDeferredValue, useId, useMemo, useState} from 'react'
import {normalizeArtists, type LibraryItemKind, type SmartRule, type SmartRules, type Space} from '../../shared/library'
import {Button, IconButton, Segmented, Select} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {useLibrary} from './libStore'
import {
  emptyRule,
  hasValues,
  kindOptions,
  ruleFields,
  smartMatches,
  valueOptions,
  type RuleField,
  type ValueField,
  type ValueOption,
} from './spacesModel'

const foldText = (value: string) => value.normalize('NFKD').replace(/\p{M}+/gu, '').toLowerCase()
const TOGGLED: ValueField[] = ['kind', 'space']

/** Rules with a value picker that were left empty are dropped; what remains is what gets saved. */
export function cleanRules(rules: SmartRules): SmartRules {
  const kept = rules.rules.filter((rule) => (hasValues(rule.field) ? (rule as Extract<SmartRule, {values: string[]}>).values.length > 0 : rule.field !== 'addedWithin' || rule.days > 0))
  const out: SmartRules = {match: rules.match, rules: kept}
  if (rules.onlyKind) out.onlyKind = rules.onlyKind
  if (rules.excludeSpaces?.length) out.excludeSpaces = rules.excludeSpaces
  return out
}

function ValuePicker({field, values, options, ruleNo, onChange}: {field: ValueField; values: string[]; options: ValueOption[]; ruleNo: number; onChange: (values: string[]) => void}) {
  const [text, setText] = useState('')
  const [error, setError] = useState('')
  const listId = useId()
  const labelOf = (value: string) => options.find((option) => option.value === value)?.label ?? value
  const fieldLabel = ruleFields.find((entry) => entry.value === field)!.label.toLowerCase()
  function toggle(value: string) {
    onChange(values.includes(value) ? values.filter((entry) => entry !== value) : [...values, value])
  }
  if (TOGGLED.includes(field))
    return (
      <div className="chips" role="group" aria-label={`${fieldLabel} for rule ${ruleNo}`}>
        {options.map((option) => (
          <button key={option.value} type="button" className={`chip chip-lg${values.includes(option.value) ? ' on' : ''}`} aria-pressed={values.includes(option.value)} onClick={() => toggle(option.value)}>
            {option.label}
          </button>
        ))}
      </div>
    )
  function commit() {
    const typed = text.trim()
    if (!typed) return
    const hit = options.find((option) => option.label.toLowerCase() === typed.toLowerCase() || option.value === typed.toLowerCase())
    // Tags, artists and albums may be typed freely; collections and sources must exist.
    const free = field === 'tag' ? typed.toLowerCase() : field === 'artist' ? normalizeArtists(typed)[0] : field === 'album' ? foldText(typed).trim() : undefined
    const value = hit?.value ?? free
    if (!value) {
      setError(`Pick a ${fieldLabel} from the list.`)
      return
    }
    setError('')
    if (!values.includes(value)) onChange([...values, value])
    setText('')
  }
  return (
    <div className="value-picker">
      {values.length > 0 && (
        <ul className="value-chips" aria-label={`Chosen ${fieldLabel} values`}>
          {values.map((value) => (
            <li key={value} className="value-chip">
              <span>{labelOf(value)}</span>
              <IconButton icon="close" size="sm" label={`Remove ${labelOf(value)}`} onClick={() => toggle(value)} />
            </li>
          ))}
        </ul>
      )}
      <div className="value-add">
        <input
          className={`input${error ? ' invalid' : ''}`}
          list={listId}
          aria-label={`Add a ${fieldLabel} to rule ${ruleNo}`}
          aria-invalid={!!error}
          placeholder={field === 'tag' ? 'Type or pick a tag' : `Pick a ${fieldLabel}`}
          value={text}
          onChange={(event) => {
            setText(event.target.value)
            setError('')
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              commit()
            }
          }}
        />
        <datalist id={listId}>
          {options.map((option) => (
            <option key={option.value} value={option.label} />
          ))}
        </datalist>
        <Button size="sm" icon="plus" aria-label={`Add to rule ${ruleNo}`} disabled={!text.trim()} onClick={commit}>
          Add
        </Button>
      </div>
      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

export function SmartRulesEditor({rules, onChange, spaceId, spaces}: {rules: SmartRules; onChange: (rules: SmartRules) => void; spaceId: string; spaces: readonly Space[]}) {
  const lib = useLibrary()
  const optionsFor = useMemo(() => {
    const cache = new Map<ValueField, ValueOption[]>()
    return (field: ValueField) => {
      let found = cache.get(field)
      if (!found) cache.set(field, (found = valueOptions(field, lib, spaces)))
      return found
    }
  }, [lib, spaces])
  const deferred = useDeferredValue(rules)
  const matches = useMemo(() => smartMatches(cleanRules(deferred), lib.snapshot.items, lib.snapshot.collections).length, [deferred, lib.snapshot.items, lib.snapshot.collections])
  const complete = cleanRules(rules).rules.length > 0
  const set = (patch: Partial<SmartRules>) => onChange({...rules, ...patch})
  const setRule = (index: number, rule: SmartRule) => set({rules: rules.rules.map((entry, at) => (at === index ? rule : entry))})
  const folders = spaces.filter((space) => space.type !== 'smart' && space.id !== spaceId)
  return (
    <div className="rules-editor">
      <div className="rules-match">
        <span className="field-label" id="rules-match-label">
          Gather items that match
        </span>
        <Segmented<'any' | 'all'>
          label="Match"
          value={rules.match}
          onChange={(match) => set({match})}
          options={[
            {value: 'any', label: 'Any rule'},
            {value: 'all', label: 'All rules'},
          ]}
        />
      </div>
      <ol className="rule-list" aria-label="Rules">
        {rules.rules.map((rule, index) => (
          <li key={index} className="rule-row" aria-label={`Rule ${index + 1}`}>
            <Select<RuleField> label={`Rule ${index + 1} field`} value={rule.field} onChange={(field) => setRule(index, emptyRule(field))} options={ruleFields} />
            <div className="rule-value">
              {hasValues(rule.field) && (
                <ValuePicker
                  key={rule.field}
                  field={rule.field}
                  values={(rule as Extract<SmartRule, {values: string[]}>).values}
                  options={optionsFor(rule.field)}
                  ruleNo={index + 1}
                  onChange={(values) => setRule(index, {field: rule.field, values} as SmartRule)}
                />
              )}
              {rule.field === 'addedWithin' && (
                <label className="unit-input">
                  <input
                    className="input tabular"
                    type="number"
                    min={1}
                    max={3650}
                    inputMode="numeric"
                    aria-label={`Days for rule ${index + 1}`}
                    value={rule.days}
                    onChange={(event) => setRule(index, {field: 'addedWithin', days: Math.max(0, Math.min(3650, Math.floor(Number(event.target.value) || 0)))})}
                  />
                  <span>days</span>
                </label>
              )}
              {(rule.field === 'favorite' || rule.field === 'unplayed') && <span className="muted small">{rule.field === 'favorite' ? 'Items you have hearted.' : 'Items you have never played.'}</span>}
            </div>
            <IconButton icon="trash" size="sm" label={`Remove rule ${index + 1}`} onClick={() => set({rules: rules.rules.filter((_, at) => at !== index)})} />
          </li>
        ))}
      </ol>
      <Button size="sm" icon="plus" onClick={() => set({rules: [...rules.rules, emptyRule('tag')]})}>
        Add rule
      </Button>
      <div className="rules-limits">
        <Select<LibraryItemKind | 'any'>
          label="Only"
          hideLabel={false}
          value={rules.onlyKind ?? 'any'}
          onChange={(kind) => set({onlyKind: kind === 'any' ? undefined : kind})}
          options={[{value: 'any', label: 'Any kind'}, ...kindOptions]}
        />
        {folders.length > 0 && (
          <div className="chips" role="group" aria-label="Leave out these spaces">
            <span className="field-label">Leave out</span>
            {folders.map((space) => {
              const on = !!rules.excludeSpaces?.includes(space.id)
              return (
                <button
                  key={space.id}
                  type="button"
                  className={`chip chip-lg${on ? ' on' : ''}`}
                  aria-pressed={on}
                  onClick={() => set({excludeSpaces: on ? rules.excludeSpaces!.filter((id) => id !== space.id) : [...(rules.excludeSpaces ?? []), space.id]})}
                >
                  {space.name}
                </button>
              )
            })}
          </div>
        )}
      </div>
      <p className={`rules-count${complete ? '' : ' muted'}`} role="status" aria-live="polite">
        <Icon name="smart" size={15} />
        {complete ? (
          <>
            <strong className="tabular">{matches.toLocaleString()}</strong> {matches === 1 ? 'item matches' : 'items match'} right now
          </>
        ) : (
          'Add a rule to see what it gathers.'
        )}
      </p>
    </div>
  )
}
