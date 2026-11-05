# android: the phone app (native Kotlin)

Jetpack Compose + Material 3, Media3 playback, MediaStore. It does not run the Node engine or load the React screens. Design: `android/DESIGN.md`. It moves to `apps/android` in step 5 of `dev-docs/plans/PROJECT-STRUCTURE.md` (app id `app.keepfin` and Kotlin packages stay).

- **Decisions come from the core** (`import app.keepfin.core.*`). A rule Windows also needs goes in `core/`, not here.
- **Same features as Windows** (feature parity): a change here is checked against the desktop app, and the other way round.
- **Portrait layouts, dp/sp sizes, 48 dp touch targets, font scaling.**
- **Small files.** `npm run check` fails when a file passes its limit (`scripts/check/big-files.json`). New actions go in the helpers in `ui/model/` (through `ViewModelHost`) and `library/repo/Repo*.kt` (through `RepoCore`), not in `StudioViewModel.kt` or `LibraryRepository.kt`.
- **The Compose BOM stays at 2026.03.00** for AGP 8.13 / compileSdk 36.
- **Never read or print the signing secrets** (names only: `ANDROID_KEYSTORE_*`, `ANDROID_KEY_*`).

Checks, from `android/` (strip stray `"` from PATH first on Windows): `gradlew.bat :app:testDebugUnitTest`, and `gradlew.bat :app:connectedDebugAndroidTest` with a device or emulator for UI changes. A build alone proves nothing about device UI.
