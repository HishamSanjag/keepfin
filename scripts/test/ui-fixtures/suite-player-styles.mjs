// The full player's three styles (Stage, Cinema, Afterglow) and the picture in place (dev-docs/plans/FULL-PLAYER.md). Registered by
// smoke-ui-flows.mjs. The fixtures play real tiny files, so "keeps playing" and "no reload" are checked on the real media element.
import assert from 'node:assert/strict'

export async function registerPlayerStylesSuite({test, calls}) {
  const mainNav = (page) => page.getByRole('navigation', {name: 'Main navigation'})
  const openMusic = async (page) => {
    await mainNav(page).getByRole('button', {name: 'Library'}).click()
    await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('button', {name: /^Music/}).click()
  }
  const song = (page, title) => page.getByRole('button', {name: new RegExp('^' + title)})
  const bar = (page) => page.getByRole('region', {name: 'Now playing'})
  const full = (page) => page.getByRole('dialog', {name: 'Now playing'})
  /** Plays a song and opens the full player on it. */
  const openFull = async (page, title) => {
    await openMusic(page)
    await song(page, title).first().click()
    await bar(page).waitFor()
    await page.waitForFunction(() => window.__keepfinTest.audio().active.currentTime > 0.2)
    await bar(page).getByRole('button', {name: /Open the player/}).click()
    await full(page).waitFor()
  }
  /** The fixture clips are a few seconds long: Repeat one keeps them going for as long as a check takes. */
  const repeatOne = async (page) => {
    if (await full(page).getByRole('button', {name: 'Repeat one'}).count()) return
    await full(page).getByRole('button', {name: 'Repeat off'}).click()
    await full(page).getByRole('button', {name: 'Repeat all'}).click()
    await full(page).getByRole('button', {name: 'Repeat one'}).waitFor()
  }
  const media = (page) =>
    page.evaluate(() => {
      const el = window.__keepfinTest.audio().active
      return {inStage: !!el.closest('.cover-stage'), paused: el.paused, time: el.currentTime, src: el.getAttribute('src') || '', tag: el.tagName}
    })
  const stage = (page) => page.locator('.full-player .cover-stage')
  const pill = (page, title) => full(page).getByRole('radiogroup', {name: `Song or video for ${title}`})

  // ---------------------------------------------------------------- the styles
  await test('player style: the button lists three styles, switching changes the layout, and the choice survives a reload', async (page) => {
    await openFull(page, 'Glass Harbour')
    assert.equal(await page.locator('.full-player.layout-stage').count(), 1, 'Stage is the default')
    await full(page).getByRole('button', {name: 'Player style'}).click()
    const menu = page.getByRole('menu', {name: 'Player style'})
    const items = menu.getByRole('menuitemradio')
    assert.equal(await items.count(), 3)
    const text = (await items.allTextContents()).map((entry) => entry.replace(/\s+/g, ' ').trim())
    assert.match(text[0], /^Stage.*cover/)
    assert.match(text[1], /^Cinema.*wide stage/)
    assert.match(text[2], /^Afterglow.*glow/)
    assert.equal(await items.nth(0).getAttribute('aria-checked'), 'true')
    assert.equal(await items.nth(1).getAttribute('aria-checked'), 'false')
    await items.nth(1).click()
    await page.locator('.full-player.layout-cinema').waitFor()
    await page.locator('.cinema-side').waitFor()
    // the new layout has the button too, and keyboard focus stays on it
    await page.waitForFunction(() => document.activeElement?.getAttribute('aria-label') === 'Player style')
    // the playback went on through the swap
    assert.equal((await media(page)).paused, false)
    await full(page).getByRole('button', {name: 'Player style'}).click()
    await page.getByRole('menu', {name: 'Player style'}).getByRole('menuitemradio').nth(2).click()
    await page.locator('.full-player.layout-afterglow').waitFor()
    await full(page).getByRole('button', {name: 'Collapse player'}).click()
    await full(page).waitFor({state: 'detached'})
    await page.waitForTimeout(500)
    await page.reload()
    await bar(page).waitFor()
    await bar(page).getByRole('button', {name: /Open the player/}).click()
    await full(page).waitFor()
    await page.locator('.full-player.layout-afterglow').waitFor()
    assert.equal(await page.evaluate(() => localStorage.getItem('keepfin-player-layout')), '"afterglow"')
  })

  await test('player style: a saved style that does not exist falls back to Stage', async (page) => {
    await page.evaluate(() => localStorage.setItem('keepfin-player-layout', '"hologram"'))
    await page.reload()
    await openFull(page, 'Glass Harbour')
    await page.locator('.full-player.layout-stage').waitFor()
    await full(page).getByRole('button', {name: 'Player style'}).click()
    const items = page.getByRole('menu', {name: 'Player style'}).getByRole('menuitemradio')
    assert.equal(await items.nth(0).getAttribute('aria-checked'), 'true')
  })

  await test('player style: Settings > Appearance has the same choice', async (page) => {
    await page.locator('aside.rail').getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Appearance'}).click()
    const group = page.getByRole('radiogroup', {name: 'Player style'})
    await group.waitFor()
    assert.deepEqual((await group.getByRole('radio').allTextContents()).map((entry) => entry.trim()), ['Stage', 'Cinema', 'Afterglow'])
    assert.equal(await group.getByRole('radio', {name: 'Stage'}).getAttribute('aria-checked'), 'true')
    await group.getByRole('radio', {name: 'Cinema'}).click()
    await page.getByText(/current lyric over the picture/).waitFor()
    assert.equal(await page.evaluate(() => localStorage.getItem('keepfin-player-layout')), '"cinema"')
    await openFull(page, 'Glass Harbour')
    await page.locator('.full-player.layout-cinema').waitFor()
    // and the player's own menu shows it checked
    await full(page).getByRole('button', {name: 'Player style'}).click()
    assert.equal(await page.getByRole('menu', {name: 'Player style'}).getByRole('menuitemradio').nth(1).getAttribute('aria-checked'), 'true')
  })

  await test('player style: Stage lyrics are one block per row, never overlapping, with the current line larger and brighter', async (page) => {
    await openFull(page, 'Glass Harbour')
    await full(page).getByRole('button', {name: 'First line of the fixture lyrics'}).waitFor()
    await page.waitForFunction(() => document.querySelector('.layout-stage .lyric.active'))
    const boxes = await page.locator('.layout-stage .lyrics .lyric').evaluateAll((nodes) =>
      nodes.map((node) => {
        const r = node.getBoundingClientRect()
        return {top: r.top, bottom: r.bottom, display: getComputedStyle(node).display, size: parseFloat(getComputedStyle(node).fontSize), active: node.classList.contains('active')}
      }),
    )
    assert.ok(boxes.length >= 4)
    for (let i = 1; i < boxes.length; i++) assert.ok(boxes[i].top >= boxes[i - 1].bottom - 12, `line ${i} starts at ${boxes[i].top}, the one above ends at ${boxes[i - 1].bottom}`)
    assert.ok(boxes.every((box) => box.display === 'block' || box.display === 'grid' || box.display === 'flex'), 'each line is its own block')
    const lyricsBox = await page.locator('.layout-stage .lyrics').boundingBox()
    const active = await page.locator('.layout-stage .lyric.active').boundingBox()
    assert.ok(active.y >= lyricsBox.y - 2 && active.y + active.height <= lyricsBox.y + lyricsBox.height + 2, 'the current line is in view')
  })
  await test('player style: Cinema paints the current lyric line over the stage, and the side panel has Queue and Lyrics', async (page) => {
    await page.evaluate(() => localStorage.setItem('keepfin-player-layout', '"cinema"'))
    await page.reload()
    await openFull(page, 'Glass Harbour')
    const line = page.locator('.cinema-stage .cinema-line')
    await line.waitFor()
    assert.match(await line.textContent(), /line/)
    const frame = await page.locator('.cinema-frame').boundingBox()
    assert.ok(Math.abs(frame.width / frame.height - 16 / 9) < 0.03, `the stage is 16:9, got ${frame.width}x${frame.height}`)
    const side = page.locator('.cinema-side')
    await side.getByRole('tab', {name: 'Lyrics'}).waitFor()
    await side.getByRole('button', {name: 'First line of the fixture lyrics'}).waitFor()
    await side.getByRole('tab', {name: 'Queue'}).click()
    assert.equal(await side.getByRole('list', {name: 'Play queue'}).count(), 1)  })

  await test('player style: Afterglow has a glow, karaoke lyrics, and a visualizer that reads the audio graph', async (page) => {
    await page.evaluate(() => localStorage.setItem('keepfin-player-layout', '"afterglow"'))
    await page.reload()
    await openFull(page, 'Glass Harbour')
    await page.locator('.afterglow-glow').waitFor({state: 'attached'})
    await full(page).getByRole('button', {name: 'First line of the fixture lyrics'}).waitFor()
    assert.equal(await page.locator('.shelf-song').count(), 0, 'the small Up next shelf is gone')
    const canvas = page.locator('canvas.visualizer')
    await canvas.waitFor()
    await page.waitForFunction(() => document.querySelector('canvas.visualizer')?.dataset.visualizer === 'live')
    await page.waitForTimeout(300)
    const painted = await canvas.evaluate((el) => {
      const data = el.getContext('2d').getImageData(0, 0, el.width, el.height).data
      let n = 0
      for (let i = 3; i < data.length; i += 4) if (data[i] > 0) n++
      return n
    })
    assert.ok(painted > 50, `bars are drawn (${painted} pixels)`)
    // the sound runs through the audio graph (the analyser) while the visualizer is on screen
    assert.equal(await page.evaluate(() => typeof AudioContext), 'function')
  })

  await test('player style: under reduced motion Afterglow shows still bars and the glow does not drift', async (page) => {
    await page.evaluate(() => localStorage.setItem('keepfin-player-layout', '"afterglow"'))
    await page.reload()
    await openFull(page, 'Glass Harbour')
    await page.locator('canvas.visualizer').waitFor()
    assert.equal(await page.locator('canvas.visualizer').getAttribute('data-visualizer'), 'still')
    const animation = await page.locator('.afterglow-glow').evaluate((el) => getComputedStyle(el, '::before').animationName)
    assert.equal(animation, 'none')
  }, {reducedMotion: true})

  // ---------------------------------------------------------------- the picture in place
  await test('picture in place: Video on a video kept as a song plays the same file in the cover\'s place, with no reload and no jump; Song brings the cover back', async (page) => {
    await openFull(page, 'Night Swim')
    await repeatOne(page)
    await page.evaluate(() => {
      const {active, idle} = window.__keepfinTest.audio()
      window.__loads = 0
      for (const el of [active, idle]) el.addEventListener('loadstart', () => window.__loads++)
      active.currentTime = 0.5
    })
    const before = await media(page)
    assert.equal(before.inStage, false, 'the cover shows, the element waits in its holder')
    assert.equal(await stage(page).evaluate((el) => el.classList.contains('is-picture')), false)
    assert.equal(await pill(page, 'Night Swim').getByRole('radio', {name: 'Song'}).getAttribute('aria-checked'), 'true')
    await pill(page, 'Night Swim').getByRole('radio', {name: 'Video'}).click()
    await page.locator('.full-player .cover-stage.is-picture').waitFor()
    const video = page.locator('.full-player .cover-media video')
    await video.waitFor()
    const now = await media(page)
    assert.equal(now.inStage, true, 'the element that plays sits inside the stage')
    assert.equal(now.tag, 'VIDEO')
    assert.equal(now.paused, false, 'it kept playing')
    assert.equal(now.src, before.src, 'the same source')
    assert.ok(now.time >= before.time - 0.05, `no jump back in time (${before.time} to ${now.time})`)
    const box = await stage(page).boundingBox()
    const picture = await video.boundingBox()
    assert.ok(Math.abs(picture.width - box.width) < 2 && Math.abs(picture.height - box.height) < 2, 'the picture fills the cover\'s place')
    await page.waitForFunction((t) => window.__keepfinTest.audio().active.currentTime > t + 0.3, now.time)
    // Song: the cover is back, the sound goes on, still no reload
    await pill(page, 'Night Swim').getByRole('radio', {name: 'Song'}).click()
    await page.waitForFunction(() => !document.querySelector('.full-player .cover-stage.is-picture'))
    const back = await media(page)
    assert.equal(back.inStage, false)
    assert.equal(back.paused, false)
    assert.equal(back.src, before.src)
    assert.equal(await page.evaluate(() => window.__loads), 0, 'a video kept as a song never reloads')
    // no second play counted and no video screen: the music player kept the song
    assert.equal(await page.getByRole('dialog', {name: /^Playing Night Swim/}).count(), 0)
  }, {fake: {videoSong: true}})

  await test('picture in place: full screen enters and leaves (button, Back to the player, double-click, Esc) with the same element playing', async (page) => {
    await openFull(page, 'Night Swim')
    await repeatOne(page)
    await pill(page, 'Night Swim').getByRole('radio', {name: 'Video'}).click()
    await page.locator('.full-player .cover-stage.is-picture').waitFor()
    const inFull = () => page.evaluate(() => ({el: document.fullscreenElement?.className || null, holds: !!document.fullscreenElement?.contains(window.__keepfinTest.audio().active), paused: window.__keepfinTest.audio().active.paused}))
    await full(page).getByRole('button', {name: 'Full screen'}).click()
    await page.waitForFunction(() => document.fullscreenElement?.classList.contains('cover-stage'))
    let state = await inFull()
    assert.equal(state.holds, true, 'the same element fills the screen')
    assert.equal(state.paused, false)
    const back = page.getByRole('button', {name: 'Back to the player'})
    await back.waitFor()
    const at = await page.evaluate(() => window.__keepfinTest.audio().active.currentTime)
    await back.click()
    await page.waitForFunction(() => document.fullscreenElement === null)
    assert.equal(await full(page).count(), 1, 'back in the player')
    assert.ok((await media(page)).time >= at - 0.1, 'no jump in time')
    // double-click on the picture
    await page.locator('.full-player .cover-media').dblclick()
    await page.waitForFunction(() => document.fullscreenElement?.classList.contains('cover-stage'))
    // Esc leaves full screen only, never the player
    await page.keyboard.press('Escape')
    await page.waitForFunction(() => document.fullscreenElement === null)
    await page.waitForTimeout(300)
    assert.equal(await full(page).count(), 1, 'Esc went back to the player, not out of it')
    assert.equal((await media(page)).inStage, true)
    // the pill belongs to the player, not to the full-screen picture
    await full(page).getByRole('button', {name: 'Full screen'}).click()
    await page.waitForFunction(() => document.fullscreenElement !== null)
    assert.equal(await page.locator('.cover-pill').count(), 0)
    await back.click()
  }, {fake: {videoSong: true}})

  await test('picture in place: a music video uses its own file; Song | Video go on from the same second; no second play is counted', async (page) => {
    await openFull(page, 'Cold Front')
    await repeatOne(page)
    await page.evaluate(() => (window.__keepfinTest.audio().active.currentTime = 1.5))
    await page.waitForFunction(() => window.__keepfinTest.audio().active.currentTime > 1.6)
    const songAt = (await media(page)).time
    await pill(page, 'Cold Front').getByRole('radio', {name: 'Video'}).click()
    await page.locator('.full-player .cover-stage.is-picture').waitFor()
    await page.waitForFunction(() => /\/video\/a-front$/.test(window.__keepfinTest.audio().active.getAttribute('src') || ''))
    // the picture goes on from the second the song was at
    await page.waitForFunction((from) => Math.abs(window.__keepfinTest.audio().active.currentTime - from) < 0.8, songAt)
    let state = await media(page)
    assert.equal(state.inStage, true)
    await page.waitForFunction(() => !window.__keepfinTest.audio().active.paused && window.__keepfinTest.audio().active.currentTime > 0.2)
    // seek the video, then go back to the song
    await page.evaluate(() => (window.__keepfinTest.audio().active.currentTime = 2.5))
    const plays = (await calls(page, 'libraryPlayback')).length
    await pill(page, 'Cold Front').getByRole('radio', {name: 'Song'}).click()
    await page.waitForFunction(() => /\/item\/a-front$/.test(window.__keepfinTest.audio().active.getAttribute('src') || ''))
    await page.waitForFunction(() => window.__keepfinTest.audio().active.currentTime >= 2.4)
    state = await media(page)
    assert.equal(state.inStage, false)
    assert.ok(state.time < 3.6, `the song carries on from the second the video reached (${state.time})`)
    assert.equal((await calls(page, 'libraryPlayback')).length, plays, 'leaving the music video reported no play of the song')
    // Video again: from the same second
    await pill(page, 'Cold Front').getByRole('radio', {name: 'Video'}).click()
    await page.waitForFunction(() => /\/video\/a-front$/.test(window.__keepfinTest.audio().active.getAttribute('src') || ''))
    await page.waitForFunction(() => window.__keepfinTest.audio().active.currentTime >= 2.3)
    // no separate video screen was ever opened
    assert.equal(await page.getByRole('dialog', {name: /^Playing /}).count(), 0)
  })

  await test('picture in place: Song | Video share one place, so a restart resumes at the second the video reached', async (page) => {
    await openFull(page, 'Cold Front')
    await repeatOne(page)
    await page.evaluate(() => (window.__keepfinTest.audio().active.currentTime = 1.5))
    await page.waitForFunction(() => window.__keepfinTest.audio().active.currentTime > 1.6)
    await pill(page, 'Cold Front').getByRole('radio', {name: 'Video'}).click()
    await page.waitForFunction(() => /\/video\/a-front$/.test(window.__keepfinTest.audio().active.getAttribute('src') || ''))
    await page.evaluate(() => (window.__keepfinTest.audio().active.currentTime = 2.8))
    await page.waitForFunction(() => window.__keepfinTest.audio().active.currentTime > 2.9)
    // the player keeps the place from its clock tick (120-250 ms), so a busy machine can leave the saved place one tick behind the video:
    // ask for the save until it has caught up with the second the video was set to, instead of reading it once
    const readSaved = () => {
      window.dispatchEvent(new Event('beforeunload'))
      return JSON.parse(localStorage.getItem('keepfin-player-v1') || '{}').position
    }
    await page.waitForFunction(() => {
      window.dispatchEvent(new Event('beforeunload'))
      return JSON.parse(localStorage.getItem('keepfin-player-v1') || '{}').position >= 2.8
    }, null, {polling: 50})
    const saved = await page.evaluate(readSaved)
    const videoAt = (await media(page)).time
    assert.ok(saved >= 2.8 && Math.abs(saved - videoAt) < 0.6, `the saved place is the shared one (video at ${videoAt}, saved ${saved})`)
  })

  await test('picture in place: choosing Song then Video at once ends on Video, never on the song file with the picture showing', async (page) => {
    await openFull(page, 'Cold Front')
    await repeatOne(page)
    await pill(page, 'Cold Front').getByRole('radio', {name: 'Video'}).click()
    await page.waitForFunction(() => /\/video\/a-front$/.test(window.__keepfinTest.audio().active.getAttribute('src') || ''))
    await page.evaluate(() => {
      const radios = document.querySelectorAll('[role=radiogroup] [role=radio]')
      const pick = (label) => [...radios].find((r) => r.textContent.trim() === label || r.getAttribute('aria-label') === label)?.click()
      pick('Song')
      pick('Video')
    })
    await page.waitForTimeout(700)
    const state = await media(page)
    assert.match(state.src, /\/video\/a-front$/, 'the video file stays')
    assert.equal(await stage(page).evaluate((el) => el.classList.contains('is-picture')), true)
    assert.equal(state.inStage, true)
    // and the other order ends on the song
    await page.evaluate(() => {
      const radios = document.querySelectorAll('[role=radiogroup] [role=radio]')
      const pick = (label) => [...radios].find((r) => r.textContent.trim() === label || r.getAttribute('aria-label') === label)?.click()
      pick('Song')
      pick('Video')
      pick('Song')
    })
    await page.waitForFunction(() => /\/item\/a-front$/.test(window.__keepfinTest.audio().active.getAttribute('src') || ''))
    await page.waitForTimeout(400)
    assert.equal(await stage(page).evaluate((el) => el.classList.contains('is-picture')), false)
  })

  await test('picture in place: Repeat one with the End of song sleep timer stops a music video at its end', async (page) => {
    await openFull(page, 'Cold Front')
    await repeatOne(page)
    await pill(page, 'Cold Front').getByRole('radio', {name: 'Video'}).click()
    await page.waitForFunction(() => /\/video\/a-front$/.test(window.__keepfinTest.audio().active.getAttribute('src') || ''))
    await full(page).getByRole('button', {name: 'Collapse player'}).click()
    await bar(page).getByRole('button', {name: /^More actions for/}).click()
    await page.getByRole('menuitemradio', {name: 'End of song'}).click()
    assert.equal(await page.evaluate(() => window.__keepfinTest.audio().active.loop), false, 'no native loop while the sleep is armed')
    await page.evaluate(() => {
      const el = window.__keepfinTest.audio().active
      el.currentTime = Math.max(0, el.duration - 0.4)
    })
    await page.getByText('Sleep timer: playback stopped at the end of the song.').waitFor({timeout: 6000})
    assert.equal((await media(page)).paused, true)
  })
  await test('picture in place: collapsing keeps a music video playing (no reload, no jump), expanding shows the picture again, and Song is the only way back to the song file', async (page) => {
    await openFull(page, 'Cold Front')
    await repeatOne(page)
    await pill(page, 'Cold Front').getByRole('radio', {name: 'Video'}).click()
    await page.locator('.full-player .cover-stage.is-picture').waitFor()
    await page.waitForFunction(() => /\/video\/a-front$/.test(window.__keepfinTest.audio().active.getAttribute('src') || ''))
    await page.waitForFunction(() => !window.__keepfinTest.audio().active.paused && window.__keepfinTest.audio().active.currentTime > 0.6)
    await page.evaluate(() => {
      const {active, idle} = window.__keepfinTest.audio()
      window.__loads = 0
      for (const el of [active, idle]) el.addEventListener('loadstart', () => window.__loads++)
      window.__before = active.currentTime
    })
    await full(page).getByRole('button', {name: 'Collapse player'}).click()
    await full(page).waitFor({state: 'detached'})
    await page.waitForTimeout(500)
    let now = await media(page)
    assert.match(now.src, /\/video\/a-front$/, 'still the music video file: the sound did not change')
    assert.equal(now.paused, false, 'still playing')
    assert.equal(now.inStage, false, 'only the picture went away')
    assert.ok(now.time >= (await page.evaluate(() => window.__before)) - 0.05, 'no jump back in time')
    assert.equal(await page.evaluate(() => window.__loads), 0, 'nothing reloaded on collapse')
    // expanding shows the picture in place again, still the same file
    await bar(page).getByRole('button', {name: /Open the player/}).click()
    await full(page).waitFor()
    await page.locator('.full-player .cover-stage.is-picture').waitFor()
    now = await media(page)
    assert.match(now.src, /\/video\/a-front$/)
    assert.equal(now.inStage, true)
    assert.equal(await page.evaluate(() => window.__loads), 0, 'nothing reloaded on expand either')
    // Song goes back to the song file
    await pill(page, 'Cold Front').getByRole('radio', {name: 'Song'}).click()
    await page.waitForFunction(() => /\/item\/a-front$/.test(window.__keepfinTest.audio().active.getAttribute('src') || ''))
    await full(page).getByRole('button', {name: 'Collapse player'}).click()
    await full(page).waitFor({state: 'detached'})
    assert.match((await media(page)).src, /\/item\/a-front$/, 'collapsing as a song stays on the song')
  })

  await test('picture in place: Video stays the choice for the next song with a picture; a song without one has no pill', async (page) => {
    await openFull(page, 'Cold Front')
    await repeatOne(page)
    await pill(page, 'Cold Front').getByRole('radio', {name: 'Video'}).click()
    await page.locator('.full-player .cover-stage.is-picture').waitFor()
    await full(page).getByRole('button', {name: 'Collapse player'}).click()
    await full(page).waitFor({state: 'detached'})
    // a song without a picture: no pill, no picture
    await song(page, 'Glass Harbour').first().click()
    await bar(page).getByRole('button', {name: /Open the player/}).click()
    await full(page).waitFor()
    assert.equal(await pill(page, 'Glass Harbour').count(), 0)
    assert.equal(await page.locator('.full-player .cover-stage.is-picture').count(), 0)
    await full(page).getByRole('button', {name: 'Collapse player'}).click()
    await full(page).waitFor({state: 'detached'})
    // the next song that has a picture starts with it
    await song(page, 'Cold Front').first().click()
    await bar(page).getByRole('button', {name: /Open the player/}).click()
    await full(page).waitFor()
    await page.locator('.full-player .cover-stage.is-picture').waitFor()
    assert.equal(await pill(page, 'Cold Front').getByRole('radio', {name: 'Video'}).getAttribute('aria-checked'), 'true')
    await page.waitForFunction(() => /\/video\/a-front$/.test(window.__keepfinTest.audio().active.getAttribute('src') || ''))
  })
  await test('picture in place: the mini bar has Show video (no Song | Video pill) and it opens the player with the picture', async (page) => {
    await openMusic(page)
    await song(page, 'Glass Harbour').first().click()
    await bar(page).waitFor()
    assert.equal(await bar(page).getByRole('button', {name: 'Show video'}).count(), 0, 'a song without a picture has no video button')
    await song(page, 'Night Swim').first().click()
    await page.waitForFunction(() => /Night Swim/.test(document.querySelector('[aria-label="Now playing"]')?.textContent || ''))
    assert.equal(await bar(page).getByRole('radiogroup', {name: /Song or video for/}).count(), 0, 'the pill left the mini bar')
    await bar(page).getByRole('button', {name: 'Show video'}).click()
    await full(page).waitFor()
    await page.locator('.full-player .cover-stage.is-picture').waitFor()
    assert.equal((await media(page)).inStage, true)
    assert.equal(await page.getByRole('dialog', {name: /^Playing /}).count(), 0, 'not the video screen')
    // the row menu's Watch video does the same for a video kept as a song that is not playing
    await full(page).getByRole('button', {name: 'Collapse player'}).click()
    await full(page).waitFor({state: 'detached'})
    await song(page, 'Glass Harbour').first().click()
    await page.waitForFunction(() => /Glass Harbour/.test(document.querySelector('[aria-label="Now playing"]')?.textContent || ''))
    await page.locator('.song-row', {has: song(page, 'Night Swim')}).click({button: 'right'})
    await page.getByRole('menuitem', {name: 'Watch video'}).click()
    await full(page).waitFor()
    await page.locator('.full-player .cover-stage.is-picture').waitFor()
    assert.equal((await media(page)).inStage, true)
  }, {fake: {videoSong: true}})

  await test('picture in place: the picture shows in Cinema and Afterglow too', async (page) => {
    for (const style of ['cinema', 'afterglow']) {
      await page.evaluate((id) => localStorage.setItem('keepfin-player-layout', JSON.stringify(id)), style)
      await page.reload()
      await openFull(page, 'Night Swim')
      await repeatOne(page)
      await pill(page, 'Night Swim').getByRole('radio', {name: 'Video'}).click()
      await page.locator('.full-player .cover-stage.is-picture .cover-media video').waitFor()
      assert.equal((await media(page)).inStage, true, style)
      await pill(page, 'Night Swim').getByRole('radio', {name: 'Song'}).click()
      await page.waitForFunction(() => !document.querySelector('.cover-stage.is-picture'))
    }
  }, {fake: {videoSong: true}})

  // ---------------------------------------------------------------- songs without lyrics, and Afterglow's Up next
  const selected = (page, name) => full(page).getByRole('tab', {name}).getAttribute('aria-selected')
  const layout = async (page, id) => {
    await page.evaluate((value) => localStorage.setItem('keepfin-player-layout', JSON.stringify(value)), id)
    await page.reload()
  }
  for (const style of ['stage', 'cinema']) {
    await test(`no lyrics (${style}): the side panel opens on the queue, and Lyrics comes back for a song with lyrics unless Queue was picked`, async (page) => {
      await layout(page, style)
      await openFull(page, 'Cold Front') // no lyrics
      await page.waitForFunction(() => document.querySelector('.full-panel [aria-label="Play queue"]'))
      assert.equal(await selected(page, 'Queue'), 'true')
      assert.equal(await full(page).getByText('No lyrics for this song').count(), 0, 'no empty half screen')
      // the Lyrics tab stays; its note shows only when it is picked
      await full(page).getByRole('tab', {name: 'Lyrics'}).click()
      await full(page).getByText('No lyrics for this song').waitFor()
      // not picked yet by the person for the queue: put the choice back to automatic by reloading, then go to a song with lyrics
      await page.reload()
      await openFull(page, 'Cold Front')
      await page.waitForFunction(() => document.querySelector('.full-panel [aria-label="Play queue"]'))
      await full(page).getByRole('list', {name: 'Play queue'}).getByRole('button', {name: /^Glass Harbour/}).click()
      await page.waitForFunction(() => /Glass Harbour/.test(document.querySelector('.full-player')?.textContent || ''))
      await full(page).getByRole('button', {name: 'First line of the fixture lyrics'}).waitFor()
      assert.equal(await selected(page, 'Lyrics'), 'true', 'a song with lyrics gets Lyrics back')
      // the person picks Queue: it holds for the next song with lyrics
      await full(page).getByRole('tab', {name: 'Queue'}).click()
      await full(page).getByRole('list', {name: 'Play queue'}).getByRole('button', {name: /^Fog Signals/}).click()
      await page.waitForFunction(() => /Fog Signals/.test(document.querySelector('.full-player')?.textContent || ''))
      await page.waitForTimeout(400)
      assert.equal(await selected(page, 'Queue'), 'true', 'Queue stays: the person picked it')
    })
  }

  await test('no lyrics (Afterglow): the centre area shows Up next', async (page) => {
    await layout(page, 'afterglow')
    await openFull(page, 'Cold Front')
    const view = full(page).getByRole('radiogroup', {name: 'Player view'})
    await view.waitFor()
    assert.equal(await view.getByRole('radio', {name: 'Up next'}).getAttribute('aria-checked'), 'true')
    await page.locator('.afterglow-queue').getByRole('list', {name: 'Play queue'}).waitFor()
    assert.equal(await page.locator('.afterglow-lyrics').count(), 0)
    assert.equal(await full(page).getByText('No lyrics for this song').count(), 0)
  })

  await test('Afterglow: a Lyrics | Up next control in the header; Up next fills the centre with the whole queue, current song marked, reorder and remove as everywhere', async (page) => {
    await layout(page, 'afterglow')
    await openFull(page, 'Glass Harbour')
    const view = full(page).getByRole('radiogroup', {name: 'Player view'})
    assert.equal(await view.getByRole('radio', {name: 'Lyrics'}).getAttribute('aria-checked'), 'true', 'Lyrics is the default for a song with lyrics')
    assert.equal(await page.locator('.shelf-song, .afterglow-shelf').count(), 0, 'no small shelf at the bottom')
    await view.getByRole('radio', {name: 'Up next'}).click()
    const list = page.locator('.afterglow-queue').getByRole('list', {name: 'Play queue'})
    await list.waitFor()
    assert.ok((await list.locator('li').count()) >= 10, 'the whole queue')
    assert.equal(await list.locator('li.is-current').count(), 1)
    assert.match(await list.locator('li.is-current').textContent(), /Glass Harbour/)
    assert.ok((await list.locator('li').first().boundingBox()).height >= 64, 'large rows')
    const box = await page.locator('.afterglow-queue').boundingBox()
    assert.ok(box.width > 500 && box.height > 400, `it fills the centre area (${box.width}x${box.height})`)
    // remove and reorder work as in the normal queue
    const before = await list.locator('li').count()
    await full(page).getByRole('button', {name: /Remove .* from the queue/}).last().click()
    assert.equal(await list.locator('li').count(), before - 1)
    const titles = () => list.locator('.row-title').allTextContents()
    const first = await titles()
    await list.locator('li').nth(1).locator('.drag-handle').dragTo(list.locator('li').nth(3))
    assert.equal((await titles())[3], first[1])
    // a row plays that song, and the control goes back to Lyrics
    await list.getByRole('button', {name: /^Fog Signals/}).click()
    await page.waitForFunction(() => document.querySelector('.afterglow-main h2')?.textContent === 'Fog Signals')
    await view.getByRole('radio', {name: 'Lyrics'}).click()
    await page.locator('.afterglow-lyrics').getByRole('button', {name: 'First line of the fixture lyrics'}).waitFor()
  })

  // ---------------------------------------------------------------- the mini bar
  const more = (page) => bar(page).getByRole('button', {name: /^More actions for/})
  const boxes = (page) =>
    page.evaluate(() => {
      const rect = (selector) => {
        const r = document.querySelector(selector)?.getBoundingClientRect()
        return r ? {x: r.left, y: r.top, right: r.right, bottom: r.bottom, w: r.width, h: r.height} : null
      }
      return {bar: rect('.now-bar'), rail: rect('aside.rail'), left: rect('.now-left'), center: rect('.now-center'), right: rect('.now-right'), main: rect('main.main'), vw: innerWidth, vh: innerHeight, pillText: document.querySelector('.video-pill-text') ? getComputedStyle(document.querySelector('.video-pill-text')).display : null}
    })
  const apart = (a, b) => a.right <= b.x + 1 || b.right <= a.x + 1 || a.bottom <= b.y + 1 || b.bottom <= a.y + 1

  await test('mini bar: a floating island; cover, title and heart left, transport and seek in the middle, only Video, Lyrics, Queue, volume, More and Close right', async (page) => {
    await openMusic(page)
    await song(page, 'Night Swim').first().click()
    await bar(page).waitFor()
    const g = await boxes(page)
    // beside the floating sidebar with its 8 px gutters
    assert.ok(Math.abs(g.bar.x - g.rail.right - 8) < 1.5 && Math.abs(g.vw - g.bar.right - 8) < 1.5, `8px from the sidebar and the window (${g.bar.x - g.rail.right}, ${g.vw - g.bar.right})`)
    assert.ok(Math.abs(g.vh - g.bar.bottom - 8) < 1.5, `8px from the bottom (${g.vh - g.bar.bottom})`)
    assert.ok(g.main.bottom <= g.bar.y + 1, 'the page is not hidden behind the bar')
    const look = await page.locator('.now-bar').evaluate((el) => {
      const s = getComputedStyle(el)
      return {radius: s.borderTopLeftRadius, bg: s.backgroundColor, image: s.backgroundImage, shadow: s.boxShadow, raised: getComputedStyle(document.documentElement).getPropertyValue('--raised').trim()}
    })
    assert.equal(look.radius, '20px')
    assert.equal(look.image, 'none', 'no artwork tint')
    assert.match(look.shadow, /inset/, 'a hairline edge')
    assert.equal(await page.locator('.now-bar.has-tint').count(), 0)
    // left
    const left = bar(page).locator('.now-left')
    await left.getByRole('button', {name: /^Open the player, Night Swim/}).waitFor()
    assert.equal(await left.locator('.now-chevron').count(), 1, 'a small chevron on the cover')
    assert.match(await left.locator('.now-title').textContent(), /Night Swim/)
    await left.getByRole('button', {name: /favorites/}).waitFor()
    // middle
    for (const name of ['Shuffle off', 'Previous song', 'Next song']) await bar(page).locator('.now-center').getByRole('button', {name}).waitFor()
    await bar(page).locator('.now-center').getByRole('slider', {name: 'Seek'}).waitFor()
    // right: nothing else
    const names = await bar(page).locator('.now-right button').evaluateAll((nodes) => nodes.map((node) => node.getAttribute('aria-label')))
    assert.deepEqual(names, ['Show video', 'Lyrics', 'Queue and Now playing', 'Mute', 'More actions for Night Swim', 'Close player'])
    assert.equal(await bar(page).locator('.video-pill').textContent().then((text) => text.trim()), 'Video', 'a labelled Video pill')
    // a song without a picture has no pill
    await song(page, 'Glass Harbour').first().click()
    await page.waitForFunction(() => /Glass Harbour/.test(document.querySelector('[aria-label="Now playing"]')?.textContent || ''))
    assert.equal(await bar(page).locator('.video-pill').count(), 0)
  }, {fake: {videoSong: true}, width: 1440, height: 900})

  await test('mini bar: More holds the equalizer, playback speed, sleep timer, the file format and Expand; Close is a button on the bar', async (page) => {
    await openMusic(page)
    await song(page, 'Night Swim').first().click()
    await bar(page).waitFor()
    await more(page).click()
    const menu = page.getByRole('menu', {name: /^More actions for/})
    await menu.getByRole('menuitem', {name: 'Equalizer', exact: true}).waitFor()
    await menu.getByText('Playback speed').waitFor()
    assert.equal(await menu.getByRole('group', {name: 'Speed'}).getByRole('menuitemradio').count(), 7)
    assert.equal(await menu.getByRole('group', {name: 'Sleep timer'}).getByRole('menuitemradio', {name: 'End of song'}).count(), 1)
    const format = menu.getByRole('menuitem', {name: /MP4 · 1080p/})
    assert.equal(await format.isDisabled(), true, 'the format is information, not a button')
    assert.equal(await menu.getByRole('menuitem', {name: 'Close player'}).count(), 0, 'closing is a button on the bar, not a menu item')
    // the song's own actions are still first
    await menu.getByRole('menuitem', {name: /^Play next/}).waitFor()
    // Expand player opens the full player
    await menu.getByRole('menuitem', {name: 'Expand player'}).click()
    await full(page).waitFor()
    await full(page).getByRole('button', {name: 'Collapse player'}).click()
    await full(page).waitFor({state: 'detached'})
    // Equalizer opens its dialog; speed and the sleep timer apply
    await more(page).click()
    await page.getByRole('menuitem', {name: 'Equalizer', exact: true}).click()
    await page.getByRole('dialog', {name: 'Equalizer'}).getByRole('button', {name: 'Done'}).click()
    await more(page).click()
    await page.getByRole('menuitemradio', {name: '1.5×'}).click()
    assert.equal(await page.evaluate(() => window.__keepfinTest.audio().active.playbackRate), 1.5)
    // The bar's own Close button stops and removes the bar
    await bar(page).getByRole('button', {name: 'Close player'}).click()
    await bar(page).waitFor({state: 'detached'})
  }, {fake: {videoSong: true}})

  for (const width of [1000, 860, 700, 560]) {
    await test(`mini bar: at ${width} px the groups never overlap, the bar stays 8 px inside the window beside the sidebar${width <= 1099 ? ' and the Video pill keeps only its icon' : ''}`, async (page) => {
      await openMusic(page)
      await song(page, 'Night Swim').first().click()
      await bar(page).waitFor()
      await page.waitForTimeout(200)
      const g = await boxes(page)
      assert.ok(g.bar.x >= g.rail.right + 7 && g.vw - g.bar.right >= 7 && g.vh - g.bar.bottom >= 7, 'inside the window, beside the sidebar')
      assert.ok(g.bar.right <= g.vw && g.bar.bottom <= g.vh)
      for (const [a, b] of [['left', 'center'], ['left', 'right'], ['center', 'right']]) assert.ok(apart(g[a], g[b]), `${a} and ${b} overlap: ${JSON.stringify([g[a], g[b]])}`)
      for (const key of ['left', 'center', 'right']) assert.ok(g[key].x >= g.bar.x - 1 && g[key].right <= g.bar.right + 1, `${key} stays inside the bar`)
      assert.equal(g.pillText, 'none', 'icon only')
      assert.equal(await bar(page).getByRole('button', {name: 'Show video'}).isVisible(), true)
      assert.equal(await more(page).isVisible(), true)
    }, {fake: {videoSong: true}, width, height: 700})
  }

  await test('mini bar: the light theme uses the raised light surface and dark text', async (page) => {
    await openMusic(page)
    await song(page, 'Glass Harbour').first().click()
    await bar(page).waitFor()
    const look = await page.evaluate(() => {
      const bar = document.querySelector('.now-bar')
      const parse = (value) => value.match(/[\d.]+/g).map(Number)
      return {bg: parse(getComputedStyle(bar).backgroundColor), text: parse(getComputedStyle(bar.querySelector('.now-title')).color)}
    })
    assert.ok(look.bg[0] > 220 && look.bg[1] > 220 && look.bg[2] > 220, `a light surface, got ${look.bg}`)
    assert.ok(look.text[0] < 60 && look.text[1] < 60, `dark text, got ${look.text}`)
  }, {theme: 'light'})
}