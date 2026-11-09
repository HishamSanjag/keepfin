import {test} from 'node:test'
import assert from 'node:assert/strict'
import {friendlyError, lastErrorLine, retryBackoff, retryPolicy, suggestsGallery} from './errors.js'

const cases: [string, string, RegExp][] = [
  ['ERROR: [youtube] abc: Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies for the authentication.', 'signIn', /not a bot/],
  ["ERROR: [youtube] abc: Sign in to confirm you're not a bot. This helps protect our community.", 'signIn', /not a bot/],
  ['ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate for some users.', 'signIn', /age-restricted/],
  ['ERROR: [youtube] abc: Join this channel to get access to members-only content like this video', 'signIn', /members-only/],
  ['ERROR: [youtube] abc: Private video. Sign in if you\'ve been granted access to this video', 'signIn', /private/],
  ['ERROR: [vimeo] 76979871: The web client only works when logged-in. Use --cookies, --cookies-from-browser, --username and --password', 'signIn', /Sign-in required/],
  // what Instagram answers a signed-out reel now and then (issue #45): a sign-in gets past it, so the sign-in retry must see it
  ['ERROR: [Instagram] DdV1kbBIIF2: Requested content is not available, rate-limit reached or login required. Use --cookies, --cookies-from-browser, --username and --password, --netrc-cmd, or --netrc (instagram) to provide account credentials', 'signIn', /Sign-in required/],
  ['ERROR: [youtube] abc: The uploader has not made this video available in your country', 'tryAnother', /region/],
  ['ERROR: [youtube] abc: Premieres in 3 hours', 'retry', /not started/],
  ['ERROR: [youtube] abc: This live event will begin in 2 days.', 'retry', /not started/],
  ['ERROR: [youtube] abc: Video unavailable. This video has been removed by the uploader', 'tryAnother', /removed/],
  ['ERROR: Unsupported URL: https://example.com/page', 'tryAnother', /not supported/],
  ['ERROR: [Instagram] xyz: No video formats found!', 'updateEngine', /No downloadable formats/],
  ['ERROR: unable to download video data: HTTP Error 403: Forbidden', 'retry', /refused the download/],
  ['ERROR: unable to download video data: HTTP Error 429: Too Many Requests', 'retry', /limiting downloads/],
  ['ERROR: Postprocessing: Conversion failed!', 'tryAnother', /media failed/],
  ['ERROR: You have requested merging of multiple formats but ffmpeg is not installed.', 'updateEngine', /media processor is missing/],
  ['ERROR: unable to write data: [Errno 28] No space left on device', 'none', /disk is full/],
  ['ERROR: [TikTok] 671: Unable to download webpage: Failed to perform, curl: (28) Connection timed out after 20003 milliseconds', 'retry', /Network problem/],
  ['ERROR: Could not copy Chrome cookie database. See https://github.com/yt-dlp/yt-dlp/issues/7271 for more info', 'signIn', /browser cookies/],
]

for (const [stderr, action, message] of cases)
  test(`maps: ${stderr.slice(7, 70)}`, () => {
    const result = friendlyError(stderr)
    assert.equal(result.action, action)
    assert.match(result.message, message)
  })

test('warnings do not override the real error line', () => {
  const stderr = [
    'WARNING: [youtube] No supported JavaScript runtime could be found. Only deno is enabled by default; some formats may be missing',
    'WARNING: [youtube] HTTP Error 403: Forbidden. Retrying (1/3)...',
    'ERROR: [youtube] abc: Private video. Sign in if you have been granted access',
  ].join('\n')
  assert.equal(friendlyError(stderr).action, 'signIn')
  assert.match(lastErrorLine(stderr)!, /^ERROR: .*Private video/)
})

test('unknown errors keep a trimmed detail and suggest retry', () => {
  const result = friendlyError('ERROR: Something odd happened; please report this issue on https://github.com/yt-dlp/yt-dlp/issues')
  assert.equal(result.action, 'retry')
  assert.equal(result.detail, 'Something odd happened')
  assert.equal(friendlyError('').message, 'The download engine stopped. Try again.')
})

