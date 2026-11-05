// Screenshots of the 0.5.5 screens (fixture data) in light and dark: node scripts/capture/capture-refresh.mjs <outDir>
import fs from 'node:fs'
import {startServer, launch, openApp} from '../test/ui-fixtures/harness.mjs'

const out = process.argv[2]
fs.mkdirSync(out, {recursive: true})
const srv = await startServer()
const browser = await launch()
const nav = (page, name) => page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name}).click()
const box = (page) => page.getByRole('textbox').first()
const play = async (page) => {
  await nav(page, 'Library')
  await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('button', {name: /^Music/}).click()
  await page.getByRole('button', {name: /^Glass Harbour/}).click()
  await page.getByRole('region', {name: 'Now playing'}).waitFor()
}
const screens = [
  {name: 'home', setup: (page) => nav(page, 'Home').then(() => page.getByRole('region', {name: 'Continue watching'}).waitFor())},
  {name: 'home-empty', fake: {libraryScenario: 'empty', simulate: false}, setup: (page) => nav(page, 'Home')},
  {name: 'search', setup: async (page) => (await nav(page, 'Search'), await box(page).fill('harbo'), await page.getByRole('region', {name: 'On YouTube'}).waitFor())},
  {name: 'library', setup: (page) => nav(page, 'Library')},
  {name: 'album', setup: async (page) => (await nav(page, 'Search'), await box(page).fill('weather systems'), await page.getByRole('button', {name: /^Weather Systems/}).first().click(), await page.getByRole('heading', {name: 'Weather Systems'}).waitFor())},
  {name: 'artist', setup: async (page) => (await nav(page, 'Search'), await box(page).fill('mira'), await page.getByRole('region', {name: 'Artists'}).getByRole('button', {name: /^Mira Halden/}).click(), await page.getByRole('heading', {name: 'Mira Halden'}).waitFor())},
  {name: 'playlist', fake: {libraryScenario: 'v2'}, setup: async (page) => (await nav(page, 'Library'), await page.getByRole('group', {name: 'Show in Library'}).getByRole('button', {name: 'Collections', exact: true}).click(), await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).click(), await page.getByRole('button', {name: 'Shuffle'}).waitFor())},
  {name: 'now-playing-panel', setup: async (page) => (await play(page), await page.getByRole('region', {name: 'Now playing'}).getByRole('button', {name: 'Queue and Now playing'}).click(), await page.getByRole('complementary', {name: 'Now playing panel'}).waitFor())},
  {name: 'lyrics', setup: async (page) => (await play(page), await page.getByRole('region', {name: 'Now playing'}).getByRole('button', {name: 'Lyrics'}).click(), await page.getByRole('region', {name: 'Lyrics', exact: true}).waitFor())},
  {name: 'action-menu', setup: async (page) => (await nav(page, 'Search'), await box(page).fill('cold front'), await page.getByRole('button', {name: /^Cold Front/}).first().click({button: 'right'}), await page.getByRole('menu', {name: 'Item actions'}).waitFor())},
  {name: 'start-mode', fake: {storagePhase: 'firstStart'}, setup: async (page) => (await page.getByRole('button', {name: 'Skip intro'}).click(), await page.getByRole('heading', {name: 'How will you use Keepfin?'}).waitFor())},
  {name: 'downloader-only', fake: {library: 'off'}, setup: (page) => nav(page, 'Downloads')},
  {name: 'settings-mode', setup: async (page) => (await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click(), await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Library & playback'}).click(), await page.getByRole('radiogroup', {name: 'Keepfin mode'}).first().waitFor())},
]
const sizes = [{w: 1440, h: 900, tag: '1440'}, {w: 1100, h: 800, tag: '1100'}]
for (const theme of ['dark', 'light'])
  for (const size of sizes)
    for (const screen of screens) {
      if (size.tag === '1100' && !['home', 'album', 'now-playing-panel'].includes(screen.name)) continue
      const page = await openApp(browser, srv.url, {width: size.w, height: size.h, theme, intro: !!screen.intro, fake: {simulate: false, ...(screen.fake || {})}})
      try {
        await screen.setup(page)
        await page.waitForTimeout(700)
        await page.screenshot({path: `${out}/${screen.name}-${theme}-${size.tag}.png`})
      } catch (error) {
        console.log(`FAIL ${screen.name} ${theme} ${size.tag}: ${String(error.message).split('\n')[0]}`)
        await page.screenshot({path: `${out}/FAIL-${screen.name}-${theme}-${size.tag}.png`}).catch(() => {})
      } finally {
        await page.context().close()
      }
    }
await browser.close()
await srv.close()
