import '../../styles/where.css'
import type {WhereKind, WhereState} from '../../../shared/where'
import {navigate} from '../../nav'
import {Button, InlineAlert} from '../../ui/controls'
import {Icon} from '../../ui/Icon'
import {Group} from '../../ui/SettingCard'
import {canKeepCopy, holdsWords, kindWords, onePlaceWords, singlesWords, sizeWords, statusTone, statusWords} from '../whereText'
import {useNow, useWhere} from './useWhere'

const iconOf: Record<WhereKind, string> = {this: 'home', pc: 'sidebar', phone: 'pip', drive: 'folder', cloud: 'globe'}

/** The anchor Settings → Storage reads to open Add a copy with a space chosen (empty: nothing chosen). */
export const keepCopyAnchor = (spaceId?: string) => `keep-copy:${spaceId ?? ''}`

/**
 * "Where your files are" (dev-docs/plans/WHERE-FILES.md): one row per place that holds part of this library, this computer first, with
 * what it holds, how much, and its state; a tap opens that place (a paired device's card, or a copy). Under the list, the files that
 * only one place has, each with Keep a copy… for the ones on this computer. The same component sits in Storage and in Devices.
 */
export function WhereFiles() {
  const state = useWhere()
  const now = useNow()
  if (!state || !state.available) return null
  return <WhereView state={state} now={now} />
}

export function WhereView({state, now}: {state: WhereState; now: number}) {
  const only = state.places.length === 1 ? state.places[0] : undefined
  const thisName = 'this PC'
  return (
    <Group id="where" title="Where your files are" description="Every place that keeps part of your library, and what it keeps.">
      <ul className="where-list" aria-label="Places that hold your files">
        {state.places.map((place) => {
          const size = sizeWords(place)
          const tone = statusTone(place.status)
          const status = statusWords(place, now)
          const body = (
            <>
              <span className="where-icon" aria-hidden="true">
                <Icon name={iconOf[place.kind]} size={18} />
              </span>
              <span className="where-text">
                <span className="where-name">
                  <strong>{place.name}</strong>
                  {place.thisDevice && <span className="where-tag">{kindWords.this}</span>}
                </span>
                <span className="small muted">{holdsWords(place.holds)}</span>
              </span>
              <span className="where-state small">
                {size && <span className="tabular where-size">{size}</span>}
                <span className="where-status" data-tone={tone}>
                  {status}
                </span>
              </span>
              {place.link && (
                <span className="where-chevron" aria-hidden="true">
                  <Icon name="chevronRight" size={16} />
                </span>
              )}
            </>
          )
          const link = place.link
          return (
            <li key={place.id}>
              {link ? (
                <button
                  type="button"
                  className="where-row"
                  data-state={place.status.state}
                  aria-label={`${place.name}: ${holdsWords(place.holds)}${size ? `, ${size}` : ''}, ${status}. ${link.to === 'device' ? 'Open the device' : 'Open the copy'}`}
                  onClick={() => (link.to === 'device' ? navigate('Settings', 'devices', `device-${link.id}`) : navigate('Settings', 'storage', `copy-${link.id}`))}
                >
                  {body}
                </button>
              ) : (
                <div className="where-row" data-state={place.status.state}>
                  {body}
                </div>
              )}
            </li>
          )
        })}
      </ul>
      {only && only.items > 0 && (
        <div className="where-note">
          <p className="small muted">{onePlaceWords(thisName)}</p>
          <Button size="sm" icon="plus" onClick={() => navigate('Settings', 'storage', keepCopyAnchor())}>
            Keep a copy…
          </Button>
        </div>
      )}
      {!only && state.singles.length > 0 && (
        <div className="where-singles" role="group" aria-label="Files that are in one place only">
          {state.singles.map((single) => (
            <InlineAlert
              key={`${single.placeId}:${single.kind}`}
              tone="warning"
              actions={
                canKeepCopy(single) && (
                  <Button size="sm" aria-label={`Keep a copy of ${single.kind === 'other' ? 'the other files' : `the ${single.kind}`} that are only on ${thisName}`} onClick={() => navigate('Settings', 'storage', keepCopyAnchor(single.spaceId))}>
                    Keep a copy…
                  </Button>
                )
              }
            >
              {singlesWords(single, thisName)}
            </InlineAlert>
          ))}
        </div>
      )}
    </Group>
  )
}
