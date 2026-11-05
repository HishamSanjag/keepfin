// Composes README artwork from real app screenshots in docs/screenshots:
//   node scripts/capture/readme-images.mjs            → docs/readme/*.png
// Inputs are captured by the UI suites (desktop) and on the emulator (Android).
import {chromium} from '@playwright/test'
import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const shots = path.join(root, 'docs/screenshots')
const out = path.join(root, 'docs/readme')
await fs.mkdir(out, {recursive: true})

const dataUrl = async (file) =>
  `data:image/png;base64,${(await fs.readFile(path.join(shots, file))).toString('base64')}`
const icon = `data:image/svg+xml;base64,${(await fs.readFile(path.join(root, 'assets/icons/icon.svg'))).toString('base64')}`

const themes = {
  dark: {bg: '#0b0f16', glow: 'rgba(76,126,255,0.28)', ink: '#eff3fa', muted: '#a9b6cc', frame: '#1b2331', line: 'rgba(255,255,255,0.08)'},
  light: {bg: '#f5f3ee', glow: 'rgba(76,126,255,0.18)', ink: '#192333', muted: '#536178', frame: '#ffffff', line: 'rgba(25,35,51,0.10)'},
}

function page(theme, body, width, height) {
  const t = themes[theme]
  return `<!doctype html><html><head><meta charset="utf-8"><style>
  *{box-sizing:border-box;margin:0}
  body{width:${width}px;height:${height}px;overflow:hidden;font-family:"Segoe UI",system-ui,sans-serif;color:${t.ink};
    background:radial-gradient(900px 520px at 72% 38%, ${t.glow}, transparent 70%), ${t.bg};}
  .window{position:absolute;border-radius:14px;overflow:hidden;background:${t.frame};
    box-shadow:0 40px 90px -30px rgba(0,0,0,.55),0 0 0 1px ${t.line};}
  .bar{height:30px;display:flex;align-items:center;gap:7px;padding:0 13px;background:${t.frame};border-bottom:1px solid ${t.line}}
  .bar i{width:11px;height:11px;border-radius:50%;background:${t.line}}
  .window img{display:block;width:100%}
  .phone{position:absolute;border-radius:40px;padding:12px;background:#05070b;
    box-shadow:0 40px 90px -25px rgba(0,0,0,.6),0 0 0 1px rgba(255,255,255,.06)}
  .phone img{display:block;width:100%;border-radius:20px}
  .brand{position:absolute;display:flex;align-items:center;gap:16px}
  .brand img{width:64px;height:64px;border-radius:15px}
  .brand b{font-size:44px;font-weight:700;letter-spacing:-.02em}
  .tag{position:absolute;font-size:22px;line-height:1.45;color:${t.muted};max-width:430px}
  .tag strong{color:${t.ink};font-weight:600}
  </style></head><body>${body}</body></html>`
}

const browser = await chromium.launch()
async function render(name, html, width, height) {
  const tab = await browser.newPage({viewport: {width, height}, deviceScaleFactor: 2})
  await tab.setContent(html, {waitUntil: 'load'})
  await tab.screenshot({path: path.join(out, name)})
  await tab.close()
  console.log(`wrote docs/readme/${name}`)
}

// NO_ANDROID=1 renders desktop-only artwork (e.g. while phone screenshots are being retaken).
const withAndroid = !process.env.NO_ANDROID
for (const theme of ['dark', 'light']) {
  const desktop = await dataUrl(`desktop-hero-${theme}.png`)
  const phone = withAndroid ? await dataUrl(`android-downloads-${theme}.png`) : ''
  // Hero banner: brand + promise on the left, real desktop window with the phone overlapping it.
  await render(
    `banner-${theme}.png`,
    page(
      theme,
      `<div class="brand" style="left:64px;top:84px"><img src="${icon}"><b>Keepfin</b></div>
       <p class="tag" style="left:64px;top:178px"><strong>Keep your media.</strong> Video, music, playlists and posts from YouTube, TikTok, Instagram, SoundCloud and 1,000+ more sites, in any quality, on Windows and Android.</p>
       <div class="window" style="left:520px;top:70px;width:900px"><div class="bar"><i></i><i></i><i></i></div><img src="${desktop}"></div>
       ${withAndroid ? `<div class="phone" style="left:1290px;top:150px;width:250px"><img src="${phone}"></div>` : ''}`,
      1600,
      720,
    ),
    1600,
    720,
  )
  if (!withAndroid) continue
  // Phone trio for the Android section.
  const trio = await Promise.all(
    ['android-review', 'android-collection', 'android-library'].map((name) => dataUrl(`${name}-${theme}.png`)),
  )
  await render(
    `android-${theme}.png`,
    page(
      theme,
      trio
        .map((src, index) => `<div class="phone" style="left:${60 + index * 330}px;top:${index === 1 ? 36 : 84}px;width:290px"><img src="${src}"></div>`)
        .join(''),
      1050,
      790,
    ),
    1050,
    790,
  )
}

// Framed single desktop screens for the feature sections.
for (const [name, file] of [
  ['formats', 'media-review-video'],
  ['audio', 'media-review-audio'],
  ['collections', 'collection'],
  ['library', 'library-grid'],
  ['support', 'settings-support'],
]) {
  for (const theme of ['dark', 'light']) {
    const src = await dataUrl(`desktop-${file}-${theme}.png`)
    await render(
      `${name}-${theme}.png`,
      page(theme, `<div class="window" style="left:40px;top:36px;width:1120px"><div class="bar"><i></i><i></i><i></i></div><img src="${src}"></div>`, 1200, 800),
      1200,
      800,
    )
  }
}
await browser.close()
