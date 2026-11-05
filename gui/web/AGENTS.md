# gui/web: the React screens

The desktop app's screens (and later the web version's). It moves to `packages/ui` in step 5 of `dev-docs/plans/PROJECT-STRUCTURE.md`. Design rules: the root `DESIGN.md`.

- **The engine is reached only through `bridge.ts`** (requests and push events) and `media.ts` (playback addresses). Never import from `gui/desktop`, Electron or Node; `npm run check` fails otherwise (`scripts/check/check-boundaries.mjs`).
- **The browser preview (`npm run dev`) has no download API.** Never fake a successful inspection or transfer.
- **`store.ts` is an external store with per-job subscriptions,** so progress re-renders only the affected row. Keep it that way.
- **Folders by feature:** `shell/`, `capture/` (the link field and its state), `preview/` (the one window every download preview opens in, with its Extras), `collection/`, `queue/`, `library/`, `player/`, `settings/`, `setup/`. Primitives are in `ui/`, tokens in `styles/`. A new feature gets its own folder.
- **No external tool names** in any visible text (AGENTS.md, behavioural rules).

Checks: `npm run check` and `npm run gui:ui-test` (flows, progress isolation, layout and accessibility with a fake bridge).
