// Layout and accessibility checks for every screen: no horizontal overflow at 420/1280 px and
// 80/140% scale in both themes, every control has an accessible name, no clipped dialogs.
// With SHOTS=<dir> it also writes screenshots (used for docs/screenshots/desktop-*.png).
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {startServer, launch, openApp, noHorizontalOverflow} from './ui-fixtures/harness.mjs'

const YT = 'https://www.youtube.com/watch?v=lofoten8k'
const MUSIC = 'https://music.youtube.com/watch?v=tidal88'
const SPOT_LIST = 'https://open.spotify.com/playlist/37i9dQZF1DX4sWSpwq3LiO'
const SPOT = 'https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC'
const SPOT_ALBUM = 'https://open.spotify.com/album/1A2B3C4D5E6F'
const YTM_SONG = 'https://music.youtube.com/watch?v=ytm1'
const OWN_LINK = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ'
const GALLERY = 'https://www.instagram.com/p/C9harbour/'
const PRIVATE = 'https://www.youtube.com/watch?v=private01'
const shots = process.env.DOWNLOADS_SHOTS || process.env.SHOTS
if (shots) fs.mkdirSync(shots, {recursive: true})

const field = (page) => page.locator('textarea').first()
// The sidebar's library list collapses to icons below 1100 px, so spaces are opened from the Library page's cards.
const space = async (page, name) => {
  await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('list', {name: 'Pinned spaces'}).getByRole('button', {name}).click()
}
const finishedList = async (page) => {
  await nav(page, 'Downloads')
  await page.getByRole('radio', {name: /^Finished/}).click()
}
const nav = (page, name) => (name === 'Settings' ? page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}) : page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name})).click()
const group = (page, name) => page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name}).click()

