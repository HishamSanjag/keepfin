import {useEffect, useRef, useState} from 'react'
import type {CloudAccountView, CloudFoundReplica, CloudProviderChoice, CloudProviderId, CopiesState} from '../../../shared/copies'
import {messageOf} from '../../bridge'
import {Badge, Button, InlineAlert, Spinner} from '../../ui/controls'
import {AccountSummary} from './CloudAccounts'
import {attachCloudCopy, cancelCloudSignIn, connectWebdav, findCloudCopies, signInToCloud} from './copiesApi'

type Props = {
  state: CopiesState
  accountId: string
  folder: string
  onAccount: (account: CloudAccountView) => void
  onFolder: (folder: string) => void
  onState: (state: CopiesState) => void
  /** A copy the library already knows was attached to this computer: the wizard is done. */
  onAttached: (state: CopiesState) => void
  /** A sign-in started here has found no Keepfin copy on the new account: carry on to the next page, as if Continue was pressed. */
  onContinue: () => void
}

/**
 * The first step of Add a copy when the copy lives in a cloud account: pick a provider (sign in in the browser, or connect a
 * WebDAV server), pick the account, name the folder on it, or use a copy another device already made there.
 */
export function CloudStep({state, accountId, folder, onAccount, onFolder, onState, onAttached, onContinue}: Props) {
  const providers = (state.cloud?.providers ?? []).filter((p) => p.available)
  const accounts = state.cloud?.accounts ?? []
  const [adding, setAdding] = useState(accounts.length === 0)
  const [signing, setSigning] = useState<CloudProviderId | null>(null)
  const [webdav, setWebdav] = useState(false)
  const [error, setError] = useState('')
  const [found, setFound] = useState<CloudFoundReplica[] | null>(null)
  const [looking, setLooking] = useState(false)
  const [attaching, setAttaching] = useState('')
  const cancelled = useRef(false)
  // the account a sign-in from this step just made or renewed: once its copies are looked up, the step carries on by itself
  const justSignedIn = useRef<string | null>(null)
  const carryOn = useRef(onContinue)
  carryOn.current = onContinue
  const account = accounts.find((a) => a.id === accountId)

  useEffect(() => {
    let live = true
    setFound(null)
    if (!account || account.needsSignIn) return
    setLooking(true)
    findCloudCopies(account.id, account.provider === 'webdav' ? folder || undefined : undefined)
      .then((list) => {
        if (!live) return
        setFound(list)
        const fresh = justSignedIn.current === account.id
        if (fresh) justSignedIn.current = null
        // nothing there yet: straight on to the copy's setup. A copy that is there stays on screen to be chosen.
        if (fresh && list.length === 0) carryOn.current()
      })
      .catch(() => {
        if (!live) return
        setFound([])
        justSignedIn.current = null // not knowing is not "nothing there": the person decides
      })
      .finally(() => live && setLooking(false))
    return () => {
      live = false
    }
    // WebDAV looks in the folder that was typed; the other providers list their own Keepfin folder once per account
  }, [account?.id, account?.needsSignIn, account?.provider === 'webdav' ? folder : ''])

  async function signIn(provider: Exclude<CloudProviderId, 'webdav'>) {
    setError('')
    setSigning(provider)
    cancelled.current = false
    try {
      const result = await signInToCloud(provider)
      justSignedIn.current = result.account.id
      onState(result.state)
      onAccount(result.account)
      setAdding(false)
    } catch (caught) {
      if (!cancelled.current) setError(messageOf(caught))
    } finally {
      setSigning(null)
    }
  }

  /** Not one of the copies found: a folder name nothing on the account uses yet, then on to the copy's setup. */
  function makeNew() {
    const taken = new Set((found ?? []).map((f) => f.folder.toLowerCase()))
    const base = folder.trim() || 'Library'
    let name = base
    for (let n = 2; taken.has(name.toLowerCase()); n++) name = base + ' ' + n
    if (name !== folder) onFolder(name)
    onContinue()
  }

  async function cancel() {
    cancelled.current = true
    await cancelCloudSignIn().catch(() => undefined)
  }

  async function attach(replica: CloudFoundReplica) {
    if (!account) return
    setAttaching(replica.folder)
    setError('')
    try {
      onAttached(await attachCloudCopy(account.id, replica.folder))
    } catch (caught) {
      setError(messageOf(caught))
      setAttaching('')
    }
  }

  return (
    <div className="cloud-step">
      {accounts.length > 0 && (
        <fieldset className="cloud-pick">
          <legend>Account</legend>
          {accounts.map((a) => (
            <label key={a.id} className="copy-choice" data-needs-signin={a.needsSignIn || undefined}>
              <input type="radio" name="cloud-account" checked={a.id === accountId} disabled={a.needsSignIn} onChange={() => onAccount(a)} />
              <span>
                <strong>
                  {a.providerName} <small className="muted">{a.label}</small>
                </strong>
                {a.needsSignIn ? <small>Sign in again (in Needs review) to use this account.</small> : <AccountSummary account={a} />}
              </span>
              {a.needsSignIn && <Badge tone="warning">Sign in again</Badge>}
            </label>
          ))}
        </fieldset>
      )}

      {!adding && !signing && (
        <Button size="sm" icon="plus" onClick={() => setAdding(true)}>
          Add another account
        </Button>
      )}
      {adding && !webdav && (
        <div className="cloud-providers" role="group" aria-label="Choose where the copy lives">
          {providers.map((p) => (
            <ProviderButton key={p.id} provider={p} busy={signing === p.id} disabled={!!signing} onChoose={() => (p.id === 'webdav' ? setWebdav(true) : void signIn(p.id as Exclude<CloudProviderId, 'webdav'>))} />
          ))}
          {providers.length === 0 && <p className="muted small">No cloud provider is set up in this build of Keepfin.</p>}
        </div>
      )}
      {signing && (
        <div className="cloud-waiting" role="status">
          <Spinner /> <span>Finish signing in in your browser. Keepfin is waiting for you.</span>
          <Button size="sm" variant="ghost" onClick={() => void cancel()}>
            Cancel
          </Button>
        </div>
      )}
      {adding && webdav && (
        <WebdavForm
          onCancel={() => setWebdav(false)}
          onConnected={(result) => {
            justSignedIn.current = result.account.id
            onState(result.state)
            onAccount(result.account)
            setWebdav(false)
            setAdding(false)
          }}
        />
      )}
      {error && <InlineAlert tone="error">{error}</InlineAlert>}

      {account && !account.needsSignIn && (
        <div className="cloud-folder">
          <label className="copy-field">
            <span>{account.provider === 'webdav' ? 'Folder on the server' : `Folder in ${account.providerName}`}</span>
            <input className="input" value={folder} maxLength={120} onChange={(event) => onFolder(event.target.value)} aria-describedby="cloud-folder-hint" />
          </label>
          <p id="cloud-folder-hint" className="small muted">
            {account.provider === 'webdav' ? 'Keepfin keeps the copy in this folder on your server. It is made if it is not there.' : `Keepfin keeps the copy in a folder called Keepfin/${folder.trim() || '…'} in this account.`}
          </p>
          {looking && (
            <p className="muted small" role="status">
              <Spinner /> Looking for copies already on this account…
            </p>
          )}
          {found && found.length > 0 && (
            <div className="cloud-found" role="group" aria-label="Copies already on this account">
              <h5>A Keepfin copy is already on this account</h5>
              <ul>
                {found.map((f) => (
                  <li key={f.folder}>
                    <span>
                      <strong>{f.folder}</strong>
                      <small className="muted">{f.placeName ? `The copy “${f.placeName}” in your library` : 'A Keepfin copy of this library'}</small>
                    </span>
                    {f.placeId ? (
                      <Button size="sm" variant="secondary" loading={attaching === f.folder} disabled={!!attaching} onClick={() => void attach(f)}>
                        Use it
                      </Button>
                    ) : (
                      <Button size="sm" variant="secondary" disabled={folder === f.folder} onClick={() => onFolder(f.folder)}>
                        {folder === f.folder ? 'Chosen' : 'Use it'}
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
              <Button size="sm" variant="ghost" onClick={makeNew}>
                Make a new one
              </Button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function ProviderButton({provider, busy, disabled, onChoose}: {provider: CloudProviderChoice; busy: boolean; disabled: boolean; onChoose: () => void}) {
  return (
    <button type="button" className="cloud-provider" disabled={disabled} aria-busy={busy || undefined} onClick={onChoose}>
      <strong>{provider.name}</strong>
      <small>{provider.scope}</small>
    </button>
  )
}

function WebdavForm({onCancel, onConnected}: {onCancel: () => void; onConnected: (result: {account: CloudAccountView; state: CopiesState}) => void}) {
  const [url, setUrl] = useState('https://')
  const [user, setUser] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function connect() {
    setBusy(true)
    setError('')
    try {
      onConnected(await connectWebdav(url, user, password))
    } catch (caught) {
      setError(messageOf(caught))
      setBusy(false)
    }
  }

  return (
    <form
      className="copy-form cloud-webdav"
      onSubmit={(event) => {
        event.preventDefault()
        void connect()
      }}
    >
      <p className="small muted">Use an app password, not your main password. Keepfin keeps it encrypted on this computer only.</p>
      <label className="copy-field">
        <span>Server address</span>
        <input className="input" value={url} inputMode="url" autoComplete="off" spellCheck={false} onChange={(event) => setUrl(event.target.value)} />
      </label>
      <label className="copy-field">
        <span>User name</span>
        <input className="input" value={user} autoComplete="off" spellCheck={false} onChange={(event) => setUser(event.target.value)} />
      </label>
      <label className="copy-field">
        <span>App password</span>
        <input className="input" type="password" value={password} autoComplete="off" onChange={(event) => setPassword(event.target.value)} />
      </label>
      {error && <InlineAlert tone="error">{error}</InlineAlert>}
      <div className="copy-actions">
        <Button type="submit" variant="primary" size="sm" loading={busy} disabled={!url.trim() || !user.trim() || !password}>
          Connect
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel} disabled={busy}>
          Back
        </Button>
      </div>
    </form>
  )
}
