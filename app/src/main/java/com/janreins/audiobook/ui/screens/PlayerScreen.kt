package com.janreins.audiobook.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import com.janreins.audiobook.player.BookChapter
import com.janreins.audiobook.player.ChapterIndex
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ModalBottomSheet
import com.janreins.audiobook.data.AudiobookRepository
import com.janreins.audiobook.player.SpeedSteps
import com.janreins.audiobook.ui.components.PlaybackSpeedDialog
import com.janreins.audiobook.ui.components.PlaybackSettingsDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.janreins.audiobook.data.model.Audiobook
import com.janreins.audiobook.data.model.Bookmark
import com.janreins.audiobook.data.model.SleepTimerOption
import com.janreins.audiobook.ui.components.AddBookmarkDialog
import com.janreins.audiobook.ui.components.BookmarkBottomSheet
import com.janreins.audiobook.ui.components.BookCover
import com.janreins.audiobook.ui.components.PlayerControls
import com.janreins.audiobook.ui.components.SeekBarWithTime
import com.janreins.audiobook.ui.components.SleepTimerDialog
import com.janreins.audiobook.ui.components.ThemeSelectionDialog
import com.janreins.audiobook.ui.theme.AppThemeMode

/**
 * Clean Minimalism Player Screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    audiobook: Audiobook,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    activeSleepOption: SleepTimerOption,
    sleepTimerRemainingSeconds: Int?,
    bookmarks: List<Bookmark>,
    errorMessage: String?,
    currentThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
    onSelectThemeMode: (AppThemeMode) -> Unit = {},
    onBackClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBackward15s: () -> Unit,
    onSkipBackward1m: () -> Unit,
    onSkipForward15s: () -> Unit,
    onSkipForward1m: () -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetSleepTimer: (SleepTimerOption) -> Unit,
    onAddBookmark: (title: String, trackIndex: Int, positionMs: Long) -> Unit,
    onSelectBookmark: (Bookmark) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    currentTrackIndex: Int = 0,
    trackCount: Int = 1,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    skipBackSeconds: Int = 15,
    skipForwardSeconds: Int = 30,
    smartRewindEnabled: Boolean = true,
    sleepFadeOut: Boolean = true,
    onSetSkipBackSeconds: (Int) -> Unit = {},
    onSetSkipForwardSeconds: (Int) -> Unit = {},
    onSetSmartRewindEnabled: (Boolean) -> Unit = {},
    onSetSleepFadeOut: (Boolean) -> Unit = {},
    onExtendSleepTimer: () -> Unit = {},
    chapters: ChapterIndex? = null,
    currentChapter: Int = 0,
    onJumpToChapter: (BookChapter) -> Unit = {}
) {
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showPlaybackSettings by remember { mutableStateOf(false) }
    var showChapterList by remember(audiobook.id) { mutableStateOf(false) }
    val chapterListState = rememberLazyListState()
    var showThemeDialog by remember { mutableStateOf(false) }

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentThemeMode = currentThemeMode,
            onSelectThemeMode = onSelectThemeMode,
            onDismiss = { showThemeDialog = false }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "NOW PLAYING",
                            style = MaterialTheme.typography.labelMedium.copy(
                                letterSpacing = 1.8.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .testTag("player_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to library",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showPlaybackSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Playback settings")
                    }
                    // Theme selector button
                    IconButton(
                        onClick = { showThemeDialog = true },
                        modifier = Modifier.testTag("theme_button_player")
                    ) {
                        Icon(
                            imageVector = when (currentThemeMode) {
                                AppThemeMode.LIGHT -> Icons.Default.LightMode
                                AppThemeMode.DARK -> Icons.Default.DarkMode
                                AppThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                            },
                            contentDescription = "Change theme",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Bookmarks list, with the number of saved bookmarks as a badge
                    IconButton(
                        onClick = { showBookmarksSheet = true },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .testTag("view_bookmarks_button")
                    ) {
                        BadgedBox(
                            badge = {
                                if (bookmarks.isNotEmpty()) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ) {
                                        Text("${bookmarks.size}")
                                    }
                                }
                            },
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = when (bookmarks.size) {
                                    0 -> "Bookmarks"
                                    1 -> "Bookmarks, 1 saved"
                                    else -> "Bookmarks, ${bookmarks.size} saved"
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bookmarks,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Error banner
            AnimatedVisibility(visible = errorMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        IconButton(onClick = onDismissError) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss error",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            BookCover(
                book = audiobook,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.08f)
                    .shadow(elevation = 2.dp, shape = RoundedCornerShape(40.dp)),
                cornerRadius = 40.dp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Book Title & Info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = audiobook.title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground
                )

                if (chapters?.navigable == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(chapters.chapters.getOrNull(currentChapter)?.title.orEmpty(),
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { showChapterList = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Chapters")
                        }
                    }
                }
                if (trackCount > 1) {
                    Text("Track ${currentTrackIndex + 1} of $trackCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = audiobook.fileName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Seek Bar
            SeekBarWithTime(
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                onSeek = onSeek
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (chapters?.navigable == true) {
                Row(horizontalArrangement = Arrangement.Center) {
                    val chapter = chapters.chapters.getOrNull(currentChapter)
                    IconButton(onClick = onPrevious, enabled = currentChapter > 0 || currentPositionMs > (chapter?.startMs ?: 0L)) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter")
                    }
                    IconButton(onClick = onNext, enabled = currentChapter < chapters.chapters.lastIndex) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next chapter")
                    }
                }
            }

            // Playback controls with configured inner skip intervals
            PlayerControls(
                isPlaying = isPlaying,
                onTogglePlayPause = onTogglePlayPause,
                onSkipBackward15s = onSkipBackward15s,
                onSkipBackward1m = onSkipBackward1m,
                onSkipForward15s = onSkipForward15s,
                onSkipForward1m = onSkipForward1m,
                skipBackSeconds = skipBackSeconds,
                skipForwardSeconds = skipForwardSeconds
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Footer Action Dock: Rounded 24dp Pill with Speed, Timer, Bookmark
            val timerMinutesLeft = sleepTimerRemainingSeconds?.takeIf { it > 0 }?.let { (it + 59) / 60 }
            val timerActive = timerMinutesLeft != null || activeSleepOption == SleepTimerOption.END_OF_TRACK
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockItem(
                        label = "SPEED",
                        spokenLabel = "Playback speed, ${SpeedSteps.format(playbackSpeed)}",
                        testTag = "speed_selector_button",
                        onClick = { showSpeedMenu = true }
                    ) {
                        Text(
                            text = SpeedSteps.format(playbackSpeed),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DockDivider()

                    DockItem(
                        label = "TIMER",
                        spokenLabel = when {
                            timerMinutesLeft != null -> "Sleep timer, $timerMinutesLeft min left"
                            activeSleepOption == SleepTimerOption.END_OF_TRACK -> "Sleep timer, end of track"
                            else -> "Sleep timer, off"
                        },
                        highlighted = timerActive,
                        testTag = "quick_sleep_timer_button",
                        onClick = { showSleepTimerDialog = true }
                    ) {
                        when {
                            timerMinutesLeft != null -> Text(
                                text = "${timerMinutesLeft}m",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            activeSleepOption == SleepTimerOption.END_OF_TRACK -> Text(
                                text = "End of track",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            else -> Icon(
                                imageVector = Icons.Default.Bedtime,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DockDivider()

                    DockItem(
                        label = "BOOKMARK",
                        spokenLabel = "Add bookmark",
                        testTag = "add_bookmark_button",
                        onClick = { showAddBookmarkDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    // --- Dialogs & Sheets ---
    if (showSpeedMenu) {
        PlaybackSpeedDialog(playbackSpeed, onSetPlaybackSpeed, onDismiss = { showSpeedMenu = false })
    }
    if (showPlaybackSettings) {
        PlaybackSettingsDialog(skipBackSeconds, skipForwardSeconds, smartRewindEnabled, sleepFadeOut,
            onSetSkipBackSeconds, onSetSkipForwardSeconds, onSetSmartRewindEnabled, onSetSleepFadeOut,
            onDismiss = { showPlaybackSettings = false })
    }
    if (showChapterList && chapters?.navigable == true) {
        LaunchedEffect(Unit) { chapterListState.scrollToItem(currentChapter.coerceIn(chapters.chapters.indices)) }
        ModalBottomSheet(onDismissRequest = { showChapterList = false }) {
            Text("Chapters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(24.dp))
            LazyColumn(Modifier.fillMaxWidth(), state = chapterListState) {
                itemsIndexed(chapters.chapters, key = { index, _ -> index }) { index, chapter ->
                    val current = index == currentChapter
                    Row(Modifier.fillMaxWidth()
                        .background(if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable(onClickLabel = "Play from here") { onJumpToChapter(chapter); showChapterList = false }
                        .semantics { selected = current }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(chapter.title, modifier = Modifier.weight(1f),
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                        val start = audiobook.tracks.take(chapter.trackIndex).sumOf { it.durationMs } + chapter.startMs
                        val duration = if (chapter.endMs < 0) "--:--" else AudiobookRepository.formatDuration(chapter.endMs - chapter.startMs)
                        Text("${AudiobookRepository.formatDuration(start)} • $duration",
                            modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (showAddBookmarkDialog) {
        AddBookmarkDialog(
            currentPositionMs = currentPositionMs,
            currentTrackIndex = currentTrackIndex,
            onDismiss = { showAddBookmarkDialog = false },
            onSaveBookmark = onAddBookmark
        )
    }

    if (showBookmarksSheet) {
        BookmarkBottomSheet(
            bookmarks = bookmarks,
            onDismiss = { showBookmarksSheet = false },
            onSelectBookmark = onSelectBookmark,
            onDeleteBookmark = onDeleteBookmark,
            book = audiobook,
            chapters = chapters
        )
    }

    if (showSleepTimerDialog) {
        SleepTimerDialog(
            currentOption = activeSleepOption,
            remainingSeconds = sleepTimerRemainingSeconds,
            onDismiss = { showSleepTimerDialog = false },
            onSelectOption = onSetSleepTimer,
            fadeOut = sleepFadeOut,
            onSetFadeOut = onSetSleepFadeOut,
            onExtend = onExtendSleepTimer
        )
    }
}

/**
 * One tappable slot of the player's bottom dock: a value or icon on top, a small label below.
 * Screen readers hear [spokenLabel] (for example "Playback speed, 1.2x") as one button.
 */
@Composable
private fun RowScope.DockItem(
    label: String,
    spokenLabel: String,
    testTag: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spokenLabel }
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // The visible value and label are already in spokenLabel, so hide them from screen readers.
        Box(modifier = Modifier.clearAndSetSemantics {}) { content() }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            ),
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DockDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
