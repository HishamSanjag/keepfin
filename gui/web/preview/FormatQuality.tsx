// Format and Quality for all as two compact dropdowns on one line, for a collection or several links: the list of items stays the main thing on screen.
import {Select} from '../ui/controls'

type Option = {value: string; label: string; disabled?: boolean; title?: string}

export function FormatQuality({format, formatOptions, onFormat, quality, qualityOptions, onQuality, disabled}: {
  format: string
  formatOptions: Option[]
  onFormat: (value: string) => void
  quality: string
  qualityOptions: Option[]
  onQuality: (value: string) => void
  disabled?: boolean
}) {
  return (
    <div className="preview-row preview-selects">
      <div className="preview-select">
        <span className="preview-row-label">Format</span>
        <Select label="Format" value={format} disabled={disabled} onChange={onFormat} options={formatOptions} />
      </div>
      <div className="preview-select">
        <span className="preview-row-label">Quality for all</span>
        <Select label="Quality for all" value={quality} disabled={disabled} onChange={onQuality} options={qualityOptions} />
      </div>
    </div>
  )
}
