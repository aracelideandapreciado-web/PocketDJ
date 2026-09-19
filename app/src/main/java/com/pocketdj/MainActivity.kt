package com.pocketdj

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var deckA: Deck
    private lateinit var deckB: Deck

    private var crossfader = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.rgb(8, 8, 8)
        window.navigationBarColor = android.graphics.Color.rgb(8, 8, 8)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(12, 12, 12))
            setPadding(16, 12, 16, 12)
        }

        val title = TextView(this).apply {
            text = "POCKET DJ"
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 14)
        }

        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        deckA = Deck("A", root)
        deckB = Deck("B", root)

        addMixer(root)

        setContentView(root)
    }

    private fun addMixer(root: LinearLayout) {

        val mixerTitle = TextView(this).apply {
            text = "MIXER"
            textSize = 14f
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 4)
        }

        root.addView(mixerTitle)

        val mixer = SeekBar(this).apply {
            max = 100
            progress = 50
        }

        mixer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                crossfader = progress / 100f

                // Equal-power style crossfade.
                val a = kotlin.math.cos(crossfader * Math.PI / 2).toFloat()
                val b = kotlin.math.sin(crossfader * Math.PI / 2).toFloat()

                deckA.setMixerVolume(a)
                deckB.setMixerVolume(b)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        root.addView(mixer)

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        labels.addView(
            TextView(this).apply {
                text = "A"
                setTextColor(android.graphics.Color.WHITE)
                gravity = Gravity.START
            },
            LinearLayout.LayoutParams(0, 40, 1f)
        )

        labels.addView(
            TextView(this).apply {
                text = "CROSSFADER"
                setTextColor(android.graphics.Color.GRAY)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(0, 40, 1f)
        )

        labels.addView(
            TextView(this).apply {
                text = "B"
                setTextColor(android.graphics.Color.WHITE)
                gravity = Gravity.END
            },
            LinearLayout.LayoutParams(0, 40, 1f)
        )

        root.addView(labels)
    }

    inner class Deck(
        private val deckName: String,
        private val root: LinearLayout
    ) {

        private val player = ExoPlayer.Builder(this@MainActivity).build()

        private var userVolume = 1f
        private var mixerVolume = 0.707f

        private val nameText = TextView(this@MainActivity)
        private val positionText = TextView(this@MainActivity)
        private val seek = SeekBar(this@MainActivity)
        private val speedSeek = SeekBar(this@MainActivity)
        private val volumeSeek = SeekBar(this@MainActivity)
        private val playButton = Button(this@MainActivity)

        private val picker =
            registerForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->

                uri ?: return@registerForActivityResult

                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()

                nameText.text = uri.lastPathSegment ?: "Audio file"
                playButton.text = "PLAY"
            }

        init {
            build()
            updateProgress()
        }

        private fun build() {

            val panel = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 8, 12, 8)
                setBackgroundColor(android.graphics.Color.rgb(25, 25, 25))
            }

            val header = TextView(this@MainActivity).apply {
                text = "DECK $deckName"
                textSize = 18f
                setTextColor(android.graphics.Color.CYAN)
            }

            panel.addView(header)

            nameText.apply {
                text = "No track loaded"
                textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(0, 6, 0, 6)
            }

            panel.addView(nameText)

            positionText.apply {
                text = "00:00 / 00:00"
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
            }

            panel.addView(positionText)

            seek.max = 1000

            seek.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        bar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (fromUser && player.duration > 0) {
                            val position =
                                player.duration * progress / 1000L

                            player.seekTo(position)
                        }
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                }
            )

            panel.addView(seek)

            val buttons = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val loadButton = Button(this@MainActivity).apply {
                text = "LOAD"
                setOnClickListener {
                    picker.launch(
                        arrayOf(
                            "audio/wav",
                            "audio/x-wav",
                            "audio/aiff",
                            "audio/x-aiff",
                            "audio/mpeg",
                            "audio/*"
                        )
                    )
                }
            }

            playButton.apply {
                text = "PLAY"
                setOnClickListener {
                    if (player.isPlaying) {
                        player.pause()
                        text = "PLAY"
                    } else {
                        player.play()
                        text = "PAUSE"
                    }
                }
            }

            val cueButton = Button(this@MainActivity).apply {
                text = "CUE"
                setOnClickListener {
                    player.seekTo(0)
                    player.pause()
                    playButton.text = "PLAY"
                }
            }

            buttons.addView(
                loadButton,
                LinearLayout.LayoutParams(0, 55, 1f)
            )

            buttons.addView(
                playButton,
                LinearLayout.LayoutParams(0, 55, 1f)
            )

            buttons.addView(
                cueButton,
                LinearLayout.LayoutParams(0, 55, 1f)
            )

            panel.addView(buttons)

            val speedLabel = TextView(this@MainActivity).apply {
                text = "PITCH / SPEED"
                setTextColor(android.graphics.Color.LTGRAY)
            }

            panel.addView(speedLabel)

            /*
             * 50 = 1.00x
             *
             * 0  = 0.50x
             * 100 = 1.50x
             *
             * This deliberately changes both speed and pitch,
             * producing the turntable-style effect requested.
             */

            speedSeek.max = 100
            speedSeek.progress = 50

            speedSeek.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        bar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {

                        val speed =
                            0.5f + progress / 100f

                        player.setPlaybackParameters(
                            PlaybackParameters(
                                speed,
                                speed
                            )
                        )
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                }
            )

            panel.addView(speedSeek)

            val speedText = TextView(this@MainActivity).apply {
                text = "0.50x     1.00x     1.50x"
                setTextColor(android.graphics.Color.GRAY)
                gravity = Gravity.CENTER
            }

            panel.addView(speedText)

            val volumeLabel = TextView(this@MainActivity).apply {
                text = "CHANNEL LEVEL"
                setTextColor(android.graphics.Color.LTGRAY)
            }

            panel.addView(volumeLabel)

            volumeSeek.max = 100
            volumeSeek.progress = 100

            volumeSeek.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        bar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        userVolume = progress / 100f
                        updateVolume()
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                }
            )

            panel.addView(volumeSeek)

            root.addView(
                panel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    setMargins(0, 4, 0, 4)
                }
            )
        }

        fun setMixerVolume(value: Float) {
            mixerVolume = value
            updateVolume()
        }

        private fun updateVolume() {
            player.volume =
                max(0f, min(1f, userVolume * mixerVolume))
        }

        private fun updateProgress() {

            val handler = android.os.Handler(mainLooper)

            handler.post(object : Runnable {

                override fun run() {

                    if (player.duration > 0) {

                        val progress =
                            (player.currentPosition * 1000 /
                                    player.duration)
                                .toInt()

                        seek.progress = progress

                        positionText.text =
                            "${formatTime(player.currentPosition)} / " +
                            formatTime(player.duration)
                    }

                    handler.postDelayed(this, 250)
                }
            })
        }

        private fun formatTime(ms: Long): String {

            val totalSeconds = max(0L, ms / 1000)

            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60

            return "%02d:%02d".format(minutes, seconds)
        }

        fun release() {
            player.release()
        }
    }

    override fun onDestroy() {
        deckA.release()
        deckB.release()
        super.onDestroy()
    }
}
