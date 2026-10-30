package com.songrater

import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the player, the shuffled queue and the "rate then move" logic, so playback continues with the
 * screen off. The activity only displays [UiState] and forwards button presses.
 * All fields are touched on the main thread only; folder and tag work runs on [io] and posts back.
 */
@OptIn(UnstableApi::class)
class PlayerService : MediaSessionService() {

    companion object {
        const val ACTION_LOCAL = "com.songrater.LOCAL_BIND"
    }

    data class UiState(
        val artist: String,
        val album: String,
        val title: String,
        val stars: Int,
        val previouslyRated: Boolean,
        val playing: Boolean,
        val hasTrack: Boolean,
        val loaded: Boolean,
        val setupNeeded: Boolean,
        val remaining: Int,
        val message: String?
    )

    interface StateListener {
        fun onState(state: UiState)
    }

    inner class LocalBinder : Binder() {
        fun service(): PlayerService = this@PlayerService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var exo: ExoPlayer
    private var session: MediaSession? = null
    private var listener: StateListener? = null

    private var all: MutableList<Track> = mutableListOf()      // every song currently in the source folder
    private val order = mutableListOf<Track>()                 // songs still to come in this shuffle
    private val moving = mutableSetOf<android.net.Uri>()       // songs being copied to the destination
    private var current: Track? = null
    private var info: Id3Tag.Info? = null
    private var existingStars = 0
    private var pendingStars = 0
    private var lastPlayedUri: android.net.Uri? = null

    private var loaded = false
    private var recovered = false
    private var setupNeeded = false
    private var message: String? = null
    private var loadedSource: String? = null
    private var loadedDest: String? = null
    private var loadToken = 0
    private var listToken = 0

    // ---- lifecycle --------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && exo.mediaItemCount > 0) advance() else publish()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish()
            }

