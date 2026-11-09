import {useState, type FormEvent} from 'react'
import type {DevicesState} from '../../../shared/devices'
import {pairCodeProblem} from '../../../shared/core/pairCode'
import {messageOf} from '../../bridge'
import {Button, InlineAlert} from '../../ui/controls'
import {connectTo} from './devicesApi'

/**
 * "Type a code": the ten characters another device shows (under its QR, or on its Add screen), with or without the dash, in any case.
 * A mistake is explained before anything is sent; a code the other device does not accept (wrong or expired) comes back in words from
 * the engine. Then it is the same pairing as a scanned code: the other device approves and both confirm in the dialog that opens.
 */
export function TypeCode({onState}: {onState: (state: DevicesState) => void}) {
  const [text, setText] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function submit(event: FormEvent) {
    event.preventDefault()
    const problem = pairCodeProblem(text)
    if (problem) {
      setError(problem)
      return
    }
    setBusy(true)
    setError('')
    try {
      onState(await connectTo(text.trim()))
      setText('')
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setBusy(false)
    }
  }
  return (
    <section className="devices-panel devices-typecode" aria-label="Type a code">
      <label className="devices-field-label" htmlFor="devices-type-code">
        Type a code from another device
      </label>
      <form className="devices-address" onSubmit={(event) => void submit(event)}>
        <input
          id="devices-type-code"
          className="input"
          value={text}
          placeholder="7Q4MX-2XKDP"
          onChange={(event) => {
            setText(event.target.value)
            setError('')
          }}
          spellCheck={false}
          autoCapitalize="characters"
          autoComplete="off"
          aria-invalid={!!error}
        />
        <Button type="submit" variant="primary" disabled={!text.trim()} loading={busy}>
          Connect with code
        </Button>
      </form>
      <p className="small muted">No camera? The other device shows a code of 10 characters. Capitals or small letters, with or without the dash.</p>
      {error && <InlineAlert>{error}</InlineAlert>}
    </section>
  )
}
