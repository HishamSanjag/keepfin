import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {
  audioOptionLabel,
  estimateSize,
  mediaInfoFromYtDlp,
  subtitleTracks,
  videoCodec,
  type RawInfo,
} from './formats.js'

const fixtures = path.join(path.dirname(fileURLToPath(import.meta.url)), '../desktop/fixtures')
const load = (name: string): RawInfo => JSON.parse(fs.readFileSync(path.join(fixtures, name), 'utf8'))

test('4K HDR YouTube: one option per height+fps+HDR, storyboards dropped, H.264 preferred for SDR', () => {
  const info = load('youtube-4k-hdr.info.json')
  const media = mediaInfoFromYtDlp(info, 'https://www.youtube.com/watch?v=LXb3EKWsInQ')
  assert.equal(media.provider, 'YouTube')
  assert.equal(media.engine, 'yt-dlp')
  assert.equal(media.duration, 314)
  const ids = media.video.map((v) => v.id)
  assert.equal(new Set(ids).size, ids.length, 'options are deduplicated')
  assert.ok(ids.includes('2160p60-hdr'))
  assert.ok(ids.includes('2160p60'))
  assert.ok(ids.includes('1080p60'))
  assert.equal(ids[0], '2160p60-hdr', 'best first')
  assert.ok(!media.video.some((v) => v.ext === 'mhtml'))
  const fullHd = media.video.find((v) => v.id === '1080p60')!
  assert.equal(fullHd.codec, 'h264')
  assert.equal(fullHd.formatId, '299', 'direct https stream beats the HLS twin')
  assert.equal(fullHd.hasAudio, false)
  // 299 (165,950,262 B) + best m4a companion 140 (5,079,985 B)
  assert.equal(fullHd.size, 165_950_262 + 5_079_985)
  assert.equal(fullHd.sizeApprox, false)
  const uhd = media.video.find((v) => v.id === '2160p60')!
  assert.ok(['vp9', 'av1'].includes(uhd.codec))
})

test('audio options keep the original language variants and one stream per dub, original first', () => {
  const info = load('youtube-dubbed.info.json')
  const audio = mediaInfoFromYtDlp(info, 'https://www.youtube.com/watch?v=aqz-KE-bpKQ').audio
  assert.ok(audio.length > 20 && audio.length <= 40)
  assert.equal(audio[0].language, 'en-US')
  assert.match(audio[0].note ?? '', /original/)
  const languages = audio.filter((a) => a.language !== 'en-US').map((a) => a.language)
  assert.equal(new Set(languages).size, languages.length, 'one per dubbed language')
  const arabic = audio.find((a) => a.language === 'ar')!
  assert.equal(arabic.codec, 'aac', 'dubs prefer AAC for compatibility')
  assert.match(arabic.note ?? '', /dubbed/)
  assert.match(audioOptionLabel(audio[0]), /kbps · en-US · original/)
})

test('DRC variants are labelled and ranked after normal streams', () => {
  const audio = mediaInfoFromYtDlp(load('youtube-4k-hdr.info.json'), 'https://www.youtube.com/watch?v=LXb3EKWsInQ').audio
  const drc = audio.filter((a) => a.note?.includes('DRC'))
  assert.ok(drc.length > 0)
  const firstDrc = audio.findIndex((a) => a.note?.includes('DRC'))
  assert.ok(audio.slice(0, firstDrc).every((a) => !a.note?.includes('DRC')))
})

test('subtitles: manual tracks kept, auto captions deduplicated and capped', () => {
  const tracks = subtitleTracks(load('youtube-dubbed.info.json'))
  const manual = tracks.filter((t) => !t.auto)
  const auto = tracks.filter((t) => t.auto)
  assert.equal(manual.length, 17)
  assert.ok(auto.length <= 24)
  for (const t of auto) assert.ok(!manual.some((m) => m.lang === t.lang))
})

test('YouTube Music track exposes music metadata and the YouTube Music provider', () => {
  const media = mediaInfoFromYtDlp(load('youtube-music-track.info.json'), 'https://music.youtube.com/watch?v=4D7u5KF7SP8')
  assert.equal(media.provider, 'YouTube Music')
  assert.equal(media.music?.artist, 'Daft Punk, Pharrell Williams, Nile Rodgers')
  assert.equal(media.music?.album, 'Random Access Memories')
  assert.equal(media.music?.releaseYear, 2013)
  assert.ok(media.audio.length > 0)
})