test('gallery fallback hints', () => {
  assert.ok(suggestsGallery('ERROR: Unsupported URL: https://imgur.com/a/x'))
  assert.ok(suggestsGallery('ERROR: [Instagram] C: There’s no video in this post'))
  assert.ok(suggestsGallery('ERROR: [twitter] 1: No video could be found in this tweet'))
  assert.ok(!suggestsGallery('ERROR: HTTP Error 403: Forbidden'))
})

test('user-facing text never names the underlying tools', async () => {
  const {scrubToolNames} = await import('./errors.js')
  const samples = [
    'ERROR: [youtube] abc: Something odd with yt-dlp.exe and ffmpeg',
    'gallery-dl: unexpected failure; aria2c exited',
    'Unknown thing from youtube-dl',
  ]
  for (const sample of samples) {
    assert.doesNotMatch(scrubToolNames(sample), /yt-dlp|ytdlp|ffmpeg|gallery-dl|aria2|youtube-?dl|ERROR:|\[youtube\]/i)
    assert.doesNotMatch(friendlyError(sample).message, /yt-dlp|ytdlp|ffmpeg|gallery-dl|aria2|youtube-?dl|ERROR:|\[youtube\]/i)
  }
  assert.match(friendlyError('ERROR: [youtube] abc: odd failure from yt-dlp').message, /^The download engine stopped: /)
})

const retryCases: [string, boolean, boolean][] = [
  ['ERROR: unable to download video data: Connection reset by peer', true, false],
  ['ERROR: [TikTok] 671: Unable to download webpage: Connection timed out', true, false],
  ['ERROR: unable to download video data: HTTP Error 503: Service Unavailable', true, false],
  ['ERROR: unable to download video data: HTTP Error 429: Too Many Requests', true, false],
  ['ERROR: fragment 12 not found, unable to continue: HTTP Error 403: Forbidden', true, false],
  ['ERROR: Postprocessing: Error merging formats', true, false],
  ['ERROR: [youtube] abc: Unable to extract player response', true, true],
  ['ERROR: something exploded unexpectedly', true, true],
  ["ERROR: [youtube] abc: Sign in to confirm you're not a bot", true, false],
  ['ERROR: [youtube] abc: Sign in to confirm your age', false, false],
  ['ERROR: [youtube] abc: Private video', false, false],
  ['ERROR: [youtube] abc: Join this channel to get access to members-only content', false, false],
  ['ERROR: [youtube] abc: Video unavailable. This video has been removed by the uploader', false, false],
  ['ERROR: The uploader has not made this video available in your country', false, false],
  ['ERROR: This video is not available due to a copyright claim; video unavailable', false, false],
  ['ERROR: Unsupported URL: https://example.com/page', false, false],
  ['ERROR: unable to write data: [Errno 28] No space left on device', false, false],
  ['ERROR: unable to open for writing: [Errno 13] Permission denied', false, false],
  ['ERROR: Requested format is not available. Use --list-formats', false, false],
  ['ERROR: [youtube] abc: Premieres in 3 hours', false, false],
  ['ERROR: This video is DRM protected', false, false],
]
for (const [stderr, retryable, engine] of retryCases)
  test(`retry policy: ${stderr.slice(7, 60)}`, () => {
    const result = friendlyError(stderr)
    assert.equal(result.retryable, retryable)
    assert.equal(result.engine, engine)
    assert.equal(retryPolicy(new Error(stderr)).retryable, retryable)
  })

