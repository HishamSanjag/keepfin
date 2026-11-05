import {useId, useState} from 'react'
import {messageOf, native, request} from '../bridge'
import {Button, InlineAlert} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import {Group, SettingCard} from '../ui/SettingCard'

const WORD = 'DELETE'

const copy = {
  reset: {
    title: 'Reset Keepfin',
    summary:
      'Reset Keepfin erases what this PC keeps: settings, download history, paired devices and copies. Your Keepfin folder stays, so your files and library come back when you open it again.',
    confirm: 'Reset Keepfin',
    deleteFolder: false,
  },
  everything: {
    title: 'Delete everything',
    summary:
      'Delete everything also deletes your Keepfin folder: every song, video and photo in it, your library and playlists. This cannot be undone.',
    confirm: 'Delete everything',
    deleteFolder: true,
  },
} as const

type Kind = keyof typeof copy

/** Start over: two buttons, each confirmed by typing DELETE. The browser preview has no native bridge, so they stay disabled there. */
export function StartOver({anchor}: {anchor: string}) {
  const [asking, setAsking] = useState<Kind>()
  const [typed, setTyped] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const inputId = useId()

  function close() {
    if (busy) return
    setAsking(undefined)
    setTyped('')
    setError('')
  }

  async function run(kind: Kind) {
    setBusy(true)
    setError('')
    try {
      await request({op: 'startOver', deleteFolder: copy[kind].deleteFolder})
      // Keepfin closes now and opens again by itself; keep the dialog locked until then.
    } catch (caught) {
      setError(messageOf(caught))
      setBusy(false)
    }
  }

  return (
    <Group cards id={anchor} title="Start over" description={native ? undefined : 'Available in the Keepfin desktop app.'}>
      <SettingCard
        icon="refresh"
        title="Reset Keepfin"
        description={copy.reset.summary}
        control={
          <Button disabled={!native} onClick={() => setAsking('reset')}>
            Reset Keepfin
          </Button>
        }
      />
      <SettingCard
        icon="trash"
        title="Delete everything"
        description={copy.everything.summary}
        control={
          <Button variant="danger" disabled={!native} onClick={() => setAsking('everything')}>
            Delete everything
          </Button>
        }
      />
      {asking && (
        <Dialog
          title={copy[asking].title}
          labelledBy="start-over-title"
          onClose={close}
          footer={
            <>
              <Button onClick={close} disabled={busy}>
                Cancel
              </Button>
              <Button variant="danger" loading={busy} disabled={typed !== WORD} onClick={() => void run(asking)}>
                {copy[asking].confirm}
              </Button>
            </>
          }
        >
          <p>{copy[asking].summary}</p>
          <p>
            <label htmlFor={inputId}>
              Type <strong>{WORD}</strong> to continue
            </label>
          </p>
          <input
            id={inputId}
            className="input"
            type="text"
            autoFocus
            autoComplete="off"
            spellCheck={false}
            value={typed}
            disabled={busy}
            onChange={(event) => setTyped(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && typed === WORD && !busy) void run(asking)
            }}
          />
          {busy && (
            <p className="muted small" role="status">
              Keepfin is closing and will open again fresh.
            </p>
          )}
          {error && <InlineAlert tone="error">{error}</InlineAlert>}
        </Dialog>
      )}
    </Group>
  )
}
