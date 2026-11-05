import {lazy, Suspense} from 'react'
import {Group, SettingExpander} from '../ui/SettingCard'
import {EngineSettings} from './Engine'
import {Guide} from './Guide'
import {ShortcutList} from './Shortcuts'
import {StartOver} from './StartOver'
import {Support} from './Support'

const About = lazy(() => import('./About').then((module) => ({default: module.About})))

/** Help: the guide and the keyboard shortcuts folded away. */
export function HelpSettings() {
  return (
    <Group cards id="guide" title="Help">
      <Guide />
      <SettingExpander anchor="shortcuts" icon="info" title="Keyboard shortcuts" description="Ctrl+K search · Space play or pause · Esc close">
        <ShortcutList />
      </SettingExpander>
    </Group>
  )
}

/** Support Keepfin: its own page (the sidebar's Support button opens it), apart from Help. */
export function SupportSettings() {
  return (
    <Group cards id="support" title="Support Keepfin">
      <Support />
    </Group>
  )
}

/** About Keepfin: the app and its updates (version, What's new), the download engine, and last Start over. */
export function AboutSettings() {
  return (
    <>
      <Suspense fallback={<p className="muted">Loading…</p>}>
        <About />
      </Suspense>
      <EngineSettings />
      <StartOver anchor="start-over" />
    </>
  )
}
