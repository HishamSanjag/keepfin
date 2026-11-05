// "Keepfin mode": Keepfin Complete (library and player) or Just downloads. It is the existing library on/off setting
// under a clearer name, so nothing new is stored in the settings file. The names come from the shared core.
import {useEffect} from 'react'
import {words} from '../shared/core/start'
import {readPreference, savePreference} from './prefs'
import {getMeta, updateSettings, useMeta} from './store'
import {useLibraryEnabled} from './library/libStore'

export type KeepfinMode = 'downloader' | 'full'

export const modeLabels: Record<KeepfinMode, string> = {
  downloader: words.downloads.title,
  full: words.complete.title,
}

/** Full mode shows Home, Search, Library and the player; Just downloads shows only Downloads, Activity and Settings. */
export const useKeepfinMode = (): KeepfinMode => (useLibraryEnabled() ? 'full' : 'downloader')
export const useFullMode = () => useLibraryEnabled()

export async function setKeepfinMode(mode: KeepfinMode) {
  if (!getMeta().settings.library) return
  await updateSettings({library: {enabled: mode === 'full'}})
}

// The start pages ask before the Keepfin folder (and so the settings) exists: the answer waits here until the app is ready.
const startModeKey = 'keepfin-start-mode'
const isMode = (value: unknown): value is KeepfinMode => value === 'full' || value === 'downloader'

export const rememberStartMode = (mode: KeepfinMode) => savePreference(startModeKey, mode)

/** Saves the start pages' answer once they are done (`phase` is the storage phase) and the settings are there, then forgets it. */
export function useApplyStartMode(phase: string | undefined) {
  const meta = useMeta()
  const ready = meta.ready && !!meta.settings.library && phase === 'ready'
  useEffect(() => {
    if (!ready) return
    const mode = readPreference<KeepfinMode | null>(startModeKey, null, (value) => value === null || isMode(value))
    if (!mode) return
    savePreference(startModeKey, null)
    void setKeepfinMode(mode)
  }, [ready])
}
