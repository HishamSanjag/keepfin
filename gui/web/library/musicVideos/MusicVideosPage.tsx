// See all: every music video as a list, reached from the Library shelf. The breadcrumb ("Library") is the way back.
import {musicVideoWords} from '../../../shared/library'
import {plural} from '../../format'
import {PageHeader} from '../../shell/PageHeader'
import {EmptyState} from '../../ui/controls'
import {setLibView} from '../libNav'
import {MusicVideoRowItem, useRowMenu} from './cells'
import {useMusicVideoRows} from './rows'

export function MusicVideosPage() {
  const rows = useMusicVideoRows()
  const {openMenu, menuNode} = useRowMenu()
  return (
    <div className="space-view">
      <PageHeader title={musicVideoWords.shelf} crumbs={[{label: 'Library', onSelect: () => setLibView({type: 'home'})}]} caption={rows.length ? plural(rows.length, 'video') : undefined} />
        <div className="space-body">
          {rows.length ? (
            <div className="mv-list" role="list" aria-label={musicVideoWords.shelf}>
              {rows.map((row) => (
                <div key={row.id} role="listitem" className="mv-item">
                  <MusicVideoRowItem row={row} onMenu={openMenu} />
                </div>
              ))}
            </div>
          ) : (
            <EmptyState icon="video" title="No music videos yet">
              A song’s music video shows here once it has one. Open a song’s menu and choose Get the music video.
            </EmptyState>
          )}
          {menuNode}
        </div>
    </div>
  )
}
