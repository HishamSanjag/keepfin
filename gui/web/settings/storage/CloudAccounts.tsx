import {useState} from 'react'
import {formatBytes} from '../../../shared/contracts'
import type {CloudAccountView, CopiesState} from '../../../shared/copies'
import {messageOf} from '../../bridge'
import {plural} from '../../format'
import {Badge, Button, ConfirmInline, InlineAlert, ProgressBar} from '../../ui/controls'
import {disconnectCloud, revokeCloud, signInToCloud} from './copiesApi'

export type CloudNote = {text: string; url?: string}
type Props = {state: CopiesState; onState: (state: CopiesState) => void; note: CloudNote | null; onNote: (note: CloudNote | null) => void}

/** The accounts signed in on this computer: free space, the copies that use each, Sign in again, Disconnect and Revoke. */
export function CloudAccountCards({state, onState, note, onNote}: Props) {
  const accounts = state.cloud?.accounts ?? []
  // what Remove access said is kept by the screen: the account card it came from is gone by then
  if (accounts.length === 0 && !note) return null
  return (
    <>
      {note && (
        <InlineAlert
          tone="info"
          actions={
            <Button size="sm" onClick={() => onNote(null)}>
              Got it
            </Button>
          }
        >
          {note.text}
          {note.url && (
            <>
              {' '}
              <a href={note.url} target="_blank" rel="noreferrer noopener">
                Open the permissions page
              </a>
            </>
          )}
        </InlineAlert>
      )}
      <ul className="cloud-accounts" aria-label="Cloud accounts">
        {accounts.map((account) => (
          <li key={account.id}>
            <AccountCard account={account} onState={onState} onNote={onNote} />
          </li>
        ))}
      </ul>
    </>
  )
}

export function AccountSummary({account}: {account: CloudAccountView}) {
  const used = account.totalBytes !== undefined && account.freeBytes !== undefined ? ((account.totalBytes - account.freeBytes) / account.totalBytes) * 100 : undefined
  return (
    <>
      {used !== undefined && account.freeBytes !== undefined && account.totalBytes !== undefined && (
        <div className="storage-bar">
          <ProgressBar value={used} label={`Space used in ${account.label}`} />
          <span className="small muted tabular">
            {formatBytes(account.freeBytes)} free of {formatBytes(account.totalBytes)}
          </span>
        </div>
      )}
    </>
  )
}

function AccountCard({account, onState, onNote}: {account: CloudAccountView; onState: (state: CopiesState) => void; onNote: (note: {text: string; url?: string}) => void}) {
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')

  async function act(name: string, work: () => Promise<void>) {
    setBusy(name)
    setError('')
    try {
      await work()
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setBusy('')
    }
  }

  return (
    <article className="cloud-account" aria-label={`${account.providerName}: ${account.label}`} data-signin={account.needsSignIn || undefined}>
      <header className="copy-card-head">
        <h4>{account.providerName}</h4>
        {account.needsSignIn ? <Badge tone="warning">Sign in again</Badge> : <Badge tone="success">Signed in</Badge>}
      </header>
      <p className="cloud-account-label">{account.label}</p>
      <AccountSummary account={account} />
      <p className="small muted">{account.copies > 0 ? `Used by ${plural(account.copies, 'copy', 'copies')}.` : 'No copy uses this account yet.'}</p>
      {account.support && (
        <ul className="cloud-support" aria-label="What this server supports">
          {account.support.map((line) => (
            <li key={line} className="small muted">
              {line}
            </li>
          ))}
        </ul>
      )}
      {error && <InlineAlert tone="error">{error}</InlineAlert>}
      <div className="copy-actions">
        {account.needsSignIn && account.provider !== 'webdav' && (
          <Button
            variant="primary"
            size="sm"
            loading={busy === 'signin'}
            disabled={!!busy}
            onClick={() =>
              void act('signin', async () => {
                const result = await signInToCloud(account.provider as 'drive' | 'graph' | 'dropbox', account.id)
                onState(result.state)
              })
            }
          >
            Sign in again
          </Button>
        )}
        <ConfirmInline
          label="Disconnect"
          confirmLabel="Disconnect"
          icon="close"
          disabled={!!busy}
          prompt="Forget this sign-in on this computer? Your files stay in the account, and the account stays signed in on your other devices."
          onConfirm={() =>
            act('disconnect', async () => {
              onState(await disconnectCloud(account.id))
            })
          }
        />
        {account.provider !== 'webdav' && (
          <ConfirmInline
            label="Remove access"
            confirmLabel="Remove access"
            icon="trash"
            disabled={!!busy}
            prompt={
              account.provider === 'graph'
                ? 'Forget this sign-in here and show where to remove Keepfin’s access in your Microsoft account? Microsoft does not let an app do that itself.'
                : 'End Keepfin’s access at the provider? Every device signed in to this account will have to sign in again. Your files stay in the account.'
            }
            onConfirm={() =>
              act('revoke', async () => {
                const result = await revokeCloud(account.id)
                onState(result.state)
                onNote({text: result.note, url: result.url})
              })
            }
          />
        )}
      </div>
    </article>
  )
}
