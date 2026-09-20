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
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
                38
            )
        )

        deckA = Deck("A", root)
        deckB = Deck("B", root)

        addMixer(root)

        setContentView(root)

        handler.post(object : Runnable {
            override fun run() {
                deckA.updateDisplay()
                deckB.updateDisplay()
                handler.postDelayed(this, 100)
            }
        })
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
                24
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
                        kotlin.math.cos(x * Math.PI / 2.0).toFloat()

                    val b =
                        kotlin.math.sin(x * Math.PI / 2.0).toFloat()

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

                if (uri == null) {
                    return@registerForActivityResult
                }

                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }

                loadedUri = uri

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

                Thread {
                    val samples =
                        AudioWaveformReader.read(
                            this@MainActivity,
                            uri
                        )

                    runOnUiThread {
                        if (loadedUri == uri) {
                            waveform.setSamples(samples)
                        }
                    }
                }.start()

                play.text = "PLAY"
            }

        init {

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(
                        C.AUDIO_CONTENT_TYPE_MUSIC
                    )
                    .build(),
                false
            )

            createUI(parent)
        }

        private fun createUI(
            parent: LinearLayout
        ) {

            val panel =
                LinearLayout(this@MainActivity).apply {
                    orientation =
                        LinearLayout.VERTICAL

                    setPadding(
                        8,
                        3,
                        8,
                        3
                    )

                    setBackgroundColor(
                        Color.rgb(22, 22, 22)
                    )
                }

            val heading =
                TextView(this@MainActivity).apply {
                    text = "DECK $name"
                    textSize = 16f
                    setTextColor(Color.CYAN)
                    gravity =
                        Gravity.CENTER_VERTICAL
                }

            panel.addView(
                heading,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    28
                )
            )

            trackName.apply {
                text = "No track loaded"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity =
                    Gravity.CENTER_VERTICAL
            }

            panel.addView(
                trackName,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    25
                )
            )

            panel.addView(
                waveform,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    52
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
                    20
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

                            player.seekTo(
                                newPosition
                            )

                            cuePosition =
                                newPosition
                        }
                    }

                    override fun onStartTrackingTouch(
                        bar: SeekBar?
                    ) {}

                    override fun onStopTrackingTouch(
                        bar: SeekBar?
                    ) {}
                }
            )

            panel.addView(
                seek,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    34
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
                        "audio/mpeg"
                    )
                )
            }

            play.setOnClickListener {

                if (player.mediaItemCount == 0) {
                    return@setOnClickListener
                }

                if (player.isPlaying) {
                    player.pause()
                    play.text = "PLAY"
                } else {
                    player.play()
                    play.text = "PAUSE"
                }
            }

            cue.setOnTouchListener { _, event ->

                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {

                        if (
                            player.mediaItemCount > 0
                        ) {

                            player.seekTo(
                                cuePosition
                            )

                            player.play()

                            cue.text =
                                "CUE ▶"
                        }

                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {

                        player.pause()

                        player.seekTo(
                            cuePosition
                        )

                        cue.text = "CUE"

                        true
                    }

                    else -> true
                }
            }

            bass.setOnClickListener {

                if (bass.text == "BASS") {

                    bass.text = "BASS CUT"

                    bass.setTextColor(
                        Color.BLACK
                    )

                    bass.setBackgroundColor(
                        Color.rgb(
                            255,
                            190,
                            0
                        )
                    )

                } else {

                    bass.text = "BASS"

                    bass.setTextColor(
                        Color.WHITE
                    )

                    bass.setBackgroundColor(
                        Color.rgb(
                            45,
                            45,
                            45
                        )
                    )
                }
            }

            controls.addView(
                load,
                LinearLayout.LayoutParams(
                    0,
                    46,
                    1f
                )
            )

            controls.addView(
                play,
                LinearLayout.LayoutParams(
                    0,
                    46,
                    1f
                )
            )

            controls.addView(
                cue,
                LinearLayout.LayoutParams(
                    0,
                    46,
                    1f
                )
            )

            controls.addView(
                bass,
                LinearLayout.LayoutParams(
                    0,
                    46,
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
                    18
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
                                progress /
                                100f

                        applySpeed()
                    }

                    override fun onStartTrackingTouch(
                        bar: SeekBar?
                    ) {}

                    override fun onStopTrackingTouch(
                        bar: SeekBar?
                    ) {}
                }
            )

            bendDown.setOnTouchListener {
                    _,
                    event ->

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

            bendUp.setOnTouchListener {
                    _,
                    event ->

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

                    gravity =
                        Gravity.CENTER

                    setTextColor(
                        Color.GRAY
                    )
                }

            panel.addView(
                pitchRange,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    18
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
                    18
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
                            progress /
                            100f

                        updateVolume()
                    }

                    override fun onStartTrackingTouch(
                        bar: SeekBar?
                    ) {}

                    override fun onStopTrackingTouch(
                        bar: SeekBar?
                    ) {}
                }
            )

            panel.addView(
                volume,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    34
                )
            )

            parent.addView(
                panel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    setMargins(
                        0,
                        2,
                        0,
                        2
                    )
                }
            )
        }

        private fun makeButton(
            text: String
        ): Button {

            return Button(
                this@MainActivity
            ).apply {

                this.text = text

                textSize = 10f

                minHeight = 0
                minimumHeight = 0

                minWidth = 0
                minimumWidth = 0

                setPadding(
                    2,
                    0,
                    2,
                    0
                )

                setTextColor(
                    Color.WHITE
                )

                setBackgroundColor(
                    Color.rgb(
                        45,
                        45,
                        45
                    )
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

            button.setPadding(
                2,
                0,
                2,
                0
            )

            button.setTextColor(
                Color.WHITE
            )

            button.setBackgroundColor(
                Color.rgb(
                    45,
                    45,
                    45
                )
            )
        }

        private fun applySpeed() {

            val finalSpeed =
                min(
                    2f,
                    max(
                        0.1f,
                        baseSpeed +
                            bendAmount
                    )
                )

            player.setPlaybackParameters(
                PlaybackParameters(
                    finalSpeed,
                    1f
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
                        channelVolume *
                            mixerVolume
                    )
                )
        }

        fun updateDisplay() {

            if (
                player.mediaItemCount > 0 &&
                player.duration > 0
            ) {

                val current =
                    player.currentPosition

                val duration =
                    player.duration

                seek.progress =
                    (
                        current *
                            1000L /
                            duration
                    )
                        .toInt()
                        .coerceIn(
                            0,
                            1000
                        )

                waveform.progress =
                    current.toFloat() /
                    duration.toFloat()

                position.text =
                    "${formatTime(current)} / " +
                    formatTime(duration)

                if (
                    !player.isPlaying &&
                    current >= duration
                ) {
                    play.text = "PLAY"
                }

            } else {

                waveform.progress = 0f

                position.text =
                    "00:00 / 00:00"
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
            Paint(
                Paint.ANTI_ALIAS_FLAG
            )

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

        init {
            setBackgroundColor(
                Color.rgb(
                    5,
                    5,
                    5
                )
            )
        }

        fun reset() {
            samples = FloatArray(0)
            progress = 0f
        }

        fun setSamples(
            newSamples: FloatArray
        ) {

            samples = newSamples

            invalidate()
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
                Color.rgb(
                    30,
                    30,
                    30
                )

            paint.strokeWidth = 1f

            canvas.drawLine(
                0f,
                h / 2f,
                w,
                h / 2f,
                paint
            )

            paint.color =
                Color.rgb(
                    0,
                    210,
                    220
                )

            paint.strokeWidth = 2f

            if (samples.isEmpty()) {

                val bars = 80

                val barWidth =
                    w / bars

                for (i in 0 until bars) {

                    val wave =
                        sin(
                            i * 0.63
                        ) * 0.45 +
                        sin(
                            i * 0.17
                        ) * 0.25

                    val amplitude =
                        (
                            0.15f +
                            abs(
                                wave
                            ).toFloat() *
                            0.5f
                        ) *
                        h / 2f

                    val x =
                        i * barWidth

                    canvas.drawLine(
                        x,
                        h / 2f -
                            amplitude,
                        x,
                        h / 2f +
                            amplitude,
                        paint
                    )
                }

            } else {

                val count =
                    samples.size

                for (i in 0 until count) {

                    val x =
                        if (count == 1) {
                            0f
                        } else {
                            i.toFloat() /
                                (
                                    count - 1
                                ).toFloat() *
                                w
                        }

                    val amplitude =
                        samples[i] *
                        h /
                        2f

                    canvas.drawLine(
                        x,
                        h / 2f -
                            amplitude,
                        x,
                        h / 2f +
                            amplitude,
                        paint
                    )
                }
            }

            paint.color =
                Color.WHITE

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

        handler.removeCallbacksAndMessages(
            null
        )

        deckA.release()
        deckB.release()

        super.onDestroy()
    }
}

object AudioWaveformReader {

    fun read(
        context: android.content.Context,
        uri: Uri
    ): FloatArray {

        return try {

            val input =
                context.contentResolver
                    .openInputStream(uri)
                    ?: return FloatArray(0)

            input.use {

                val bytes =
                    it.readBytes()

                if (bytes.size < 12) {
                    return FloatArray(0)
                }

                if (
                    isRiffWave(bytes)
                ) {
                    readWave(bytes)
                } else if (
                    isAiff(bytes)
                ) {
                    readAiff(bytes)
                } else {
                    FloatArray(0)
                }
            }

        } catch (_: Exception) {

            FloatArray(0)
        }
    }

    private fun isRiffWave(
        data: ByteArray
    ): Boolean {

        return data.size >= 12 &&
            data[0] == 'R'.code.toByte() &&
            data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() &&
            data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() &&
            data[9] == 'A'.code.toByte() &&
            data[10] == 'V'.code.toByte() &&
            data[11] == 'E'.code.toByte()
    }

    private fun isAiff(
        data: ByteArray
    ): Boolean {

        return data.size >= 12 &&
            (
                (
                    data[0] == 'F'.code.toByte() &&
                    data[1] == 'O'.code.toByte() &&
                    data[2] == 'R'.code.toByte() &&
                    data[3] == 'M'.code.toByte()
                )
            ) &&
            (
                (
                    data[8] == 'A'.code.toByte() &&
                    data[9] == 'I'.code.toByte() &&
                    data[10] == 'F'.code.toByte() &&
                    data[11] == 'F'.code.toByte()
                ) ||
                (
                    data[8] == 'A'.code.toByte() &&
                    data[9] == 'I'.code.toByte() &&
                    data[10] == 'F'.code.toByte() &&
                    data[11] == 'C'.code.toByte()
                )
            )
    }

    private fun readWave(
        data: ByteArray
    ): FloatArray {

        var position = 12

        var channels = 1
        var bits = 16
        var audioFormat = 1
        var dataStart = -1
        var dataSize = 0

        while (
            position + 8 <= data.size
        ) {

            val id =
                String(
                    data,
                    position,
                    4,
                    Charsets.US_ASCII
                )

            val size =
                littleEndianInt(
                    data,
                    position + 4
                )

            if (
                size < 0 ||
                position + 8 + size >
                    data.size
            ) {
                break
            }

            when (id) {

                "fmt " -> {

                    if (size >= 16) {

                        audioFormat =
                            littleEndianShort(
                                data,
                                position + 8
                            )

                        channels =
                            littleEndianShort(
                                data,
                                position + 10
                            )

                        bits =
                            littleEndianShort(
                                data,
                                position + 22
                            )
                    }
                }

                "data" -> {

                    dataStart =
                        position + 8

                    dataSize =
                        size

                    break
                }
            }

            position +=
                8 +
                size +
                (size and 1)
        }

        if (
            audioFormat != 1 ||
            dataStart < 0 ||
            channels < 1
        ) {
            return FloatArray(0)
        }

        return makeWaveform(
            data,
            dataStart,
            dataSize,
            channels,
            bits,
            false
        )
    }

    private fun readAiff(
        data: ByteArray
    ): FloatArray {

        var position = 12

        var channels = 1
        var bits = 16
        var dataStart = -1
        var dataSize = 0

        while (
            position + 8 <= data.size
        ) {

            val id =
                String(
                    data,
                    position,
                    4,
                    Charsets.US_ASCII
                )

            val size =
                bigEndianInt(
                    data,
                    position + 4
                )

            if (
                size < 0 ||
                position + 8 + size >
                    data.size
            ) {
                break
            }

            when (id) {

                "COMM" -> {

                    if (size >= 18) {

                        channels =
                            bigEndianShort(
                                data,
                                position + 8
                            )

                        bits =
                            bigEndianShort(
                                data,
                                position + 14
                            )
                    }
                }

                "SSND" -> {

                    if (size >= 8) {

                        val offset =
                            bigEndianInt(
                                data,
                                position + 8
                            )

                        dataStart =
                            position +
                            16 +
                            offset

                        dataSize =
                            size -
                            8 -
                            offset

                        break
                    }
                }
            }

            position +=
                8 +
                size +
                (size and 1)
        }

        if (
            dataStart < 0 ||
            channels < 1
        ) {
            return FloatArray(0)
        }

        return makeWaveform(
            data,
            dataStart,
            dataSize,
            channels,
            bits,
            true
        )
    }

    private fun makeWaveform(
        data: ByteArray,
        start: Int,
        size: Int,
        channels: Int,
        bits: Int,
        bigEndian: Boolean
    ): FloatArray {

        val bytesPerSample =
            bits / 8

        if (
            bytesPerSample <= 0 ||
            channels <= 0
        ) {
            return FloatArray(0)
        }

        val frameSize =
            bytesPerSample *
            channels

        if (frameSize <= 0) {
            return FloatArray(0)
        }

        val frames =
            size / frameSize

        if (frames <= 0) {
            return FloatArray(0)
        }

        val outputCount =
            min(
                900,
                max(
                    120,
                    frames / 5000
                )
            )

        val output =
            FloatArray(outputCount)

        for (i in 0 until outputCount) {

            val from =
                i * frames / outputCount

            val to =
                max(
                    from + 1,
                    (i + 1) *
                        frames /
                        outputCount
                )

            var peak = 0f

            for (
                frame in from until min(
                    to,
                    frames
                )
            ) {

                val frameOffset =
                    start +
                    frame *
                    frameSize

                for (
                    channel in 0 until channels
                ) {

                    val sampleOffset =
                        frameOffset +
                        channel *
                        bytesPerSample

                    val value =
                        when (bits) {

                            8 -> {

                                val v =
                                    data[
                                        sampleOffset
                                    ].toInt() and
                                        0xFF

                                abs(
                                    (
                                        v -
                                        128
                                    ) /
                                    128f
                                )
                            }

                            16 -> {

                                val v =
                                    if (bigEndian) {
                                        bigEndianShort(
                                            data,
                                            sampleOffset
                                        )
                                    } else {
                                        littleEndianShort(
                                            data,
                                            sampleOffset
                                        )
                                    }

                                abs(
                                    v /
                                    32768f
                                )
                            }

                            24 -> {

                                val v =
                                    if (bigEndian) {

                                        (
                                            (
                                                data[
                                                    sampleOffset
                                                ].toInt()
                                                    shl 16
                                            ) or
                                            (
                                                (
                                                    data[
                                                        sampleOffset + 1
                                                    ].toInt()
                                                        and 0xFF
                                                ) shl 8
                                            ) or
                                            (
                                                data[
                                                    sampleOffset + 2
                                                ].toInt()
                                                    and 0xFF
                                            )
                                        )
                                    } else {

                                        (
                                            (
                                                data[
                                                    sampleOffset + 2
                                                ].toInt()
                                                    shl 16
                                            ) or
                                            (
                                                (
                                                    data[
                                                        sampleOffset + 1
                                                    ].toInt()
                                                        and 0xFF
                                                ) shl 8
                                            ) or
                                            (
                                                data[
                                                    sampleOffset
                                                ].toInt()
                                                    and 0xFF
                                            )
                                        )
                                    }

                                abs(
                                    v /
                                    8388608f
                                )
                            }

                            32 -> {

                                val v =
                                    if (bigEndian) {
                                        bigEndianInt(
                                            data,
                                            sampleOffset
                                        )
                                    } else {
                                        littleEndianInt(
                                            data,
                                            sampleOffset
                                        )
                                    }

                                abs(
                                    v /
                                    2147483648f
                                )
                            }

                            else -> 0f
                        }

                    peak =
                        max(
                            peak,
                            value
                        )
                }
            }

            output[i] =
                peak.coerceIn(
                    0f,
                    1f
                )
        }

        return output
    }

    private fun littleEndianShort(
        data: ByteArray,
        offset: Int
    ): Int {

        return (
            (data[offset].toInt() and 0xFF) or
            (
                data[offset + 1].toInt()
                    shl 8
            )
        ).toShort().toInt()
    }

    private fun littleEndianInt(
        data: ByteArray,
        offset: Int
    ): Int {

        return (
            (data[offset].toInt() and 0xFF) or
            (
                (data[offset + 1].toInt() and 0xFF)
                    shl 8
            ) or
            (
                (data[offset + 2].toInt() and 0xFF)
                    shl 16
            ) or
            (
                data[offset + 3].toInt()
                    shl 24
            )
        )
    }

    private fun bigEndianShort(
        data: ByteArray,
        offset: Int
    ): Int {

        return (
            (
                data[offset].toInt()
                    shl 8
            ) or
            (
                data[offset + 1].toInt()
                    and 0xFF
            )
        ).toShort().toInt()
    }

    private fun bigEndianInt(
        data: ByteArray,
        offset: Int
    ): Int {

        return (
            (
                data[offset].toInt()
                    and 0xFF
            ) shl 24
        ) or
        (
            (
                data[offset + 1].toInt()
                    and 0xFF
            ) shl 16
        ) or
        (
            (
                data[offset + 2].toInt()
                    and 0xFF
            ) shl 8
        ) or
        (
            data[offset + 3].toInt()
                and 0xFF
        )
    }
}
