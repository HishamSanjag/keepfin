// Text that holds links goes to the capture flow instead of searching.
import {captureText} from '../capture/useCapture'
import {extractLinks} from '../format'
import {navigate} from '../nav'

/** Sends links to the capture flow. Returns true when the text held a link. */
export function routeLinks(text: string): boolean {
  if (!extractLinks(text).length) return false
  navigate('Downloads')
  captureText(text)
  return true
}