/** Screens: name, fake options, optional file name for screenshots, setup. */
const screens = [
  {
    // A collection in the Downloads grid opens in a popup (the same window as the download preview) over the unmoved cards.
    name: 'downloads-collection-popup',
    fake: {scenario: 'groups', simulate: false},
    setup: async (page) => {
      await finishedList(page)
      await page.getByTitle('Grid view').click()
      // Enough additional members to verify scrolling inside the popup at every viewport and scale.
      await page.evaluate(() => {
        const seed = window.__fake.jobs().find((job) => job.group?.id === 'grp-album')
        for (let i = 0; i < 16; i++)
          window.__fake.addJob({
            ...seed,
            id: 'layout-member-' + i,
            title: 'Additional item ' + i,
          })
      })
      const button = page.locator('.group-card[data-group="grp-album"] .lib-card-media')
      await button.scrollIntoViewIfNeeded()
      await page.waitForTimeout(350)
      const boxes = () =>
        page.locator('.lib-grid > li').evaluateAll((nodes) =>
          nodes.map((node) => {
            const {x, y, width, height} = node.getBoundingClientRect()
            return {x, y, width, height}
          }),
        )
      const before = await boxes()
      await button.click()
      const popup = page.locator('dialog.collection-window[open]')
      await popup.waitFor()
      assert.deepEqual(await boxes(), before, 'opening a collection never moves a card')
      await popup.locator('.collection-items .job').nth(20).waitFor({state: 'attached'})
      await popup.locator('.collection-items .job .thumb img').first().waitFor()
      const fit = await popup.evaluate((node) => {
        const box = node.getBoundingClientRect()
        const body = node.querySelector('.preview-scroll')
        return {
          left: box.left,
          right: box.right,
          top: box.top,
          bottom: box.bottom,
          width: innerWidth,
          height: innerHeight,
          overflow: body.scrollWidth - body.clientWidth,
          scrollable: body.scrollHeight > body.clientHeight,
        }
      })
      assert.ok(fit.left >= -1 && fit.top >= -1 && fit.right <= fit.width + 1 && fit.bottom <= fit.height + 1, JSON.stringify(fit))
      assert.ok(fit.overflow <= 1, 'popup items fit horizontally: ' + JSON.stringify(fit))
      assert.ok(fit.scrollable, 'the collection scrolls inside the popup')
      const outerScroll = await page.locator('.main').evaluate((node) => node.scrollTop)
      await popup.locator('.preview-scroll').evaluate((node) => {
        node.scrollTop = 300
      })
      assert.equal(await page.locator('.main').evaluate((node) => node.scrollTop), outerScroll)
      await popup.locator('.preview-scroll').evaluate((node) => {
        node.scrollTop = 0
      })
    },
  },


  {name: 'downloads-empty', fake: {scenario: 'empty'}, setup: async () => {}},
  {name: 'queue-active', setup: async () => {}},
  {
    name: 'media-review-video',
    fake: {simulate: false},
    setup: async (page) => {
      await field(page).fill(YT)
      await page.getByRole('radiogroup', {name: 'Video quality'}).waitFor()
    },
  },
  {
    name: 'media-review-audio',
    fake: {simulate: false},
    setup: async (page) => {
      await field(page).fill(MUSIC)
      await page.locator('.download-btn').waitFor()
    },
  },
  {
    name: 'preview-extras',
    fake: {simulate: false},
    setup: async (page) => {
      await field(page).fill(YT)
      await page.getByRole('radiogroup', {name: 'Video quality'}).waitFor()
      await page.getByRole('tab', {name: /^Extras/}).click()
      await page.getByRole('radiogroup', {name: 'Subtitles'}).getByRole('radio', {name: 'Save subtitles'}).click()
      await page.getByRole('radiogroup', {name: 'Cut a part'}).getByRole('radio', {name: 'Choose start and end'}).click()
      await page.getByRole('radiogroup', {name: 'Cut a part'}).scrollIntoViewIfNeeded()
    },
  },
  // One screen for each case of the download preview (dev-docs/plans/DOWNLOAD-PREVIEW.md), in both themes and at every size.
  {
    name: 'preview-song',
    fake: {simulate: false},
    setup: async (page) => {
      await field(page).fill(YTM_SONG)
      await page.getByText('No official music video was found, so Both is off.').waitFor()
    },
  },
  {
    name: 'preview-song-both',
    fake: {simulate: false},
    setup: async (page) => {
      await field(page).fill(SPOT)
      await page.getByRole('radiogroup', {name: 'What to keep'}).waitFor()
      await page.waitForFunction(() => !document.querySelector('[aria-label="What to keep"] button:disabled'))
      await page.getByRole('radiogroup', {name: 'What to keep'}).getByRole('radio', {name: 'Both', exact: true}).click()
      await page.getByText('They stay on the same second.').waitFor()
    },
  },
  {
    name: 'preview-have-video',
    fake: {simulate: false, captureSong: 'has'},
    setup: async (page) => {
      await field(page).fill(OWN_LINK)
      await page.getByText('You have this song. This video will be added to it.').waitFor()
    },
  },
  {
    name: 'preview-have-audio',
    fake: {simulate: false},
    setup: async (page) => {
      await field(page).fill(MUSIC)
      await page.getByRole('status').filter({hasText: 'Already in Music'}).first().waitFor()
    },
  },
  {
    name: 'preview-playlist',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await field(page).fill('https://www.youtube.com/playlist?list=PLfocus')
      await page.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
      await page.getByRole('button', {name: /Download \d+ selected/}).waitFor({timeout: 8000})
    },
  },
  {
    name: 'preview-album',
    fake: {simulate: false},
    setup: async (page) => {
      await field(page).fill(SPOT_ALBUM)
      await page.getByPlaceholder('Filter 9 entries').waitFor({timeout: 20000})
    },
  },
  {
    name: 'preview-links',
    fake: {simulate: false},
    setup: async (page) => {
      await field(page).fill([YT, MUSIC, PRIVATE].join('\n'))
      await page.getByRole('button', {name: /Download all 2/}).waitFor({timeout: 8000})
      await page.getByRole('button', {name: 'Try again'}).waitFor()
    },
  },
  {
    name: 'preview-photos',
    fake: {simulate: false},
    setup: async (page) => {
      await field(page).fill(GALLERY)
      await page.getByRole('tab', {name: /^Photos/}).waitFor()
    },
  },
  {
    name: 'preview-reading',
    fake: {simulate: false, latency: 30},
    setup: async (page) => {
      await field(page).fill(YT)
      await page.getByRole('dialog', {name: /^Reading formats/}).waitFor()
    },
  },
  {
    name: 'preview-problem',
    fake: {simulate: false},
    setup: async (page) => {
      await field(page).fill(PRIVATE)
      await page.getByRole('dialog', {name: 'This link could not be read'}).getByRole('button', {name: 'Sign in'}).waitFor()
    },
  },
  {
    name: 'collection',
    fake: {simulate: false},
    setup: async (page) => {
      await field(page).fill(SPOT_LIST)
      await page.getByText(/Showing positions 1–/).waitFor({timeout: 20000})
      const boxes = page.locator('.entries .entry input[type=checkbox]')
      await boxes.nth(1).click()
      await boxes.nth(4).click({modifiers: ['Shift']})
    },
  },
  {
    name: 'collection-ytmusic',
    fake: {simulate: false},
    setup: async (page) => {
      await field(page).fill('https://music.youtube.com/playlist?list=PLytm')
      await page.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
      await page.getByText(/24 entries/).waitFor({timeout: 20000})
      const boxes = page.locator('.entries .entry input[type=checkbox]')
      await boxes.nth(0).click()
      await boxes.nth(5).click({modifiers: ['Shift']})
    },
  },
  {
    name: 'queue-group-collapsed',
    fake: {scenario: 'groups', simulate: false},
    setup: async (page) => {
      await page.locator('.group-row[data-group="grp-road"]').waitFor()
      await page.getByRole('button', {name: /Hide 6 items/}).click()
    },
  },
  {
    name: 'queue-group-expanded',
    fake: {scenario: 'groups', simulate: false},
    setup: async (page) => {
      const row = page.locator('.group-row[data-group="grp-road"]')
      await page.locator('.group-items .job').nth(5).waitFor()
    },
  },
  {
    name: 'queue-retrying',
    fake: {scenario: 'groups', simulate: false},
    setup: async (page) => {
      await page.getByText(/Retrying in \d+ s/).first().waitFor()
      await page.getByRole('listitem', {name: /Coastal timelapse/}).scrollIntoViewIfNeeded()
    },
  },
  {
    name: 'library-group-collapsed',
    fake: {scenario: 'groups', simulate: false, library: 'off'},
    setup: async (page) => {
      await finishedList(page)
      await page.locator('.group-library[data-group="grp-album"]').waitFor()
      await page.getByRole('button', {name: /Hide 5 items/}).click()
    },
  },
  {
    name: 'library-group-expanded',
    fake: {scenario: 'groups', simulate: false, library: 'off'},
    setup: async (page) => {
      await finishedList(page)
      await page.locator('.group-library[data-group="grp-album"] .lib-row').nth(4).waitFor()
    },
  },
  {
    name: 'library-list',
    fake: {library: 'off'},
    setup: async (page) => {
      await finishedList(page)
      await page.getByRole('list', {name: 'Download history'}).waitFor()
    },
  },
  {
    name: 'library-grid',
    fake: {library: 'off'},
    setup: async (page) => {
      await finishedList(page)
      await page.getByTitle('Grid view').click()
      await page.locator('.lib-grid').waitFor()
    },
  },
  {
    name: 'downloads-finished',
    fake: {simulate: false},
    setup: async (page) => {
      await page.getByRole('radio', {name: /^Finished/}).click()
      await page.getByRole('list', {name: 'Download history'}).waitFor()
    },
  },
  {
    name: 'lib-home',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Home')
      await page.getByRole('region', {name: 'Continue watching'}).waitFor()
    },
  },
  {
    name: 'search-results',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Search')
      await page.getByRole('textbox').first().fill('harbo')
      await page.getByRole('region', {name: 'On YouTube'}).waitFor()
    },
  },
  {
    name: 'start-welcome',
    fake: {storagePhase: 'firstStart'},
    setup: async (page) => {
      await page.getByRole('heading', {name: 'Keep the videos and music you love'}).waitFor()
    },
  },
  {
    name: 'start-mode',
    fake: {storagePhase: 'firstStart'},
    setup: async (page) => {
      await page.getByRole('button', {name: 'Skip intro'}).click()
      await page.getByRole('heading', {name: 'How will you use Keepfin?'}).waitFor()
    },
  },
  {
    name: 'start-folder',
    fake: {storagePhase: 'firstStart'},
    setup: async (page) => {
      await page.getByRole('button', {name: 'Skip intro'}).click()
      await page.getByRole('button', {name: 'Next', exact: true}).click()
      await page.getByRole('button', {name: 'Advanced setup ›'}).waitFor()
    },
  },
  {
    name: 'now-playing-panel',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('button', {name: /^Glass Harbour/}).click()
      const bar = page.getByRole('region', {name: 'Now playing'})
      await bar.waitFor()
      await bar.getByRole('button', {name: 'Queue and Now playing'}).click()
    },
  },
  {
    name: 'lyrics-overlay',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('button', {name: /^Glass Harbour/}).click()
      const bar = page.getByRole('region', {name: 'Now playing'})
      await bar.waitFor()
      await bar.getByRole('button', {name: 'Lyrics'}).click()
      await page.getByRole('region', {name: 'Lyrics', exact: true}).waitFor()
    },
  },
  {
    name: 'lib-music-songs',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('list', {name: 'Songs'}).waitFor()
    },
  },
  {
    name: 'lib-music-albums',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('tab', {name: /^Albums/}).click()
      await page.getByRole('list', {name: 'Albums'}).waitFor()
    },
  },
  {
    name: 'lib-videos',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Videos/)
      await page.getByRole('region', {name: 'Continue watching'}).waitFor()
    },
  },
  {
    name: 'lib-downloads-mixed',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Downloads/)
      await page.getByRole('list', {name: 'Downloads items'}).waitFor()
    },
  },
  {
    name: 'lib-empty-space',
    fake: {simulate: false, libraryScenario: 'empty'},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByText('No music yet').waitFor()
    },
  },
  {
    name: 'player-full',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('button', {name: /^Glass Harbour/}).click()
      await page.getByRole('button', {name: /Open the player/}).click()
      await page.getByRole('dialog', {name: 'Now playing'}).waitFor()
    },
  },
  {
    name: 'player-bar',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('button', {name: /^Glass Harbour/}).click()
      await page.getByRole('region', {name: 'Now playing'}).waitFor()
    },
  },
  {
    name: 'video-player',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Videos/)
      await page.getByRole('button', {name: /^Folder Northbound Films/}).click()
      await page.getByRole('button', {name: /^Salt Flats/}).click()
      await page.getByRole('dialog', {name: /Playing Salt Flats/}).waitFor()
    },
  },
  // ---- Spaces v2 (hub, edit mode, smart rules, tags, collections, add from library, settings)
  {
    name: 'spaces-hub',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await page.getByRole('list', {name: 'Pinned spaces'}).waitFor()
    },
  },
  {
    name: 'spaces-hub-edit',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('button', {name: 'Edit', exact: true}).click()
      await page.getByRole('list', {name: 'Spaces, in order'}).waitFor()
    },
  },
  {
    name: 'library-overview',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await page.getByRole('region', {name: 'Collections', exact: true}).waitFor()
    },
  },
  {
    name: 'library-playlists-filter',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await page.getByRole('group', {name: 'Show in Library'}).getByRole('button', {name: 'Playlists', exact: true}).click()
      await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Night drive/}).waitFor()
    },
  },
  {
    name: 'sidebar-collapsed',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Library')
      const toggle = page.getByRole('button', {name: 'Collapse sidebar'})
      if (await toggle.isVisible()) await toggle.click()
    },
  },
  {
    name: 'works-with',
    fake: {scenario: 'empty', simulate: false},
    setup: async (page) => {
      await nav(page, 'Downloads')
      await page.locator('.works-with').waitFor()
    },
  },
  {
    name: 'history-grid-groups',
    fake: {scenario: 'groups', simulate: false},
    setup: async (page) => {
      await nav(page, 'Downloads')
      await page.getByRole('radio', {name: /^Finished/}).click()
      await page.getByTitle('Grid view').click()
      await page.locator('.lib-grid .group-card').first().waitFor()
    },
  },
  {
    name: 'panel-wide',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Library')
      await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('button', {name: /^Music/}).click()
      await page.locator('button.row-main', {hasText: 'Glass Harbour'}).click()
      const panel = page.getByRole('complementary', {name: 'Now playing panel'})
      if (!(await panel.isVisible())) await page.getByRole('region', {name: 'Now playing'}).getByRole('button', {name: 'Queue and Now playing'}).click()
      // Below 960 px of window width the panel opens over the page and keeps its width; there is nothing to resize then.
      if (!(await panel.waitFor({timeout: 1500}).then(() => true, () => false))) return
      if (!(await page.getByRole('separator', {name: 'Resize Now playing panel'}).isVisible())) return
      await page.getByRole('separator', {name: 'Resize Now playing panel'}).focus()
      await page.keyboard.press('End')
    },
  },
  {
    name: 'panel-narrow',
    fake: {simulate: false},
    setup: async (page) => {
      await nav(page, 'Library')
      await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('button', {name: /^Music/}).click()
      await page.locator('button.row-main', {hasText: 'Glass Harbour'}).click()
      const panel = page.getByRole('complementary', {name: 'Now playing panel'})
      if (!(await panel.isVisible())) await page.getByRole('region', {name: 'Now playing'}).getByRole('button', {name: 'Queue and Now playing'}).click()
      if (!(await panel.waitFor({timeout: 1500}).then(() => true, () => false))) return
      if (!(await page.getByRole('separator', {name: 'Resize Now playing panel'}).isVisible())) return
      await page.getByRole('separator', {name: 'Resize Now playing panel'}).focus()
      await page.keyboard.press('Home')
    },
  },
  {
    name: 'spaces-all',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await page.getByRole('button', {name: /^All spaces/}).first().click()
      await page.getByRole('searchbox', {name: 'Filter spaces'}).waitFor()
    },
  },
  {
    name: 'spaces-smart-rules',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('button', {name: 'Edit', exact: true}).click()
      await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('button', {name: 'New space'}).click()
      const dialog = page.getByRole('dialog', {name: 'New space'})
      await dialog.getByLabel('Name').fill('Workout mix')
      await dialog.getByRole('radio', {name: 'Smart'}).click()
      await dialog.getByRole('radio', {name: 'Rust'}).click()
      await dialog.getByRole('button', {name: 'Add rule'}).click()
      await dialog.getByLabel('Add a tag to rule 1').fill('workout')
      await dialog.getByLabel('Add a tag to rule 1').press('Enter')
      await dialog.getByRole('button', {name: 'Add rule'}).click()
      await dialog.getByLabel('Rule 2 field').selectOption('kind')
      await dialog.getByRole('status').filter({hasText: /match/}).waitFor()
      await dialog.getByRole('region', {name: 'Rules'}).scrollIntoViewIfNeeded()
    },
  },
  {
    name: 'space-tags',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('list', {name: 'Songs'}).waitFor()
      const tags = page.getByRole('group', {name: 'Tags'})
      await tags.getByRole('button', {name: /^ambient/}).click()
      await tags.getByRole('button', {name: /^indie/}).click()
      await page.getByRole('button', {name: 'Save as smart space'}).waitFor()
    },
  },
  {
    name: 'space-collections',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('tab', {name: /^Collections/}).click()
      await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).waitFor()
    },
  },
  {
    name: 'collection-detail',
    // At 420 px and 140% the virtual list has no room to render a tile; the same views are checked at every other size.
    skipAt: '420@140',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('tab', {name: /^Collections/}).click()
      await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).click()
      await page.getByRole('region', {name: 'Following'}).waitFor()
      await page.getByText('Removed from the playlist · kept here').waitFor()
    },
  },
  {
    name: 'follow-settings',
    // At 420 px and 140% the virtual list has no room to render a tile; the same views are checked at every other size.
    skipAt: '420@140',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('tab', {name: /^Collections/}).click()
      await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).click()
      await page.getByRole('button', {name: 'Follow settings…'}).click()
      await page.getByRole('dialog', {name: 'Follow settings'}).waitFor()
    },
  },
  {
    name: 'add-from-library',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Downloads/)
      await page.getByRole('button', {name: 'Add', exact: true}).click()
      const dialog = page.getByRole('dialog', {name: 'Add to Downloads'})
      await dialog.getByRole('checkbox').first().click()
    },
  },
  {
    name: 'review-keep-in-sync',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await field(page).fill('https://www.youtube.com/playlist?list=PLfocus')
      await page.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
      await page.getByRole('button', {name: /Download \d+ selected/}).waitFor({timeout: 8000})
      const row = page.getByRole('region', {name: 'Keep in sync'})
      await row.getByRole('switch', {name: 'Keep in sync'}).click()
      await row.getByLabel('How often to check for new ones').selectOption('12')
      await row.scrollIntoViewIfNeeded()
    },
  },
  {
    name: 'add-from-link',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('button', {name: 'Add', exact: true}).click()
      await page.getByRole('dialog', {name: 'Add to Music'}).getByRole('button', {name: 'From a link…'}).click()
      await page.getByRole('dialog', {name: 'Add to Music'}).getByLabel('Link').fill('https://www.youtube.com/playlist?list=PLfocus')
    },
  },
  {
    name: 'add-sheet',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('button', {name: 'Add', exact: true}).click()
      await page.getByRole('dialog', {name: 'Add to Music'}).getByRole('button', {name: 'From a link…'}).waitFor()
    },
  },
  {
    name: 'collection-new-downloads',
    // At 420 px and 140% the virtual list has no room to render a tile; the same views are checked at every other size.
    skipAt: '420@140',
    fake: {simulate: false, libraryScenario: 'v2', checkJobs: 3},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('tab', {name: /^Collections/}).click()
      await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).click()
      await page.getByRole('region', {name: 'Following'}).getByRole('button', {name: 'Check now'}).click()
      await page.getByRole('region', {name: 'New downloads'}).waitFor()
      await page.evaluate(() => {
        window.__fake.patchJob('job-sync-0', {status: 'completed', percent: 100, completedAt: Date.now()})
        window.__fake.patchJob('job-sync-1', {status: 'downloading', percent: 50})
      })
      await page.getByText('Downloading 1 of 3 new').waitFor()
      await page.waitForTimeout(900)
    },
  },
  {
    name: 'smart-space',
    fake: {simulate: false, libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await page.getByRole('button', {name: /^All spaces/}).first().click()
      await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Gym/}).click()
      await page.getByRole('button', {name: 'Play all'}).first().waitFor()
    },
  },
  {
    name: 'settings-library',
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Library')
      await page.getByRole('radio', {name: 'Just downloads'}).waitFor()
    },
  },
  {
    name: 'save-to-menu',
    fake: {simulate: false},
    setup: async (page) => {
      await field(page).fill(YT)
      await page.getByRole('radiogroup', {name: 'Video quality'}).waitFor()
      await page.getByRole('button', {name: /^Save to:/}).click()
      await page.getByRole('menu').waitFor()
    },
  },

  ...['Downloads', 'Storage & copies', 'Devices', 'Appearance', 'Notifications', 'Help', 'Support Keepfin', 'About Keepfin'].map((label) => ({
    name: `settings-${label.split(' ')[0].toLowerCase()}`,
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, label)
      await page.waitForTimeout(250)
    },
  })),
  // the Devices page: Available devices as a circle (found on the network and through Tailscale) beside the QR code, the firewall card, Away from home
  {
    name: 'settings-devices-circle',
    fake: {devicesTailscale: true, devicesFirewall: true, devicesScanning: true},
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Devices')
      await page.getByRole('region', {name: 'Available devices', exact: true}).getByRole('button', {name: /^Connect to Studio PC/}).waitFor()
      await page.getByRole('img', {name: /QR code to connect a phone/}).waitFor()
      await page.getByText('Windows Firewall is blocking other devices').waitFor()
      await page.getByText('Also on Tailscale').waitFor()
      await page.waitForTimeout(250)
    },
  },
  // the one guide behind the "?" (how devices work, Away from home)
  {
    name: 'settings-devices-guide',
    fake: {devicesTailscale: true},
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Devices')
      await page.getByRole('button', {name: 'How devices work'}).click()
      await page.getByRole('dialog').getByRole('region', {name: 'Away from home'}).waitFor()
      await page.waitForTimeout(250)
    },
  },
  // this device asked: it shows the code and waits (only Cancel)
  {
    name: 'settings-devices-asking',
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Devices')
      await page.getByRole('button', {name: /^Connect to Galaxy S24/}).click()
      await page.getByRole('dialog').getByRole('button', {name: 'Codes match · Connect'}).click()
      await page.getByRole('dialog').getByText(/Waiting for Galaxy S24/).waitFor()
      await page.waitForTimeout(250)
    },
  },
  // two devices with their own libraries: the setup starts with the Combine step
  {
    name: 'settings-devices-setup-different',
    fake: {copiesDevices: 'allowed', devicesLibraries: 'different'},
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Devices')
      await page.getByRole('button', {name: /^Connect to Galaxy S24/}).click()
      await page.getByRole('dialog').getByRole('button', {name: 'Codes match · Connect'}).click()
      await page.getByRole('dialog').getByText(/Waiting for Galaxy S24/).waitFor()
      await page.evaluate(() => window.__fake.devicesPeerConfirms())
      await page.getByRole('dialog').getByRole('button', {name: 'Set up'}).click()
      await page.locator('dialog.devices-setup').getByRole('heading', {name: 'Combine libraries'}).waitFor()
      await page.locator('dialog.devices-setup').getByRole('radio').first().waitFor()
      await page.waitForTimeout(250)
    },
  },
  // the review of the dry run, over the Combine step
  {
    name: 'settings-devices-combine-review',
    fake: {copiesDevices: 'allowed', devicesLibraries: 'different'},
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Devices')
      await page.getByRole('button', {name: /^Connect to Galaxy S24/}).click()
      await page.getByRole('dialog').getByRole('button', {name: 'Codes match · Connect'}).click()
      await page.getByRole('dialog').getByText(/Waiting for Galaxy S24/).waitFor()
      await page.evaluate(() => window.__fake.devicesPeerConfirms())
      await page.getByRole('dialog').getByRole('button', {name: 'Set up'}).click()
      const dialog = page.locator('dialog.devices-setup')
      await dialog.getByRole('radio').first().waitFor()
      await dialog.getByRole('button', {name: 'Review'}).click()
      await dialog.getByLabel('Review', {exact: true}).waitFor()
      await dialog.getByRole('button', {name: /^Show the details/}).click()
      await page.waitForTimeout(250)
    },
  },
  // Separate libraries…, with a copy held on the other device (Keep / Remove) and when the other device cannot be reached
  ...[
    {name: 'settings-devices-combine-separate', fake: {combine: 'done'}},
    {name: 'settings-devices-combine-unreachable', fake: {combine: 'done', combineUnreachable: true}},
  ].map(({name, fake}) => ({
    name,
    fake,
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Devices')
      await page.locator('.devices-card-row').filter({hasText: 'Laptop'}).click()
      await page.getByRole('button', {name: 'Separate libraries…'}).click()
      await page.locator('dialog.devices-setup').getByRole('button', {name: /^Separate/}).waitFor()
      await page.waitForTimeout(250)
    },
  })),
  // the card while a combine runs, is paused, stopped, or done
  ...[
    {name: 'settings-devices-combine-running', fake: {combine: 'running'}},
    {name: 'settings-devices-combine-paused', fake: {combine: 'paused'}},
    {name: 'settings-devices-combine-failed', fake: {combine: 'failed'}},
    {name: 'settings-devices-combine-done', fake: {combine: 'done'}},
  ].map(({name, fake}) => ({
    name,
    fake,
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Devices')
      await page.locator('.devices-combine-line').waitFor()
      await page.locator('.devices-card-row').filter({hasText: 'Laptop'}).click()
      await page.locator('.devices-card-body').waitFor()
      await page.waitForTimeout(250)
    },
  })),  // many devices found: the searchable list
  {
    name: 'settings-devices-crowded',
    fake: {devicesNearby: 'many'},
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Devices')
      await page.getByRole('searchbox', {name: 'Find a device'}).waitFor()
      await page.waitForTimeout(250)
    },
  },
  // an opened device card
  {
    name: 'settings-devices-card',
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Devices')
      await page.locator('.devices-card-row').first().click()
      await page.locator('.devices-card-body').waitFor()
      await page.waitForTimeout(250)
    },
  },
  // a request that comes in, over the page
  {
    name: 'settings-devices-incoming',
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Devices')
      await page.waitForFunction(() => !!window.__fake)
      await page.evaluate(() => window.__fake.devicesIncoming(true))
      await page.getByRole('dialog').getByRole('button', {name: 'Codes match · Connect'}).waitFor()
      await page.waitForTimeout(250)
    },
  },
  // the setup after pairing: step 1, step 2 with and without differences, and a device that does not allow a copy
  ...[
    {name: 'settings-devices-setup-step1', fake: {copiesDevices: 'allowed'}, step: 1},
    {name: 'settings-devices-setup-review', fake: {copiesDevices: 'allowed'}, step: 2},
    {name: 'settings-devices-setup-differences', fake: {copiesDevices: 'allowed'}, step: 2, preview: 'differences'},
    {name: 'settings-devices-setup-notallowed', fake: {copiesDevices: 'blocked'}, step: 1},
  ].map(({name, fake, step, preview}) => ({
    name,
    fake,
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Devices')
      await page.waitForFunction(() => !!window.__fake)
      if (preview) await page.evaluate((kind) => (window.__devicePreview = kind), preview)
      await page.locator('.devices-card-row').filter({hasText: 'Laptop'}).click()
      await page.getByRole('button', {name: 'Choose what stays up to date'}).first().click()
      const dialog = page.locator('dialog.devices-setup')
      await dialog.getByRole('heading', {name: 'What stays up to date'}).waitFor()
      if (step === 2) {
        await dialog.getByRole('checkbox', {name: 'Music'}).waitFor()
        await dialog.getByRole('button', {name: 'Next'}).click()
        await dialog.getByLabel('Review').waitFor()
      } else if (fake.copiesDevices === 'blocked') await dialog.getByRole('alert').waitFor()
      else await dialog.getByRole('checkbox', {name: 'Music'}).waitFor()
      await page.waitForTimeout(250)
    },
  })),
  {
    name: 'settings-devices-empty',
    fake: {devicesNearby: 'none', devices: 'empty'},
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Devices')
      await page.getByText(/Nothing found yet/).waitFor()
      await page.waitForTimeout(250)
    },
  },
  // Where your files are: Your library at the top of Storage (the sentence and the places) and the view below Your devices in
  // Devices, from one place to every state at once (the longest words)
  ...[
    {name: 'where-storage-one', page: 'Storage & copies', where: 'one'},
    {name: 'where-storage-several', page: 'Storage & copies', where: 'several'},
    {name: 'where-storage-all', page: 'Storage & copies', where: 'all'},
    {name: 'where-devices-several', page: 'Devices', where: 'several'},
    {name: 'where-devices-all', page: 'Devices', where: 'all'},
  ].map(({name, page: category, where}) => ({
    name,
    fake: {where},
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, category)
      const storage = category.startsWith('Storage')
      const region = page.getByRole('region', {name: storage ? 'Your library' : 'Where your files are', exact: true})
      await region.getByRole('list', {name: storage ? 'Places that keep your library' : 'Places that hold your files'}).waitFor()
      await region.scrollIntoViewIfNeeded()
      await page.waitForTimeout(250)
    },
  })),
  // Details… on a file: the facts and where it is kept
  {
    name: 'where-item-details',
    // At 420 px and 140% the song list has no room to render a row to open the menu from; the dialog is checked at every other size.
    skipAt: '420@140',
    fake: {where: 'several', simulate: false},
    setup: async (page) => {
      await nav(page, 'Library')
      await space(page, /^Music/)
      await page.getByRole('button', {name: /^Cold Front/}).click({button: 'right'})
      await page.getByRole('menuitem', {name: 'Details…'}).click()
      await page.getByRole('dialog', {name: 'Details'}).getByText('On: this PC, Pixel 9 (offline), Backup drive (E:)').waitFor()
    },
  },
  {
    name: 'settings-speed',
    setup: async (page) => {
      await nav(page, 'Settings')
      await page.getByRole('button', {name: /Speed and connections/}).click()
      await page.locator('#proxy-input').waitFor()
    },
  },
  {
    name: 'settings-search',
    setup: async (page) => {
      await nav(page, 'Settings')
      await page.getByRole('combobox', {name: 'Search settings'}).fill('s')
      await page.getByRole('listbox', {name: 'Matching settings'}).waitFor()
    },
  },
  {
    name: 'settings-guide',
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Help')
      await page.getByRole('searchbox', {name: 'Search features'}).waitFor()
    },
  },
  {
    name: 'settings-guide-open',
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Help')
      await page.getByRole('button', {name: 'Keep a playlist or channel in sync'}).click()
      await page.getByText('Checks run while Keepfin is open.').waitFor()
    },
  },
  {
    name: 'move-to-space',
    fake: {libraryScenario: 'v2'},
    setup: async (page) => {
      await nav(page, 'Library')
      await page.getByRole('group', {name: 'Show in Library'}).getByRole('button', {name: 'Playlists', exact: true}).click()
      await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Night drive/}).click()
      await page.getByRole('button', {name: /^More actions for /}).first().click()
      await page.getByRole('menuitem', {name: 'Move to space…'}).click()
      await page.getByRole('dialog', {name: 'Move to space…'}).getByRole('radio', {name: /^Downloads/}).click()
    },
  },
  {
    name: 'saved-toast',
    fake: {scenario: 'empty', simulate: false},
    setup: async (page) => {
      await page.evaluate(() =>
        window.__fake.addJob({id: 'job-lt', url: 'https://music.youtube.com/watch?v=lt', title: 'Layout Song', provider: 'YouTube Music', kind: 'audio', format: {type: 'audio', target: 'mp3', quality: '320'}, status: 'downloading', percent: 50, space: 'music', totalBytes: 1e6, downloadedBytes: 5e5}),
      )
      await page.getByText('Layout Song').first().waitFor()
      await page.evaluate(() => window.__fake.completeJob('job-lt'))
      await page.getByRole('status').filter({hasText: 'Saved to Music'}).waitFor()
    },
  },
  {
    name: 'settings-support',
    setup: async (page) => {
      await nav(page, 'Settings')
      await group(page, 'Support Keepfin')
      await page.getByRole('heading', {name: 'Hisham Sanjag'}).waitFor()
    },
  },
  {
    name: 'activity-panel',
    fake: {scenario: 'groups', libraryScenario: 'v2', activity: 'seed', simulate: false},
    setup: async (page) => {
      await page.locator('.act-bell').click()
      const panel = page.getByRole('dialog', {name: 'Activity'})
      await panel.getByText('Road Trip Mix got 3 new songs').waitFor()
      await page.waitForTimeout(500)
      // The panel stays inside the window, and no title or action is clipped sideways.
      const fit = await page.evaluate(() => {
        const box = document.querySelector('.act-panel').getBoundingClientRect()
        const rows = [...document.querySelectorAll('.act-row')].map((row) => row.scrollWidth > row.clientWidth + 1)
        return {left: box.left, right: box.right, width: innerWidth, rowsOverflow: rows.some(Boolean)}
      })
      assert.ok(fit.left >= 0 && fit.right <= fit.width, 'activity panel fits the window: ' + JSON.stringify(fit))
      assert.equal(fit.rowsOverflow, false, 'activity rows do not overflow')
    },
  },
  {
    name: 'activity-panel-long',
    fake: {scenario: 'groups', libraryScenario: 'v2', activity: 'many', simulate: false},
    setup: async (page) => {
      await page.locator('.act-bell').click()
      await page.getByRole('dialog', {name: 'Activity'}).getByText('Older playlist 40 finished').waitFor({state: 'attached'})
      await page.waitForTimeout(400)
      const scrolls = await page.evaluate(() => {
        const list = document.querySelector('.act-list')
        return list.scrollHeight > list.clientHeight && document.querySelector('.act-panel').getBoundingClientRect().bottom <= innerHeight
      })
      assert.ok(scrolls, 'a long list scrolls inside the panel')
    },
  },
  {
    name: 'activity-empty',
    fake: {scenario: 'empty', simulate: false},
    setup: async (page) => {
      await page.locator('.act-bell').click()
      await page.getByText('Nothing yet. Downloads, synced playlists and updates show up here.').waitFor()
    },
  },
  {
    name: 'update-dialog',
    fake: {update: 'available', simulate: false},
    setup: async (page) => {
      await page.getByRole('dialog', {name: 'Keepfin 0.4.1 is available'}).getByText('Spotify playlist').waitFor({timeout: 6000})
    },
  },
  {
    name: 'update-popover',
    fake: {update: 'available', simulate: false},
    setup: async (page) => {
      await page.getByRole('dialog', {name: 'Keepfin 0.4.1 is available'}).getByRole('button', {name: 'Later'}).click({timeout: 6000})
      await page.getByRole('button', {name: 'Update available · v0.4.1'}).click()
      await page.getByRole('dialog', {name: 'App updates'}).getByText('Spotify playlist').waitFor()
    },
  },
  {
    name: 'update-popover-current',
    fake: {update: 'current', simulate: false},
    setup: async (page) => {
      await page.getByRole('button', {name: 'App updates'}).click()
      await page.getByText('You’re on v0.4.0').waitFor()
    },
  },
  {
    name: 'whats-new',
    fake: {update: 'current', lastSeen: '0.3.12', simulate: false},
    setup: async (page) => {
      await page.getByRole('dialog', {name: 'What’s new in v0.4.0'}).getByText('Rebuilt interface').waitFor()
    },
  },
]

