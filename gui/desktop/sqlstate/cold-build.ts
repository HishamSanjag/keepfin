// Run as a child process by scale.test.ts: the cold path in a clean process. Reads the checkpoint parts of a directory,
// bulk-loads them into a new database, then admits the ops above the checkpoint, and reports time and peak memory.
// Usage: node --import tsx cold-build.ts <parts dir> <db path> <library> <tail ops file (one op per line)>
import {readFileSync, readdirSync} from 'node:fs'
import {join} from 'node:path'
import {SqlState} from './sqlstate.js'

const [dir, dbPath, library, tailFile] = process.argv.slice(2)
const parts = readdirSync(dir).map((f) => new Uint8Array(readFileSync(join(dir, f))))
const tail = readFileSync(tailFile, 'utf8').split('\n').filter(Boolean)
const baselineRssMb = Math.round(process.memoryUsage().rss / 1e5) / 10
const started = performance.now()
const db = new SqlState(dbPath, {synchronous: 'NORMAL'})
const loaded = db.bulkLoad(parts, library)
const loadedAt = performance.now()
let admitted = 0
let peak = loaded.peakRssMb
for (let i = 0; i < tail.length; i += 5000) {
  admitted += db.admit(tail.slice(i, i + 5000)).filter((c) => c === 'admitted').length
  peak = Math.max(peak, process.memoryUsage().rss / 1e6)
}
const done = performance.now()
db.close()
console.log(
  JSON.stringify({
    ok: loaded.ok,
    rows: loaded.rows,
    coreMs: Math.round(loaded.coreMs),
    parseMs: Math.round(loaded.parseMs),
    insertMs: Math.round(loaded.insertMs),
    bulkLoadMs: Math.round(loadedAt - started),
    tailMs: Math.round(done - loadedAt),
    totalMs: Math.round(done - started),
    tailAdmitted: admitted,
    baselineRssMb,
    peakRssMb: Math.round(peak * 10) / 10,
  }),
)
