# gui/shared: the contract and pure TypeScript logic

The types that cross the doorway between the screens and the engine (`contracts.ts` and the state types it imports), pure helpers, and the adapters to the Kotlin core (`core/*.ts`). It splits into `packages/contracts` and `packages/shared` in step 5 of `dev-docs/plans/PROJECT-STRUCTURE.md`.

- **No I/O and no app imports.** Nothing here imports `gui/desktop`, `gui/web` or Electron (`scripts/check/check-boundaries.mjs`).
- **`core/*.ts` are the only files that import the generated core bundle** (`build/core-js`). Everything else uses these adapters.
- **Logic both apps need belongs in `core/` (Kotlin), not here.** This folder holds the desktop-only remainder and thin adapters.
- **Contract changes need both apps.** The phone mirrors these types in `android/.../EngineModels.kt`; until step 8 makes one generated contract, change both together.

Checks: `npm run check` (includes the shared fixtures in `fixtures.test.ts`).
