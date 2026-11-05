// Writes the 1600x1000 hero screenshots used by the README: a 4K video review open above
// an active queue. Usage: HERO_OUT=<dir> node scripts/capture/capture-hero.mjs
import fs from 'node:fs'
import {startServer, launch, openApp} from '../test/ui-fixtures/harness.mjs'

const out = process.env.HERO_OUT || 'docs/screenshots'
fs.mkdirSync(out, {recursive: true})
const srv = await startServer()
const browser = await launch()
for (const theme of ['dark', 'light']) {
  const page = await openApp(browser, srv.url, {width: 1600, height: 1000, scale: 80, theme, fake: {simulate: true}})
  await page.locator('textarea').first().fill('https://www.youtube.com/watch?v=lofoten8k')
  await page.getByRole('radiogroup', {name: 'Video quality'}).waitFor()
  // Keep the format list short so the active queue shows beneath the review.
  await page.getByRole('radiogroup', {name: 'Video quality'}).evaluate((el) => {
    el.style.maxHeight = '176px'
    el.style.overflowY = 'auto'
  })
  await page.waitForTimeout(1200)
  await page.screenshot({path: `${out}/desktop-hero-${theme}.png`})
  await page.context().close()
}
await browser.close()
await srv.close()
console.log('hero screenshots written to', out)
