# GrimReader (Kotlin)

Native Android client for Grimmory. Kotlin 2.2, Jetpack Compose, Hilt, Retrofit
with kotlinx.serialization, Media3. Two modules: `core` (pure Kotlin: models,
`GrimmoryClient`, progress and timeline maths) and `app` (Compose UI, data
layer, playback service).

## Conventions

- Branch → PR → squash merge. Commit subjects in conventional-commit form.
  Attribution trailers go on with `git commit --trailer`, never as separate
  `-m` paragraphs.
- Releases are cut with the Release workflow (`workflow_dispatch`, semver
  version). A batch of features is a minor bump, a fix is a patch bump.
  Version numbers started at 0.13.0 and the workflow offsets `versionCode`
  by 100, both continuing an earlier line of releases. The tag is the
  version of record: the workflow stamps the build and commits nothing, so
  `app/build.gradle.kts` keeps a placeholder version.
- `main` is protected: PR required, `build-and-test` and the OSV scan must
  pass. Dependabot keeps Gradle and Actions current weekly.
- Never name other apps or projects in issues, PRs, commits or the README.
- Everything that talks to the server goes through `core`. The app never
  builds a request itself.
- The player may only be read on the main thread; the service snapshots on
  Main and posts on IO.
- Progress bodies always carry the `fileProgress` block; the server drops
  the typed field alone for dual-format books.
- The `continue-*` endpoints return nothing for admins; Continue rows are
  built from the filtered list endpoint (see `BooksRepository`).

## Building locally

JDK 21 at `JAVA_HOME`; Android SDK with platform 36 in `local.properties`
(`sdk.dir=`) or `ANDROID_HOME`. The dev box has 3.8 GB of RAM and an
uncapped Gradle build has crashed it: `gradle.properties` keeps one 1 GB
JVM with the Kotlin compiler in-process and no daemon, and every local
build runs inside a memory-capped systemd user scope through
`~/.claude/scripts/gradle-build-limited.sh` (MemoryMax 2.2G, the same cap
validated for Flutter's Gradle step). Run the phases separately so nothing
is held in memory at once:

```
gradle-build-limited.sh -p . :app:assembleDebug
gradle-build-limited.sh -p . :core:test :app:testDebugUnitTest
gradle-build-limited.sh -p . :app:lintDebug
```

Never run a bare `./gradlew` here, and never two builds at once.
