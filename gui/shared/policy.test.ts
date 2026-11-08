import {test} from 'node:test'
import assert from 'node:assert/strict'
import {
  buildDownloadArgs,
  cookieArgs,
  formatArgs,
  formatLabel,
  jobQuote,
  metadataArgs,
  metadataLiteral,
  networkArgs,
  presetFormat,
  validateFormatRequest,
  validateOptions,
  validateProxy,
  ENCODING_ARGS,
  FILE_PREFIX,
} from './policy.js'
import type {DownloadOptions, FormatRequest} from './contracts.js'

// The core builds the selection and the option arguments together; these views keep each test about one half.
const videoSelection = (format: FormatRequest) => formatArgs(format, {})
const audioSelection = videoSelection
const optionArgs = (format: FormatRequest, options: DownloadOptions, chapterTemplate?: string, extra: {squareCover?: boolean} = {}) =>
  formatArgs(format, options, {chapterTemplate, ...extra}).slice(formatArgs(format, {}).length)

const arg = (args: string[], flag: string) => args[args.indexOf(flag) + 1]

test('exact video + audio ids are honoured with safe fallbacks', () => {
  const args = videoSelection({type: 'video', formatId: '299', audioFormatId: '140', codec: 'auto', container: 'mp4'})
  assert.equal(arg(args, '-f'), '299+140/299/bv*+ba/b')
  assert.equal(arg(args, '--merge-output-format'), 'mp4')
  assert.equal(arg(args, '--remux-video'), 'mp4')
  assert.ok(!arg(args, '-S').includes('hdr:sdr'), 'explicit picks may be HDR')
})

test('exact video id without audio picks a container-compatible companion', () => {
  const mp4 = videoSelection({type: 'video', formatId: '337', codec: 'auto', container: 'mp4'})
  assert.equal(arg(mp4, '-f'), '337+ba[ext=m4a]/337+ba/337/bv*+ba/b')
  const webm = videoSelection({type: 'video', formatId: '337', codec: 'auto', container: 'webm'})
  assert.equal(arg(webm, '-f'), '337+ba[ext=webm]/337+ba/337/bv*[ext=webm]+ba[ext=webm]/bv*+ba/b')
  assert.equal(arg(webm, '--merge-output-format'), 'webm/mkv')
  assert.ok(!webm.includes('--remux-video'))
})

test('generic video uses -S with resolution cap, fps, SDR and codec preference', () => {
  const args = videoSelection({type: 'video', maxHeight: 1080, codec: 'auto', container: 'mp4'})
  assert.equal(arg(args, '-f'), 'bv*+ba/b')
  assert.equal(arg(args, '-S'), 'res:1080,fps,hdr:sdr,vcodec:h264,acodec:aac')
  const av1 = videoSelection({type: 'video', fps: 30, codec: 'av1', container: 'mkv'})
  assert.equal(arg(av1, '-S'), 'res,fps:30,hdr:sdr,vcodec:av01')
})

test('audio targets: conversion, quality and stream preference', () => {
  assert.deepEqual(audioSelection({type: 'audio', target: 'mp3', quality: '320'}), [
    '-f', 'ba/b', '-x', '--audio-format', 'mp3', '--audio-quality', '320K',
  ])
  assert.deepEqual(audioSelection({type: 'audio', target: 'm4a', quality: 'best'}), [
    '-f', 'ba[ext=m4a]/ba[acodec^=mp4a]/ba/b', '-x', '--audio-format', 'm4a', '--audio-quality', '0',
  ])
  assert.deepEqual(audioSelection({type: 'audio', target: 'flac', quality: '128'}), [
    '-f', 'ba/b', '-x', '--audio-format', 'flac',
  ])
  assert.deepEqual(audioSelection({type: 'audio', target: 'original', quality: 'best', formatId: '251'}), [
    '-f', '251/ba/b', '-x',
  ])
})

