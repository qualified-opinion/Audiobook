package com.janreins.audiobook.player

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import com.janreins.audiobook.data.PreferencesManager
import com.janreins.audiobook.data.LibraryStateStore
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.DefaultMediaNotificationProvider
import com.janreins.audiobook.R
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.janreins.audiobook.data.ChapterRepository
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.janreins.audiobook.MainActivity

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var coverBitmapLoader: CoverBitmapLoader? = null
    private var prefs: PreferencesManager? = null
    // Held strongly: SharedPreferences only keeps weak references to listeners.
    private val skipLabelListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PreferencesManager.KEY_SKIP_BACK_SECONDS || key == PreferencesManager.KEY_SKIP_FORWARD_SECONDS) {
            val p = prefs ?: return@OnSharedPreferenceChangeListener
            session?.setMediaButtonPreferences(mediaButtons(p))
            sessionPlayer?.invalidate()
        }
    }
    private var sessionPlayer: SessionPlayer? = null
    private var endOfTrackController: EndOfTrackController? = null
    private val resumeExecutor = Executors.newSingleThreadExecutor()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        val REWIND_60 = SessionCommand("com.janreins.audiobook.REWIND_60", Bundle.EMPTY)
        val END_OF_TRACK = SessionCommand("com.janreins.audiobook.END_OF_TRACK", Bundle.EMPTY)
        val FORWARD_60 = SessionCommand("com.janreins.audiobook.FORWARD_60", Bundle.EMPTY)
    }

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(object : DefaultMediaNotificationProvider(this) {
            override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? =
                metadata.subtitle ?: metadata.artist
        }.apply {
            setSmallIcon(R.drawable.ic_stat_audiobook)
        })
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(15_000)
            .build()
        val prefs = PreferencesManager(this).also { this.prefs = it }
        prefs.registerChangeListener(skipLabelListener)
        ChapterRepository.init(this)
        val libraryState = LibraryStateStore(this)
        val sessionPlayer = SessionPlayer(player, object : SessionSettings {
            override fun skipBackMs() = prefs.getSkipBackSeconds() * 1000L
            override fun skipForwardMs() = prefs.getSkipForwardSeconds() * 1000L
            override fun smartRewindEnabled() = prefs.getSmartRewindEnabled()
            override fun isFinished(bookId: String) = libraryState.isFinished(bookId)
        }, { ChapterRepository.indexes.value[it] }).also { this.sessionPlayer = it }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                session?.setMediaButtonPreferences(mediaButtons(prefs))
            }
        })
        val resumption = PlaybackResumption.forContext(applicationContext, resumeExecutor)
        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val coverLoader = CoverBitmapLoader(this).also { coverBitmapLoader = it }
        session = MediaSession.Builder(this, sessionPlayer)
            // MediaSession wraps this in CacheBitmapLoader; the folder cover travels in extras so that
            // cache never matches it against later embedded art (see playBook).
            .setBitmapLoader(coverLoader)
            .setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onPlaybackResumption(
                    session: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val future = resumption.resume(isForPlayback)
                    if (!isForPlayback) return future
                    return Futures.transform(future, { items ->
                        session.player.setPlaybackSpeed(prefs.getPlaybackSpeed())
                        // Never connect a controller synchronously inside the resumption request.
                        Handler(Looper.getMainLooper()).post {
                            AudiobookPlayerManager.initialize(applicationContext)
                        }
                        requireNotNull(items)
                    }, ContextCompat.getMainExecutor(this@PlaybackService))
                }

                override fun onConnect(
                    session: MediaSession, controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val defaults = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(defaults.availableSessionCommands.buildUpon()
                            .add(REWIND_60).add(FORWARD_60).add(END_OF_TRACK).build())
                        .build()
                }

                override fun onMediaButtonEvent(
                    session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent
                ): Boolean {
                    val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        ?: return false
                    val direction = MediaKeySkips.direction(event.keyCode, session.player.mediaItemCount,
                        currentChapterIndex()?.chapters?.size ?: 1)
                        ?: return false
                    // Consume both key-down and key-up; act once on the first key-down.
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        if (direction < 0) session.player.seekBack() else session.player.seekForward()
                    }
                    return true
                }

                override fun onCustomCommand(
                    session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == END_OF_TRACK.customAction) {
                        endOfTrackController?.setEnabled(args.getBoolean("enabled", false))
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    val delta = when (customCommand.customAction) {
                        REWIND_60.customAction -> -60_000L
                        FORWARD_60.customAction -> 60_000L
                        else -> return super.onCustomCommand(session, controller, customCommand, args)
                    }
                    session.player.run {
                        seekTo(SeekMath.clampSeek(currentPosition, delta, duration))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            // Keep configured skips in the primary slots; navigation goes in the overflow.
            .setMediaButtonPreferences(mediaButtons(prefs))
            .build()
        // ExoPlayer owns pauseAtEndOfMediaItems; controllers mirror the published session extras.
        endOfTrackController = EndOfTrackController(player, { player.pauseAtEndOfMediaItems = it }, prefs, { enabled ->
            session?.setSessionExtras(Bundle().apply {
                putBoolean(EndOfTrackController.EXTRA_END_OF_TRACK, enabled)
            })
        })
        serviceScope.launch {
            ChapterRepository.indexes.collect {
                sessionPlayer.invalidate()
                session?.setMediaButtonPreferences(mediaButtons(prefs))
            }
        }
        // Chapter title in the notification: tick only while playing (seeks invalidate via player events).
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                chapterTicker?.cancel()
                chapterTicker = if (isPlaying) serviceScope.launch {
                    var lastChapter: Pair<String?, Int?>? = null
                    while (isActive) {
                        val bookId = player.currentMediaItem?.mediaId?.substringBeforeLast("#")
                        val chapter = bookId to currentChapterIndex()?.indexAt(player.currentMediaItemIndex, player.currentPosition)
                        if (chapter != lastChapter) {
                            lastChapter = chapter
                            sessionPlayer.invalidate()
                        }
                        delay(1000)
                    }
                } else null
            }
        })
    }

    private var chapterTicker: Job? = null

    private fun currentChapterIndex(): ChapterIndex? = session?.player?.currentMediaItem?.mediaId
        ?.substringBeforeLast("#")?.let { ChapterRepository.indexes.value[it] }

    // Keep configured skips in the primary slots; navigation goes in the overflow.
    private fun mediaButtons(prefs: PreferencesManager) = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setDisplayName("-${prefs.getSkipBackSeconds()}s").setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK).build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setDisplayName("+${prefs.getSkipForwardSeconds()}s").setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD).build()
    ) + if (currentChapterIndex()?.navigable == true) listOf(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName("Previous chapter").setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
            .setSlots(CommandButton.SLOT_OVERFLOW).build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName("Next chapter").setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
            .setSlots(CommandButton.SLOT_OVERFLOW).build()
    ) else listOf(
        CommandButton.Builder(CommandButton.ICON_REWIND)
            .setDisplayName("-1m").setSessionCommand(REWIND_60)
            .setSlots(CommandButton.SLOT_OVERFLOW).build(),
        CommandButton.Builder(CommandButton.ICON_FAST_FORWARD)
            .setDisplayName("+1m").setSessionCommand(FORWARD_60)
            .setSlots(CommandButton.SLOT_OVERFLOW).build()
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.isPlaying || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        resumeExecutor.shutdownNow()
        prefs?.unregisterChangeListener(skipLabelListener)
        endOfTrackController?.release()
        endOfTrackController = null
        session?.run { player.release(); release() }
        session = null
        coverBitmapLoader?.release()
        coverBitmapLoader = null
        super.onDestroy()
    }
}
