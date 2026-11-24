package com.live.tv.music.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.live.tv.music.MusicActivity
import com.live.tv.music.catalog.GdMusicCatalog
import com.live.tv.music.catalog.MusicRepository
import com.live.tv.music.network.SmartisaxNetwork

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private lateinit var sessionStore: PlaybackSessionStore
    private val progressHandler = Handler(Looper.getMainLooper())
    private val persistProgress = object : Runnable {
        override fun run() {
            persistSession()
            progressHandler.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val catalog = GdMusicCatalog(SmartisaxNetwork.catalogClient)
        val repository = MusicRepository.get(this)
        val streamUrlResolver = GdStreamUrlResolver(catalog, offlineAudio = { id ->
            repository.audioFile(id)?.let(Uri::fromFile)
        })
        val httpDataSourceFactory = OkHttpDataSource.Factory(SmartisaxNetwork.streamingClient)
            .setUserAgent("SmartisaxMusic/0.1")
        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, httpDataSourceFactory), streamUrlResolver,
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(GdTemporaryUrlRetryPolicy(streamUrlResolver))
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        sessionStore = PlaybackSessionStore(getSharedPreferences("music-playback", MODE_PRIVATE))
        sessionStore.restore()?.let { saved ->
            player.setMediaItem(PlaybackMediaItemFactory.create(saved.selection), saved.positionMillis)
            player.playWhenReady = false
            player.prepare()
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!MusicForeground.active && player.playWhenReady) player.pause()
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED,
                    )) persistSession()
            }
        })

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MusicActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(VoiceSessionCallback(sessionScope, VoiceMediaResolver(repository::search)))
            .build()
        progressHandler.postDelayed(persistProgress, 2_000L)
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = if (controllerInfo.packageName == packageName) mediaSession else null

    override fun onDestroy() {
        sessionScope.cancel()
        progressHandler.removeCallbacksAndMessages(null)
        persistSession()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun persistSession() {
        if (!::sessionStore.isInitialized) return
        mediaSession?.player?.let { sessionStore.save(it.currentMediaItem, it.currentPosition) }
    }
}
