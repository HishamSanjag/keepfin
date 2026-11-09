// A time zone or daylight saving change moves the modified time of every file on a drive that keeps local time (FAT, exFAT) by whole
// hours, so "equal stats mean unchanged" cannot be trusted that day (spec 3). This reads the baseline and one inventory side by side;
// the shared core decides whether what it sees is such a shift (ClockShift).
import type {BaselineEntry, InvLine, NameRulesId} from '../../shared/core/copies.js'
import {ClockShift, fastKey} from '../../shared/core/copies.js'
import {lineReader} from './lines.js'

/** Compares the stat of every file the baseline knows with its line in [inventoryFile] (both sorted by [rules]); [side] is the endpoint the inventory is of. */
export function detectClockShift(baselineFile: string, inventoryFile: string, rules: NameRulesId, side: 'main' | 'copy'): {detected: boolean; hours: number} {
  const shift = new ClockShift()
  const base = lineReader(baselineFile)
  const inv = lineReader(inventoryFile)
  try {
    const nextBase = () => {
      const line = base()
      return line === null ? null : (JSON.parse(line) as BaselineEntry)
    }
    const nextInv = () => {
      const line = inv()
      return line === null ? null : (JSON.parse(line) as InvLine)
    }
    let b = nextBase()
    let c = nextInv()
    while (b && c) {
      const kb = fastKey(rules, b.path)
      const kc = fastKey(rules, c.path)
      if (kb < kc) b = nextBase()
      else if (kb > kc) c = nextInv()
      else {
        const was = b[side]
        if (was && was.size === c.size) shift.add(was.mtimeMs, c.mtimeMs)
        b = nextBase()
        c = nextInv()
      }
    }
  } finally {
    base.close()
    inv.close()
  }
  return {detected: shift.detected, hours: shift.hours}
}
