// Subtitle sidecars → WebVTT for <track>. SRT and ASS/SSA are converted; styling is simplified.
import {extensionOf} from '../../shared/library.js'

const BOM = /^﻿/

export function srtToVtt(source: string): string {
  const body = source
    .replace(BOM, '')
    .replace(/\r\n?/g, '\n')
    .replace(/(\d{1,2}:\d{2}:\d{2}),(\d{1,3})/g, (_m, clock: string, ms: string) => `${clock}.${ms.padEnd(3, '0')}`)
    .replace(/(^|\n)(\d{1,2}:\d{2}),(\d{3})(?= -->)/g, '$1$2.$3')
    .replace(/<\/?(?!(?:b|i|u|c|v|ruby|rt|lang)\b)[a-z][^>]*>/gi, '')
  return `WEBVTT\n\n${body.trim()}\n`
}

const stamp = /^(\d+):(\d{2}):(\d{2})[.:](\d{1,3})$/

function assTime(value: string): string | undefined {
  const match = stamp.exec(value.trim())
  if (!match) return
  const [, h, m, s, fraction] = match
  // ASS stores centiseconds ("0:00:01.50"); tolerate milliseconds.
  const ms = fraction.length <= 2 ? Number(fraction.padEnd(2, '0')) * 10 : Number(fraction.padEnd(3, '0'))
  return `${String(h).padStart(2, '0')}:${m}:${s}.${String(ms).padStart(3, '0')}`
}

/** Dialogue lines from an ASS/SSA script, with override tags removed and \N turned into line breaks. */
export function assToVtt(source: string): string {
  const lines = source.replace(BOM, '').replace(/\r\n?/g, '\n').split('\n')
  let fields: string[] = []
  const cues: {start: string; end: string; text: string; order: number}[] = []
  let inEvents = false
  for (const raw of lines) {
    const line = raw.trim()
    if (/^\[.*\]$/.test(line)) {
      inEvents = /^\[events\]$/i.test(line)
      continue
    }
    if (!inEvents) continue
    if (/^format:/i.test(line)) {
      fields = line.slice(7).split(',').map((name) => name.trim().toLowerCase())
      continue
    }
    if (!/^dialogue:/i.test(line) || !fields.length) continue
    const values = line.slice(9).split(',')
    const textAt = fields.indexOf('text')
    if (textAt < 0 || values.length < fields.length) continue
    const text = values
      .slice(textAt)
      .join(',')
      .replace(/\{[^}]*\}/g, '')
      .replace(/\\N/gi, '\n')
      .replace(/\\h/g, ' ')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .trim()
    const start = assTime(values[fields.indexOf('start')] ?? '')
    const end = assTime(values[fields.indexOf('end')] ?? '')
    if (!start || !end || !text) continue
    cues.push({start, end, text, order: cues.length})
  }
  cues.sort((a, b) => (a.start < b.start ? -1 : a.start > b.start ? 1 : a.order - b.order))
  return `WEBVTT\n\n${cues.map((cue) => `${cue.start} --> ${cue.end}\n${cue.text}`).join('\n\n')}\n`
}

/** WebVTT for any supported subtitle file's text. */
export function toVtt(file: string, source: string): string {
  switch (extensionOf(file)) {
    case 'srt':
      return srtToVtt(source)
    case 'ass':
    case 'ssa':
      return assToVtt(source)
    default:
      return source.replace(BOM, '')
  }
}