test('audio-only sources: SoundCloud and Bandcamp have no video options and approximate sizes', () => {
  const sc = mediaInfoFromYtDlp(load('soundcloud-track.info.json'), 'https://soundcloud.com/forss/flickermood')
  assert.equal(sc.provider, 'SoundCloud')
  assert.equal(sc.video.length, 0)
  // One per codec: AAC 160 (HLS) and the direct-http MP3 beats its HLS twin.
  assert.deepEqual(
    sc.audio.map((a) => a.formatId),
    ['hls_aac_160k', 'http_mp3_0_0'],
  )
  assert.ok(sc.audio.every((a) => a.sizeApprox))
  const bc = mediaInfoFromYtDlp(load('bandcamp-track.info.json'), 'https://youtube-dl.bandcamp.com/track/youtube-dl-test-song')
  assert.equal(bc.provider, 'Bandcamp')
  assert.equal(bc.audio[0].codec, 'mp3')
})

test('progressive HLS sources (Dailymotion) are muxed options with bitrate size estimates', () => {
  const media = mediaInfoFromYtDlp(load('dailymotion-video.info.json'), 'https://www.dailymotion.com/video/x5kesuj')
  assert.equal(media.provider, 'Dailymotion')
  assert.deepEqual(
    media.video.map((v) => v.height),
    [1080, 720, 480, 288],
  )
  assert.ok(media.video.every((v) => v.hasAudio && v.sizeApprox && v.size! > 0))
  // tbr 6221.6 kbit/s × 187 s / 8
  assert.equal(media.video[0].size, Math.round((6221.6 * 1000 * 187) / 8))
})

test('playlist context only for watch URLs carrying list=', () => {
  const info = load('youtube-music-track.info.json')
  assert.deepEqual(
    mediaInfoFromYtDlp(info, 'https://www.youtube.com/watch?v=4D7u5KF7SP8&list=PL123456').playlist,
    {url: 'https://www.youtube.com/playlist?list=PL123456', title: undefined},
  )
  assert.equal(mediaInfoFromYtDlp(info, 'https://www.youtube.com/watch?v=4D7u5KF7SP8').playlist, undefined)
})

test('size estimate precedence and codec mapping', () => {
  assert.deepEqual(estimateSize({filesize: 10, filesize_approx: 20}), {size: 10, approx: false})
  assert.deepEqual(estimateSize({filesize_approx: 20, tbr: 100}, 10), {size: 20, approx: true})
  assert.deepEqual(estimateSize({tbr: 800}, 10), {size: 1_000_000, approx: true})
  assert.deepEqual(estimateSize({}), {approx: true})
  assert.equal(videoCodec('avc1.64002A'), 'h264')
  assert.equal(videoCodec('vp09.02.51.10'), 'vp9')
  assert.equal(videoCodec('av01.0.13M.10'), 'av1')
  assert.equal(videoCodec('hvc1.2.4'), 'hevc')
})

test('portrait videos are labelled by their short side and live flags survive', () => {
  const media = mediaInfoFromYtDlp(
    {
      title: 'Vertical',
      live_status: 'is_live',
      formats: [{format_id: 'v', vcodec: 'h264', acodec: 'aac', width: 1080, height: 1920, tbr: 2000, ext: 'mp4', protocol: 'https'}],
    },
    'https://www.tiktok.com/@a/video/1',
  )
  assert.equal(media.video[0].height, 1080)
  assert.equal(media.isLive, true)
  assert.equal(media.provider, 'TikTok')
})

test('hostile info JSON never throws or leaks non-http thumbnails', () => {
  const media = mediaInfoFromYtDlp(
    {title: 42, thumbnail: 'file:///etc/passwd', formats: [null, 'x', {format_id: 'bad id+1'.repeat(20)}]} as never,
    'https://example.com/v',
  )
  assert.equal(media.title, 'example.com video')
  assert.equal(media.thumbnail, undefined)
  assert.equal(media.video.length, 0)
})

test('inspection captures genre (list and single), tags and categories as normalised tags', () => {
  const url = 'https://www.youtube.com/watch?v=LXb3EKWsInQ'
  const base = {title: 'Song', formats: []}
  const media = mediaInfoFromYtDlp({...base, genres: ['Electronic', 'Ambient'], genre: 'Ambient', tags: ['Lo-Fi', ' chill ', 'https://example.com/x'], categories: ['Music']}, url)
  assert.deepEqual(media.tags, ['electronic', 'ambient', 'lo-fi', 'chill', 'music'])
  assert.equal(mediaInfoFromYtDlp({...base, genre: 'Jazz, Blues'}, url).tags?.join(), 'jazz,blues')
  assert.equal(mediaInfoFromYtDlp(base, url).tags, undefined)
  assert.equal(mediaInfoFromYtDlp({...base, tags: Array.from({length: 40}, (_, n) => `tag${n}`)}, url).tags?.length, 15)
})
