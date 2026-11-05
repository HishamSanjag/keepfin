// The owner's desktop test list (player, lyrics, mini player, cards, sidebar), against the synthetic bridge: the active lyric line
// stands out in both themes, the lyrics header stays while the lyrics scroll, navigating puts the player away, the whole mini bar
// opens the player and has the row menu, scrolling down collapses the full player without fighting its lists, the card heart and
// menu are chips on the artwork corner, and the collapsed sidebar opens from its logo with an animated width and fading labels.
// Registered by smoke-ui-flows.mjs. Set UI_FIXES_SHOTS to a folder to also save screenshots. Fixtures never certify playback.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const SHOTS = process.env.UI_FIXES_SHOTS
if (SHOTS) fs.mkdirSync(SHOTS, {recursive: true})

const manyLines = Array.from({length: 48}, (_, index) => ({time: 0.4 + index * 1.5, text: `Line ${index + 1} of a long song, sung slowly`}))

export async function registerUiFixesSuite({test, sent}) {
  const mainNav = (page) => page.getByRole('navigation', {name: 'Main navigation'})
  const side = (page) => page.getByRole('navigation', {name: 'Your library'})
  const bar = (page) => page.getByRole('region', {name: 'Now playing'})
  const shot = async (page, name) => {
    if (SHOTS) await page.screenshot({path: path.join(SHOTS, `${name}.png`)})
  }
  const toLibrary = async (page) => {
    await mainNav(page).getByRole('button', {name: 'Library'}).click()
    await page.getByRole('region', {name: 'Spaces', exact: true}).waitFor()
  }
  const playGlassHarbour = async (page) => {
    await toLibrary(page)
    await side(page).getByRole('list', {name: 'Spaces'}).getByRole('button', {name: /^Music/}).click()
    await page.getByRole('button', {name: /^Glass Harbour/}).first().click()
    await bar(page).waitFor()
  }

  await test('player: the equalizer and bass boost (as on the phone) are in the options menu, remembered, and the music keeps playing', async (page) => {
    // Chromium warns when Web Audio may not read a media element ("outputs zeroes due to CORS"): the equalizer would make it silent
    const warnings = []
    page.on('console', (message) => /CORS|outputs zeroes/i.test(message.text()) && warnings.push(message.text()))
    await playGlassHarbour(page)
    await page.waitForFunction(() => window.__keepfinTest.audio().active.currentTime > 0.2)
    // CORS mode from the start, so the equalizer can shape the sound
    assert.equal(await page.evaluate(() => window.__keepfinTest.audio().active.crossOrigin), 'anonymous')
    // its own button on the bar
    await bar(page).getByRole('button', {name: /^More actions for/}).click()
    await page.getByRole('menuitem', {name: 'Equalizer', exact: true}).click()
    const dialog = page.getByRole('dialog', {name: 'Equalizer'})
    await dialog.waitFor()
    assert.equal(await dialog.getByRole('switch', {name: 'Equalizer'}).getAttribute('aria-checked'), 'false')
    await dialog.getByRole('combobox', {name: 'Equalizer preset'}).selectOption('Rock')
    assert.equal(await dialog.getByRole('switch', {name: 'Equalizer'}).getAttribute('aria-checked'), 'true', 'a preset turns it on')
    assert.equal(await dialog.getByRole('slider', {name: '60 Hz band'}).inputValue(), '5')
    await dialog.getByRole('slider', {name: 'Bass boost'}).fill('40')
    const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('keepfin-equalizer')))
    assert.deepEqual([saved.enabled, saved.preset, saved.levels, saved.bass], [true, 'Rock', [5, 3, -1, 3, 5], 40])
    // a band moved by hand makes it Custom
    await dialog.getByRole('slider', {name: '910 Hz band'}).fill('4')
    assert.equal(await dialog.getByRole('combobox', {name: 'Equalizer preset'}).inputValue(), '')
    await dialog.getByRole('button', {name: 'Done'}).click()
    await bar(page).getByRole('button', {name: /^More actions for/}).click()
    const button = page.getByRole('menuitem', {name: 'Equalizer (Custom, bass +40 %)'})
    await button.waitFor() // the menu line says what the equalizer is doing
    await page.keyboard.press('Escape')
    // in the full player, scrolling inside the dialog never puts the player away
    await bar(page).getByRole('button', {name: /Open the player/}).click()
    const full = page.getByRole('dialog', {name: 'Now playing'})
    await full.waitFor()
    await full.getByRole('button', {name: /^Equalizer/}).click()
    await dialog.waitFor()
    await dialog.hover()
    await page.mouse.wheel(0, 900)
    await page.waitForTimeout(500)
    // by its element: the open dialog hides the rest of the page from the accessibility tree
    assert.equal(await page.locator('.full-player').isVisible(), true, 'the full player stays open')
    await dialog.getByRole('button', {name: 'Done'}).click()
    await page.keyboard.press('Escape')
    // still playing through the new route
    const before = await page.evaluate(() => window.__keepfinTest.audio().active.currentTime)
    await page.waitForFunction((t) => window.__keepfinTest.audio().active.currentTime > t + 0.2, before)
    assert.deepEqual(warnings, [], 'the equalizer may read the audio (no CORS silence)')
  })
  await test('player: a notice sits above the music bar, never over its controls', async (page) => {
    await playGlassHarbour(page)
    await bar(page).getByRole('button', {name: /favorites/i}).first().click()
    const toast = page.locator('.toast').first()
    await toast.waitFor()
    const [t, b] = [await toast.boundingBox(), await page.locator('.now-bar').boundingBox()]
    assert.ok(t.y + t.height <= b.y, `the notice ends at ${t.y + t.height}, the bar starts at ${b.y}`)
  })
  const openLyrics = async (page) => {
    await bar(page).getByRole('button', {name: 'Lyrics', exact: true}).click()
    await page.locator('.lyrics-overlay .lyric').first().waitFor()
  }
  for (const theme of ['dark', 'light']) {
    await test(`ui fixes (${theme}): the line being sung is full contrast, heavier and larger; the others are dimmed but readable`, async (page) => {
      await playGlassHarbour(page)
      await openLyrics(page)
      await page.waitForFunction(() => window.__keepfinTest.audio().active.currentTime > 0.6)
      // Wait until the highlighted line has finished easing into its larger size (it changes about every second).
      await page.waitForFunction(() => {
        const node = document.querySelector('.lyrics-overlay .lyric.active')
        return node && new DOMMatrixReadOnly(getComputedStyle(node).transform).a > 1.065
      })
      const info = await page.evaluate(() => {
        const ctx = document.createElement('canvas').getContext('2d', {willReadFrequently: true})
        const rgba = (color) => {
          ctx.clearRect(0, 0, 1, 1)
          ctx.fillStyle = '#000'
          ctx.fillStyle = color
          ctx.fillRect(0, 0, 1, 1)
          const [red, green, blue, alpha] = ctx.getImageData(0, 0, 1, 1).data
          return [red, green, blue, alpha / 255]
        }
        const over = (fg, bg) => [0, 1, 2].map((i) => fg[i] * fg[3] + bg[i] * (1 - fg[3]))
        const lum = ([red, green, blue]) => {
          const f = (v) => ((v /= 255) <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)
          return 0.2126 * f(red) + 0.7152 * f(green) + 0.0722 * f(blue)
        }
        const ratio = (a, b) => {
          const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x)
          return (hi + 0.05) / (lo + 0.05)
        }
        const canvas = rgba(getComputedStyle(document.documentElement).getPropertyValue('--canvas').trim())
        const active = document.querySelector('.lyrics-overlay .lyric.active')
        const others = [...document.querySelectorAll('.lyrics-overlay .lyric:not(.plain):not(.active)')]
        const measure = (node) => {
          const style = getComputedStyle(node)
          return {contrast: ratio(over(rgba(style.color), canvas), canvas), weight: Number(style.fontWeight), scale: new DOMMatrixReadOnly(style.transform).a, opacity: Number(style.opacity)}
        }
        return {active: measure(active), others: others.map(measure)}
      })
      assert.ok(info.active.contrast >= 7, `active line contrast ${info.active.contrast}`)
      assert.ok(info.active.weight >= 700, `active weight ${info.active.weight}`)
      assert.ok(info.active.scale > 1.04, `active scale ${info.active.scale}`)
      assert.ok(info.others.length >= 2)
      for (const other of info.others) {
        assert.ok(other.contrast >= 4.5, `other lines stay AA readable, got ${other.contrast}`)
        assert.ok(other.contrast < info.active.contrast - 1, `other lines are dimmer (${other.contrast} vs ${info.active.contrast})`)
        assert.ok(other.weight <= 650 && other.scale <= 1.001, 'other lines are lighter and not enlarged')
        assert.equal(other.opacity, 1, 'dimmed by colour, never by opacity')
      }
      await shot(page, `lyrics-active-${theme}`)
    }, {theme})
  }

  await test('ui fixes: lyrics respect reduced motion (the highlight changes without a transition)', async (page) => {
    await playGlassHarbour(page)
    await openLyrics(page)
    const durations = await page.locator('.lyrics-overlay .lyric').first().evaluate((node) => getComputedStyle(node).transitionDuration)
    assert.ok(durations.split(',').every((value) => parseFloat(value) < 0.01), `no lyric transition, got ${durations}`)
  }, {reducedMotion: true})

  await test('ui fixes: the lyrics header stays put while the lyrics scroll', async (page) => {
    await playGlassHarbour(page)
    await openLyrics(page)
    const header = page.locator('.lyrics-overlay .np-head')
    const close = page.getByRole('button', {name: 'Close lyrics'})
    const before = await header.boundingBox()
    await page.locator('.lyrics-overlay .lyrics').hover()
    await page.mouse.wheel(0, 700)
    await page.waitForFunction(() => document.querySelector('.lyrics-overlay .lyrics').scrollTop > 200)
    const after = await header.boundingBox()
    assert.equal(Math.round(after.y), Math.round(before.y), 'the header did not move')
    assert.ok(await close.isVisible())
    const viewport = page.viewportSize()
    const closeBox = await close.boundingBox()
    assert.ok(closeBox.y >= 0 && closeBox.y + closeBox.height <= viewport.height, 'the close button is on screen')
    const scrollers = await page.evaluate(() => ({body: document.querySelector('.lyrics-overlay-body').scrollTop, overlay: document.querySelector('.lyrics-overlay').scrollTop, lyrics: document.querySelector('.lyrics-overlay .lyrics').scrollTop}))
    assert.equal(scrollers.body, 0)
    assert.equal(scrollers.overlay, 0)
    assert.ok(scrollers.lyrics > 200, 'only the lyrics scrolled')
    await shot(page, 'lyrics-scrolled-header-visible')
    await close.click()
    await page.locator('.lyrics-overlay').waitFor({state: 'detached'})
  }, {fake: {lyricLines: manyLines}})

  await test('ui fixes: going to another page puts the lyrics and the full player away', async (page) => {
    await playGlassHarbour(page)
    await openLyrics(page)
    await mainNav(page).getByRole('button', {name: 'Downloads'}).click()
    await page.locator('.lyrics-overlay').waitFor({state: 'detached'})
    await page.getByRole('heading', {name: 'Downloads', level: 1}).waitFor()
    // Home, Search, Settings, each from the lyrics view.
    for (const name of ['Home', 'Search', 'Library']) {
      await openLyrics(page)
      await mainNav(page).getByRole('button', {name}).click()
      await page.locator('.lyrics-overlay').waitFor({state: 'detached'})
    }
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click().catch(() => {})
    // A space chosen in the sidebar while the Library is already open is a navigation too.
    await toLibrary(page)
    await openLyrics(page)
    await side(page).getByRole('list', {name: 'Spaces'}).getByRole('button', {name: /^Videos/}).click()
    await page.locator('.lyrics-overlay').waitFor({state: 'detached'})
    // The expanded player.
    await bar(page).getByRole('button', {name: /Open the player/}).click()
    await page.getByRole('dialog', {name: 'Now playing'}).waitFor()
    // The full player covers the sidebar, so the way off it is a shortcut: Ctrl+L goes to Downloads.
    await page.keyboard.press('Control+l')
    await page.getByRole('dialog', {name: 'Now playing'}).waitFor({state: 'detached'})
    await bar(page).waitFor()
  })

  await test('ui fixes: the whole mini bar opens the player, the controls do not, and the menu is the row menu', async (page) => {
    await playGlassHarbour(page)
    const full = page.getByRole('dialog', {name: 'Now playing'})
    const box = await page.locator('.now-bar').boundingBox()
    const info = await page.locator('.now-info').boundingBox()
    // The song group is only as wide as the song (the heart follows it, dev-docs/plans/FULL-PLAYER.md "The mini bar").
    // The far end of the title area, the empty bar beside the heart, then the bar's own padding.
    await page.mouse.click(info.x + info.width - 8, info.y + info.height / 2)
    await full.waitFor()
    await full.getByRole('button', {name: 'Collapse player'}).click()
    await full.waitFor({state: 'detached'})
    await page.waitForTimeout(700) // the page cannot be clicked while the close transition runs
    const heart = await bar(page).getByRole('button', {name: /favorites$/}).boundingBox()
    await page.mouse.click(heart.x + heart.width + 24, heart.y + heart.height / 2)
    await full.waitFor()
    await full.getByRole('button', {name: 'Collapse player'}).click()
    await full.waitFor({state: 'detached'})
    await page.waitForTimeout(700)
    await page.mouse.click(box.x + 6, box.y + 4)
    await full.waitFor()
    await full.getByRole('button', {name: 'Collapse player'}).click()
    await full.waitFor({state: 'detached'})
    // Controls keep their own job.
    await bar(page).getByRole('button', {name: 'Next song'}).click()
    await bar(page).getByRole('slider', {name: 'Seek'}).click()
    await bar(page).getByRole('button', {name: 'Shuffle off'}).click()
    await bar(page).getByRole('slider', {name: 'Volume'}).click()
    await page.waitForTimeout(400)
    assert.equal(await full.count(), 0, 'no control opened the player')
    // The menu has the actions a song row has, in the same words.
    await bar(page).getByRole('button', {name: /^More actions for /}).click()
    const menu = page.getByRole('menu', {name: /^More actions for /})
    await menu.waitFor()
    assert.equal(await full.count(), 0, 'opening the menu does not open the player')
    const names = (await menu.getByRole('menuitem').allTextContents()).map((text) => text.replace(/\s+/g, ' ').trim())
    for (const want of ['Play', 'Play next', 'Add to queue', 'Add to playlist', 'Go to album', 'Go to artist', 'Move to…', 'Copy to…', 'Show in folder'])
      assert.ok(names.some((name) => name.startsWith(want)), `${want} is in the menu, got ${names.join(' | ')}`)
    await shot(page, 'mini-player-menu')
    await menu.getByRole('menuitem', {name: 'Show in folder'}).click()
    await menu.waitFor({state: 'detached'})
    // And an action that opens a dialog.
    await bar(page).getByRole('button', {name: /^More actions for /}).click()
    await page.getByRole('menuitem', {name: 'Add to playlist'}).click()
    await page.getByRole('dialog').first().waitFor()
  })

  await test('ui fixes: scrolling down collapses the full player, but not while a list is scrolling or the volume is moving', async (page) => {
    await playGlassHarbour(page)
    const full = page.getByRole('dialog', {name: 'Now playing'})
    const open = async () => {
      await bar(page).getByRole('button', {name: /Open the player/}).click()
      await full.waitFor()
    }
    await open()
    // Over the lyrics list the wheel scrolls the list.
    await page.locator('.full-panel .lyrics').hover()
    await page.mouse.wheel(0, 400)
    await page.mouse.wheel(0, 400)
    await page.waitForTimeout(500)
    assert.equal(await full.count(), 1, 'the lyrics list keeps its wheel')
    assert.ok(await page.locator('.full-panel .lyrics').evaluate((node) => node.scrollTop > 0), 'and scrolled')
    // Over the volume the wheel changes the volume.
    await full.getByRole('slider', {name: 'Volume'}).hover()
    await page.mouse.wheel(0, 300)
    await page.waitForTimeout(400)
    assert.equal(await full.count(), 1, 'the volume keeps its wheel')
    // Over the cover a scroll down puts the player away.
    await page.locator('.full-art').hover()
    await page.mouse.wheel(0, 80)
    await page.mouse.wheel(0, 80)
    await full.waitFor({state: 'detached'})
    // Scrolling up does not.
    await open()
    await page.locator('.full-art').hover()
    await page.mouse.wheel(0, -200)
    await page.waitForTimeout(500)
    assert.equal(await full.count(), 1)
    // Dragging the top area down does it too.
    const head = await page.locator('.full-head').boundingBox()
    const x = head.x + head.width / 2
    await page.mouse.move(x, head.y + 10)
    await page.mouse.down()
    await page.mouse.move(x, head.y + 60, {steps: 4})
    await page.mouse.move(x, head.y + 140, {steps: 4})
    await page.mouse.up()
    await full.waitFor({state: 'detached'})
  }, {fake: {lyricLines: manyLines}})

  // ---------------------------------------------------------------- cards
  const cardChecks = async (page, label) => {
    const cards = page.locator('.card:not(.is-gone)')
    const total = await cards.count()
    assert.ok(total > 0, `${label}: there are cards`)
    let checked = 0
    for (let index = 0; index < Math.min(total, 14); index++) {
      const card = cards.nth(index)
      if (!(await card.locator('.card-actions .icon-btn').count())) continue
      await card.scrollIntoViewIfNeeded()
      await card.hover()
      await page.waitForTimeout(220)
      const geo = await card.evaluate((node) => {
        const box = (element) => {
          const rect = element.getBoundingClientRect()
          return {left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom}
        }
        const art = box(node.querySelector('.card-art'))
        const text = [...node.querySelectorAll('.card-title, .card-sub')].map(box)
        const chips = [...node.querySelectorAll('.card-actions .icon-btn')].map((chip) => ({...box(chip), opacity: Number(getComputedStyle(chip).opacity), radius: getComputedStyle(chip).borderRadius, bg: getComputedStyle(chip).backgroundColor, fg: getComputedStyle(chip).color}))
        return {art, text, chips}
      })
      assert.ok(geo.chips.length > 0)
      for (const chip of geo.chips) {
        assert.equal(chip.opacity, 1, `${label}: the chips show on hover`)
        assert.ok(chip.right - chip.left >= 27 && chip.bottom - chip.top >= 27, `${label}: chips are at least 28px`)
        const inside = chip.left >= geo.art.left - 1 && chip.right <= geo.art.right + 1 && chip.top >= geo.art.top - 1 && chip.bottom <= geo.art.bottom + 1
        assert.ok(inside, `${label}: chip sits on the artwork, chip ${JSON.stringify(chip)} art ${JSON.stringify(geo.art)}`)
        for (const text of geo.text) {
          const overlaps = chip.left < text.right && chip.right > text.left && chip.top < text.bottom && chip.bottom > text.top
          assert.equal(overlaps, false, `${label}: a chip never covers the title or subtitle`)
        }
      }
      checked++
    }
    assert.ok(checked > 0, `${label}: at least one card has chips`)
  }
  const chipContrast = (page) =>
    page.evaluate(() => {
      const ctx = document.createElement('canvas').getContext('2d', {willReadFrequently: true})
      const rgba = (color) => {
        ctx.clearRect(0, 0, 1, 1)
        ctx.fillStyle = '#000'
        ctx.fillStyle = color
        ctx.fillRect(0, 0, 1, 1)
        const [red, green, blue, alpha] = ctx.getImageData(0, 0, 1, 1).data
        return [red, green, blue, alpha / 255]
      }
      const lum = ([red, green, blue]) => {
        const f = (v) => ((v /= 255) <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)
        return 0.2126 * f(red) + 0.7152 * f(green) + 0.0722 * f(blue)
      }
      const ratio = (a, b) => {
        const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x)
        return (hi + 0.05) / (lo + 0.05)
      }
      const worst = []
      for (const chip of document.querySelectorAll('.card:hover .card-actions .icon-btn')) {
        const style = getComputedStyle(chip)
        const bg = rgba(style.backgroundColor)
        const fg = rgba(style.color)
        // The chip is semi-opaque: measure on the worst backdrops, a white and a black picture.
        for (const backdrop of [[255, 255, 255], [0, 0, 0]]) {
          const surface = [0, 1, 2].map((i) => bg[i] * bg[3] + backdrop[i] * (1 - bg[3]))
          worst.push(ratio(fg, surface))
        }
      }
      return Math.min(...worst)
    })

  for (const theme of ['dark', 'light']) {
    await test(`ui fixes (${theme}): card heart and menu are chips on the artwork corner, readable and clear of the text`, async (page) => {
      await toLibrary(page)
      // Home shelves (video and square cards).
      await mainNav(page).getByRole('button', {name: 'Home'}).click()
      await page.locator('.shelf .card').first().waitFor()
      await cardChecks(page, 'home shelves')
      await page.locator('.shelf .card').first().hover()
      await page.waitForTimeout(220)
      assert.ok((await chipContrast(page)) >= 3, 'chip icon contrast is at least 3:1 on any picture')
      await shot(page, `home-card-hover-${theme}`)
      // Library home shelves and the spaces' grids.
      await toLibrary(page)
      await cardChecks(page, 'library home')
      await side(page).getByRole('list', {name: 'Spaces'}).getByRole('button', {name: /^Videos/}).click()
      await page.getByText(/\d+ items? ·/).first().waitFor()
      const toolbar = page.getByRole('toolbar', {name: 'Library toolbar'})
      const grid = toolbar.getByRole('radio', {name: 'Grid view'})
      if (await grid.count()) await grid.click()
      await page.locator('.card').first().waitFor()
      await cardChecks(page, 'video grid')
      await side(page).getByRole('list', {name: 'Spaces'}).getByRole('button', {name: /^Music/}).click()
      await page.locator('.card, .song-row').first().waitFor()
      if (await page.locator('.card').count()) await cardChecks(page, 'music grid')
      await shot(page, `library-card-hover-${theme}`)
    }, {theme})
  }

  await test('ui fixes: albums, artists and playlists (the open cards) have the same chips', async (page) => {
    await toLibrary(page)
    const group = page.getByRole('group', {name: 'Show in Library'})
    let seen = 0
    for (const name of ['Playlists', 'Albums', 'Artists', 'Collections']) {
      const filter = group.getByRole('button', {name, exact: true})
      if (!(await filter.count())) continue
      await filter.click()
      await page.waitForTimeout(250)
      if (!(await page.locator('.card .card-actions .icon-btn').count())) continue
      await cardChecks(page, name)
      seen++
    }
    assert.ok(seen > 0, 'at least one kind of open card has a menu chip')
  })

  // ---------------------------------------------------------------- sidebar
  const railWidth = (page) => page.locator('aside.rail').evaluate((node) => node.getBoundingClientRect().width)

  await test('ui fixes: collapsed, the logo is the expand button (hover and focus swap the icon); keyboard works', async (page) => {
    await page.getByRole('button', {name: 'Collapse sidebar'}).click()
    await page.waitForFunction(() => document.querySelector('aside.rail').getBoundingClientRect().width < 80)
    const brand = page.getByRole('button', {name: 'Expand sidebar'})
    assert.equal(await brand.count(), 1, 'one expand button: the logo')
    assert.equal(await brand.getAttribute('title'), 'Expand sidebar (Ctrl+B)')
    assert.equal(await page.locator('.rail-toggle').count(), 0, 'no separate toggle under the logo')
    await page.waitForFunction(() => Number(getComputedStyle(document.querySelector('.brand-expand')).opacity) < 0.01)
    await page.mouse.move(600, 500)
    await brand.hover()
    await page.waitForFunction(() => getComputedStyle(document.querySelector('.brand-expand')).opacity === '1')
    assert.equal(await page.locator('.rail-brand .mark').evaluate((node) => getComputedStyle(node).opacity), '0', 'the mark gives way to the icon')
    await shot(page, 'sidebar-collapsed-logo-hover')
    await page.mouse.move(600, 500)
    await page.waitForFunction(() => getComputedStyle(document.querySelector('.brand-expand')).opacity === '0')
    // Keyboard: focus shows the same icon, Enter expands.
    await page.keyboard.press('Tab')
    await page.evaluate(() => document.querySelector('.rail-brand').focus())
    await page.keyboard.press('Shift+Tab')
    await page.keyboard.press('Tab')
    assert.equal(await page.evaluate(() => document.activeElement?.className.includes('rail-brand')), true)
    await page.waitForFunction(() => getComputedStyle(document.querySelector('.brand-expand')).opacity === '1')
    await page.keyboard.press('Enter')
    await page.waitForFunction(() => document.querySelector('aside.rail').getBoundingClientRect().width > 150)
    await page.getByRole('button', {name: 'Collapse sidebar'}).waitFor()
    assert.equal(await page.getByRole('button', {name: /^Keepfin, go to Home/}).count(), 1, 'expanded, the logo goes Home again')
  })

  await test('ui fixes: the sidebar width eases over 200 ms and labels fade instead of wrapping', async (page) => {
    // At rest nothing animates, and the shell never transitions its columns: the page lays out once when the sidebar is toggled, only the sidebar eases.
    assert.equal(await page.locator('aside.rail').evaluate((node) => getComputedStyle(node).animationName), 'none')
    assert.ok(parseFloat(await page.locator('.app').evaluate((node) => getComputedStyle(node).transitionDuration)) < 0.01)
    assert.equal(await railWidth(page), 208)
    const sample = (selector) =>
      page.evaluate(
        (selector) =>
          new Promise((resolve) => {
            const samples = []
            const start = performance.now()
            const tick = () => {
              const label = document.querySelector('.rail .nav-label')
              const item = document.querySelector('.rail .nav-item')
              const main = document.querySelector('.main')
              samples.push({t: Math.round(performance.now() - start), duration: getComputedStyle(document.querySelector('aside.rail')).animationDuration, name: getComputedStyle(document.querySelector('aside.rail')).animationName, width: document.querySelector('aside.rail').getBoundingClientRect().width, labelOpacity: Number(getComputedStyle(label).opacity), labelHeight: label.getBoundingClientRect().height, itemHeight: item.getBoundingClientRect().height, mainLeft: main.getBoundingClientRect().left})
              if (performance.now() - start < 420) requestAnimationFrame(tick)
              else resolve(samples)
            }
            document.querySelector(selector).click()
            requestAnimationFrame(tick)
          }),
        selector,
      )
    const collapse = await sample('.rail-toggle')
    assert.equal(collapse[0].duration, '0.2s', 'a 200 ms ease while the sidebar changes')
    assert.equal(collapse[0].name, 'rail-collapse')
    assert.equal(collapse.at(-1).name, 'none', 'and the animation is gone again afterwards')
    const widths = collapse.map((s) => s.width)
    assert.ok(widths[0] > 100, `the rail does not jump on the first frame, got ${widths[0]}`)
    assert.ok(widths.some((value) => value > 80 && value < 200), `the width passes through in-between values, got ${widths.filter((_, i) => i % 3 === 0)}`)
    assert.equal(Math.round(widths.at(-1)), 68)
    assert.ok(widths.every((value, index) => index === 0 || value <= widths[index - 1] + 0.5), 'it only narrows')
    assert.ok(collapse.some((s) => s.labelOpacity > 0.05 && s.labelOpacity < 0.95), 'the labels fade through in-between values')
    assert.equal(new Set(collapse.map((s) => Math.round(s.labelHeight))).size, 1, 'a label never wraps to two lines')
    assert.equal(new Set(collapse.map((s) => Math.round(s.itemHeight))).size, 1, 'rows keep their height')
    assert.equal(new Set(collapse.map((s) => Math.round(s.mainLeft))).size, 1, 'the page settles once (one layout) instead of following the rail frame by frame')
    const expand = await sample('.rail-brand')
    assert.equal(expand[0].name, 'rail-expand')
    assert.equal(new Set(expand.map((s) => Math.round(s.mainLeft))).size, 1, 'and again when the sidebar opens')
    const grow = expand.map((s) => s.width)
    assert.ok(grow.some((value) => value > 80 && value < 200), 'it widens through in-between values too')
    assert.equal(Math.round(grow.at(-1)), 208)
    assert.equal(new Set(expand.map((s) => Math.round(s.labelHeight))).size, 1, 'labels do not wrap while opening either')
    assert.ok(expand.some((s) => s.labelOpacity > 0.05 && s.labelOpacity < 0.95), 'and fade in')
  })

  await test('ui fixes: with reduced motion the sidebar changes at once', async (page) => {
    await page.getByRole('button', {name: 'Collapse sidebar'}).click()
    const motion = await page.locator('aside.rail').evaluate((node) => getComputedStyle(node).animationName)
    assert.equal(motion, 'none', 'no animation')
    await page.waitForFunction(() => document.querySelector('aside.rail').getBoundingClientRect().width < 80, null, {timeout: 600})
  }, {reducedMotion: true})

  await test('ui fixes: the collapsed sidebar keeps icons in one place and hides labels and the library list', async (page) => {
    await page.getByRole('button', {name: 'Collapse sidebar'}).click()
    await page.waitForFunction(() => document.querySelector('aside.rail').getBoundingClientRect().width < 80)
    await page.waitForTimeout(300)
    const geo = await page.evaluate(() => {
      const rail = document.querySelector('aside.rail').getBoundingClientRect()
      const centre = (node) => {
        const rect = node.getBoundingClientRect()
        return rect.left + rect.width / 2 - rail.left
      }
      return {
        rail: rail.width,
        icons: [...document.querySelectorAll('.rail .nav-icon')].map(centre),
        foot: [...document.querySelectorAll('.rail .foot-item .icon')].map(centre),
        mark: centre(document.querySelector('.rail-brand .mark')),
        labels: [...document.querySelectorAll('.rail .nav-label, .rail .foot-label, .rail .wordmark')].map((node) => Number(getComputedStyle(node).opacity)),
      }
    })
    for (const value of [...geo.icons, ...geo.foot, geo.mark]) assert.ok(Math.abs(value - geo.rail / 2) < 4, `icons are centred in the collapsed rail, ${value} vs ${geo.rail / 2}`)
    assert.ok(geo.labels.every((value) => value === 0), 'labels are invisible')
    await shot(page, 'sidebar-collapsed')
  })

  // Light-theme screenshots of the rest of the list (only when UI_FIXES_SHOTS is set; the checks above already cover both themes).
  if (SHOTS)
    await test('ui fixes (light): screenshots of the scrolled lyrics, the mini player menu and the collapsed sidebar', async (page) => {
      await playGlassHarbour(page)
      await openLyrics(page)
      await page.locator('.lyrics-overlay .lyrics').hover()
      await page.mouse.wheel(0, 700)
      await page.waitForTimeout(500)
      await shot(page, 'lyrics-scrolled-header-visible-light')
      await page.getByRole('button', {name: 'Close lyrics'}).click()
      await bar(page).getByRole('button', {name: /^More actions for /}).click()
      await page.getByRole('menu', {name: /^More actions for /}).waitFor()
      await shot(page, 'mini-player-menu-light')
      await page.keyboard.press('Escape')
      await page.getByRole('button', {name: 'Collapse sidebar'}).click()
      await page.waitForFunction(() => document.querySelector('aside.rail').getBoundingClientRect().width < 80)
      await page.mouse.move(600, 500)
      await page.getByRole('button', {name: 'Expand sidebar'}).hover()
      await page.waitForTimeout(300)
      await shot(page, 'sidebar-collapsed-logo-hover-light')
    }, {theme: 'light', fake: {lyricLines: manyLines}})
  // ---------------------------------------------------------------- one page structure and the keyboard (dev-docs/plans/PAGE-LAYOUT.md)
  await test('app bar: one global search and the update and Activity buttons on the right; no Settings gear in any page', async (page) => {
    const appBar = page.locator('.app-bar')
    await appBar.getByRole('textbox', {name: 'Search your library, or paste a link'}).waitFor()
    await appBar.getByRole('button', {name: /^Activity/}).waitFor()
    for (const name of ['Home', 'Search', 'Library', 'Downloads', 'Settings']) {
      await mainNav(page).getByRole('button', {name: new RegExp('^' + name)}).click()
      assert.equal(await page.locator('.app-bar').count(), 1, `one app bar on ${name}`)
      assert.equal(await page.locator('.main').getByRole('button', {name: 'Settings', exact: true}).count(), 0, `no Settings gear on ${name}`)
      assert.equal(await page.locator('.main .page-header h1:visible').count(), 1, `one h1 on ${name}`)
    }
  })

  await test('shortcuts: Ctrl+K and "/" focus the search, Ctrl+, opens Settings, Space plays and pauses (never while typing)', async (page) => {
    const search = page.getByRole('textbox', {name: 'Search your library, or paste a link'})
    const focused = () => search.evaluate((node) => node === document.activeElement)
    await page.keyboard.press('Control+k')
    assert.equal(await focused(), true, 'Ctrl+K focuses the search')
    await page.keyboard.press('Escape')
    assert.equal(await focused(), false, 'Esc leaves it')
    await page.keyboard.press('/')
    assert.equal(await focused(), true, '"/" focuses the search')
    await page.keyboard.type('a/b')
    assert.equal(await search.inputValue(), 'a/b', 'a slash typed in the field stays in the field')
    await search.fill('')
    await page.keyboard.press('Escape')
    await page.keyboard.press('Control+,')
    await page.getByRole('heading', {name: 'Settings', level: 1}).waitFor()
    await playGlassHarbour(page)
    const paused = () => page.evaluate(() => window.__keepfinTest.audio().active.paused)
    await page.waitForFunction(() => window.__keepfinTest.audio().active.currentTime > 0.3)
    assert.equal(await paused(), false)
    await page.locator('.page-header h1:visible').click()
    await page.keyboard.press('Space')
    assert.equal(await paused(), true, 'Space pauses')
    await page.keyboard.press('Space')
    assert.equal(await paused(), false, 'and plays again')
    await search.focus()
    await page.keyboard.press('Space')
    assert.equal(await paused(), false, 'Space typed in the search does not pause')
    assert.equal(await search.inputValue(), ' ')
  })

  await test('shortcuts: Esc puts the lyrics and the full player away', async (page) => {
    await playGlassHarbour(page)
    await openLyrics(page)
    await page.keyboard.press('Escape')
    await page.locator('.lyrics-overlay').waitFor({state: 'detached'})
    await page.getByRole('button', {name: /Open the player/}).click()
    await page.getByRole('dialog', {name: 'Now playing'}).waitFor()
    await page.keyboard.press('Escape')
    await page.getByRole('dialog', {name: 'Now playing'}).waitFor({state: 'hidden'})
  })
}