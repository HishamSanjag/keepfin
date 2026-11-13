// Settings › Library & playback › Music on this PC: the switch, the folders it looks in, and the songs removed from Keepfin.
import {native} from '../bridge'
import {deviceCaption, findingMusic} from '../library/deviceText'
import {libraryAct, useDeviceMusic} from '../library/libStore'
import {plural} from '../format'
import {Button, Switch} from '../ui/controls'
import {Group, SettingCard} from '../ui/SettingCard'

export function DeviceMusicSettings() {
  const state = useDeviceMusic()
  const disabled = !native || !state
  const on = state?.enabled === true
  return (
    <Group id="device-music" cards title="Music on this PC" description="See the music already on this PC, where it is. Nothing is copied.">
      <SettingCard
        anchor="device-music-switch"
        icon="computer"
        title="Show the music on this PC"
        description={on ? `${deviceCaption}: it is not saved in your Keepfin folder and does not go to your other devices. ${state.scanning ? findingMusic(state.found) : plural(state.count, 'song')}.` : 'Adds a “This device” space to your library. Only on this PC.'}
        control={<Switch label="Show the music on this PC" checked={on} disabled={disabled} onChange={(enabled) => void libraryAct('deviceMusicEnable', undefined, {enabled})} />}
      />
      {on && (
        <SettingCard
          anchor="device-music-folders"
          icon="folder"
          title="Folders it looks in"
          description="Songs in these folders and the folders inside them are listed. Songs shorter than 30 seconds and folders like Ringtones are left out."
        >
          <ul className="space-list" aria-label="Folders it looks in">
            {state.folders.map((folder) => (
              <li key={folder} className="space-line">
                <span className="space-line-text">
                  <span className="path" title={folder}>
                    {folder}
                  </span>
                </span>
                <Button size="sm" variant="ghost" icon="trash" disabled={disabled} aria-label={`Remove ${folder}`} onClick={() => void libraryAct('deviceMusicFolders', undefined, {action: 'remove', text: folder})}>
                  Remove
                </Button>
              </li>
            ))}
            {!state.folders.length && <li className="muted small">No folders yet. Add one to see its music.</li>}
          </ul>
          <div className="button-row">
            <Button icon="plus" disabled={disabled} onClick={() => void libraryAct('deviceMusicFolders', undefined, {action: 'add'})}>
              Add a folder
            </Button>
            <Button icon="eye" disabled={disabled} onClick={() => void libraryAct('deviceMusicUnhide')}>
              Show hidden songs again
            </Button>
          </div>
        </SettingCard>
      )}
    </Group>
  )
}