            override fun onPlayerError(error: PlaybackException) {
                onTrackError()
            }
        })

        // The notification / lock screen "next" button should behave like our Next button, and there is no "previous".
        val sessionPlayer = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()

            override fun isCommandAvailable(command: Int): Boolean =
                getAvailableCommands().contains(command)

            override fun hasNextMediaItem(): Boolean = true
            override fun seekToNext() = skip()
            override fun seekToNextMediaItem() = skip()
        }
        session = MediaSession.Builder(this, sessionPlayer).build()
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_LOCAL) binder else super.onBind(intent)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!exo.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        exo.release()
        io.shutdown()
        super.onDestroy()
    }

    // ---- API used by the activity -----------------------------------------------------------

    fun setListener(l: StateListener?) {
        listener = l
        if (l != null) publish()
    }

    fun togglePlay() {
        if (current == null || exo.mediaItemCount == 0) return   // nothing loaded yet
        if (exo.playWhenReady) {
            exo.pause()
        } else {
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            exo.play()
        }
        publish()
    }

    fun skip() {
        if (current == null) return
        exo.stop()
        advance()
    }

    fun rate(stars: Int) {
        val t = current ?: return
        if (stars !in 1..5) return
        pendingStars = stars
        Prefs.setPending(this, t.uri.toString(), stars)
        publish()
    }

    fun seekTo(ms: Long) {
        if (current != null) exo.seekTo(ms)
    }

    fun position(): Long = if (current != null) exo.currentPosition else 0L

    fun duration(): Long {
        val d = exo.duration
        return if (current != null && d != C.TIME_UNSET && d > 0) d else 0L
    }

    /** Re-reads the source folder. Safe to call often; also picks up changed folders from Settings. */
    fun refresh() {
        val src = Prefs.source(this)
        val dst = Prefs.dest(this)
        if (src == null || dst == null) {
            setupNeeded = true
            loaded = true
            stopCurrent()
            all.clear()
            order.clear()
            loadedSource = null
            loadedDest = null
            publish()
            return
        }
        setupNeeded = false
        if (src.toString() != loadedSource || dst.toString() != loadedDest) {
            stopCurrent()
            all.clear()
            order.clear()
            loaded = false
            loadedSource = src.toString()
            loadedDest = dst.toString()
        }
        val token = ++listToken
        val doRecover = !recovered
        recovered = true
        io.execute {
            var list: List<Track>? = null
            var err: String? = null
            try {
                var found = Library.list(this, src)
                if (doRecover) found = recoverPending(found, dst)
                list = found
            } catch (e: SecurityException) {
                err = "Lost access to a folder. Choose the folders again in Settings."
            } catch (e: Exception) {
                err = "Couldn't read the folder: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post { onListed(list, err, token) }
        }
    }

    // ---- queue ------------------------------------------------------------------------------

    /** Runs on io: finishes a move that was interrupted because the app was closed mid-song. */
    private fun recoverPending(found: List<Track>, dst: android.net.Uri): List<Track> {
        val uri = Prefs.pendingUri(this) ?: return found
        val stars = Prefs.pendingStars(this)
        val track = found.firstOrNull { it.uri.toString() == uri }
        Prefs.clearPending(this)
        if (track == null || stars !in 1..5) return found
        return try {
            Library.moveRated(this, track, dst, stars)
            found.filter { it.uri != track.uri }
        } catch (e: Exception) {
            main.post {
                message = "Couldn't move ${track.name}: ${e.message ?: e.javaClass.simpleName}"
                publish()
            }
            found
        }
    }

    private fun onListed(list: List<Track>?, err: String?, token: Int) {
        if (token != listToken) return
        loaded = true
        if (err != null) {
            message = err
            publish()
            return
        }
        val fresh = list.orEmpty().filter { it.uri !in moving }
        val freshUris = fresh.mapTo(HashSet()) { it.uri }
        val known = all.mapTo(HashSet()) { it.uri }
        val currentUri = current?.uri
        all = fresh.toMutableList()
        order.removeAll { it.uri !in freshUris }
        val newOnes = fresh.filter { it.uri !in known && it.uri != currentUri }
        if (newOnes.isNotEmpty()) {
            order.addAll(newOnes)
            order.shuffle()
        }
        if (current == null) playNext(autoPlay = false) else publish()
    }

    private fun pickNext(): Track? {
        if (order.isEmpty()) {
            order.addAll(all.shuffled())
            // avoid playing the same song twice in a row when there is a choice
            if (order.size > 1 && order[0].uri == lastPlayedUri) {
                val first = order[0]
                order[0] = order[order.size - 1]
                order[order.size - 1] = first
            }
        }
        return if (order.isEmpty()) null else order.removeAt(0)
    }

    private fun stopCurrent() {
        loadToken++
        current = null
        info = null
        existingStars = 0
        pendingStars = 0
        exo.stop()
        exo.clearMediaItems()
    }

    /** The song finished or was skipped: move it if it was rated, then start another. */
    private fun advance() {
        val finished = current
        val stars = pendingStars
        if (finished != null) lastPlayedUri = finished.uri
        current = null
        pendingStars = 0
        exo.stop()
        if (finished != null && stars > 0) enqueueMove(finished, stars)
        playNext(autoPlay = true)
    }

    private fun enqueueMove(t: Track, stars: Int) {
        val dst = Prefs.dest(this) ?: return
        all.removeAll { it.uri == t.uri }
        order.removeAll { it.uri == t.uri }
        moving.add(t.uri)
        io.execute {
            var err: String? = null
            try {
                Library.moveRated(this, t, dst, stars)
            } catch (e: Exception) {
                err = "Couldn't move ${t.name}: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post {
                moving.remove(t.uri)
                if (Prefs.pendingUri(this) == t.uri.toString()) Prefs.clearPending(this)
                if (err != null) {
                    message = err
                    all.add(t)          // still in the source folder, so keep it in rotation
                    order.add(t)
                }
                publish()
            }
        }
    }

    private fun playNext(autoPlay: Boolean, keepMessage: Boolean = false) {
        if (!keepMessage) message = null
        val t = pickNext()
        if (t == null) {
            current = null
            info = null
            existingStars = 0
            exo.stop()
            exo.clearMediaItems()
            publish()
            return
        }
        current = t
        info = null
        existingStars = 0
        pendingStars = 0
        publish()
        val token = ++loadToken
        io.execute {
            val read = try {
                contentResolver.openInputStream(t.uri)?.use { Id3Tag.read(java.io.BufferedInputStream(it)) }
            } catch (e: Exception) {
                null
            }
            main.post {
                if (token == loadToken && current == t) startTrack(t, read, autoPlay)
            }
        }
    }

    private fun startTrack(t: Track, i: Id3Tag.Info?, autoPlay: Boolean) {
        info = i
        existingStars = i?.stars() ?: 0
        val md = MediaMetadata.Builder()
            .setTitle(displayTitle(t, i))
            .setArtist(i?.artist?.ifBlank { null })
            .setAlbumTitle(i?.album?.ifBlank { null })
            .build()
        exo.setMediaItem(MediaItem.Builder().setUri(t.uri).setMediaMetadata(md).build())
        exo.prepare()
        exo.playWhenReady = autoPlay
        publish()
    }

    private fun onTrackError() {
        val t = current ?: return
        all.removeAll { it.uri == t.uri }
        order.removeAll { it.uri == t.uri }
        current = null
        pendingStars = 0
        playNext(autoPlay = true, keepMessage = false)
        message = "Couldn't play ${t.name}; skipped it."
        publish()
    }

    // ---- state ------------------------------------------------------------------------------

    private fun displayTitle(t: Track, i: Id3Tag.Info?): String =
        i?.title?.takeIf { it.isNotBlank() } ?: t.name.removeSuffix(".mp3").removeSuffix(".MP3")

    private fun publish() {
        val l = listener ?: return
        val t = current
        l.onState(
            UiState(
                artist = info?.artist ?: "",
                album = info?.album ?: "",
                title = if (t == null) "" else displayTitle(t, info),
                stars = if (pendingStars > 0) pendingStars else existingStars,
                previouslyRated = pendingStars == 0 && existingStars > 0,
                playing = t != null && exo.playWhenReady,
                hasTrack = t != null,
                loaded = loaded,
                setupNeeded = setupNeeded,
                remaining = all.size,
                message = message
            )
        )
    }
}
