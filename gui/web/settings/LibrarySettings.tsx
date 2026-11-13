import {useEffect, useState} from 'react'
import type {LibrarySettings as LibrarySettingsModel} from '../../shared/library'
import {folderSpaces, isKeptSpace} from '../../shared/library'
import {native} from '../bridge'
import {plural} from '../format'
import {openDialog} from '../library/dialogState'
import {useLibrary} from '../library/libStore'
import {modeLabels, setKeepfinMode, type KeepfinMode} from '../mode'
import {updateSettings, useMeta} from '../store'
import {Button, Segmented, Select, Switch} from '../ui/controls'
import {Group, SettingCard} from '../ui/SettingCard'
import {SpaceMark} from '../library/SpacesHub'
import {DeviceMusicSettings} from './DeviceMusicSettings'
import {useSpaceCounts} from '../library/spacesModel'

const kindWord = {music: 'Music', video: 'Videos', mixed: 'Mixed'} as const

export function LibrarySettings() {
  const {settings} = useMeta()
  const lib = useLibrary()
  const library = settings.library
  const counts = useSpaceCounts(library.spaces)
  const disabled = !native
  // only what changed: an older copy of the spaces must never go back to main
  const save = (change: Partial<LibrarySettingsModel>) => void updateSettings({library: change})
  const [fade, setFade] = useState(library.crossfade)
  useEffect(() => setFade(library.crossfade), [library.crossfade])
  const spaceOptions = folderSpaces(library.spaces).map((space) => ({value: space.id, label: space.name}))
  return (
    <>
      <Group cards title="Library">
        <SettingCard
          anchor="mode"
          icon="library"
          title="Keepfin mode"
          description="Downloader + library & player keeps what you save in organised spaces with Home, Search and a built-in player. Downloader only changes nothing on disk; finished downloads stay in Downloads."
          control={
            <Segmented<KeepfinMode>
              label="Keepfin mode"
              value={library.enabled ? 'full' : 'downloader'}
              disabled={disabled}
              onChange={(next) => void setKeepfinMode(next)}
              options={[
                {value: 'downloader', label: modeLabels.downloader},
                {value: 'full', label: modeLabels.full},
              ]}
            />
          }
        />
        <SettingCard
          anchor="open-with"
          icon="play"
          title="Open media with"
          description="Play opens files in Keepfin’s player or in your computer’s default app. Open in another app stays in every menu."
          disabled={!library.enabled}
          control={
            <Segmented<'keepfin' | 'system'>
              label="Open media with"
              value={library.openWith}
              disabled={disabled || !library.enabled}
              onChange={(openWith) => save({openWith})}
              options={[
                {value: 'keepfin', label: 'Keepfin player'},
                {value: 'system', label: 'Default app'},
              ]}
            />
          }
        />
        <SettingCard
          anchor="home-releases"
          icon="user"
          title="Show new releases on Home"
          description="A small card on Home tells you when artists you follow have new releases. Turn it off to remove it for good; you can still follow artists and see their releases from Library, Artists."
          disabled={!library.enabled}
          control={<Switch label="Show new releases on Home" checked={settings.newReleasesOnHome} disabled={disabled || !library.enabled} onChange={(newReleasesOnHome) => void updateSettings({newReleasesOnHome})} />}
        />
      </Group>
      {library.enabled && (
        <>
          <Group id="routes" cards title="Where downloads go" description="Used when you don’t pick a space. The Save to button next to Download can always change it for one download.">
            <SettingCard
              icon="music"
              title="Music links"
              description="Links from YouTube Music, Spotify, Apple Music and Deezer, whatever format you choose."
              control={<Select label="Space for music links" value={library.routes.music} disabled={disabled} onChange={(music) => save({routes: {...library.routes, music}})} options={spaceOptions} />}
            />
            <SettingCard
              icon="video"
              title="Everything else"
              description="Videos and audio from any other site, so a random audio grab never lands in your music."
              control={<Select label="Space for everything else" value={library.routes.other} disabled={disabled} onChange={(other) => save({routes: {...library.routes, other}})} options={spaceOptions} />}
            />
          </Group>
          <Group id="spaces" title="Spaces" description="A space is a folder with its own rules. Removing one never deletes its files.">
            <ul className="space-list" aria-label="Spaces">
              {library.spaces.map((space) => (
                <li key={space.id} className="space-line">
                  <SpaceMark space={space} />
                  <span className="space-line-text">
                    <strong>
                      {space.name} <span className="muted small">· {space.type === 'smart' ? 'Smart' : kindWord[space.kind]} · {space.type === 'smart' ? plural(counts.get(space.id) ?? 0, 'match', 'matches') : plural(lib.bySpace.get(space.id)?.length ?? 0, 'item')}</span>
                    </strong>
                    <span className="path" title={space.folder}>
                      {space.type === 'smart' ? 'Gathers items by rules' : space.folder}
                    </span>
                  </span>
                  <Button size="sm" icon="edit" disabled={disabled} onClick={() => openDialog({type: 'space', space})} aria-label={`Edit ${space.name}`}>
                    Edit
                  </Button>
                  {!isKeptSpace(space.id) && (
                    <Button size="sm" variant="ghost" icon="trash" disabled={disabled} onClick={() => openDialog({type: 'removeSpace', space})} aria-label={`Remove ${space.name}`}>
                      Remove
                    </Button>
                  )}
                </li>
              ))}
            </ul>
            <div>
              <Button icon="plus" disabled={disabled} onClick={() => openDialog({type: 'space'})}>
                New space
              </Button>
            </div>
          </Group>
          <DeviceMusicSettings />
          <Group cards title="Tags" description="Genres, tags and categories from the source can be kept on new downloads, so you can filter and build smart spaces by them. Existing items are not changed.">
            <SettingCard
              anchor="tags"
              icon="tag"
              title="Import tags from sources"
              description="Off keeps new downloads untagged. You can always add your own tags from an item’s menu."
              control={<Switch label="Import tags from sources" checked={library.importTags !== false} disabled={disabled} onChange={(importTags) => save({importTags})} />}
            />
          </Group>
          <Group cards title="Playback">
            <SettingCard
              anchor="normalize"
              icon="volume"
              title="Volume normalisation"
              description="Evens out loud and quiet songs by lowering the louder ones. It never boosts, so nothing distorts."
              control={<Switch label="Volume normalisation" checked={library.normalize} disabled={disabled} onChange={(normalize) => save({normalize})} />}
            />
            <SettingCard
              anchor="crossfade"
              icon="shuffle"
              title="Crossfade"
              description={fade ? `Songs overlap for ${fade} seconds. Songs are otherwise switched within a few milliseconds.` : 'Off. Songs are switched within a few milliseconds, which is close to gapless but not exactly gapless.'}
              control={
                <div className="scale-control">
                  <input
                    type="range"
                    className="range"
                    min={0}
                    max={12}
                    step={1}
                    value={fade}
                    disabled={disabled}
                    aria-label="Crossfade"
                    aria-valuetext={fade ? `${fade} seconds` : 'Off'}
                    style={{['--fill' as string]: `${(fade / 12) * 100}%`}}
                    onChange={(event) => setFade(Number(event.target.value))}
                    onPointerUp={() => fade !== library.crossfade && save({crossfade: fade})}
                    onKeyUp={() => fade !== library.crossfade && save({crossfade: fade})}
                    onBlur={() => fade !== library.crossfade && save({crossfade: fade})}
                  />
                  <span className="tabular scale-value">{fade ? `${fade} s` : 'Off'}</span>
                </div>
              }
            />
          </Group>
        </>
      )}
    </>
  )
}
