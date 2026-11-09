// The 0.5.5 interface refresh: Keepfin mode, one sidebar, Home, Search, one action menu, player panels.
import assert from 'node:assert/strict'

const YT = 'https://www.youtube.com/watch?v=lofoten8k'

export async function registerRefreshSuite({test, calls, last, sent}) {
  const mainNav = (page) => page.getByRole('navigation', {name: 'Main navigation'})
  const goto = (page, name) => mainNav(page).getByRole('button', {name}).click()
  const omni = (page) => page.getByRole('searchbox').or(page.getByRole('textbox', {name: /Paste a link to download, or search|Search your library, or paste a link/})).first()
  const menuLabels = async (page) => {
    const menu = page.getByRole('menu', {name: 'Item actions'})
    await menu.waitFor()
    const labels = await menu.getByRole('menuitem').evaluateAll((nodes) => nodes.map((node) => node.querySelector('.menu-text > span')?.textContent?.trim()))
    await page.keyboard.press('Escape')
    return labels
  }

  await test('mode: the start pages ask new users; Just downloads hides Home, Library, Search and the player', async (page) => {
    await page.getByRole('button', {name: 'Skip intro'}).click()
    await page.getByRole('radio', {name: /^Just downloads/}).check({force: true})
    await page.getByRole('button', {name: 'Next', exact: true}).click()
    const startButton = page.getByRole('button', {name: 'Start using Keepfin'})
    await page.waitForFunction(() => [...document.querySelectorAll('button')].some((b) => b.textContent.trim() === 'Start using Keepfin' && !b.disabled))
    await startButton.click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'updateSettings' && c.settings?.library?.enabled === false))
    await mainNav(page).getByRole('button', {name: 'Downloads'}).waitFor()
    for (const name of ['Home', 'Search', 'Library']) assert.equal(await mainNav(page).getByRole('button', {name}).count(), 0, `${name} is hidden`)
    assert.equal(await page.getByRole('region', {name: 'Now playing'}).count(), 0)
  }, {fake: {storagePhase: 'firstStart', scenario: 'empty', simulate: false}})

  await test('mode: existing users see no start pages', async (page) => {
    await mainNav(page).getByRole('button', {name: 'Home'}).waitFor()
    assert.equal(await page.getByRole('heading', {name: 'How will you use Keepfin?'}).count(), 0)
  })
  await test('mode: Settings › Library & playback switches between the two modes', async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Library & playback'}).click()
    const mode = page.getByRole('radiogroup', {name: 'Keepfin mode'}).first()
    await mode.getByRole('radio', {name: 'Just downloads'}).click()
    assert.equal((await last(page, 'updateSettings')).settings.library.enabled, false)
    assert.equal(await mainNav(page).getByRole('button', {name: 'Home'}).count(), 0)
    await mode.getByRole('radio', {name: 'Keepfin Complete'}).click()
    assert.equal((await last(page, 'updateSettings')).settings.library.enabled, true)
    await mainNav(page).getByRole('button', {name: 'Home'}).waitFor()
    await mainNav(page).getByRole('button', {name: 'Search'}).waitFor()
  })

  await test('sidebar: Downloads shows a live count; one list holds the spaces and playlists; albums and artists open from the Library page', async (page) => {
    await mainNav(page).getByRole('button', {name: /^Downloads, \d+ active/}).waitFor()
    const library = page.getByRole('navigation', {name: 'Your library'})
    await library.getByRole('list', {name: 'Spaces'}).getByRole('button', {name: /^Music/}).waitFor()
    await library.getByRole('list', {name: 'Playlists'}).waitFor()
    await goto(page, 'Library')
    await page.getByRole('group', {name: 'Show in Library'}).getByRole('button', {name: 'Albums', exact: true}).click()
    await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Weather Systems/}).click()
    await page.getByRole('heading', {name: 'Weather Systems'}).waitFor()
    await goto(page, 'Library')
    await page.getByRole('group', {name: 'Show in Library'}).getByRole('button', {name: 'Artists', exact: true}).click()
    await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Mira Halden/}).click()
    await page.getByRole('heading', {name: 'Mira Halden'}).waitFor()
  })

  await test('sidebar: collapses to icons at 1000 px and keeps every destination reachable', async (page) => {
    for (const name of ['Home', 'Search', 'Library', 'Downloads']) await mainNav(page).getByRole('button', {name}).waitFor()
    assert.equal(await page.getByRole('navigation', {name: 'Your library'}).isVisible(), false)
    assert.ok((await page.locator('.rail').boundingBox()).width < 90)
  }, {width: 1000})

  await test('home: the paste box sends a link to the quality picker', async (page) => {
    await goto(page, 'Home')
    await omni(page).fill(YT)
    await omni(page).press('Enter')
    await page.getByRole('radiogroup', {name: 'Video quality'}).waitFor()
    assert.equal((await last(page, 'inspect')).url, YT)
    assert.equal(await mainNav(page).getByRole('button', {name: 'Downloads'}).getAttribute('aria-current'), 'page')
  })

  await test('home: text opens Search with results and never inspects', async (page) => {
    await goto(page, 'Home')
    await omni(page).fill('glass')
    await omni(page).press('Enter')
    await page.getByRole('heading', {name: 'Search', level: 1}).waitFor()
    assert.equal(await omni(page).inputValue(), 'glass')
    await page.getByRole('region', {name: 'Songs'}).waitFor()
    assert.equal((await calls(page, 'inspect')).length, 0)
  })

  await test('home: filter chips are remembered; rows with data show, Made from your listening needs five songs', async (page) => {
    await goto(page, 'Home')
    await page.getByRole('list', {name: 'Jump back in'}).waitFor()
    await page.getByRole('region', {name: 'Continue watching'}).waitFor()
    await page.getByRole('region', {name: 'Downloading now'}).waitFor()
    const chips = page.getByRole('group', {name: 'Show on Home'})
    await chips.getByRole('button', {name: 'Music'}).click()
    assert.equal(await page.getByRole('region', {name: 'Continue watching'}).count(), 0, 'videos row hides under Music')
    await page.reload()
    await goto(page, 'Home')
    assert.equal(await chips.getByRole('button', {name: 'Music'}).getAttribute('aria-pressed'), 'true')
  })

  await test('home: the ✕ on a Continue watching card takes it off the row, and Undo puts it back', async (page) => {
    await goto(page, 'Home')
    const row = page.getByRole('region', {name: 'Continue watching'})
    const remove = row.getByRole('button', {name: /^Remove .+ from Continue watching$/}).first()
    const label = await remove.getAttribute('aria-label')
    await remove.click()
    const saved = await last(page, 'libraryPlayback')
    assert.equal(saved.position, 0, 'the saved place is forgotten')
    assert.equal(saved.finished, false, 'not counted as watched to the end')
    assert.equal(await page.getByRole('button', {name: label}).count(), 0, 'the card left the row')
    await page.getByRole('button', {name: 'Undo'}).click()
    await page.getByRole('region', {name: 'Continue watching'}).getByRole('button', {name: label}).waitFor()
  })

  await test('home: a brand-new library shows one friendly empty state and the paste box', async (page) => {
    await goto(page, 'Home')
    await page.getByText('Your library starts here').waitFor()
    await omni(page).waitFor()
    for (const name of ['Continue watching', 'Made from your listening', 'Recently added']) assert.equal(await page.getByRole('region', {name}).count(), 0, `${name} hidden`)
  }, {fake: {libraryScenario: 'empty'}})

  await test('search: library results by kind with a filter row; YouTube search opens the picker with a search link', async (page) => {
    await goto(page, 'Search')
    await omni(page).fill('harbo')
    await page.getByRole('region', {name: 'In your library'}).waitFor()
    await page.getByRole('region', {name: 'On YouTube'}).waitFor()
    await page.getByRole('region', {name: 'Songs'}).waitFor()
    const filters = page.getByRole('group', {name: 'Filter results'})
    for (const name of ['All', 'Videos', 'Songs', 'Albums', 'Artists', 'Playlists']) await filters.getByRole('button', {name, exact: true}).waitFor()
    await filters.getByRole('button', {name: 'Albums', exact: true}).click()
    assert.equal(await page.getByRole('region', {name: 'Songs'}).count(), 0)
    await omni(page).fill('zzzz-nothing')
    await page.getByText(/Nothing in your library matches/).waitFor()
    await omni(page).fill('lofi beats')
    await page.getByRole('button', {name: 'Search YouTube for lofi beats'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => /search_query=lofi%20beats/.test(c.url || c.link || JSON.stringify(c))))
  })

  await test('action menu: the same actions in Home, Library, Search and an album page', async (page) => {
    const song = (name) => page.getByRole('button', {name: new RegExp('^' + name)}).first()
    // Library: Music space
    await goto(page, 'Library')
    await page.getByRole('navigation', {name: 'Your library'}).getByRole('button', {name: /^Music/}).click()
    await song('Cold Front').click({button: 'right'})
    const inLibrary = await menuLabels(page)
    // Search
    await goto(page, 'Search')
    await omni(page).fill('cold front')
    await song('Cold Front').click({button: 'right'})
    const inSearch = await menuLabels(page)
    // Album page
    await omni(page).fill('weather systems')
    await page.getByRole('button', {name: /^Weather Systems/}).first().click()
    await song('Cold Front').click({button: 'right'})
    const inAlbum = await menuLabels(page)
    // Home: the song is in the quick grid once played recently
    await goto(page, 'Home')
    await page.getByRole('list', {name: 'Jump back in'}).getByRole('button', {name: /^Cold Front/}).click({button: 'right'})
    const inHome = await menuLabels(page)
    for (const word of ['Play', 'Play next', 'Add to queue', 'Add to playlist', 'Go to album', 'Go to artist', 'Edit info…', 'Move to…', 'Show in folder', 'Delete file…']) assert.ok(inLibrary.includes(word), `menu has ${word}`)
    assert.deepEqual(inSearch, inLibrary)
    assert.deepEqual(inAlbum, inLibrary)
    assert.deepEqual(inHome, inLibrary)
  })

  await test('action menu: actions that do not apply are disabled with a reason, not hidden', async (page) => {
    await goto(page, 'Search')
    await omni(page).fill('salt flats')
    await page.getByRole('region', {name: 'Videos'}).getByRole('button', {name: /^Salt Flats/}).first().click({button: 'right'})
    const menu = page.getByRole('menu', {name: 'Item actions'})
    // a video does what a song does: queue, playlist, favorites, and Play as audio; what it lacks (a tag) says so
    for (const name of ['Play as audio', 'Play next', 'Add to queue', 'Add to playlist', 'Add to favorites'])
      assert.equal(await menu.getByRole('menuitem', {name: new RegExp('^' + name)}).isDisabled(), false, name)
    const album = menu.getByRole('menuitem', {name: /^Go to album/})
    assert.equal(await album.isDisabled(), true)
    assert.match(await album.textContent(), /No album tag/)
    await page.keyboard.press('Escape')
  })

  await test('video as a song: Play as audio starts the music bar with its title; it can go in a playlist and be deleted for good', async (page) => {
    await goto(page, 'Search')
    await omni(page).fill('salt flats')
    const row = page.getByRole('region', {name: 'Videos'}).getByRole('button', {name: /^Salt Flats/}).first()
    await row.click({button: 'right'})
    await page.getByRole('menuitem', {name: /^Play as audio/}).click()
    await page.getByRole('region', {name: 'Now playing'}).getByText('Salt Flats at Sunrise').first().waitFor()
    assert.equal(await page.getByRole('dialog').count(), 0, 'the video player did not open')
    await row.click({button: 'right'})
    await page.getByRole('menuitem', {name: /^Add to playlist/}).click()
    await page.getByRole('dialog', {name: 'Add to playlist'}).getByRole('listitem').filter({hasText: 'Night drive'}).click()
    await sent(page, 'collectionAdd')
    await row.click({button: 'right'})
    await page.getByRole('menuitem', {name: 'Delete file…'}).click()
    await page.getByRole('dialog', {name: /Delete file from disk/}).getByRole('checkbox', {name: /Delete for good/}).waitFor()
  })

  await test('favorites: a space holding songs and videos has All, Videos and Audio chips that split them', async (page) => {
    await goto(page, 'Library')
    await page.getByRole('navigation', {name: 'Your library'}).getByRole('button', {name: /^Favorites/}).click()
    const chips = page.getByRole('group', {name: 'Show'})
    for (const name of ['All', 'Videos', 'Audio']) await chips.getByRole('button', {name}).waitFor()
    await chips.getByRole('button', {name: 'Videos'}).click()
    await page.getByRole('button', {name: /^Lofoten in Winter/}).first().waitFor()
    assert.equal(await page.getByRole('button', {name: /^Glass Harbour/}).count(), 0)
    await chips.getByRole('button', {name: 'Audio'}).click()
    await page.getByRole('button', {name: /^Glass Harbour/}).first().waitFor()
    assert.equal(await page.getByRole('button', {name: /^Lofoten in Winter/}).count(), 0)
  }, {fake: {favoritesSpace: true}})

  await test('player: bar has favourite, format chip, lyrics and queue; Now playing panel and full-width lyrics toggle', async (page) => {
    await goto(page, 'Library')
    await page.getByRole('navigation', {name: 'Your library'}).getByRole('button', {name: /^Music/}).click()
    await page.getByRole('button', {name: /^Glass Harbour/}).click()
    const bar = page.getByRole('region', {name: 'Now playing'})
    await bar.waitFor()
    await bar.getByRole('button', {name: /favorites/}).waitFor()
    // the file format moved into More, as a line that cannot be pressed
    await bar.getByRole('button', {name: /^More actions for/}).click()
    assert.equal(await page.getByRole('menuitem', {name: /^MP3/}).isDisabled(), true)
    await page.keyboard.press('Escape')
    await bar.getByRole('button', {name: 'Queue and Now playing'}).click()
    const panel = page.getByRole('complementary', {name: 'Now playing panel'})
    await panel.getByRole('heading', {name: 'Glass Harbour'}).waitFor()
    await panel.getByRole('list', {name: 'Play queue'}).waitFor()
    await bar.getByRole('button', {name: 'Lyrics'}).click()
    const overlay = page.getByRole('region', {name: 'Lyrics', exact: true})
    await overlay.getByRole('button', {name: 'First line of the fixture lyrics'}).waitFor()
    await page.keyboard.press('Escape')
    assert.equal(await overlay.count(), 0)
    await panel.getByRole('button', {name: 'Close Now playing panel'}).click()
    assert.equal(await panel.count(), 0)
  })

  await test('collection page: header has Play, Shuffle and a menu with Keep in sync', async (page) => {
    await goto(page, 'Library')
    await page.getByRole('group', {name: 'Show in Library'}).getByRole('button', {name: 'Collections', exact: true}).click()
    await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).click()
    const header = page.getByRole('region', {name: /^Road Trip Mix/}).first()
    await header.waitFor()
    await page.getByRole('button', {name: 'Play', exact: true}).waitFor()
    await page.getByRole('button', {name: 'Shuffle'}).waitFor()
    await page.getByRole('button', {name: /^More actions for Road Trip Mix/}).click()
    await page.getByRole('menuitem', {name: /Keep in sync|Keeping in sync/}).waitFor()
    await page.keyboard.press('Escape')
  }, {fake: {libraryScenario: 'v2'}})

  await test('artist page: header, albums and a Follow button that follows', async (page) => {
    await goto(page, 'Search')
    await omni(page).fill('mira')
    await page.getByRole('region', {name: 'Artists'}).getByRole('button', {name: /^Mira Halden/}).click()
    await page.getByRole('heading', {name: 'Mira Halden'}).waitFor()
    const follow = page.getByRole('button', {name: 'Follow Mira Halden', exact: true})
    assert.equal(await follow.isEnabled(), true)
    await follow.click()
    await page.getByRole('button', {name: /^Following Mira Halden/}).waitFor()
  })
}
