package com.janreins.audiobook

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.janreins.audiobook.data.FolderAccess
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.janreins.audiobook.data.ChapterRepository
import com.janreins.audiobook.data.AudiobookRepository
import com.janreins.audiobook.data.BookStatus
import com.janreins.audiobook.data.LibraryStateStore
import com.janreins.audiobook.data.LibrarySort
import com.janreins.audiobook.data.LibraryFilter
import com.janreins.audiobook.data.ListeningBackup
import com.janreins.audiobook.data.ListeningBackupStore
import com.janreins.audiobook.data.model.BookProgress
import com.janreins.audiobook.data.ResumeSnapshotStore
import com.janreins.audiobook.data.ProgressMigration
import com.janreins.audiobook.data.PreferencesManager
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.data.model.Bookmark
import com.janreins.audiobook.data.model.SleepTimerOption
import com.janreins.audiobook.player.BookChapter
import com.janreins.audiobook.player.AudiobookPlayerManager
import com.janreins.audiobook.ui.theme.AppThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen destinations for the single-activity navigation flow.
 */
enum class AppScreen {
    FOLDER_SELECTION,
    LIBRARY,
    PLAYER
}

/**
 * Main ViewModel managing state, folder scanning, preferences, and player coordination.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AudiobookRepository(application)
    private val prefs = PreferencesManager(application)
    private val libraryState = LibraryStateStore(application)
    val libraryVersion = libraryState.libraryVersion
    private val _librarySort = MutableStateFlow(libraryState.getSort())
    val librarySort = _librarySort.asStateFlow()
    private val _libraryFilter = MutableStateFlow(libraryState.getFilter())
    val libraryFilter = _libraryFilter.asStateFlow()

    fun setLibrarySort(sort: LibrarySort) {
        libraryState.setSort(sort)
        _librarySort.value = sort
    }

    fun setLibraryFilter(filter: LibraryFilter) {
        libraryState.setFilter(filter)
        _libraryFilter.value = filter
    }

    fun getBookStatus(book: Audiobook): BookStatus {
        val finished = libraryState.isFinished(book.id)
        val saved = prefs.getBookProgress(book.id)
        val progress = if (finished) 1f else if (book.durationMs > 0L) {
            (getSavedPosition(book.id).toFloat() / book.durationMs).coerceIn(0f, 1f)
        } else 0f
        val started = finished || progress > 0f || saved.positionMs > 0L || saved.trackIndex > 0
        return BookStatus(progress, finished, libraryState.getLastPlayedAt(book.id), started)
    }

    fun markFinished(book: Audiobook) {
        val progress = AudiobookPlayerManager.finishedProgress(book)
        prefs.saveBookProgress(book.id, progress)
        libraryState.setFinished(book.id, true)
        AudiobookPlayerManager.applyFinished(book.id, progress)
    }

    /** Resets progress to the start; a loaded book is paused and moved to the start too. Bookmarks stay. */
    fun markUnplayed(book: Audiobook) {
        prefs.saveBookProgress(book.id, BookProgress())
        AudiobookPlayerManager.applyStoredProgress(book.id, BookProgress())
        libraryState.setFinished(book.id, false)
    }

    private val _themeMode = MutableStateFlow(prefs.getThemeMode())
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _currentScreen = MutableStateFlow(AppScreen.FOLDER_SELECTION)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _folderMessage = MutableStateFlow<String?>(null)
    val folderMessage: StateFlow<String?> = _folderMessage.asStateFlow()

    private val _folderUri = MutableStateFlow<Uri?>(null)
    val folderUri: StateFlow<Uri?> = _folderUri.asStateFlow()

    private val _audiobooks = MutableStateFlow<List<Audiobook>>(emptyList())
    val audiobooks: StateFlow<List<Audiobook>> = _audiobooks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentBookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val currentBookmarks: StateFlow<List<Bookmark>> = _currentBookmarks.asStateFlow()

    private val _skipBackSeconds = MutableStateFlow(prefs.getSkipBackSeconds())
    val skipBackSeconds = _skipBackSeconds.asStateFlow()
    private val _skipForwardSeconds = MutableStateFlow(prefs.getSkipForwardSeconds())
    val skipForwardSeconds = _skipForwardSeconds.asStateFlow()
    private val _smartRewindEnabled = MutableStateFlow(prefs.getSmartRewindEnabled())
    val smartRewindEnabled = _smartRewindEnabled.asStateFlow()
    private val _sleepFadeOut = MutableStateFlow(prefs.getSleepFadeOut())
    val sleepFadeOut = _sleepFadeOut.asStateFlow()

    fun setSkipBackSeconds(seconds: Int) {
        prefs.saveSkipBackSeconds(seconds)
        _skipBackSeconds.value = prefs.getSkipBackSeconds()
    }
    fun setSkipForwardSeconds(seconds: Int) {
        prefs.saveSkipForwardSeconds(seconds)
        _skipForwardSeconds.value = prefs.getSkipForwardSeconds()
    }
    fun setSmartRewindEnabled(enabled: Boolean) {
        prefs.saveSmartRewindEnabled(enabled)
        _smartRewindEnabled.value = enabled
    }
    fun setSleepFadeOut(enabled: Boolean) {
        prefs.saveSleepFadeOut(enabled)
        _sleepFadeOut.value = enabled
    }
    fun extendSleepTimer() = AudiobookPlayerManager.extendSleepTimer()
    // Delegate player states from AudiobookPlayerManager
    val currentPlayingBook = AudiobookPlayerManager.currentBook
    val isPlaying = AudiobookPlayerManager.isPlaying
    val currentPositionMs = AudiobookPlayerManager.currentPositionMs
    val chapters = AudiobookPlayerManager.chapters
    val currentChapter = AudiobookPlayerManager.currentChapter
    fun jumpToChapter(chapter: BookChapter) = AudiobookPlayerManager.jumpToChapter(chapter)
    val currentTrackIndex = AudiobookPlayerManager.currentTrackIndex
    val trackCount = AudiobookPlayerManager.trackCount
    val durationMs = AudiobookPlayerManager.durationMs
    val playbackSpeed = AudiobookPlayerManager.playbackSpeed
    val activeSleepOption = AudiobookPlayerManager.activeSleepOption
    val sleepTimerRemainingSeconds = AudiobookPlayerManager.sleepTimerRemainingSeconds
    val errorMessage = AudiobookPlayerManager.errorMessage

    init {
        AudiobookPlayerManager.initialize(application)
        checkSavedFolder()
        if (prefs.consumeResumeUnavailable()) _folderMessage.value = "Last book unavailable"
    }

    /**
     * Checks if a folder was previously selected and persisted.
     */
    private fun checkSavedFolder() {
        val savedFolderString = prefs.getFolderUri()
        if (!savedFolderString.isNullOrBlank()) {
            val uri = Uri.parse(savedFolderString)
            if (!FolderAccess.hasPersistedReadAccess(getApplication<Application>().contentResolver, uri)) {
                prefs.saveFolderUri(null)
                _folderMessage.value = "Access to your audiobook folder was lost. Please choose the folder again."
                _currentScreen.value = AppScreen.FOLDER_SELECTION
                return
            }
            _folderUri.value = uri
            _currentScreen.value = AppScreen.LIBRARY
            loadAudiobooks(uri)
        } else {
            _currentScreen.value = AppScreen.FOLDER_SELECTION
        }
    }

    /**
     * Handles user folder selection from the SAF picker.
     */
    fun onFolderSelected(context: Context, uri: Uri) {
        try {
            // Persist read permissions so folder remains accessible across app restarts
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            Log.w("MainViewModel", "Could not take persistable URI permission: ${e.message}")
            _folderMessage.value = "Could not keep access to this folder. Please choose the folder again."
            _currentScreen.value = AppScreen.FOLDER_SELECTION
            return
        }

        context.contentResolver.persistedUriPermissions.forEach { permission ->
            if (permission.uri != uri && DocumentsContract.isTreeUri(permission.uri)) {
                val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                try {
                    context.contentResolver.releasePersistableUriPermission(permission.uri, flags)
                } catch (e: SecurityException) {
                    Log.w("MainViewModel", "Could not release old folder permission", e)
                }
            }
        }
        ResumeSnapshotStore(context).clear()
        _folderMessage.value = null
        prefs.saveFolderUri(uri.toString())
        _folderUri.value = uri
        _currentScreen.value = AppScreen.LIBRARY
        loadAudiobooks(uri)
    }

    /**
     * Scans and loads audio files from the given folder URI (including sub-folders).
     */
    fun loadAudiobooks(uri: Uri? = _folderUri.value) {
        if (uri == null) return
        viewModelScope.launch {
            _isLoading.value = true
            val scan = repository.loadLibrary(uri)
            val books = scan.books
            books.forEach { book ->
                ProgressMigration.migrate(book, prefs::hasBookProgress, prefs::getPlaybackPosition,
                    prefs::saveBookProgress, prefs::getBookmarks, prefs::saveBookmarks,
                    prefs::isMigrated, prefs::markMigrated, prefs::getLastPlayedBookId, prefs::saveLastPlayedBookId)
            }
            // Ignore a result from a folder that was replaced while the scan was running.
            if (_folderUri.value != null && _folderUri.value != uri) {
                _isLoading.value = false
                return@launch
            }
            AudiobookPlayerManager.reconcileLibrary(books, scan.succeeded)
            _audiobooks.value = books
            _isLoading.value = false

            // If we have a last played book, update bookmarks for it
            val lastPlayedId = prefs.getLastPlayedBookId()
            if (lastPlayedId != null && AudiobookPlayerManager.currentBook.value == null) {
                val matchingBook = books.find { it.id == lastPlayedId }
                if (matchingBook != null) {
                    _currentBookmarks.value = prefs.getBookmarks(matchingBook.id)
                }
            }
        }
    }

    /**
     * Opens player for the selected book and begins playback (or resumes from saved point).
     */
    fun playBook(context: Context, book: Audiobook) {
        AudiobookPlayerManager.playBook(context, book)
        _currentBookmarks.value = prefs.getBookmarks(book.id)
        _currentScreen.value = AppScreen.PLAYER
    }

    /**
     * Navigates to the Player screen from the mini-player bar.
     */
    fun openPlayer() {
        val book = AudiobookPlayerManager.currentBook.value
        if (book != null) {
            ChapterRepository.ensureLoaded(book)
            _currentBookmarks.value = prefs.getBookmarks(book.id)
            _currentScreen.value = AppScreen.PLAYER
        }
    }

    fun navigateBackToLibrary() {
        _currentScreen.value = AppScreen.LIBRARY
    }

    fun navigateToFolderSelection() {
        _currentScreen.value = AppScreen.FOLDER_SELECTION
    }

    fun togglePlayPause(context: Context) {
        AudiobookPlayerManager.togglePlayPause(context)
    }

    fun seekTo(positionMs: Long) {
        AudiobookPlayerManager.seekTo(positionMs)
    }

    fun previous() = AudiobookPlayerManager.previous()
    fun next() = AudiobookPlayerManager.next()

    fun skipBackward() {
        AudiobookPlayerManager.skip(-_skipBackSeconds.value * 1000L)
    }

    fun skipBackward1m() {
        AudiobookPlayerManager.skip(-60_000L)
    }

    fun skipForward() {
        AudiobookPlayerManager.skip(_skipForwardSeconds.value * 1000L)
    }

    fun skipForward1m() {
        AudiobookPlayerManager.skip(60_000L)
    }

    fun setPlaybackSpeed(speed: Float) {
        AudiobookPlayerManager.setPlaybackSpeed(speed)
    }

    fun setSleepTimer(option: SleepTimerOption, context: Context) {
        AudiobookPlayerManager.setSleepTimer(option, context)
    }

    /** [trackIndex] and [positionMs] are where the user tapped MARK, not where playback is now. */
    fun addBookmark(title: String, trackIndex: Int, positionMs: Long) {
        val currentBook = AudiobookPlayerManager.currentBook.value ?: return
        val bookmark = Bookmark(
            id = System.currentTimeMillis().toString(),
            audiobookId = currentBook.id,
            positionMs = positionMs.coerceAtLeast(0L),
            title = title,
            trackIndex = trackIndex.coerceIn(0, currentBook.trackCount - 1)
        )
        prefs.addBookmark(bookmark)
        _currentBookmarks.value = prefs.getBookmarks(currentBook.id)
    }

    fun selectBookmark(context: Context, bookmark: Bookmark) {
        val book = AudiobookPlayerManager.currentBook.value?.takeIf { it.id == bookmark.audiobookId }
            ?: _audiobooks.value.find { it.id == bookmark.audiobookId } ?: return
        AudiobookPlayerManager.playBook(context, book, bookmark.positionMs, bookmark.trackIndex)
        _currentBookmarks.value = prefs.getBookmarks(book.id)
    }

    fun deleteBookmark(bookmark: Bookmark) {
        prefs.deleteBookmark(bookmark.audiobookId, bookmark.id)
        _currentBookmarks.value = prefs.getBookmarks(bookmark.audiobookId)
    }

    fun getSavedPosition(bookId: String): Long {
        val progress = prefs.getBookProgress(bookId)
        val book = _audiobooks.value.find { it.id == bookId } ?: return progress.positionMs
        val index = progress.trackIndex.coerceIn(0, book.trackCount - 1)
        return book.tracks.take(index).sumOf { it.durationMs } + progress.positionMs
    }

    /** Writes progress, finished flags and bookmarks to [uri], a file the user created in the system picker. */
    fun exportListeningData(uri: Uri) {
        val app = getApplication<Application>()
        AudiobookPlayerManager.saveCurrentPosition()
        val backup = ListeningBackupStore(app).export(_audiobooks.value)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(uri, "w")!!.use { it.write(backup.toJson().toByteArray(Charsets.UTF_8)) }
                }.onFailure { Log.w("MainViewModel", "Backup failed", it) }.isSuccess
            }
            _folderMessage.value = if (saved) "Backup saved: progress and bookmarks for ${books(backup.books.size)}."
                else "Could not save the backup file. Please try another location."
        }
    }

    /** Reads a backup made by [exportListeningData] and merges it into this phone's listening data. */
    fun importListeningData(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val backup = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)!!.use { input ->
                        val bytes = input.readNBytesCompat(MAX_BACKUP_BYTES + 1)
                        if (bytes.size > MAX_BACKUP_BYTES) null else ListeningBackup.fromJson(bytes.toString(Charsets.UTF_8))
                    }
                }.getOrNull()
            }
            if (backup == null) {
                _folderMessage.value = "This file isn't an Audiobook backup, so nothing was restored."
                return@launch
            }
            AudiobookPlayerManager.saveCurrentPosition()
            val summary = ListeningBackupStore(app).restore(backup, _audiobooks.value,
                AudiobookPlayerManager::applyStoredProgress)
            AudiobookPlayerManager.currentBook.value?.let { _currentBookmarks.value = prefs.getBookmarks(it.id) }
            _folderMessage.value = "Restored ${books(summary.restored)}." + if (summary.notInLibrary > 0)
                " ${books(summary.notInLibrary).replaceFirstChar { it.uppercase() }} from the backup aren't in this folder." else ""
        }
    }

    private fun books(count: Int) = if (count == 1) "1 book" else "$count books"

    fun dismissFolderMessage() { _folderMessage.value = null }

    fun dismissError() {
        AudiobookPlayerManager.clearErrorMessage()
    }

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        prefs.saveThemeMode(mode)
    }

    private companion object {
        const val MAX_BACKUP_BYTES = 5 * 1024 * 1024
    }
}

/** InputStream.readNBytes needs API 33; minSdk is 26. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