const viewports = [
  {w: 1280, h: 820, scale: 100},
  {w: 1280, h: 820, scale: 140},
  {w: 1280, h: 820, scale: 80},
  {w: 999, h: 820, scale: 100},
  // The narrowest windows Keepfin allows (dev-docs/plans/DESKTOP-WIDTHS.md): 560 px of layout, at 100 % and at 140 % (800 px wide).
  {w: 860, h: 820, scale: 100},
  {w: 560, h: 820, scale: 100},
  {w: 800, h: 820, scale: 140},
]

async function accessibility(page) {
  return page.evaluate(() => {
    const visible = (el) => !!(el.offsetParent || el.getClientRects().length) && getComputedStyle(el).visibility !== 'hidden'
    const name = (el) =>
      (el.getAttribute('aria-label') || el.getAttribute('aria-labelledby') || el.getAttribute('title') || el.textContent || '').trim() ||
      (el.labels && el.labels.length ? 'labelled' : '')
    const problems = []
    for (const el of document.querySelectorAll('button, [role=button], a[href], input:not([type=hidden]), select, textarea, [role=switch], [role=radio], [role=tab]')) {
      if (!visible(el)) continue
      if (!name(el) && !el.querySelector('img[alt]:not([alt=""])')) problems.push(`${el.tagName.toLowerCase()}.${el.className || ''} has no accessible name`)
    }
    for (const img of document.querySelectorAll('img:not([alt])')) problems.push('img without alt')
    return problems.slice(0, 8)
  })
}

