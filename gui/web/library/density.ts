import {useStoredChoice} from './controller'

export type Density = 'compact' | 'comfortable'
export const densities: readonly Density[] = ['compact', 'comfortable']

/** Remembered per space (a per-space key in localStorage); compact is the default. */
export const useDensity = (spaceId: string) => useStoredChoice<Density>(`keepfin-lib-density:${spaceId}`, 'compact', densities)

/** Sizes the virtualised lists and grids use, in CSS px. Comfortable keeps the earlier, roomier values. */
export const sizes = {
  compact: {songRow: 44, fileRow: 44, posterMin: 150, coverMin: 140, albumMin: 140, artistMin: 140, playlistMin: 140, gap: 12, cellExtra: 44},
  comfortable: {songRow: 56, fileRow: 68, posterMin: 220, coverMin: 168, albumMin: 176, artistMin: 160, playlistMin: 176, gap: 16, cellExtra: 54},
} as const satisfies Record<Density, Record<string, number>>
