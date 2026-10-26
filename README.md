# My Private Audiobook Player

A simple, reliable, 100% offline and privacy-first audiobook player for Android.

## Download the APK

Every push to `main` and every pull request builds a debug APK in GitHub Actions. While signed in to GitHub, open the **Actions** tab → an **Android CI** run → **Artifacts** → **app-debug-apk** to download a zip containing `app-debug.apk`.

**Release APK (signed with your release key):** publish a release on GitHub (**Releases** → **Draft a new release**, tag such as `v1.2.0` → **Publish**). The **Release APK** workflow then builds `audiobook-<version>.apk`, signed with your release key, and attaches it to that release. You can also run it by hand from **Actions** → **Release APK** → **Run workflow** (leave the tag empty to get the APK as an Actions artifact only). Enable **Install unknown apps** on your Android device to sideload it.

The workflow needs four repository secrets: `RELEASE_KEYSTORE_BASE64` (the keystore file, base64-encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. If any is missing it stops with an error; it never falls back to the debug key or an unsigned APK, because Android only installs an update over an app signed with the same key. Locally, `./gradlew assembleRelease` reads `AUDIOBOOK_KEYSTORE_FILE`, `AUDIOBOOK_KEYSTORE_PASSWORD`, `AUDIOBOOK_KEY_ALIAS` and `AUDIOBOOK_KEY_PASSWORD` instead.

## Features

1. **Folder Selection & Recursive Sub-Folder Scanning**
   - Tap "Choose Audiobook Folder" on first launch to pick any folder containing your audiobooks.
   - Automatically scans sub-folders (up to 3 levels deep) and treats each sub-folder as one audiobook, with tracks played in natural filename order and automatic advance. Files directly in the chosen folder are single books.
   - You can easily switch to a different folder at any time using the folder icon at the top of the Library.
   - Supports popular audiobook formats: MP3, M4A, M4B, AAC, FLAC, OGG, WAV, WMA, and OPUS.

2. **Clean Book Library**
   - **Cover art**: Embedded artwork first, then `cover.jpg`, `cover.jpeg`, `cover.png`, `folder.jpg`, `folder.jpeg`, or `folder.png` (case-insensitive) for folder books, then coloured initials. Covers appear in the library, player, mini-player, and media controls. Thumbnails are cached locally; no network.
   - Automatically formats file names into clean, readable book titles.
   - Displays duration and listening progress percentage on every book. Durations are cached for faster library loading.
   - Search by title or filename, filter by listening status, and sort by title, recently played, progress, or duration. Sort and filter choices are remembered.
   - Finished labels and time remaining make progress easy to see. Each book's options menu can mark it finished or unplayed without changing bookmarks.

3. **Dedicated Player Screen**
   - **Prominent Center Play / Pause** button.
   - **Interactive Seek Bar** with elapsed time and total duration.
   - **4 Skip Controls**:
     - Rewind 1 minute (`-1m`)
     - Rewind 15 seconds (`-15s`)
     - Fast forward 30 seconds (`+30s`)
     - Fast forward 1 minute (`+1m`)
   - Configurable inner skip intervals: 5, 10, 15, 30, 45, or 60 seconds (defaults: back 15, forward 30).
   - A chapter list and previous/next chapter buttons for books with multiple chapters. The seek bar and skip controls operate within the current file.
   - **Playback Speed Selector**: 0.5x–3x in 0.05x steps, with a slider, presets, and reset.
   - **Sleep Timer**: Choose 5, 10, 15, 30, 45, 60, or 90 minutes, or end of track. End of track survives service recreation when the same book and track load within 30 minutes on the same device boot. Live countdown, +5 minute extension, and optional fade-out over the last 30 seconds (enabled by default).
   - **Playback settings**: Adjust skip intervals, smart rewind, and sleep fade-out from the player gear button. Smart rewind is enabled by default: resumes within the current track by 0/2/5/10/20 seconds depending on pause length.

4. **Bookmarks**
   - Save your exact position at any moment with a custom or auto-generated note.
   - Open the bookmarks list to see all saved timestamps for the current book.
   - Tap any bookmark to jump straight to that moment, or delete bookmarks you no longer need.

5. **Progress Memory**
   - Automatically remembers the track and position for every book, even when choosing a different parent folder containing the same book sub-folders.
   - Resumes saved progress when you open a book again. Within two seconds of a track's end, it advances to the next track or plays the final tail on the last track. Bookmarks keep their exact positions.
   - Completed books keep 100% progress. Pressing play at the end (in the app, notification or headset) restarts the whole book; seeking back after finishing clears Finished and continues from there.
   - Marking the loaded book finished pauses it and clears the queue and media notification, keeping the book visible in the app; one press of Play restarts it. Marking it unplayed pauses it and moves to the start.
   - After an app restart, the last book appears paused immediately. Headset/Bluetooth Play can resume it after process death, using the latest saved position (saved on pause, seek, and every five seconds). Finished books restart in one press, including after marking them finished.
   - Folder access and the starting track are checked before cold playback; unavailable books stay paused until access is restored.
   - Stored 100% privately on your device using Android SharedPreferences and a local last-book snapshot.
   - **Backup and restore**: the library's ⋮ menu saves progress, finished flags and bookmarks to a JSON file you choose, and restores them from one (for example after reinstalling or on a new phone, once the audiobook folder is chosen). Books are matched by folder and file names, so a library moved to an SD card is still found. A restore never moves a book back from a position this phone played more recently, and adds bookmarks without removing any.

6. **Background Playback & Media3 Media Controls**
   - Background playback with notification and lock-screen controls for play/pause, configured skip intervals and chapter navigation (or -1m/+1m when there is only one chapter; button visibility depends on Android/device).
   - Supports headset/Bluetooth media buttons and auto-pauses on headphone disconnect and calls. Headset next/previous keys follow chapters first, then files, then configured skips for a single file without chapters. Fast-forward/rewind keys always use the configured intervals.

7. **100% Private & Pure Offline**
   - Zero internet permissions in the Android Manifest.
   - App data is not included in Android cloud backup; folder access grants are device-specific. Use Backup and restore (above) to move listening data yourself.
   - No tracking, no analytics, no accounts, and no cloud dependencies.
   - Light, dark, and system-adaptive Material Design 3 themes.

---

## Chapters

Chapters are read locally from M4B/M4A QuickTime chapter tracks, Nero `chpl` metadata, and MP3 ID3 CHAP entries. Files without embedded chapters contribute one chapter using the file title, so multi-file books work immediately.

Open **Chapters** beside the current chapter title to see titles, start times relative to the whole book, and durations (`--:--` when unknown). The current chapter is highlighted and brought into view; tap a row to play from its start. The current title also appears in the mini-player and notification.

**Previous** restarts the current chapter when more than three seconds into it; otherwise it goes to the previous chapter. **Next** goes to the next chapter, including across files, and does nothing on the final chapter. Notification overflow shows Previous chapter/Next chapter when available, otherwise -1m/+1m; the configured skip buttons remain. Headset priority is **chapters > files > skips**.

Chapter metadata is cached privately per file, including files with no embedded chapters. Size or modification time changes invalidate the cache, and library scans prune removed files. All extraction and caching stays offline.

Nero `chpl` chapters use a fast read; files with no chapters or exactly 255 chapters on that pass fall back to a full sample-table read (about 3.7 MB of memory per hour of audio for long QuickTime-only M4Bs), once per file before caching.

## How to Open and Run (for Beginners)

To build from the command line with JDK 17+, run `./gradlew assembleDebug` from the project's root folder.

1. **Open in Android Studio**:
   - Launch **Android Studio** (Giraffe, Hedgehog, Iguana, Jellyfish, Ladybug or newer).
   - Click **Open** and select this project's root folder.
   - Allow Gradle to finish syncing (this usually takes 1–2 minutes on first load).

2. **Run on a Phone or Emulator**:
   - Connect your Android phone with USB debugging enabled, or start an Android Emulator from the Device Manager.
   - Click the green **Run (▶)** button in the top toolbar of Android Studio.

3. **Using the App**:
   - Copy your audiobook files (`.mp3`, `.m4a`, `.aac`, etc.) into a folder on your phone (e.g., `Download` or `Audiobooks`), organized directly or inside sub-folders.
   - Open the app and tap **Choose Audiobook Folder**.
   - Select your folder and tap **"Use this folder"** (and grant permission).
   - Tap any book in your library to start listening!
