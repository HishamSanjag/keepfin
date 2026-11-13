import type {ReactNode} from 'react'
import {focusCapture} from '../capture/useCapture'
import {navigate} from '../nav'
import {builtinSpaceIds, type Space} from '../../shared/library'
import {Button, EmptyState} from '../ui/controls'
import {openDialog} from './dialogState'

export function PasteLinkButton({primary = true}: {primary?: boolean}) {
  return (
    <Button
      variant={primary ? 'primary' : 'secondary'}
      icon="link"
      onClick={() => {
        navigate('Downloads')
        requestAnimationFrame(focusCapture)
      }}
    >
      Paste a link
    </Button>
  )
}

/** Teaches the next step for a space with nothing in it yet. */
export function EmptySpace({space}: {space: Space}) {
  if (space.deviceOnly)
    return (
      <EmptyState icon="music" title="No music found yet">
        Keepfin looks in the folders listed in <strong>Settings › Library & playback › Music on this PC</strong>. Add a folder there, or put songs in one of them.
      </EmptyState>
    )
  const custom = !(builtinSpaceIds as readonly string[]).includes(space.id)
  const add = (
    <Button icon="plus" onClick={() => openDialog({type: 'import', space: space.id})}>
      Add files
    </Button>
  )
  if (custom)
    return (
      <EmptyState icon="folderPlus" title="Choose what goes here" actions={<>{add}</>}>
        Pick <strong>{space.name}</strong> in <strong>Save to</strong> next to any Download button, or add files you already have. Files you save here stay in {space.folder}.
      </EmptyState>
    )
  if (space.kind === 'music')
    return (
      <EmptyState icon="music" title="No music yet" actions={<><PasteLinkButton />{add}</>}>
        Songs from YouTube Music, Spotify, Apple Music and Deezer land here. Add music you already own, or paste a link to start your collection.
      </EmptyState>
    )
  if (space.kind === 'video')
    return (
      <EmptyState icon="video" title="No videos yet" actions={<><PasteLinkButton />{add}</>}>
        Videos you save to {space.name} show up as posters. Anything you start watching appears under Continue watching.
      </EmptyState>
    )
  return (
    <EmptyState icon="download" title="Nothing saved here yet" actions={<><PasteLinkButton />{add}</>}>
      Downloads that aren’t music land here, organised by folder. You can also add files you already have.
    </EmptyState>
  )
}

export function NoMatches({query, onClear, children}: {query: string; onClear: () => void; children?: ReactNode}) {
  return (
    <EmptyState
      icon="search"
      title="No matches"
      actions={
        <Button size="sm" onClick={onClear}>
          Clear search
        </Button>
      }
    >
      {children || <>Nothing here matches “{query}”. Try fewer words; accents and capital letters don’t matter.</>}
    </EmptyState>
  )
}
