import {useCallback, useEffect, useRef, useState} from 'react'
import {setScale, setTheme, useAppearance} from '../appearance'
import {native} from '../bridge'
import {CardSizeSlider, CardTextChoice} from '../media/CardControls'
import {useKeepfinMode} from '../mode'
import {layoutById, layouts, usePlayerLayout} from '../player/full/layouts'
import {setSidebarStyle, useSidebarPrefs, type SidebarStyle} from '../shell/sidebarPrefs'
import {updateSettings, useMeta} from '../store'
import {Button, Segmented} from '../ui/controls'
import {Group, SettingCard} from '../ui/SettingCard'

type StartPage = 'downloads' | 'library' | 'last'

/**
 * Interface size. Applying the size while dragging rescales the page, slider included, so the thumb would slide away
 * from the pointer and the value would jump. While the pointer drags, only the number previews the size; it is applied
 * when the pointer is released. Keyboard steps (arrows, Home/End) apply at once.
 */
function ScaleControl({scale}: {scale: number}) {
  const [draft, setDraft] = useState<number | null>(null)
  // idle: keyboard steps apply at once. drag: only the number previews. settle: the release is still being handled by
  // the browser; any value it reports now was read from a slider that is about to move, so it is ignored.
  const phase = useRef<'idle' | 'drag' | 'settle'>('idle')
  const pending = useRef<number | null>(null)
  const applyTimer = useRef<number | undefined>(undefined)
  const idleTimer = useRef<number | undefined>(undefined)
  const shown = draft ?? scale
  const apply = useCallback(() => {
    window.clearTimeout(applyTimer.current)
    applyTimer.current = undefined
    const next = pending.current
    pending.current = null
    if (next !== null) setScale(next)
    setDraft(null)
  }, [])
  const release = useCallback(() => {
    if (phase.current !== 'drag') return
    phase.current = 'settle'
    window.removeEventListener('pointerup', release)
    window.removeEventListener('pointercancel', release)
    // Apply after the browser has finished the release (its own last value update comes from the same pointer event).
    applyTimer.current = window.setTimeout(() => {
      apply()
      // Let the rescaled layout settle before pointer values count again. A timer, not animation frames: frames pause
      // while the window is hidden, which would leave the slider ignoring every change.
      idleTimer.current = window.setTimeout(() => (phase.current = 'idle'), 120)
    }, 0)
  }, [apply])
  useEffect(
    () => () => {
      window.clearTimeout(applyTimer.current)
      window.clearTimeout(idleTimer.current)
      window.removeEventListener('pointerup', release)
      window.removeEventListener('pointercancel', release)
    },
    [release],
  )
  return (
    <div className="scale-control">
      <input
        type="range"
        min={80}
        max={140}
        step={5}
        value={shown}
        aria-label="Interface size"
        aria-valuetext={`${shown}%`}
        onKeyDown={() => {
          // Only the pointer's late value is ignored while settling; a key press ends the settling at once.
          if (phase.current !== 'settle') return
          if (applyTimer.current !== undefined) apply()
          window.clearTimeout(idleTimer.current)
          phase.current = 'idle'
        }}
        onPointerDown={(event) => {
          if (phase.current === 'settle') return
          phase.current = 'drag'
          pending.current = null
          // Keep the pointer on the slider so the release always arrives, even outside it.
          event.currentTarget.setPointerCapture?.(event.pointerId)
          window.addEventListener('pointerup', release)
          window.addEventListener('pointercancel', release)
        }}
        onChange={(event) => {
          const value = Number(event.target.value)
          if (phase.current === 'settle') return
          if (phase.current === 'drag') {
            pending.current = value
            setDraft(value)
          } else setScale(value)
        }}
      />
      <span className="tabular scale-value">{shown}%</span>
      <Button size="sm" variant="ghost" disabled={shown === 100} onClick={() => setScale(100)}>
        Reset size
      </Button>
    </div>
  )
}

/** Settings > Appearance > Player style: the same choice as the button in the full player's header (player/full/layouts.ts). */
function PlayerStyleSetting() {
  const [current, choose] = usePlayerLayout()
  return (
    <SettingCard
      anchor="player-style"
      icon="palette"
      title="Player style"
      description={layoutById(current).summary}
      control={<Segmented label="Player style" value={current} onChange={choose} options={layouts.map((layout) => ({value: layout.id, label: layout.name}))} />}
    />
  )
}

export function AppearanceSettings() {
  const appearance = useAppearance()
  const {settings} = useMeta()
  const mode = useKeepfinMode()
  const sidebar = useSidebarPrefs()
  return (
    <>
      <Group cards title="When Keepfin opens">
        <SettingCard
          anchor="start-page"
          icon="home"
          title="Open Keepfin on"
          description="Where I left off brings back the page you were on when you closed Keepfin."
          control={
            <Segmented<StartPage>
              label="Open Keepfin on"
              value={settings.startPage}
              onChange={(startPage) => void updateSettings({startPage})}
              options={[
                {value: 'last', label: 'Where I left off'},
                {value: 'downloads', label: 'Downloads'},
                ...(mode === 'full' ? [{value: 'library' as const, label: 'Home'}] : []),
              ]}
            />
          }
        />
      </Group>
      <Group cards title="Look">
        <SettingCard
          anchor="theme"
          icon="sun"
          title="Theme"
          description="Dark suits long sessions; light matches bright rooms. System follows Windows."
          control={
            <Segmented
              label="Theme"
              value={appearance.theme}
              onChange={setTheme}
              options={[
                {value: 'dark', label: 'Dark'},
                {value: 'light', label: 'Light'},
                {value: 'system', label: 'System'},
              ]}
            />
          }
        />
        <SettingCard
          anchor="sidebar-style"
          icon="sidebar"
          title="Sidebar"
          description="Floating sits as a rounded panel beside the page; docked runs along the window's edge. Drag its right edge to change its width."
          control={
            <Segmented<SidebarStyle>
              label="Sidebar"
              value={sidebar.style}
              onChange={setSidebarStyle}
              options={[
                {value: 'floating', label: 'Floating'},
                {value: 'docked', label: 'Docked'},
              ]}
            />
          }
        />
        {mode === 'full' && <PlayerStyleSetting />}
        <SettingCard anchor="size" icon="zoomIn" title="Interface size" description="Scales text and controls from 80% to 140%." control={<ScaleControl scale={appearance.scale} />} />
        {mode === 'full' && (
          <SettingCard anchor="cards" icon="grid" title="Cards" description="How big the covers and thumbnails are, from 80% to 125%, and the words under them. Ctrl + and Ctrl − change the size anywhere.">
            <div className="card-settings">
              <CardSizeSlider />
              <CardTextChoice />
            </div>
          </SettingCard>
        )}
      </Group>
    </>
  )
}
