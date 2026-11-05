// Features & how-to. The text comes from content/guide.json (shared with Android); this file only lays it out.
import {useMemo, useState} from 'react'
import guide from '../../../content/guide.json'
import {setLibView} from '../library/libNav'
import {navigate} from '../nav'
import {Button} from '../ui/controls'
import {Icon} from '../ui/Icon'

type Feature = {
  id: string
  title: string
  summary: string
  steps?: string[]
  /** Steps that differ on Windows (gestures, selection); replaces steps here. */
  desktopSteps?: string[]
  try?: string
  platforms?: string[]
  notes?: {desktop?: string; android?: string}
}
type Section = {id: string; title: string; features: Feature[]}

/** The shared text is written for touch; on Windows "tap" reads as "click". */
const forDesktop = (text: string) => text.replace(/Tap/g, 'Click').replace(/tap/g, 'click')

const sections: Section[] = (guide.sections as Section[])
  .map((section) => ({
    ...section,
    features: section.features
      .filter((feature) => !feature.platforms || feature.platforms.includes('desktop'))
      .map((feature) => ({...feature, summary: forDesktop(feature.summary), steps: (feature.desktopSteps || feature.steps)?.map(forDesktop)})),
  }))
  .filter((section) => section.features.length)

const tryLabels: Record<string, string> = {downloads: 'Downloads', library: 'Library', 'library-edit': 'Library settings', settings: 'Settings'}

/** Maps a guide "try" id to a screen. */
export function tryIt(target: string) {
  if (target === 'downloads') navigate('Downloads')
  else if (target === 'library') {
    setLibView({type: 'home'})
    navigate('Library')
  } else if (target === 'library-edit') navigate('Settings', 'library')
  else if (target === 'settings') navigate('Settings', 'downloads')
}

function matches(feature: Feature, words: string[]) {
  if (!words.length) return true
  const haystack = [feature.title, feature.summary, ...(feature.steps || []), feature.notes?.desktop || ''].join(' ').toLowerCase()
  return words.every((word) => haystack.includes(word))
}

function FeatureCard({feature, open, onToggle}: {feature: Feature; open: boolean; onToggle: () => void}) {
  const note = feature.notes?.desktop
  return (
    <li className={`guide-card${open ? ' open' : ''}`} data-feature={feature.id}>
      <h4 className="guide-card-head">
        <button type="button" className="guide-toggle" aria-expanded={open} aria-controls={`guide-${feature.id}`} onClick={onToggle}>
          <span className="guide-card-title">{feature.title}</span>
          <Icon name={open ? 'chevronUp' : 'chevronDown'} size={16} />
        </button>
      </h4>
      <div id={`guide-${feature.id}`} className="guide-card-body" hidden={!open}>
        <p className="guide-summary">{feature.summary}</p>
        {!!feature.steps?.length && (
          <ol className="guide-steps">
            {feature.steps.map((step) => (
              <li key={step}>{step}</li>
            ))}
          </ol>
        )}
        {note && (
          <p className="guide-note">
            <Icon name="info" size={14} /> <span>{note}</span>
          </p>
        )}
        {feature.try && (
          <div className="guide-actions">
            <Button size="sm" icon="open" onClick={() => tryIt(feature.try!)}>
              Try it
              <span className="sr-only"> ({tryLabels[feature.try] || feature.try})</span>
            </Button>
          </div>
        )}
      </div>
    </li>
  )
}

export function Guide() {
  const [query, setQuery] = useState('')
  const [openIds, setOpenIds] = useState<ReadonlySet<string>>(new Set())
  const words = useMemo(() => query.toLowerCase().split(/\s+/).filter(Boolean), [query])
  const shown = useMemo(() => sections.map((section) => ({...section, features: section.features.filter((feature) => matches(feature, words))})).filter((section) => section.features.length), [words])
  const toggle = (id: string) =>
    setOpenIds((previous) => {
      const next = new Set(previous)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  return (
    <div className="guide">
      <p className="guide-intro">{forDesktop(guide.intro)}</p>
      <div className="search guide-search">
        <Icon name="search" size={15} />
        <input className="input" type="search" aria-label="Search features" placeholder="Search features and steps" value={query} onChange={(event) => setQuery(event.target.value)} />
      </div>
      {shown.map((section) => (
        <section key={section.id} className="guide-section" aria-labelledby={`guide-h-${section.id}`}>
          <h3 id={`guide-h-${section.id}`} className="guide-heading">
            {section.title}
          </h3>
          <ul className="guide-list">
            {section.features.map((feature) => (
              <FeatureCard key={feature.id} feature={feature} open={openIds.has(feature.id) || words.length > 0} onToggle={() => toggle(feature.id)} />
            ))}
          </ul>
        </section>
      ))}
      {!shown.length && (
        <p className="muted" role="status">
          Nothing matches “{query.trim()}”. Try a shorter word.
        </p>
      )}
    </div>
  )
}