const srv = await startServer()
let browser = await launch()
const failures = []
let checks = 0
// Every check gets its own browser context, so checks run side by side: LAYOUT_WORKERS at a time (default 4).
const tasks = []
for (const theme of (process.env.THEME ? [process.env.THEME] : ['dark', 'light']))
  for (const viewport of viewports) {
    if (process.env.VP && process.env.VP !== `${viewport.w}@${viewport.scale}`) continue
    for (const screen of screens) {
      if (process.env.ONLY && !screen.name.includes(process.env.ONLY)) continue
      if (viewport.w === 999 && !screen.name.startsWith('downloads-collection-')) continue
      if (screen.skipAt === `${viewport.w}@${viewport.scale}`) continue
      tasks.push({theme, viewport, screen})
    }
  }

async function check({theme, viewport, screen}) {
  const label = `${screen.name} ${theme} ${viewport.w}px @${viewport.scale}%`
  // A slow CI runner can miss an 8 s wait; a timeout gets one fresh attempt. Layout, a11y and page errors never retry.
  for (let attempt = 1; attempt <= 2; attempt++) {
    let page
    try {
      // Opening the app is inside the retry too: a cold runner can take more than 30 s to render the shell once.
      page = await openApp(browser, srv.url, {...viewport, width: viewport.w, height: viewport.h, theme, intro: !!screen.intro, fake: {simulate: true, ...(screen.fake || {})}})
      await screen.setup(page)
      await page.waitForTimeout(300)
      const overflow = await noHorizontalOverflow(page)
      assert.ok(overflow.ok, `horizontal overflow ${JSON.stringify(overflow)}`)
      const a11y = await accessibility(page)
      assert.deepEqual(a11y, [], a11y.join('; '))
      assert.deepEqual(page.errors, [], `page errors: ${page.errors.join('; ')}`)
      checks++
      if (shots && (viewport.scale === 100 || viewport.scale === 140) && (viewport.w === 1280 || viewport.scale === 100)) {
        const file = `${shots}/${screen.name}-${theme}-${viewport.w}${viewport.scale === 100 ? '' : '-' + viewport.scale}.png`
        await page.screenshot({path: file})
      }
      return
    } catch (error) {
      if (attempt === 1 && (error?.name === 'TimeoutError' || String(error?.message).startsWith('App shell did not render'))) {
        console.log(`retry ${label}: ${String(error.message).split('\n')[0]}`)
        continue
      }
      failures.push(`FAIL ${label}: ${String(error.message).split('\n')[0]}`)
      if (shots && page) await page.screenshot({path: `${shots}/FAIL-${label.replace(/\W+/g, '-')}.png`}).catch(() => {})
      return
    } finally {
      if (page) await withLimit(page.context().close(), 15_000).catch(() => {})
    }
  }
}

