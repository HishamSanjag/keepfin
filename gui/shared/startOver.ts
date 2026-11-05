import type {StartOverOutcome} from './contracts'

/** The sentence shown after Keepfin opens again following Start over: what was erased, what was not, never more than was true. */
export function startOverMessage(outcome: StartOverOutcome): {tone: 'success' | 'error'; message: string} {
  const parts: string[] = []
  if (outcome.dataDeleted) parts.push(outcome.deleteFolder && outcome.folderDeleted ? 'Keepfin started over. Your Keepfin folder and what this PC kept were deleted.' : 'Keepfin started over. What this PC kept was erased and your Keepfin folder stayed.')
  else if (outcome.deleteFolder && outcome.folderDeleted) parts.push('Your Keepfin folder was deleted, but what this PC kept was not fully erased.')
  else parts.push('Start over did not finish.')
  if (outcome.deleteFolder && !outcome.folderDeleted && outcome.problems.length === 0) parts.push('Your Keepfin folder was not deleted.')
  for (const note of outcome.notes ?? []) parts.push(note)
  for (const problem of outcome.problems) parts.push(problem)
  return {tone: outcome.problems.length > 0 || !outcome.dataDeleted ? 'error' : 'success', message: parts.join(' ')}
}
