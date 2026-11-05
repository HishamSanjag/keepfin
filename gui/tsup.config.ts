import {defineConfig} from 'tsup'

// Cloud client ids come from the build configuration (public values, not secrets; dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 3).
// A variable that is set when the build runs is written into the bundle; one that is not stays a runtime lookup, so a development
// run can still set it in its environment. The Google desktop client secret is not confidential (spec 7.2): PKCE protects the sign-in.
const cloudDefines: Record<string, string> = {}
for (const name of ['KEEPFIN_GOOGLE_CLIENT_ID', 'KEEPFIN_GOOGLE_CLIENT_SECRET', 'KEEPFIN_MS_CLIENT_ID', 'KEEPFIN_DROPBOX_APP_KEY']) {
  const value = process.env[name]?.trim()
  if (value) cloudDefines[`process.env.${name}`] = JSON.stringify(value)
}

export default defineConfig([
  {
    // The library store runs in its own thread (gui/desktop/store/worker.ts): it is built beside main.js, where StoreClient looks for it.
    // The copies run in their own thread too (gui/desktop/copiesWorker.ts), also looked up beside main.js.
    entry: {main: 'gui/desktop/main.ts', worker: 'gui/desktop/store/worker.ts', copiesWorker: 'gui/desktop/copiesWorker.ts'},
    outDir: 'build/desktop',
    format: 'esm',
    target: 'node22',
    // tsup strips the node: prefix by default, which breaks prefix-only modules such as node:sqlite.
    removeNodeProtocol: false,
    external: [
      'electron',
      'ffmpeg-static',
      'electron-updater',
      'builder-util-runtime',
      'music-metadata',
    ],
    // Both independent entries share this directory; cleaning here can race preload output.
    clean: false,
    define: cloudDefines,
  },
  {
    entry: {preload: 'gui/desktop/preload.cts'},
    outDir: 'build/desktop',
    format: 'cjs',
    target: 'node22',
    external: ['electron'],
    outExtension: () => ({js: '.cjs'}),
  },
])