/** Rejects when `promise` takes longer than `ms`. */
function withLimit(promise, ms) {
  let timer
  return Promise.race([promise, new Promise((_, reject) => (timer = setTimeout(() => reject(new Error(`stuck for ${ms / 1000} s`)), ms)))]).finally(() => clearTimeout(timer))
}

// A browser that stops answering (seen on GitHub's Windows runners) would otherwise hang the whole run: a check that
// takes over 2 minutes fails, and the browser is replaced so the checks after it still run.
const CHECK_LIMIT = 120_000
let relaunching = null
async function guarded(task) {
  const used = browser
  try {
    await withLimit(check(task), CHECK_LIMIT)
  } catch (error) {
    failures.push(`FAIL ${task.screen.name} ${task.theme} ${task.viewport.w}px @${task.viewport.scale}%: ${error.message}`)
    if (browser === used) {
      relaunching ||= (async () => {
        console.log('browser stopped answering; starting a new one')
        await withLimit(used.close(), 15_000).catch(() => {})
        browser = await launch()
      })().finally(() => (relaunching = null))
    }
    await relaunching
  }
}

const workers = Math.max(1, Number(process.env.LAYOUT_WORKERS) || 4)
let next = 0
await Promise.all(Array.from({length: Math.min(workers, tasks.length)}, async () => {
  while (next < tasks.length) await guarded(tasks[next++])
}))
// ---- One page structure (dev-docs/plans/PAGE-LAYOUT.md): every page has one app bar, one h1 and the same place for it ----
const settingsCategories = ['Downloads', 'Library & playback', 'Storage & copies', 'Devices', 'Appearance', 'Notifications', 'Help', 'Support Keepfin', 'About Keepfin']
const structurePages = [
  {name: 'home', setup: async (page) => { await nav(page, 'Home'); await page.getByRole('region', {name: 'Continue watching'}).waitFor() }},
  {name: 'search', setup: async (page) => { await nav(page, 'Search'); await page.getByRole('textbox', {name: /Search your library/}).fill('harbo'); await page.getByRole('region', {name: 'On YouTube'}).waitFor() }},
  {name: 'library', setup: async (page) => { await nav(page, 'Library'); await page.getByRole('region', {name: 'Collections', exact: true}).waitFor() }},
  {name: 'library-albums', setup: async (page) => { await nav(page, 'Library'); await page.getByRole('group', {name: 'Show in Library'}).getByRole('button', {name: 'Albums', exact: true}).click(); await page.getByRole('searchbox', {name: 'Filter albums'}).waitFor() }},
  {name: 'space', setup: async (page) => { await nav(page, 'Library'); await space(page, /^Music/); await page.getByRole('list', {name: 'Songs'}).waitFor() }},
  {name: 'library-spaces', setup: async (page) => { await nav(page, 'Library'); await page.getByRole('group', {name: 'Show in Library'}).getByRole('button', {name: 'Spaces', exact: true}).click(); await page.getByRole('searchbox', {name: 'Filter spaces'}).waitFor() }},
  {name: 'smart-space', setup: async (page) => { await nav(page, 'Library'); await page.getByRole('button', {name: /^All spaces/}).first().click(); await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Gym/}).click(); await page.getByRole('button', {name: 'Play all'}).first().waitFor() }},
  {name: 'album', setup: async (page) => { await nav(page, 'Library'); await space(page, /^Music/); await page.getByRole('tab', {name: /^Albums/}).click(); await page.getByRole('list', {name: 'Albums'}).getByRole('button').first().click(); await page.getByRole('navigation', {name: 'Breadcrumb'}).waitFor() }},
  {name: 'collection', setup: async (page) => { await nav(page, 'Library'); await space(page, /^Music/); await page.getByRole('tab', {name: /^Collections/}).click(); await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).click(); await page.getByRole('region', {name: 'Following'}).waitFor() }},
  {name: 'downloads', setup: async (page) => { await nav(page, 'Downloads') }},
  ...settingsCategories.map((label) => ({name: `settings-${label.split(' ')[0].toLowerCase()}`, setup: async (page) => { await nav(page, 'Settings'); await group(page, label); await page.waitForTimeout(200) }})),
]

