import type {MusicMatch} from '../../shared/contracts'
import {Icon} from '../ui/Icon'

const sourceNames: Record<MusicMatch['source'], string> = {
  spotify: 'Spotify',
  'apple-music': 'Apple Music',
  deezer: 'Deezer',
  youtube: 'YouTube',
}

/** How a song from a music service was matched to a recording on YouTube Music, with how sure the match is. */
export function MatchLine({match, compact}: {match: MusicMatch; compact?: boolean}) {
  const confidence = match.confidence !== undefined ? Math.round(match.confidence * 100) : undefined
  return (
    <div className="match-line">
      <Icon name="music" size={15} />
      <span>
        {match.source === 'youtube'
          ? 'Saved from another upload of this song'
          : match.matchedUrl
            ? 'Matched from YouTube Music'
            : 'Will be matched on YouTube Music'}
        {confidence !== undefined && (
          <>
            {' · '}
            <span className="tabular">{confidence}%</span>
          </>
        )}
      </span>
      {confidence !== undefined && (
        <span className={`match-meter${confidence < 70 ? ' low' : ''}`} aria-hidden="true">
          <span style={{transform: `scaleX(${confidence / 100})`}} />
        </span>
      )}
      {!compact && (
        <span className="match-source">
          {match.source === 'youtube'
            ? 'YouTube does not play the original in your region, so Keepfin uses another upload of the same song.'
            : `${sourceNames[match.source]} audio is DRM-protected, so the recording comes from YouTube Music.`}
        </span>
      )}
    </div>
  )
}
