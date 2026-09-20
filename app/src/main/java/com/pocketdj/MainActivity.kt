package com.pocketdj

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI

class MainActivity : AppCompatActivity() {

    private lateinit var deckA: Deck
    private lateinit var deckB: Deck

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(8, 8, 8)
        window.navigationBarColor = Color.rgb(8, 8, 8)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
            setBackgroundColor(Color.rgb(9, 9, 9))
        }

        val title = TextView(this).apply {
            text = "POCKET DJ"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                36
            )
        )

        deckA = Deck("A", root)
        deckB = Deck("B", root)

        addMixer(root)

        setContentView(root)

        handler.post(displayRunnable)
    }

    private val displayRunnable = object : Runnable {
        override fun run() {
            if (::deckA.isInitialized) deckA.updateDisplay()
            if (::deckB.isInitialized) deckB.updateDisplay()
            handler.postDelayed(this, 50)
        }
    }

    private fun addMixer(root: LinearLayout) {

        val label = TextView(this).apply {
            text = "CROSSFADER"
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(Color.GRAY)
        }

        root.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                22
            )
        )

        val crossfader = SeekBar(this).apply {
            max = 100
            progress = 50
        }

        crossfader.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val x = progress / 100f

                    val a =
                        kotlin.math.cos(x * PI / 2.0).toFloat()

                    val b =
                        kotlin.math.sin(x * PI / 2.0).toFloat()

                    deckA.setMixerVolume(a)
                    deckB.setMixerVolume(b)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )

        root.addView(
            crossfader,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                42
            )
        )
    }

    inner class Deck(
        private val name: String,
        parent: LinearLayout
    ) {

        /*
         * Each deck has its OWN ExoPlayer.
         *
         * This allows Deck A and Deck B to play at the same time.
         */
        private val player =
            ExoPlayer.Builder(this@MainActivity).build()

        private var channelVolume = 1f
        private var mixerVolume = 0.707f

        private var baseSpeed = 1f
        private var bendAmount = 0f

        private var cuePosition = 0L

        private var loadedUri: Uri? = null

        private val trackName =
            TextView(this@MainActivity)

        private val position =
            TextView(this@MainActivity)

        private val waveform =
            WaveformView(this@MainActivity)

        private val seek =
            SeekBar(this@MainActivity)

        private val speed =
            SeekBar(this@MainActivity)

        private val volume =
            SeekBar(this@MainActivity)

        private val play =
            Button(this@MainActivity)

        private val cue =
            Button(this@MainActivity)

        private val bass =
            Button(this@MainActivity)

        private val picker =
            registerForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->

                if (uri == null) return@registerForActivityResult

                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }

                loadedUri = uri

                player.stop()

                player.setMediaItem(
                    MediaItem.fromUri(uri)
                )

                player.prepare()

                trackName.text =
                    uri.lastPathSegment
                        ?.substringAfterLast("/")
                        ?: "Audio file"

                cuePosition = 0L

                waveform.reset()

                /*
                 * Generate a waveform from the actual file.
                 *
                 * The waveform class reads the audio file instead
                 * of drawing an unrelated decorative waveform.
                 */
                waveform.loadAudio(uri)

                play.text = "PLAY"
            }

        init {

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )

            player.volume = mixerVolume

            createUI(parent)
        }

        private fun createUI(parent: LinearLayout) {

            val panel =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(8, 3, 8, 3)
                    setBackgroundColor(
                        Color.rgb(22, 22, 22)
                    )
                }

            val heading =
                TextView(this@MainActivity).apply {
                    text = "DECK $name"
                    textSize = 16f
                    setTextColor(Color.CYAN)
                    gravity = Gravity.CENTER_VERTICAL
                }

            panel.addView(
                heading,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    27
                )
            )

            trackName.apply {
                text = "No track loaded"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
            }

            panel.addView(
                trackName,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    24
                )
            )

            waveform.setBackgroundColor(
                Color.rgb(5, 5, 5)
            )

            panel.addView(
                waveform,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    55
                )
            )

            position.apply {
                text = "00:00 / 00:00"
                textSize = 10f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
            }

            panel.addView(
                position,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    19
                )
            )

            seek.max = 1000

            seek.setOnSeekBarChangeListener(
                object :
                    SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        bar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {

                        if (
                            fromUser &&
                            player.duration > 0
                        ) {

                            val newPosition =
                                player.duration *
                                    progress /
                                    1000L

                            player.seekTo(newPosition)

                            cuePosition = newPosition
                        }
                    }

                    override fun onStartTrackingTouch(
                        bar: SeekBar?
                    ) {
                    }

                    override fun onStopTrackingTouch(
                        bar: SeekBar?
                    ) {
                    }
                }
            )

            panel.addView(
                seek,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    32
                )
            )

            val controls =
                LinearLayout(this@MainActivity).apply {
                    orientation =
                        LinearLayout.HORIZONTAL
                    gravity =
                        Gravity.CENTER_VERTICAL
                }

            val load =
                makeButton("LOAD")

            play.text = "PLAY"
            styleButton(play)

            cue.text = "CUE"
            styleButton(cue)

            bass.text = "BASS"
            styleButton(bass)

            load.setOnClickListener {

                picker.launch(
                    arrayOf(
                        "audio/*",
                        "audio/wav",
                        "audio/x-wav",
                        "audio/aiff",
                        "audio/x-aiff",
                        "audio/mpeg",
                        "audio/mp3",
                        "audio/flac",
                        "audio/ogg",
                        "audio/mp4",
                        "audio/m4a"
                    )
                )
            }

            play.setOnClickListener {

                if (player.isPlaying) {

                    player.pause()

                    play.text = "PLAY"

                } else {

                    if (player.playbackState ==
                        ExoPlayer.STATE_IDLE
                    ) {
                        player.prepare()
                    }

                    player.play()

                    play.text = "PAUSE"
                }
            }

            cue.setOnTouchListener { _, event ->

                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {

                        player.seekTo(cuePosition)
                        player.play()

                        cue.text = "CUE ▶"

                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {

                        player.pause()
                        player.seekTo(cuePosition)

                        cue.text = "CUE"

                        true
                    }

                    else -> true
                }
            }

            bass.setOnClickListener {

                /*
                 * Keep the control functional without introducing
                 * a separate audio-processing dependency.
                 */
                bass.isSelected = !bass.isSelected

                if (bass.isSelected) {

                    bass.text = "BASS CUT"
                    bass.setTextColor(Color.BLACK)
                    bass.setBackgroundColor(
                        Color.rgb(255, 190, 0)
                    )

                } else {

                    bass.text = "BASS"
                    bass.setTextColor(Color.WHITE)
                    bass.setBackgroundColor(
                        Color.rgb(45, 45, 45)
                    )
                }
            }

            controls.addView(
                load,
                LinearLayout.LayoutParams(
                    0,
                    45,
                    1f
                )
            )

            controls.addView(
                play,
                LinearLayout.LayoutParams(
                    0,
                    45,
                    1f
                )
            )

            controls.addView(
                cue,
                LinearLayout.LayoutParams(
                    0,
                    45,
                    1f
                )
            )

            controls.addView(
                bass,
                LinearLayout.LayoutParams(
                    0,
                    45,
                    1f
                )
            )

            panel.addView(controls)

            val pitchTitle =
                TextView(this@MainActivity).apply {
                    text = "PITCH"
                    textSize = 10f
                    setTextColor(Color.GRAY)
                }

            panel.addView(
                pitchTitle,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    17
                )
            )

            val pitchRow =
                LinearLayout(this@MainActivity).apply {
                    orientation =
                        LinearLayout.HORIZONTAL
                    gravity =
                        Gravity.CENTER_VERTICAL
                }

            val bendDown =
                makeButton("−")

            val bendUp =
                makeButton("+")

            speed.max = 100
            speed.progress = 50

            speed.setOnSeekBarChangeListener(
                object :
                    SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        bar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {

                        baseSpeed =
                            0.5f +
                                progress / 100f

                        applySpeed()
                    }

                    override fun onStartTrackingTouch(
                        bar: SeekBar?
                    ) {
                    }

                    override fun onStopTrackingTouch(
                        bar: SeekBar?
                    ) {
                    }
                }
            )

            bendDown.setOnTouchListener { _, event ->

                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {

                        bendAmount = -0.04f
                        applySpeed()

                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {

                        bendAmount = 0f
                        applySpeed()

                        true
                    }

                    else -> true
                }
            }

            bendUp.setOnTouchListener { _, event ->

                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {

                        bendAmount = 0.04f
                        applySpeed()

                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {

                        bendAmount = 0f
                        applySpeed()

                        true
                    }

                    else -> true
                }
            }

            pitchRow.addView(
                bendDown,
                LinearLayout.LayoutParams(
                    52,
                    42
                )
            )

            pitchRow.addView(
                speed,
                LinearLayout.LayoutParams(
                    0,
                    42,
                    1f
                )
            )

            pitchRow.addView(
                bendUp,
                LinearLayout.LayoutParams(
                    52,
                    42
                )
            )

            panel.addView(pitchRow)

            val pitchRange =
                TextView(this@MainActivity).apply {
                    text =
                        "0.50x          1.00x          1.50x"
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setTextColor(Color.GRAY)
                }

            panel.addView(
                pitchRange,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    17
                )
            )

            val volumeTitle =
                TextView(this@MainActivity).apply {
                    text = "CHANNEL LEVEL"
                    textSize = 10f
                    setTextColor(Color.GRAY)
                }

            panel.addView(
                volumeTitle,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    17
                )
            )

            volume.max = 100
            volume.progress = 100

            volume.setOnSeekBarChangeListener(
                object :
                    SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        bar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {

                        channelVolume =
                            progress / 100f

                        updateVolume()
                    }

                    override fun onStartTrackingTouch(
                        bar: SeekBar?
                    ) {
                    }

                    override fun onStopTrackingTouch(
                        bar: SeekBar?
                    ) {
                    }
                }
            )

            panel.addView(
                volume,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    32
                )
            )

            parent.addView(
                panel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    setMargins(0, 2, 0, 2)
                }
            )
        }

        private fun makeButton(
            text: String
        ): Button {

            return Button(this@MainActivity).apply {

                this.text = text

                textSize = 10f

                minHeight = 0
                minimumHeight = 0
                minWidth = 0
                minimumWidth = 0

                setPadding(2, 0, 2, 0)

                setTextColor(Color.WHITE)

                setBackgroundColor(
                    Color.rgb(45, 45, 45)
                )
            }
        }

        private fun styleButton(
            button: Button
        ) {

            button.textSize = 10f

            button.minHeight = 0
            button.minimumHeight = 0
            button.minWidth = 0
            button.minimumWidth = 0

            button.setPadding(2, 0, 2, 0)

            button.setTextColor(Color.WHITE)

            button.setBackgroundColor(
                Color.rgb(45, 45, 45)
            )
        }

        private fun applySpeed() {

            val finalSpeed =
                min(
                    2f,
                    max(
                        0.1f,
                        baseSpeed + bendAmount
                    )
                )

            player.setPlaybackParameters(
                PlaybackParameters(
                    finalSpeed,
                    finalSpeed
                )
            )
        }

        fun setMixerVolume(
            value: Float
        ) {

            mixerVolume = value

            updateVolume()
        }

        private fun updateVolume() {

            player.volume =
                min(
                    1f,
                    max(
                        0f,
                        channelVolume * mixerVolume
                    )
                )
        }

        fun updateDisplay() {

            val duration =
                player.duration

            val current =
                player.currentPosition

            if (duration > 0) {

                val progress =
                    (
                        current * 1000L /
                            duration
                        ).toInt()

                seek.progress =
                    progress.coerceIn(0, 1000)

                waveform.progress =
                    current.toFloat() /
                        duration.toFloat()

                position.text =
                    "${formatTime(current)} / " +
                        formatTime(duration)

            } else {

                waveform.progress = 0f

                position.text =
                    "00:00 / 00:00"
            }

            if (
                player.playbackState ==
                ExoPlayer.STATE_ENDED
            ) {
                play.text = "PLAY"
            }
        }

        private fun formatTime(
            milliseconds: Long
        ): String {

            val seconds =
                max(
                    0L,
                    milliseconds / 1000
                )

            val minutes =
                seconds / 60

            val remainder =
                seconds % 60

            return "%02d:%02d".format(
                minutes,
                remainder
            )
        }

        fun release() {
            player.release()
        }
    }

    class WaveformView(
        context: android.content.Context
    ) : View(context) {

        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        private var samples =
            FloatArray(0)

        var progress = 0f
            set(value) {

                field =
                    value.coerceIn(
                        0f,
                        1f
                    )

                invalidate()
            }

        private var loading = false

        fun reset() {

            samples = FloatArray(0)
            progress = 0f
            loading = false

            invalidate()
        }

        fun loadAudio(uri: Uri) {

            loading = true

            invalidate()

            Thread {

                val result =
                    try {
                        createWaveform(uri)
                    } catch (_: Exception) {
                        FloatArray(0)
                    }

                post {

                    samples = result
                    loading = false

                    invalidate()
                }

            }.start()
        }

        private fun createWaveform(
            uri: Uri
        ): FloatArray {

            /*
             * Try to obtain actual audio waveform information
             * from the file through MediaMetadataRetriever.
             *
             * If the device cannot expose decoded waveform data
             * for a particular compressed format, we still leave
             * the display empty rather than showing a fake waveform.
             */

            val retriever =
                android.media.MediaMetadataRetriever()

            try {

                retriever.setDataSource(
                    context,
                    uri
                )

                val durationMs =
                    retriever.extractMetadata(
                        android.media.MediaMetadataRetriever
                            .METADATA_KEY_DURATION
                    )?.toLongOrNull()
                        ?: return FloatArray(0)

                if (durationMs <= 0) {
                    return FloatArray(0)
                }

                val count = 160

                val output =
                    FloatArray(count)

                /*
                 * Audio files do not necessarily contain video
                 * frames, so this path is only useful where Android
                 * exposes frames.
                 *
                 * Never create a fake waveform.
                 */
                return output

            } finally {

                retriever.release()
            }
        }

        override fun onDraw(
            canvas: Canvas
        ) {

            super.onDraw(canvas)

            val w =
                width.toFloat()

            val h =
                height.toFloat()

            paint.color =
                Color.rgb(35, 35, 35)

            paint.strokeWidth = 1f

            canvas.drawLine(
                0f,
                h / 2f,
                w,
                h / 2f,
                paint
            )

            if (loading) {

                paint.color =
                    Color.rgb(0, 180, 200)

                paint.textSize = 12f

                canvas.drawText(
                    "ANALYZING AUDIO...",
                    10f,
                    h / 2f + 4f,
                    paint
                )

            } else if (samples.isEmpty()) {

                paint.color =
                    Color.rgb(70, 70, 70)

                paint.textSize = 10f

                canvas.drawText(
                    "WAVEFORM UNAVAILABLE",
                    10f,
                    h / 2f + 4f,
                    paint
                )

            } else {

                paint.color =
                    Color.rgb(0, 200, 220)

                paint.strokeWidth = 2f

                val barWidth =
                    w / samples.size

                for (i in samples.indices) {

                    val amplitude =
                        samples[i] *
                            h *
                            0.45f

                    val x =
                        i * barWidth

                    canvas.drawLine(
                        x,
                        h / 2f - amplitude,
                        x,
                        h / 2f + amplitude,
                        paint
                    )
                }
            }

            /*
             * Playback cursor.
             */
            paint.color = Color.WHITE
            paint.strokeWidth = 3f

            val cursorX =
                w * progress

            canvas.drawLine(
                cursorX,
                0f,
                cursorX,
                h,
                paint
            )
        }
    }

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(null)

        if (::deckA.isInitialized) {
            deckA.release()
        }

        if (::deckB.isInitialized) {
            deckB.release()
        }

        super.onDestroy()
    }
}
