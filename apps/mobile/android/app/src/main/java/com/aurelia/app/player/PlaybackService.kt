package com.aurelia.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionError
import com.aurelia.app.MainActivity
import com.aurelia.app.R
import com.aurelia.app.audio.AudioManager
import com.aurelia.app.player.auto.AutoMediaCatalog
import com.aurelia.app.player.auto.AutoMediaLabels
import com.aurelia.app.player.auto.AutoMediaUnavailableException
import com.aurelia.app.player.auto.JellyfinAutoMediaSource
import com.aurelia.app.storage.SessionStore
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
  private var mediaSession: MediaLibrarySession? = null
  private lateinit var notificationManager: NotificationManager
  private lateinit var resumeStore: PlaybackResumeStore
  private var playbackReporting: PlaybackReporting? = null
  private val serviceJob: Job = SupervisorJob()
  private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()
    notificationManager = getSystemService(NotificationManager::class.java)
    ensureNotificationChannel()

    val exoPlayer = ExoPlayer.Builder(this).build()
    val player = TranscodingSeekPlayer(exoPlayer)
    val sessionStore = SessionStore.forApplication(this)
    playbackReporting = PlaybackReporting(player, sessionStore)
    val catalog =
      AutoMediaCatalog(
        source = JellyfinAutoMediaSource(sessionStore),
        labels =
          AutoMediaLabels(
            root = getString(R.string.android_auto_root),
            recentlyPlayed = getString(R.string.android_auto_recently_played),
            albums = getString(R.string.android_auto_albums),
            artists = getString(R.string.android_auto_artists),
            playlists = getString(R.string.android_auto_playlists),
            songs = getString(R.string.android_auto_songs),
            songCount = { count -> resources.getQuantityString(R.plurals.android_auto_song_count, count, count) },
          ),
      )
    resumeStore = PlaybackResumeStore(this)

    player.addListener(
      object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
          if (audioSessionId != 0) {
            AudioManager.initialize(this@PlaybackService, audioSessionId, sessionStore)
          }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
          if (isPlaying) {
            AudioManager.onPlaybackStarted(sessionStore)
          } else {
            AudioManager.onPlaybackStopped(sessionStore)
          }
          saveResumeState(player)
        }

        override fun onMediaItemTransition(
          mediaItem: MediaItem?,
          reason: Int,
        ) {
          saveResumeState(player)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
          if (playbackState == Player.STATE_ENDED) {
            resumeStore.clear()
          }
        }
      },
    )

    mediaSession =
      MediaLibrarySession
        .Builder(this, player, createSessionCallback(catalog))
        .setSessionActivity(mainActivityPendingIntent())
        .build()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

  override fun onTaskRemoved(rootIntent: Intent?) {
    val player = mediaSession?.player
    player?.let(::saveResumeState)
    if (player == null || (!player.playWhenReady && player.mediaItemCount == 0)) {
      stopSelf()
    }
  }

  override fun onDestroy() {
    playbackReporting?.close()
    mediaSession?.player?.let(::saveResumeState)
    serviceScope.cancel()
    AudioManager.release()
    mediaSession?.run {
      player.release()
      release()
    }
    mediaSession = null
    super.onDestroy()
  }

  override fun onUpdateNotification(
    session: MediaSession,
    startInForegroundRequired: Boolean,
  ) {
    val notification = buildNotification(session)
    if (startInForegroundRequired) {
      startForeground(PlaybackNotificationIds.SERVICE, notification)
    } else {
      notificationManager.notify(PlaybackNotificationIds.SERVICE, notification)
    }
  }

  private fun buildNotification(session: MediaSession): Notification {
    val metadata = session.player.mediaMetadata
    val title = metadata.title?.toString() ?: getString(R.string.playback_notification_title)
    val artist = metadata.artist?.toString() ?: getString(R.string.playback_notification_artist)

    return NotificationCompat
      .Builder(this, PlaybackNotificationIds.CHANNEL)
      .setContentTitle(title)
      .setContentText(artist)
      .setSmallIcon(R.drawable.ic_stat_music_note)
      .setContentIntent(mainActivityPendingIntent())
      .setOngoing(session.player.playWhenReady)
      .setOnlyAlertOnce(true)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setStyle(MediaStyleNotificationHelper.MediaStyle(session))
      .build()
  }

  private fun ensureNotificationChannel() {
    val channel =
      NotificationChannel(
        PlaybackNotificationIds.CHANNEL,
        getString(R.string.playback_notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      )
    notificationManager.createNotificationChannel(channel)
  }

  private fun createSessionCallback(catalog: AutoMediaCatalog): MediaLibrarySession.Callback =
    object : MediaLibrarySession.Callback {
      override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
      ): MediaSession.ConnectionResult {
        if (controller.packageName != packageName && !controller.isTrusted) {
          return MediaSession.ConnectionResult.reject()
        }
        return MediaSession.ConnectionResult
          .AcceptedResultBuilder(session)
          .setAvailablePlayerCommands(
            Player.Commands
              .Builder()
              .addAllCommands()
              .build(),
          ).setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
          .build()
      }

      override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
      ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(catalog.root(), params))

      override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
      ): ListenableFuture<LibraryResult<MediaItem>> =
        serviceFuture {
          try {
            catalog.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
              ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
          } catch (error: Exception) {
            libraryFailure(error)
          }
        }

      override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
      ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        serviceFuture {
          try {
            catalog.children(parentId, page, pageSize)?.let { LibraryResult.ofItemList(it, params) }
              ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
          } catch (error: Exception) {
            libraryFailure(error, params)
          }
        }

      override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
      ): ListenableFuture<LibraryResult<Void>> =
        serviceFuture {
          try {
            val resultCount = catalog.search(query, 0, Int.MAX_VALUE).size
            session.notifySearchResultChanged(browser, query, resultCount, params)
            LibraryResult.ofVoid(params)
          } catch (error: Exception) {
            libraryFailure(error, params)
          }
        }

      override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
      ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        serviceFuture {
          try {
            LibraryResult.ofItemList(catalog.search(query, page, pageSize), params)
          } catch (error: Exception) {
            libraryFailure(error, params)
          }
        }

      override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
      ): ListenableFuture<List<MediaItem>> =
        serviceFuture {
          catalog.resolveItems(mediaItems).ifEmpty {
            throw IllegalArgumentException("No playable media items were found")
          }
        }

      override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
      ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        serviceFuture {
          if (mediaItems.size == 1) {
            val resolved =
              catalog.resolveSelection(mediaItems.first())
                ?: throw IllegalArgumentException("The selected item is not playable")
            MediaSession.MediaItemsWithStartPosition(
              resolved.mediaItems,
              resolved.startIndex,
              startPositionMs,
            )
          } else {
            val resolvedItems = catalog.resolveItems(mediaItems)
            if (resolvedItems.isEmpty()) throw IllegalArgumentException("No playable media items were found")
            MediaSession.MediaItemsWithStartPosition(
              resolvedItems,
              startIndex.coerceIn(0, resolvedItems.lastIndex),
              startPositionMs,
            )
          }
        }

      override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
      ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        serviceFuture {
          val state = resumeStore.load() ?: throw IllegalStateException("No playback state is available")
          val requested = MediaItem.Builder().setMediaId(state.mediaId).build()
          val resolved =
            catalog.resolveSelection(requested)
              ?: throw IllegalStateException("The previous song is no longer available")
          MediaSession.MediaItemsWithStartPosition(
            resolved.mediaItems,
            resolved.startIndex,
            if (isForPlayback) state.positionMs else C.TIME_UNSET,
          )
        }
    }

  private fun mainActivityPendingIntent(): PendingIntent =
    PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  private fun saveResumeState(player: Player) {
    if (player.playbackState == Player.STATE_ENDED) {
      resumeStore.clear()
      return
    }
    val mediaId = player.currentMediaItem?.mediaId ?: return
    resumeStore.save(mediaId, player.currentPosition)
  }

  private fun <T> serviceFuture(block: suspend () -> T): ListenableFuture<T> {
    val future = SettableFuture.create<T>()
    val job =
      serviceScope.launch {
        try {
          future.set(block())
        } catch (error: Throwable) {
          future.setException(error)
        }
      }
    future.addListener(
      { if (future.isCancelled) job.cancel() },
      MoreExecutors.directExecutor(),
    )
    return future
  }

  private fun <T : Any> libraryFailure(
    error: Exception,
    params: LibraryParams? = null,
  ): LibraryResult<T> {
    val sessionError =
      if (error is AutoMediaUnavailableException) {
        val code =
          when (error.reason) {
            AutoMediaUnavailableException.Reason.SIGNED_OUT -> SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
            AutoMediaUnavailableException.Reason.LOAD_FAILED -> SessionError.ERROR_IO
          }
        SessionError(code, error.message ?: getString(R.string.android_auto_load_error))
      } else {
        SessionError(SessionError.ERROR_UNKNOWN, getString(R.string.android_auto_load_error))
      }
    return if (params == null) {
      LibraryResult.ofError<T>(sessionError)
    } else {
      LibraryResult.ofError<T>(sessionError, params)
    }
  }
}

private object PlaybackNotificationIds {
  const val CHANNEL = "aurelia_playback"
  const val SERVICE = 2001
}