const youtubeCases: [string, RegExp, {kind?: string; retryable: boolean; engine: boolean; action: string}][] = [
  [
    "ERROR: [youtube] fcXJMqDW8aI: Video unavailable. This content isn't available, try again later.",
    /limiting downloads from your network/,
    {kind: 'rateLimited', retryable: true, engine: false, action: 'retry'},
  ],
  [
    'ERROR: [download] Got error: HTTP Error 429: Too Many Requests',
    /Keepfin will retry automatically/,
    {kind: 'rateLimited', retryable: true, engine: false, action: 'retry'},
  ],
  [
    'ERROR: [youtube] abc: Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies for the authentication.',
    /Website sign-in/,
    {kind: 'botCheck', retryable: true, engine: false, action: 'signIn'},
  ],
  [
    'ERROR: [youtube] abc: Video unavailable',
    /didn't return this video right now/,
    {retryable: true, engine: false, action: 'retry'},
  ],
  [
    'ERROR: [youtube] abc: Video unavailable. This video has been removed by the uploader',
    /removed or is unavailable/,
    {retryable: false, engine: false, action: 'tryAnother'},
  ],
  [
    'ERROR: [youtube] abc: Video unavailable. This video is no longer available because the YouTube account associated with this video has been terminated.',
    /removed or is unavailable/,
    {retryable: false, engine: false, action: 'tryAnother'},
  ],
  [
    'ERROR: [youtube] abc: Unable to extract nsig function code; please report this issue',
    /Updating the engine/,
    {retryable: true, engine: true, action: 'updateEngine'},
  ],
  [
    'ERROR: [youtube] abc: nsig extraction failed: Some formats may be missing',
    /Updating the engine/,
    {retryable: true, engine: true, action: 'updateEngine'},
  ],
  [
    'ERROR: [youtube] abc: Precondition check failed.',
    /Updating the engine/,
    {retryable: true, engine: true, action: 'updateEngine'},
  ],
]
for (const [stderr, message, expected] of youtubeCases)
  test(`youtube classification: ${stderr.slice(7, 80)}`, () => {
    const result = friendlyError(stderr)
    assert.match(result.message, message)
    assert.equal(result.kind, expected.kind)
    assert.equal(result.retryable, expected.retryable)
    assert.equal(result.engine, expected.engine)
    assert.equal(result.action, expected.action)
  })

test('only extractor failures mention updating the engine', () => {
  for (const [stderr] of youtubeCases) {
    const mentions = /updat/i.test(friendlyError(stderr).message)
    assert.equal(mentions, /extract|nsig|Precondition/i.test(stderr), stderr)
  }
  assert.doesNotMatch(friendlyError('ERROR: Requested format is not available').message, /updat/i)
})

test('rate limits back off 60, 180, 300 s; a bot check retries once after 120 s', () => {
  const limited = retryPolicy(new Error("ERROR: [youtube] abc: Video unavailable. This content isn't available, try again later."))
  assert.deepEqual([...limited.backoff!], [60_000, 180_000, 300_000])
  assert.equal(limited.kind, 'rateLimited')
  const bot = retryPolicy(new Error("ERROR: [youtube] abc: Sign in to confirm you're not a bot"))
  assert.deepEqual([...bot.backoff!], [120_000])
  assert.equal(bot.kind, 'botCheck')
  // Classification carried on an engine error keeps its pacing.
  assert.deepEqual(retryPolicy({retryable: true, engine: false, kind: 'rateLimited', backoff: [1, 2]}), {
    retryable: true, engine: false, kind: 'rateLimited', backoff: [1, 2],
  })
})

test('error details are scrubbed of tool names and keep the last lines', async () => {
  const {errorDetailText} = await import('./errors.js')
  const error = Object.assign(new Error('x'), {
    stderr: 'WARNING: yt-dlp is old\nERROR: [youtube] abc: ffmpeg exited; see gallery-dl and aria2c\n',
  })
  const text = errorDetailText(error)!
  assert.doesNotMatch(text, /yt-dlp|ffmpeg|gallery-dl|aria2/i)
  assert.match(text, /\n/)
  assert.equal(errorDetailText(new Error('plain message')), 'plain message')
})

test('retry policy honours a classification already on the error and backs off 5, 15, 30 s', () => {
  assert.deepEqual(retryPolicy({retryable: false, engine: true}), {retryable: false, engine: true})
  assert.deepEqual(retryPolicy({retryable: true}), {retryable: true, engine: false})
  assert.deepEqual([...retryBackoff], [5000, 15000, 30000])
})

test('only the ambiguous YouTube unavailable error is probed for a regional restriction', async () => {
  const {isGenericUnavailable} = await import('./errors.js')
  assert.equal(isGenericUnavailable('ERROR: [youtube] jdOSfoV1njc: Video unavailable'), true)
  assert.equal(isGenericUnavailable('ERROR: [youtube] abc: Video unavailable. This video has been removed by the uploader'), false)
  assert.equal(isGenericUnavailable('ERROR: [youtube] abc: Video unavailable. This content isn\'t available, try again later.'), false)
  assert.equal(isGenericUnavailable('ERROR: Sign in to confirm your age'), false)
})
