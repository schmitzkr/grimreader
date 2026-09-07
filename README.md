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
- Home with the server's own rows, plus Up next in series and Recently
  finished (each switchable off in Settings)
- Libraries with status and format filter pills and sorting
- Book detail with progress and mark finished
- Audiobook playback: single-file and folder-based books, resume from the
  server's saved position, 30-second skips that cross track boundaries,
  chapters, speed (remembered per book), sleep timer with fade-out,
  auto-rewind after a pause, notification and lock-screen controls,
  Android Auto browsing
- Every file type the server stores has a path: FB2 is converted on the
  device and opens in the EPUB reader; MOBI, AZW3 and supplementary files
  can be handed to another app; a book's files are listed on its page
- EPUB reader: chapters, bookmarks, light, sepia and dark pages, text size,
  swipe or tap to turn, progress saved as the same CFI and percentage the
  web reader uses so both open at the same place
- Comic (CBX) and PDF readers: pinch to zoom, double-tap, page slider,
  right-to-left reading for manga, night mode for PDFs, progress saved to
  the server so the web reader opens on the same page
- Download any book for offline use: audiobooks play, EPUB, FB2 and PDF
  open in their readers and comics keep every page, all from the device
  with no network; a Downloaded pill filters each library, and Settings →
  Downloads lists what is on the device with sizes and removal
- Positions saved on the device first and pushed to the server, so a lost
  connection never loses your place; pending saves retry on their own
- Reading and listening sessions recorded for the server's stats pages,
  queued on the device when offline
- Your stats in Settings: this week's listening and reading, streaks with a
  past-year grid, last seven days of listening, audiobooks in progress
- Shake to reset a running sleep timer; libraries remember their sort and
  filter pills
- Floating mini player and navigation bar, the same floating chrome inside
  the readers, neutral theme with a choice of accent, pure-black option for
  OLED screens

## Coming next

In rough order: SSO sign-in, dashboard rows from the server's layout,
series, authors and shelves, search, bookmarks and in-app updates. Each has an issue in the tracker.

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
