import {defaultSpaceFor, folderSpaces} from '../../shared/library'
import {useLibraryEnabled, useLibrarySettings} from '../library/libStore'
import {navigate} from '../nav'
import {Icon} from '../ui/Icon'
import {Menu, type MenuItem} from '../ui/Menu'

/** The space a link goes to when nothing was picked: music links to the music route, the rest to the other route. */
export function useDefaultSpace(url: string | undefined) {
  const settings = useLibrarySettings()
  return settings && url ? defaultSpaceFor(url, settings) : settings?.spaces[0]?.id
}

/**
 * "Save to: Music" next to every Download button. Changing it affects this download only; the menu ends with
 * "Change defaults…". Renders nothing when the library is off.
 */
export function SaveTo({url, value, onChange, mixed}: {url?: string; value?: string; onChange: (space: string | undefined) => void; mixed?: boolean}) {
  const enabled = useLibraryEnabled()
  const settings = useLibrarySettings()
  const fallback = useDefaultSpace(url)
  if (!enabled || !settings) return null
  const targets = folderSpaces(settings.spaces)
  const current = targets.find((space) => space.id === (value ?? fallback)) ?? targets[0]
  const items: MenuItem[] = [
    {heading: 'Save this download to'},
    ...targets.map((space) => ({
      label: space.name,
      detail: space.folder,
      checked: space.id === current?.id,
      onSelect: () => onChange(space.id === fallback ? undefined : space.id),
    })),
    'separator',
    {label: 'Change defaults…', icon: 'settings', onSelect: () => navigate('Settings', 'library')},
  ]
  return (
    <span className="save-to">
      <Menu
        label={`Save to: ${mixed && value === undefined ? 'by source, music links to ' + (current?.name ?? 'your library') + ' and the rest by default' : current?.name}. Change where this download is saved`}
        items={items}
        align="start"
        width={280}
        triggerClassName="save-chip"
        trigger={
          <>
            <Icon name={current?.kind === 'music' ? 'music' : current?.kind === 'video' ? 'video' : 'folder'} size={15} />
            <span className="save-label">Save to:</span>
            <strong>{mixed && value === undefined ? 'by source' : current?.name}</strong>
            <Icon name="chevronDown" size={13} />
          </>
        }
      />
    </span>
  )
}
