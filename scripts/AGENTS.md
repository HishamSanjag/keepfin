# scripts: build, test, release and capture helpers

Run through `npm run …` names in `package.json`; those names never change when files move.

| Folder | What is in it |
|---|---|
| `build/` | the core, icons, installer art, the bundled tools, the Android build helper |
| `release/` | sorting builds into `releases/`, checking the update files |
| `check/` | the guard rails `npm run check` runs: `check-boundaries.mjs` (the layer rules), `check-links.mjs` (no broken links to files), `report-sizes.mjs` (no new file over 1,000 lines, and known big files stay under their limit in `big-files.json`) |
| `test/` | `run-tests.mjs` (finds every `*.test.ts`; `--slow` runs the scale tests at full size, `--list` prints them), `test-core.mjs`, the engine/desktop/UI smoke tests and their `ui-fixtures/`, the peers the Android device tests talk to |
| `capture/` | screenshots for the docs, the README and the website |
| `dev/` | the browser demo, and `move-files.mjs` (moves files with history and rewrites every reference) |

- **A script finds the repository root from its own location** (`'..', '..'` from these folders). When you move one, use `scripts/dev/move-files.mjs` and fix its root line.
- **Move files with `node scripts/dev/move-files.mjs <map.json>`** (a dry run; add `--apply`), then run `npm run check` and the builds.
- **No secrets** in scripts or their output.

Check: `npm run check`.
