import {useCallback, useEffect, useRef, useState} from 'react'
import {
  validateUrl,
  type DownloadOptions,
  type InspectResult,
  type MediaInfo,
  type Snapshot,
} from '../../shared/contracts'
import {messageOf, native, previewMessage, request} from '../bridge'
import {extractLinks, plural} from '../format'
import {applySnapshot, getMeta, useMeta} from '../store'
import {toast} from '../toasts'
import {defaultSpaceFor, type ExistingMatch} from '../../shared/library'
import {defaultChoice, formatRequest, type Choice} from './choice'
import {clearIntake, intakeSpace, landBack, startIntake} from './intake'
import {navigate} from '../nav'

export type CaptureEntry = {
  url: string
  state: 'waiting' | 'inspecting' | 'media' | 'collection' | 'error' | 'adding'
  media?: MediaInfo
  collection?: {url: string; title?: string; uploader?: string}
  error?: string
  choice?: Choice
}

export const maxBatch = 30
const workers = 2

// Global capture entry points (Ctrl+V anywhere, drag and drop, tray "reveal").
let handler: ((text: string, space?: string) => void) | undefined
let focusHandler: (() => void) | undefined
/** `space` presets Save to for this capture (a link brought in from a space). */
export function captureText(text: string, space?: string) {
  handler?.(text, space)
}
export function focusCapture() {
  focusHandler?.()
}

