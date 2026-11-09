// Adapter for the shared core's typed pairing code check (core net/Pairing.kt pairCodeProblem), usable in the renderer. The reading of the
// code itself (parsePairCode) stays with the engine; this only says what is wrong with a typed code, in words, the same on both apps.
import {netPairCodeProblem} from '../../../build/core-js/keepfin-core.mjs'

/** What is wrong with a typed code, or null when the engine can read it. */
export const pairCodeProblem = (text: string): string | null => netPairCodeProblem(text) ?? null
