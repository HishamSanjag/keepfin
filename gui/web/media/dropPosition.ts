/** Whether a pointer at `clientY` is in the lower half of a box, so a dragged section lands after it and not before. */
export const dropsAfter = (clientY: number, box: {top: number; height: number}) => clientY > box.top + box.height / 2