export function useCapture() {
  const meta = useMeta()
  const [input, setInput] = useState('')
  const [entries, setEntries] = useState<CaptureEntry[]>([])
  const [inputError, setInputError] = useState('')
  const [inspecting, setInspecting] = useState(false)
  const [startedAt, setStartedAt] = useState(0)
  const [adding, setAdding] = useState(false)
  const [space, setSpace] = useState<string | undefined>()
  // "Add to playlist": belongs to this review, off by default, cleared with it
  const [playlist, setPlaylist] = useState<string | undefined>()
  const [existing, setExisting] = useState<Record<string, ExistingMatch>>({})
  // the music video the person ticked for the song under review (one song at a time)
  const [keepVideo, setKeepVideo] = useState<{url: string; videoId: string} | undefined>()
  const [options, setOptionsState] = useState<DownloadOptions>(() => ({...meta.settings.options}))
  const optionsDirty = useRef(false)
  const session = useRef<{id: string; token: number} | null>(null)
  const token = useRef(0)
  const inspected = useRef('')
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const immediate = useRef(false)

  // Defaults follow Settings until the user edits options for this capture.
  useEffect(() => {
    if (!optionsDirty.current) setOptionsState({...meta.settings.options})
  }, [JSON.stringify(meta.settings.options)])
  const setOptions = useCallback((next: DownloadOptions) => {
    optionsDirty.current = true
    setOptionsState(next)
  }, [])

  const patch = (url: string, change: Partial<CaptureEntry>) =>
    setEntries((list) => list.map((entry) => (entry.url === url ? {...entry, ...change} : entry)))

  function stopSession() {
    const current = session.current
    session.current = null
    token.current++
    if (current) void request({op: 'cancelInspection', inspectionId: current.id}).catch(() => {})
    setInspecting(false)
  }

  async function run(urls: string[], fresh: boolean) {
    const id = crypto.randomUUID()
    const mine = ++token.current
    session.current = {id, token: mine}
    setInspecting(true)
    setStartedAt(Date.now())
    if (fresh) setEntries(urls.map((url) => ({url, state: 'waiting'})))
    else setEntries((list) => list.map((entry) => (urls.includes(entry.url) ? {url: entry.url, state: 'waiting'} : entry)))
    let next = 0
    const preset = getMeta().settings.defaultPreset
    const youtubeMusic = getMeta().settings.youtubeMusic
    const worker = async () => {
      while (token.current === mine && next < urls.length) {
        const url = urls[next++]
        patch(url, {state: 'inspecting'})
        try {
          const result = await request<InspectResult>({op: 'inspect', url, inspectionId: id})
          if (token.current !== mine) return
          if (result.type === 'collection')
            patch(url, {state: 'collection', collection: {url: result.url, title: result.title, uploader: result.uploader}})
          else patch(url, {state: 'media', media: result.media, choice: defaultChoice(result.media, preset, youtubeMusic)})
        } catch (error) {
          if (token.current !== mine) return
          patch(url, {state: 'error', error: messageOf(error)})
        }
      }
    }
    await Promise.all(Array.from({length: Math.min(workers, urls.length)}, worker))
    if (token.current === mine) {
      session.current = null
      setInspecting(false)
    }
  }

  function inspect(text = input) {
    setInputError('')
    if (!native) {
      setInputError(previewMessage)
      return
    }
    const links = extractLinks(text)
    if (!links.length) {
      setInputError(text.trim() ? 'That doesn’t look like a link. Paste a complete https:// address.' : 'Paste a link to get started.')
      return
    }
    if (links.length > maxBatch) {
      setInputError(`Inspect up to ${maxBatch} links at a time. This paste has ${links.length}.`)
      return
    }
    let urls: string[]
    try {
      urls = links.map(validateUrl)
    } catch (error) {
      setInputError(messageOf(error))
      return
    }
    stopSession()
    inspected.current = text
    void run([...new Set(urls)], true)
  }

  /** Editing the link always discards results for the previous link. */
  function edit(value: string) {
    if (value === input) return
    if (session.current || entries.length) {
      stopSession()
      setEntries([])
    }
    inspected.current = ''
    setInputError('')
    setInput(value)
  }

  // Auto-inspect once a complete link is present (debounced; immediate after paste).
  useEffect(() => {
    const text = input.trim()
    if (!text || inspected.current === input || !/^https?:\/\/\S+(?:\s+https?:\/\/\S+)*$/i.test(text)) return
    if (!native) return
    const delay = immediate.current ? 0 : 650
    immediate.current = false
    const timer = setTimeout(() => inspect(input), delay)
    return () => clearTimeout(timer)
  }, [input])

  function capture(text: string, forSpace?: string) {
    const links = extractLinks(text)
    if (!links.length) {
      toast({tone: 'info', message: 'No link found in what you pasted.'})
      return
    }
    if (forSpace) {
      startIntake(forSpace)
      setSpace(forSpace)
    } else if (intakeSpace()) {
      // A plain paste after a link from a space starts over with the usual destination.
      clearIntake()
      setSpace(undefined)
    }
    const value = links.join('\n')
    immediate.current = true
    if (value === input) inspect(value)
    else edit(value)
  }
  function pasted() {
    immediate.current = true
  }
  async function pasteFromClipboard() {
    try {
      const text = await navigator.clipboard.readText()
      capture(text)
      inputRef.current?.focus()
    } catch {
      setInputError('Clipboard access is unavailable. Press Ctrl+V in the field instead.')
      inputRef.current?.focus()
    }
  }
  function cancel() {
    const pending = entries.filter((entry) => entry.state === 'waiting' || entry.state === 'inspecting')
    stopSession()
    setEntries((list) =>
      list.map((entry) =>
        pending.includes(entry) ? {...entry, state: 'error', error: 'Inspection cancelled.'} : entry,
      ),
    )
  }
  function retry(url: string) {
    if (session.current) return
    void run([url], false)
  }
  function dismiss() {
    clearIntake()
    setSpace(undefined)
    setPlaylist(undefined)
    setExisting({})
    stopSession()
    setEntries([])
    setInput('')
    setInputError('')
    inspected.current = ''
    optionsDirty.current = false
    setOptionsState({...getMeta().settings.options})
    requestAnimationFrame(() => inputRef.current?.focus())
  }
  function removeEntry(url: string) {
    const next = entries.filter((entry) => entry.url !== url)
    if (!next.length) return dismiss()
    setEntries(next)
    const text = next.map((entry) => entry.url).join('\n')
    inspected.current = text
    setInput(text)
  }
  function setChoice(url: string, choice: Choice) {
    patch(url, {choice})
  }
  function openCollection(url: string, title?: string) {
    stopSession()
    inspected.current = url
    setInput(url)
    setEntries([{url, state: 'collection', collection: {url, title}}])
  }

  /** Request.space for a link: the chosen space, or the routed default. Absent when the library is off. */
  function spaceFor(url: string): {space?: string} {
    const library = getMeta().settings.library
    if (!library?.enabled) return {}
    return {space: space ?? defaultSpaceFor(url, library)}
  }

  // Single links: is this recording already in the target space? Nothing is skipped silently; this only informs.
  useEffect(() => {
    const library = meta.settings.library
    const ready = entries.filter((entry) => entry.state === 'media' && entry.media)
    if (!native || !library?.enabled || ready.length !== 1) {
      setExisting((previous) => (Object.keys(previous).length ? {} : previous))
      return
    }
    const url = ready[0].media!.url
    const target = space ?? defaultSpaceFor(url, library)
    if (!library.spaces.find((entry) => entry.id === target)?.skipExisting) {
      setExisting({})
      return
    }
    let alive = true
    request<Record<string, ExistingMatch>>({op: 'libraryMatch', urls: [url], space: target})
      .then((found) => alive && setExisting(found && typeof found === 'object' ? found : {}))
      .catch(() => alive && setExisting({}))
    return () => {
      alive = false
    }
  }, [entries.map((entry) => `${entry.url}:${entry.state}`).join('|'), space, meta.settings.library?.enabled])

  async function enqueue(targets?: CaptureEntry[]) {
    const list = (targets || entries).filter((entry) => entry.state === 'media' && entry.media && entry.choice)
    if (!list.length || adding) return
    setAdding(true)
    let added = 0
    const failures: string[] = []
    for (const entry of list) {
      patch(entry.url, {state: 'adding'})
      try {
        const snapshot = await request<Snapshot>({
          op: 'enqueue',
          url: entry.media!.url,
          ...spaceFor(entry.media!.url),
          ...(playlist && getMeta().settings.library?.enabled ? {joinPlaylist: playlist} : {}),
          format: formatRequest(entry.media!, entry.choice!),
          options,
          ...(keepVideo?.url === entry.media!.url && entry.choice!.tab === 'audio' ? {videoId: keepVideo.videoId} : {}),
        })
        applySnapshot(snapshot)
        added++
        setEntries((items) => items.filter((item) => item.url !== entry.url))
      } catch (error) {
        failures.push(entry.url)
        patch(entry.url, {state: 'media', error: messageOf(error)})
      }
    }
    setAdding(false)
    if (added) {
      const library = getMeta().settings.library
      const target = library?.enabled ? space ?? defaultSpaceFor(list[0].media!.url, library) : undefined
      const landed = landBack(target)
      toast({
        tone: 'success',
        message: `Added ${plural(added, 'download')} to the queue.`,
        ...(landed ? {action: {label: 'Show downloads', run: () => navigate('Downloads')}} : {}),
      })
    }
    const remaining = entries.filter((entry) => !list.includes(entry) || failures.includes(entry.url))
    // Keep the field in sync with what is still on screen so nothing silently disappears.
    const text = remaining.map((entry) => entry.url).join('\n')
    inspected.current = text
    setInput(text)
    if (!remaining.length) {
      setSpace(undefined)
      setPlaylist(undefined)
      optionsDirty.current = false
      setOptionsState({...getMeta().settings.options})
    }
  }

  useEffect(() => {
    handler = capture
    focusHandler = () => {
      inputRef.current?.focus()
      inputRef.current?.select()
    }
    return () => {
      handler = undefined
      focusHandler = undefined
    }
  })
  useEffect(() => () => stopSession(), [])

  return {
    input,
    edit,
    inputRef,
    inputError,
    entries,
    inspecting,
    startedAt,
    adding,
    space,
    setSpace,
    playlist,
    setPlaylist,
    existing,
    keepVideo,
    setKeepVideo,
    options,
    setOptions,
    inspect,
    pasted,
    pasteFromClipboard,
    cancel,
    retry,
    dismiss,
    removeEntry,
    setChoice,
    openCollection,
    enqueue,
  }
}
export type CaptureModel = ReturnType<typeof useCapture>
