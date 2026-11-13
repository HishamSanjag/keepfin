// The Library home card that offers Music on this PC; once it is on, the "This device" space takes its place.
import {native} from '../bridge'
import {Button} from '../ui/controls'
import {SettingCard} from '../ui/SettingCard'
import {libraryAct, useDeviceMusic} from './libStore'

export function DeviceMusicCard() {
  const state = useDeviceMusic()
  if (!native || !state || state.enabled) return null
  return (
    <SettingCard
      anchor="device-music-card"
      icon="computer"
      title="Music on this PC"
      description="See the music already on this PC, where it is. Nothing is copied."
      control={
        <Button variant="primary" onClick={() => void libraryAct('deviceMusicEnable', undefined, {enabled: true})}>
          Show my music
        </Button>
      }
    />
  )
}