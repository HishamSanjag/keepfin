// Songs and their music videos (dev-docs/plans/MUSIC-VIDEOS.md, "Owner's rules (2026-10-31)"): Song | Video keeps the second, a song's video
// has a row of its own, a link you have becomes the song's video, and Find lyrics. Registered by smoke-ui-flows.mjs. The synthetic
// bridge plays tiny real files (a 4 s song, a 6 s video); it never certifies what the real engine does (the unit tests do).
import assert from 'node:assert/strict'

const OWN_LINK = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ'

export async function registerSongVideosSuite({test, calls, last, sent, capture}) {
  const keep = (page, name) => page.getByRole('radiogroup', {name: 'What to keep'}).getByRole('radio', {name, exact: true})
  const mainNav = (page) => page.getByRole('navigation', {name: 'Main navigation'})
  const openLibrary = (page) => mainNav(page).getByRole('button', {name: 'Library'}).click()
  // music videos moved from Library to Home (under All and Videos)
  const openHome = (page) => mainNav(page).getByRole('button', {name: 'Home'}).click()
  const openMusic = async (page) => {
    await openLibrary(page)
    await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('button', {name: /^Music/}).click()
  }
  const song = (page, title) => page.getByRole('button', {name: new RegExp('^' + title)})
  const bar = (page) => page.getByRole('region', {name: 'Now playing'})
  const active = (page) => page.evaluate(() => {
    const el = window.__keepfinTest.audio().active
    return {paused: el.paused, time: el.currentTime, src: el.getAttribute('src') || ''}
  })
  const shelf = (page) => page.getByRole('region', {name: 'Music videos'})

  // ---------------------------------------------------------------- Song | Video keeps the second
  await test('Song | Video: a switch goes on from the same second, and stops at the end of the shorter side', async (page) => {
    await openMusic(page)
    await song(page, 'Cold Front').click()
    await bar(page).waitFor()
    await page.waitForFunction(() => window.__keepfinTest.audio().active.currentTime > 0.5)
    await bar(page).getByRole('button', {name: 'Pause'}).click()
    const stopped = (await active(page)).time
    await bar(page).getByRole('button', {name: 'Show video'}).click()
    const view = page.getByRole('dialog', {name: 'Now playing'})
    await view.waitFor()
    await page.waitForFunction(() => /\/video\/a-front$/.test(window.__keepfinTest.audio().active.getAttribute('src') || ''))
    // the picture starts at the second the song was at, not at its own start
    await page.waitForFunction((from) => Math.abs(window.__keepfinTest.audio().active.currentTime - from) < 0.2, stopped)
    // the video (6 s) is ahead of the song (4 s): back to Song lands one second before the song's end, not at the video's place
    await page.evaluate(() => {
      window.__keepfinTest.audio().active.currentTime = 5
    })
    await view.getByRole('radiogroup', {name: 'Song or video for Cold Front'}).getByRole('radio', {name: 'Song'}).click()
    await page.waitForFunction(() => /\/item\/a-front$/.test(window.__keepfinTest.audio().active.getAttribute('src') || ''))
    await page.waitForFunction(() => Math.abs(window.__keepfinTest.audio().active.currentTime - 3) < 0.3)
    // no per-video place is kept any more
    assert.equal(await page.evaluate(() => localStorage.getItem('keepfin-music-video-positions')), null)
  })

  // ---------------------------------------------------------------- a video of its own
  await test('Music videos: the Home shelf, See all, a row that plays the video alone, and a menu of four things', async (page) => {
    await openHome(page)
    await shelf(page).waitFor()
    await shelf(page).getByText('Cold Front').waitFor()
    await shelf(page).getByText('Music video', {exact: true}).first().waitFor()
    // See all lists them as rows: cover, title, artist, length and the small label
    await shelf(page).getByRole('button', {name: 'See all Music videos'}).click()
    await page.getByRole('heading', {name: 'Music videos', level: 1}).waitFor()
    const row = page.locator('.mv-row', {hasText: 'Cold Front'})
    await row.getByText('The Overcast Choir').waitFor()
    await row.getByText('3:34').waitFor()
    await row.getByText('Music video', {exact: true}).waitFor()
    // the row's menu: Play, Go to song, Delete video, Show in folder
    await row.click({button: 'right'})
    for (const name of ['Play', 'Go to song', 'Delete video', 'Show in folder']) await page.getByRole('menuitem', {name}).waitFor()
    await page.getByRole('menuitem', {name: 'Show in folder'}).click()
    await sent(page, 'libraryReveal')
    const reveal = await last(page, 'libraryReveal')
    assert.equal(reveal.id, 'a-front')
    assert.equal(reveal.video, true, 'the video file, not the song')
    // Go to song opens the song's album
    await row.click({button: 'right'})
    await page.getByRole('menuitem', {name: 'Go to song'}).click()
    await page.getByRole('heading', {name: 'Weather Systems', level: 1}).waitFor()
    // clicking a row plays the video alone in the video player from its start: no song queue, no play counted
    await openHome(page)
    await shelf(page).getByRole('button', {name: /^Cold Front, Music video/}).click()
    const player = page.getByRole('dialog', {name: 'Playing Cold Front'})
    await player.waitFor()
    await page.waitForFunction(() => /\/video\/a-front$/.test(document.querySelector('.video-view video')?.getAttribute('src') || ''))
    assert.equal(await bar(page).count(), 0, 'not the song queue')
    await page.waitForTimeout(400)
    assert.equal((await calls(page, 'libraryPlayback')).length, 0, 'watching the video counts no play of the song')
  })

  await test('Music videos: Delete video asks first, removes only the video, and the shelf goes with it; the song stays', async (page) => {
    await openHome(page)
    await shelf(page).getByRole('button', {name: /^More actions for Cold Front/}).click()
    await page.getByRole('menuitem', {name: 'Delete video'}).click()
    const dialog = page.getByRole('dialog', {name: 'Delete this music video? The song stays.'})
    await dialog.getByRole('button', {name: 'Keep it'}).click()
    assert.equal((await calls(page, 'libraryRemoveMusicVideo')).length, 0)
    await shelf(page).getByRole('button', {name: /^More actions for Cold Front/}).click()
    await page.getByRole('menuitem', {name: 'Delete video'}).click()
    await page.getByRole('dialog', {name: 'Delete this music video? The song stays.'}).getByRole('button', {name: 'Remove video'}).click()
    await sent(page, 'libraryRemoveMusicVideo')
    assert.equal((await last(page, 'libraryRemoveMusicVideo')).id, 'a-front')
    await shelf(page).waitFor({state: 'detached'})
    // the song is still in Music
    await openMusic(page)
    await song(page, 'Cold Front').waitFor()
  })

  await test('Music videos: Search lists them with the videos, and Home has them under All and Videos, not Music', async (page) => {
    await mainNav(page).getByRole('button', {name: 'Search'}).click()
    await page.getByRole('searchbox').or(page.getByRole('textbox', {name: /Search your library, or paste a link/})).first().fill('cold front')
    const videos = page.getByRole('region', {name: 'Videos'})
    await videos.getByText('Cold Front').first().waitFor()
    await videos.getByText('Music video', {exact: true}).waitFor()
    // Home: All and Videos show the shelf, Music does not
    await mainNav(page).getByRole('button', {name: 'Home'}).click()
    const show = page.getByRole('group', {name: 'Show on Home'})
    await show.getByRole('button', {name: 'All'}).click()
    await shelf(page).getByText('Cold Front').waitFor()
    await show.getByRole('button', {name: 'Music'}).click()
    assert.equal(await shelf(page).count(), 0)
    await show.getByRole('button', {name: 'Videos'}).click()
    await shelf(page).getByText('Cold Front').waitFor()
  })

  // ---------------------------------------------------------------- a link you already have
  await test('paste of a song you have: the Video tab says the video will be added to it, and Download sends the video', async (page) => {
    await capture(page, OWN_LINK)
    await page.getByText('You have this song. This video will be added to it.').waitFor()
    assert.equal(await page.getByText(/Already in/).count(), 0, 'not offered as a duplicate: it joins the song')
    await page.locator('.download-btn').click()
    await sent(page, 'enqueue')
    assert.equal((await last(page, 'enqueue')).format.type, 'video')
  }, {fake: {captureSong: 'has'}})

  await test('paste of a song that already has its video: the line says so and Download explains in words', async (page) => {
    await capture(page, OWN_LINK)
    await page.getByText('You have this song and its video.').waitFor()
    await page.locator('.download-btn').click()
    await page.getByText('This song already has its music video.').waitFor()
  }, {fake: {captureSong: 'video'}})

  await test('paste of a link that is no song of yours: no line, an ordinary video download', async (page) => {
    await capture(page, OWN_LINK)
    assert.equal(await page.getByText(/You have this song/).count(), 0)
  })

  await test('a YouTube link with Both: Both keeps the link itself, searches nothing, and is never chosen for the person', async (page) => {
    await capture(page, OWN_LINK)
    assert.equal(await keep(page, 'Video').getAttribute('aria-checked'), 'true')
    assert.equal(await keep(page, 'Both').getAttribute('aria-checked'), 'false')
    await keep(page, 'Audio').click()
    assert.equal(await keep(page, 'Both').getAttribute('aria-checked'), 'false', 'Audio alone until Both is chosen')
    assert.equal((await calls(page, 'findMusicVideo')).length, 0, 'the link itself: nothing is searched')
    await keep(page, 'Both').click()
    await page.getByText('The song is saved with this video beside it.', {exact: true}).waitFor()
    await page.locator('.download-btn').click()
    await sent(page, 'enqueue')
    const sentJob = await last(page, 'enqueue')
    assert.equal(sentJob.format.type, 'audio')
    assert.equal(sentJob.videoId, 'dQw4w9WgXcQ', "the link's own id")
  })

  await test('a YouTube link on Audio, Both not chosen: the song downloads alone', async (page) => {
    await capture(page, OWN_LINK)
    await keep(page, 'Audio').click()
    await keep(page, 'Both').waitFor()
    await page.locator('.download-btn').click()
    await sent(page, 'enqueue')
    assert.equal((await last(page, 'enqueue')).videoId, undefined)
  })

  // ---------------------------------------------------------------- Find lyrics
  for (const [outcome, words] of [['none', 'No lyrics found'], ['busy', 'Could not reach the lyrics service. Try again later.'], ['found', 'Lyrics added.']]) {
    await test(`Find lyrics (${outcome}): the song's menu asks, and says ${outcome === 'found' ? 'the lyrics were added' : outcome === 'busy' ? 'the service could not be reached' : 'none were found'}`, async (page) => {
      await openMusic(page)
      await page.locator('.song-row', {has: song(page, 'Glass Harbour')}).click({button: 'right'})
      assert.equal(await page.getByRole('menuitem', {name: 'Find lyrics'}).count(), 0, 'a song with lyrics is not offered it')
      await page.keyboard.press('Escape')
      await page.locator('.song-row', {has: song(page, 'Cold Front')}).click({button: 'right'})
      await page.getByRole('menuitem', {name: 'Find lyrics'}).click()
      await page.getByText(words, {exact: true}).waitFor()
      assert.equal((await last(page, 'libraryFindLyrics')).id, 'a-front')
      await page.locator('.song-row', {has: song(page, 'Cold Front')}).click({button: 'right'})
      assert.equal(await page.getByRole('menuitem', {name: 'Find lyrics'}).count(), outcome === 'found' ? 0 : 1)
    }, {fake: {findLyrics: outcome}})
  }

  await test('Find lyrics: the lyrics panel of a song without lyrics has the button, and the lyrics appear when they are found', async (page) => {
    await openMusic(page)
    await song(page, 'Cold Front').click()
    await bar(page).waitFor()
    await bar(page).getByRole('button', {name: /Open the player/}).click()
    const full = page.getByRole('dialog', {name: 'Now playing'})
    await full.waitFor()
    await full.getByRole('tab', {name: 'Lyrics'}).click()
    await full.getByText(/No lyrics for this song/).waitFor()
    await full.getByRole('button', {name: 'Find lyrics'}).click()
    await full.getByText('First line of the fixture lyrics').waitFor()
    assert.equal(await full.getByText(/No lyrics for this song/).count(), 0)
  }, {fake: {findLyrics: 'found'}})

  await test('Find lyrics: in the panel, not found and unreachable read differently', async (page) => {
    await openMusic(page)
    await song(page, 'Cold Front').click()
    await bar(page).getByRole('button', {name: /Open the player/}).click()
    const full = page.getByRole('dialog', {name: 'Now playing'})
    await full.getByRole('tab', {name: 'Lyrics'}).click()
    await full.getByRole('button', {name: 'Find lyrics'}).click()
    await full.getByText('Could not reach the lyrics service. Try again later.').waitFor()
    assert.equal(await full.getByText('No lyrics found', {exact: true}).count(), 0)
    await full.getByRole('button', {name: 'Find lyrics'}).waitFor()
  }, {fake: {findLyrics: 'busy'}})
}
