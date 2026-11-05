import {Kbd} from '../ui/controls'
import {Group} from '../ui/SettingCard'

const shortcuts: [string[], string][] = [
  [['Ctrl', 'K'], 'Search your library (or paste a link into the search bar)'],
  [['/'], 'Search, when you are not typing'],
  [['Ctrl', ','], 'Open Settings'],
  [['Ctrl', 'B'], 'Show or hide the sidebar'],
  [['Space'], 'Play or pause the song'],
  [['Esc'], 'Close the full player, the lyrics or a dialog'],
  [['Ctrl', 'L'], 'Focus the link field on Downloads'],
  [['Ctrl', 'V'], 'Paste and inspect a link from anywhere'],
  [['Ctrl', 'A'], 'Select everything in a list'],
  [['Alt', '←'], 'Go back (Alt + → goes forward)'],
  [['Shift', 'Click'], 'Select a range of entries'],
]

/** The list on its own, for About & help (inside Help, folded until opened). */
export function ShortcutList() {
  return (
      <dl className="shortcuts">
        {shortcuts.map(([keys, text]) => (
          <div key={text}>
            <dt>
              {keys.map((key) => (
                <Kbd key={key}>{key}</Kbd>
              ))}
            </dt>
            <dd>{text}</dd>
          </div>
        ))}
      </dl>
  )
}

export function Shortcuts() {
  return (
    <Group id="shortcuts" title="Keyboard shortcuts">
      <ShortcutList />
    </Group>
  )
}