test('options map to post-processing flags only where the container supports them', () => {
  const video: FormatRequest = {type: 'video', codec: 'auto', container: 'mp4'}
  const args = optionArgs(video, {
    subtitles: {enabled: true, languages: 'en.*,ar', auto: true, embed: true},
    embedThumbnail: true,
    embedMetadata: true,
    embedChapters: true,
    sponsorBlock: 'remove',
    section: {start: 10, end: 75.5},
    writeDescription: true,
  })
  assert.deepEqual(args, [
    '--write-subs', '--sub-langs', 'en.*,ar', '--write-auto-subs', '--embed-subs',
    '--embed-thumbnail', '--convert-thumbnails', 'jpg',
    '--embed-metadata', '--embed-chapters',
    '--sponsorblock-remove', 'sponsor,selfpromo,interaction',
    '--download-sections', '*10-75.5',
    '--write-description',
  ])
  const webm = optionArgs({type: 'video', codec: 'auto', container: 'webm'}, {embedThumbnail: true})
  assert.deepEqual(webm, [], 'WebM cannot hold cover art')
  const audio = optionArgs({type: 'audio', target: 'mp3', quality: 'best'}, {
    subtitles: {enabled: true, languages: 'en', auto: false, embed: true},
    splitChapters: true,
    section: {start: 5},
  }, 'C:/out/Album - %(section_number)03d %(section_title)s.%(ext)s')
  assert.deepEqual(audio, [
    '--download-sections', '*5-inf',
    '--split-chapters', '-o', 'chapter:C:/out/Album - %(section_number)03d %(section_title)s.%(ext)s',
  ])
})

test('network: retries, fragments clamp, rate limit, proxy and cookies', () => {
  const args = networkArgs({fragments: 40, rateLimit: 2_000_000, proxy: 'socks5://127.0.0.1:9050'}, {browser: 'firefox'})
  assert.equal(arg(args, '--concurrent-fragments'), '16')
  assert.equal(arg(args, '--limit-rate'), '2000000')
  assert.equal(arg(args, '--proxy'), 'socks5://127.0.0.1:9050')
  assert.equal(arg(args, '--cookies-from-browser'), 'firefox')
  assert.equal(arg(args, '--retries'), '10')
  assert.ok(args.includes('http:exp=1:20'))
  assert.deepEqual(networkArgs({fragments: 4, rateLimit: 0, proxy: ''}, {file: 'C:/c.txt'}).slice(-2), ['--cookies', 'C:/c.txt'])
  assert.throws(() => networkArgs({fragments: 1, rateLimit: 0, proxy: ''}, {browser: 'safari; rm' as never}))
})

test('full argument list ends with the source and prints the moved file', () => {
  const args = buildDownloadArgs({
    source: {url: 'https://www.youtube.com/watch?v=x'},
    format: presetFormat('720'),
    options: {},
    template: 'C:/Downloads/Clip.%(ext)s',
    network: {fragments: 8, rateLimit: 0, proxy: ''},
    tools: {ffmpeg: 'C:/ffmpeg.exe', jsRuntime: 'C:/Keepfin.exe', cacheDir: 'C:/cache'},
  })
  assert.equal(args[0], '--ignore-config')
  // Without this flag the engine drops every non-Latin character from the path it prints (Windows console code page).
  assert.equal(arg(args, '--encoding'), 'utf-8')
  assert.deepEqual(ENCODING_ARGS, ['--encoding', 'utf-8'])
  assert.equal(arg(args, '--js-runtimes'), 'node:C:/Keepfin.exe')
  assert.equal(arg(args, '--print'), `after_move:${FILE_PREFIX}%(filepath)s`)
  assert.deepEqual(args.slice(-2), ['--', 'https://www.youtube.com/watch?v=x'])
  const cached = buildDownloadArgs({
    source: {infoJson: 'C:/cache/info.json'},
    format: presetFormat('audio-mp3'),
    options: {},
    template: 'C:/Downloads/Clip.%(ext)s',
    network: {fragments: 8, rateLimit: 0, proxy: ''},
    tools: {},
  })
  assert.deepEqual(cached.slice(-2), ['--load-info-json', 'C:/cache/info.json'])
  assert.throws(() => buildDownloadArgs({...{source: {url: 'https://x.test'}, options: {}, template: 'x', network: {fragments: 1, rateLimit: 0, proxy: ''}, tools: {}}, format: {type: 'gallery'}}))
})

