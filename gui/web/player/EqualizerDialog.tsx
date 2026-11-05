// The equalizer and bass boost (Speed, sleep timer and output › Equalizer…), as on the phone.
import {Button, Switch} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import {BAND_DB, bands, choosePreset, presets, resetEqualizer, setBand, setBassBoost, setEqualizerOn, useEqualizer} from './equalizer'

const hz = (value: number) => (value >= 1000 ? `${value / 1000} kHz` : `${value} Hz`)
const db = (value: number) => `${value > 0 ? '+' : ''}${value} dB`

export function EqualizerDialog({onClose}: {onClose: () => void}) {
  const eq = useEqualizer()
  return (
    <Dialog
      title="Equalizer"
      labelledBy="equalizer-title"
      className="equalizer-dialog"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={resetEqualizer}>
            Reset
          </Button>
          <Button variant="primary" onClick={onClose} autoFocus>
            Done
          </Button>
        </>
      }
    >
      <div className="eq-row eq-head">
        <span>
          <strong>Equalizer</strong>
          <span className="muted small">Shapes the sound of everything the player plays on this PC</span>
        </span>
        <Switch label="Equalizer" checked={eq.enabled} onChange={setEqualizerOn} />
      </div>
      <label className="eq-row">
        <span>Preset</span>
        <select className="input" value={eq.preset ?? ''} onChange={(event) => choosePreset(event.target.value)} aria-label="Equalizer preset">
          {eq.preset === null && <option value="">Custom</option>}
          {presets.map((preset) => (
            <option key={preset.name} value={preset.name}>
              {preset.name}
            </option>
          ))}
        </select>
      </label>
      <div className="eq-bands" role="group" aria-label="Bands" data-off={!eq.enabled || undefined}>
        {bands.map((frequency, index) => (
          <label key={frequency} className="eq-band">
            <span className="eq-band-hz tabular">{hz(frequency)}</span>
            <input
              type="range"
              min={-BAND_DB}
              max={BAND_DB}
              step={1}
              value={eq.levels[index]}
              aria-label={`${hz(frequency)} band`}
              aria-valuetext={db(eq.levels[index])}
              onChange={(event) => setBand(index, Number(event.target.value))}
            />
            <span className="eq-band-db tabular">{db(eq.levels[index])}</span>
          </label>
        ))}
      </div>
      <label className="eq-row">
        <span>Bass boost</span>
        <input type="range" min={0} max={100} step={5} value={eq.bass} aria-label="Bass boost" aria-valuetext={`${eq.bass} %`} onChange={(event) => setBassBoost(Number(event.target.value))} />
        <span className="eq-band-db tabular">{eq.bass} %</span>
      </label>
    </Dialog>
  )
}
