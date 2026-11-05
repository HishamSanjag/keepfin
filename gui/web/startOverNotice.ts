import type {StartOverOutcome} from '../shared/contracts'
import {startOverMessage} from '../shared/startOver'
import {native, request} from './bridge'
import {toast} from './toasts'

/** After Start over, Keepfin opens again and says honestly what was and was not erased. The outcome is read once. */
export function showStartOverOutcome() {
  if (!native) return
  request<StartOverOutcome | null>({op: 'startOverResult'})
    .then((outcome) => {
      if (!outcome || typeof outcome !== 'object') return
      const {tone, message} = startOverMessage(outcome)
      toast({tone, message, key: 'start-over', duration: tone === 'error' ? 20000 : 10000})
    })
    .catch(() => {})
}
