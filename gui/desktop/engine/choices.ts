// Remembered choices: which YouTube Music recording the user picked for a Spotify / Apple Music / Deezer link.
// A small JSON file in userData; everything read from it is validated, and a damaged file is ignored.
import fs from 'node:fs/promises'
import {renameWithRetry} from '../fsutil.js'

export type MusicChoice = {videoId: string; title?: string; durationSec?: number; at: number}

const MAX_CHOICES = 2000
const videoId = /^[A-Za-z0-9_-]{11}$/

function validUrl(value: unknown): value is string {
  if (typeof value !== 'string' || value.length > 2048) return false
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && !url.username && !url.password
  } catch {
    return false
  }
}

/** A choice from untrusted data, or undefined when it is not one. */
export function validChoice(value: unknown): MusicChoice | undefined {
  if (!value || typeof value !== 'object') return
  const raw = value as Record<string, unknown>
  if (typeof raw.videoId !== 'string' || !videoId.test(raw.videoId)) return
  const choice: MusicChoice = {videoId: raw.videoId, at: typeof raw.at === 'number' && Number.isFinite(raw.at) ? raw.at : 0}
  if (typeof raw.title === 'string' && raw.title.trim()) choice.title = raw.title.trim().slice(0, 300)
  if (typeof raw.durationSec === 'number' && Number.isFinite(raw.durationSec) && raw.durationSec > 0 && raw.durationSec < 86_400) choice.durationSec = raw.durationSec
  return choice
}

export class MusicChoices {
  private choices = new Map<string, MusicChoice>()
  private loaded?: Promise<void>
  private writing: Promise<void> = Promise.resolve()

  constructor(
    private readonly file: string,
    private readonly now: () => number = Date.now,
  ) {}

  private load(): Promise<void> {
    return (this.loaded ??= (async () => {
      try {
        const data = JSON.parse(await fs.readFile(this.file, 'utf8')) as {choices?: unknown}
        const entries = data && typeof data.choices === 'object' && data.choices ? Object.entries(data.choices) : []
        for (const [url, value] of entries) {
          const choice = validChoice(value)
          if (choice && validUrl(url)) this.choices.set(url, choice)
        }
      } catch {
        // Missing or damaged: start empty; the next change rewrites the file.
      }
    })())
  }

  /** The choice for a source link, if the user made one. */
  async get(sourceUrl: string): Promise<MusicChoice | undefined> {
    await this.load()
    return this.choices.get(sourceUrl)
  }

  /** Remembers `choice` for `sourceUrl`. Throws when either is not valid. */
  async set(sourceUrl: string, choice: {videoId: string; title?: string; durationSec?: number}): Promise<MusicChoice> {
    if (!validUrl(sourceUrl)) throw new Error('This link cannot be remembered.')
    const valid = validChoice({...choice, at: this.now()})
    if (!valid) throw new Error('This recording cannot be remembered.')
    await this.load()
    this.choices.delete(sourceUrl)
    this.choices.set(sourceUrl, valid)
    while (this.choices.size > MAX_CHOICES) this.choices.delete(this.choices.keys().next().value!)
    await this.save()
    return valid
  }

  private save(): Promise<void> {
    const snapshot = JSON.stringify({version: 1, choices: Object.fromEntries(this.choices)})
    this.writing = this.writing.catch(() => {}).then(async () => {
      const temporary = `${this.file}.tmp`
      await fs.writeFile(temporary, snapshot, 'utf8')
      await renameWithRetry(temporary, this.file)
    })
    return this.writing
  }
}
