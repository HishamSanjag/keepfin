// Run as a child process by scale.test.ts: opens a merge-state database in a clean process and reports what it costs.
// Usage: node --import tsx open-probe.ts <db path>
import {SqlState} from './sqlstate.js'

const mb = (n: number) => Math.round(n / 1e5) / 10
const usage = () => {
  const m = process.memoryUsage()
  return {rssMb: mb(m.rss), heapUsedMb: mb(m.heapUsed), externalMb: mb(m.external)}
}

const path = process.argv[2]
const started = performance.now()
const db = new SqlState(path)
const opened = performance.now()
const afterOpen = usage()
const items = db.readItems()
const done = performance.now()
const afterRead = usage()
console.log(
  JSON.stringify({
    openMs: Math.round(opened - started),
    readAllMs: Math.round(done - opened),
    warmOpenMs: Math.round(done - started),
    items: items.length,
    afterOpen,
    afterRead,
  }),
)
db.close()
