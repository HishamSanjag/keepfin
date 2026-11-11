// Cobalt Atelier (dev-docs/plans/MAIN-REDESIGN.md): the structure of the restyled main screens against the synthetic bridge. One
// sidebar that holds spaces and playlists, Continue watching cards with progress and the time left, sections that keep one card shape,
// every size as one list under a Format row, and the Afterglow mini player.
// Registered by smoke-ui-flows.mjs, so `npm run gui:ui-test` runs them. Fixtures never certify provider or playback behaviour.
import assert from 'node:assert/strict'

const YT = 'https://www.youtube.com/watch?v=lofoten8k'
const MUSIC = 'https://music.youtube.com/watch?v=tidal88'

export async function registerAtelierSuite({test, calls, last, sent}) {
  const mainNav = (page) => page.getByRole('navigation', {name: 'Main navigation'})
  const toLibrary = async (page) => {
    await mainNav(page).getByRole('button', {name: 'Library'}).click()
    await page.getByRole('region', {name: 'Spaces', exact: true}).waitFor()
  }
  const side = (page) => page.getByRole('navigation', {name: 'Your library'})

  await test('atelier: the fonts are bundled and loaded (Inter for text, Manrope for headings)', async (page) => {
    const state = await page.evaluate(async () => {
      await document.fonts.ready
      const fonts = [...document.fonts].filter((face) => face.status === 'loaded').map((face) => face.family.replace(/"/g, ''))
      return {fonts, inter: document.fonts.check('14px Inter'), manrope: document.fonts.check('700 40px Manrope'), body: getComputedStyle(document.body).fontFamily}
    })
    assert.ok(state.inter && state.manrope, 'both families can be used')
    assert.match(state.body, /Inter/)
    assert.ok(state.fonts.includes('Inter') && state.fonts.includes('Manrope'), `both are loaded from the renderer assets, loaded: ${state.fonts}`)
  })

  await test('atelier: one sidebar with the destinations, then spaces and playlists as a quiet list', async (page) => {
    await toLibrary(page)
    assert.equal(await page.locator('aside.rail').count(), 1, 'a single sidebar')
    for (const name of ['Home', 'Search', 'Library', 'Downloads']) await mainNav(page).getByRole('button', {name, exact: false}).first().waitFor()
    await page.locator('.rail-foot').getByRole('button', {name: 'Settings', exact: true}).waitFor()
    const spaces = side(page).getByRole('list', {name: 'Spaces'})
    const playlists = side(page).getByRole('list', {name: 'Playlists'})
    await spaces.getByRole('button', {name: /^Music/}).waitFor()
    await playlists.getByRole('button', {name: /^Night drive/}).waitFor()
    // Order inside the one sidebar: main navigation, then spaces, then playlists, then the footer.
    const order = await page.evaluate(() => {
      const y = (selector) => document.querySelector(selector)?.getBoundingClientRect().top ?? -1
      return [y('.rail-nav'), y('.your-list[aria-label="Spaces"]'), y('.your-list[aria-label="Playlists"]'), y('.rail-foot')]
    })
    assert.deepEqual([...order].sort((a, b) => a - b), order, 'navigation, spaces, playlists, footer from top to bottom')
    // Devices are on in the fixture, so their status shows at the bottom and opens the Devices page.
    await page.locator('.rail-foot').getByRole('button', {name: /^Devices, \d+ connected/}).click()
    await page.getByRole('heading', {name: 'Devices', level: 2}).first().waitFor()
  })

  await test('sidebar: edit mode hides, reorders and pins; it can dock, and its right edge resizes it', async (page) => {
    await toLibrary(page)
    const rail = page.locator('aside.rail')
    assert.equal(await rail.getAttribute('data-style'), 'floating', 'floating by default')
    await rail.getByRole('button', {name: 'Edit sidebar'}).click()
    const pages = mainNav(page).getByRole('list', {name: 'Pages'})
    assert.equal(await pages.getByRole('button', {name: 'Hide Downloads'}).count(), 0, 'Downloads always stays')
    await pages.getByRole('button', {name: 'Hide Search'}).click()
    // the handle moves a row with the arrow keys
    await pages.getByRole('button', {name: 'Move Downloads'}).press('ArrowUp')
    await side(page).getByRole('button', {name: 'Pin Night drive to top'}).click()
    await side(page).getByRole('list', {name: 'Pinned'}).getByText('Night drive').waitFor()
    await rail.getByRole('switch', {name: 'Floating sidebar'}).click()
    assert.equal(await rail.getAttribute('data-style'), 'docked')
    await rail.getByRole('button', {name: 'Done', exact: true}).click()
    // after Done: Search is gone, Downloads moved before Library, Night drive sits under Pinned and not under Playlists
    const tabs = await mainNav(page).getByRole('button').evaluateAll((buttons) => buttons.map((button) => button.title))
    assert.deepEqual(tabs, ['Home', 'Downloads', 'Library'])
    await side(page).getByRole('list', {name: 'Pinned'}).getByRole('button', {name: /^Night drive/}).waitFor()
    assert.equal(await side(page).getByRole('list', {name: 'Playlists'}).getByRole('button', {name: /^Night drive/}).count(), 0)
    const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('keepfin-sidebar')))
    assert.deepEqual([saved.style, saved.hiddenNav, saved.nav], ['docked', ['Search'], ['Home', 'Search', 'Downloads', 'Library']], 'the choices are remembered, hidden pages keep their place')
    // right-click a pinned row to unpin it
    await side(page).getByRole('list', {name: 'Pinned'}).getByRole('button', {name: /^Night drive/}).click({button: 'right'})
    await page.getByRole('menuitem', {name: 'Unpin from top'}).click()
    await side(page).getByRole('list', {name: 'Playlists'}).getByRole('button', {name: /^Night drive/}).waitFor()
    // width: the separator follows the keyboard and Enter resets it
    const edge = rail.getByRole('separator', {name: 'Resize sidebar'})
    const before = (await rail.boundingBox()).width
    await edge.focus()
    await page.keyboard.press('Shift+ArrowRight')
    assert.equal(await edge.getAttribute('aria-valuenow'), '256')
    await page.waitForFunction((w) => document.querySelector('aside.rail').getBoundingClientRect().width > w + 20, before)
    await page.keyboard.press('Enter')
    assert.equal(await edge.getAttribute('aria-valuenow'), '224')
  })

  await test('atelier: Library home order is spaces, playlists, albums, artists, then videos', async (page) => {
    await toLibrary(page)
    const home = page.locator('.library-home')
    assert.equal((await page.locator('.page-header h1:visible').textContent()).trim(), 'Library')
    await page.getByText('Your media, close at hand.').waitFor()
    const tops = await home.evaluate((node) => {
      const top = (label) => node.querySelector(`[aria-label="${label}"]`)?.getBoundingClientRect().top ?? -1
      return ['Spaces', 'Playlists', 'Albums', 'Artists', 'Videos'].map(top)
    })
    assert.ok(tops.every((value) => value >= 0), `all sections exist: ${tops}`)
    assert.deepEqual([...tops].sort((a, b) => a - b), tops, 'sections are in the designed order')
  })

  await test('atelier: Continue watching cards are wide 16:9 with progress, a play button and the time left', async (page) => {
    await mainNav(page).getByRole('button', {name: 'Home'}).click()
    const cards = page.getByRole('region', {name: 'Continue watching'}).getByRole('listitem')
    assert.ok((await cards.count()) >= 1)
    for (let index = 0; index < (await cards.count()); index++) {
      const card = cards.nth(index)
      await card.locator('.card-play').waitFor({state: 'attached'})
      assert.ok(await card.locator('.card-play').count(), 'the play button is over the artwork')
      const bar = card.getByRole('progressbar', {name: 'Watched'})
      assert.ok(Number(await bar.getAttribute('aria-valuenow')) > 0, 'progress is shown')
      // the shared rule: "0:11 left", "4 min left", "1 h left", "1 h 12 min left"
      assert.match(await card.locator('.card-sub').textContent(), /^(\d+:\d\d|\d+ min|\d+ h|\d+ h \d+ min) left$/)
      const box = await card.locator('.card-art').boundingBox()
      assert.ok(Math.abs(box.width / box.height - 16 / 9) < 0.02, `16:9, got ${box.width / box.height}`)
      assert.ok(box.width > 150 && box.width < 320, `compact 16:9 cards (about 200px), not posters or giant tiles, got ${box.width}`)
    }
    // The card plays the video it resumes.
    await cards.first().getByRole('button', {name: /, .+ left$/}).click()
    await page.getByRole('dialog', {name: /^Playing /}).or(page.getByRole('region', {name: 'Now playing'})).first().waitFor()
  })

  await test('atelier: sections keep one card shape: videos are 16:9, music squares, artists round', async (page) => {
    await toLibrary(page)
    const shelves = page.locator('.library-home .section-body[data-kind="video"], .library-home .section-body[data-kind="square"]')
    assert.ok((await shelves.count()) >= 2, 'a video section and a music section at least')
    const seen = new Set()
    for (let index = 0; index < (await shelves.count()); index++) {
      const shelf = shelves.nth(index)
      const shape = await shelf.getAttribute('data-kind')
      seen.add(shape)
      const ratios = await shelf.locator('.card-art').evaluateAll((nodes) => nodes.map((node) => Math.round((node.getBoundingClientRect().width / node.getBoundingClientRect().height) * 10) / 10))
      assert.ok(ratios.length > 0, 'the shelf has cards')
      const want = shape === 'video' ? 1.8 : 1
      assert.ok(ratios.every((ratio) => Math.abs(ratio - want) < 0.1), `${await shelf.getAttribute('aria-label')} keeps one shape (${shape}), ratios ${ratios}`)
    }
    assert.ok(seen.has('video') && seen.has('square'), 'both shapes exist, in separate shelves')
  })

  await test('atelier: a space page has a count, a sort menu and a list/grid switch', async (page) => {
    await toLibrary(page)
    await side(page).getByRole('list', {name: 'Spaces'}).getByRole('button', {name: /^Videos/}).click()
    await page.getByText(/\d+ items? ·/).first().waitFor()
    const toolbar = page.getByRole('toolbar', {name: 'Library toolbar'})
    await toolbar.getByLabel('Sort').waitFor()
    await toolbar.getByRole('radio', {name: /List view|Grid view/}).first().waitFor()
  })

  await test('atelier: Downloads shows every size once as one list with real sizes, best first, no fold', async (page) => {
    await page.locator('textarea').first().fill(YT)
    const list = page.getByRole('radiogroup', {name: 'Video quality'})
    await list.waitFor()
    const rows = list.getByRole('radio')
    assert.equal(await rows.count(), 12, 'every size the engine listed')
    for (let index = 0; index < 1; index++) {
      const row = rows.nth(index)
      assert.match(await row.locator('.size-row-size').textContent(), /\d(\.\d)?\s?(MB|GB)/, 'a size from the engine, not a placeholder')
      assert.match(await row.locator('.size-row-label').textContent(), /\d+p/)
    }
    assert.match(await rows.first().locator('.size-row-label').textContent(), /Best/)
    assert.equal(await rows.first().getAttribute('aria-checked'), 'true', 'the engine default (best) is preselected')
    assert.equal(await page.getByRole('button', {name: /More sizes/}).count(), 0)
    await page.getByRole('radio', {name: 'MKV'}).waitFor()
    await rows.nth(1).click()
    assert.equal(await rows.nth(1).getAttribute('aria-checked'), 'true')
    assert.match(await page.locator('.download-btn').textContent(), /^Download\s*·\s*[≈~]?\d/)
    await page.locator('.download-btn').click()
    await sent(page, 'enqueue')
    assert.equal((await last(page, 'enqueue')).format.maxHeight, (await rows.nth(1).getAttribute('aria-label')).match(/^(\d+)p/)[1] * 1)
  }, {fake: {simulate: false}})

  await test('atelier: audio lists its qualities, MP3 320 preselected, with sizes where known', async (page) => {
    await page.locator('textarea').first().fill(MUSIC)
    const list = page.getByRole('radiogroup', {name: 'Audio quality'})
    await list.waitFor()
    assert.equal(await list.locator('[data-row="320"]').getAttribute('aria-checked'), 'true', 'MP3 320 is the default and is preselected')
    await list.locator('[data-row="320"] .size-row-detail').filter({hasText: 'MP3'}).waitFor()
    await page.getByRole('radio', {name: 'FLAC'}).waitFor()
  }, {fake: {simulate: false}})

  await test('atelier: queue rows show one status line and at most two actions beside the menu', async (page) => {
    const rows = page.locator('.jobs > .job')
    await rows.first().waitFor()
    for (let index = 0; index < (await rows.count()); index++) {
      const row = rows.nth(index)
      const buttons = await row.locator('.job-actions').locator('button, a').count()
      assert.ok(buttons <= 3, `at most two actions and a menu, got ${buttons} (${await row.getAttribute('aria-label')})`)
    }
    const live = page.locator('.job-live').first()
    assert.equal(await live.locator('.job-meta').isVisible(), false, 'a running row has one status line, not a second details line')
    assert.match((await live.locator('.job-stats').textContent()) || '', /\d/)
    const font = await live.locator('.job-stats').evaluate((node) => getComputedStyle(node).fontVariantNumeric)
    assert.match(font, /tabular-nums/)
  })

  await test('atelier: the mini player is a raised bar: artwork, a centred outlined play button, the seek bar under it, volume and queue on the right', async (page) => {
    await toLibrary(page)
    await side(page).getByRole('list', {name: 'Spaces'}).getByRole('button', {name: /^Music/}).click()
    await page.getByRole('button', {name: /^Glass Harbour/}).first().click()
    const bar = page.getByRole('region', {name: 'Now playing'})
    await bar.waitFor()
    const play = bar.getByRole('button', {name: /^Pause|^Play/})
    const seek = bar.getByRole('slider', {name: 'Seek'})
    const volume = bar.getByRole('slider', {name: 'Volume'})
    const queue = bar.getByRole('button', {name: 'Queue and Now playing'})
    for (const name of ['Shuffle off', 'Previous song', 'Next song']) await bar.getByRole('button', {name}).waitFor()
    await bar.getByRole('button', {name: /^Repeat/}).waitFor()
    const geo = await page.evaluate(() => {
      const box = (selector) => document.querySelector(selector)?.getBoundingClientRect()
      const bar = box('.now-bar')
      const playBox = box('.now-bar .play-btn')
      const seekBox = box('.now-bar input[aria-label="Seek"]')
      const volumeBox = box('.now-bar input[aria-label="Volume"]')
      const art = box('.now-bar .now-art')
      const style = getComputedStyle(document.querySelector('.now-bar .play-btn'))
      return {
        barLeft: bar.left, barRight: bar.right, art: [art.width, art.height],
        play: {x: playBox.left + playBox.width / 2, y: playBox.top + playBox.height / 2, w: playBox.width, h: playBox.height, radius: style.borderRadius, shadow: style.boxShadow},
        seek: {left: seekBox.left, right: seekBox.right, y: seekBox.top + seekBox.height / 2},
        volumeLeft: volumeBox.left,
        shadow: getComputedStyle(document.querySelector('.now-bar')).boxShadow,
      }
    })
    assert.ok(geo.art[0] >= 40 && geo.art[0] === geo.art[1], `compact square artwork, got ${geo.art}`)
    assert.ok(Math.abs(geo.play.w - geo.play.h) < 1 && /50%|999|22px/.test(geo.play.radius), 'the play button is a circle')
    assert.ok(/inset/.test(geo.play.shadow) && !/rgba\(0, 0, 0, 0\)/.test(geo.play.shadow), 'it is outlined')
    const mid = (geo.barLeft + geo.barRight) / 2
    assert.ok(Math.abs(geo.play.x - mid) < 90, `the play button sits in the middle of the bar (${geo.play.x} vs ${mid})`)
    assert.ok(geo.seek.y > geo.play.y + geo.play.h / 2 - 2, 'the seek bar runs under the transport')
    assert.ok(geo.seek.left < geo.play.x && geo.seek.right > geo.play.x, 'across the middle')
    assert.ok(geo.volumeLeft > geo.seek.right, 'volume sits right of the seek bar')
    assert.notEqual(geo.shadow, 'none', 'a soft shadow lifts the bar: the one place with real elevation')
    // The times beside the bar use tabular figures.
    const times = await bar.locator('.seek-time').evaluateAll((nodes) => nodes.map((node) => getComputedStyle(node).fontVariantNumeric))
    assert.equal(times.length, 2)
    assert.ok(times.every((value) => /tabular-nums/.test(value)))
    assert.ok(await queue.isVisible() && (await seek.isVisible()) && (await volume.isVisible()) && (await play.isVisible()))
  })

  await test('atelier: the full player has an artwork wash behind opaque controls; lyrics stay readable', async (page) => {
    await toLibrary(page)
    await side(page).getByRole('list', {name: 'Spaces'}).getByRole('button', {name: /^Music/}).click()
    await page.getByRole('button', {name: /^Glass Harbour/}).first().click()
    await page.getByRole('region', {name: 'Now playing'}).getByRole('button', {name: /Open the player/}).click()
    const full = page.getByRole('dialog', {name: 'Now playing'})
    await full.waitFor()
    const info = await page.evaluate(() => {
      const wash = document.querySelector('.full-player .art-wash')
      const layer = wash?.querySelector('.art-wash-layer')
      const veil = wash?.querySelector('.art-wash-veil')
      const controls = document.querySelector('.full-controls')
      const alpha = (color) => {
        const match = /rgba?\(([^)]+)\)/.exec(color)
        if (!match) return 1
        const parts = match[1].split(/[ ,/]+/).filter(Boolean)
        return parts.length > 3 ? Number(parts[3]) : 1
      }
      return {
        wash: !!wash, layerFilter: layer ? getComputedStyle(layer).filter : '', layerImage: layer ? getComputedStyle(layer).backgroundImage : '', veil: veil ? getComputedStyle(veil).backgroundColor : '',
        controlsAlpha: controls ? alpha(getComputedStyle(controls).backgroundColor) : -1,
      }
    })
    assert.ok(info.wash, 'the wash exists')
    assert.equal(info.layerFilter, 'none', 'no blur filter on the wash: it is a flat gradient from the tint to the canvas')
    assert.match(info.layerImage, /linear-gradient/, 'the wash is a gradient, not a blurred image')
    assert.equal(info.controlsAlpha, 1, 'the controls sit on an opaque layer')
    assert.ok(info.veil && !/rgba\(0, 0, 0, 0\)/.test(info.veil))
    // Lyrics: the current line is brightest, the others stay readable (never near-invisible).
    const lyric = page.locator('.full-player .lyric').first()
    await lyric.waitFor()
    const opacities = await page.locator('.full-player .lyric:not(.plain)').evaluateAll((nodes) => nodes.map((node) => Number(getComputedStyle(node).opacity)))
    assert.ok(opacities.length > 0 && opacities.every((value) => value >= 0.9), `lyrics are never faded out, opacities ${opacities}`)
  })

  await test('atelier: reduced motion gives the player wash no cross-fade', async (page) => {
    await toLibrary(page)
    await side(page).getByRole('list', {name: 'Spaces'}).getByRole('button', {name: /^Music/}).click()
    await page.getByRole('button', {name: /^Glass Harbour/}).first().click()
    await page.getByRole('region', {name: 'Now playing'}).getByRole('button', {name: /Open the player/}).click()
    await page.getByRole('dialog', {name: 'Now playing'}).waitFor()
    await page.getByRole('dialog', {name: 'Now playing'}).getByRole('button', {name: 'Next song'}).click()
    await page.waitForTimeout(150)
    assert.equal(await page.locator('.full-player .art-wash-layer').count(), 1, 'the old wash is dropped at once, nothing fades')
  }, {reducedMotion: true})

  await test('atelier: the wash cross-fades on a track change (the old one stays underneath until the new one covers it)', async (page) => {
    await toLibrary(page)
    await side(page).getByRole('list', {name: 'Spaces'}).getByRole('button', {name: /^Music/}).click()
    await page.getByRole('button', {name: /^Glass Harbour/}).first().click()
    await page.getByRole('region', {name: 'Now playing'}).getByRole('button', {name: /Open the player/}).click()
    const full = page.getByRole('dialog', {name: 'Now playing'})
    await full.waitFor()
    await full.getByRole('button', {name: 'Next song'}).click()
    await page.waitForFunction(() => document.querySelectorAll('.full-player .art-wash-layer').length === 2)
    assert.equal(await page.locator('.full-player .art-wash-layer.is-entering').count(), 1)
    await page.waitForFunction(() => document.querySelectorAll('.full-player .art-wash-layer').length === 1, null, {timeout: 4000})
  })

  // ---------------------------------------------------------------- narrow windows (dev-docs/plans/DESKTOP-WIDTHS.md)
  const playGlassHarbour = async (page) => {
    await toLibrary(page)
    await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('button', {name: /^Music/}).click()
    await page.getByRole('button', {name: /^Glass Harbour/}).first().click()
    return page.getByRole('region', {name: 'Now playing'})
  }
  const overlap = (a, b) => a.x < b.x + b.width - 1 && b.x < a.x + a.width - 1 && a.y < b.y + b.height - 1 && b.y < a.y + a.height - 1

  await test('narrow window: no phone layout; the top bar keeps search, updates and notifications on one row', async (page) => {
    assert.equal(await page.locator('.compact-bar').count(), 0, 'no separate brand bar')
    const rail = await page.locator('aside.rail').boundingBox()
    assert.ok(rail.x < 12 && rail.height > 500, 'the sidebar stays at the side, not a bottom tab bar')
    const search = await page.locator('.app-search').boundingBox()
    const bell = await page.locator('.act-bell').boundingBox()
    assert.ok(Math.abs(search.y + search.height / 2 - (bell.y + bell.height / 2)) < 4, 'search and the bell share one row')
  }, {width: 560, height: 800})

  await test('narrow window: the music bar is one row at 860 px; the volume slider opens over the mute button', async (page) => {
    const bar = await playGlassHarbour(page)
    const info = await bar.locator('.now-info').boundingBox()
    const play = await bar.locator('.play-btn').boundingBox()
    assert.ok(play.y < info.y + info.height && info.y < play.y + play.height, 'song and transport on one row')
    const mute = bar.getByRole('button', {name: 'Mute'})
    assert.ok(await mute.isVisible(), 'the mute button stays')
    const slider = bar.getByRole('slider', {name: 'Volume'})
    assert.equal(await slider.isVisible(), false, 'the slider waits for the pointer')
    await mute.hover()
    await slider.waitFor()
    assert.ok((await slider.boundingBox()).y + 4 < (await mute.boundingBox()).y, 'it opens above the button')
  }, {width: 860, height: 800})

  await test('narrow window: the music bar has two rows below 800 px, the seek bar the width of the bar', async (page) => {
    const bar = await playGlassHarbour(page)
    const info = await bar.locator('.now-info').boundingBox()
    const play = await bar.locator('.play-btn').boundingBox()
    assert.ok(play.y > info.y + info.height - 4, 'the transport is on the second row')
    const seek = await bar.getByRole('slider', {name: /Seek|Position/}).first().boundingBox()
    const barBox = await bar.boundingBox()
    assert.ok(seek.width > barBox.width * 0.7, `a long seek bar, got ${seek.width} of ${barBox.width}`)
  }, {width: 560, height: 800})

  await test('narrow window: the queue panel opens over the page below 960 px', async (page) => {
    const bar = await playGlassHarbour(page)
    const before = await page.locator('main.main').boundingBox()
    await bar.getByRole('button', {name: 'Queue and Now playing'}).click()
    const panel = page.getByRole('complementary', {name: 'Now playing panel'})
    await panel.waitFor()
    const box = await panel.boundingBox()
    assert.ok(box.width >= 280 && box.x + box.width <= 861, 'a panel at the right edge')
    assert.deepEqual(await page.locator('main.main').boundingBox(), before, 'the page keeps its width')
  }, {width: 860, height: 800})

  await test('narrow window: the expanded player is one column; artwork, title and controls never overlap', async (page) => {
    const bar = await playGlassHarbour(page)
    await bar.getByRole('button', {name: /Open the player/}).click()
    const full = page.getByRole('dialog', {name: 'Now playing'})
    await full.waitFor()
    await page.waitForTimeout(600)
    const art = await full.locator('.full-art').boundingBox()
    const title = await full.locator('.full-meta h2').boundingBox()
    const controls = await full.locator('.full-controls').boundingBox()
    const tabs = await full.getByRole('tablist').boundingBox()
    assert.ok(title.height > 10, 'the title is shown')
    // Stage's one control bar sits along the bottom of the window, outside the scrolling column: the song, then the tabs, scroll above it
    for (const [name, a, b] of [['art/title', art, title], ['art/tabs', art, tabs]]) assert.ok(!overlap(a, b), `${name} overlap: ${JSON.stringify([a, b])}`)
    assert.ok(art.y < title.y && title.y < tabs.y, 'art, title, then the tabs')
    const viewport = page.viewportSize()
    assert.ok(controls.y + controls.height <= viewport.height + 1 && controls.y > viewport.height / 2, 'the control bar is along the bottom')
    assert.ok(art.y + art.height <= controls.y && title.y + title.height <= controls.y, 'the artwork and title are not under the controls')  }, {width: 860, height: 640})
}
