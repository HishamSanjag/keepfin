// Sizes for formats the site reports without one (Instagram gives a bitrate but no length). The shared core picks
// the formats (sizeProbes); here the server is asked for each file's length: HEAD first, then a one-byte ranged GET
// when HEAD is refused. Plain requests only: no cookies are sent. Anything that fails leaves the size unknown.
import {sizeProbes, type SizeProbe} from '../../shared/core/formats.js'

export type Fetch = (url: string, init: RequestInit) => Promise<Response>

const EACH_MS = 3_000
const TOTAL_MS = 4_000
const PARALLEL = 4

const positive = (text: string | null | undefined): number | undefined => {
  const n = text != null && /^\d+$/.test(text.trim()) ? Number(text) : 0
  return Number.isSafeInteger(n) && n > 0 ? n : undefined
}

async function lengthOf(probe: SizeProbe, fetcher: Fetch, total: AbortSignal, eachMs: number): Promise<number | undefined> {
  const ask = async (method: 'HEAD' | 'GET', extra: Record<string, string>) => {
    const controller = new AbortController()
    const signal = AbortSignal.any([total, controller.signal, AbortSignal.timeout(eachMs)])
    const response = await fetcher(probe.url, {method, redirect: 'follow', headers: {...probe.headers, ...extra}, signal})
    if (method === 'GET') controller.abort() // only the headers are needed
    return response
  }
  try {
    const head = await ask('HEAD', {})
    if (head.ok) return positive(head.headers.get('content-length'))
    if (head.status !== 403 && head.status !== 405) return undefined
    const ranged = await ask('GET', {Range: 'bytes=0-0'})
    const match = /^bytes 0-0\/(\d+)$/.exec(ranged.headers.get('content-range')?.trim() ?? '')
    return ranged.status === 206 && match ? positive(match[1]) : undefined
  } catch {
    return undefined
  }
}

/** Sets `filesize` on the formats of `info` that have no size, from the server. Never throws; at most ~4 s in all, less when `signal` (the inspection's) ends. */
export async function fillSizes(info: {formats?: unknown}, fetcher: Fetch = fetch, eachMs = EACH_MS, signal?: AbortSignal): Promise<void> {
  if (!Array.isArray(info.formats)) return
  const probes = sizeProbes(info).filter((p) => /^https?:\/\//i.test(p.url))
  if (!probes.length) return
  const total = signal ? AbortSignal.any([signal, AbortSignal.timeout(TOTAL_MS)]) : AbortSignal.timeout(TOTAL_MS)
  let next = 0
  const worker = async () => {
    while (next < probes.length && !total.aborted) {
      const probe = probes[next++]!
      const bytes = await lengthOf(probe, fetcher, total, eachMs)
      const format = (info.formats as Record<string, unknown>[]).find((f) => f.format_id === probe.formatId)
      if (bytes !== undefined && format) format.filesize = bytes
    }
  }
  await Promise.all(Array.from({length: Math.min(PARALLEL, probes.length)}, worker))
}
