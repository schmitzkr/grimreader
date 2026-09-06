<img src="assets/icon/icon.png" alt="GrimReader app icon" width="120" align="left" />

### GrimReader

A native Android client for [Grimmory](https://grimmory.org), a self-hosted
digital library server. Kotlin and Jetpack Compose.

<br clear="left" />

> [!WARNING]
> **Early days.** GrimReader is under heavy development. Releases ship
> often and bugs are likely. Expect rough edges and please
> [open an issue](https://github.com/schmitzkr/grimreader/issues) when you
> hit one.

## What works today

- Connect to any Grimmory server, sign in with username and password
- Home with Continue listening, Continue reading and Recently added
- Libraries with status and format filter pills and sorting
- Book detail with progress and mark finished
- Audiobook playback: single-file and folder-based books, resume from the
  server's saved position, 30-second skips that cross track boundaries,
  chapters, speed (remembered per book), sleep timer with fade-out,
  auto-rewind after a pause, notification and lock-screen controls,
  Android Auto browsing
- Comic (CBX) and PDF readers: pinch to zoom, double-tap, page slider,
  right-to-left reading for manga, night mode for PDFs, progress saved to
  the server so the web reader opens on the same page
- Floating mini player and navigation bar, neutral theme with a choice of
  accent, pure-black option for OLED screens

## Coming next

In rough order: SSO sign-in, the EPUB reader, downloads and
offline playback, dashboard rows from the server's layout, series, authors
and shelves, search, bookmarks, in-app updates, reading-session recording
and the stats screen. Each has an issue in the tracker.

## Building

Needs JDK 21 and an Android SDK with platform 36.

```
./gradlew :core:test :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug                  # debug APK
./gradlew :app:assembleRelease                # release (debug-signed without the keystore env vars)
```

`core` is a plain Kotlin module (models, the Grimmory client, timeline
maths) with no Android in it; `app` is the Compose app.

Releases come from the Release workflow (`workflow_dispatch` with a version),
which stamps that version into the build, signs with the keystore in the
repository secrets, tags `vX.Y.Z` and publishes the APK on GitHub Releases.
The version of record is the tag; `app/build.gradle.kts` only holds a
placeholder, so no bump commit is needed.

The launcher icon is generated from `assets/icon/` (`icon.png` for the
README and stores, `icon_foreground.png` and `icon_monochrome.png` for the
adaptive icon layers in `app/src/main/res`).

## Licence

MIT, see [LICENSE](LICENSE).