test('renderer input validation rejects selector injection and unknown values', () => {
  assert.deepEqual(validateFormatRequest({type: 'video', formatId: 'hls-1080', container: 'mkv', codec: 'vp9', maxHeight: 1080}), {
    type: 'video', maxHeight: 1080, formatId: 'hls-1080', audioFormatId: undefined, fps: undefined, codec: 'vp9', container: 'mkv',
  })
  for (const formatId of ['299+140', 'b/bv*', '137[height>1]', 'a b', 'x,y', "a'b"])
    assert.throws(() => validateFormatRequest({type: 'video', formatId, codec: 'auto', container: 'mp4'}), /Choose a format/)
  assert.throws(() => validateFormatRequest({type: 'video', container: 'avi'}))
  assert.throws(() => validateFormatRequest({type: 'audio', target: 'exe'}))
  assert.throws(() => validateFormatRequest({type: 'video', maxHeight: 1.5}))
  assert.throws(() => validateFormatRequest({type: 'shell'}))
  assert.deepEqual(validateFormatRequest({type: 'gallery', extra: 1}), {type: 'gallery'})
})

test('option validation keeps known fields and rejects bad ranges and languages', () => {
  assert.deepEqual(validateOptions({embedMetadata: true, args: ['--exec', 'calc'], sponsorBlock: 'mark'}), {
    embedMetadata: true,
    sponsorBlock: 'mark',
  })
  assert.throws(() => validateOptions({section: {start: 30, end: 10}}), /after its start/)
  assert.throws(() => validateOptions({section: {start: -1}}))
  assert.throws(() => validateOptions({subtitles: {enabled: true, languages: 'en;calc'}}), /language codes/)
  assert.throws(() => validateOptions({sponsorBlock: 'all'}))
  assert.deepEqual(validateOptions(undefined), {})
})

test('proxy validation allows only http/https/socks5(h) without paths', () => {
  assert.equal(validateProxy(''), '')
  assert.equal(validateProxy('http://user:pw@proxy.local:8080'), 'http://user:pw@proxy.local:8080')
  assert.equal(validateProxy('socks5h://127.0.0.1:1080/'), 'socks5h://127.0.0.1:1080')
  assert.throws(() => validateProxy('file:///etc/passwd'))
  assert.throws(() => validateProxy('ftp://proxy:21'))
  assert.throws(() => validateProxy('http://proxy:8080/path'))
  assert.throws(() => validateProxy('not a url'))
})

test('presets and labels', () => {
  assert.deepEqual(presetFormat('1080'), {type: 'video', maxHeight: 1080, codec: 'auto', container: 'mp4'})
  assert.deepEqual(presetFormat('audio-flac'), {type: 'audio', target: 'flac', quality: 'best'})
  assert.throws(() => presetFormat('8k'))
  assert.equal(formatLabel(presetFormat('best')), 'Best quality · MP4')
  const quality = {id: '1080p60', formatId: '299', height: 1080, fps: 60, hdr: false, codec: 'h264' as const, ext: 'mp4', hasAudio: false, sizeApprox: false}
  assert.equal(jobQuote({type: 'video', codec: 'h264', container: 'mp4'}, {video: [quality], audio: []}).label, '1080p60 · MP4 · H.264')
  assert.equal(formatLabel({type: 'audio', target: 'mp3', quality: '320'}), 'MP3 · 320 kbps')
  assert.equal(formatLabel({type: 'gallery'}), 'Images & media')
})