/** What every page must share: one visible h1, the app bar, and nothing in the app bar's place on the page itself. */
async function structureOf(page) {
  return page.evaluate(() => {
    const visible = (el) => !!(el.offsetParent || el.getClientRects().length) && getComputedStyle(el).visibility !== 'hidden'
    const box = (el) => {
      if (!el) return null
      const r = el.getBoundingClientRect()
      return {top: r.top, left: r.left, width: r.width, height: r.height}
    }
    const h1s = [...document.querySelectorAll('.main h1')].filter(visible)
    const h1 = h1s[0]
    const top = h1 ? h1.getBoundingClientRect().top : 0
    // A search field that sits above the h1 is only allowed in the app bar.
    const strays = [...document.querySelectorAll('.main input:not([type=hidden]):not([type=checkbox]):not([type=radio]), .main [role=searchbox], .main [role=search]')]
      .filter((el) => visible(el) && !el.closest('.app-bar') && el.getBoundingClientRect().bottom <= top + 1)
      .map((el) => `${el.tagName.toLowerCase()}.${el.className}`)
    return {
      count: h1s.length,
      text: h1 ? h1.textContent.trim() : '',
      h1: box(h1),
      fontSize: h1 ? getComputedStyle(h1).fontSize : '',
      lineHeight: h1 ? getComputedStyle(h1).lineHeight : '',
      fontWeight: h1 ? getComputedStyle(h1).fontWeight : '',
      bar: box(document.querySelector('.app-bar')),
      search: (() => {
        const el = document.querySelector('.app-search')
        return el && visible(el) ? box(el) : null
      })(),
      bars: document.querySelectorAll('.app-bar').length,
      strays,
    }
  })
}

