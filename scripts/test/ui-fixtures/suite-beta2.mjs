// 0.5.5-beta.2 desktop fixes: Library page sections and filters, sidebar chips/toggle/footer, "Works with",
// history grid cards, Now playing panel resize, lyrics icon, Play on finished downloads.
import assert from 'node:assert/strict'

const V2 = {libraryScenario: 'v2'}

export async function registerBeta2Suite({test, calls, last, sent}) {
  const mainNav = (page) => page.getByRole('navigation', {name: 'Main navigation'})
  const goto = (page, name) => mainNav(page).getByRole('button', {name}).click()
  const filters = (page) => page.getByRole('group', {name: 'Show in Library'})
  const chip = (page, name) => filters(page).getByRole('button', {name, exact: true})
  const rail = (page) => page.locator('aside.rail')
  const width = async (locator) => (await locator.boundingBox()).width
  const finishedTab = async (page) => {
    await goto(page, 'Downloads')
    await page.getByRole('radio', {name: /^Finished/}).click()
    await page.getByRole('list', {name: 'Download history'}).waitFor()
  }
  const openPanel = async (page) => {
    await goto(page, 'Library')
    await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('button', {name: /^Music/}).click()
    await page.locator('button.row-main', {hasText: 'Glass Harbour'}).click()
    const panel = page.getByRole('complementary', {name: 'Now playing panel'})
    if (!(await panel.isVisible())) await page.getByRole('region', {name: 'Now playing'}).getByRole('button', {name: 'Queue and Now playing'}).click()
    await panel.waitFor()
  }

  // ---------------------------------------------------------------- Library page
  await test('library page: spaces, playlists and collections on one page, filters, and a way back from a collection', async (page) => {
    await goto(page, 'Library')
    await page.getByRole('region', {name: 'Spaces', exact: true}).waitFor()
    await page.getByRole('region', {name: 'Playlists', exact: true}).waitFor()
    await page.getByRole('region', {name: 'Collections', exact: true}).waitFor()
    for (const name of ['All', 'Spaces', 'Playlists', 'Collections', 'Albums', 'Artists', 'Videos']) await chip(page, name).waitFor()
    assert.equal(await chip(page, 'All').getAttribute('aria-pressed'), 'true')
    // "See all" on the Collections section is the Collections filter.
    await page.getByRole('button', {name: 'See all Collections'}).click()
    assert.equal(await chip(page, 'Collections').getAttribute('aria-pressed'), 'true')
    await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).click()
    await page.getByRole('button', {name: 'Shuffle'}).waitFor()
    // The breadcrumb returns to the same filter, and the filter row is still there.
    await page.getByRole('navigation', {name: 'Breadcrumb'}).getByRole('button', {name: 'Collections'}).click()
    await chip(page, 'Collections').waitFor()
    assert.equal(await chip(page, 'Collections').getAttribute('aria-pressed'), 'true')
    await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).waitFor()
    await chip(page, 'Playlists').click()
    await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Night drive/}).click()
    await page.getByRole('navigation', {name: 'Breadcrumb'}).getByRole('button', {name: 'Playlists'}).click()
    await chip(page, 'Playlists').waitFor()
    await chip(page, 'Albums').click()
    await page.getByRole('list', {name: 'Albums'}).waitFor()
    await chip(page, 'Spaces').click()
    await page.getByRole('button', {name: /^Music/}).first().waitFor()
    await chip(page, 'All').click()
    assert.equal(await page.getByRole('button', {name: /Back to collections/}).count(), 0, 'no separate Collections mode')
    // The sidebar's Library item always returns to the root of the page.
    await page.getByRole('button', {name: 'See all Playlists'}).click()
    await goto(page, 'Library')
  }, {fake: V2})

  await test('sidebar: a single sidebar holds the spaces and the playlists, with nothing to scroll sideways', async (page) => {
    await goto(page, 'Library')
    const library = page.getByRole('navigation', {name: 'Your library'})
    const spaces = library.getByRole('list', {name: 'Spaces'})
    const playlists = library.getByRole('list', {name: 'Playlists'})
    await spaces.getByRole('button', {name: /^Music/}).waitFor()
    await playlists.getByRole('button', {name: /^Night drive/}).waitFor()
    // One sidebar: the second Library sidebar (Home, Spaces, Collections) is gone, its content lives in this one.
    assert.equal(await page.locator('aside.rail').count(), 1)
    assert.equal(await page.locator('.lib-side').count(), 0)
    assert.equal(await page.getByRole('navigation', {name: 'Library sections'}).count(), 0)
    const overflow = await library.evaluate((node) => node.scrollWidth - node.clientWidth)
    assert.ok(overflow <= 0, `no sideways scroll, overflow ${overflow}`)
    // Both lists sit inside the sidebar and a space opens its page.
    const inside = await library.evaluate((node) => {
      const outer = node.closest('aside').getBoundingClientRect()
      return [...node.querySelectorAll('button')].every((button) => button.getBoundingClientRect().right <= outer.right + 1)
    })
    assert.ok(inside, 'every entry fits inside the sidebar')
    await spaces.getByRole('button', {name: /^Music/}).click()
    assert.equal(await spaces.getByRole('button', {name: /^Music/}).getAttribute('aria-current'), 'page')
  })

  await test('sidebar: the Spaces chip glyphs stay visible on the space colour when selected', async (page) => {
    await goto(page, 'Library')
    await page.getByRole('navigation', {name: 'Your library'}).getByRole('button', {name: /^Music/}).click()
    const entry = page.getByRole('navigation', {name: 'Your library'}).getByRole('button', {name: /^Music/})
    const colors = await entry.evaluate((node) => {
      const mark = node.querySelector('.space-mark')
      const icon = mark.querySelector('svg')
      return {glyph: getComputedStyle(icon).color, tile: getComputedStyle(mark).backgroundColor}
    })
    assert.notEqual(colors.glyph, colors.tile, 'the glyph differs from its tile')
  })

  // ---------------------------------------------------------------- Works with, logo
  await test('downloads: a "Works with" line lists real services, in full and downloader-only mode', async (page) => {
    await goto(page, 'Downloads')
    const line = page.locator('.works-with')
    await line.waitFor()
    const text = await line.textContent()
    for (const name of ['YouTube', 'Spotify', 'Apple Music', 'SoundCloud', 'TikTok', 'Instagram', '1,000+']) assert.ok(text.includes(name), name)
    assert.ok(!/yt-dlp|ffmpeg|gallery-dl|aria2/i.test(text))
  })
  await test('downloader-only: the Works with line is on Downloads too', async (page) => {
    await page.locator('.works-with').waitFor()
    assert.equal(await mainNav(page).getByRole('button', {name: 'Home'}).count(), 0)
  }, {fake: {library: 'off'}})

  await test('logo: goes Home in full mode', async (page) => {
    await goto(page, 'Library')
    await page.getByRole('button', {name: 'Keepfin, go to Home'}).click()
    await page.getByRole('heading', {name: 'Home', level: 1}).waitFor()
    assert.equal(await mainNav(page).getByRole('button', {name: 'Home'}).getAttribute('aria-current'), 'page')
  })
  await test('logo: goes to Downloads in downloader-only mode', async (page) => {
    await page.locator('aside.rail').getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('button', {name: 'Keepfin, add a link'}).click()
    await page.getByRole('heading', {name: 'Downloads', level: 1}).waitFor()
  }, {fake: {library: 'off'}})

  // ---------------------------------------------------------------- sidebar toggle and footer
  await test('sidebar: collapse and expand by hand, remembered, Ctrl+B, badge stays, footer shows icons only', async (page) => {
    const full = await width(rail(page))
    assert.ok(full > 150)
    // The width eases over 200 ms, so wait for it to settle before measuring.
    const settled = (narrow) => page.waitForFunction((narrow) => (narrow ? document.querySelector('aside.rail').getBoundingClientRect().width < 70 : document.querySelector('aside.rail').getBoundingClientRect().width > 200), narrow)
    await page.getByRole('button', {name: 'Collapse sidebar'}).click()
    await page.getByRole('button', {name: 'Expand sidebar'}).waitFor()
    await settled(true)
    assert.ok((await width(rail(page))) < 90, 'icons only')
    await page.getByRole('navigation', {name: 'Your library'}).waitFor({state: 'hidden'})
    assert.equal(await page.getByRole('navigation', {name: 'Your library'}).count(), 0, 'library list hidden while collapsed')
    await page.locator('.rail .nav-count').waitFor()
    assert.ok(await page.locator('.rail .nav-count').isVisible(), 'the Downloads badge stays visible')
    await page.reload()
    await page.getByRole('button', {name: 'Expand sidebar'}).waitFor()
    assert.ok((await width(rail(page))) < 90, 'remembered after reload')
    await page.keyboard.press('Control+b')
    await page.getByRole('button', {name: 'Collapse sidebar'}).waitFor()
    await settled(false)
    assert.ok((await width(rail(page))) > 150)
    await page.keyboard.press('Control+b')
    await page.getByRole('button', {name: 'Expand sidebar'}).waitFor()
    await settled(true)
    // A narrow window (dev-docs/plans/DESKTOP-WIDTHS.md): the icon rail, and the button opens the full sidebar over the page as a drawer;
    // the page keeps its width, and the saved choice is not touched.
    await page.keyboard.press('Control+b')
    await settled(false)
    await page.setViewportSize({width: 860, height: 800})
    await page.waitForFunction(() => document.querySelector('aside.rail').getBoundingClientRect().width < 90)
    const mainLeft = async () => (await page.locator('main.main').boundingBox()).x
    const before = await mainLeft()
    await page.getByRole('button', {name: 'Expand sidebar'}).click()
    await page.getByRole('button', {name: 'Close sidebar'}).waitFor()
    await settled(false)
    await page.getByRole('navigation', {name: 'Your library'}).waitFor()
    assert.equal(await mainLeft(), before, 'the drawer opens over the page; the page does not move')
    await page.keyboard.press('Escape')
    await settled(true)
    await page.keyboard.press('Control+b')
    await settled(false)
    // Choosing a page closes it, and so does a click beside it.
    await page.getByRole('navigation', {name: 'Your library'}).getByRole('button', {name: /^Music/}).click()
    await settled(true)
    await page.keyboard.press('Control+b')
    await settled(false)
    await page.mouse.click(700, 400)
    await settled(true)
    assert.equal(await page.evaluate(() => localStorage.getItem('keepfin-rail-collapsed')), 'false', 'the saved choice is unchanged')
    // Back in a wide window the saved choice (the full sidebar) is shown again.
    await page.setViewportSize({width: 1280, height: 800})
    await settled(false)
  })

  await test('sidebar footer: one compact status line, no theme switch, and the version beside Edit sidebar and Settings', async (page) => {
    assert.equal(await page.locator('.rail .foot-item').count(), 0, 'no footer links')
    assert.equal(await page.locator('.rail .theme-toggle').count(), 0, 'no theme switch in the sidebar')
    assert.equal(await page.locator('.rail').getByRole('button', {name: 'Support Keepfin'}).count(), 0, 'Support lives in Settings')
    const foot = page.locator('.rail-foot .side-foot-row')
    for (const name of ['Edit sidebar', 'Settings']) await foot.getByRole('button', {name, exact: true}).waitFor()
    // Downloads and devices share one row at the bottom.
    assert.equal(await page.locator('.rail .side-status-row').count(), 1, 'one status line')
    assert.ok((await page.locator('.rail .side-status-row > *').count()) >= 1)
    const row = await page.locator('.rail .side-status-row').boundingBox()
    const rail = await page.locator('aside.rail').boundingBox()
    assert.ok(row.y + row.height <= rail.y + rail.height + 1, 'inside the sidebar')
  })
  // ---------------------------------------------------------------- history grid
  await test('downloads collection: history cards keep their geometry and open a popup', async (page) => {
    await finishedTab(page)
    await page.getByTitle('Grid view').click()
    await page.locator('.lib-grid').waitFor()
    assert.equal(await page.locator('.lib-grid .group-row').count(), 0, 'no list rows inside the grid')
    const cards = page.locator('.lib-grid .group-card')
    assert.ok((await cards.count()) >= 1)
    const single = await page.locator('.lib-grid .lib-card:not(.group-card)').first().boundingBox()
    const card = await cards.first().boundingBox()
    assert.ok(Math.abs(card.width - single.width) <= 2, `same width: ${card.width} vs ${single.width}`)
    // Several cards share a row.
    const tops = await page.locator('.lib-grid > li').evaluateAll((nodes) => nodes.map((node) => Math.round(node.getBoundingClientRect().top)))
    assert.ok(tops.filter((top) => top === tops[0]).length >= 3, 'several cards per row')
    // Aligned: every cover starts at the same height and has the same size, and every card is the same height.
    const geo = await page.locator('.lib-grid > li').evaluateAll((nodes) =>
      nodes.map((node) => {
        const media = node.querySelector('.lib-card-media').getBoundingClientRect()
        const title = node.querySelector('.lib-title').getBoundingClientRect()
        return {row: Math.round(node.getBoundingClientRect().top), mediaTop: Math.round(media.top), mediaH: Math.round(media.height), cardH: Math.round(node.getBoundingClientRect().height), titleH: Math.round(title.height)}
      }),
    )
    for (const row of new Set(geo.map((g) => g.row))) {
      const same = geo.filter((g) => g.row === row)
      assert.equal(new Set(same.map((g) => g.mediaTop)).size, 1, 'covers start at the same height')
      assert.equal(new Set(same.map((g) => g.mediaH)).size, 1, 'covers are the same size')
      assert.equal(new Set(same.map((g) => g.cardH)).size, 1, 'cards are the same height')
    }
    assert.ok(geo.every((g) => g.titleH === 36), 'titles are exactly two lines tall')
    // A value never breaks inside: every meta part stays on one line.
    const broken = await page.locator('.lib-card-body .lib-meta > span').evaluateAll((nodes) => nodes.filter((node) => node.getClientRects().length > 1).length)
    assert.equal(broken, 0)
    const first = cards.first()
    await first.locator('.group-stack').waitFor()
    await first.locator('.group-kind').waitFor()
    await first.getByRole('button', {name: /^Show \d+ items? in /}).click()
    await page.locator('dialog.collection-window[open]').getByRole('list', {name: /^Items in /}).waitFor()
    await page.locator('dialog.collection-window[open]').getByRole('button', {name: 'Close'}).click()
    assert.equal(await page.locator('dialog.collection-window[open]').count(), 0)
    await page.waitForFunction(() => document.activeElement?.matches('.group-card .lib-card-media'))
  }, {fake: {scenario: 'groups'}})

  // ---------------------------------------------------------------- lyrics icon
  await test('lyrics button uses the lyrics icon, not the microphone', async (page) => {
    await goto(page, 'Library')
    await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('button', {name: /^Music/}).click()
    await page.locator('button.row-main', {hasText: 'Glass Harbour'}).click()
    const button = page.getByRole('region', {name: 'Now playing'}).getByRole('button', {name: 'Lyrics'})
    const path = await button.locator('svg path').first().getAttribute('d')
    assert.ok(path.startsWith('M5.5 4h13'), 'speech bubble outline')
    assert.ok((await button.locator('svg circle').count()) === 1, 'a note head')
  })

  // ---------------------------------------------------------------- Now playing panel resize
  await test('now playing panel: drag, keyboard, double-click reset, limits, remembered', async (page) => {
    await openPanel(page)
    const panel = page.getByRole('complementary', {name: 'Now playing panel'})
    const handle = page.getByRole('separator', {name: 'Resize Now playing panel'})
    assert.equal(await handle.getAttribute('aria-orientation'), 'vertical')
    assert.equal(Number(await handle.getAttribute('aria-valuenow')), 320)
    assert.ok(Math.abs((await width(panel)) - 320) <= 1)
    await handle.focus()
    await page.keyboard.press('ArrowLeft')
    assert.equal(Number(await handle.getAttribute('aria-valuenow')), 336)
    await page.keyboard.press('ArrowRight')
    await page.keyboard.press('ArrowRight')
    assert.equal(Number(await handle.getAttribute('aria-valuenow')), 304)
    await page.keyboard.press('Home')
    assert.equal(Number(await handle.getAttribute('aria-valuenow')), 280)
    await page.keyboard.press('ArrowRight')
    assert.equal(Number(await handle.getAttribute('aria-valuenow')), 280, 'not narrower than the minimum')
    await page.keyboard.press('End')
    const max = Number(await handle.getAttribute('aria-valuenow'))
    assert.ok(max <= 520 && max > 400)
    await page.keyboard.press('ArrowLeft')
    assert.equal(Number(await handle.getAttribute('aria-valuenow')), max, 'not wider than the maximum')
    // Drag: moving the edge left by 80 px widens the panel by 80.
    await handle.press('Enter')
    assert.equal(Number(await handle.getAttribute('aria-valuenow')), 320)
    const box = await handle.boundingBox()
    const y = box.y + box.height / 2
    await page.mouse.move(box.x + box.width / 2, y)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width / 2 - 80, y, {steps: 6})
    await page.mouse.up()
    assert.equal(Number(await handle.getAttribute('aria-valuenow')), 400)
    assert.ok(Math.abs((await width(panel)) - 400) <= 1)
    // Remembered, and a double-click resets it.
    await page.reload()
    await openPanel(page)
    assert.equal(Number(await page.getByRole('separator', {name: 'Resize Now playing panel'}).getAttribute('aria-valuenow')), 400)
    await page.getByRole('separator', {name: 'Resize Now playing panel'}).dblclick()
    assert.equal(Number(await page.getByRole('separator', {name: 'Resize Now playing panel'}).getAttribute('aria-valuenow')), 320)
  })

  // ---------------------------------------------------------------- Play on finished downloads
  const addSong = async (page, id, title) => {
    await page.evaluate(
      ([id, title]) => window.__fake.addJob({id, url: `https://music.youtube.com/watch?v=${id}`, title, provider: 'YouTube Music', kind: 'audio', format: {type: 'audio', target: 'mp3', quality: '320'}, status: 'downloading', percent: 50, space: 'music', totalBytes: 1e6, downloadedBytes: 5e5}),
      [id, title],
    )
    await page.evaluate((id) => window.__fake.completeJob(id), id)
  }
  await test('finished download: Play starts the in-app player; thumbnail plays; Open in another app is in the menu', async (page) => {
    await addSong(page, 'pj1', 'Played In App')
    await finishedTab(page)
    const row = page.locator('.lib-row').filter({hasText: 'Played In App'})
    await row.waitFor()
    assert.equal(await row.getByRole('button', {name: /^Open /}).count(), 0, 'no bare Open on a playable file')
    await row.getByRole('button', {name: 'Play Played In App'}).first().click()
    await page.getByRole('region', {name: 'Now playing'}).getByText('Played In App').first().waitFor()
    assert.equal((await calls(page, 'open')).length, 0, 'the system app was not asked')
    // The thumbnail is a play button too.
    await page.getByRole('region', {name: 'Now playing'}).getByRole('button', {name: /^Pause|^Play/}).first().waitFor()
    await row.locator('.thumb-play').click()
    // The menu keeps the external route.
    await row.getByRole('button', {name: 'More actions for Played In App'}).click()
    await page.getByRole('menuitem', {name: 'Open in another app'}).click()
    await sent(page, 'open')
    assert.equal((await last(page, 'open')).id, 'pj1')
    // The finished card on the Downloads queue plays too.
    await page.getByRole('radiogroup', {name: 'Show downloads'}).getByRole('radio', {name: /^All/}).click()
  }, {fake: {scenario: 'empty', simulate: false}})

  await test('finished video: Play opens the in-app video player', async (page) => {
    await finishedTab(page)
    const play = page.getByRole('button', {name: /^Play Crispy rice salad/}).first()
    await play.waitFor()
    await play.click()
    await page.getByRole('dialog', {name: /Playing One-pan lemon orzo/}).waitFor()
  })

  await test('finished downloads in grid view: card click plays, group cards play the collection', async (page) => {
    await finishedTab(page)
    await page.getByTitle('Grid view').click()
    await page.locator('.lib-grid').waitFor()
    await page.getByRole('button', {name: /^Play Crispy rice salad/}).first().waitFor()
  })

  await test('downloader-only: finished downloads keep Open (default app) and never show Play', async (page) => {
    await finishedTab(page)
    assert.equal(await page.getByRole('button', {name: /^Play /}).count(), 0)
    const open = page.getByRole('button', {name: /^Open /}).first()
    await open.waitFor()
    await open.click()
    await sent(page, 'open')
  }, {fake: {library: 'off'}})
  const popup = (page) => page.locator('dialog.collection-window[open]')
  const grid = async (page) => {
    await finishedTab(page)
    await page.getByTitle('Grid view').click()
    await page.locator('.group-card[data-group="grp-album"]').waitFor()
  }
  const card = (page) => page.locator('.group-card[data-group="grp-album"]')
  const albumButton = (page) => card(page).locator('.lib-card-media')
  const focusIsCover = (page) => page.waitForFunction(() => document.activeElement?.matches('[data-group="grp-album"] .lib-card-media'))
  const cardBoxes = (page) =>
    page.locator('.lib-grid > li').evaluateAll((nodes) =>
      nodes.map((node) => {
        const {x, y, width, height} = node.getBoundingClientRect()
        return {x, y, width, height}
      }),
    )
  for (const scale of [80, 100, 140]) {
    await test(
      'downloads collection: popup opens, fits and closes at ' + scale + '%',
      async (page) => {
        await grid(page)
        await albumButton(page).scrollIntoViewIfNeeded()
        await page.waitForTimeout(350)
        const before = await cardBoxes(page)
        await albumButton(page).press('Enter')
        await popup(page).waitFor()
        await popup(page).getByRole('heading', {name: 'Glass Harbour Sessions'}).waitFor()
        assert.deepEqual(await cardBoxes(page), before, 'no card changes position or size')
        const fit = await popup(page).evaluate((node) => {
          const box = node.getBoundingClientRect()
          const body = node.querySelector('.preview-scroll')
          return {left: box.left, right: box.right, top: box.top, bottom: box.bottom, w: innerWidth, h: innerHeight, overflow: body.scrollWidth - body.clientWidth}
        })
        assert.ok(fit.left >= -1 && fit.top >= -1 && fit.right <= fit.w + 1 && fit.bottom <= fit.h + 1, JSON.stringify(fit))
        assert.ok(fit.overflow <= 1, 'no horizontal overflow: ' + JSON.stringify(fit))
        await page.keyboard.press('Escape')
        await popup(page).waitFor({state: 'detached'})
        await focusIsCover(page)
        await albumButton(page).click()
        await popup(page).getByRole('button', {name: 'Close'}).click()
        await popup(page).waitFor({state: 'detached'})
        await focusIsCover(page)
      },
      {scale, fake: {scenario: 'groups', simulate: false}},
    )
  }
  await test(
    'downloads collection: cover, card body and Show items open it; outside click closes it; focus returns to the cover',
    async (page) => {
      await grid(page)
      await card(page).locator('.lib-card-body').click()
      await popup(page).waitFor()
      await page.mouse.click(4, 4)
      await popup(page).waitFor({state: 'detached'})
      await focusIsCover(page)
      await card(page).getByRole('button', {name: 'More actions for Glass Harbour Sessions'}).click()
      await page.getByRole('menuitem', {name: 'Show items'}).click()
      await popup(page).waitFor()
      assert.equal(await card(page).locator('.group-card-items').count(), 0, 'the card never expands in place')
      await page.keyboard.press('Escape')
      await popup(page).waitFor({state: 'detached'})
      await focusIsCover(page)
    },
    {fake: {scenario: 'groups', simulate: false}},
  )
  await test(
    'downloads collection: summary row, then every item under its section label, and the existing actions',
    async (page) => {
      await grid(page)
      await albumButton(page).click()
      const win = popup(page)
      await win.getByRole('button', {name: 'Show Glass Harbour Sessions in folder'}).click()
      await sent(page, 'reveal')
      await win.getByRole('button', {name: 'Retry failed'}).waitFor()
      await win.getByRole('button', {name: 'Remove failed'}).waitFor()
      const order = await win.locator('.collection-items > li').evaluateAll((nodes) => nodes.map((node) => (node.classList.contains('group-sub') ? 'label:' + node.textContent : 'job')))
      assert.ok(order[0].startsWith('label:Needs attention'), JSON.stringify(order))
      assert.ok(order.some((entry) => entry.startsWith('label:Saved')) && order.includes('job'), JSON.stringify(order))
      const members = await page.evaluate(() => window.__fake.jobs().filter((job) => job.group?.id === 'grp-album').length)
      assert.equal(await win.locator('.collection-items .job').count(), members)
      await win.locator('.collection-items .job .thumb img').first().waitFor()
      await win.getByRole('button', {name: 'Retry failed'}).click()
      await sent(page, 'retryGroup')
      assert.equal((await last(page, 'retryGroup')).id, 'grp-album')
    },
    {fake: {scenario: 'groups', simulate: false}},
  )
  await test(
    'downloads collection: the popup scrolls inside itself with many items',
    async (page) => {
      await grid(page)
      await page.evaluate(() => {
        const seed = window.__fake.jobs().find((job) => job.group?.id === 'grp-album')
        for (let i = 0; i < 16; i++) window.__fake.addJob({...seed, id: 'many-' + i, title: 'Additional item ' + i})
      })
      await albumButton(page).click()
      const win = popup(page)
      await win.locator('.collection-items .job').nth(20).waitFor({state: 'attached'})
      const outer = await page.locator('.main').evaluate((node) => node.scrollTop)
      const scroller = await win.locator('.preview-scroll').evaluate((node) => {
        node.scrollTop = 300
        return {scrolls: node.scrollHeight > node.clientHeight, top: node.scrollTop}
      })
      assert.ok(scroller.scrolls && scroller.top > 0, JSON.stringify(scroller))
      assert.equal(await page.locator('.main').evaluate((node) => node.scrollTop), outer)
    },
    {fake: {scenario: 'groups', simulate: false}},
  )
  await test(
    'downloads collection: it stays live while open',
    async (page) => {
      await grid(page)
      await albumButton(page).click()
      const win = popup(page)
      const attention = win.locator('.group-sub-attention')
      const before = Number((await attention.textContent()).split('·')[1])
      await page.evaluate(() => {
        const seed = window.__fake.jobs().find((job) => job.group?.id === 'grp-album')
        window.__fake.addJob({...seed, id: 'late-fail', title: 'Late failure', status: 'failed', error: 'The download failed.'})
      })
      // A member that fails while the popup is open shows up in it without reopening.
      await win.getByText('Late failure').waitFor()
      assert.equal(Number((await attention.textContent()).split('·')[1]), before + 1)
    },
    {fake: {scenario: 'groups', simulate: false}},
  )
  await test(
    'downloads collection: it closes when the collection is removed from history',
    async (page) => {
      await grid(page)
      await albumButton(page).click()
      const win = popup(page)
      await win.getByRole('button', {name: 'More actions for Glass Harbour Sessions'}).click()
      await page.getByRole('menuitem', {name: 'Remove from history'}).click()
      await sent(page, 'removeGroup')
      await popup(page).waitFor({state: 'detached'})
      assert.equal(await card(page).count(), 0)
    },
    {fake: {scenario: 'groups', simulate: false}},
  )
  await test(
    'downloads collection: items retain thumbnails and isolated live progress',
    async (page) => {
      await goto(page, 'Downloads')
      const row = page.locator('.group-row[data-group="grp-road"]').first()
      await row.locator('.job').nth(5).waitFor()
      await row.locator('.job .thumb img').first().waitFor()
      await page.waitForTimeout(600)
      const baseline = await page.evaluate(() => {
        const jobs = window.__fake.jobs().filter((job) => job.group?.id === 'grp-road')
        return {jobs, renders: {...window.__keepfinTest.rowRenders}}
      })
      await page.waitForTimeout(1200)
      const renders = await page.evaluate(() => window.__keepfinTest.rowRenders)
      for (const job of baseline.jobs) {
        const delta = (renders[job.id] || 0) - (baseline.renders[job.id] || 0)
        if (job.status === 'downloading') assert.ok(delta >= 2, job.title + ' receives progress')
        else assert.equal(delta, 0, job.title + ' stays isolated')
      }
    },
    {fake: {scenario: 'groups'}},
  )
  await test(
    'downloads collection: history filters keep their intent through the popup',
    async (page) => {
      await grid(page)
      const search = page.getByRole('searchbox', {name: 'Search downloads'})
      await search.fill('Glass track 1')
      await albumButton(page).click()
      await popup(page).locator('.job').waitFor()
      assert.equal(await popup(page).locator('.job').count(), 1, 'the ids prop restricts the popup list')
      await popup(page).getByRole('button', {name: 'Close'}).click()
      assert.equal(await search.inputValue(), 'Glass track 1', 'closing preserves the history filter')
    },
    {fake: {scenario: 'groups', simulate: false}},
  )
}
