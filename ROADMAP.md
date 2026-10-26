# Roadmap

Written after the October 2026 code review. The app is in good shape: playback, resume, chapters,
covers and release signing have all been worked through carefully, and most of the tricky rules have
unit tests. The items below are what is left, in the order they are worth doing.

Each item should be one small pull request (under ~300 changed lines) that CI (**Android CI**:
unit tests, lint and a debug APK) passes before it is merged. Sizes: **S** = an hour or two,
**M** = a day, **L** = several days.

## Already fixed in the review

- Bookmarks saved the spot where **Save** was pressed, not where **MARK** was tapped, so typing a note
  moved the bookmark later (sometimes into the next file).
- A file whose length couldn't be read once (still copying or syncing) showed `00:00` and no progress
  until the file changed. It is now read again on the next scan.
- The library header said "3 audio tracks" when it meant three audiobooks.
- The README described an old, debug-signed release process.

## 1. Protect listening data

| # | What | Why it matters | Size |
|---|------|----------------|------|
| 1.1 | ~~**Export and import progress and bookmarks**~~ **Done:** library ⋮ menu → *Back up progress & bookmarks* / *Restore from backup* (JSON file through the system file picker; still no internet). | Cloud backup is off on purpose (`allowBackup="false"`), so uninstalling the app or changing phones lost every saved position and bookmark. | M |
| 1.2 | **Changing folder while a book is playing**: check what happens. Choosing a new folder releases access to the old one, so the next file of the playing book may fail with "Unable to play audio file". Either keep the old grant until playback moves on, or stop playback with a clear message. *Needs a check on a phone.* | Avoids a confusing error after switching libraries. | S |

## 2. Speed and polish for bigger libraries

| # | What | Why it matters | Size |
|---|------|----------------|------|
| 2.1 | ~~**Faster folder scan**~~ **Done:** one query per folder. Was: read name, type, size and date for all files of a folder in one query, instead of about five separate queries per file (`AudiobookRepository.scanDirectoryRecursively`). | Scans of libraries with thousands of files are slow, especially on SD cards and cloud folders. | M |
| 2.2 | ~~**Refresh without hiding the library**~~ **Done:** progress bar and "N audio files found" above the list; Refresh again restarts the scan. Was: keep the list visible with a small progress bar while rescanning, and cancel a scan that is still running when Refresh is tapped again. | Today the whole list disappears behind a spinner, and two taps start two scans. | S |
| 2.3 | ~~**Live progress in the library**~~ **Done** (worth a look on a phone). Was: the percentage and "time left" of the playing book only update when the list is redrawn for another reason. *Needs a check on a phone.* | Small, but visible. | S |
| 2.4 | ~~**Bookmarks in multi-file books**~~ **Done:** the list shows the time in the whole book and the chapter or file. Was: show only the time inside the file (two bookmarks can both say `05:12`). Show the chapter or file name and the time in the whole book, as the chapter list already does. | Makes bookmarks readable for folder books. | S |

## 3. Sleep timer

| # | What | Why it matters | Size |
|---|------|----------------|------|
| 3.1 | Decide whether the countdown should **pause while playback is paused** (most audiobook apps do). If yes, freeze the remaining time on pause and continue on play. | Pausing for a phone call shouldn't use up the timer. | S |
| 3.2 | Small fixes: after **+5 min** the remaining time is rounded down for one tick; the countdown text uses the phone's locale for digits without saying so. | Cosmetic. | S |
| 3.3 | Optional: **shake to extend** in the last minute. | Common request for bedtime listening. | M |

## 4. Getting ready for Google Play (only if you want to publish there)

| # | What | Size |
|---|------|------|
| 4.1 | Build an **app bundle** (`bundleRelease`, `.aab`) in the Release APK workflow; Play only accepts bundles. | S |
| 4.2 | Turn on **code shrinking** (R8, `isMinifyEnabled = true`) with keep rules for Media3, and test the release build on a phone. Makes the app much smaller; a mistake here only shows up in release builds, so test carefully. | M |
| 4.3 | Write a short **privacy policy** page ("no data leaves the device") and the Play **Data safety** form. Play requires both even when nothing is collected. | S |
| 4.4 | Keep `targetSdk` current: 36 meets today's Play rule; Google raises it every August. | S |
| 4.5 | Move all screen text into `strings.xml` so the app can be translated. | M |

## 5. Clean-up (no behaviour change; do after the fixes above)

| # | What | Size |
|---|------|------|
| 5.1 | Remove unused code: `AudiobookRepository.loadAudiobooksFromFolder` and `scanAudioFiles`, `MainViewModel.navigateToFolderSelection`, the template `ExampleUnitTest` / `ExampleInstrumentedTest`, and the AI Studio leftovers `metadata.json` and `assets/.aistudio/`. | S |
| 5.2 | Split `AudiobookPlayerManager` (650 lines) into smaller parts, for example the sleep timer and the "finished / unplayed" handling, keeping the existing tests green. | L |
| 5.3 | Add tests for `MainViewModel` (folder chosen, folder access lost, mark finished/unplayed) and a Compose test for the player screen buttons. | M |

## 6. Feature ideas

- **Per-book speed** (remember 1.3x for one narrator and 1x for another). M
- **Android Auto** and car head units (switch the service to `MediaLibraryService` with a browsable library). L
- **Listening statistics**: time listened per day and per book, kept on the device. M
- **Home-screen widget** with cover, play/pause and skip. M
- **Volume boost / mono** for quiet recordings and one-earbud listening. M