test('YouTube audio covers are cropped to a centred square; video and other sites are unchanged', () => {
  const audio: FormatRequest = {type: 'audio', target: 'mp3', quality: 'best'}
  const square = optionArgs(audio, {embedThumbnail: true}, undefined, {squareCover: true})
  assert.deepEqual(square.slice(0, 3), ['--embed-thumbnail', '--convert-thumbnails', 'jpg'])
  const ppa = square[square.indexOf('--ppa') + 1]
  assert.ok(ppa.startsWith('ThumbnailsConvertor+FFmpeg_o:'))
  assert.match(ppa, /-vf crop="'if\(gt\(ih,iw\),iw,ih\)':'if\(gt\(iw,ih\),ih,iw\)'"$/)
  assert.deepEqual(optionArgs(audio, {embedThumbnail: true}), ['--embed-thumbnail', '--convert-thumbnails', 'jpg'])
  assert.ok(!optionArgs(audio, {embedThumbnail: false}, undefined, {squareCover: true}).includes('--ppa'))
  const video: FormatRequest = {type: 'video', codec: 'auto', container: 'mp4'}
  assert.ok(!optionArgs(video, {embedThumbnail: true}, undefined, {squareCover: true}).includes('--ppa'))
})

test('gentle YouTube batches add a request pause only when asked', () => {
  const input = {
    source: {url: 'https://music.youtube.com/watch?v=x'},
    format: {type: 'audio', target: 'mp3', quality: 'best'} as FormatRequest,
    options: {embedThumbnail: true},
    template: 'C:/out/a.%(ext)s',
    network: {fragments: 4, rateLimit: 0, proxy: ''},
    tools: {},
  }
  const gentle = buildDownloadArgs({...input, youtube: true, sleepRequests: 0.75})
  assert.equal(arg(gentle, '--sleep-requests'), '0.75')
  assert.ok(gentle.includes('--ppa'))
  const plain = buildDownloadArgs(input)
  assert.ok(!plain.includes('--sleep-requests') && !plain.includes('--ppa'))
})

test('embedded metadata carries the clean title and keeps the original wording as the description', () => {
  assert.equal(metadataLiteral('Hello: 100% world'), 'Hello\\: 100%% world')
  assert.equal(metadataLiteral('Hello'), '%(keepfin_literal|Hello)s')
  assert.deepEqual(metadataArgs('A: B'), ['--parse-metadata', '%(description,title)s:%(meta_description)s', '--parse-metadata', 'A\\: B:%(meta_title)s'])
  const base = {
    source: {url: 'https://x.test/v'}, format: presetFormat('720'), template: 'C:/D/Clip.%(ext)s',
    network: {fragments: 8, rateLimit: 0, proxy: ''}, tools: {}, title: 'Clean title',
  }
  assert.ok(buildDownloadArgs({...base, options: {embedMetadata: true}}).includes('%(description,title)s:%(meta_description)s'))
  assert.ok(!buildDownloadArgs({...base, options: {}}).includes('--parse-metadata'))
  assert.ok(!buildDownloadArgs({...base, options: {embedMetadata: true}, preview: true}).includes('--parse-metadata'))
})

test('cookies from a Firefox profile: absolute folder only, Firefox only, no container separator', () => {
  const profile = String.raw`C:\Users\a\AppData\Roaming\Mozilla\Firefox\Profiles\x.default`
  assert.deepEqual(cookieArgs({browser: 'firefox', profile}), ['--cookies-from-browser', `firefox:${profile}`])
  assert.deepEqual(cookieArgs({browser: 'firefox', profile: '/home/a/.mozilla/firefox/x.default'}), ['--cookies-from-browser', 'firefox:/home/a/.mozilla/firefox/x.default'])
  assert.deepEqual(cookieArgs({browser: 'firefox'}), ['--cookies-from-browser', 'firefox'])
  for (const bad of ['', 'x.default', String.raw`..\x`,'C:x', `${profile}::work`, `${profile}\nx`]) assert.throws(() => cookieArgs({browser: 'firefox', profile: bad}), /profile/, bad)
  assert.throws(() => cookieArgs({browser: 'chrome', profile}), /profile/)
})
