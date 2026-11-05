// Screenshots for the 0.5.5-beta.2 desktop fixes (fixture data): node scripts/capture/capture-beta2.mjs <outDir> [name-substring]
// SCALES=80,100,140 repeats each screen at those interface scales.
import fs from 'node:fs'
import {startServer, launch, openApp} from '../test/ui-fixtures/harness.mjs'

const out = process.argv[2]
const only = process.argv[3]
fs.mkdirSync(out, {recursive: true})
const srv = await startServer()
const browser = await launch()
const nav = (page, name) => page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name}).click()
const chip = (page, name) => page.getByRole('group', {name: 'Show in Library'}).getByRole('button', {name, exact: true}).click()
const play = async (page) => {
  await nav(page, 'Library')
  await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('button', {name: /^Music/}).click()
  await page.getByRole('button', {name: /^Glass Harbour/}).click()
  await page.getByRole('region', {name: 'Now playing'}).waitFor()
}
const openPanel = async (page) => {
  await play(page)
  await page.getByRole('region', {name: 'Now playing'}).getByRole('button', {name: 'Queue and Now playing'}).click()
  await page.getByRole('complementary', {name: 'Now playing panel'}).waitFor()
}
const finished = async (page) => {
  await nav(page, 'Downloads')
  await page.getByRole('radiogroup', {name: 'Show downloads'}).getByRole('radio', {name: /^Finished/}).click()
  await page.getByRole('list', {name: 'Download history'}).waitFor()
}
const V2 = {libraryScenario: 'v2'}
const screens = [
  {name: 'sidebar', setup: (page) => nav(page, 'Library')},
  {name: 'sidebar-collapsed', setup: async (page) => (await nav(page, 'Library'), await page.getByRole('button', {name: /Collapse sidebar/}).click())},
  {name: 'library-all', fake: V2, setup: (page) => nav(page, 'Library')},
  {name: 'library-playlists', fake: V2, setup: async (page) => (await nav(page, 'Library'), await chip(page, 'Playlists'))},
  {name: 'library-collections', fake: V2, setup: async (page) => (await nav(page, 'Library'), await chip(page, 'Collections'))},
  {name: 'library-albums', fake: V2, setup: async (page) => (await nav(page, 'Library'), await chip(page, 'Albums'))},
  {name: 'library-spaces', fake: V2, setup: async (page) => (await nav(page, 'Library'), await chip(page, 'Spaces'))},
  {name: 'library-collection-detail', fake: V2, setup: async (page) => (await nav(page, 'Library'), await chip(page, 'Collections'), await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).click(), await page.getByRole('button', {name: /Back to Library/}).waitFor())},
  {name: 'downloads', setup: (page) => nav(page, 'Downloads')},
  {name: 'downloads-empty', fake: {scenario: 'empty', simulate: false}, setup: (page) => nav(page, 'Downloads')},
  {name: 'downloader-only', fake: {library: 'off'}, setup: (page) => nav(page, 'Downloads')},
  {name: 'history-list', fake: {scenario: 'groups'}, setup: finished},
  {name: 'history-grid', fake: {scenario: 'groups'}, setup: async (page) => (await finished(page), await page.getByTitle('Grid view').click(), await page.locator('.lib-grid').waitFor())},
  {name: 'panel', setup: openPanel},
  {name: 'panel-wide', setup: async (page) => (await openPanel(page), await page.getByRole('separator', {name: /Resize/}).focus(), await page.keyboard.press('Shift+ArrowLeft'), await page.keyboard.press('Shift+ArrowLeft'), await page.keyboard.press('Shift+ArrowLeft'), await page.keyboard.press('Shift+ArrowLeft'))},
  {name: 'panel-narrow', setup: async (page) => (await openPanel(page), await page.getByRole('separator', {name: /Resize/}).focus(), await page.keyboard.press('Home'))},
  {name: 'lyrics-icon', setup: play},
]
const sizes = [
  {w: 1440, h: 900, tag: '1440'},
  {w: 1100, h: 800, tag: '1100'},
]
const scales = (process.env.SCALES || '100').split(',').map(Number)
const clipOf = (name, size) => {
  if (name.startsWith('sidebar')) return {x: 0, y: 0, width: 260, height: size.h}
  if (name === 'lyrics-icon') return {x: 0, y: size.h - 110, width: size.w, height: 110}
  return undefined
}
for (const theme of ['dark', 'light'])
  for (const size of sizes)
    for (const scale of scales)
      for (const screen of screens) {
        if (only && !screen.name.includes(only)) continue
        if (size.tag === '1100' && !['library-all', 'downloads', 'sidebar', 'sidebar-collapsed', 'history-grid'].includes(screen.name)) continue
        const page = await openApp(browser, srv.url, {width: size.w, height: size.h, theme, scale, fake: {simulate: false, ...(screen.fake || {})}})
        try {
          await screen.setup(page)
          await page.waitForTimeout(600)
          await page.screenshot({path: `${out}/${screen.name}-${theme}-${size.tag}${scale === 100 ? '' : `-s${scale}`}.png`, clip: clipOf(screen.name, size)})
        } catch (error) {
          console.log(`FAIL ${screen.name} ${theme} ${size.tag} s${scale}: ${String(error.message).split('\n')[0]}`)
        } finally {
          await page.context().close()
        }
      }
await browser.close()
await srv.close()