const near = (a, b) => Math.abs(a - b) <= 1
const sameBox = (a, b) => (!a && !b) || (a && b && near(a.top, b.top) && near(a.left, b.left) && near(a.width, b.width) && near(a.height, b.height))

for (const viewport of [{w: 1280, h: 820}, {w: 560, h: 820}]) {
  if (process.env.VP && process.env.VP !== `${viewport.w}@100`) continue
  if (process.env.ONLY) continue
  const reference = {}
  const label = (name) => `page structure ${name} ${viewport.w}px`
  for (const entry of structurePages) {
    const theme = 'dark'
    let page
    try {
      page = await openApp(browser, srv.url, {...viewport, width: viewport.w, height: viewport.h, scale: 100, theme, fake: {simulate: false, libraryScenario: 'v2'}})
      await entry.setup(page)
      await page.waitForTimeout(350)
      const found = await structureOf(page)
      assert.equal(found.count, 1, `exactly one visible h1, found ${found.count}`)
      assert.equal(found.bars, 1, `exactly one app bar, found ${found.bars}`)
      assert.deepEqual(found.strays, [], `no search field above the h1: ${found.strays.join(', ')}`)
      reference.first ??= {name: entry.name, ...found}
      const first = reference.first
      assert.ok(near(found.h1.top, first.h1.top), `h1 top ${found.h1.top} vs ${first.h1.top} on ${first.name}`)
      assert.ok(near(found.h1.left, first.h1.left), `h1 left ${found.h1.left} vs ${first.h1.left} on ${first.name}`)
      assert.equal(found.fontSize, first.fontSize, `h1 font-size ${found.fontSize} vs ${first.fontSize} on ${first.name}`)
      assert.equal(found.lineHeight, first.lineHeight, `h1 line-height ${found.lineHeight} vs ${first.lineHeight}`)
      assert.equal(found.fontWeight, first.fontWeight, `h1 font-weight ${found.fontWeight} vs ${first.fontWeight}`)
      assert.ok(sameBox(found.bar, first.bar), `app bar ${JSON.stringify(found.bar)} vs ${JSON.stringify(first.bar)}`)
      assert.ok(sameBox(found.search, first.search), `app search ${JSON.stringify(found.search)} vs ${JSON.stringify(first.search)}`)
      checks++
      if (shots && viewport.w === 1280) await page.screenshot({path: `${shots}/structure-${entry.name}-${theme}-${viewport.w}.png`})
    } catch (error) {
      failures.push(`FAIL ${label(entry.name)}: ${String(error.message).split('\n')[0]}`)
    } finally {
      if (page) await page.context().close()
    }
  }
}
failures.sort()
await browser.close()
await srv.close()
console.log(failures.join('\n'))
console.log(failures.length ? `${failures.length} layout failures (${checks} passed)` : `all ${checks} layout checks passed`)
process.exit(failures.length ? 1 : 0)
