import {useEffect, useRef, useState} from 'react'
import {onPush, native} from '../bridge'
import {updateSettings, useMeta} from '../store'
import {InlineAlert} from '../ui/controls'
import manifest from './appIcons.generated.json'

const accentHex = manifest.accent.replace(/^#/, '').toUpperCase()
/** Picture of a variant at the current accent. One function, so a later accent setting only passes another accent. */
const pictureFor = (id: string, hex: string = accentHex) => `./app-icons/${id}-${hex}.png`

/** Settings -> Appearance -> App icon. The list comes from the generated manifest (assets/icons/variants/index.json). */
export function AppIconPicker() {
  const {settings} = useMeta()
  const variants = manifest.variants
  const current = variants.some((v) => v.id === settings.appIcon) ? settings.appIcon : manifest.main
  const [failed, setFailed] = useState(false)
  const refs = useRef<(HTMLButtonElement | null)[]>([])

  useEffect(
    () =>
      onPush((event) => {
        if (event.type === 'appIcon') setFailed(event.shortcutsFailed)
      }),
    [],
  )

  const choose = (id: string) => {
    if (!native) return
    void updateSettings({appIcon: id})
  }
  const move = (from: number, step: number) => {
    const to = (from + step + variants.length) % variants.length
    refs.current[to]?.focus()
    choose(variants[to].id)
  }

  return (
    <div className="app-icon-picker">
      <div className="app-icon-grid" role="radiogroup" aria-label="App icon">
        {variants.map((variant, index) => {
          const selected = variant.id === current
          return (
            <button
              key={variant.id}
              ref={(node) => {
                refs.current[index] = node
              }}
              type="button"
              role="radio"
              aria-checked={selected}
              tabIndex={selected ? 0 : -1}
              className={`app-icon-option${selected ? ' selected' : ''}`}
              onClick={() => choose(variant.id)}
              onKeyDown={(event) => {
                if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
                  event.preventDefault()
                  move(index, 1)
                } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
                  event.preventDefault()
                  move(index, -1)
                }
              }}
            >
              <img src={pictureFor(variant.id)} alt="" width={56} height={56} draggable={false} />
              <span>{variant.name}</span>
            </button>
          )
        })}
      </div>
      <p className="app-icon-help">Changes the window, taskbar and Keepfin shortcuts.</p>
      {!native && <p className="app-icon-help">Browser preview: the icon changes in the installed app.</p>}
      {failed && <InlineAlert tone="info">The Start menu shortcut could not be updated.</InlineAlert>}
    </div>
  )
}
