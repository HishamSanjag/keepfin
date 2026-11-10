import type {CloudProviderId} from '../../../shared/copies'
import {Icon} from '../../ui/Icon'

/** The service's own mark for Dropbox and Google Drive; a globe for the others. */
export function ProviderIcon({provider, size = 18}: {provider?: CloudProviderId; size?: number}) {
  if (provider === 'dropbox')
    return (
      <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
        <path
          fill="#0061FF"
          d="M6 1.8 0 5.6l6 3.9 6-3.9-6-3.8Zm12 0-6 3.8 6 3.9 6-3.9-6-3.8ZM0 13.3l6 3.8 6-3.8-6-3.8-6 3.8Zm18-3.8-6 3.8 6 3.8 6-3.8-6-3.8ZM6 18.4l6 3.8 6-3.8-6-3.9-6 3.9Z"
        />
      </svg>
    )
  if (provider === 'drive')
    return (
      <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
        <path fill="#FBBC04" d="M8 2.5h8l7.5 13h-8Z" />
        <path fill="#34A853" d="M8 2.5 12 9.4 4.5 22.5l-4-7Z" />
        <path fill="#4285F4" d="M8.5 15.5h15l-4 7h-15Z" />
      </svg>
    )
  return <Icon name="globe" size={size} />
}
