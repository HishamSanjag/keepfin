// The keyboard-shortcuts sheet of the video player (opened with ? or from More). Esc closes it; the player's key
// handler leaves the keypress to it, so the player itself stays open.
import {Button} from '../ui/controls'

const groups: {title: string; rows: [string, string][]}[] = [
  {
    title: 'Playback',
    rows: [
      ['Space  K', 'Play or pause'],
      ['J  L', 'Back or forward 10 seconds'],
      ['←  →', 'Back or forward 5 seconds'],
      ['0 – 9', 'Jump to 0 % – 90 %'],
      ['Home  End', 'Start or end of the video'],
      [',  .', 'One frame back or forward (paused)'],
      ['Ctrl ←  Ctrl →', 'Previous or next chapter'],
      ['N  P', 'Next or previous video'],
      ['Hold the mouse on the video', 'Play at 2× while held'],
    ],
  },
  {
    title: 'Sound, speed and captions',
    rows: [
      ['↑  ↓', 'Volume up or down'],
      ['M', 'Mute'],
      ['<  >', 'Slower or faster'],
      ['C', 'Captions on or off'],
      ['+  −', 'Larger or smaller captions'],
    ],
  },
  {
    title: 'Window',
    rows: [
      ['F', 'Full screen'],
      ['?', 'This list'],
      ['Esc', 'Close this list, then the video'],
    ],
  },
]

/** "Space  K" → two keys; "Ctrl ←" → Ctrl + ←; "0 – 9" → a range. Plain words (a mouse action) stay text. */
function Keys({spec}: {spec: string}) {
  if (/^[A-Z][a-z]+ [a-z]/.test(spec)) return <>{spec}</>
  return (
    <>
      {spec.split(/\s{2,}/).map((combo, at) => (
        <span key={at} className="kbd-combo">
          {combo.split(' ').map((part, index, parts) =>
            part === '–' ? (
              <span key={index} className="kbd-sep">–</span>
            ) : (
              <span key={index} className="kbd-part">
                <kbd>{part}</kbd>
                {part === 'Ctrl' && index < parts.length - 1 && <span className="kbd-sep">+</span>}
              </span>
            ),
          )}
        </span>
      ))}
    </>
  )
}

export function VideoShortcuts({onClose}: {onClose: () => void}) {
  return (
    <div className="video-sheet-scrim" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="video-sheet" role="dialog" aria-modal="true" aria-label="Keyboard shortcuts">
        <h2>Keyboard shortcuts</h2>
        <div className="video-sheet-body">
          {groups.map((group) => (
            <section key={group.title}>
              <h3>{group.title}</h3>
              <dl>
                {group.rows.map(([keys, what]) => (
                  <div key={keys}>
                    <dt>
                      <Keys spec={keys} />
                    </dt>
                    <dd>{what}</dd>
                  </div>
                ))}
              </dl>
            </section>
          ))}
        </div>
        <div className="video-sheet-actions">
          <Button autoFocus onClick={onClose}>
            Done
          </Button>
        </div>
      </div>
    </div>
  )
}
