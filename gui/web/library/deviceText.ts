// The words of Music on this PC, in one place so the card, the space and Settings say the same things.
export const deviceCaption = 'Only on this PC'

/** Shown while the first scan runs. */
export const findingMusic = (found: number) => `Finding your music… ${found.toLocaleString('en-US')} ${found === 1 ? 'song' : 'songs'}`