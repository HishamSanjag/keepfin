import path from 'node:path'
import {defineConfig} from 'vite'
export default defineConfig({
  root: 'gui/web',
  base: './',
  build: {outDir: '../../build/web', emptyOutDir: true},
  // content/guide.json is the single source of the Features & how-to guide and lives outside the renderer root.
  server: {host: '127.0.0.1', fs: {allow: [path.resolve('.')]}},
})
