// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the desktop's start pages. This is the only file that imports the core's start package. The core decides the
// pages, their order, where Skip intro goes, when the folder page lets you on, and every word (dev-docs/plans/START-PAGES.md).
import {startCanGoOn, startIntroduces, startPages, startSkipTo, startWordsJson} from '../../../build/core-js/keepfin-core.mjs'

export type StartPage = 'WELCOME' | 'HOW' | 'MODE' | 'FOLDER' | 'PERMISSIONS'
export type StartLine = {title: string; body: string}
export type StartWords = {
  welcomeEyebrow: string
  welcomeTitle: string
  welcomeBody: string
  features: StartLine[]
  sitesTitle: string
  sites: string[]
  sitesMore: string
  musicLinks: string
  howEyebrow: string
  howTitle: string
  steps: StartLine[]
  signIn: StartLine
  modeEyebrow: string
  modeTitle: string
  complete: StartLine
  downloads: StartLine
  recommended: string
  modeLater: string
  folderEyebrow: string
  folderTitle: string
  folderHint: string
  haveFolder: string
  advanced: string
  advancedHint: string
  folderLater: string
  next: string
  back: string
  skipIntro: string
  start: string
}

/** The desktop's pages, in order. */
export const pages: readonly StartPage[] = Array.from(startPages(false)) as StartPage[]
export const skipTo = startSkipTo() as StartPage
export const introduces = (page: StartPage): boolean => startIntroduces(page)
export const canGoOn = (page: StartPage, folderReady: boolean): boolean => startCanGoOn(page, folderReady)
export const words: StartWords = JSON.parse(startWordsJson(false))
