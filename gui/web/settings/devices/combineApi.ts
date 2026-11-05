import type {CombineArgs, CombineState} from '../../../shared/combine'
import type {Operation} from '../../../shared/contracts'
import {request} from '../../bridge'

/** Typed wrappers over the combine* operations (gui/shared/combine.ts). Every call answers with the whole current state. */
const call = (op: Operation, combine?: CombineArgs) => request<CombineState>({op, combine})

export const getCombine = () => call('combineState')
/** Looks at both libraries and makes the review (nothing changes yet). The state follows it by push. */
export const previewCombine = (deviceId: string) => call('combinePreview', {deviceId})
export const startCombine = (deviceId: string, keep: 'mine' | 'theirs') => call('combineStart', {deviceId, keep})
export const pauseCombine = () => call('combinePause')
export const resumeCombine = () => call('combineResume')
export const cancelCombine = () => call('combineCancel')
export const previewSeparate = (deviceId: string) => call('combineSeparatePreview', {deviceId})
export const separateLibraries = (deviceId: string, keepOnPeer: 'all' | 'none', anyway: boolean) => call('combineSeparate', {deviceId, keepOnPeer, ...(anyway ? {anyway: true} : {})})
export const keepCombined = (deviceId: string) => call('combineKeep', {deviceId})
