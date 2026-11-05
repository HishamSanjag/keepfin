# core: the shared decisions (Kotlin Multiplatform)

Written once, used by both apps: JVM for Android (`includeBuild("../core")`), JavaScript for desktop (`scripts/build/build-core.mjs` → `build/core-js`). Details and portability rules: `core/README.md`. Its future modules: step 9 of `dev-docs/plans/PROJECT-STRUCTURE.md`.

- **No I/O.** The core decides; the apps do the files, network and processes. Sequences both apps run are written as `(state, event, time) → (new state, things to do)`.
- **Portable code only** (`core/README.md`): no regex shorthands, JavaScript whitespace and trim, JavaScript rounding. Every fixture runs on both JVM and JS.
- **Anything both apps need goes here,** never written twice in TypeScript and Kotlin.

Checks, from `core/`: `gradlew.bat jvmTest jsNodeTest`. A core change also needs `npm run check` and the Android unit tests (`android/`: `gradlew.bat :app:testDebugUnitTest`), because both apps consume it.
