# gui/desktop: the desktop engine and Electron shell

The Node side of the desktop app: engine (`engine/`), queue (`manager.ts`), library (`library/`), copies (`copies/`, `copiesHost.ts`), devices (`net/`), the SQL store (`store/`, `sqlstate/`, `merge/`), cloud copies (`cloud/`). It moves to `packages/engine` and `apps/desktop` in step 5 of `dev-docs/plans/PROJECT-STRUCTURE.md`.

- **Only `main.ts` and `preload.cts` import Electron.** Everything platform-specific is handed in from `main.ts` as callbacks. `npm run check` fails otherwise (`scripts/check/check-boundaries.mjs`).
- **Decisions live in the core** (`gui/shared/core/*` adapters). Don't write a rule here that the phone also needs; put it in `core/`.
- **The renderer sends structured requests only** (`gui/shared/contracts.ts`), never paths or command-line arguments. Main re-validates every path.
- **Small files.** `npm run check` fails when a file passes its limit (`scripts/check/big-files.json`). New features go in new files: `manager/`, `library/parts/` and `copiesHost/` hold the parts of the three big classes, each reached through its core interface.
- **Tests** sit next to the code (`*.test.ts`) and are found automatically. `scale.test.ts` files run with `npm run test:slow`.

Checks: `npm run check`, then `npm run engine:test` for engine changes and `npm run desktop:test` for packaging.
