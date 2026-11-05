import {useState} from 'react'
import type {CopiesState, CopyReviewAction, CopyReviewItem} from '../../../shared/copies'
import {messageOf} from '../../bridge'
import {plural} from '../../format'
import {Badge, Button, ConfirmInline, InlineAlert} from '../../ui/controls'
import {Dialog} from '../../ui/Dialog'
import {filesSummary} from '../../../shared/core/safety'
import {resolveReview} from './copiesApi'

const labels: Record<CopyReviewAction, string> = {
  keepBoth: 'Keep both',
  takeMain: 'Take the Keepfin folder’s',
  takeCopy: 'Take the copy’s',
  confirmDelete: 'Delete everywhere',
  restore: 'Restore it',
  sameDrive: 'This is the same drive',
  separateCopy: 'Treat as a separate copy',
  confirmHeld: 'Go ahead',
  dismiss: 'Leave it',
  signIn: 'Sign in again',
}

const titles: Record<CopyReviewItem['kind'], string> = {
  conflict: 'Edited in two places',
  copyDeleted: 'Removed on the copy',
  missingSource: 'Missing',
  missingEverywhere: 'Missing everywhere',
  nameCollision: 'Same name',
  unknownFile: 'Not in your library',
  keptOnBackup: 'Kept on the backup',
  difference: 'Differs',
  suspended: 'Removals paused',
  notHeld: 'No longer held',
  held: 'Large change',
  restoredDrive: 'Restored drive',
  signIn: 'Sign in',
}

/** A group's headline: "52 lyrics files and 1 video are not in your library", "Edited in two places: 3 files"; one item keeps its own title. */
function groupTitle(items: CopyReviewItem[]): string {
  if (items[0].kind === 'unknownFile') return `${filesSummary(items.map((item) => item.path ?? item.title))} ${items.length === 1 ? 'is' : 'are'} not in your library`
  if (items.length === 1) return items[0].title
  return `${titles[items[0].kind]}: ${plural(items.length, 'file')}`
}

/** What the group means and what its choice does, in plain words. */
function groupDetail(group: Group): string {
  if (group.items[0].kind === 'unknownFile') {
    const one = group.items.length === 1
    return `${one ? 'It is' : 'They are'} on ${group.place}, but your library does not list ${one ? 'it' : 'them'}, so Keepfin does not copy, change or delete ${one ? 'it' : 'them'}. Leave ${one ? 'it' : 'them'} to stop seeing this here: the ${one ? 'file stays' : 'files stay'} where ${one ? 'it is' : 'they are'}.`
  }
  return group.items[0].detail
}

type Group = {key: string; place: string; items: CopyReviewItem[]; actions: CopyReviewAction[]}

/** Items of one copy and one kind together; a group offers the choices every item in it has. */
export function reviewGroups(items: readonly CopyReviewItem[]): Group[] {
  const groups = new Map<string, Group>()
  for (const item of items) {
    const key = `${item.placeName}\u0000${item.kind}`
    const group = groups.get(key)
    if (group) {
      group.items.push(item)
      group.actions = group.actions.filter((action) => item.actions.includes(action))
    } else groups.set(key, {key, place: item.placeName, items: [item], actions: [...item.actions]})
  }
  return [...groups.values()]
}

/**
 * Needs a decision, as a dialog: conflicts, held plans, missing files, name collisions, files Keepfin did not put there, restored
 * drives, grouped by copy and kind so 50 files of one sort take one choice. Nothing here is lost while it waits.
 */
export function ReviewDialog({state, onState, onClose}: {state: CopiesState; onState: (state: CopiesState) => void; onClose: () => void}) {
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const groups = reviewGroups(state.review)
  const total = state.review.length + state.reviewMore

  async function act(group: Group, action: CopyReviewAction) {
    setBusy(`${group.key}|${action}`)
    setError('')
    try {
      let next: CopiesState | undefined
      for (const item of group.items) next = await resolveReview(item.id, action)
      if (next) onState(next)
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setBusy('')
    }
  }

  let place = ''
  return (
    <Dialog
      className="review-dialog"
      labelledBy="review-dialog-title"
      onClose={onClose}
      title="Needs a decision"
      eyebrow={total ? `${plural(total, 'file')} · nothing is lost or changed while they wait` : undefined}
      footer={<Button onClick={onClose}>Done</Button>}
    >
      {groups.length === 0 && <p className="muted">Nothing needs your attention. Every difference between your library and its copies has been settled without losing a version.</p>}
      <div className="review-groups">
        {groups.map((group) => {
          const heading = group.place !== place ? (place = group.place) : ''
          return (
            <div key={group.key} className="review-group-wrap">
              {heading && <h3 className="review-place">{heading}</h3>}
              <article className="review-group" data-kind={group.items[0].kind} aria-label={groupTitle(group.items)}>
                <div className="review-group-head">
                  <div className="review-group-text">
                    <Badge tone={group.items[0].kind === 'restoredDrive' ? 'danger' : 'warning'}>{titles[group.items[0].kind]}</Badge>
                    <strong>{groupTitle(group.items)}</strong>
                    <p className="small muted">{groupDetail(group)}</p>
                  </div>
                  <div className="review-group-actions">
                    {group.actions.map((action) =>
                      action === 'confirmDelete' ? (
                        <ConfirmInline key={action} label={labels[action]} confirmLabel="Delete" icon="trash" prompt={group.items.length === 1 ? 'Delete it from your library and every copy? It goes to the Keepfin trash.' : `Delete these ${group.items.length} from your library and every copy? They go to the Keepfin trash.`} onConfirm={() => act(group, action)} />
                      ) : (
                        <Button key={action} size="sm" variant="secondary" loading={busy === `${group.key}|${action}`} disabled={!!busy} onClick={() => void act(group, action)}>
                          {action === 'dismiss' && group.items.length > 1 ? 'Leave them' : labels[action]}
                        </Button>
                      ),
                    )}
                  </div>
                </div>
                {group.items.length > 1 && (
                  <details className="review-group-files">
                    <summary>Show the {group.items.length.toLocaleString()} files</summary>
                    <ul>
                      {group.items.map((item) => (
                        <li key={item.id} dir="auto">
                          {item.path ?? item.title}
                        </li>
                      ))}
                    </ul>
                  </details>
                )}
              </article>
            </div>
          )
        })}
      </div>
      {state.reviewMore > 0 && <p className="small muted">{state.reviewMore.toLocaleString()} more are not shown. Settle these first.</p>}
      {error && <InlineAlert tone="error">{error}</InlineAlert>}
    </Dialog>
  )
}
