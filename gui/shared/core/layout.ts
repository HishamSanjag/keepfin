// Adapter between the shared core (core/, Kotlin Multiplatform) and card sizes and page layouts. This is the only file that imports the
// core's layout package. The core decides the sizes, the default sections and how a saved layout is made whole.
import {
  cardPrefsReadJson,
  layoutCardSize,
  layoutDefaultsJson,
  layoutMoveJson,
  layoutReadJson,
  layoutStepJson,
  layoutStyleJson,
  layoutToggleJson,
} from '../../../build/core-js/keepfin-core.mjs'

export type CardKind = 'video' | 'square' | 'round' | 'mix' | 'photo' | 'tile'
export type CardText = 'full' | 'title' | 'none'
export type SectionStyle = 'row' | 'grid'
export type LayoutPage = 'home' | 'library'
export type CardPrefs = {scale: number; text: CardText}
/** `canStyle` is false for sections that are always shown one way (the spaces row, Jump back in, Downloading now). */
export type SectionPref = {id: string; visible: boolean; style: SectionStyle; canStyle: boolean}
export type PageLayout = {page: LayoutPage; sections: SectionPref[]}

export const CARD_KINDS: readonly CardKind[] = ['video', 'square', 'round', 'mix', 'photo', 'tile']
export const MIN_CARD_SCALE = 0.8
export const MAX_CARD_SCALE = 1.25

/** A card's size on a page `pageWidth` px wide (a photo's row height, every other kind's width). */
export const cardSize = (kind: CardKind, pageWidth: number, scale: number): number => layoutCardSize(kind, pageWidth, scale)

const parse = (json: string) => JSON.parse(json) as PageLayout
const text = (layout: PageLayout) => JSON.stringify(layout)

/** The saved layout made whole; `undefined` gives the defaults. */
export const readLayout = (page: LayoutPage, saved: unknown): PageLayout => parse(layoutReadJson(page, saved === undefined ? '' : JSON.stringify(saved)))
export const defaultLayout = (page: LayoutPage): PageLayout => parse(layoutDefaultsJson(page))
export const toggleSection = (layout: PageLayout, id: string) => parse(layoutToggleJson(layout.page, text(layout), id))
export const styleSection = (layout: PageLayout, id: string, style: SectionStyle) => parse(layoutStyleJson(layout.page, text(layout), id, style))
export const moveSection = (layout: PageLayout, id: string, target: string, after: boolean) => parse(layoutMoveJson(layout.page, text(layout), id, target, after))
export const stepSection = (layout: PageLayout, id: string, delta: -1 | 1) => parse(layoutStepJson(layout.page, text(layout), id, delta))
export const readCardPrefs = (saved: unknown): CardPrefs => JSON.parse(cardPrefsReadJson(saved === undefined ? '' : JSON.stringify(saved))) as CardPrefs
