import type {ToolStatus} from '../shared/contracts'

/**
 * Display names for engine components. The contract keeps the real tool names as
 * internal identifiers; the UI only ever shows these generic labels.
 */
export const toolLabels: Record<ToolStatus['name'], string> = {
  'yt-dlp': 'Download engine',
  'gallery-dl': 'Gallery engine',
  ffmpeg: 'Media processor',
  'js-runtime': 'Compatibility runtime',
}

/** The gallery engine installs itself the first time an image post needs it, so not having it yet is not a problem. */
export const installsWhenNeeded = (tool: ToolStatus) => tool.state === 'missing' && tool.name === 'gallery-dl'

/** A component that needs fixing: broken, or missing when it should be there. */
export const needsFixing = (tool: ToolStatus) => tool.state === 'error' || (tool.state === 'missing' && !installsWhenNeeded(tool))
