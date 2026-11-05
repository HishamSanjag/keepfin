import {useEffect, useRef, type ComponentType} from 'react'
import {native} from '../bridge'
import {navigate, useNav} from '../nav'
import {Button, InlineAlert} from '../ui/controls'
import {PageHeader} from '../shell/PageHeader'
import {Icon} from '../ui/Icon'
import {AboutSettings, HelpSettings, SupportSettings} from './AboutSettings'
import {AppearanceSettings} from './AppearanceSettings'
import {DownloadsSettings} from './DownloadsSettings'
import {LibrarySettings} from './LibrarySettings'
import {Notifications} from './Notifications'
import {SettingsRail} from './SettingsRail'
import {StorageSettings} from './StorageSettings'
import {DevicesSettings} from './devices/Devices'
import {categories, type SettingsCategory} from './sections'
import {useAttention} from './useAttention'

function PreviewNote() {
  if (native) return null
  return <InlineAlert tone="info">Browser preview: these are the desktop defaults. Settings are stored by the installed app.</InlineAlert>
}

const pages: Record<SettingsCategory, ComponentType> = {
  downloads: DownloadsSettings,
  library: LibrarySettings,
  storage: StorageSettings,
  devices: DevicesSettings,
  appearance: AppearanceSettings,
  notifications: Notifications,
  help: HelpSettings,
  support: SupportSettings,
  about: AboutSettings,
}

/** Scrolls to the setting a search result or a link asked for and highlights it for a moment. Waits for lazy pages to draw it. */
function useAnchorFocus() {
  const {anchor, focus, group} = useNav()
  useEffect(() => {
    if (!anchor) return
    let tries = 0
    let wait: number | undefined
    let clear: number | undefined
    let target: HTMLElement | null = null
    const calm = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
    const attempt = () => {
      const found = document.getElementById(`setting-${anchor}`)
      if (!found) {
        if (tries++ < 40) wait = window.setTimeout(attempt, 50)
        return
      }
      target = found
      found.scrollIntoView?.({block: 'center', behavior: calm ? 'auto' : 'smooth'})
      found.classList.remove('is-highlight')
      void found.offsetWidth
      found.classList.add('is-highlight')
      clear = window.setTimeout(() => found.classList.remove('is-highlight'), 2400)
    }
    attempt()
    return () => {
      window.clearTimeout(wait)
      window.clearTimeout(clear)
      target?.classList.remove('is-highlight')
    }
  }, [anchor, focus, group])
}

export function SettingsScreen() {
  const nav = useNav()
  const current = categories.find((item) => item.id === nav.group) || categories[0]
  const panel = useRef<HTMLDivElement>(null)
  const attention = useAttention(current.id).filter((item) => item.category !== current.id)
  const Page = pages[current.id]
  useAnchorFocus()
  return (
    <>
      <PageHeader title="Settings" caption="Everything that changes how Keepfin works. Search finds a setting by name." />
      <div className="screen settings">
        <SettingsRail current={current.id} onSelect={() => panel.current?.scrollTo?.({top: 0})} />
        <div className="settings-panel" ref={panel}>
          <h2 className="settings-title">{current.label}</h2>
          <PreviewNote />
          {attention.length > 0 && (
            <ul className="settings-attention" aria-label="Needs your attention">
              {attention.map((item) => (
                <li key={item.id}>
                  <Icon name="warning" size={16} />
                  <span>{item.text}</span>
                  <Button size="sm" aria-label={`${item.fix}: ${item.text}`} onClick={() => navigate('Settings', item.category, item.anchor)}>
                    {item.fix}
                  </Button>
                </li>
              ))}
            </ul>
          )}
          <Page />
        </div>
      </div>
    </>
  )
}