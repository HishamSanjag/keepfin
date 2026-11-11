// Shared browser harness for renderer smoke tests: serves gui/web with Vite and opens pages
// in Chromium with the synthetic desktop bridge from fake-bridge.js injected before load.
import {chromium} from '@playwright/test'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {createServer, preview, build} from 'vite'
import {ensureMedia, mediaPlugin} from './media.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
export const root = path.resolve(here, '../../..')
export const bridgePath = path.join(here, 'fake-bridge.js')

/** Starts the renderer. Uses the production build when KEEPFIN_UI_BUILD=1, otherwise the dev server. */
export async function startServer() {
  ensureMedia()
  const configFile = path.join(root, 'gui/vite.config.ts')
  if (process.env.KEEPFIN_UI_BUILD === '1') {
    await build({configFile, root: path.join(root, 'gui/web'), logLevel: 'warn'})
    const server = await preview({configFile, root: path.join(root, 'gui/web'), preview: {port: 0, host: '127.0.0.1'}, plugins: [mediaPlugin()], logLevel: 'warn'})
    const url = server.resolvedUrls.local[0]
    return {url, close: () => new Promise((resolve) => server.httpServer.close(resolve))}
  }
  const server = await createServer({configFile, root: path.join(root, 'gui/web'), server: {port: 0, host: '127.0.0.1'}, plugins: [mediaPlugin()], optimizeDeps: {include: ['media-chrome/react', '@tanstack/react-virtual']}, logLevel: 'warn'})
  await server.listen()
  const url = server.resolvedUrls.local[0]
  return {url, close: () => server.close()}
}

export async function launch() {
  // The fixture videos and songs really play; keep them off the speakers (the page still sees its own volume and mute state).
  return chromium.launch({args: ['--mute-audio']})
}

/**
 * Opens the app. `fake` options go to the synthetic bridge (scenario, simulate, latency, engine);
 * pass fake:false for the honest browser preview with no bridge.
 */
export async function openApp(browser, url, {fake = {}, width = 1280, height = 820, theme = 'dark', scale = 100, reducedMotion = false, intro = false, deviceScaleFactor = 1} = {}) {
  const context = await browser.newContext({
    viewport: {width, height},
    deviceScaleFactor,
    reducedMotion: reducedMotion ? 'reduce' : 'no-preference',
    colorScheme: theme === 'light' ? 'light' : 'dark',
  })
  await context.addInitScript(
    ({theme, scale, intro}) => {
      try {
        localStorage.setItem('keepfin-theme', theme)
        localStorage.setItem('keepfin-scale', String(scale))

      } catch {}
    },
    {theme, scale, intro},
  )
  if (fake) {
    await context.addInitScript(
      ({options, base}) => {
        window.__FAKE_OPTIONS = options
        window.__KEEPFIN_MEDIA_BASE = base
      },
      {options: fake, base: new URL(url).origin + '/__media'},
    )
    await context.addInitScript({path: bridgePath})
  }
  const page = await context.newPage()
  page.setDefaultTimeout(8000)
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error' && !/favicon|Download the React DevTools/.test(message.text())) errors.push(message.text())
  })
  await page.goto(url)
  // The first load after the preview server starts can be slow on a cold machine; report page errors if it never renders.
  // The app shell, or first start / the setup window, which come before it (gui/web/setup).
  await page
    .locator('nav[aria-label="Main navigation"], .setup-host:not([aria-busy])')
    .first()
    .waitFor({timeout: 30_000})
    .catch(async (error) => {
      await context.close().catch(() => {})
      throw new Error(`App shell did not render: ${error.message}\nPage errors: ${errors.join(' | ') || 'none'}`)
    })
  page.errors = errors
  return page
}

export async function noHorizontalOverflow(page) {
  return page.evaluate(() => {
    const doc = document.documentElement
    const main = document.querySelector('.main')
    const offenders = []
    const limit = doc.clientWidth
    for (const element of document.querySelectorAll('.main *')) {
      const rect = element.getBoundingClientRect()
      if (rect.width && rect.right > limit + 1 && getComputedStyle(element).position !== 'fixed')
        offenders.push(`${element.tagName.toLowerCase()}.${element.className}`)
    }
    return {
      ok: doc.scrollWidth <= doc.clientWidth + 1 && (!main || main.scrollWidth <= main.clientWidth + 1),
      doc: [doc.scrollWidth, doc.clientWidth],
      main: main ? [main.scrollWidth, main.clientWidth] : null,
      offenders: offenders.slice(0, 8),
    }
  })
}
