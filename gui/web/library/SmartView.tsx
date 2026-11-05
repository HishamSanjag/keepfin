import {useState} from 'react'
import {useDensity} from './density'
import {HeroTrail} from './Hero'
import {libraryTrail} from './LibraryDetail'
import {isSong, useLibrary} from './libStore'
import type {SmartId} from './model'
import {PlaylistDetail} from './MusicViews'
import {Skeleton} from './Skeleton'

/** A smart playlist opened from Home ("See all"). */
export function SmartView({id}: {id: SmartId}) {
  const lib = useLibrary()
  const [selecting, setSelecting] = useState(false)
  const [density] = useDensity('playlists')
  if (!lib.loaded) return <Skeleton rows={6} />
  return (
    <HeroTrail.Provider value={libraryTrail('smart')}>
      <div className="space-view">
        <div className="space-body">
          <PlaylistDetail
            target={{type: 'smart', id}}
            songs={lib.snapshot.items.filter(isSong)}
            playlists={lib.playlists}
            selecting={selecting}
            density={density}
            onSelectingChange={setSelecting}
          />
        </div>
      </div>
    </HeroTrail.Provider>
  )
}
