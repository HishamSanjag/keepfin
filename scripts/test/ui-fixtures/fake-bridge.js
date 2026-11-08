// Fake desktop bridge for renderer tests. Injected with page.addInitScript before the app loads.
// Everything here is SYNTHETIC fixture data (titles, creators, sizes, artwork): it exercises the
// v2 contract (gui/shared/contracts.ts) and never certifies real provider behaviour.
;(() => {
  const options = Object.assign({scenario: 'full', simulate: true, latency: 1}, window.__FAKE_OPTIONS || {})
  const listeners = new Set()
  const now = Date.now()
  const min = 60_000
  const hour = 3_600_000
  const day = 86_400_000
  const MB = 1_048_576
  const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms * options.latency))
  const clone = (value) => JSON.parse(JSON.stringify(value))
  const fake = {
    calls: [],
    thumbCalls: {},
    pushes: 0,
    simulate: options.simulate,
    push,
    freeze() {
      fake.simulate = false
    },
    // Tests move a job along by hand (then the renderer is told with a snapshot).
    patchJob(id, change) {
      patch(id, change)
      pushSnapshot()
    },
    resume() {
      fake.simulate = true
    },
  }
  window.__fake = fake
  window.__calls = fake.calls
  window.__keepfinTest = {rowRenders: {}}

  // ---------------------------------------------------------------- artwork
  const palettes = [
    ['#0d1b2a', '#1b3a5c', '#e0a96d', '#2d4a3e', '#16302b'],
    ['#1a1423', '#3d2c5e', '#f2b880', '#5b3a6e', '#2a1f3d'],
    ['#06131f', '#0f4c75', '#bbe1fa', '#3282b8', '#1b262c'],
    ['#2b0f0e', '#7a2e1f', '#ffcf70', '#a0522d', '#3b1a12'],
    ['#0b1e16', '#1e5640', '#c9f2c7', '#2f7a54', '#10291f'],
    ['#14121f', '#2a2350', '#ff8fab', '#5a4fcf', '#1d1838'],
  ]
  function hash(text) {
    let h = 2166136261
    for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619)
    return h >>> 0
  }
  function rng(seed) {
    let s = seed || 1
    return () => ((s = Math.imul(s ^ (s >>> 15), 2246822507) ^ Math.imul(s ^ (s >>> 13), 3266489909)) >>> 0) / 4294967296
  }
  const art = new Map()
  function artwork(key, square) {
    const id = `${key}|${square ? 1 : 0}`
    if (art.has(id)) return art.get(id)
    const r = rng(hash(key))
    const p = palettes[hash(key) % palettes.length]
    const w = square ? 320 : 480
    const h = square ? 320 : 270
    const canvas = document.createElement('canvas')
    canvas.width = w
    canvas.height = h
    const g = canvas.getContext('2d')
    const sky = g.createLinearGradient(0, 0, 0, h)
    sky.addColorStop(0, p[0])
    sky.addColorStop(0.65, p[1])
    sky.addColorStop(1, p[2])
    g.fillStyle = sky
    g.fillRect(0, 0, w, h)
    if (square) {
      // Album-cover composition: bold disc and bands.
      g.fillStyle = p[2]
      g.beginPath()
      g.arc(w * (0.3 + r() * 0.4), h * (0.3 + r() * 0.3), w * (0.18 + r() * 0.12), 0, Math.PI * 2)
      g.fill()
      for (let i = 0; i < 4; i++) {
        g.fillStyle = p[3 + (i % 2)]
        g.globalAlpha = 0.55 + r() * 0.4
        g.fillRect(0, h * (0.55 + i * 0.11), w, h * 0.07)
      }
      g.globalAlpha = 1
    } else {
      g.fillStyle = p[2]
      g.globalAlpha = 0.9
      g.beginPath()
      g.arc(w * (0.2 + r() * 0.6), h * (0.25 + r() * 0.2), 18 + r() * 26, 0, Math.PI * 2)
      g.fill()
      g.globalAlpha = 1
      for (let layer = 0; layer < 3; layer++) {
        g.fillStyle = p[3 + (layer % 2)]
        g.globalAlpha = 0.55 + layer * 0.2
        g.beginPath()
        const base = h * (0.55 + layer * 0.14)
        g.moveTo(0, h)
        g.lineTo(0, base)
        let x = 0
        while (x < w) {
          x += 30 + r() * 70
          g.lineTo(x, base - r() * h * (0.32 - layer * 0.07))
        }
        g.lineTo(w, h)
        g.closePath()
        g.fill()
      }
      g.globalAlpha = 1
    }
    const vignette = g.createRadialGradient(w / 2, h / 2, h * 0.3, w / 2, h / 2, w * 0.75)
    vignette.addColorStop(0, 'rgba(0,0,0,0)')
    vignette.addColorStop(1, 'rgba(0,0,0,0.35)')
    g.fillStyle = vignette
    g.fillRect(0, 0, w, h)
    const url = canvas.toDataURL('image/jpeg', 0.86)
    art.set(id, url)
    return url
  }

  // ---------------------------------------------------------------- inspection fixtures
  const yt = (id) => `https://www.youtube.com/watch?v=${id}`
  const v = (height, fps, hdr, codec, ext, formatId, size, approx = false, extra = {}) => ({
    id: `${height}p${fps}${hdr ? '-hdr' : ''}-${codec}`,
    height,
    width: Math.round((height * 16) / 9),
    fps,
    hdr,
    codec,
    ext,
    formatId,
    hasAudio: false,
    size,
    sizeApprox: approx,
    tbr: Math.round((size * 8) / 1000 / 512),
    ...extra,
  })
  const a = (formatId, codec, ext, abr, size, language, note, approx = false) => ({
    id: `a-${formatId}`,
    formatId,
    codec,
    ext,
    abr,
    size,
    sizeApprox: approx,
    language,
    note,
  })
  const media = {
    [yt('lofoten8k')]: {
      url: yt('lofoten8k'),
      title: 'Lofoten in Winter — Arctic Light Drone Film (4K HDR 60fps)',
      uploader: 'Northbound Films',
      duration: 512,
      thumbnail: 'https://i.ytimg.com/vi/lofoten8k/maxresdefault.jpg',
      extractor: 'Youtube',
      provider: 'YouTube',
      engine: 'yt-dlp',
      video: [
        v(2160, 60, true, 'av1', 'mp4', '701', 1.62e9),
        v(2160, 60, true, 'vp9', 'webm', '337', 2.04e9),
        v(2160, 60, false, 'vp9', 'webm', '315', 1.31e9),
        v(1440, 60, true, 'vp9', 'webm', '336', 842e6),
        v(1440, 60, false, 'av1', 'mp4', '400', 590e6),
        v(1080, 60, false, 'av1', 'mp4', '399', 298e6),
        v(1080, 60, false, 'h264', 'mp4', '299', 351e6, true),
        v(1080, 30, false, 'h264', 'mp4', '137', 214e6),
        v(720, 60, false, 'h264', 'mp4', '298', 151e6),
        v(480, 30, false, 'h264', 'mp4', '135', 61e6, true),
        v(360, 30, false, 'h264', 'mp4', '18', 38e6, false, {hasAudio: true}),
        v(144, 30, false, 'av1', 'mp4', '394', 6.4e6),
      ],
      audio: [
        a('251', 'opus', 'webm', 152, 9.7e6, 'en', 'original'),
        a('140', 'aac', 'm4a', 129, 8.3e6, 'en', 'original'),
        a('251-es', 'opus', 'webm', 148, 9.4e6, 'es', 'dubbed'),
        a('140-es', 'aac', 'm4a', 129, 8.3e6, 'es', 'dubbed'),
        a('249', 'opus', 'webm', 54, 3.4e6, 'en', 'original', true),
      ],
      subtitles: [
        {lang: 'en', name: 'English', auto: false},
        {lang: 'no', name: 'Norwegian', auto: false},
        {lang: 'de', name: 'German', auto: false},
        {lang: 'ja', auto: true},
      ],
      chapters: 9,
    },
    [yt('focusmix&list=PLfocus')]: null, // filled below (watch link inside a playlist)
    'https://music.youtube.com/watch?v=tidal88': {
      url: 'https://music.youtube.com/watch?v=tidal88',
      title: 'Tidal (Night Version)',
      uploader: 'Mira Halden',
      duration: 247,
      thumbnail: 'https://lh3.googleusercontent.com/tidal88-cover',
      extractor: 'Youtube',
      provider: 'YouTube Music',
      engine: 'yt-dlp',
      music: {artist: 'Mira Halden', album: 'Low Tide Hours', track: 'Tidal (Night Version)', releaseYear: 2025},
      video: [],
      audio: [a('251', 'opus', 'webm', 160, 4.9e6, 'en'), a('140', 'aac', 'm4a', 128, 3.9e6, 'en'), a('139', 'aac', 'm4a', 48, 1.5e6, 'en')],
      subtitles: [],
      chapters: 0,
    },
    'https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC': {
      url: 'https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC',
      title: 'Glass Harbour',
      uploader: 'The Overcast Choir',
      duration: 213,
      thumbnail: 'https://i.scdn.co/image/glass-harbour',
      provider: 'Spotify',
      engine: 'music',
      music: {artist: 'The Overcast Choir', album: 'Weather Systems', track: 'Glass Harbour', releaseYear: 2024},
      match: {
        source: 'spotify',
        sourceUrl: 'https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC',
        matchedUrl: 'https://music.youtube.com/watch?v=glassharb',
        matchedTitle: 'Glass Harbour',
        confidence: 0.92,
      },
      video: [],
      audio: [a('251', 'opus', 'webm', 160, 4.3e6, 'en'), a('140', 'aac', 'm4a', 128, 3.4e6, 'en')],
      subtitles: [],
      chapters: 0,
    },
    'https://www.instagram.com/p/C9harbour/': {
      url: 'https://www.instagram.com/p/C9harbour/',
      title: 'Morning light over the harbour — 8 frames',
      uploader: 'saltandsilver',
      thumbnail: 'https://instagram.fcdn/harbour-0.jpg',
      extractor: 'gallery-dl:instagram',
      provider: 'Instagram',
      engine: 'gallery-dl',
      video: [],
      audio: [],
      subtitles: [],
      chapters: 0,
      gallery: {
        count: 8,
        previews: Array.from({length: 8}, (_, i) => `https://instagram.fcdn/harbour-${i}.jpg`),
        items: Array.from({length: 8}, (_, i) => ({position: i + 1, url: `https://instagram.fcdn/harbour-${i}.jpg`, photo: true})),
        truncated: false,
      },
    },
    // A carousel of seven: six photos and a video (the fourth).
    'https://www.instagram.com/p/C9carousel/': {
      url: 'https://www.instagram.com/p/C9carousel/',
      title: 'Market day — 7 slides',
      uploader: 'saltandsilver',
      thumbnail: 'https://instagram.fcdn/market-0.jpg',
      extractor: 'gallery-dl:instagram',
      provider: 'Instagram',
      engine: 'gallery-dl',
      video: [],
      audio: [],
      subtitles: [],
      chapters: 0,
      gallery: {
        count: 7,
        previews: Array.from({length: 6}, (_, i) => `https://instagram.fcdn/market-${i}.jpg`),
        items: Array.from({length: 7}, (_, i) => ({
          position: i + 1,
          url: i === 3 ? 'https://instagram.fcdn/market-3.mp4' : `https://instagram.fcdn/market-${i}.jpg`,
          photo: i !== 3,
        })),
        truncated: false,
      },
    },
    'https://www.tiktok.com/@kitchenlab/video/7391': {
      url: 'https://www.tiktok.com/@kitchenlab/video/7391',
      title: 'One-pan lemon orzo in 60 seconds',
      uploader: 'kitchenlab',
      duration: 61,
      thumbnail: 'https://p16.tiktokcdn.com/orzo.jpg',
      provider: 'TikTok',
      engine: 'yt-dlp',
      video: [
        v(1080, 30, false, 'h264', 'mp4', 'h264_1080', 18.2e6, false, {hasAudio: true}),
        v(720, 30, false, 'h264', 'mp4', 'h264_720', 9.1e6, false, {hasAudio: true}),
        v(540, 30, false, 'hevc', 'mp4', 'bytevc1_540', 5.2e6, true, {hasAudio: true}),
      ],
      audio: [],
      subtitles: [],
      chapters: 0,
    },
    'https://vimeo.com/824512': {
      url: 'https://vimeo.com/824512',
      title: 'Salt Roads — a short documentary',
      uploader: 'Field Notes Studio',
      duration: 1342,
      thumbnail: 'https://i.vimeocdn.com/salt.jpg',
      provider: 'Vimeo',
      engine: 'yt-dlp',
      video: [
        v(2160, 24, false, 'h264', 'mp4', 'hls-4k', 3.1e9, true),
        v(1080, 24, false, 'h264', 'mp4', 'hls-1080', 812e6, true),
        v(720, 24, false, 'h264', 'mp4', 'hls-720', 402e6, true),
      ],
      audio: [a('hls-audio', 'aac', 'm4a', 192, 32e6, 'en', undefined, true)],
      subtitles: [{lang: 'en', auto: false}],
      chapters: 0,
    },
  }
  media[yt('focusmix&list=PLfocus')] = {
    ...clone(media[yt('lofoten8k')]),
    url: yt('focusmix&list=PLfocus'),
    title: 'Deep Focus — 3 Hours of Ambient Study Music',
    uploader: 'Quiet Hours',
    duration: 10800,
    thumbnail: 'https://i.ytimg.com/vi/focusmix/hq.jpg',
    chapters: 24,
    playlist: {url: 'https://www.youtube.com/playlist?list=PLfocus', title: 'Focus & Study Mixes'},
    video: [v(1080, 30, false, 'vp9', 'webm', '248', 1.1e9, true), v(720, 30, false, 'h264', 'mp4', '136', 640e6, true)],
  }
  media['https://music.youtube.com/watch?v=ytm1'] = {
    ...clone(media[yt('lofoten8k')]),
    url: 'https://music.youtube.com/watch?v=ytm1',
    title: 'Harbor Lights (Official Audio)',
    uploader: 'Saltwater Radio',
    duration: 228,
    thumbnail: 'https://i.ytimg.com/vi/ytm1/sddefault.jpg',
    provider: 'YouTube Music',
    chapters: 0,
    video: [v(1080, 30, false, 'h264', 'mp4', '137', 21e6), v(720, 30, false, 'h264', 'mp4', '136', 12e6)],
    audio: [a('251', 'opus', 'webm', 152, 3.9e6, 'en', 'original'), a('140', 'aac', 'm4a', 129, 3.6e6, 'en', 'original')],
  }
  const errors = {
    'https://www.youtube.com/watch?v=private01':
      'Sign in required: this video is private. Add cookies in Settings → Downloads → Website sign-in, then try again.',
    'https://example.com/not-media': 'No video or images were found at this link. It may be unsupported or need sign-in.',
  }
  const collections = {
    'https://open.spotify.com/playlist/37i9dQZF1DX4sWSpwq3LiO': {
      title: 'Peaceful Piano Evenings',
      uploader: 'Spotify',
      total: 150,
      music: true,
      pace: 25,
      page: 100,
    },
    'https://music.youtube.com/playlist?list=PLytm': {title: 'Late Night Drive', uploader: 'Saltwater Radio', total: 24, pace: 12, ytmusic: true},
    'https://www.youtube.com/playlist?list=PLfocus': {title: 'Focus & Study Mixes', uploader: 'Quiet Hours', total: 64, pace: 12},
    // A radio mix and an album: Keep in sync is not offered for them.
    'https://www.youtube.com/watch?v=mixseed&list=RDmixseed': {title: 'Mix - Saltwater Radio', uploader: 'YouTube', total: 25, pace: 25},
    'https://open.spotify.com/album/1A2B3C4D5E6F': {title: 'Weather Systems', uploader: 'The Overcast Choir', total: 9, pace: 9, music: true},
    'https://www.youtube.com/@northbound/videos': {title: 'Northbound Films — Videos', uploader: 'Northbound Films', total: 1200, pace: 400, page: 200},
  }
  const words = 'Amber Coastline Hollow Signal Paper Lanterns Northern Drift Static Bloom Velvet Hours Satellite Rain Copper Field Glass Orchard Quiet Engine Harbor Lights Midnight Ferry Silver Thread Wild Frequencies'.split(' ')
  const artists = ['Mira Halden', 'The Overcast Choir', 'Lena Arkwright', 'Coastal Static', 'Ivo Brandt', 'Saltwater Radio', 'June Okafor', 'Paper Kites Society']
  function entryFor(source, index) {
    const r = rng(hash(source + index))
    const title = `${words[Math.floor(r() * words.length)]} ${words[Math.floor(r() * words.length)]}${r() > 0.7 ? ` (${['Live', 'Acoustic', 'Reprise', 'Night Version'][Math.floor(r() * 4)]})` : ''}`
    const music = collections[source].music || collections[source].ytmusic
    // fake.unavailable: the third entry is a video whose channel was removed (what discovery reports for it)
    if (options.unavailable && index === 2 && !collections[source].music)
      return {
        url: `https://www.youtube.com/watch?v=gone${hash(source) % 9999}`,
        position: index + 1,
        thumbnail: `https://img.fixture/${hash(source + index)}.jpg`,
        unavailable: 'No longer on YouTube: deleted, made private, or its channel was removed.',
      }
    return {
      url: collections[source].ytmusic
        ? `https://music.youtube.com/watch?v=m${index}_${hash(source) % 9999}`
        : music
          ? `https://open.spotify.com/track/sp${index}x${hash(source) % 9999}`
          : `https://www.youtube.com/watch?v=c${index}_${hash(source) % 9999}`,
      title,
      artist: music ? artists[Math.floor(r() * artists.length)] : undefined,
      album: music ? `${words[Math.floor(r() * words.length)]} Sessions` : undefined,
      uploader: music ? undefined : collections[source].uploader,
      thumbnail: `https://img.fixture/${hash(source + index)}.jpg`,
      duration: Math.round(120 + r() * (music ? 200 : 1500)),
      position: index + 1,
    }
  }

  // ---------------------------------------------------------------- jobs
  const opts = {embedMetadata: true, embedThumbnail: true, embedChapters: true, sponsorBlock: 'off'}
  const videoFormat = (maxHeight) => ({type: 'video', maxHeight, codec: 'auto', container: 'mp4'})
  const audioFormat = (target) => ({type: 'audio', target, quality: '320'})
  let seq = 0
  const job = (fields) => ({
    id: `job-${++seq}`,
    options: opts,
    percent: 0,
    createdAt: now - seq * 7 * min,
    kind: 'video',
    format: videoFormat(1080),
    ...fields,
  })
  let jobs = []
  if (options.scenario !== 'empty') {
    jobs = [
      job({
        url: yt('auroraTL'),
        title: 'Aurora Borealis Timelapse over Tromsø — 8K Real Time',
        uploader: 'Northbound Films',
        provider: 'YouTube',
        duration: 1834,
        label: '2160p60 HDR · MP4 · AV1',
        status: 'downloading',
        percent: 41.2,
        speed: 14.6 * MB,
        eta: 96,
        downloadedBytes: 1.04e9,
        totalBytes: 2.52e9,
        startedAt: now - 3 * min,
      }),
      job({
        url: 'https://open.spotify.com/track/sp-harbor',
        title: 'Harbor Lights',
        uploader: 'Saltwater Radio',
        provider: 'Spotify',
        kind: 'audio',
        format: audioFormat('mp3'),
        duration: 228,
        label: 'MP3 · 320 kbps',
        status: 'preparing',
        phase: 'Matching on YouTube Music',
        match: {source: 'spotify', sourceUrl: 'https://open.spotify.com/track/sp-harbor', confidence: 0.88, matchedUrl: 'https://music.youtube.com/watch?v=hl'},
      }),
      job({
        url: yt('cityjazz'),
        title: 'Tokyo Night Walk — Shinjuku in the Rain (Binaural Audio)',
        uploader: 'Rambalac Walks',
        provider: 'YouTube',
        duration: 4122,
        label: '1080p60 · MKV · VP9',
        status: 'processing',
        phase: 'Merging',
        percent: 100,
        downloadedBytes: 1.31e9,
        totalBytes: 1.31e9,
      }),
      job({
        url: 'https://www.twitch.tv/videos/2210',
        title: 'Speedrun marathon — Day 3 finale',
        uploader: 'RunFest',
        provider: 'Twitch',
        duration: 9620,
        label: '720p60 · MP4',
        status: 'downloading',
        percent: 12.8,
        speed: 6.1 * MB,
        eta: 642,
        downloadedBytes: 512e6,
        totalBytes: 4.0e9,
      }),
      job({url: 'https://soundcloud.com/lena/arkwright-tides', title: 'Tides (Extended Mix)', uploader: 'Lena Arkwright', provider: 'SoundCloud', kind: 'audio', format: audioFormat('m4a'), duration: 412, label: 'M4A · Best', status: 'queued', expectedSize: 13.2e6}),
      job({url: yt('ramen'), title: 'How a Master Makes Ramen From Scratch', uploader: 'Process Kitchen', provider: 'YouTube', duration: 1104, label: '1440p · MP4 · VP9', status: 'queued', expectedSize: 612e6}),
      job({url: 'https://vimeo.com/77001', title: 'Field recordings: Iceland interior', uploader: 'Field Notes Studio', provider: 'Vimeo', duration: 1980, label: '1080p · MP4 · H.264', status: 'paused', percent: 63, downloadedBytes: 402e6, totalBytes: 638e6}),
      job({url: yt('private02'), title: 'Members-only livestream replay', uploader: 'Studio Hour', provider: 'YouTube', label: '1080p · MP4', status: 'failed', error: 'Sign in required: this video is for channel members. Add cookies in Settings → Downloads → Website sign-in.', completedAt: now - 22 * min}),
      job({url: 'https://www.instagram.com/reel/Cxyz', title: 'Reel by @saltandsilver', uploader: 'saltandsilver', provider: 'Instagram', label: '720p · MP4', status: 'failed', error: 'The site refused the request (HTTP 403). Update the engine in Settings → Download engine and retry.', completedAt: now - 2 * hour}),
      job({url: yt('lofi24'), title: 'lofi hip hop radio — beats to relax/study to (2 hour excerpt)', uploader: 'Quiet Hours', provider: 'YouTube', kind: 'audio', format: audioFormat('opus'), duration: 7200, label: 'Opus · Best', status: 'completed', percent: 100, fileSize: 118e6, file: 'C:/Users/me/Downloads/Keepfin/YouTube/lofi hip hop radio.opus', completedAt: now - 14 * min}),
      job({url: yt('salt4k'), title: 'Salt Flats at Sunrise — Bolivia 4K', uploader: 'Northbound Films', provider: 'YouTube', duration: 384, label: '2160p · MP4 · AV1', status: 'completed', percent: 100, fileSize: 1.21e9, file: 'C:/Users/me/Downloads/Keepfin/YouTube/Salt Flats at Sunrise — Bolivia 4K.mp4', completedAt: now - 48 * min}),
      job({url: 'https://www.tiktok.com/@kitchenlab/video/7380', title: 'Crispy rice salad, no oven', uploader: 'kitchenlab', provider: 'TikTok', duration: 58, label: '1080p · MP4 · H.264', status: 'completed', percent: 100, fileSize: 17.4e6, file: 'C:/Users/me/Downloads/Keepfin/TikTok/Crispy rice salad, no oven.mp4', completedAt: now - 3 * hour}),
      job({url: 'https://open.spotify.com/track/sp-glass', title: 'Glass Harbour', uploader: 'The Overcast Choir', provider: 'Spotify', kind: 'audio', format: audioFormat('mp3'), duration: 213, label: 'MP3 · 320 kbps', status: 'completed', percent: 100, fileSize: 8.5e6, file: 'C:/Users/me/Downloads/Keepfin/Spotify/The Overcast Choir - Glass Harbour.mp3', completedAt: now - 5 * hour, match: {source: 'spotify', sourceUrl: 'https://open.spotify.com/track/sp-glass', matchedUrl: 'https://music.youtube.com/watch?v=g', confidence: 0.94}}),
      job({url: 'https://www.instagram.com/p/C8market/', title: 'Night market in Taipei — 12 photos', uploader: 'streetframes', provider: 'Instagram', kind: 'gallery', format: {type: 'gallery'}, label: 'Gallery · 12 items', status: 'completed', percent: 100, fileSize: 31.2e6, file: 'C:/Users/me/Downloads/Keepfin/Instagram/C8market', files: ['1.jpg', '2.jpg'], completedAt: now - 1.2 * day}),
      job({url: 'https://www.reddit.com/r/space/comments/x1', title: 'JWST deep field comparison, animated', uploader: 'u/orbitalmech', provider: 'Reddit', duration: 42, label: '1080p · MP4', status: 'cancelled', percent: 22, completedAt: now - 2 * day}),
      job({url: 'https://bandcamp.com/track/static-bloom', title: 'Static Bloom', uploader: 'Coastal Static', provider: 'Bandcamp', kind: 'audio', format: audioFormat('flac'), duration: 301, label: 'FLAC', status: 'completed', percent: 100, fileSize: 34.8e6, file: 'C:/Users/me/Downloads/Keepfin/Bandcamp/Coastal Static - Static Bloom.flac', completedAt: now - 3 * day}),
      job({url: yt('drone'), title: 'Faroe Islands from Above', uploader: 'Northbound Films', provider: 'YouTube', duration: 655, label: '1440p60 HDR · MKV · VP9', status: 'completed', percent: 100, fileSize: 921e6, file: 'C:/Users/me/Downloads/Keepfin/YouTube/Faroe Islands from Above.mkv', completedAt: now - 6 * day}),
      job({url: 'https://x.com/nasa/status/1', title: 'Launch replay, Artemis pad view', uploader: 'NASA', provider: 'X', duration: 140, label: '720p · MP4', status: 'completed', percent: 100, fileSize: 42.6e6, file: 'C:/Users/me/Downloads/Keepfin/X/Launch replay.mp4', completedAt: now - 9 * day}),
    ]
  }
  if (options.scenario === 'groups') {
    const gRoad = {id: 'grp-road', title: 'Road Trip Mix 2026', url: 'https://www.youtube.com/playlist?list=PLroad', uploader: 'Quiet Hours', kind: 'playlist'}
    const gAlbum = {id: 'grp-album', title: 'Glass Harbour Sessions', url: 'https://music.youtube.com/browse/MPREb_glass', uploader: 'The Overcast Choir', kind: 'album'}
    const gSeason = {id: 'grp-season', title: 'Northbound Films: Season One', url: 'https://www.youtube.com/playlist?list=PLseason', uploader: 'Northbound Films', kind: 'playlist'}
    const member = (group, n, fields) =>
      job({group, url: `${group.url}#${n}`, title: `${group.title.split(' ')[0]} track ${n}`, uploader: group.uploader, provider: group.kind === 'album' ? 'YouTube Music' : 'YouTube', duration: 180 + n * 31, label: '1080p · MP4', createdAt: now - 3 * day + n * 1000, ...fields})
    const done = (group, n, ago) => member(group, n, {status: 'completed', percent: 100, fileSize: (20 + n) * MB, file: `C:/Users/me/Downloads/Keepfin/${group.id}/${n}.mp4`, completedAt: now - ago})
    jobs.unshift(
      done(gRoad, 1, 9 * min),
      done(gRoad, 2, 7 * min),
      member(gRoad, 3, {status: 'downloading', percent: 55, speed: 7.2 * MB, eta: 40, downloadedBytes: 120e6, totalBytes: 220e6, startedAt: now - 2 * min}),
      member(gRoad, 4, {status: 'downloading', percent: 20, speed: 5.1 * MB, eta: 90, downloadedBytes: 44e6, totalBytes: 220e6, startedAt: now - min}),
      member(gRoad, 5, {status: 'queued'}),
      member(gRoad, 6, {status: 'failed', error: 'Sign in required: this video is private.', completedAt: now - 5 * min}),
      done(gAlbum, 1, 3 * hour),
      done(gAlbum, 2, 3 * hour),
      done(gAlbum, 3, 3 * hour),
      done(gAlbum, 4, 3 * hour),
      member(gAlbum, 5, {status: 'failed', error: 'This media was removed or is unavailable.', completedAt: now - 3 * hour}),
      done(gSeason, 1, 2 * day),
      done(gSeason, 2, 2 * day),
      done(gSeason, 3, 2 * day),
      done(gSeason, 4, 2 * day),
      job({url: yt('coastal'), title: 'Coastal timelapse — Faroe sunrise', uploader: 'Northbound Films', provider: 'YouTube', label: '1080p · MP4', status: 'queued', retry: {n: 2, max: 3, at: Date.now() + 14000}}),
      job({url: yt('retryengine'), title: 'Rooftop concert, full set', uploader: 'Studio Hour', provider: 'YouTube', label: '1080p · MP4', status: 'queued', retry: {n: 2, max: 3, updating: true}}),
    )
  }
  if (options.scenario === 'groups' && options.goneFiles) {
    // Files the library deleted (history keeps the entry without a path) and one it flags as gone from its folder.
    const clearFile = (group, ns) => {
      jobs = jobs.map((entry) => (entry.group && entry.group.id === group && ns.some((n) => entry.url.endsWith('#' + n)) ? {...entry, file: undefined} : entry))
    }
    clearFile('grp-album', [1, 2])
    clearFile('grp-season', [1, 2, 3, 4])
    jobs.unshift(
      job({url: yt('gone1'), title: 'Deleted clip', uploader: 'Northbound Films', provider: 'YouTube', label: '1080p · MP4', status: 'completed', percent: 100, fileSize: 40 * MB, completedAt: now - hour}),
      job({url: yt('moved1'), title: 'Moved away clip', uploader: 'Northbound Films', provider: 'YouTube', label: '1080p · MP4', status: 'completed', percent: 100, fileSize: 40 * MB, file: 'C:/Users/me/Downloads/Keepfin/moved.mp4', completedAt: now - 2 * hour}),
    )
    jobs = jobs.map((entry) => (entry.title === 'Deleted clip' ? {...entry, file: undefined} : entry))
  }
  if (options.scenario === 'groups' && options.partialGroup) {
    // A kept-together collection that stopped moving with failures: nothing downloading, one member on an automatic-retry countdown.
    const gTrip = {id: 'grp-trip', title: 'Lisbon Walking Tours', url: 'https://www.youtube.com/playlist?list=PLtrip', uploader: 'City Reels', kind: 'playlist'}
    const tripMember = (n, fields) =>
      job({group: gTrip, url: `${gTrip.url}#${n}`, title: `Lisbon walk ${n}`, uploader: gTrip.uploader, provider: 'YouTube', duration: 300 + n * 40, label: '1080p · MP4', createdAt: now - 20 * min + n * 1000, ...fields})
    const tripDone = (n) => tripMember(n, {status: 'completed', percent: 100, fileSize: (30 + n) * MB, file: `C:/Users/me/Downloads/Keepfin/${gTrip.id}/${n}.mp4`, completedAt: now - 4 * min})
    jobs.unshift(
      tripDone(1),
      tripMember(2, {status: 'failed', error: 'Sign in required: this video is private.', completedAt: now - 5 * min}),
      tripDone(3),
      tripMember(4, {status: 'queued', retry: {n: 1, max: 3, at: Date.now() + 90000}}),
      tripMember(5, {status: 'cancelled', percent: 12, completedAt: now - 6 * min}),
      tripDone(6),
    )
  }
  if (options.scenario === 'stress') {
    for (let i = 0; i < 400; i++)
      jobs.push(job({url: yt(`old${i}`), title: `Archive clip ${i + 1} — ${words[i % words.length]} ${words[(i * 7) % words.length]}`, provider: ['YouTube', 'Vimeo', 'TikTok', 'SoundCloud'][i % 4], kind: i % 4 === 3 ? 'audio' : 'video', label: '1080p · MP4', status: 'completed', percent: 100, fileSize: (5 + (i % 50) * 9) * MB, file: `C:/Users/me/Downloads/Keepfin/clip-${i}.mp4`, completedAt: now - (10 + i) * day}))
  }

  let settings = {
    outDir: 'C:\\Users\\me\\Downloads\\Keepfin',
    organization: 'platform',
    concurrency: 3,
    fragments: 8,
    rateLimit: 0,
    filenameStyle: 'title',
    defaultPreset: 'best',
    youtubeMusic: options.youtubeMusic || 'audio',
    options: {embedMetadata: true, embedThumbnail: true, embedChapters: true, sponsorBlock: 'off', subtitles: {enabled: false, languages: 'en', auto: false, embed: true}},
    cookies: {mode: 'browser', browser: 'firefox'},
    proxy: '',
    engine: {channel: 'stable', autoUpdate: true},
    updateChannel: 'stable',
    startPage: options.startPage || 'downloads',
    lyrics: options.lyrics ?? true,
    musicVideos: options.musicVideos ?? false,
    autoSignIn: options.autoSignIn ?? true,
    appIcon: '',
    turbo: {enabled: false, connections: 8},
  }
  let engine = {
    busy: false,
    tools: [
      {name: 'yt-dlp', state: 'ready', version: '2026.09.28', latest: '2026.09.28', checkedAt: now - 2 * hour},
      {name: 'gallery-dl', state: 'ready', version: '1.30.4', latest: '1.30.4', checkedAt: now - 2 * hour},
      {name: 'ffmpeg', state: 'ready', version: '7.1'},
      {name: 'js-runtime', state: 'ready', version: '24.9.0', message: 'Built in'},
    ],
  }
  if (options.engine === 'updating') {
    engine = clone(engine)
    engine.busy = true
    engine.tools[0] = {...engine.tools[0], state: 'updating', latest: '2026.10.02'}
  }
  if (options.engine === 'error') {
    engine = clone(engine)
    engine.tools[0] = {...engine.tools[0], state: 'error', message: 'Update failed: checksum mismatch. The previous version is still in use.'}
  }
  // A fresh install run from source: the gallery engine is not there yet; it installs on the first image post.
  if (options.engine === 'gallery-later') {
    engine = clone(engine)
    engine.tools = engine.tools.map((tool) => (tool.name === 'gallery-dl' ? {name: 'gallery-dl', state: 'missing'} : tool))
  }
  let hasCookieFile = false
  // Keepfin's own sign-ins (the sign-in window). Option signInWindow: 'closed' = the window is closed before signing in; signedInTo: sites signed in at start.
  const signInSites = [['instagram', 'Instagram'], ['facebook', 'Facebook'], ['x', 'X'], ['tiktok', 'TikTok'], ['reddit', 'Reddit'], ['youtube', 'YouTube'], ['vimeo', 'Vimeo'], ['soundcloud', 'SoundCloud'], ['pinterest', 'Pinterest']]
  const signedIn = new Set(options.signedInTo || [])
  const signInState = () => ({canKeep: options.canKeepSignIn !== false, sites: signInSites.map(([key, name]) => ({key, name, signedIn: signedIn.has(key)}))})
  // options.signedIn: a download that used a sign-in the site asked for (its row says so quietly)
  if (options.signedIn)
    jobs.unshift(job({url: 'https://www.instagram.com/p/DfW56Yoim48/', title: 'Harbour photos', provider: 'Instagram', kind: 'gallery', label: '2 photos', status: 'downloading', percent: 30, signedIn: options.signedIn}))
  const snapshot = () => clone({jobs, settings, engine, platform: 'desktop', hasCookieFile})
  fake.jobs = () => clone(jobs)

  function push(event) {
    fake.pushes++
    for (const listener of listeners) listener(clone(event))
  }
  let snapshotTimer
  function pushSnapshot() {
    clearTimeout(snapshotTimer)
    snapshotTimer = setTimeout(() => push({type: 'snapshot', snapshot: snapshot()}), 50)
  }

  // ---------------------------------------------------------------- simulation (4 Hz progress)
  const patch = (id, change) => {
    jobs = jobs.map((item) => (item.id === id ? {...item, ...change} : item))
    return jobs.find((item) => item.id === id)
  }
  setInterval(() => {
    if (!fake.simulate) return
    const progress = []
    let structural = false
    for (const item of jobs) {
      if (item.status !== 'downloading') continue
      const total = item.totalBytes || 400e6
      const speed = Math.max(0.5 * MB, (item.speed || 5 * MB) * (0.9 + Math.random() * 0.2))
      const downloaded = Math.min(total, (item.downloadedBytes || 0) + speed / 4)
      const percent = (downloaded / total) * 100
      if (percent >= 100) {
        patch(item.id, {status: 'processing', phase: 'Merging', percent: 100, downloadedBytes: total, speed: undefined, eta: undefined})
        structural = true
        const id = item.id
        setTimeout(() => {
          const done = patch(id, {status: 'completed', phase: undefined, fileSize: total, completedAt: Date.now(), file: `C:/Users/me/Downloads/Keepfin/${id}.mp4`})
          if (done && done.space && libSettings.enabled) {
            const dest = libSettings.spaces.find((space) => space.id === done.space)
            if (dest) {
              const audio = done.kind === 'audio'
              libItems.push({id: `${audio ? 'a' : 'v'}-job-${id}`, space: dest.id, path: `${dest.folder}\${done.title}.${audio ? 'mp3' : 'mp4'}`, folder: '', kind: audio ? 'audio' : 'video', title: done.title, artist: done.uploader, duration: 6, size: total, modifiedAt: Date.now(), addedAt: Date.now(), hasArt: true, jobId: id})
              pushLibrary()
            }
          }
          pushSnapshot()
        }, 2500)
        continue
      }
      const next = patch(item.id, {downloadedBytes: downloaded, percent, speed, eta: Math.round((total - downloaded) / speed)})
      progress.push({id: next.id, status: next.status, percent: next.percent, speed: next.speed, eta: next.eta, downloadedBytes: next.downloadedBytes, totalBytes: next.totalBytes, phase: next.phase})
    }
    if (progress.length) push({type: 'progress', progress})
    if (structural) pushSnapshot()
  }, 250)

  // ---------------------------------------------------------------- discovery
  const discoveries = new Map()
  function runDiscovery(state, from, to) {
    const source = collections[state.source]
    state.state = 'discovering'
    const step = () => {
      if (state.state !== 'discovering') return
      const end = Math.min(to, source.total, state.entries.length + source.pace)
      for (let i = state.entries.length; i < end; i++) state.entries.push(entryFor(state.source, i))
      const done = state.entries.length >= Math.min(to, source.total)
      if (done) {
        state.state = 'completed'
        state.truncated = source.total > to
        state.notice = source.music && state.truncated ? 'Spotify lists only the first ' + to + ' tracks of a playlist at a time. Load the next page for the rest.' : undefined
      }
      push({type: 'discovery', discovery: view(state)})
      if (!done) state.timer = setTimeout(step, 220 * options.latency)
    }
    state.timer = setTimeout(step, 300 * options.latency)
  }
  const view = (state) => clone({id: state.id, title: state.title, uploader: state.uploader, entries: state.entries, state: state.state, limit: state.limit, truncated: state.truncated, error: state.error, notice: state.notice})

  // ---------------------------------------------------------------- library (SYNTHETIC fixture data)
  // Mirrors the final contract shapes in gui/shared/library.ts. Ids: a-* audio, v-* video, i-* image (the fixture
  // media server maps them to tiny real files). Like the real index, ids derive from paths, so move/rename change them.
  const libScenario = options.library === 'off' ? 'off' : options.libraryScenario || 'full'
  // inside the fake Keepfin folder (storageState.folder), as after a correct setup
  const FOLDERS = {downloads: 'C:\\Users\\you\\Keepfin\\Downloads', music: 'C:\\Users\\you\\Keepfin\\Music', videos: 'C:\\Users\\you\\Keepfin\\Videos'}
  const libSettings = {
    enabled: libScenario !== 'off',
    openWith: 'keepfin',
    normalize: true,
    crossfade: 0,
    spaces: [
      {id: 'downloads', name: 'Downloads', kind: 'mixed', folder: FOLDERS.downloads, layout: 'platform', naming: 'title', skipExisting: false, type: 'folder', color: 'slate', icon: 'folder', pinned: true},
      {id: 'music', name: 'Music', kind: 'music', folder: FOLDERS.music, layout: 'collection', naming: 'title', skipExisting: true, type: 'folder', color: 'cobalt', icon: 'music', pinned: true},
      {id: 'videos', name: 'Videos', kind: 'video', folder: FOLDERS.videos, layout: 'collection', naming: 'title', skipExisting: false, type: 'folder', color: 'violet', icon: 'video', pinned: true},
      // Favorites is a kept space like the three above: every library has it (everything marked with a heart)
      {id: 'favorites', name: 'Favorites', kind: 'mixed', folder: '', layout: 'flat', naming: 'title', skipExisting: false, type: 'smart', color: 'berry', icon: 'heart', pinned: true, rules: {match: 'any', rules: [{field: 'favorite'}]}},
    ],
    routes: {music: 'music', other: 'downloads'},
    importTags: true,
  }
  // options.lyricLines lets screenshot scripts show readable sample lyrics.
  const lyricLines = options.lyricLines || [
    {time: 0.4, text: 'First line of the fixture lyrics'},
    {time: 1.4, text: 'Second line arrives'},
    {time: 2.4, text: 'Third line keeps the beat'},
    {time: 3.2, text: 'Last line fades out'},
  ]
  let libItems = []
  // The user's own lists (kind 'own') and the collections downloaded from links; the snapshot joins them.
  let libPlaylists = []
  let libDownloaded = []
  let libOperation
  // The Keepfin trash (options.trash: 'seeded' | 'clash' | 'empty' | 'nofolder'). Entries are listed newest deletion first.
  const DAYMS = 86_400_000
  let fakeTrash =
    options.trash === 'seeded' || options.trash === 'clash'
      ? [
          {id: '11111111-1111-4111-8111-111111111111', title: 'Glass Harbour', fileName: 'Glass Harbour.mp3', space: 'music', kind: 'audio', size: 8_600_000, deletedAt: Date.now() - 3_600_000},
          {id: '22222222-2222-4222-8222-222222222222', title: 'Night Drive', fileName: 'Night Drive.mp4', space: 'videos', kind: 'video', size: 412_000_000, deletedAt: Date.now() - 12 * DAYMS - 5000},
          {id: '33333333-3333-4333-8333-333333333333', title: 'Old Demo', fileName: 'Old Demo.mp3', space: 'music', kind: 'audio', size: 3_100_000, deletedAt: Date.now() - 28 * DAYMS - 5000, clash: options.trash === 'clash'},
          {id: '44444444-4444-4444-8444-444444444444', title: 'Yesterday Mix', fileName: 'Yesterday Mix.mp3', space: 'music', kind: 'audio', size: 5_000_000, deletedAt: Date.now() - DAYMS - 5000},
        ]
      : []
  const trashList = () =>
    fakeTrash
      .map(({clash, ...entry}) => ({...entry, daysLeft: Math.max(0, 30 - Math.floor((Date.now() - entry.deletedAt) / DAYMS))}))
      .sort((a, b) => b.deletedAt - a.deletedAt)
  let idSeq = 0
  const song = (id, title, artist, album, track, extra = {}) => ({
    id, space: 'music', path: `${FOLDERS.music}\\${album || 'Singles'}\\${title}.mp3`, folder: album || '', kind: 'audio', title, artist, album, track, year: 2024,
    duration: options.songSeconds ? options.songSeconds + [...title].reduce((sum, c) => sum + c.charCodeAt(0), 0) % 100 : 4, size: 3.2 * MB, modifiedAt: now - day, addedAt: now - (idSeq += 1) * hour, hasArt: true, plays: 0, ...extra,
  })
  const video = (id, title, folder, extra = {}) => ({
    id, space: 'videos', path: `${FOLDERS.videos}\\${folder ? folder + '\\' : ''}${title}.mp4`, folder, kind: 'video', title, duration: 6, size: 180 * MB, modifiedAt: now - day,
    addedAt: now - (idSeq += 1) * hour, hasArt: true, width: 1920, height: 1080, ...extra,
  })
  if (libScenario !== 'off' && libScenario !== 'empty') {
    libItems = [
      song('a-glass', 'Glass Harbour', 'The Overcast Choir', 'Weather Systems', 1, {hasLyrics: true, favorite: true, plays: 12, gain: -3, lastPlayedAt: now - 2 * hour, disc: 1}),
      song('a-front', 'Cold Front', 'The Overcast Choir', 'Weather Systems', 2, {plays: 7, lastPlayedAt: now - 5 * hour, disc: 1, musicVideo: true, videoDuration: 214, videoSize: 52_000_000}),
      song('a-fog', 'Fog Signals', 'The Overcast Choir', 'Weather Systems', 3, {hasLyrics: true, disc: 2, plays: 0}),
      song('a-tidal', 'Tidal (Night Version)', 'Mira Halden', 'Low Tide Hours', 1, {sourceId: 'youtube:tidal88', plays: 3, favorite: true}),
      song('a-low', 'Low Tide Hours', 'Mira Halden', 'Low Tide Hours', 2, {plays: 0}),
      song('a-amber', 'Amber Coastline', 'Lena Arkwright', 'Static Bloom', 1, {plays: 1}),
      song('a-bloom', 'Static Bloom', 'Coastal Static', 'Static Bloom', 2, {}),
      song('a-hours', 'Velvet Hours', 'Ivo Brandt', 'Static Bloom', 3, {}),
      song('a-café', 'Café Océan', 'Noémie Aubé', 'Café Sessions', 1, {plays: 2}),
      song('a-one', 'One Off Single', 'June Okafor', '', undefined, {folder: '', album: undefined}),
      song('a-radio', 'Harbor Lights', 'Saltwater Radio', 'Harbor Lights', 1, {plays: 4}),
      song('a-podcast', 'Long Mix Episode', 'Quiet Hours', 'Long Mixes', 1, {duration: 5400, position: 1800, lastPlayedAt: now - hour}),
      video('v-lofoten', 'Lofoten in Winter', 'Northbound Films', {chapters: [{start: 0, title: 'Intro'}, {start: 2, title: 'Fjord'}, {start: 4, title: 'Aurora'}], subtitles: ['English', 'Norwegian'], duration: 600, position: 120, lastPlayedAt: now - 3 * hour, favorite: true}),
      video('v-salt', 'Salt Flats at Sunrise', 'Northbound Films', {}),
      video('v-faroe', 'Faroe Islands from Above', 'Northbound Films', {watched: true, plays: 2}),
      video('v-ramen', 'How a Master Makes Ramen', '', {}),
      video('v-mkv', 'Old Recording (MKV, HEVC)', '', {path: `${FOLDERS.videos}\\Old Recording.mkv`}),
      video('v-iceland', 'Iceland Interior', 'Field Notes', {}),
      {id: 'v-dl-orzo', space: 'downloads', path: `${FOLDERS.downloads}\\TikTok\\One-pan lemon orzo.mp4`, folder: 'TikTok', kind: 'video', title: 'One-pan lemon orzo in 60 seconds', duration: 6, size: 17 * MB, modifiedAt: now - hour, addedAt: now - hour, hasArt: true, jobId: 'job-12'},
      {id: 'a-dl-lofi', space: 'downloads', path: `${FOLDERS.downloads}\\YouTube\\lofi hip hop radio.mp3`, folder: 'YouTube', kind: 'audio', title: 'lofi hip hop radio', artist: 'Quiet Hours', duration: 4, size: 3 * MB, modifiedAt: now - hour, addedAt: now - 2 * hour, hasArt: true},
      ...[0, 1, 2, 3, 4].map((n) => ({id: `i-market-${n}`, space: 'downloads', path: `${FOLDERS.downloads}\\Instagram\\C8market\\${n + 1}.jpg`, folder: 'Instagram/C8market', kind: 'image', title: `Night market ${n + 1}`, size: 2 * MB, modifiedAt: now - day, addedAt: now - day - n * min, hasArt: true, width: 640, height: 360})),
      {id: 'o-notes', space: 'downloads', path: `${FOLDERS.downloads}\\notes.pdf`, folder: '', kind: 'other', title: 'notes', size: 90000, modifiedAt: now - day, addedAt: now - day, hasArt: false},
      {id: 'a-ghost', space: 'music', path: `${FOLDERS.music}\\Ghost Song.mp3`, folder: '', kind: 'audio', title: 'Ghost Song', artist: 'Moved Away', duration: 200, size: 3 * MB, modifiedAt: now - day, addedAt: now - 9 * day, hasArt: false, missing: true},
    ]
    // A YouTube Music song saved as a video into Music (dev-docs/plans/MUSIC-VIDEOS.md Part A): listed and played as a song.
    if (options.videoSong) libItems.push({id: 'v-song', space: 'music', path: `${FOLDERS.music}\\Singles\\Night Swim.mp4`, folder: '', kind: 'video', title: 'Night Swim', artist: 'Mira Halden', album: 'Low Tide Hours', track: 3, duration: 8, size: 40 * MB, modifiedAt: now - hour, addedAt: now - hour, hasArt: true, width: 1920, height: 1080})
    // A song of a shared library that was added on another device: no file on this PC (the engine lists it at a path that never exists).
    if (options.awayItem) libItems.push({id: 'a-far', space: 'music', path: 'D:\\Keepfin\\.keepfin\\unplaced\\a-far', folder: '', kind: 'audio', title: 'Far Song', artist: 'Far Artist', duration: 200, size: 3 * MB, modifiedAt: 0, addedAt: now - day, hasArt: false, missing: true})
    if (options.goneFiles) {
      const moved = jobs.find((entry) => entry.title === 'Moved away clip')
      libItems.push({id: 'v-moved', space: 'downloads', path: `${FOLDERS.downloads}\\moved.mp4`, folder: '', kind: 'video', title: 'Moved away clip', duration: 6, size: 40 * MB, modifiedAt: now - day, addedAt: now - 2 * hour, hasArt: false, jobId: moved.id, missing: true})
    }
    if (libScenario === 'collections' || libScenario === 'v2') {
      // A downloaded playlist: alphabetical order is the opposite of the playlist order.
      const drive = (id, title, position) => song(id, title, 'Quiet Hours', 'Road Trip Mix', undefined, {folder: 'Road Trip Mix', album: undefined, jobId: `job-drive-${position}`})
      libItems.push(drive('a-drive-0', 'Zebra Crossing', 0), drive('a-drive-1', 'Midnight Radio', 1), drive('a-drive-2', 'Aurora Drive', 2))
      const downloaded = (id, name, space, itemIds) => ({id, name, kind: 'playlist', space, itemIds, seen: [], createdAt: now - day, updatedAt: now - day})
      libDownloaded.push(downloaded('grp-drive', 'Road Trip Mix', 'music', ['a-drive-0', 'a-drive-1', 'a-drive-2']), downloaded('grp-clips', 'Weekend clips', 'downloads', ['v-clips-0', 'v-clips-1']))
      // The same kind of collection saved into a mixed space with a flat layout, as videos.
      libItems.push(
        ...[0, 1].map((n) => ({id: `v-clips-${n}`, space: 'downloads', path: `${FOLDERS.downloads}\\Clips ${n}.mp4`, folder: '', kind: 'video', title: n ? 'Alpha clip' : 'Zulu clip', duration: 6, size: 9 * MB, modifiedAt: now - hour, addedAt: now - hour, hasArt: true})),
      )
    }
    if (libScenario === 'v2') {
      // Spaces v2 fixtures (SYNTHETIC): tags, followed collections, extra spaces and a smart space.
      const tag = (id, tags, userTags) => Object.assign(libItems.find((item) => item.id === id), {tags, ...(userTags ? {userTags} : {})})
      tag('a-glass', ['ambient', 'indie'])
      tag('a-front', ['ambient', 'indie'], ['workout'])
      tag('a-fog', ['ambient'])
      tag('a-tidal', ['indie', 'night'])
      tag('a-low', ['night'])
      tag('a-amber', ['indie'])
      tag('a-radio', ['night', 'road trip'])
      Object.assign(libItems.find((item) => item.id === 'v-faroe'), {userTags: ['workout']})
      const road = libDownloaded.find((entry) => entry.id === 'grp-drive')
      Object.assign(road, {
        source: {url: 'https://www.youtube.com/playlist?list=PLroad', provider: 'YouTube', id: 'PLroad'},
        follow: {mode: 'auto', intervalHours: 6, wifiOnly: true, notify: true, confirmAbove: 20, lastCheckedAt: now - 12 * min},
        newItemIds: ['a-drive-2'],
        removedItemIds: ['a-drive-1'],
      })
      const clips = libDownloaded.find((entry) => entry.id === 'grp-clips')
      Object.assign(clips, {
        source: {url: 'https://www.youtube.com/playlist?list=PLclips', provider: 'YouTube', id: 'PLclips'},
        follow: {mode: 'auto', intervalHours: 24, wifiOnly: true, notify: true, confirmAbove: 20, lastCheckedAt: now - 3 * hour, pending: {count: 28, at: now - hour}},
      })
      libSettings.spaces.push(
        {id: 'kids', name: 'Kids', kind: 'mixed', folder: 'C:\\Users\\me\\Kids', layout: 'collection', naming: 'title', skipExisting: false, type: 'folder', color: 'teal', icon: 'child', pinned: false},
        {id: 'podcasts', name: 'Podcasts', kind: 'music', folder: 'C:\\Users\\me\\Podcasts', layout: 'collection', naming: 'title', skipExisting: false, type: 'folder', color: 'amber', icon: 'mic', pinned: false},
        {id: 'gym', name: 'Gym', kind: 'mixed', folder: '', layout: 'flat', naming: 'title', skipExisting: false, type: 'smart', color: 'rust', icon: 'dumbbell', pinned: false, rules: {match: 'any', rules: [{field: 'tag', values: ['workout']}]}},
      )
    }
    libPlaylists = [{id: 'pl-night', name: 'Night drive', kind: 'own', space: 'music', itemIds: ['a-tidal', 'a-glass', 'a-radio'], seen: [], createdAt: now - day, updatedAt: now - day}]
    if (libScenario === 'stress')
      for (let i = 0; i < 10000; i++)
        libItems.push(song(`a-bulk-${i}`, `Bulk song ${String(i).padStart(5, '0')} ${words[i % words.length]}`, artists[i % artists.length], `Album ${Math.floor(i / 12)}`, (i % 12) + 1, {size: 4 * MB, hasArt: i % 3 === 0}))
  }
  const libSnapshot = () => clone({items: libItems, collections: [...libPlaylists, ...libDownloaded], scanning: false, problems: [], ...(libOperation ? {operation: libOperation} : {})})
  // The window gets what changed (a libraryDelta) whenever the order allows it, and the whole library otherwise, exactly as the
  // desktop app does: every scenario here exercises the same push path.
  let libSeq = 0
  let libSent
  const libMetaOf = (snap) => ({scanning: snap.scanning, scannedAt: snap.scannedAt, problems: snap.problems, operation: snap.operation})
  const libMark = (snap) => {
    libSent = {
      items: new Map(snap.items.map((entry) => [entry.id, JSON.stringify(entry)])),
      ids: snap.items.map((entry) => entry.id),
      collections: new Map(snap.collections.map((entry) => [entry.id, JSON.stringify(entry)])),
      collectionIds: snap.collections.map((entry) => entry.id),
      meta: JSON.stringify(libMetaOf(snap)),
    }
  }
  // Updated entries keep their place and new ones come last: if that is not how the list looks now, a delta cannot say it.
  const followsOrder = (before, list) => {
    const now = new Set(list.map((entry) => entry.id))
    const kept = before.filter((id) => now.has(id))
    const known = new Set(before)
    return list.length === kept.length + list.filter((entry) => !known.has(entry.id)).length && kept.every((id, at) => list[at].id === id)
  }
  const pushLibrary = () => {
    const snap = libSnapshot()
    if (!libSent || !followsOrder(libSent.ids, snap.items) || !followsOrder(libSent.collectionIds, snap.collections)) {
      libSeq++
      libMark(snap)
      push({type: 'library', library: {...snap, seq: libSeq}})
      return
    }
    const delta = {type: 'libraryDelta', seq: libSeq + 1, base: libSeq}
    const items = snap.items.filter((entry) => libSent.items.get(entry.id) !== JSON.stringify(entry))
    const nowIds = new Set(snap.items.map((entry) => entry.id))
    const removedItems = libSent.ids.filter((id) => !nowIds.has(id))
    const collections = snap.collections.filter((entry) => libSent.collections.get(entry.id) !== JSON.stringify(entry))
    const nowCollections = new Set(snap.collections.map((entry) => entry.id))
    const removedCollections = libSent.collectionIds.filter((id) => !nowCollections.has(id))
    const meta = JSON.stringify(libMetaOf(snap))
    if (items.length) delta.items = items
    if (removedItems.length) delta.removedItems = removedItems
    if (collections.length) delta.collections = collections
    if (removedCollections.length) delta.removedCollections = removedCollections
    if (meta !== libSent.meta) delta.meta = libMetaOf(snap)
    if (!delta.items && !delta.removedItems && !delta.collections && !delta.removedCollections && !delta.meta) return
    libSeq++
    libMark(snap)
    push(delta)
  }
  const libState = () => {
    const snap = libSnapshot()
    if (!libSent) libMark(snap)
    return {...snap, seq: libSeq}
  }
  const libById = (id) => libItems.find((item) => item.id === id)
  const withSettings = () => {
    settings = {...settings, library: libSettings}
  }
  withSettings()
  fake.library = {
    items: () => clone(libItems),
    playlists: () => clone(libPlaylists),
    settings: () => clone(libSettings),
    pushLibrary,
    setOperation(operation) {
      libOperation = operation
      pushLibrary()
    },
  }
  fake.addJob = (fields) => {
    jobs = [...jobs, {options: opts, createdAt: Date.now(), ...fields}]
    pushSnapshot()
  }
  fake.completeJob = (id) => {
    const done = patch(id, {status: 'completed', percent: 100, speed: undefined, eta: undefined, completedAt: Date.now(), fileSize: 4 * MB, file: `C:/Users/me/Music/${id}.mp3`})
    if (done && done.space) {
      libItems.push({id: `a-job-${id}`, space: done.space, path: `${FOLDERS.music}\\${done.title}.mp3`, folder: '', kind: 'audio', title: done.title, duration: 4, size: 4 * MB, modifiedAt: Date.now(), addedAt: Date.now(), hasArt: false, jobId: id})
      pushLibrary()
    }
    pushSnapshot()
  }
  const norm = (text) => (text || '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
  const NOT_HANDLED = Symbol('not handled')
  const folderFor = (space, item) => (space.layout === 'flat' ? '' : item.folder)
  const rebase = (item, space) => {
    const name = item.path.split('\\').pop()
    const folder = folderFor(space, item)
    const base = item.id.split('~')[0]
    const next = {...item, space: space.id, folder, path: `${space.folder}\\${folder ? folder.replace(/\//g, '\\') + '\\' : ''}${name}`, id: `${base}~${space.id}`}
    return next
  }
  function remap(oldId, next) {
    libItems = libItems.map((item) => (item.id === oldId ? next : item))
    libPlaylists = libPlaylists.map((playlist) => ({...playlist, itemIds: playlist.itemIds.map((id) => (id === oldId ? next.id : id))}))
    libDownloaded = libDownloaded.map((entry) => ({...entry, itemIds: entry.itemIds.map((id) => (id === oldId ? next.id : id))}))
  }
  // ---------------------------------------------------------------- Undo (as the main process does it: from the state at the action, on the state at Undo)
  // An action sent with an undoId records how to reverse it; libraryUndo with that id reverses it on the state as it is then.
  const undos = new Map()
  const UNDOABLE = new Set(['libraryTag', 'libraryFavorite', 'libraryMove', 'libraryAddFrom', 'libraryRename', 'libraryEditInfo', 'libraryDeleteFile', 'collectionSave', 'playlistSave', 'collectionAdd', 'collectionDelete', 'playlistDelete', 'spaceDelete'])
  /** The order after an Undo: `now` and each removed id back before the first id that followed it in `before` and is still there. */
  const restoreOrder = (before, now, removed) => {
    const kept = now.filter((id) => !removed.has(id))
    const present = new Set(kept)
    const anchored = new Map()
    let anchor = ''
    for (let i = before.length - 1; i >= 0; i--) {
      const id = before[i]
      if (present.has(id)) anchor = id
      else if (removed.has(id)) anchored.set(anchor, [...(anchored.get(anchor) || []), id])
    }
    const out = []
    for (const id of kept) out.push(...(anchored.get(id) || []).reverse(), id)
    out.push(...(anchored.get('') || []).reverse())
    return out
  }
  const done = (count, message) => ({done: count, failed: 0, ...(message ? {message} : {})})
  /** The reversal of an action, or undefined when it changed nothing. `before` is the state when the action began, `out` its answer. */
  function undoFor(req, before, out) {
    const ids = req.ids || (req.id ? [req.id] : [])
    switch (req.op) {
      case 'libraryTag': {
        const groups = new Map()
        for (const id of ids) {
          const was = before.items.find((item) => item.id === id)?.userTags || []
          const now = libById(id)?.userTags || []
          const added = now.filter((tag) => !was.includes(tag))
          const removed = was.filter((tag) => !now.includes(tag))
          if (!added.length && !removed.length) continue
          const key = JSON.stringify([added, removed])
          groups.set(key, {ids: [...(groups.get(key)?.ids || []), id], added, removed})
        }
        if (!groups.size) return
        return async () => {
          let count = 0
          for (const group of groups.values()) {
            const present = group.ids.filter((id) => libById(id))
            libItems = libItems.map((item) => {
              if (!present.includes(item.id)) return item
              const next = [...new Set([...(item.userTags || []).filter((tag) => !group.added.includes(tag)), ...group.removed])]
              return {...item, userTags: next.length ? next : undefined}
            })
            count += present.length
          }
          pushLibrary()
          return done(count)
        }
      }
      case 'libraryFavorite': {
        const changed = ids.filter((id) => !!before.items.find((item) => item.id === id)?.favorite !== !!req.favorite)
        if (!changed.length) return
        return async () => {
          libItems = libItems.map((item) => (changed.includes(item.id) ? {...item, favorite: !req.favorite} : item))
          pushLibrary()
          return done(changed.length)
        }
      }
      case 'libraryMove':
      case 'libraryAddFrom': {
        if (req.addMode === 'copy' || !out.moved.length) return
        // moved answers the new ids in the order of the items that existed
        const wanted = [...ids]
        for (const collectionId of req.collectionIds || []) {
          const found = [...before.playlists, ...before.downloaded].find((entry) => entry.id === collectionId)
          if (found) wanted.push(...found.itemIds)
        }
        const existing = [...new Set(wanted)].filter((id) => before.items.some((item) => item.id === id))
        const origins = out.moved.map((id, at) => [id, before.items.find((item) => item.id === existing[at])?.space])
        return async () => {
          let count = 0
          for (const [id, space] of origins) {
            const item = libById(id)
            const target = libSettings.spaces.find((entry) => entry.id === space)
            if (!item || !target) continue
            remap(id, rebase(item, target))
            count++
          }
          pushLibrary()
          return done(count)
        }
      }
      case 'libraryRename': {
        const was = before.items.find((item) => item.id === req.id)
        if (!was) return
        return async () => {
          const item = libById(out.id)
          if (!item) throw new Error('That file is no longer in the library.')
          remap(item.id, {...item, path: was.path, id: was.id})
          pushLibrary()
          return done(1)
        }
      }
      case 'libraryEditInfo': {
        const info = req.info || {}
        if (info.cover) return
        const was = before.items.find((item) => item.id === req.id)
        if (!was) return
        const previous = Object.fromEntries(Object.keys(info).map((key) => [key, was[key]]))
        return async () => {
          libItems = libItems.map((item) => (item.id === req.id ? {...item, ...previous} : item))
          pushLibrary()
          return done(1)
        }
      }
      case 'libraryDeleteFile': {
        if (!out.undoable) return
        const gone = before.items.filter((item) => out.undoable.includes(item.id))
        return async () => {
          let count = 0
          for (const item of gone) {
            if (libById(item.id)) continue
            libItems.push(item)
            count++
            for (const list of [libPlaylists, libDownloaded]) {
              for (const entry of list) {
                const held = before.playlists.concat(before.downloaded).find((saved) => saved.id === entry.id)
                if (held && held.itemIds.includes(item.id)) entry.itemIds = restoreOrder(held.itemIds, entry.itemIds, new Set([item.id]))
              }
            }
          }
          pushLibrary()
          return done(count, count === 1 ? `Brought back “${gone[0].title}”` : `Brought back ${count} files`)
        }
      }
      case 'collectionSave':
      case 'playlistSave': {
        const input = req.collection || req.playlist
        if (!input.id) {
          return async () => {
            libPlaylists = libPlaylists.filter((entry) => entry.id !== out.id)
            pushLibrary()
            return done(1)
          }
        }
        const was = before.playlists.find((entry) => entry.id === input.id)
        if (!was || was.name === input.name) return
        return async () => {
          const playlist = libPlaylists.find((entry) => entry.id === input.id)
          if (!playlist) throw new Error('That collection no longer exists.')
          playlist.name = was.name
          pushLibrary()
          return done(1)
        }
      }
      case 'collectionAdd': {
        const was = before.playlists.find((entry) => entry.id === req.id)
        const added = (out.itemIds || []).filter((id) => !was || !was.itemIds.includes(id))
        if (!added.length) return
        return async () => {
          const playlist = libPlaylists.find((entry) => entry.id === req.id)
          if (!playlist) throw new Error('That collection no longer exists.')
          playlist.itemIds = playlist.itemIds.filter((id) => !added.includes(id))
          pushLibrary()
          return done(added.length)
        }
      }
      case 'collectionDelete':
      case 'playlistDelete': {
        const saved = before.playlists.find((entry) => entry.id === req.id)
        if (!saved) return
        return async () => {
          libPlaylists.push({...clone(saved), itemIds: saved.itemIds.filter((id) => libById(id)), updatedAt: Date.now()})
          pushLibrary()
          return done(1)
        }
      }
      case 'spaceDelete': {
        const position = before.spaces.findIndex((space) => space.id === req.id)
        if (position < 0) return
        const space = before.spaces[position]
        const gone = before.items.filter((item) => item.space === req.id)
        return async () => {
          libSettings.spaces.splice(Math.min(position, libSettings.spaces.length), 0, space)
          if (before.routes.music === req.id) libSettings.routes.music = req.id
          if (before.routes.other === req.id) libSettings.routes.other = req.id
          libItems.push(...gone.filter((item) => !libById(item.id)))
          withSettings()
          pushLibrary()
          pushSnapshot()
          return done(1)
        }
      }
    }
  }
  async function libraryHandle(req) {
    if (req.op === 'libraryUndo') {
      const run = undos.get(req.undoId)
      if (!run) throw new Error('This can no longer be undone.')
      undos.delete(req.undoId)
      return run()
    }
    // Delete for good: the notice went without Undo (the call is recorded for the tests)
    if (req.op === 'libraryEraseTrashed') {
      undos.delete(req.undoId)
      return null
    }
    if (!req.undoId || !UNDOABLE.has(req.op)) return libraryHandleOnce(req)
    const before = {items: clone(libItems), playlists: clone(libPlaylists), downloaded: clone(libDownloaded), spaces: clone(libSettings.spaces), routes: clone(libSettings.routes)}
    const out = await libraryHandleOnce(req)
    const run = undoFor(req, before, out)
    if (run) undos.set(req.undoId, run)
    return out
  }
  async function libraryHandleOnce(req) {
    const ids = req.ids || (req.id ? [req.id] : [])
    switch (req.op) {
      case 'libraryState':
      case 'libraryResync':
        await wait(40)
        return libState()
      case 'libraryScan':
        return libState()
      case 'libraryCancel':
        libOperation = undefined
        pushLibrary()
        return null
      case 'spaceSave': {
        const def = req.spaceDef
        if (!def || !def.name || (def.type !== 'smart' && !def.folder)) throw new Error('A space needs a name and a folder.')
        const at = libSettings.spaces.findIndex((space) => space.id === def.id)
        if (at >= 0) libSettings.spaces[at] = def
        else libSettings.spaces.push(def)
        withSettings()
        pushLibrary()
        return snapshot()
      }
      case 'spaceDelete': {
        if (['downloads', 'music', 'videos', 'favorites'].includes(req.id)) throw new Error('Downloads, Music, Videos and Favorites can be renamed but not removed.')
        libSettings.spaces = libSettings.spaces.filter((space) => space.id !== req.id)
        const first = libSettings.spaces[0]?.id
        if (libSettings.routes.music === req.id) libSettings.routes.music = first
        if (libSettings.routes.other === req.id) libSettings.routes.other = first
        libItems = libItems.filter((item) => item.space !== req.id)
        withSettings()
        pushLibrary()
        return snapshot()
      }
      case 'spaceReorder': {
        const order = req.ids || []
        const known = new Map(libSettings.spaces.map((space) => [space.id, space]))
        if (order.some((id) => !known.has(id)) || new Set(order).size !== order.length) throw new Error('That list of spaces is out of date. Refresh and try again.')
        libSettings.spaces = [...order.map((id) => known.get(id)), ...libSettings.spaces.filter((space) => !order.includes(space.id))]
        withSettings()
        pushLibrary()
        return snapshot()
      }
      case 'libraryTag': {
        const add = (req.tags && req.tags.add) || []
        const remove = (req.tags && req.tags.remove) || []
        libItems = libItems.map((item) => {
          if (!ids.includes(item.id)) return item
          const next = [...new Set([...(item.userTags || []).filter((tag) => !remove.includes(tag)), ...add])]
          return {...item, userTags: next.length ? next : undefined}
        })
        pushLibrary()
        return null
      }
      case 'libraryAddFrom': {
        const target = libSettings.spaces.find((space) => space.id === req.space)
        if (!target || target.type === 'smart') throw new Error('That space no longer exists.')
        const wanted = [...ids]
        for (const collectionId of req.collectionIds || []) {
          const found = [...libPlaylists, ...libDownloaded].find((entry) => entry.id === collectionId)
          if (found) wanted.push(...found.itemIds)
        }
        const unique = [...new Set(wanted)].filter((id) => libById(id))
        libOperation = {kind: req.addMode === 'copy' ? 'copy' : 'move', done: 0, total: unique.length}
        pushLibrary()
        await wait(250)
        const moved = []
        for (const id of unique) {
          const item = libById(id)
          const next = rebase(item, target)
          if (req.addMode === 'copy') {
            libItems.push({...next, id: `${item.id.split('~')[0]}~copy${++idSeq}`})
            moved.push(id)
          } else {
            remap(id, next)
            moved.push(next.id)
          }
        }
        libOperation = undefined
        pushLibrary()
        return {moved, failed: [], notes: [], cancelled: false}
      }
      case 'collectionFollow': {
        const found = libDownloaded.find((entry) => entry.id === req.id)
        if (!found) throw new Error('Collection not found.')
        found.follow = {...(found.follow || {mode: 'auto', intervalHours: 6, wifiOnly: true, notify: true, confirmAbove: 20}), ...(req.follow || {})}
        pushLibrary()
        return clone(found)
      }
      case 'collectionCheck': {
        const found = libDownloaded.find((entry) => entry.id === req.id)
        if (!found) throw new Error('Collection not found.')
        await wait(150)
        found.follow = {...found.follow, lastCheckedAt: Date.now(), lastError: undefined}
        let added = 0
        if (options.checkJobs && !fake.checked) {
          // The check queued downloads for the collection (SYNTHETIC): tests then move them along with patchJob/push.
          fake.checked = true
          added = options.checkJobs
          for (let n = 0; n < added; n++)
            jobs = [...jobs, {id: `job-sync-${n}`, url: `https://www.youtube.com/watch?v=sync${n}`, title: ['Sunrise Tape', 'Harbour Fog', 'Lantern Road'][n % 3], uploader: 'Quiet Hours', provider: 'YouTube', label: 'MP3 · 320 kbps', kind: 'audio', format: {type: 'audio', target: 'mp3', quality: '320'}, options: opts, status: 'queued', percent: 0, createdAt: Date.now() + n, space: found.space, collectionId: found.id, collectionIndex: 3 + n}]
          pushSnapshot()
        } else if (!fake.checked) {
          fake.checked = true
          added = 1
          libItems.push(song('a-drive-3', 'Tailwind', 'Quiet Hours', 'Road Trip Mix', undefined, {folder: 'Road Trip Mix', album: undefined}))
          found.itemIds = [...found.itemIds, 'a-drive-3']
          found.newItemIds = [...(found.newItemIds || []), 'a-drive-3']
        }
        pushLibrary()
        return [{id: found.id, added, removed: 0}]
      }
      case 'collectionReview': {
        const found = libDownloaded.find((entry) => entry.id === req.id)
        if (!found || !found.follow || !found.follow.pending) throw new Error('Nothing is waiting for review.')
        const count = found.follow.pending.count
        found.follow = {...found.follow, pending: undefined, lastCheckedAt: Date.now()}
        pushLibrary()
        return {id: found.id, added: count, removed: 0}
      }
      case 'collectionOpened': {
        const found = libDownloaded.find((entry) => entry.id === req.id)
        if (found && found.newItemIds) {
          found.newItemIds = undefined
          pushLibrary()
        }
        return null
      }
      case 'libraryImport': {
        await wait(200)
        const target = libSettings.spaces.find((space) => space.id === req.space)
        if (!target) throw new Error('That space no longer exists.')
        const picked = req.paths || (req.pick === 'folder' ? ['C:\\Imports\\Album Folder\\song1.mp3', 'C:\\Imports\\Album Folder\\song2.mp3'] : ['C:\\Imports\\imported-a.mp3', 'C:\\Imports\\imported-b.mp3'])
        const added = picked.map((file, n) => {
          const name = file.split('\\').pop().replace(/\.[^.]+$/, '')
          const id = `a-import-${++idSeq}`
          libItems.push({id, space: target.id, path: `${target.folder}\\${name}.mp3`, folder: '', kind: 'audio', title: name, artist: 'Imported', album: target.kind === 'music' ? 'Imported' : undefined, duration: 4, size: 3 * MB, modifiedAt: Date.now(), addedAt: Date.now() + n, hasArt: false})
          return id
        })
        pushLibrary()
        return {added, duplicates: [], failed: [], cancelled: false}
      }
      case 'libraryMove': {
        await wait(150)
        const target = libSettings.spaces.find((space) => space.id === req.space)
        if (!target) throw new Error('That space no longer exists.')
        const moved = []
        for (const id of ids) {
          const item = libById(id)
          if (!item) continue
          const next = rebase(item, target)
          remap(id, next)
          moved.push(next.id)
        }
        pushLibrary()
        return {moved, failed: [], notes: [], cancelled: false}
      }
      case 'libraryRename': {
        const item = libById(req.id)
        if (!item) throw new Error('That file is no longer in the library.')
        const ext = item.path.slice(item.path.lastIndexOf('.'))
        const dir = item.path.slice(0, item.path.lastIndexOf('\\'))
        const next = {...item, path: `${dir}\\${req.text}${ext}`, id: `${item.id.split('~')[0]}~r${++idSeq}`}
        remap(item.id, next)
        pushLibrary()
        return {id: next.id}
      }
      case 'libraryEditInfo': {
        const info = req.info || {}
        libItems = libItems.map((item) => (item.id === req.id ? {...item, ...Object.fromEntries(Object.entries(info).filter(([key]) => key !== 'cover'))} : item))
        pushLibrary()
        return null
      }
      case 'libraryDeleteFile': {
        // Long deletes report progress; the fake takes a few steps so a test can see them.
        const total = ids.length
        for (let step = 1; step <= 3 && total > 3; step++) {
          libOperation = {kind: 'delete', done: Math.floor((total * step) / 4), total, current: `file-${step}.mp3`}
          pushLibrary()
          await new Promise((resolve) => setTimeout(resolve, 300))
        }
        libOperation = undefined
        await wait(80)
        libItems = libItems.filter((item) => !ids.includes(item.id))
        libPlaylists = libPlaylists.map((playlist) => ({...playlist, itemIds: playlist.itemIds.filter((id) => !ids.includes(id))}))
        libDownloaded = libDownloaded.map((entry) => ({...entry, itemIds: entry.itemIds.filter((id) => !ids.includes(id))}))
        pushLibrary()
        // files in the Keepfin folder wait in its trash and Undo can bring them back; options.recycleBin makes the fake delete to the Recycle Bin
        return {deleted: ids, failed: [], ...(options.recycleBin ? {} : {undoable: ids})}
      }
      case 'libraryTrashList':
        await wait(30)
        return {hasFolder: options.trash !== 'nofolder', entries: options.trash === 'nofolder' ? [] : trashList()}
      case 'libraryTrashRestore': {
        const picked = fakeTrash.filter((entry) => ids.includes(entry.id))
        const ok = picked.filter((entry) => !entry.clash)
        const clashed = picked.filter((entry) => entry.clash)
        fakeTrash = fakeTrash.filter((entry) => !ok.includes(entry))
        const problem = clashed.length ? `“${clashed[0].title}”: a new file has its name now` : ''
        const restored = ok.length === 1 ? `Restored “${ok[0].title}”` : `Restored ${ok.length} files`
        return {done: ok.length, failed: clashed.length, message: clashed.length ? (ok.length ? `${restored}. ${problem}` : problem) : restored, ids: ok.map((entry) => entry.id)}
      }
      case 'libraryTrashErase': {
        const gone = fakeTrash.filter((entry) => ids.includes(entry.id))
        fakeTrash = fakeTrash.filter((entry) => !gone.includes(entry))
        return {done: gone.length, failed: 0, message: gone.length === 1 ? 'Deleted for good' : `Deleted ${gone.length} files for good`, ids: gone.map((entry) => entry.id)}
      }
      case 'libraryTrashEmpty': {
        if (options.trashDelay) await wait(options.trashDelay)
        const gone = fakeTrash
        fakeTrash = []
        return {done: gone.length, failed: 0, message: `Deleted ${gone.length} files for good`, ids: gone.map((entry) => entry.id)}
      }
      case 'libraryOpen':
      case 'libraryReveal':
      case 'libraryDrag':
        return null
      case 'libraryPlayback': {
        libItems = libItems.map((item) =>
          item.id === req.id
            ? {...item, position: req.finished ? undefined : req.position, lastPlayedAt: Date.now(), plays: req.finished ? (item.plays || 0) + 1 : item.plays, watched: req.finished && item.kind === 'video' ? true : item.watched}
            : item,
        )
        return null
      }
      case 'libraryFavorite':
        libItems = libItems.map((item) => (ids.includes(item.id) ? {...item, favorite: !!req.favorite} : item))
        pushLibrary()
        return null
      case 'libraryLyrics': {
        const item = libById(req.id)
        return item?.hasLyrics ? {synced: true, lines: lyricLines} : null
      }
      case 'libraryFindLyrics': {
        // options.findLyrics: 'found' | 'none' | 'busy' (default none); found stores lyrics the way a finished download does
        const item = libById(req.id)
        if (!item || item.kind !== 'audio') throw new Error('Choose a song from your library.')
        const outcome = options.findLyrics || 'none'
        if (outcome === 'found') {
          libItems = libItems.map((entry) => (entry.id === req.id ? {...entry, hasLyrics: true} : entry))
          pushLibrary()
        }
        return {outcome, message: {found: 'Lyrics added.', none: 'No lyrics found', busy: 'Could not reach the lyrics service. Try again later.'}[outcome]}
      }
      case 'songOfLink': {
        // options.captureSong: 'has' (a song you have) | 'video' (a song you have, with its video); any inspected link
        return options.captureSong ? {itemId: 'a-tidal', space: 'music', hasVideo: options.captureSong === 'video'} : null
      }
      case 'libraryFindMusicVideo': {
        const item = libById(req.id)
        if (!item) throw new Error('That file is no longer in the library.')
        if (options.musicVideoError) throw new Error('Keepfin could not look for the music video. Check your connection and try again.')
        if (/Fog/.test(item.title)) return {none: true}
        return {videoId: 'fixtureVid01', title: item.title + ' (Official Video)', channel: item.artist, views: 3_200_000_000, durationSec: 214}
      }
      case 'findMusicVideo': {
        // Capture: the video of the inspected song (window.__FAKE_OPTIONS.captureVideo: 'none' | 'error' | 'slow'; Fog has none)
        if (options.captureVideo === 'slow') await wait(1500)
        if (options.captureVideo === 'error') throw new Error('Keepfin could not look for the music video. Check your connection and try again.')
        const m = Object.values(media).find((item) => item && item.url === req.url)
        if (!m || options.captureVideo === 'none' || !(m.music && m.music.artist)) return {none: true}
        return {videoId: 'fixtureVid02', title: m.title + ' (Official Video)', channel: m.music.artist, views: 1_400_000, durationSec: 231}
      }
      case 'libraryGetMusicVideo': {
        libItems = libItems.map((item) => (item.id === req.id ? {...item, musicVideo: true} : item))
        pushLibrary()
        return null
      }
      case 'libraryRemoveMusicVideo': {
        libItems = libItems.map((item) => (item.id === req.id ? {...item, musicVideo: undefined} : item))
        pushLibrary()
        return null
      }
      case 'librarySearchLyrics':
        return libItems.filter((item) => item.hasLyrics && lyricLines.some((line) => norm(line.text).includes(norm(req.text)))).map((item) => item.id)
      case 'libraryPrepare': {
        const item = libById(req.id)
        if (!item) throw new Error('That file is no longer in the library.')
        if (item.id === 'a-far') {
          if (options.awayDevice === 'none') throw new Error('This is not on this PC, and no device that lets you play its files has it right now.')
          if (options.awayDevice === 'offline') return {unreachable: {device: 'Laptop', lastSeen: Date.now() - 3 * 3_600_000}}
          return {url: `${window.__KEEPFIN_MEDIA_BASE}/item/a-front`, remuxed: false, from: 'Laptop'}
        }
        if (item.id.startsWith('v-mkv')) return {unsupported: true, reason: 'This video uses a codec that Keepfin’s player can’t decode on this computer.'}
        return {url: `${window.__KEEPFIN_MEDIA_BASE}/item/${encodeURIComponent(item.id)}`, remuxed: false}
      }
      case 'libraryMatch': {
        const found = {}
        for (const url of req.urls || []) {
          const index = /sp(\d+)x/.exec(url)
          if ((index && Number(index[1]) % 4 === 0) || /tidal88/.test(url)) found[url] = {itemId: 'a-tidal', path: `${FOLDERS.music}\\Low Tide Hours\\Tidal (Night Version).mp3`, space: req.space, by: 'source'}
        }
        return libSettings.spaces.find((space) => space.id === req.space)?.skipExisting ? found : {}
      }
      case 'downloadAnyway': {
        const target = jobs.find((item) => item.id === req.id)
        if (!target) throw new Error('That download no longer exists.')
        patch(req.id, {existing: undefined, force: true, status: 'downloading', percent: 0, totalBytes: 40e6, downloadedBytes: 0, speed: 8 * MB, startedAt: Date.now(), completedAt: undefined})
        return snapshot()
      }
      case 'collectionSave':
      case 'playlistSave': {
        const input = req.collection || req.playlist
        let playlist = libPlaylists.find((entry) => entry.id === input.id)
        // as the main process does: a rename without songs keeps the songs the playlist holds now
        if (playlist) playlist = Object.assign(playlist, {name: input.name, ...(input.itemIds ? {itemIds: input.itemIds} : {}), updatedAt: Date.now()})
        else libPlaylists.push((playlist = {id: `pl-${++idSeq}`, name: input.name, kind: 'own', space: 'music', itemIds: input.itemIds || [], seen: [], createdAt: Date.now(), updatedAt: Date.now()}))
        pushLibrary()
        return clone(playlist)
      }
      case 'collectionAdd':
      case 'collectionRemove':
      case 'collectionReorder': {
        // as the main process does: on the playlist as it is saved, never on a list the page held
        const playlist = libPlaylists.find((entry) => entry.id === req.id)
        if (!playlist) throw new Error('Playlist not found.')
        const ids = req.ids || []
        if (req.op === 'collectionAdd') playlist.itemIds = [...playlist.itemIds, ...ids.filter((id) => !playlist.itemIds.includes(id))]
        else if (req.op === 'collectionRemove') playlist.itemIds = playlist.itemIds.filter((id) => !ids.includes(id))
        else playlist.itemIds = [...ids.filter((id) => playlist.itemIds.includes(id)), ...playlist.itemIds.filter((id) => !ids.includes(id))]
        playlist.updatedAt = Date.now()
        pushLibrary()
        return clone(playlist)
      }
      case 'collectionDelete':
      case 'playlistDelete':
        libPlaylists = libPlaylists.filter((entry) => entry.id !== req.id)
        pushLibrary()
        return null
      case 'collectionExport':
      case 'playlistExport':
        return {path: 'C:\\Users\\me\\Music\\playlist.m3u8', count: libPlaylists.find((entry) => entry.id === req.id)?.itemIds.length || 0}
      default:
        return NOT_HANDLED
    }
  }

  // ---------------------------------------------------------------- Activity centre (SYNTHETIC entries; the real list is built by the main process)
  // options.activity: 'seed' for a realistic list (4 unread), 'many' for a long one, or an array of entries. options.away: the
  // one-time "while you were away" count returned with the first activityList.
  let activityEntries = []
  let activityAway = options.away || 0
  const activityAt = (ago) => Date.now() - ago
  if (options.activity === 'seed' || options.activity === 'many') {
    const failedJob = jobs.find((item) => item.group && item.group.id === 'grp-road' && item.status === 'failed')
    activityEntries = [
      {id: 'act-review', kind: 'sync-review', at: activityAt(10 * min), title: 'Weekend clips has 28 new items waiting', detail: 'More than 20, so they wait for you', read: false, target: {collectionId: 'grp-clips'}, count: 28},
      {id: 'act-found', kind: 'sync-found', at: activityAt(40 * min), title: 'Road Trip Mix got 3 new songs', detail: 'All 3 saved', read: false, target: {collectionId: 'grp-drive', groupId: 'grp-road'}, count: 3},
      {id: 'act-failed', kind: 'failed', at: activityAt(65 * min), title: '1 item from Road Trip Mix 2026 failed', detail: 'Sign in required: this video is private.', read: false, target: {groupId: 'grp-road', jobIds: failedJob ? [failedJob.id] : []}, count: 1},
      {id: 'act-signin', kind: 'sign-in', at: activityAt(2 * hour), title: 'Sign in to YouTube again', detail: 'Private or members-only downloads need it', read: false, target: {provider: 'YouTube'}},
      ...(options.appVersion ? [] : [{id: 'act-ready', kind: 'update-ready', at: activityAt(5 * hour), title: 'Keepfin 0.4.1 is ready to install', read: true, target: {version: '0.4.1'}}]),
      {id: 'act-batch', kind: 'batch-done', at: activityAt(30 * hour), title: 'Northbound Films: Season One finished', detail: '4 of 4 items saved to Downloads', read: true, target: {groupId: 'grp-season'}, count: 4},
      {id: 'act-gone', kind: 'sync-found', at: activityAt(3 * day), title: 'Evening Radio got 2 new songs', detail: 'All 2 saved', read: true, target: {collectionId: 'col-removed'}, count: 2},
      {id: 'act-updated', kind: 'app-updated', at: activityAt(9 * day), title: `Keepfin updated to ${options.appVersion || '0.4.0'}`, detail: 'See what changed in this version', read: true, target: {version: options.appVersion || '0.4.0'}},
    ]
    if (options.activity === 'many')
      for (let n = 0; n < 40; n++)
        activityEntries.push({id: `act-old-${n}`, kind: 'batch-done', at: activityAt(((10 + n) * day) / 2), title: `Older playlist ${n + 1} finished`, detail: `${n + 3} of ${n + 3} songs saved to Music`, read: true, target: {groupId: 'grp-season'}, count: n + 3})
  } else if (Array.isArray(options.activity)) activityEntries = options.activity.map((entry) => ({read: false, at: Date.now(), ...entry}))
  const activityList = (away = 0) => ({entries: clone([...activityEntries].sort((a, b) => b.at - a.at)), away})
  const pushActivity = () => push({type: 'activity', activity: activityList()})
  fake.activity = {
    record(entry) {
      activityEntries = [{id: `act-new-${activityEntries.length}`, read: false, at: Date.now(), ...entry}, ...activityEntries]
      pushActivity()
    },
    entries: () => clone(activityEntries),
  }

  // ---------------------------------------------------------------- Keepfin folder (storage* and setup* operations; SYNTHETIC data)
  // Tests choose the screen with window flags, read on every call:
  //   window.__storagePhase  'firstStart' | 'needsSetup' | 'resumeSetup' | 'ready' (default 'ready', so the existing suites run the app).
  //                          Creating or opening a folder and finishing setup switch it back to 'ready'.
  //   window.__pickedPath    what the native folder picker returns (default D:\Keepfin; null cancels).
  //   window.__setupDiskFull true: the next setup run stops with 'stoppedDiskFull' once, then runs normally.
  // Folder paths pick the answer of storageInspectFolder: "Cloud" (inside OneDrive), "Existing" (a Keepfin folder),
  // "Newer" (made by a newer app), "Locked" (not writable), "Stuff" (non-empty, not Keepfin), "Tiny" (too small for the plan).
  const GB = 1024 * MB
  const storageState = {folder: {path: 'C:\\Users\\you\\Keepfin', libraryId: 'lib-fake'}, musicShortcut: true, checking: false}
  const setupCounts = {media: 1548, sidecars: 212, history: 2014, playlists: 12, collections: 3, favourites: 230, userTags: 86, art: 1490}
  const setupSources = [
    {id: 'music', name: 'Music', path: 'C:\\Users\\you\\Music\\Keepfin', files: 1240, bytes: 8.2 * GB, sameDrive: true, suggested: 'move'},
    {id: 'downloads', name: 'Downloads', path: 'C:\\Users\\you\\Downloads\\Keepfin', files: 310, bytes: 21 * GB, sameDrive: false, suggested: 'copy'},
    {id: 'videos', name: 'Videos', path: 'C:\\Users\\you\\Videos\\Keepfin', files: 0, bytes: 0, sameDrive: true, suggested: 'leave'},
  ]
  let setupDiskFullSent = false
  let setupTimer
  const storagePhase = () => window.__storagePhase || options.storagePhase || 'ready'
  const storageDone = () => {
    window.__storagePhase = 'ready'
    return {phase: 'ready', defaultPath: 'C:\\Users\\you\\Keepfin', folder: storageState.folder}
  }
  function storageInspect(path) {
    const has = (word) => path.includes(word)
    return {
      path,
      exists: has('Existing') || has('Newer') || has('Stuff') || has('Cloud'),
      empty: !(has('Existing') || has('Newer') || has('Stuff')),
      isKeepfinFolder: has('Existing') || has('Newer'),
      newerFormat: has('Newer') || undefined,
      writable: !has('Locked'),
      freeBytes: has('Tiny') ? 4 * GB : 412 * GB,
      insideCloudApp: has('Cloud') ? 'onedrive' : null,
      problem: has('Locked') ? 'Keepfin cannot write to this folder. Pick another one.' : undefined,
    }
  }
  function setupRun(runId) {
    clearTimeout(setupTimer)
    const total = 1550
    const bytesTotal = 29.2 * GB
    const phases = ['preparing', 'copying', 'copying', 'copying', 'verifying', 'saving', 'cleaning', 'done']
    let index = 0
    const tick = () => {
      const phase = phases[index]
      const part = Math.min(1, index / (phases.length - 2))
      if (window.__setupDiskFull && index === 3) {
        window.__setupDiskFull = false
        push({type: 'setup', progress: {runId, phase: 'stoppedDiskFull', done: 812, total, bytesDone: 5.4 * GB, bytesTotal, current: 'Glass Harbour.mp3', failed: 0}})
        return
      }
      push({
        type: 'setup',
        progress: {runId, phase, done: Math.round(total * part), total, bytesDone: Math.round(bytesTotal * part), bytesTotal, current: phase === 'done' ? undefined : 'Glass Harbour.mp3', failed: phase === 'done' ? 2 : 0},
      })
      index++
      if (index < phases.length) setupTimer = setTimeout(tick, 80 * options.latency)
    }
    setupTimer = setTimeout(tick, 40 * options.latency)
  }
  async function storageHandle(req) {
    const args = req.storage || {}
    switch (req.op) {
      case 'storageState':
        await wait(10)
        return storagePhase() === 'ready'
          ? {phase: 'ready', defaultPath: 'C:\\Users\\you\\Keepfin', folder: storageState.folder}
          : {phase: storagePhase(), defaultPath: 'C:\\Users\\you\\Keepfin', runId: storagePhase() === 'resumeSetup' ? 'run-fake' : undefined, folder: storageState.folder, move: window.__move}
      case 'storagePickFolder':
        await wait(20)
        return window.__pickedPath === undefined ? 'D:\\Keepfin' : window.__pickedPath
      case 'storageInspectFolder':
        await wait(20)
        return storageInspect(args.path || '')
      case 'storageCreate': {
        await wait(60)
        const choice = storageInspect(args.path || '')
        if (choice.problem) throw new Error(choice.problem)
        storageState.folder = {path: args.path, libraryId: 'lib-fake'}
        storageState.musicShortcut = args.musicShortcut !== false
        return storageDone()
      }
      case 'storageOpenExisting':
        await wait(60)
        if ((args.path || '').includes('Newer')) throw new Error('This Keepfin folder was made by a newer version of the app.')
        if (!(args.path || '').includes('Existing')) throw new Error('There is no Keepfin folder at that place.')
        storageState.folder = {path: args.path, libraryId: 'lib-existing'}
        return storageDone()
      // Storage › Change location: a request "restarts" into the move window; Start copies in three steps, then Done
      case 'storageMoveCheck': {
        await wait(20)
        const to = args.path || ''
        const from = storageState.folder.path
        const problem = to.toLowerCase().startsWith(from.toLowerCase() + '\\') ? 'The new place cannot be inside your Keepfin folder.' : undefined
        const sameDrive = to.slice(0, 2).toLowerCase() === from.slice(0, 2).toLowerCase()
        return {problem, bytes: 29.2 * GB, freeBytes: 184 * GB, sameDrive}
      }
      case 'storageMoveRequest':
        await wait(20)
        window.__move = {from: storageState.folder.path, to: args.path, keepOld: !!args.keepOld}
        window.__storagePhase = 'moveFolder'
        setTimeout(() => push({type: 'storage', state: {phase: 'moveFolder', defaultPath: 'C:\\Users\\you\\Keepfin', folder: storageState.folder, move: window.__move}}), 50)
        return null
      case 'storageMoveStart': {
        const move = window.__move
        const delay = options.moveDelay ?? 120
        ;[0, 1, 2].forEach((step) => setTimeout(() => push({type: 'storageMove', progress: {phase: 'copying', done: step, total: 3, bytesDone: step * GB, bytesTotal: 3 * GB, current: `Music/song ${step + 1}.mp3`}}), step * delay))
        setTimeout(() => {
          storageState.folder = {...storageState.folder, path: move.to}
          window.__storagePhase = 'ready'
          push({type: 'storageMove', progress: {phase: 'done', done: 3, total: 3, bytesDone: 3 * GB, bytesTotal: 3 * GB}})
        }, 3 * delay)
        return null
      }
      case 'storageMoveCancel':
        window.__storagePhase = 'ready'
        window.__moveCancelled = true
        setTimeout(() => push({type: 'storage', state: {phase: 'ready', defaultPath: 'C:\\Users\\you\\Keepfin', folder: storageState.folder}}), 50)
        return null
      case 'storageOverview':
      case 'storageCheckFiles':
      case 'storageSetMusicShortcut':
      case 'storageOpenFolder': {
        if (req.op === 'storageOpenFolder') return null
        if (req.op === 'storageSetMusicShortcut') storageState.musicShortcut = !!args.enabled
        if (req.op === 'storageCheckFiles') {
          await wait(120)
          storageState.checked = Date.now()
        } else await wait(30)
        const missing = options.storageMissing === undefined ? 0 : options.storageMissing
        return {
          path: storageState.folder.path,
          drive: 'Local Disk (C:)',
          freeBytes: 184 * GB,
          totalBytes: 476 * GB,
          bytes: 29.2 * GB,
          ...(options.musicVideoBytes ? {musicVideoBytes: options.musicVideoBytes} : {}),
          spaces: [
            {id: 'music', name: 'Music', files: 1240, bytes: 8.2 * GB},
            {id: 'downloads', name: 'Downloads', files: 308, bytes: 21 * GB},
            {id: 'videos', name: 'Videos', files: 0, bytes: 0},
          ],
          health: {missing, checking: !!options.storageChecking, lastCheckedAt: storageState.checked},
          insideCloudApp: options.storageCloud ? 'onedrive' : null,
          musicShortcut: storageState.musicShortcut,
        }
      }
      case 'setupInventory':
        await wait(40)
        return {sources: clone(setupSources), counts: clone(setupCounts), unindexed: 6, missing: 2}
      case 'setupPlan': {
        await wait(40)
        const choices = args.choices || {}
        const fits = !(args.path || '').includes('Tiny')
        const copied = Object.entries(choices).reduce((sum, [id, how]) => {
          const source = setupSources.find((entry) => entry.id === id)
          return source && (how === 'copy' || (how === 'move' && !source.sameDrive)) ? sum + source.bytes : sum
        }, 0)
        return {
          runId: 'run-fake',
          target: args.path,
          choices,
          bytesToCopy: copied,
          freeBytes: fits ? 412 * GB : 4 * GB,
          fits: fits && copied < 400 * GB,
          files: 1550,
          lines: [
            `${choices.music === 'copy' ? 'Copy' : choices.music === 'leave' ? 'Leave' : 'Move'} 1,240 songs from Music`,
            `${choices.downloads === 'move' ? 'Move' : choices.downloads === 'leave' ? 'Leave' : 'Copy'} 310 files from Downloads`,
            'Bring over your history, 12 playlists, 230 favourites and your tags',
          ],
        }
      }
      case 'setupStart':
        setupRun(args.runId || 'run-fake')
        return null
      case 'setupAbandon':
        clearTimeout(setupTimer)
        window.__storagePhase = 'needsSetup'
        return {phase: 'needsSetup', defaultPath: 'C:\\Users\\you\\Keepfin'}
      case 'setupReport':
        window.__storagePhase = 'ready'
        return {
          runId: args.runId || 'run-fake',
          before: clone(setupCounts),
          after: {...setupCounts, media: 1546},
          attention: [
            {name: 'Tokyo Night Walk.mkv', reason: 'In use by another app', actions: ['retry', 'leave']},
            {name: 'Speedrun finale.mp4', reason: 'Not found on disk', actions: ['locate', 'leave']},
          ],
          text: 'Keepfin setup report (synthetic)',
        }
      case 'setupSaveReport':
        return {path: 'C:\\Users\\you\\Documents\\Keepfin setup report.txt'}
      default:
        return NOT_HANDLED
    }
  }

  // ---------------------------------------------------------------- Copies on folders and drives (copies* operations; SYNTHETIC data)
  // options.copies: 'empty' (no copies yet) | 'unavailable' (no library open). Folder paths pick the answer of copiesCheckFolder
  // and copiesPreview: "Nested" (inside the Keepfin folder), "Existing" (240 files already there), "Mine" (already a Keepfin copy),
  // "Cloud" (inside OneDrive), "Full" (not enough space), "Held" (a large change waits for OK), "Refused" (cannot be used).
  // window.__pickedCopyPath is what the native folder picker returns (default D:\Spare drive; null cancels).
  const copyRows = [
    {key: 'space:music', kind: 'space', id: 'music', name: 'Music'},
    {key: 'space:videos', kind: 'space', id: 'videos', name: 'Videos'},
    {key: 'space:downloads', kind: 'space', id: 'downloads', name: 'Downloads'},
    {key: 'favourites', kind: 'favourites', id: 'favourites', name: 'Favourites'},
    {key: 'playlist:road', kind: 'playlist', id: 'road', name: 'Road trip'},
    {key: 'playlist:gym', kind: 'playlist', id: 'gym', name: 'Gym'},
  ]
  const copyAllCells = (cell) => Object.fromEntries(copyRows.map((row) => [row.key, cell]))
  const copyCellsFor = (holds, cell) => {
    const out = {}
    for (const row of copyRows) {
      const held = holds.all || (row.kind === 'space' && holds.spaces.includes(row.id)) || (row.kind === 'playlist' && holds.playlists.includes(row.id)) || (row.kind === 'favourites' && holds.favourites)
      out[row.key] = held ? cell : 'notHeld'
    }
    return out
  }
  let copiesData
  // one timer per copy: Sync all runs several at once
  const copiesTimers = new Map()
  let copiesDraft
  function copiesInit() {
    if (copiesData) return copiesData
    const empty = options.copies === 'empty'
    copiesData = {
      places: empty
        ? []
        : [
            {id: 'p-backup', name: 'Backup drive (E:)', path: 'E:\\', mode: 'backup', holds: {all: true, spaces: [], playlists: [], favourites: false}, schedule: {everyHours: 24, auto: true}, connected: true, status: 'upToDate', lastVerifiedAt: Date.now() - 3 * 3600_000, kept: 1548, waiting: 0, review: 0, freeBytes: 612 * GB, totalBytes: 931 * GB, versions: {files: 14, deletedFiles: 3}, previewPending: false, cells: copyAllCells('kept')},
            {id: 'p-laptop', name: 'Work laptop', path: 'D:\\Sync\\Keepfin', mode: 'twoWay', holds: {all: false, spaces: ['music'], playlists: ['road'], favourites: true}, schedule: {everyHours: null, auto: true}, connected: true, status: 'waiting', lastVerifiedAt: Date.now() - 26 * 3600_000, kept: 1180, waiting: 12, review: 1, freeBytes: 88 * GB, totalBytes: 238 * GB, previewPending: false, cells: {...copyCellsFor({all: false, spaces: ['music'], playlists: ['road'], favourites: true}, 'kept'), 'space:music': 'waiting', 'playlist:road': 'review'}},
            {id: 'p-stick', name: 'Car USB stick (F:)', path: 'F:\\', mode: 'mirror', holds: {all: false, spaces: [], playlists: ['gym'], favourites: false}, schedule: {everyHours: null, auto: true}, connected: false, status: 'disconnected', statusText: 'The drive or folder is not connected.', lastVerifiedAt: Date.now() - 9 * 86400_000, kept: 64, waiting: 3, review: 0, previewPending: false, cells: copyCellsFor({all: false, spaces: [], playlists: ['gym'], favourites: false}, 'waiting')},
          ],
      review: empty
        ? []
        : [
            {id: 'r-conflict', placeId: '', placeName: 'Library', kind: 'conflict', title: 'Glass Harbour.mp3 was edited in two places', detail: 'Both versions are kept (2). Choose the one to keep going forward; the other stays in the Keepfin trash.', actions: ['takeMain', 'takeCopy', 'keepBoth']},
            {id: 'r-deleted', placeId: 'p-laptop', placeName: 'Work laptop', kind: 'copyDeleted', title: 'Night Drive.mp3 was removed from the copy', detail: 'Keepfin copied it back. If you meant to delete it everywhere, confirm: it goes to the Keepfin trash and leaves your library.', path: 'Music/Night Drive.mp3', actions: ['confirmDelete', 'restore']},
            {id: 'r-unknown', placeId: 'p-backup', placeName: 'Backup drive (E:)', kind: 'unknownFile', title: 'holiday.mov is not Keepfin\u2019s', detail: 'This file is on the copy but Keepfin did not put it there. It is left alone.', path: 'holiday.mov', actions: ['dismiss']},
            // options.reviewGroup: several files of one kind on one copy, which the review shows as one group
            ...(options.reviewGroup
              ? [1, 2, 3].map((n) => ({id: `r-lrc-${n}`, placeId: 'p-laptop', placeName: 'Work laptop', kind: 'unknownFile', title: `lyrics-${n}.lrc is not Keepfin\u2019s`, detail: 'This file is on the copy but Keepfin did not put it there. It is left alone.', path: `Music/lyrics-${n}.lrc`, actions: ['dismiss']}))
              : []),
          ],
    }
    // Cloud accounts (SYNTHETIC): which providers this build offers, the accounts signed in on this computer, and (scenario 'cloud')
    // two copies in accounts, one of which needs a new sign-in. options.cloudHidden lists providers without a client id.
    const hidden = options.cloudHidden || []
    copiesData.cloudProviders = [
      {id: 'drive', name: 'Google Drive', available: !hidden.includes('drive'), scope: 'Keepfin sees only the files it creates itself.'},
      {id: 'graph', name: 'OneDrive', available: !hidden.includes('graph'), scope: 'Keepfin gets its own app folder and sees nothing else.'},
      {id: 'dropbox', name: 'Dropbox', available: !hidden.includes('dropbox'), scope: 'Keepfin gets its own app folder and sees nothing else.'},
      {id: 'webdav', name: 'WebDAV', available: !hidden.includes('webdav'), scope: 'A folder on your own server (Nextcloud, a NAS, any WebDAV address).'},
    ]
    copiesData.cloudAccounts = []
    if (options.copies === 'cloud') {
      copiesData.cloudAccounts.push(
        {id: 'acct-drive', provider: 'drive', providerName: 'Google Drive', label: 'me@example.org', needsSignIn: false, freeBytes: 218 * GB, totalBytes: 2048 * GB, copies: 1},
        {id: 'acct-ms', provider: 'graph', providerName: 'OneDrive', label: 'OneDrive', needsSignIn: true, copies: 1},
      )
      const cloudFor = (provider, providerName, accountId, accountLabel, folder, needsSignIn) => ({provider, providerName, accountId, accountLabel, folder, needsSignIn, signedInHere: true})
      copiesData.places.push(
        {id: 'p-cloud-drive', name: 'Cloud backup', path: '', mode: 'backup', holds: {all: true, spaces: [], playlists: [], favourites: false}, schedule: {everyHours: 24, auto: true}, connected: true, status: 'upToDate', lastVerifiedAt: Date.now() - 5 * 3600_000, kept: 1548, waiting: 0, review: 0, freeBytes: 218 * GB, totalBytes: 2048 * GB, versions: {files: 6, deletedFiles: 1}, previewPending: false, cells: copyAllCells('kept'), cloud: cloudFor('drive', 'Google Drive', 'acct-drive', 'me@example.org', 'Music', false)},
        {id: 'p-cloud-ms', name: 'Shared with the phone', path: '', mode: 'twoWay', holds: {all: true, spaces: [], playlists: [], favourites: false}, schedule: {everyHours: null, auto: true}, connected: false, status: 'disconnected', statusText: 'Sign in again to sync this copy.', lastVerifiedAt: Date.now() - 3 * 86400_000, kept: 902, waiting: 4, review: 0, previewPending: false, cells: copyAllCells('waiting'), cloud: cloudFor('graph', 'OneDrive', 'acct-ms', 'OneDrive', 'Shared', true)},
      )
      copiesData.review.unshift({id: 'r-signin', placeId: 'p-cloud-ms', placeName: 'Shared with the phone', kind: 'signIn', title: 'Sign in to OneDrive again', detail: 'OneDrive needs a new sign-in before this copy can sync. Nothing was changed, and your files are safe where they are.', actions: ['signIn']})
    }
    return copiesData
  }
  const copiesView = () => {
    const data = copiesInit()
    for (const account of data.cloudAccounts) account.copies = data.places.filter((p) => p.cloud && p.cloud.accountId === account.id).length
    return clone({available: options.copies !== 'unavailable', places: data.places.map((p) => ({...p, musicVideos: p.musicVideos ?? p.holds.all})), rows: copyRows, review: data.review, reviewMore: 0, syncing: data.places.some((p) => p.status === 'syncing'), ...(data.report ? {report: data.report} : {}), ...(data.found ? {found: data.found} : {}), ...(options.cloud === 'off' ? {} : {cloud: {providers: data.cloudProviders, accounts: data.cloudAccounts}}), ...(options.copiesDevices ? {devices: copiesDeviceChoices()} : {})})
  }
  let cloudSignInReject
  const cloudProviderName = {drive: 'Google Drive', graph: 'OneDrive', dropbox: 'Dropbox', webdav: 'WebDAV'}
  const copiesPush = () => push({type: 'copies', state: copiesView()})
  // tests change the copy on a paired device (offline, needs a decision, up to date) and the page follows the push
  fake.copiesDevicePlace = (patch) => {
    const place = copiesInit().places.find((p) => p.device)
    if (!place) return
    clearTimeout(copiesTimers.get(place.id))
    if (patch.device) place.device = {...place.device, ...patch.device}
    Object.assign(place, {...patch, device: place.device})
    copiesPush()
  }
  // tests change one copy by id (a backup with no earlier versions) and the page follows the push
  fake.copiesPatch = (id, patch) => {
    const place = copiesInit().places.find((p) => p.id === id)
    if (!place) return
    Object.assign(place, patch)
    copiesPush()
  }
  function copiesRun(id) {
    const data = copiesInit()
    const place = data.places.find((p) => p.id === id)
    if (!place || place.status === 'syncing') return
    clearTimeout(copiesTimers.get(id))
    place.status = 'syncing'
    place.statusText = undefined
    const total = Math.max(place.waiting, 24)
    let done = 0
    const tick = () => {
      if (place.status !== 'syncing') return
      done += Math.ceil(total / 5)
      if (done >= total) {
        place.problem = undefined
        place.status = place.review > 0 ? 'review' : 'upToDate'
        place.kept += place.waiting
        place.waiting = 0
        place.lastVerifiedAt = Date.now()
        place.cells = copyCellsFor(place.holds, 'kept')
        place.progress = undefined
        // the core's words for one copy that updated files (core/.../copies/SyncReport.kt)
        data.report = {title: 'Copy synced', detail: `${total} files updated.`, warn: false, at: Date.now()}
      } else {
        place.progress = {doneActions: done, totalActions: total, doneBytes: done * 6 * MB, totalBytes: total * 6 * MB, current: 'Music/Glass Harbour.mp3'}
        copiesTimers.set(id, setTimeout(tick, 120 * options.latency))
      }
      copiesPush()
    }
    copiesTimers.set(id, setTimeout(tick, 120 * options.latency))
    copiesPush()
  }
  async function copiesHandle(req) {
    if (!req.op.startsWith('copies')) return NOT_HANDLED
    const args = req.copies || {}
    const data = copiesInit()
    const find = (id) => {
      const place = data.places.find((p) => p.id === id)
      if (!place) throw new Error('That copy is gone.')
      return place
    }
    switch (req.op) {
      case 'copiesState':
        await wait(30)
        return copiesView()
      case 'copiesPickFolder':
        return '__pickedCopyPath' in window ? window.__pickedCopyPath : 'D:\\Spare drive'
      case 'copiesCheckFolder': {
        await wait(40)
        const p = args.path || ''
        // the Keepfin folder the wizard makes inside a picked folder does not exist yet and is named after that folder
        const made = /\\Keepfin$/i.test(p)
        const name = (made ? p.replace(/\\Keepfin$/i, '') : p).split('\\').filter(Boolean).pop() || 'Copy'
        const base = {path: p, ok: true, suggestedName: name, freeBytes: 412 * GB, totalBytes: 931 * GB, existingFiles: 0, ...(made ? {willCreate: true} : {})}
        if (p.includes('Nested')) return {...base, ok: false, problem: 'A copy cannot be inside your Keepfin folder, or contain it.'}
        if (p.includes('Mine')) return {...base, ok: false, problem: 'This is already your copy "Backup drive (E:)".'}
        if (p.includes('Existing') && !made) return {...base, existingFiles: 240}
        if (p.includes('CopyDrive')) return {...base, isCopy: true, keepfinFolder: true}
        if (p.includes('Cloud')) return {...base, insideCloudApp: 'onedrive'}
        return base
      }
      case 'copiesPreview': {
        await wait(220)
        const draft = args.draft
        // a cloud copy has no path: its folder name picks the answer the same way
        const p = draft.cloud ? draft.cloud.folder : draft.device ? (copiesDeviceChoices().find((c) => c.deviceId === draft.device.deviceId) || {name: ''}).name : draft.path
        copiesDraft = {draft, placeId: args.placeId || 'draft-1'}
        const base = {placeId: copiesDraft.placeId, status: 'ready', additiveOnly: true, lines: [], copyFiles: 0, copyBytes: 0, destructive: 0, differences: [], differencesMore: 0, enough: true, freeBytes: 412 * GB, importsToLibrary: 0}
        if (draft.device) {
          // a copy on a paired device (the setup after pairing): window.__devicePreview picks the answer
          // 'differences' (7 files differ, more than the five shown) | 'full' (not enough space) | 'held' | 'busy' | 'disconnected' | 'refused'
          const kind = window.__devicePreview || ''
          const found = {copyFiles: 800, copyBytes: 12.5 * GB, lines: [{label: 'Put on the copy', files: 800, bytes: 12.5 * GB}], importsToLibrary: draft.mode === 'twoWay' ? 36 : 0}
          const out = {...base, ...found}
          if (kind === 'differences') return {...out, differences: [1, 2, 3, 4, 5, 6].map((i) => ({path: `Music/Track ${i}.mp3`, detail: 'Different on both sides'})), differencesMore: 1}
          if (kind === 'full') return {...out, enough: false, freeBytes: 2 * GB}
          if (kind === 'held') return {...out, status: 'held', reason: 'A large change is waiting.', destructive: 900}
          if (kind === 'busy') return {...base, status: 'busy'}
          if (kind === 'disconnected') return {...base, status: 'disconnected'}
          if (kind === 'refused') return {...base, status: 'refused', reason: 'That device cannot keep this copy.'}
          return out
        }
        if (p.includes('Refused')) return {...base, status: 'refused', reason: 'The copy is reached through a link.'}
        const lines = [{label: 'Put on the copy', files: 1548, bytes: 29.2 * GB}]
        const out = {...base, lines, copyFiles: 1548, copyBytes: 29.2 * GB}
        if (p.includes('Full')) return {...out, enough: false, freeBytes: 2 * GB}
        if (p.includes('Held')) return {...out, status: 'held', reason: 'Over half of the files on the copy would be replaced.', destructive: 900}
        if (p.includes('Existing') && draft.mode === 'twoWay') return {...out, importsToLibrary: 180, lines: [...lines, {label: 'Add to your library from the copy', files: 180, bytes: 1.4 * GB}], differences: [{path: 'Music/Glass Harbour.mp3', detail: 'Different on both sides'}, {path: 'Music/Night Drive.mp3', detail: 'Different on both sides'}], differencesMore: 7}
        if (p.includes('Existing')) return {...out, differences: [{path: 'Music/Glass Harbour.mp3', detail: 'Different on both sides'}], differencesMore: 0}
        return out
      }
      case 'copiesCancelPreview':
        copiesDraft = undefined
        return null
      case 'copiesAdd': {
        if (!copiesDraft) throw new Error('This preview has expired. Start again.')
        const d = copiesDraft.draft
        const id = `p-new-${data.places.length + 1}`
        const account = d.cloud ? data.cloudAccounts.find((a) => a.id === d.cloud.accountId) : undefined
        const peer = d.device ? copiesDeviceChoices().find((c) => c.deviceId === d.device.deviceId) : undefined
        data.places.push({id, name: d.name, path: d.cloud || d.device ? '' : d.path, ...(peer ? {device: {deviceId: peer.deviceId, deviceName: peer.name, online: peer.online}} : {}), mode: d.mode, holds: d.holds, ...(typeof d.musicVideos === 'boolean' ? {musicVideos: d.musicVideos} : {}), schedule: d.schedule, connected: true, status: 'waiting', kept: 0, waiting: 1548, review: 0, freeBytes: account && account.freeBytes ? account.freeBytes : 412 * GB, totalBytes: account && account.totalBytes ? account.totalBytes : 931 * GB, previewPending: false, cells: copyCellsFor(d.holds, 'waiting'), ...(d.mode === 'backup' ? {versions: {files: 0, deletedFiles: 0}} : {}), ...(account ? {cloud: {provider: account.provider, providerName: account.providerName, accountId: account.id, accountLabel: account.label, folder: d.cloud.folder, needsSignIn: false, signedInHere: true}} : {})})
        copiesDraft = undefined
        setTimeout(() => copiesRun(id), 30)
        return copiesView()
      }
      case 'copiesAdopt':
        return {path: args.found, ok: true, suggestedName: 'Found drive'}
      case 'copiesSync':
        for (const p of data.places) if ((!args.placeId || p.id === args.placeId) && p.connected) copiesRun(p.id)
        return copiesView()
      case 'copiesPause': {
        for (const p of data.places) if ((!args.placeId || p.id === args.placeId) && p.status === 'syncing') p.status = 'paused'
        return copiesView()
      }
      case 'copiesEdit': {
        const place = find(args.placeId)
        if (args.name) place.name = args.name
        if (args.holds) {
          place.holds = args.holds
          const wasKept = place.cells
          place.cells = copyCellsFor(place.holds, 'waiting')
          for (const key of Object.keys(place.cells)) if (wasKept[key] === 'kept' && place.cells[key] === 'waiting') place.cells[key] = 'kept'
        }
        if (args.schedule) place.schedule = args.schedule
        if (typeof args.companions === 'boolean') place.companions = args.companions
        if (typeof args.musicVideos === 'boolean') place.musicVideos = args.musicVideos
        return copiesView()
      }
      case 'copiesRemove': {
        // the work goes on after the click: the row says what it is doing; window.__copiesRemove = 'hold' waits for window.__finishRemove(), 'fail' ends in an error
        const place = find(args.placeId)
        if (place.removal?.state === 'removing') return copiesView()
        place.removal = {state: 'removing', text: args.deleteFiles ? 'Moving its files to the Recycle Bin…' : 'Stopping the copy…'}
        copiesPush()
        await new Promise((resolve) => {
          window.__finishRemove = resolve
          if (window.__copiesRemove !== 'hold') setTimeout(resolve, 300 * options.latency)
        })
        if (window.__copiesRemove === 'fail') {
          const why = 'Keepfin could not move E:\\ to the Recycle Bin. Another program may have it open; close it and try again. The copy was not removed.'
          place.removal = {state: 'failed', text: why}
          copiesPush()
          throw new Error(why)
        }
        data.places = data.places.filter((p) => p.id !== args.placeId)
        data.report = {title: `${place.name} was removed`, detail: args.deleteFiles ? 'Its files are in the Recycle Bin.' : 'Its files stay where they are.', warn: false, at: Date.now()}
        return copiesView()
      }
      case 'copiesResolve': {
        const item = data.review.find((r) => r.id === args.reviewId)
        if (!item) throw new Error('This was already settled.')
        data.review = data.review.filter((r) => r.id !== args.reviewId)
        const place = data.places.find((p) => p.id === item.placeId)
        if (item.kind === 'signIn' && place && place.cloud) {
          // Sign in again from Needs review: the browser step, then the copy syncs again
          await wait(300 * options.latency)
          const account = data.cloudAccounts.find((a) => a.id === place.cloud.accountId)
          if (account) account.needsSignIn = false
          place.cloud.needsSignIn = false
          place.connected = true
          place.status = 'waiting'
          place.statusText = undefined
          setTimeout(() => copiesRun(place.id), 30)
          return copiesView()
        }
        if (place && place.review > 0) place.review -= 1
        return copiesView()
      }
      case 'copiesVersions':
        find(args.placeId)
        return [
          {id: 'v1', name: 'Glass Harbour.mp3', path: 'Music/Glass Harbour.mp3', archivedAt: Date.now() - 2 * 86400_000, why: 'replaced', deleted: false},
          {id: 'v2', name: 'Old demo.mp3', path: 'Music/Old demo.mp3', archivedAt: Date.now() - 12 * 86400_000, why: 'deleted', deleted: true},
        ]
      case 'copiesRestore':
        find(args.placeId)
        return copiesView()
      case 'copiesCleanup': {
        const place = find(args.placeId)
        if (place.versions) place.versions = {files: 0, deletedFiles: 0}
        return copiesView()
      }
      case 'copiesCloudSignIn': {
        const c = args.cloud || {}
        if (!['drive', 'graph', 'dropbox'].includes(c.provider)) throw new Error('Invalid request.')
        // the browser step: waits until the test lets it finish (window.__cloudSignIn = 'fail' | 'hold'), else a short moment
        await new Promise((resolve, reject) => {
          cloudSignInReject = () => reject(new Error('Sign-in was cancelled.'))
          if (window.__cloudSignIn === 'hold') return
          setTimeout(resolve, 350 * options.latency)
        })
        cloudSignInReject = undefined
        if (window.__cloudSignIn === 'fail') throw new Error('Access was not allowed in the browser.')
        let account = c.accountId ? data.cloudAccounts.find((a) => a.id === c.accountId) : undefined
        if (account) {
          account.needsSignIn = false
          data.review = data.review.filter((r) => !(r.kind === 'signIn' && data.places.find((p) => p.id === r.placeId)?.cloud?.accountId === account.id))
          for (const p of data.places) {
            if (p.cloud && p.cloud.accountId === account.id) {
              p.cloud.needsSignIn = false
              p.connected = true
              p.status = 'waiting'
              p.statusText = undefined
            }
          }
        } else {
          account = {id: `acct-${data.cloudAccounts.length + 1}`, provider: c.provider, providerName: cloudProviderName[c.provider], label: c.provider === 'drive' ? 'me@example.org' : cloudProviderName[c.provider], needsSignIn: false, freeBytes: 218 * GB, totalBytes: 2048 * GB, copies: 0}
          data.cloudAccounts.push(account)
        }
        return {account: clone(account), state: copiesView()}
      }
      case 'copiesCloudCancel':
        if (cloudSignInReject) cloudSignInReject()
        return null
      case 'copiesCloudWebdav': {
        const c = args.cloud || {}
        await wait(200)
        if (!/^https:\/\//.test(c.url || '')) throw new Error('Use an https:// address. Keepfin will not send a password over an unencrypted connection.')
        if (c.password === 'wrong') throw new Error('The server did not accept this user name and password.')
        const host = new URL(c.url).host
        const account = {id: `acct-${data.cloudAccounts.length + 1}`, provider: 'webdav', providerName: 'WebDAV', label: `${c.user} at ${host}`, needsSignIn: false, copies: 0, freeBytes: 1.2 * 1024 * GB, totalBytes: 4 * 1024 * GB, support: ['Checks that a file has not changed before replacing it.', 'Never writes over a file that is already there.', 'Can continue an interrupted download.']}
        data.cloudAccounts.push(account)
        return {account: clone(account), state: copiesView()}
      }
      case 'copiesCloudFind': {
        await wait(60)
        const account = data.cloudAccounts.find((a) => a.id === (args.cloud || {}).accountId)
        if (!account) throw new Error('This account is not on this computer any more.')
        if (account.provider === 'drive') return [{folder: 'Music', placeName: 'Cloud backup', placeId: 'p-cloud-drive'}, {folder: 'Phone library'}]
        return []
      }
      case 'copiesCloudAttach':
        return copiesView()
      case 'copiesCloudDisconnect': {
        const id = (args.cloud || {}).accountId
        data.cloudAccounts = data.cloudAccounts.filter((a) => a.id !== id)
        for (const p of data.places) {
          if (p.cloud && p.cloud.accountId === id) {
            p.connected = false
            p.status = 'disconnected'
            p.statusText = 'This account is not signed in on this computer.'
          }
        }
        return copiesView()
      }
      case 'copiesCloudRevoke': {
        const id = (args.cloud || {}).accountId
        const account = data.cloudAccounts.find((a) => a.id === id)
        data.cloudAccounts = data.cloudAccounts.filter((a) => a.id !== id)
        const microsoft = account && account.provider === 'graph'
        return {
          revoked: !microsoft,
          note: microsoft
            ? 'Microsoft does not let an app end this sign-in for personal accounts. Remove Keepfin in your Microsoft account’s app permissions.'
            : 'The sign-in was ended at the provider, so it is signed out on your other devices too.',
          ...(microsoft ? {url: 'https://account.live.com/consent/Manage'} : {}),
          state: copiesView(),
        }
      }
      default:
        return NOT_HANDLED
    }
  }
  // ---------------------------------------------------------------- Devices on the network (devices* operations; SYNTHETIC data)
  // options.devices: 'paired' (default: two paired devices, devices on), 'empty' (on, nobody paired), 'off' (not turned on yet),
  // 'unavailable' (no secure storage), 'portBusy' (another program has the port). Tests drive the other side with
  // window.__fake.devicesIncoming() (a device asks to pair with this one) and window.__fake.devicesPeerGone().
  const devicesData = {
    enabled: (options.devices || 'paired') !== 'off',
    name: 'Desk PC',
    pairingUntil: null,
    pairing: null,
    qr: undefined,
    // libraries other devices share that this one does not hold (options.devicesOffers), the join in progress, downloads sent to other devices,
    // and the library profiles on this device
    offers: options.devicesOffers ? [{deviceId: 'dev-laptop', deviceName: 'Laptop', libraryId: 'lib-2', name: 'Family music', online: true}] : [],
    join: null,
    remoteJobs: [],
    // options.devicesFirewall: Windows Firewall blocks other devices (devicesFirewallAllow clears it); options.devicesTailscale: this PC is on Tailscale;
    // options.devicesNearby: 'none' finds nobody; options.devicesScanning: the scan is still running. The Devices page asks for devicesWatch while it is open.
    firewallBlocked: !!options.devicesFirewall,
    watching: false,
    profiles: [{id: 'prof-1', name: 'My library', libraryId: 'lib-1', current: true}],
    paired:
      (options.devices || 'paired') === 'paired'
        ? [
            {deviceId: 'dev-phone', fingerprint: '9f3a 41c2 7b05 d8e6 1a90', name: 'Pixel 9', platform: 'android', pairedAt: now - 3 * day, lastSeen: now - 5 * min, online: false, unreachableSince: now - 40 * min, address: 'pixel-9.tailnet.ts.net', route: null, jobs: {allowed: false, spaces: []}, shareable: [], libraries: [{libraryId: 'lib-1', name: 'My library', actions: ['metadata.read', 'metadata.write']}]},
            {deviceId: 'dev-laptop', fingerprint: '2c71 e9a4 03bd 5f18 c6a2', name: 'Laptop', platform: 'windows', pairedAt: now - 9 * day, lastSeen: options.awayDevice === 'offline' ? now - 3 * hour : now - 20_000, online: options.awayDevice !== 'offline', playFrom: options.awayDevice !== 'none', unreachableSince: options.awayDevice === 'offline' ? now - 2 * hour : null, address: '192.168.1.40', route: options.awayDevice === 'tailscale' ? 'tailscale' : 'lan', jobs: {allowed: true, spaces: [{id: 'music', name: 'Music'}, {id: 'videos', name: 'Videos'}]}, shareable: [], libraries: [{libraryId: 'lib-1', name: 'My library', actions: ['metadata.read', 'metadata.write', 'media.read']}]},
          ]
        : [],
  }
  // options.devicesNearby: 'many' finds eight devices (the list view), 'six' finds six
  const devicesMany = Array.from({length: options.devicesNearby === 'six' ? 6 : 8}, (_, i) => ({hint: (0x10 + i).toString(16).padStart(2, '0') + 'ab12cd34ef567890'.slice(0, 14), name: ['Kitchen laptop', 'Living room PC', 'Galaxy Tab S9', 'Office desktop', 'Pixel 8', 'Guest laptop', 'Studio PC', 'Garage tablet'][i], host: `192.168.1.${60 + i}`, port: 47215, paired: false, route: i % 3 === 2 ? 'tailscale' : 'lan', platform: i % 2 ? 'android' : 'windows'}))
  const devicesDiscovered = options.devicesNearby === 'none' ? [] : options.devicesNearby === 'many' || options.devicesNearby === 'six' ? devicesMany : [{hint: 'a1b2c3d4e5f60718', name: 'Galaxy S24', host: '192.168.1.23', port: 47215, paired: false, route: 'lan', platform: 'android'}, ...(options.devicesTailscale ? [{hint: 'd4d3d2d1d0cfcecd', name: 'Studio PC', host: '100.101.20.5', port: 47215, paired: false, route: 'tailscale', platform: 'windows'}] : [])]
  const devicesView = () => {
    if (options.devices === 'unavailable') return {available: false, unavailable: 'Secure storage is not available on this computer, so devices cannot be paired.', enabled: false, libraries: [], paired: [], discovered: [], pairingUntil: null, pairing: null, notes: [], offers: [], join: null, remoteJobs: [], firewall: {blocked: false, category: 'unknown'}, tailscale: {state: 'off'}, lan: false, scanning: false}
    return clone({
      available: true,
      enabled: devicesData.enabled,
      thisDevice: {name: devicesData.name, fingerprint: '5d20 8ab4 f1e3 7c69 0b2d', platform: 'windows'},
      ...(devicesData.enabled && options.devices !== 'portBusy' ? {port: 47215} : {}),
      ...(devicesData.enabled && options.devices === 'portBusy' ? {portError: 'Another program is using port 47215. Close it, then turn devices off and on again. Keepfin keeps this port fixed so other devices can find you.'} : {}),
      libraries: [{libraryId: 'lib-1', name: 'My library', signed: devicesData.paired.length > 0, admin: true}],
      paired: devicesData.paired,
      discovered: devicesData.watching || devicesData.pairingUntil ? devicesDiscovered.filter((d) => !devicesData.paired.some((p) => p.deviceId.endsWith(d.hint))) : [],
      pairingUntil: devicesData.pairingUntil,
      pairing: devicesData.pairing,
      ...(devicesData.qr ? {qr: devicesData.qr} : {}),
      notes: [],
      offers: devicesData.offers,
      join: devicesData.join,
      remoteJobs: devicesData.remoteJobs,
      profiles: devicesData.profiles,
      firewall: {blocked: devicesData.firewallBlocked, category: devicesData.firewallBlocked ? 'Public' : 'Private'},
      tailscale: options.devicesTailscale ? {state: 'on', name: 'desk-pc.tail1234.ts.net', address: '100.101.102.103'} : {state: 'off'},
      lan: options.devicesLan !== false,
      scanning: !!(devicesData.watching && options.devicesScanning),
    })
  }
  const devicesPush = () => push({type: 'devices', state: devicesView()})
  // the paired devices as the Add a copy wizard sees them (options.copiesDevices: 'allowed' lets every paired device keep a copy, 'blocked' none)
  function copiesDeviceChoices() {
    return devicesData.paired.map((p) => {
      const allowed = options.copiesDevices === 'allowed' && p.online && p.libraries.length > 0
      if (p.libraries.length === 0 && p.online) return {deviceId: p.deviceId, name: p.name, online: true, allowed: false, why: p.name + ' does not share this library with you.'}
      return {deviceId: p.deviceId, name: p.name, online: p.online, allowed, ...(allowed ? {} : {why: p.online ?'On ' + p.name + ', turn on "Keep a copy on this device" for this device in Settings → Devices.' : 'Not connected right now.'})}
    })
  }
  const qrSvg = 'data:image/svg+xml;base64,' + btoa('<svg xmlns="http://www.w3.org/2000/svg" width="29" height="29" viewBox="0 0 29 29"><rect width="29" height="29" fill="#fff"/><path d="M2 2h7v7H2zM20 2h7v7h-7zM2 20h7v7H2zM12 4h3v3h-3zM12 12h5v5h-5zM20 14h7v3h-7zM12 22h8v3h-8z" fill="#000"/></svg>')
  const newPairing = (role, method, name) => ({id: 'pair-' + Math.random().toString(36).slice(2, 8), method, role, stage: 'connecting', peerName: name, peerFingerprint: '', expiresAt: Date.now() + 120_000})
  // options.devicesLibraries: 'different' = a device paired from now on holds its own library (nothing in common with this one)
  function devicesPaired(name, platform) {
    devicesData.paired.push({deviceId: 'dev-' + name.toLowerCase().replace(/\W+/g, '-'), fingerprint: 'c0de 1234 5678 9abc def0', name, platform, pairedAt: Date.now(), lastSeen: Date.now(), online: true, unreachableSince: null, address: '192.168.1.23', route: 'lan', jobs: {allowed: false, spaces: []}, shareable: [], libraries: options.devicesLibraries === 'different' ? [] : [{libraryId: 'lib-1', name: 'My library', actions: ['metadata.read', 'metadata.write']}]})
  }
  // the asking side only shows its code and waits; the answering device's person confirms (or not) on the other device
  fake.devicesPeerConfirms = () => {
    const p = devicesData.pairing
    if (!p || p.role !== 'asking' || p.stage !== 'waiting') return
    p.stage = 'done'
    devicesPaired(p.peerName, 'android')
    devicesPush()
  }
  fake.devicesPeerConfirmsToo = () => {
    const p = devicesData.pairing
    if (!p || p.role !== 'answering' || !p.confirmed) return
    p.stage = 'done'
    delete p.confirmed
    devicesPaired(p.peerName, 'android')
    devicesPush()
  }
  fake.devicesPeerDeclines = () => {
    const p = devicesData.pairing
    if (!p || p.role !== 'asking' || p.stage !== 'waiting') return
    p.stage = 'failed'
    p.error = `${p.peerName} did not confirm, so nothing was paired.`
    devicesPush()
  }
  fake.devicesIncoming = (unrequested = false) => {
    devicesData.pairing = {...newPairing('answering', 'sas', 'Galaxy S24'), stage: 'comparing', peerFingerprint: '77aa 12bc 90de 34f5 6a7b', sas: '482193', ...(unrequested ? {unrequested: true} : {})}
    devicesPush()
  }
  // a phone scanned this PC's code: it connected at once (answering side, done, autoApproved)
  fake.devicesScanned = () => {
    devicesPaired('Pixel 9 Pro', 'android')
    devicesData.paired[devicesData.paired.length - 1].fingerprint = '5e5e 1111 2222 3333 4444'
    devicesData.pairing = {...newPairing('answering', 'qr', 'Pixel 9 Pro'), stage: 'done', peerFingerprint: '5e5e 1111 2222 3333 4444', autoApproved: true}
    devicesPush()
  }
  fake.remoteJobRequests = []
  // the other device opened Add a device: a pairing that was waiting for it goes on to the numbers
  fake.devicesPeerOpened = () => {
    const p = devicesData.pairing
    if (!p || p.stage !== 'waitingForPeer') return
    p.peerName = 'Galaxy S24'
    p.peerFingerprint = '77aa 12bc 90de 34f5 6a7b'
    p.stage = 'comparing'
    p.sas = '482193'
    devicesPush()
  }
  fake.devicesPeerGone = () => {
    for (const device of devicesData.paired) device.online = false
    devicesPush()
  }
  async function devicesHandle(req) {
    if (!req.op.startsWith('devices')) return NOT_HANDLED
    const args = req.devices || {}
    const d = devicesData
    switch (req.op) {
      case 'devicesState':
        await wait(25)
        return devicesView()
      case 'devicesEnable':
        await wait(60)
        d.enabled = true
        return devicesView()
      case 'devicesDisable':
        d.enabled = false
        d.pairingUntil = null
        return devicesView()
      case 'devicesPairingOpen':
        d.pairingUntil = Date.now() + 120_000
        return devicesView()
      case 'devicesPairingClose':
        d.pairingUntil = null
        d.qr = undefined
        return devicesView()
      case 'devicesQr':
        d.pairingUntil = Date.now() + 120_000
        d.qr = {link: 'keepfin://pair?h=192.168.1.20&p=47215&k=AAAA&t=BBBB', image: qrSvg, code: 'K7QPM-4X2TA', expiresAt: Date.now() + 120_000}
        return devicesView()
      case 'devicesConnect': {
        const address = String(args.address || '').trim()
        if (!address || /\s/.test(address) || address === 'not an address') throw new Error('That is not an address. Use a name or number, like 192.168.1.20 or my-pc.tailnet.ts.net, with :port if it is not 47215.')
        const qr = address.startsWith('keepfin://')
        d.pairing = newPairing('asking', qr ? 'qr' : 'sas', address.split(':')[0].slice(0, 30))
        setTimeout(() => {
          if (!d.pairing) return
          if (address.includes('10.0.0.7')) {
            d.pairing.stage = 'waitingForPeer' // the other device has not opened Add a device yet
          } else if (address.includes('10.0.0.9')) {
            d.pairing.stage = 'failed'
            d.pairing.error = 'Nothing is listening at that address. Is Keepfin open on the other device?'
          } else if (qr) {
            d.pairing.peerName = 'Pixel 9'
            d.pairing.stage = 'waiting'
          } else {
            d.pairing.peerName = 'Galaxy S24'
            d.pairing.peerFingerprint = '77aa 12bc 90de 34f5 6a7b'
            d.pairing.stage = 'comparing'
            d.pairing.sas = '482193'
          }
          devicesPush()
          if (qr) setTimeout(() => {
            if (!d.pairing) return
            d.pairing.stage = 'done'
            devicesPaired('Pixel 9', 'android')
            devicesPush()
          }, 150 * options.latency)
        }, 120 * options.latency)
        return devicesView()
      }
      case 'devicesDecide': {
        const p = d.pairing
        if (!p || p.id !== args.pairingId) throw new Error('This pairing is no longer open.')
        if (!args.match && p.role === 'asking' && p.stage === 'waiting') {
          d.pairing = null // Cancel after the first tap: declined to the other device, the dialog closes
          return devicesView()
        }
        if (!args.match) {
          p.stage = 'failed'
          p.error = p.role === 'asking' ? 'You said the numbers do not match, so nothing was paired.' : 'You said the numbers do not match, so nothing was paired.'
          return devicesView()
        }
        if (p.role === 'asking') {
          // the first tap: the code stays, this side waits for the other device's person (fake.devicesPeerConfirms / Declines)
          p.stage = 'waiting'
          return devicesView()
        }
        if (fake.devicesHold) {
          // the asked device tapped first: it waits for the asking device's person (fake.devicesPeerConfirmsToo)
          p.confirmed = true
          return devicesView()
        }
        p.stage = 'waiting'
        setTimeout(() => {
          if (!d.pairing) return
          d.pairing.stage = 'done'
          devicesPaired(d.pairing.peerName, 'android')
          devicesPush()
        }, 150 * options.latency)
        return devicesView()
      }
      case 'devicesFirewallAllow':
        await wait(40)
        d.firewallBlocked = false
        return devicesView()
      case 'devicesWatch':
        d.watching = true
        return devicesView()
      case 'devicesWatchStop':
        d.watching = false
        return devicesView()
      case 'devicesDismiss':
        d.pairing = null
        return devicesView()
      case 'devicesRename':
        d.name = String(args.name || d.name)
        return devicesView()
      case 'devicesRemove':
        d.paired = d.paired.filter((p) => p.deviceId !== args.deviceId)
        return devicesView()
      case 'devicesSyncNow':
        if (!d.paired.some((p) => p.deviceId === args.deviceId)) throw new Error('That device is not paired.')
        return devicesView()
      case 'devicesAccess': {
        const device = d.paired.find((p) => p.deviceId === args.deviceId)
        const lib = device && device.libraries.find((l) => l.libraryId === args.libraryId)
        if (!lib) throw new Error('That device is not paired.')
        lib.actions = args.actions
        return devicesView()
      }
      case 'devicesShare': {
        const device = d.paired.find((p) => p.deviceId === args.deviceId)
        if (!device) throw new Error('That device is not paired.')
        device.shareable = device.shareable.filter((l) => l.libraryId !== args.libraryId)
        device.libraries.push({libraryId: args.libraryId, name: 'Shared library', actions: ['metadata.read', 'metadata.write']})
        return devicesView()
      }
      case 'devicesJoin': {
        if (!args.path) throw new Error('Choose a folder for the library.')
        const offer = d.offers.find((o) => o.deviceId === args.deviceId && o.libraryId === args.libraryId)
        if (!offer) throw new Error('That device is not offering this library right now. Make sure it is turned on and connected.')
        d.join = {deviceId: offer.deviceId, deviceName: offer.deviceName, libraryId: offer.libraryId, name: offer.name, folder: args.path, stage: 'folder', segments: 0}
        const step = (stage, segments, after) => setTimeout(() => {
          if (!d.join || d.join.stage === 'failed') return
          d.join.stage = stage
          d.join.segments = segments
          if (stage === 'done') {
            d.offers = d.offers.filter((o) => o !== offer)
            d.profiles.push({id: 'prof-joined', name: offer.name, libraryId: offer.libraryId, current: false})
          }
          devicesPush()
          if (after) after()
        }, 80 * options.latency)
        if (window.__joinFails) {
          step('asking', 0, () => step('pulling', 14, () => setTimeout(() => {
            d.join.stage = 'failed'
            d.join.error = 'The other device could not be reached.'
            d.join.resumable = true
            devicesPush()
          }, 80 * options.latency)))
        } else step('asking', 0, () => step('pulling', 14, () => step('done', 31)))
        return devicesView()
      }
      case 'devicesJoinCancel':
        if (d.join && d.join.stage !== 'done') {
          d.join.stage = 'failed'
          d.join.error = 'The join was stopped.'
          d.join.resumable = true
        }
        return devicesView()
      case 'devicesOpenLibrary': {
        const profile = d.profiles.find((p) => p.id === args.profileId)
        if (!profile) throw new Error('Unknown library.')
        fake.openedLibrary = profile.id
        return devicesView()
      }
      case 'devicesJob': {
        const device = d.paired.find((p) => p.deviceId === args.deviceId)
        if (!device || !device.jobs.allowed) throw new Error('That device does not allow downloads from this device.')
        const row = {requestId: 'req-' + (d.remoteJobs.length + 1), deviceId: device.deviceId, deviceName: device.name, url: args.url, state: 'sending', percent: 0, createdAt: Date.now()}
        d.remoteJobs.unshift(row)
        fake.remoteJobRequests.push({deviceId: args.deviceId, url: args.url, format: args.format, space: args.space})
        const go = (patch, after) => setTimeout(() => {
          Object.assign(row, patch)
          devicesPush()
          if (after) after()
        }, 100 * options.latency)
        go({state: 'queued', title: undefined}, () => go({state: 'downloading', percent: 35, title: 'Clip from the link', label: '1080p · MP4', totalBytes: 84 * MB}, () => setTimeout(() => go({state: 'completed', percent: 100}), (window.__remoteJobMs || 1500))))
        return devicesView()
      }
      case 'devicesJobDismiss':
        d.remoteJobs = d.remoteJobs.filter((r) => r.requestId !== args.requestId)
        setTimeout(devicesPush, 20) // the service pushes its state after every change
        return devicesView()
      default:
        return NOT_HANDLED
    }
  }
  // ---------------------------------------------------------------- combining two libraries (fakes for the UI; the real engine is gui/desktop/merge)
  const combineData = {phase: 'idle', timers: [], mergeId: 'merge-fixture'}
  fake.combine = combineData
  const combinePreviewFixture = (deviceId, deviceName) => ({
    deviceId,
    deviceName,
    libraryName: deviceName + "'s library",
    items: 210,
    // how big the other library is (options.combineBigger: 'mine' makes this PC's the bigger one)
    otherItems: options.combineBigger === 'mine' ? 40 : 1326,
    added: 180,
    linked: 28,
    keptDuplicates: 2,
    missingFiles: 1,
    differing: 4,
    playlists: 3,
    spaces: 2,
    review: [
      {kind: 'duplicateInS', s: 'item-1', t: 'item-t-1', title: 'Harbour Lights'},
      {kind: 'missingFile', s: 'item-2', t: 'item-t-2', title: 'Night Ferry'},
      {kind: 'differingField', s: 'item-3', t: 'item-t-3', field: 'title', title: 'Glass Harbour (live)'},
      {kind: 'sameNamePlaylist', s: 'list-1', t: 'list-t-1', title: 'Road trip'},
    ],
    reviewTotal: 8,
    separateDays: 30,
  })
  // options.combine seeds a combine that is already there when the page opens (running, recovering after a restart, paused, stopped, done) for Laptop
  const combineSeeds = {
    running: {phase: 'applying', progress: {done: 1230, total: 1450, label: 'Writing the changes'}},
    paused: {phase: 'paused', progress: {done: 600, total: 1450}, paused: 'Laptop is not reachable right now. It continues by itself when it is back.'},
    failed: {phase: 'failed', error: 'The folder could not be written to. Check that the drive is connected, then try again.'},
    ready: {phase: 'ready', preview: null},
    done: {phase: 'done', combined: {since: new Date(Date.now() - 2 * 86400000).toISOString(), until: new Date(Date.now() + 28 * 86400000).toISOString(), libraryName: "Laptop's library", canSeparate: true}},
    kept: {phase: 'done', combined: {since: new Date(Date.now() - 2 * 86400000).toISOString(), until: new Date(Date.now() + 28 * 86400000).toISOString(), libraryName: "Laptop's library", canSeparate: false}},
  }
  if (combineSeeds[options.combine]) {
    Object.assign(combineData, {deviceId: 'dev-laptop', deviceName: 'Laptop', libraryName: "Laptop's library"}, combineSeeds[options.combine])
    if (combineData.preview === null) combineData.preview = combinePreviewFixture('dev-laptop', 'Laptop')
    // with options.devicesLibraries 'different' the paired Laptop holds its own library too (so the setup starts with the Combine step)
    if (options.devicesLibraries === 'different') devicesData.paired.forEach((p) => { if (p.deviceId === 'dev-laptop') p.libraries = [] })
  }
  function combineView() {
    const out = {available: true, phase: combineData.phase}
    for (const key of ['deviceId', 'deviceName', 'libraryName', 'progress', 'preview', 'paused', 'error', 'combined', 'separate']) if (combineData[key] !== undefined) out[key] = combineData[key]
    if (combineData.phase !== 'idle') out.mergeId = combineData.mergeId
    return out
  }
  const combinePush = () => push({type: 'combine', state: combineView()})
  // done: one library now, so the paired device shares it (the setup's copies can go on) and Separate again is on offer for 30 days
  function combineFinish() {
    const c = combineData
    c.phase = 'done'
    c.progress = undefined
    c.paused = undefined
    c.combined = {since: new Date().toISOString(), until: new Date(Date.now() + 30 * 86400000).toISOString(), libraryName: c.libraryName, canSeparate: true}
    const paired = devicesData.paired.find((p) => p.deviceId === c.deviceId)
    if (paired && paired.libraries.length === 0) {
      paired.libraries = [{libraryId: 'lib-1', name: 'My library', actions: ['metadata.read', 'metadata.write']}]
      devicesPush()
    }
  }
  const combineAfter = (ms, work) => combineData.timers.push(setTimeout(() => {
    work()
    combinePush()
  }, ms * options.latency))
  async function combineHandle(req) {
    if (!req.op.startsWith('combine')) return NOT_HANDLED
    const args = req.combine || {}
    const c = combineData
    const device = () => {
      const found = devicesData.paired.find((p) => p.deviceId === args.deviceId)
      if (!found) throw new Error('That device is not paired.')
      return found
    }
    switch (req.op) {
      case 'combineState':
        await wait(20)
        return combineView()
      case 'combinePreview': {
        const found = device()
        c.deviceId = found.deviceId
        c.deviceName = found.name
        c.libraryName = found.name + "'s library"
        c.phase = 'joining'
        c.progress = {done: 0, total: 0, label: 'Taking in the other library'}
        combineAfter(120, () => { c.phase = 'reviewing'; c.progress = {done: 50, total: 210, label: 'Describing this library'} })
        combineAfter(240, () => { c.phase = 'ready'; c.progress = undefined; c.preview = combinePreviewFixture(found.deviceId, found.name) })
        return combineView()
      }
      case 'combineStart': {
        if (c.phase === 'idle') throw new Error('Look at the review first.')
        c.keep = args.keep === 'mine' ? 'mine' : 'theirs'
        c.phase = 'planning'
        c.progress = {done: 0, total: 210, label: 'Describing this library'}
        combineAfter(100, () => { c.phase = 'applying'; c.progress = {done: 1, total: 2, label: 'Writing the changes'} })
        combineAfter(220, () => { c.phase = 'verifying'; c.progress = {done: 0, total: 0, label: 'Making sure nothing is missing'} })
        combineAfter(320, () => { c.phase = 'switching'; c.progress = undefined })
        combineAfter(420, combineFinish)
        return combineView()
      }
      case 'combinePause':
        c.timers.forEach(clearTimeout)
        c.timers = []
        if (c.phase !== 'idle' && c.phase !== 'done') {
          c.phase = 'paused'
          c.paused = 'You paused it.'
        }
        return combineView()
      case 'combineResume':
        c.paused = undefined
        if (c.phase === 'paused') combineAfter(100, combineFinish)
        return combineView()
      case 'combineCancel':
        c.timers.forEach(clearTimeout)
        Object.assign(c, {phase: 'idle', timers: [], progress: undefined, preview: undefined, paused: undefined, error: undefined, combined: undefined, separate: undefined})
        return combineView()
      case 'combineSeparatePreview':
        c.separate = {brought: 182, heldElsewhere: [{id: 'item-t-9', title: 'Song held on the other device'}], heldElsewhereTotal: 1, addedSince: 3, deviceName: c.deviceName || 'Desk PC', unreachable: !!options.combineUnreachable}
        return combineView()
      case 'combineSeparate':
        if (c.separate && c.separate.unreachable && !args.anyway) throw new Error((c.deviceName || 'The other device') + ' is not reachable. Separate anyway: it keeps everything this device brought.')
        c.phase = 'separating'
        combineAfter(200, () => { c.phase = 'idle'; c.combined = undefined; c.separate = undefined; c.preview = undefined })
        return combineView()
      case 'combineKeep':
        if (c.combined) c.combined.canSeparate = false
        return combineView()
      default:
        return NOT_HANDLED
    }
  }
  // ---------------------------------------------------------------- Where your files are (whereState / whereItem; SYNTHETIC data)
  // options.where picks the scenario; without it the page shows nothing (the state is not available):
  //   'one'       this computer is the only place that holds the library
  //   'several'   this computer, Pixel 9 (away), Laptop and a backup drive; 12 videos are only on this PC, 3 songs only on Pixel 9
  //   'copying'   'several' plus a copy on Work laptop that is copying (40 of 210)
  //   'unplugged' 'several' plus a USB stick that is not plugged in (last copied 9 days ago)
  //   'all'       every state at once: the longest names and words, for layout checks
  //   'safe'      several places and no file that only one of them holds
  // window.__fake.whereChange(patch) merges a patch into one place and pushes `where` (a copy that finishes, a device that comes back).
  const whereHolds = (patch) => ({all: false, spaces: [], playlists: [], favourites: false, ...patch})
  const wherePatches = {}
  const whereView = () => {
    const mode = options.where
    if (!mode) return {available: false, places: [], singles: []}
    const t = Date.now()
    const here = {id: 'this', kind: 'this', name: devicesData.name, thisDevice: true, holds: whereHolds({all: true, files: {held: 1548, of: 1548}}), bytes: 29.2 * GB, items: 1548, status: {state: 'upToDate'}}
    if (mode === 'one') return {available: true, places: [here], singles: []}
    const phone = {id: 'dev-phone', kind: 'phone', name: 'Pixel 9', holds: whereHolds({files: {held: 410, of: 1548}}), bytes: 6.2 * GB, items: 410, status: {state: 'offline', at: t - 5 * 60_000}, link: {to: 'device', id: 'dev-phone'}}
    const laptop = {id: 'dev-laptop', kind: 'pc', name: 'Laptop', holds: whereHolds({files: {held: 1548, of: 1548}, all: true}), bytes: 29.2 * GB, items: 1548, status: {state: 'upToDate', at: t - 2 * 60_000}, link: {to: 'device', id: 'dev-laptop'}}
    const backup = {id: 'p-backup', kind: 'drive', name: 'Backup drive (E:)', holds: whereHolds({all: true}), bytes: 29.2 * GB, items: 1548, status: {state: 'upToDate', at: t - 3 * 3600_000}, link: {to: 'copy', id: 'p-backup'}}
    const work = {id: 'p-laptop', kind: 'pc', name: 'Work laptop', holds: whereHolds({spaces: ['Music'], playlists: ['Road trip', 'Gym', 'Focus'], favourites: true}), bytes: 0, items: 40, status: {state: 'copying', done: 40, total: 210}, link: {to: 'copy', id: 'p-laptop'}}
    const stick = {id: 'p-stick', kind: 'drive', name: 'Car USB stick (F:)', holds: whereHolds({playlists: ['Gym']}), bytes: 1.4 * GB, items: 64, status: {state: 'offline', at: t - 9 * 86_400_000, unplugged: true}, link: {to: 'copy', id: 'p-stick'}}
    const cloud = {id: 'p-cloud-drive', kind: 'cloud', name: 'Google Drive', holds: whereHolds({all: true}), bytes: 29.2 * GB, items: 1548, status: {state: 'waiting', reason: 'Waiting for Wi-Fi'}, link: {to: 'copy', id: 'p-cloud-drive'}}
    const decide = {id: 'p-cloud-ms', kind: 'cloud', name: 'OneDrive', holds: whereHolds({spaces: ['Music', 'Videos']}), bytes: 8.1 * GB, items: 902, status: {state: 'needsDecision'}, link: {to: 'copy', id: 'p-cloud-ms'}}
    const singles = [
      {kind: 'songs', count: 3, placeId: 'dev-phone', placeName: 'Pixel 9'},
      {kind: 'videos', count: 12, placeId: 'this', placeName: devicesData.name, spaceId: 'videos'},
    ]
    // this computer, the devices by name, then the copies by name (the order main gives)
    let places = [here, laptop, phone, backup]
    if (mode === 'copying') places = [here, laptop, phone, backup, work]
    if (mode === 'unplugged') places = [here, laptop, phone, backup, stick]
    if (mode === 'all') places = [here, laptop, phone, backup, stick, cloud, decide, work]
    if (mode === 'safe') places = [here, phone, backup]
    places = places.map((p) => ({...p, ...(wherePatches[p.id] || {})}))
    return clone({available: true, places, singles: mode === 'safe' ? [] : mode === 'all' ? [...singles, {kind: 'other', count: 1, placeId: 'this', placeName: devicesData.name}] : singles})
  }
  fake.whereChange = (id, patch) => {
    wherePatches[id] = {...(wherePatches[id] || {}), ...patch}
    push({type: 'where', state: whereView()})
  }
  async function whereHandle(req) {
    if (req.op !== 'whereState' && req.op !== 'whereItem') return NOT_HANDLED
    if (req.op === 'whereState') {
      await wait(25)
      return whereView()
    }
    await wait(20)
    if (!options.where) return {places: []}
    const places = [{name: devicesData.name, thisDevice: true, offline: false}]
    if (options.where !== 'one') places.push({name: 'Pixel 9', offline: true}, {name: 'Backup drive (E:)', offline: false})
    return {places}
  }
  // ---------------------------------------------------------------- request handler
  async function handle(req) {
    const stored = await storageHandle(req)
    if (stored !== NOT_HANDLED) return stored
    const wheres = await whereHandle(req)
    if (wheres !== NOT_HANDLED) return wheres
    const copied = await copiesHandle(req)
    if (copied !== NOT_HANDLED) return copied
    const deviced = await devicesHandle(req)
    if (deviced !== NOT_HANDLED) return deviced
    const combined = await combineHandle(req)
    if (combined !== NOT_HANDLED) return combined
    const handled = await libraryHandle(req)
    if (handled !== NOT_HANDLED) return handled
    // History removal sent with an undoId: the finished entries come back as they were, each where it stood (main keeps the records)
    if (req.undoId && ['remove', 'removeGroup', 'removeFailed', 'clear'].includes(req.op)) {
      const before = jobs.slice()
      const out = await handle({...req, undoId: undefined})
      const kept = new Set(jobs.map((item) => item.id))
      const removed = before.filter((item) => !kept.has(item.id))
      if (removed.length && removed.every((item) => ['completed', 'failed', 'cancelled'].includes(item.status)))
        undos.set(req.undoId, async () => {
          const have = new Set(jobs.map((item) => item.id))
          const back = removed.filter((item) => !have.has(item.id))
          const order = restoreOrder(before.map((item) => item.id), jobs.map((item) => item.id), new Set(back.map((item) => item.id)))
          const byId = new Map([...jobs, ...back].map((item) => [item.id, item]))
          jobs = order.map((id) => byId.get(id))
          pushSnapshot()
          return done(back.length)
        })
      return out
    }
    switch (req.op) {
      case 'state':
        await wait(30)
        return snapshot()
      case 'thumbnail': {
        const key = req.id ? `job:${req.id}` : `url:${req.url}`
        fake.thumbCalls[key] = (fake.thumbCalls[key] || 0) + 1
        await wait(40)
        const source = req.id ? jobs.find((item) => item.id === req.id) : undefined
        if (req.id && !source) throw new Error('Unknown job')
        const square = source ? source.kind === 'audio' : /scdn|googleusercontent|img\.fixture|spotify/.test(req.url || '')
        return artwork(source ? source.url : req.url, square)
      }
      case 'signInState':
        return signInState()
      case 'signInOpen': {
        const closed = options.signInWindow === 'closed'
        await wait(closed ? 200 : 600)
        if (!closed) signedIn.add(req.site)
        const name = signInSites.find(([key]) => key === req.site)[1]
        return {site: req.site, signedIn: !closed, message: closed ? `Keepfin is not signed in to ${name}. The window was closed before the sign-in finished.` : undefined, state: signInState()}
      }
      case 'signInRemove':
        signedIn.delete(req.site)
        return signInState()
      case 'inspect': {
        await wait(/private|not-media/.test(req.url) ? 500 : 900)
        // a post Instagram shows only to signed-in people: readable once Keepfin is signed in to Instagram
        if (/instagram\.com\/p\/Locked01/.test(req.url)) {
          if (!signedIn.has('instagram')) throw new Error('Sign-in required: this post is only shown to signed-in people.')
          return {type: 'media', media: {...clone(media['https://www.instagram.com/p/C9harbour/']), url: req.url}}
        }
        if (errors[req.url]) throw new Error(errors[req.url])
        if (collections[req.url]) return {type: 'collection', url: req.url, title: collections[req.url].title, uploader: collections[req.url].uploader}
        if (/plain-file\.mp4$/.test(req.url)) return {type: 'media', media: {url: req.url, title: 'plain-file', provider: 'cdn.example.net', extractor: 'Generic', engine: 'yt-dlp', video: [], audio: [], subtitles: [], chapters: 0}}
        const found = media[req.url]
        if (!found) {
          // Unknown links resolve to a generic single video so batch tests have something real-looking.
          const host = new URL(req.url).hostname.replace(/^www\./, '')
          return {type: 'media', media: {...clone(media['https://vimeo.com/824512']), url: req.url, title: `Clip from ${host}`, provider: host, thumbnail: `https://img.fixture/${hash(req.url)}.jpg`}}
        }
        return {type: 'media', media: clone(found)}
      }
      case 'cancelInspection':
        return null
      case 'enqueue': {
        await wait(120)
        if (options.captureSong === 'video' && req.format.type === 'video') throw new Error('This song already has its music video.')
        const m = Object.values(media).find((item) => item && item.url === req.url)
        if (!m) throw new Error('This link was not inspected in this session. Inspect it again.')
        const f = req.format
        const label = f.type === 'audio' ? `${f.target.toUpperCase()}${f.quality && f.quality !== 'best' ? ` · ${f.quality} kbps` : ''}` : f.type === 'gallery' ? `Gallery · ${m.gallery?.count} items` : `${f.maxHeight}p${f.fps > 30 ? f.fps : ''} · ${f.container.toUpperCase()}`
                const created = {id: `job-new-${++seq}`, url: m.url, title: m.title, uploader: m.uploader, provider: m.provider, duration: m.duration, thumbnail: m.thumbnail, label, kind: f.type, format: f, options: req.options, status: 'queued', percent: 0, createdAt: Date.now(), match: m.match, space: req.space}
        const target = libSettings.spaces.find((space) => space.id === req.space)
        if (libSettings.enabled && target && target.skipExisting && /tidal88/.test(m.url)) {
          Object.assign(created, {status: 'completed', percent: 100, completedAt: Date.now(), fileSize: 0, existing: {itemId: 'a-tidal', path: `${FOLDERS.music}\Low Tide Hours\Tidal (Night Version).mp3`, space: target.id, by: 'source'}})
          jobs = [...jobs, created]
          return snapshot()
        }
        // a ticked music video is its own job, linked to the song's job
        const videoJob = req.videoId ? {id: `job-new-${++seq}`, url: /music.youtube.com/.test(m.url) ? `https://music.youtube.com/watch?v=${req.videoId}` : m.url, title: m.title + ' (Official Video)', uploader: m.uploader, provider: 'YouTube Music', label: '1080p · MP4', kind: 'video', format: {type: 'video', maxHeight: 1080, codec: 'h264', container: 'mp4'}, options: req.options, status: 'queued', percent: 0, createdAt: Date.now(), space: req.space, force: true, attachVideo: {jobId: created.id}} : undefined
        jobs = [...jobs, created, ...(videoJob ? [videoJob] : [])]
        setTimeout(() => {
          patch(created.id, {status: 'downloading', percent: 0, totalBytes: 220e6, downloadedBytes: 0, speed: 9 * MB, startedAt: Date.now()})
          pushSnapshot()
        }, 900)
        return snapshot()
      }
      case 'startDiscovery': {
        const source = collections[req.url]
        if (!source) throw new Error('This link is not a collection.')
        const state = {id: req.inspectionId, source: req.url, title: source.title, uploader: source.uploader, entries: [], state: 'discovering', limit: source.page || 200, truncated: false}
        discoveries.set(req.inspectionId, state)
        runDiscovery(state, 0, state.limit)
        return view(state)
      }
      case 'discoveryState': {
        const state = discoveries.get(req.inspectionId)
        if (!state) throw new Error('Discovery expired.')
        return view(state)
      }
      case 'discoveryMore': {
        const state = discoveries.get(req.inspectionId)
        const source = collections[state.source]
        state.limit += source.page || 200
        runDiscovery(state, state.entries.length, state.limit)
        return view(state)
      }
      case 'cancelDiscovery': {
        const state = discoveries.get(req.inspectionId)
        if (!state) return null
        clearTimeout(state.timer)
        if (state.state === 'discovering') state.state = 'cancelled'
        return view(state)
      }
      case 'enqueueDiscovered': {
        await wait(150)
        const state = discoveries.get(req.inspectionId)
        let group
        if (req.grouped !== false) {
          state.group ??= {id: `grp-${req.inspectionId}`, title: state.title, url: state.source, uploader: state.uploader, kind: /album|browse/.test(state.source) ? 'album' : 'playlist'}
          group = state.group
        }
        for (const url of req.urls) {
          const entry = state.entries.find((item) => item.url === url)
          jobs = [...jobs, {id: `job-new-${++seq}`, group, url, title: entry.title, uploader: entry.artist || entry.uploader, provider: collections[state.source].ytmusic ? 'YouTube Music' : collections[state.source].music ? 'Spotify' : 'YouTube', duration: entry.duration, thumbnail: entry.thumbnail, label: req.preset, kind: req.preset.startsWith('audio') ? 'audio' : 'video', format: {type: 'video', codec: 'auto', container: 'mp4'}, options: req.options, status: 'queued', percent: 0, createdAt: Date.now(), space: req.space}]
        }
        return snapshot()
      }
      case 'cancel':
      case 'pause':
      case 'resume':
      case 'retry':
      case 'remove':
      case 'moveUp':
      case 'deleteFile': {
        await wait(60)
        const target = jobs.find((item) => item.id === req.id)
        if (!target) throw new Error('That download no longer exists.')
        if (req.op === 'cancel') patch(req.id, {status: 'cancelled', speed: undefined, eta: undefined, completedAt: Date.now()})
        if (req.op === 'pause') patch(req.id, {status: 'paused', speed: undefined, eta: undefined})
        if (req.op === 'resume') patch(req.id, {status: 'downloading', speed: 8 * MB})
        if (req.op === 'retry') patch(req.id, {status: 'queued', error: undefined, percent: 0})
        if (req.op === 'remove' && target.status === 'queued' && target.retry) patch(req.id, {status: 'cancelled', retry: undefined})
        if (req.op === 'remove') jobs = jobs.filter((item) => item.id !== req.id)
        if (req.op === 'deleteFile') jobs = jobs.filter((item) => item.id !== req.id)
        if (req.op === 'moveUp') {
          const index = jobs.indexOf(target)
          const before = jobs.slice(0, index).reverse().find((item) => item.status === 'queued')
          if (before) {
            const j = jobs.indexOf(before)
            jobs = [...jobs]
            jobs[j] = target
            jobs[index] = before
          }
        }
        return snapshot()
      }
      case 'pauseGroup':
      case 'resumeGroup':
      case 'cancelGroup':
      case 'retryGroup':
      case 'removeFailed':
      case 'removeGroup': {
        await wait(60)
        const members = jobs.filter((item) => item.group && item.group.id === req.id)
        if (!members.length) throw new Error('Collection not found.')
        const live = ['queued', 'preparing', 'downloading', 'processing', 'paused']
        if (req.op === 'removeFailed') {
          if (!members.some((item) => item.status === 'failed')) throw new Error('Nothing in this collection has failed.')
          jobs = jobs.filter((item) => !(item.group && item.group.id === req.id && item.status === 'failed'))
          return snapshot()
        }
        if (req.op === 'removeGroup') {
          if (members.some((item) => live.includes(item.status))) throw new Error('Wait until this collection has finished before removing its history.')
          jobs = jobs.filter((item) => !(item.group && item.group.id === req.id))
          return snapshot()
        }
        for (const item of members) {
          if (req.op === 'pauseGroup' && ['queued', 'preparing', 'downloading', 'processing'].includes(item.status)) patch(item.id, {status: 'paused', speed: undefined, eta: undefined})
          if (req.op === 'resumeGroup' && item.status === 'paused') patch(item.id, {status: 'downloading', speed: 6 * MB})
          if (req.op === 'cancelGroup' && live.includes(item.status)) patch(item.id, {status: 'cancelled', speed: undefined, eta: undefined, completedAt: Date.now()})
          if (req.op === 'retryGroup' && ['failed', 'cancelled'].includes(item.status)) patch(item.id, {status: 'queued', error: undefined, percent: 0})
        }
        return snapshot()
      }
      case 'openGroupLink':
        return null
      case 'clear':
        jobs = jobs.filter((item) => !['completed', 'failed', 'cancelled'].includes(item.status))
        return snapshot()
      case 'open':
      case 'reveal':
        return null
      case 'updateSettings':
        if (req.settings && req.settings.library) {
          const lib = req.settings.library
          // field by field, like main's mergeLibrary: a patch names only what changed
          for (const key of ['enabled', 'openWith', 'normalize', 'crossfade', 'routes', 'importTags']) if (lib[key] !== undefined) libSettings[key] = lib[key]
        }
        settings = {...settings, ...req.settings, library: libSettings}
        if (req.settings && req.settings.updateChannel && req.settings.updateChannel !== update.channel)
          update = {...update, channel: req.settings.updateChannel, phase: 'current', version: undefined, checkedAt: Date.now()}
        return snapshot()
      case 'chooseFolder':
        if (req.space !== undefined) return {path: 'D:\\Media\\Watch later'}
        settings = {...settings, outDir: 'D:\\Media\\Keepfin'}
        return snapshot()
      case 'importCookies':
        hasCookieFile = true
        settings = {...settings, cookies: {mode: 'file'}}
        return snapshot()
      case 'clearCookies':
        hasCookieFile = false
        return snapshot()
      case 'engineState':
        return clone(engine)
      case 'engineUpdate':
        engine = {...engine, busy: true, tools: engine.tools.map((tool) => (tool.name === 'yt-dlp' ? {...tool, state: 'updating'} : tool))}
        pushSnapshot()
        setTimeout(() => {
          engine = {busy: false, tools: engine.tools.map((tool) => (tool.name === 'yt-dlp' ? {...tool, state: 'ready', version: '2026.10.02', latest: '2026.10.02', checkedAt: Date.now()} : tool))}
          pushSnapshot()
        }, 1500)
        return clone(engine)
      case 'notificationState':
      case 'notificationConfigure':
      case 'notificationTest':
      case 'notificationSettings':
        if (req.notifications) fake.notifications = req.notifications
        return {preferences: fake.notifications || {enabled: true, completed: true, failed: true, sound: false, progress: true}, supported: true}
      case 'activityList': {
        const away = activityAway
        activityAway = 0
        return activityList(away)
      }
      case 'activityRead':
        activityEntries = activityEntries.map((entry) => (req.read === 'all' || (Array.isArray(req.read) && req.read.includes(entry.id)) ? {...entry, read: true} : entry))
        pushActivity()
        return activityList()
      case 'activityRemove':
        activityEntries = activityEntries.filter((entry) => entry.id !== req.id)
        pushActivity()
        return activityList()
      case 'updateState':
        return clone(update)
      case 'updateCheck':
        update = {...update, phase: 'checking'}
        setTimeout(() => {
          update = options.update === 'available' || options.update === 'portable' ? {...update, phase: 'available', checkedAt: Date.now()} : {...update, phase: 'current', checkedAt: Date.now()}
        }, 500)
        return clone(update)
      case 'updateDownload':
        update = {...update, phase: 'downloading', percent: 0}
        clearInterval(update.timer)
        fake.updateTimer = setInterval(() => {
          const percent = Math.min(100, (update.percent || 0) + 12)
          update = {...update, percent, phase: percent >= 100 ? 'ready' : 'downloading'}
          if (percent >= 100) clearInterval(fake.updateTimer)
        }, 400)
        return clone(update)
      case 'updateCancel':
        clearInterval(fake.updateTimer)
        update = {...update, phase: 'available', percent: 0}
        return clone(update)
      case 'openSupportLink':
        return null
      case 'updateInstall':
      case 'updateReleases':
      case 'openReleaseLink':
        return clone(update)
      case 'updateNotes': {
        const upcoming = ['available', 'downloading', 'ready'].includes(update.phase)
        update = {...update, notes: upcoming ? updateNotes.next : updateNotes.current, notesVersion: upcoming ? update.version : update.current}
        return clone(update)
      }
      default:
        return null
    }
  }

  const updateNotes = {
    current: ['## Windows', '- Rebuilt interface with a real format picker and sizes.', '- Collections load progressively.', '', '## Android', '- Faster inspection.'].join(String.fromCharCode(10)),
    next: ['## Windows', '- Adds **Spotify playlist** matching and per-track tags.', '- Fixes resuming paused downloads after a restart.', '- Updated the download engine to the latest release.', '', '## Android', '- Share-sheet capture is faster.'].join(String.fromCharCode(10)),
  }
  const updateScenario = options.update || 'current'
  let update = {
    // options.appVersion: the version screenshots show; tests keep 0.4.0, with 0.4.1 as the next one.
    current: options.appVersion || (updateScenario === 'beta' ? '0.5.0-beta.1' : '0.4.0'),
    channel: 'stable',
    mode: updateScenario === 'portable' ? 'portable' : 'installed',
    phase: 'current',
    checkedAt: Date.now() - (updateScenario === 'stale' ? 9 : 1) * hour,
  }
  if (updateScenario === 'available' || updateScenario === 'portable') update = {...update, phase: 'available', version: '0.4.1', notes: updateNotes.next, notesVersion: '0.4.1'}
  if (updateScenario === 'downloading') update = {...update, phase: 'downloading', version: '0.4.1', percent: 42, notes: updateNotes.next, notesVersion: '0.4.1'}
  if (updateScenario === 'ready') update = {...update, phase: 'ready', version: '0.4.1', percent: 100, notes: updateNotes.next, notesVersion: '0.4.1'}
  if (options.lastSeen) {
    try {
      localStorage.setItem('keepfin-last-seen-version', options.lastSeen)
    } catch {}
  }

  window.keepfin = {
    request: async (req) => {
      fake.calls.push(clone(req))
      return clone(await handle(req))
    },
    onPush(callback) {
      listeners.add(callback)
      return () => listeners.delete(callback)
    },
  }
})()
