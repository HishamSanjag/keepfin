// `npm run dev:demo`: the browser preview with the fake engine from the UI tests (scripts/test/ui-fixtures/fake-bridge.js).
// Live reload like `npm run dev`, but with a made-up library, queue, history and links, so screens can be seen while
// they are edited. Everything shown is SYNTHETIC; it proves nothing about real downloads or providers.
// Pick a fixture set with the address: ?scenario=full (default) | empty | groups | stress.
import fs from 'node:fs'
import path from 'node:path'
import {createServer} from 'vite'
import {bridgePath, root} from '../test/ui-fixtures/harness.mjs'
import {ensureMedia, mediaPlugin} from '../test/ui-fixtures/media.mjs'

ensureMedia()

/** Loads the fake engine before the app starts (classic scripts in <head> run before the deferred app module). */
const demoPlugin = () => ({
  name: 'keepfin-demo',
  configureServer(server) {
    server.middlewares.use('/__demo/fake-bridge.js', (_request, response) => {
      response.setHeader('Content-Type', 'text/javascript')
      response.end(fs.readFileSync(bridgePath))
    })
  },
  transformIndexHtml: () => [
    {
      tag: 'script',
      injectTo: 'head-prepend',
      children: `{const q = new URLSearchParams(location.search)
window.__FAKE_OPTIONS = {scenario: q.get('scenario') || 'full'}
window.__KEEPFIN_MEDIA_BASE = location.origin + '/__media'}`,
    },
    {tag: 'script', injectTo: 'head-prepend', attrs: {src: '/__demo/fake-bridge.js'}},
  ],
})

const server = await createServer({
  configFile: path.join(root, 'gui/vite.config.ts'),
  root: path.join(root, 'gui/web'),
  plugins: [mediaPlugin(), demoPlugin()],
  server: {open: true},
})
await server.listen()
server.printUrls()
console.log('\n  Demo mode: fake library and downloads. Try ?scenario=empty, ?scenario=groups or ?scenario=stress.\n')
