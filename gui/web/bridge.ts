import type {PushEvent, Request} from '../shared/contracts'
import {scrubToolNames} from '../shared/errors'

declare global {
  interface Window {
    keepfin?: {
      request: (request: Request) => Promise<any>
      onPush?: (callback: (event: PushEvent) => void) => () => void
      /** v1 preload; kept so an older main process can still switch tabs. */
      onReveal?: (callback: (tab: string) => void) => () => void
      /** Absolute path of a dropped File (webUtils.getPathForFile in the preload); needed for dropping files onto a space. */
      pathForFile?: (file: File) => string
    }
    /** Optional instrumentation used by the UI smoke tests only. */
    __keepfinTest?: {rowRenders: Record<string, number>; audio?: () => {active: HTMLMediaElement; idle: HTMLMediaElement}}
  }
}

/** True inside the desktop app (preload bridge present). The browser preview has no engine. */
export const native = typeof window !== 'undefined' && !!window.keepfin

export const previewMessage =
  'This is the browser preview. Inspecting and downloading run only in the Keepfin desktop app.'

export async function request<T>(body: Request): Promise<T> {
  if (!window.keepfin) throw new Error(previewMessage)
  return window.keepfin.request(body)
}

export function onPush(callback: (event: PushEvent) => void): () => void {
  const bridge = window.keepfin
  if (!bridge) return () => {}
  if (bridge.onPush) return bridge.onPush(callback)
  if (bridge.onReveal)
    return bridge.onReveal((tab) => {
      if (tab === 'Downloads' || tab === 'Library') callback({type: 'reveal', tab})
    })
  return () => {}
}

export function messageOf(error: unknown) {
  const text = error instanceof Error ? error.message : String(error)
  return scrubToolNames(text.replace(/^Error invoking remote method '[^']*': (?:Error: )?/, ''))
}
