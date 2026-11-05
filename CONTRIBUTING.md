# Contributing to Keepfin

Thanks for helping. Bug reports, ideas and pull requests are all welcome.

## Reporting a bug

Open an [issue](https://github.com/HishamSanjag/keepfin/issues) with the app (Windows or Android) and its version (Settings → About), what you did, what you expected and what happened. A link that shows the problem helps most. Security problems go through [SECURITY.md](SECURITY.md) instead.

## Building and testing

You need Git, Node.js 24+ and a JDK 17+ (it builds the shared core). The Android app also needs SDK 36 and build-tools 36.0.0.

```powershell
npm ci                 # also turns on the pre-commit checks
npm run check          # layer rules, links, file sizes, TypeScript, unit tests, builds
npm start              # the Windows app
```

Android, from `android/`: `gradlew.bat :app:testDebugUnitTest`, and with an emulator `gradlew.bat :app:connectedDebugAndroidTest`. `package.json` lists every other command.

## Where things live

- `gui/`: the Windows app (Electron main process in `gui/desktop/`, the React window in `gui/web/`, code both share in `gui/shared/`)
- `android/`: the Android app (native Kotlin and Jetpack Compose)
- `core/`: decisions both apps need, written once in Kotlin Multiplatform and run on the JVM and as JavaScript; `fixtures/engine/` holds the shared test cases that run on both apps
- `docs/`: the user guide, release notes and screenshots ([start here](docs/README.md))

The full map is in [project structure](docs/project-structure.md) and the technologies are in [tech stack](docs/tech-stack.md). [AGENTS.md](AGENTS.md) lists the rules the apps follow. [CONTEXT.md](CONTEXT.md) defines the words used in the code.

## Pull requests

- One change per pull request, with a test that fails without it.
- A rule both apps need goes in `core/` with a fixture, not into each app.
- Keep the two apps behaving the same: a change on one is checked on the other.
- `npm run check` passes, and the pre-commit checks run (`git commit` runs them; they take seconds).
- The apps never show the names of the download tools they use (say "download engine").

By contributing you agree that your contribution is licensed under the [MIT License](LICENSE).
