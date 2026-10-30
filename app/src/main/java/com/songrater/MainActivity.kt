package com.songrater

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.ServiceConnection
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity(), PlayerService.StateListener {

    private var service: PlayerService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false

    private lateinit var artistText: TextView
    private lateinit var albumText: TextView
    private lateinit var titleText: TextView
    private lateinit var noteText: TextView
    private lateinit var previousText: TextView
    private lateinit var remainingText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var totalText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private val starViews = ArrayList<TextView>()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as PlayerService.LocalBinder).service()
            service = s
            s.setListener(this@MainActivity)
            s.refresh()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        artistText = findViewById(R.id.artistText)
        albumText = findViewById(R.id.albumText)
        titleText = findViewById(R.id.titleText)
        noteText = findViewById(R.id.noteText)
        previousText = findViewById(R.id.previousText)
        remainingText = findViewById(R.id.remainingText)
        elapsedText = findViewById(R.id.elapsedText)
        totalText = findViewById(R.id.totalText)
        seekBar = findViewById(R.id.seekBar)
        playButton = findViewById(R.id.playButton)
        nextButton = findViewById(R.id.nextButton)

        val ids = intArrayOf(R.id.star1, R.id.star2, R.id.star3, R.id.star4, R.id.star5)
        for ((index, id) in ids.withIndex()) {
            val v = findViewById<TextView>(id)
            v.setOnClickListener { service?.rate(index + 1) }
            starViews.add(v)
        }

        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        playButton.setOnClickListener { service?.togglePlay() }
        nextButton.setOnClickListener { service?.skip() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) elapsedText.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                service?.seekTo(sb.progress.toLong())
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        // Started (so playback survives the screen closing) and bound (so we can talk to it).
        startService(Intent(this, PlayerService::class.java))
        bindService(
            Intent(this, PlayerService::class.java).setAction(PlayerService.ACTION_LOCAL),
            connection,
            Context.BIND_AUTO_CREATE
        )
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        service?.setListener(null)
        service = null
        unbindService(connection)
        super.onStop()
    }

    override fun onState(state: PlayerService.UiState) {
        artistText.text = state.artist
        artistText.visibility = if (state.artist.isBlank()) View.GONE else View.VISIBLE
        albumText.text = state.album
        albumText.visibility = if (state.album.isBlank()) View.GONE else View.VISIBLE
        titleText.text = state.title
        titleText.visibility = if (state.hasTrack) View.VISIBLE else View.GONE

        val note = when {
            state.setupNeeded -> "Choose your song folder and destination folder in Settings to get started."
            state.message != null -> state.message
            !state.loaded -> "Loading…"
            !state.hasTrack -> "No songs left to rate."
            else -> null
        }
        noteText.text = note ?: ""
        noteText.visibility = if (note == null) View.GONE else View.VISIBLE

        previousText.visibility = if (state.previouslyRated) View.VISIBLE else View.INVISIBLE
        for ((i, v) in starViews.withIndex()) {
            v.setTextColor(if (i < state.stars) STAR_ON else STAR_OFF)
            v.isEnabled = state.hasTrack
        }

        playButton.text = if (state.playing) "Pause" else "Play"
        playButton.isEnabled = state.hasTrack
        nextButton.isEnabled = state.hasTrack
        seekBar.isEnabled = state.hasTrack
        if (!state.hasTrack) {
            seekBar.progress = 0
            elapsedText.text = formatTime(0)
            totalText.text = formatTime(0)
        }

        remainingText.text = if (state.setupNeeded) "" else
            if (state.remaining == 1) "1 song in the folder" else "${state.remaining} songs in the folder"
    }

    private fun tick() {
        val s = service ?: return
        val duration = s.duration()
        seekBar.max = duration.toInt()
        totalText.text = formatTime(duration)
        if (!dragging) {
            val pos = s.position()
            seekBar.progress = pos.toInt()
            elapsedText.text = formatTime(pos)
        }
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return String.format("%d:%02d", total / 60, total % 60)
    }

    companion object {
        private const val STAR_ON = 0xFFFFB300.toInt()
        private const val STAR_OFF = 0xFF9E9E9E.toInt()
    }
}
