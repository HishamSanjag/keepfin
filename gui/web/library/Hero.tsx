import {createContext, useContext, type ReactNode} from 'react'
import {PageHeader, type Crumb} from '../shell/PageHeader'
import {Icon} from '../ui/Icon'
import {Mosaic} from './Art'
import {useArtColor} from './artColor'

/** The list below a hero provides its Play / Shuffle / more actions here, so they sit under the title instead of in a row of their own. */
export const HeroActions = createContext<ReactNode>(null)
/** The page that shows a detail (a space, the Library) provides the way back: "Music › Albums". */
export const HeroTrail = createContext<Crumb[] | undefined>(undefined)

/**
 * The page header of an album, artist, playlist or collection: its title is the page's h1 at the same place as on every other page,
 * the facts are the caption, the actions sit under them, and the cover (or a mosaic of up to four covers) sits at the right with a
 * soft glow in the artwork's colour. Without artwork the glow uses the space colour.
 */
export function Hero({
  art,
  title,
  kicker,
  meta,
  children,
  round,
}: {
  art: string[]
  title: string
  kicker: string
  meta: string
  children?: ReactNode
  round?: boolean
}) {
  const actions = useContext(HeroActions)
  const trail = useContext(HeroTrail)
  const sampled = useArtColor(art[0])
  return (
    <PageHeader
      label={title}
      title={title}
      caption={`${kicker} · ${meta}`}
      crumbs={trail}
      band={sampled || undefined}
      actions={actions || undefined}
      art={<div className={`hero-art${round ? ' round' : ''}`}>{art.length ? <Mosaic ids={art} kind="audio" /> : <span className="art"><Icon name={round ? 'user' : 'disc'} size={44} /></span>}</div>}
    >
      {children}
    </PageHeader>
  )
}
