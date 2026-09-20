package com.pocketdj

import android.content.Intent
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.audiofx.Equalizer
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
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
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var deckA: Deck
    private lateinit var deckB: Deck

    private val handler = Handler(Looper.getMainLooper())

    private var masterVolume = 1f
    private var limiterEnabled = true

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

        // Keep the app process alive for background audio playback.
        startBackgroundPlaybackService()

        handler.post(displayRunnable)
    }

    private fun startBackgroundPlaybackService() {
        try {
            val intent = Intent(this, BackgroundPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
            // Playback can still work normally if the service cannot start.
        }
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

        val masterLabel = TextView(this).apply {
            text = "MASTER 100%"
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(Color.GRAY)
        }

        root.addView(masterLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 24
        ).apply {
            setMargins(0, 10, 0, 2)
        })

        val master = SeekBar(this).apply {
            max = 100
            progress = 100
        }

        master.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                masterVolume = progress / 100f
                deckA.setMasterVolume(masterVolume)
                deckB.setMasterVolume(masterVolume)
                masterLabel.text = "MASTER ${(masterVolume * 100f).roundToInt()}%"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        root.addView(master, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 42
        ).apply {
            setMargins(0, 0, 0, 4)
        })
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
        private var masterVolumeForDeck = 1f

        private var baseSpeed = 1f
        private var bendAmount = 0f
        private var bassCutEnabled = false
        private var equalizer: Equalizer? = null
        private var originalBassLevels = ShortArray(0)
        private var deckLocked = false
        private var reverseMode = false
        private var reversePosition = 0L
        private var reverseWasPlaying = false
        private var cueHeld = false
        private var cueTouchDown = false
        private val reverseHandler = Handler(Looper.getMainLooper())
        private val reverseStep = object : Runnable {
            override fun run() {
                if (!reverseMode || deckLocked || loadedUri == null) return

                reversePosition =
                    (reversePosition - 400L).coerceAtLeast(0L)

                player.seekTo(reversePosition)

                if (reverseWasPlaying && !player.isPlaying) {
                    player.play()
                }

                if (reversePosition <= 0L) {
                    reverseMode = false
                    reverse.text = "REV"
                    return
                }

                reverseHandler.postDelayed(this, 80L)
            }
        }

        private var cuePosition = 0L

        private var loadedUri: Uri? = null

        private val trackName =
            TextView(this@MainActivity)

        private val position =
            TextView(this@MainActivity)

        private val bpm =
            TextView(this@MainActivity)

        private var detectedBpm: Double? = null

        private val waveform =
            WaveformView(this@MainActivity)

        private val seek =
            SeekBar(this@MainActivity)

        private val startButton =
            Button(this@MainActivity)

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

        private val pitchReset =
            Button(this@MainActivity)

        private val reverse =
            Button(this@MainActivity)

        private val pitchPercent =
            TextView(this@MainActivity)

        private val lockButton =
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
                detectedBpm = null
                bpm.text = "BPM --"
                readBpmFromMetadataAsync(uri)

                player.stop()
                waveform.reset()
                play.text = "LOADING"
                play.isEnabled = false
                cue.isEnabled = false
                seek.isEnabled = false

                val displayName =
                    uri.lastPathSegment
                        ?.substringAfterLast("/")
                        ?: "Audio file"

                trackName.text = displayName
                cuePosition = 0L

                /*
                 * Android/Media3 does not provide reliable direct AIFF
                 * container playback on all devices. Convert PCM AIFF
                 * to a temporary WAV file while preserving the original
                 * sample rate, channels and bit depth.
                 */
                preparePlayableUri(uri) { playableUri ->
                    loadedUri = playableUri

                    player.setMediaItem(
                        MediaItem.fromUri(playableUri)
                    )
                    player.prepare()

                    waveform.loadAudio(playableUri)
                    if (detectedBpm == null) {
                        bpm.text = "BPM …"
                        estimateBpmAsync(playableUri)
                    }
                    play.text = "PLAY"
                    play.isEnabled = !deckLocked
                    cue.isEnabled = !deckLocked
                    seek.isEnabled = !deckLocked
                }
            }

        private fun readBpmFromMetadataAsync(uri: Uri) {
            Thread {
                val value = try {
                    readBpmFromMetadata(uri)
                } catch (_: Exception) {
                    null
                }

                bpm.post {
                    detectedBpm = value
                    bpm.text = if (value != null) {
                        "BPM ${formatBpm(value)}"
                    } else {
                        "BPM --"
                    }
                }
            }.start()
        }

        private fun estimateBpmAsync(uri: Uri) {
            Thread {
                val value = try {
                    estimateBpm(uri)
                } catch (_: Exception) {
                    null
                }

                bpm.post {
                    if (detectedBpm == null && value != null) {
                        detectedBpm = value
                        bpm.text = "BPM ${formatBpm(value)}"
                    } else if (detectedBpm == null) {
                        bpm.text = "BPM --"
                    }
                }
            }.start()
        }

        /*
         * Lightweight fallback for tracks without BPM metadata.
         * We inspect only a few short windows, preferably after the intro,
         * then choose the tempo that repeats most consistently.
         * This runs once in the background and never during playback.
         */
        private fun estimateBpm(uri: Uri): Double? {
            val extractor = android.media.MediaExtractor()
            var decoder: android.media.MediaCodec? = null
            try {
                val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: return null
                try {
                    extractor.setDataSource(descriptor.fileDescriptor)
                } finally {
                    descriptor.close()
                }

                var trackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) { trackIndex = i; break }
                }
                if (trackIndex < 0) return null
                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: return null
                val durationUs = if (format.containsKey(android.media.MediaFormat.KEY_DURATION))
                    format.getLong(android.media.MediaFormat.KEY_DURATION) else return null
                if (durationUs < 8_000_000L) return null

                val sampleRateHint = if (format.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE))
                    format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) else 48_000

                val starts = ArrayList<Long>()
                val preferred = longArrayOf(30_000_000L, 60_000_000L, 120_000_000L, 240_000_000L)
                for (start in preferred) if (start + 8_000_000L < durationUs) starts.add(start)
                if (starts.isEmpty()) starts.add((durationUs / 2L).coerceAtLeast(0L))
                val maxSamples = 4
                while (starts.size > maxSamples) starts.removeAt(starts.lastIndex)

                val estimates = ArrayList<Double>()
                for (startUs in starts) {
                    val estimate = estimateBpmWindow(extractor, mime, format, startUs, sampleRateHint)
                    if (estimate != null) estimates.add(estimate)
                }
                if (estimates.isEmpty()) return null

                // Quantize to quarter-BPM bins and prefer the cluster with the most agreement.
                val clusters = estimates.groupBy { kotlin.math.round(it * 4.0) / 4.0 }
                val best = clusters.entries.maxByOrNull { it.value.size }?.key ?: return null
                val close = estimates.filter { kotlin.math.abs(it - best) <= 2.0 }
                return if (close.size >= 2 || estimates.size == 1) close.average() else null
            } finally {
                try { decoder?.stop() } catch (_: Exception) {}
                try { decoder?.release() } catch (_: Exception) {}
                try { extractor.release() } catch (_: Exception) {}
            }
        }

        private fun estimateBpmWindow(
            extractor: android.media.MediaExtractor,
            mime: String,
            format: android.media.MediaFormat,
            startUs: Long,
            sampleRateHint: Int
        ): Double? {
            extractor.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val sampleRate = sampleRateHint.coerceIn(8_000, 96_000)
            val envelopeRate = 100
            val envelope = FloatArray(800)
            var envelopeIndex = 0
            var decoder: android.media.MediaCodec? = null

            fun addPcm(buffer: java.nio.ByteBuffer, channels: Int) {
                val bytesPerFrame = 2 * channels
                if (bytesPerFrame <= 0) return
                val framesPerBin = max(1, sampleRate / envelopeRate)
                var sum = 0.0
                var count = 0
                while (buffer.remaining() >= bytesPerFrame && envelopeIndex < envelope.size) {
                    var energy = 0f
                    repeat(channels) {
                        val lo = buffer.get().toInt() and 0xFF
                        val hi = buffer.get().toInt()
                        val sample = ((hi shl 8) or lo).toShort().toInt() / 32768f
                        energy += abs(sample)
                    }
                    sum += energy / channels
                    count++
                    if (count >= framesPerBin) {
                        envelope[envelopeIndex++] = (sum / count).toFloat()
                        sum = 0.0
                        count = 0
                    }
                }
            }

            try {
                var channels = if (format.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT))
                    format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 2

                if (mime == "audio/raw") {
                    val endUs = startUs + 8_000_000L
                    while (extractor.sampleTime < endUs && envelopeIndex < envelope.size) {
                        val buffer = java.nio.ByteBuffer.allocateDirect(64 * 1024)
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        buffer.limit(size)
                        addPcm(buffer, channels)
                        if (!extractor.advance()) break
                    }
                } else {
                    decoder = android.media.MediaCodec.createDecoderByType(mime)
                    decoder.configure(format, null, null, 0)
                    decoder.start()
                    val info = android.media.MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    val endUs = startUs + 8_000_000L
                    while (!outputDone && envelopeIndex < envelope.size) {
                        if (!inputDone) {
                            val inputIndex = decoder.dequeueInputBuffer(10_000L)
                            if (inputIndex >= 0) {
                                val input = decoder.getInputBuffer(inputIndex)
                                if (input != null) {
                                    input.clear()
                                    val size = extractor.readSampleData(input, 0)
                                    val time = extractor.sampleTime
                                    if (size < 0 || time > endUs) {
                                        decoder.queueInputBuffer(inputIndex, 0, 0, endUs,
                                            android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        inputDone = true
                                    } else {
                                        decoder.queueInputBuffer(inputIndex, 0, size, time, 0)
                                        extractor.advance()
                                    }
                                }
                            }
                        }
                        val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)
                        when {
                            outputIndex == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val out = decoder.outputFormat
                                if (out.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT))
                                    channels = out.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            }
                            outputIndex >= 0 -> {
                                val out = decoder.getOutputBuffer(outputIndex)
                                if (out != null && info.size > 0) {
                                    val start = info.offset.coerceIn(0, out.capacity())
                                    val end = (info.offset + info.size).coerceIn(start, out.capacity())
                                    out.position(start); out.limit(end)
                                    addPcm(out, channels)
                                }
                                val eos = (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                decoder.releaseOutputBuffer(outputIndex, false)
                                if (eos) outputDone = true
                            }
                        }
                    }
                }

                if (envelopeIndex < 120) return null
                // Remove DC-like level and emphasize changes, which makes beat pulses clearer.
                var mean = 0.0
                for (i in 0 until envelopeIndex) mean += envelope[i]
                mean /= envelopeIndex
                for (i in 0 until envelopeIndex) envelope[i] = max(0f, envelope[i] - mean.toFloat())

                var bestBpm = 0.0
                var bestScore = Double.NEGATIVE_INFINITY
                for (bpmCandidate in 70..180) {
                    val lag = (envelopeRate * 60.0 / bpmCandidate).roundToInt()
                    if (lag <= 0 || lag >= envelopeIndex / 2) continue
                    var score = 0.0
                    var count = 0
                    var i = lag
                    while (i < envelopeIndex) {
                        score += envelope[i].toDouble() * envelope[i - lag].toDouble()
                        count++
                        i++
                    }
                    if (count > 0) {
                        score /= count
                        if (score > bestScore) { bestScore = score; bestBpm = bpmCandidate.toDouble() }
                    }
                }
                return bestBpm.takeIf { it in 70.0..180.0 }
            } finally {
                try { decoder?.stop() } catch (_: Exception) {}
                try { decoder?.release() } catch (_: Exception) {}
            }
        }

        private fun formatBpm(value: Double): String {
            val rounded = kotlin.math.round(value * 10.0) / 10.0
            return if (rounded == kotlin.math.round(rounded)) {
                rounded.toInt().toString()
            } else {
                "%.1f".format(rounded)
            }
        }

        private fun readBpmFromMetadata(uri: Uri): Double? {
            val name = uri.lastPathSegment?.lowercase() ?: ""
            return when {
                name.endsWith(".mp3") -> readMp3Bpm(uri)
                name.endsWith(".flac") -> readFlacBpm(uri)
                name.endsWith(".ogg") || name.endsWith(".oga") -> readVorbisBpm(uri)
                name.endsWith(".m4a") || name.endsWith(".mp4") -> readMp4Bpm(uri)
                name.endsWith(".wav") || name.endsWith(".aif") ||
                    name.endsWith(".aiff") || name.endsWith(".aifc") -> readInfoBpm(uri)
                else -> null
            }
        }

        private fun readAsciiTagValue(bytes: ByteArray, key: String): Double? {
            val text = String(bytes, Charsets.ISO_8859_1)
            val regex = Regex("(?i)(?:^|\\u0000|\\n|\\r)" + Regex.escape(key) + "\\s*[:=]\\s*([0-9]{2,3}(?:\\.[0-9]+)?)")
            val match = regex.find(text) ?: return null
            return match.groupValues[1].toDoubleOrNull()?.takeIf { it in 20.0..300.0 }
        }

        private fun readMp3Bpm(uri: Uri): Double? {
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use {
                val header = ByteArray(10)
                if (it.read(header) != 10) return null
                if (String(header, 0, 3, Charsets.ISO_8859_1) == "ID3") {
                    val size = ((header[6].toInt() and 0x7F) shl 21) or
                        ((header[7].toInt() and 0x7F) shl 14) or
                        ((header[8].toInt() and 0x7F) shl 7) or
                        (header[9].toInt() and 0x7F)
                    val safeSize = minOf(size, 2 * 1024 * 1024)
                    val data = ByteArray(safeSize)
                    val n = it.read(data)
                    if (n > 0) {
                        val text = String(data, 0, n, Charsets.ISO_8859_1)
                        Regex("(?i)TBPM[^0-9]{0,8}([0-9]{2,3}(?:\\.[0-9]+)?)").find(text)?.let { m ->
                            return m.groupValues[1].toDoubleOrNull()?.takeIf { v -> v in 20.0..300.0 }
                        }
                    }
                }
            }
            return null
        }

        private fun readVorbisBpm(uri: Uri): Double? {
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use {
                val data = ByteArray(2 * 1024 * 1024)
                val n = it.read(data)
                if (n <= 0) return null
                val text = String(data, 0, n, Charsets.ISO_8859_1)
                return Regex("(?i)(?:BPM|TBPM)\\s*=\\s*([0-9]{2,3}(?:\\.[0-9]+)?)").find(text)?.groupValues?.get(1)
                    ?.toDoubleOrNull()?.takeIf { v -> v in 20.0..300.0 }
            }
        }

        private fun readFlacBpm(uri: Uri): Double? = readVorbisBpm(uri)

        private fun readMp4Bpm(uri: Uri): Double? {
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use {
                val data = ByteArray(4 * 1024 * 1024)
                val n = it.read(data)
                if (n <= 0) return null
                // Common iTunes/MP4 tempo atom: tmpo, stored as a 16-bit integer.
                for (i in 4 until n - 6) {
                    if (data[i].toInt().toChar() == 't' &&
                        data[i + 1].toInt().toChar() == 'm' &&
                        data[i + 2].toInt().toChar() == 'p' &&
                        data[i + 3].toInt().toChar() == 'o') {
                        val bpmValue = ((data[i + 4].toInt() and 0xFF) shl 8) or
                            (data[i + 5].toInt() and 0xFF)
                        if (bpmValue in 20..300) return bpmValue.toDouble()
                    }
                }
                return null
            }
        }

        private fun readInfoBpm(uri: Uri): Double? {
            // Lightweight fallback for textual BPM tags in WAV/AIFF metadata chunks.
            // We only inspect the first 2 MB, so loading a large recording remains cheap.
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use {
                val data = ByteArray(2 * 1024 * 1024)
                val n = it.read(data)
                if (n <= 0) return null
                return readAsciiTagValue(data.copyOf(n), "BPM")
            }
        }

        private fun preparePlayableUri(
            uri: Uri,
            onReady: (Uri) -> Unit
        ) {
            if (!isAiffUri(uri)) {
                onReady(uri)
                return
            }

            Thread {
                val converted = try {
                    convertAiffToWav(uri)
                } catch (_: Exception) {
                    null
                }

                waveform.post {
                    if (converted != null) {
                        onReady(Uri.fromFile(converted))
                    } else {
                        trackName.text = "AIFF could not be decoded"
                        play.text = "PLAY"
                        play.isEnabled = true
                    }
                }
            }.start()
        }

        private fun isAiffUri(uri: Uri): Boolean {
            val name = uri.lastPathSegment?.lowercase() ?: return false
            return name.endsWith(".aiff") ||
                name.endsWith(".aif") ||
                name.endsWith(".aifc")
        }

        private fun readFully(input: InputStream, buffer: ByteArray) {
            var offset = 0
            while (offset < buffer.size) {
                val n = input.read(buffer, offset, buffer.size - offset)
                if (n < 0) throw java.io.EOFException()
                offset += n
            }
        }

        private fun readU16BE(b: ByteArray): Int =
            ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)

        private fun readU32BE(b: ByteArray): Long =
            ((b[0].toLong() and 0xFF) shl 24) or
                ((b[1].toLong() and 0xFF) shl 16) or
                ((b[2].toLong() and 0xFF) shl 8) or
                (b[3].toLong() and 0xFF)

        private fun readIeeeExtended(b: ByteArray): Double {
            val exponent = ((b[0].toInt() and 0x7F) shl 8) or
                (b[1].toInt() and 0xFF)
            if (exponent == 0) return 0.0

            var mantissa = 0.0
            for (i in 2 until 10) {
                mantissa = mantissa * 256.0 + (b[i].toInt() and 0xFF)
            }

            val value = mantissa / Math.pow(2.0, 63.0) *
                Math.pow(2.0, (exponent - 16383).toDouble())
            return if ((b[0].toInt() and 0x80) != 0) -value else value
        }

        private fun writeLe16(out: BufferedOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }

        private fun writeLe32(out: BufferedOutputStream, value: Long) {
            out.write((value and 0xFF).toInt())
            out.write(((value ushr 8) and 0xFF).toInt())
            out.write(((value ushr 16) and 0xFF).toInt())
            out.write(((value ushr 24) and 0xFF).toInt())
        }

        private fun writeFourCC(out: BufferedOutputStream, text: String) {
            out.write(text.toByteArray(Charsets.US_ASCII))
        }

        private fun writeWavHeader(
            out: BufferedOutputStream,
            channels: Int,
            sampleRate: Int,
            bits: Int,
            dataSize: Long
        ) {
            writeFourCC(out, "RIFF")
            writeLe32(out, 36L + dataSize)
            writeFourCC(out, "WAVE")
            writeFourCC(out, "fmt ")
            writeLe32(out, 16)
            writeLe16(out, 1)
            writeLe16(out, channels)
            writeLe32(out, sampleRate.toLong())
            val blockAlign = channels * (bits / 8)
            val byteRate = sampleRate.toLong() * blockAlign
            writeLe32(out, byteRate)
            writeLe16(out, blockAlign)
            writeLe16(out, bits)
            writeFourCC(out, "data")
            writeLe32(out, dataSize)
        }

        private fun convertAiffToWav(uri: Uri): File {
            val input = BufferedInputStream(
                contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Unable to open AIFF")
            )

            var outputFile: File? = null
            var output: BufferedOutputStream? = null

            try {
                val form = ByteArray(4)
                val formSizeBytes = ByteArray(4)
                val formType = ByteArray(4)
                readFully(input, form)
                readFully(input, formSizeBytes)
                readFully(input, formType)

                val formText = String(form, Charsets.US_ASCII)
                val typeText = String(formType, Charsets.US_ASCII)
                if (formText != "FORM" || (typeText != "AIFF" && typeText != "AIFC")) {
                    throw java.io.IOException("Not an AIFF file")
                }

                var channels = 0
                var sampleRate = 0
                var bits = 0
                var compression = "NONE"
                var audioFound = false
                var dataSize = 0L
                var dataOffset = 0L

                while (!audioFound) {
                    val id = ByteArray(4)
                    val sizeBytes = ByteArray(4)
                    try {
                        readFully(input, id)
                        readFully(input, sizeBytes)
                    } catch (_: java.io.EOFException) {
                        break
                    }

                    val chunkId = String(id, Charsets.US_ASCII)
                    val chunkSize = readU32BE(sizeBytes)

                    when (chunkId) {
                        "COMM" -> {
                            val common = ByteArray(chunkSize.toInt().coerceAtMost(32))
                            readFully(input, common)
                            if (common.size < 18) throw java.io.IOException("Invalid COMM chunk")

                            channels = readU16BE(common.copyOfRange(0, 2))
                            bits = readU16BE(common.copyOfRange(6, 8))
                            sampleRate = readIeeeExtended(common.copyOfRange(8, 18)).toInt()

                            if (typeText == "AIFC" && common.size >= 22) {
                                compression = String(common.copyOfRange(18, 22), Charsets.US_ASCII)
                                val remaining = chunkSize - common.size
                                if (remaining > 0) {
                                    val skip = ByteArray(8192)
                                    var left = remaining
                                    while (left > 0) {
                                        val n = input.read(skip, 0, minOf(skip.size.toLong(), left).toInt())
                                        if (n < 0) throw java.io.EOFException()
                                        left -= n
                                    }
                                }
                            } else {
                                val remaining = chunkSize - common.size
                                if (remaining > 0) {
                                    input.skip(remaining)
                                }
                            }
                        }

                        "SSND" -> {
                            val header = ByteArray(8)
                            readFully(input, header)
                            val offset = readU32BE(header.copyOfRange(0, 4))
                            val remainingAudio = chunkSize - 8L
                            if (offset > remainingAudio) throw java.io.IOException("Invalid SSND offset")

                            var leftOffset = offset
                            val skip = ByteArray(8192)
                            while (leftOffset > 0) {
                                val n = input.read(skip, 0, minOf(skip.size.toLong(), leftOffset).toInt())
                                if (n < 0) throw java.io.EOFException()
                                leftOffset -= n
                            }

                            dataSize = remainingAudio - offset
                            if (channels <= 0 || sampleRate <= 0 || bits !in 8..32 || dataSize <= 0) {
                                throw java.io.IOException("Unsupported AIFF audio format")
                            }
                            if (compression != "NONE" && compression != "sowt") {
                                throw java.io.IOException("Unsupported AIFC compression")
                            }

                            outputFile = File(cacheDir, "aiff_${System.nanoTime()}.wav")
                            output = BufferedOutputStream(FileOutputStream(outputFile))
                            writeWavHeader(output, channels, sampleRate, bits, dataSize)

                            val bytesPerSample = bits / 8
                            val block = ByteArray(64 * 1024)
                            // AIFF PCM is big-endian. WAV PCM is little-endian.
                            // Keep incomplete samples between chunks so 24-bit audio
                            // is never re-aligned incorrectly at a 64 KB boundary.
                            val carry = ByteArray(4)
                            var carrySize = 0
                            var remaining = dataSize
                            while (remaining > 0) {
                                val want = minOf(block.size.toLong(), remaining).toInt()
                                val n = input.read(block, 0, want)
                                if (n < 0) throw java.io.EOFException()

                                if (compression == "sowt" || bytesPerSample == 1) {
                                    if (bytesPerSample == 1) {
                                        for (i in 0 until n) {
                                            output.write((block[i].toInt() and 0xFF) xor 0x80)
                                        }
                                    } else {
                                        output.write(block, 0, n)
                                    }
                                } else {
                                    var offset = 0

                                    // Complete a sample left over from the previous chunk.
                                    if (carrySize > 0) {
                                        val need = bytesPerSample - carrySize
                                        if (n >= need) {
                                            System.arraycopy(block, 0, carry, carrySize, need)
                                            var j = bytesPerSample - 1
                                            while (j >= 0) {
                                                output.write(carry[j].toInt() and 0xFF)
                                                j--
                                            }
                                            offset = need
                                            carrySize = 0
                                        } else {
                                            System.arraycopy(block, 0, carry, carrySize, n)
                                            carrySize += n
                                            remaining -= n
                                            continue
                                        }
                                    }

                                    val completeBytes = ((n - offset) / bytesPerSample) * bytesPerSample
                                    var i = offset
                                    val end = offset + completeBytes
                                    while (i < end) {
                                        var j = bytesPerSample - 1
                                        while (j >= 0) {
                                            output.write(block[i + j].toInt() and 0xFF)
                                            j--
                                        }
                                        i += bytesPerSample
                                    }

                                    val leftover = n - end
                                    if (leftover > 0) {
                                        System.arraycopy(block, end, carry, 0, leftover)
                                        carrySize = leftover
                                    }
                                }

                                remaining -= n
                            }

                            if (carrySize != 0) {
                                throw java.io.IOException("Incomplete AIFF PCM sample")
                            }

                            audioFound = true
                        }

                        else -> {
                            var left = chunkSize
                            val skip = ByteArray(8192)
                            while (left > 0) {
                                val n = input.read(skip, 0, minOf(skip.size.toLong(), left).toInt())
                                if (n < 0) throw java.io.EOFException()
                                left -= n
                            }
                        }
                    }

                    if (chunkSize % 2L != 0L && !audioFound) {
                        input.read()
                    }
                }

                if (!audioFound || outputFile == null) {
                    throw java.io.IOException("AIFF audio data not found")
                }

                output!!.flush()
                output.close()
                output = null
                return outputFile!!
            } catch (e: Exception) {
                try { output?.close() } catch (_: Exception) {}
                outputFile?.delete()
                throw e
            } finally {
                try { input.close() } catch (_: Exception) {}
            }
        }

        init {

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
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

            bpm.apply {
                text = "BPM --"
                textSize = 16f
                setTextColor(Color.CYAN)
                gravity = Gravity.CENTER
                translationY = -6f
            }

            panel.addView(
                bpm,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    25
                )
            )

            waveform.setBackgroundColor(
                Color.rgb(5, 5, 5)
            )

            panel.addView(
                waveform,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    220
                )
            )

            position.apply {
                text = "00:00 / -00:00 / 00:00"
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
                            !deckLocked &&
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

            val seekRow =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

            startButton.text = "◀"
            styleButton(startButton)
            startButton.contentDescription = "Go to beginning"

            startButton.setOnClickListener {
                if (deckLocked || loadedUri == null) return@setOnClickListener

                val wasPlaying = player.isPlaying

                reverseMode = false
                reverseHandler.removeCallbacks(reverseStep)
                reverse.text = "REV"

                player.seekTo(0L)

                if (wasPlaying) {
                    player.play()
                    play.text = "PAUSE"
                } else {
                    player.pause()
                    play.text = "PLAY"
                }
            }

            seekRow.addView(
                startButton,
                LinearLayout.LayoutParams(52, 32).apply {
                    setMargins(2, 0, 4, 0)
                }
            )

            seekRow.addView(
                seek,
                LinearLayout.LayoutParams(0, 32, 1f)
            )

            panel.addView(seekRow)

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

            lockButton.text = "LOCK"
            styleButton(lockButton)

            pitchReset.text = "RESET"
            styleButton(pitchReset)

            reverse.text = "REV"
            styleButton(reverse)

            reverse.setOnClickListener {

                if (deckLocked || loadedUri == null) return@setOnClickListener

                if (reverseMode) {
                    reverseMode = false
                    reverseHandler.removeCallbacks(reverseStep)
                    reverse.text = "REV"
                    play.text = "PLAY"
                    return@setOnClickListener
                }

                // Media3/ExoPlayer does not provide native reverse audio playback.
                // REV therefore performs a reliable reverse scrub of the playhead.
                // Keep our own position so repeated seekTo() calls cannot stall.
                reversePosition = player.currentPosition.coerceAtLeast(0L)
                reverseWasPlaying = player.isPlaying
                reverseMode = true
                reverse.text = "REV ◀"

                if (reverseWasPlaying) {
                    player.play()
                    play.text = "PAUSE"
                } else {
                    player.pause()
                    play.text = "PLAY"
                }

                reverseHandler.removeCallbacks(reverseStep)
                reverseHandler.post(reverseStep)
            }

            lockButton.setOnClickListener {

                deckLocked = !deckLocked

                if (deckLocked) {
                    lockButton.text = "UNLOCK"
                    lockButton.setTextColor(Color.BLACK)
                    lockButton.setBackgroundColor(Color.rgb(255, 190, 0))

                    load.isEnabled = false
                    play.isEnabled = false
                    cue.isEnabled = false
                    seek.isEnabled = false
                    startButton.isEnabled = false
                    reverseMode = false
                    reverseHandler.removeCallbacks(reverseStep)
                    reverse.text = "REV"
                    reverse.isEnabled = false
                } else {
                    lockButton.text = "LOCK"
                    lockButton.setTextColor(Color.WHITE)
                    lockButton.setBackgroundColor(Color.rgb(45, 45, 45))

                    load.isEnabled = true
                    play.isEnabled = loadedUri != null
                    cue.isEnabled = loadedUri != null
                    seek.isEnabled = loadedUri != null
                    startButton.isEnabled = loadedUri != null
                    reverse.isEnabled = loadedUri != null
                }
            }

            load.setOnClickListener {

                if (deckLocked) return@setOnClickListener

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

                if (deckLocked) return@setOnClickListener

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

            var cueHoldStartedPlayback = false

            val cueHoldRunnable = object : Runnable {
                override fun run() {
                    if (!cueTouchDown || deckLocked) return

                    if (!player.isPlaying) {
                        // Hold while stopped/paused: play from the cue point.
                        cueHeld = true
                        cueHoldStartedPlayback = true
                        player.seekTo(cuePosition)
                        player.play()
                        play.text = "PAUSE"
                        cue.text = "CUE ▶"
                    } else {
                        // Hold while playing: create a new cue point here.
                        // Playback continues and release does NOT jump back.
                        cueHeld = true
                        cueHoldStartedPlayback = false
                        cuePosition = player.currentPosition.coerceAtLeast(0L)
                        cue.text = "CUE SET"
                    }
                }
            }

            cue.setOnTouchListener { _, event ->

                if (deckLocked) return@setOnTouchListener true

                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {

                        cueTouchDown = true
                        cue.removeCallbacks(cueHoldRunnable)
                        cueHeld = false
                        cueHoldStartedPlayback = false

                        if (!player.isPlaying) {
                            // Tap while stopped/paused: set the cue point.
                            cuePosition =
                                player.currentPosition.coerceAtLeast(0L)
                            cue.text = "CUE SET"
                        }

                        // A hold is recognized after 250 ms.
                        cue.postDelayed(cueHoldRunnable, 250L)

                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {

                        cueTouchDown = false
                        cue.removeCallbacks(cueHoldRunnable)

                        if (cueHeld) {

                            if (cueHoldStartedPlayback) {
                                // Hold from stopped/paused: release stops and
                                // returns to the cue point.
                                player.pause()
                                player.seekTo(cuePosition)
                                play.text = "PLAY"
                            } else {
                                // Hold while playing: keep playing at the new
                                // cue point. Do NOT seek on release.
                            }

                        } else if (player.isPlaying) {

                            // Short press while playing: return to the last cue
                            // and continue playing.
                            player.seekTo(cuePosition)
                            player.play()
                            play.text = "PAUSE"
                        }

                        cueHeld = false
                        cueHoldStartedPlayback = false
                        cue.text = "CUE"
                        true
                    }

                    else -> true
                }
            }

            bass.setOnClickListener {

                bassCutEnabled = !bassCutEnabled
                applyBassCut()

                if (bassCutEnabled) {

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
                    56,
                    1f
                ).apply { setMargins(2, 2, 2, 2) }
            )

            controls.addView(
                play,
                LinearLayout.LayoutParams(
                    0,
                    56,
                    1f
                ).apply { setMargins(2, 2, 2, 2) }
            )

            controls.addView(
                cue,
                LinearLayout.LayoutParams(
                    0,
                    56,
                    1f
                ).apply { setMargins(2, 2, 2, 2) }
            )

            controls.addView(
                lockButton,
                LinearLayout.LayoutParams(
                    0,
                    56,
                    1f
                ).apply { setMargins(2, 2, 2, 2) }
            )

            controls.addView(
                bass,
                LinearLayout.LayoutParams(
                    0,
                    56,
                    1f
                ).apply { setMargins(2, 2, 2, 2) }
            )

            panel.addView(controls)

            play.isEnabled = loadedUri != null
            cue.isEnabled = loadedUri != null
            seek.isEnabled = loadedUri != null
            startButton.isEnabled = loadedUri != null
            reverse.isEnabled = loadedUri != null

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

            pitchReset.setOnClickListener {
                baseSpeed = 1f
                bendAmount = 0f
                speed.progress = 50
                applySpeed()
            }

            pitchRow.addView(
                bendDown,
                LinearLayout.LayoutParams(
                    62,
                    52
                ).apply { setMargins(2, 2, 4, 2) }
            )

            pitchRow.addView(
                reverse,
                LinearLayout.LayoutParams(
                    62,
                    52
                ).apply { setMargins(2, 2, 6, 2) }
            )

            pitchRow.addView(
                speed,
                LinearLayout.LayoutParams(
                    0,
                    52,
                    1f
                ).apply { setMargins(0, 2, 0, 2) }
            )

            pitchRow.addView(
                pitchReset,
                LinearLayout.LayoutParams(
                    76,
                    52
                ).apply { setMargins(8, 2, 6, 2) }
            )

            pitchRow.addView(
                bendUp,
                LinearLayout.LayoutParams(
                    62,
                    52
                ).apply { setMargins(6, 2, 2, 2) }
            )

            panel.addView(pitchRow)

            pitchPercent.apply {
                text = "0%"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.GRAY)
            }

            panel.addView(
                pitchPercent,
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

                textSize = 12f
                isAllCaps = false

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

        private fun applyBassCut() {

            try {
                if (equalizer == null) {
                    val sessionId = player.audioSessionId

                    if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) {
                        handler.postDelayed({ applyBassCut() }, 200)
                        return
                    }

                    equalizer = Equalizer(0, sessionId).apply {
                        enabled = true
                    }

                    val eq = equalizer ?: return
                    val bands = eq.numberOfBands.toInt()
                    originalBassLevels = ShortArray(bands)

                    for (i in 0 until bands) {
                        val band = i.toShort()
                        originalBassLevels[i] = eq.getBandLevel(band)
                    }
                }

                val eq = equalizer ?: return
                val range = eq.bandLevelRange
                val minimumLevel = range[0]
                val bassCutLevel = max(
                    minimumLevel.toInt(),
                    -1200
                ).toShort()

                for (i in 0 until eq.numberOfBands.toInt()) {
                    val band = i.toShort()
                    val frequencyHz =
                        eq.getCenterFreq(band).toLong() / 1000L

                    if (bassCutEnabled && frequencyHz <= 250L) {
                        eq.setBandLevel(band, bassCutLevel)
                    } else if (i < originalBassLevels.size) {
                        eq.setBandLevel(band, originalBassLevels[i])
                    }
                }

            } catch (_: Exception) {
                // Some Android devices do not expose an EQ for the player session.
            }
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

            val percent =
                ((finalSpeed - 1f) * 100f).roundToInt()

            pitchPercent.text =
                if (percent > 0) "+${percent}%" else "${percent}%"

            player.setPlaybackParameters(
                PlaybackParameters(
                    finalSpeed,
                    finalSpeed
                )
            )
        }

        fun setMasterVolume(value: Float) {
            masterVolumeForDeck = value.coerceIn(0f, 1f)
            updateVolume()
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
                        channelVolume * mixerVolume * masterVolumeForDeck
                    )
                )
        }

        fun updateDisplay() {

            detectedBpm?.let { originalBpm ->
                val liveBpm = originalBpm *
                    min(2.0, max(0.1, baseSpeed.toDouble() + bendAmount.toDouble()))
                bpm.text = "BPM ${formatBpm(liveBpm)}"
            }

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

                val remaining =
                    (duration - current).coerceAtLeast(0L)

                position.text =
                    "${formatTime(current)} / -${formatTime(remaining)} / " +
                        formatTime(duration)

            } else {

                waveform.progress = 0f

                position.text =
                    "00:00 / -00:00 / 00:00"
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
            try {
                equalizer?.release()
            } catch (_: Exception) {
            }
            equalizer = null
            reverseMode = false
            reverseHandler.removeCallbacks(reverseStep)
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
             * Decode audio in small buffers and keep only 160 peak values.
             * Long recordings therefore do not need to be loaded into RAM.
             */
            val extractor = android.media.MediaExtractor()
            var decoder: android.media.MediaCodec? = null

            try {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return FloatArray(0)
                try {
                    extractor.setDataSource(descriptor.fileDescriptor)
                } finally {
                    descriptor.close()
                }

                var trackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val trackFormat = extractor.getTrackFormat(i)
                    val trackMime = trackFormat.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                    if (trackMime.startsWith("audio/")) {
                        trackIndex = i
                        break
                    }
                }
                if (trackIndex < 0) return FloatArray(0)

                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                    ?: return FloatArray(0)
                val durationUs = if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    format.getLong(android.media.MediaFormat.KEY_DURATION)
                } else -1L
                if (durationUs <= 0L) return FloatArray(0)

                val output = FloatArray(640)
                val sumSquares = DoubleArray(output.size)
                val counts = IntArray(output.size)
                val peaks = FloatArray(output.size)
                var sampleRate = if (format.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                    format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                } else 48_000
                var channels = if (format.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                    format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                } else 2
                channels = channels.coerceAtLeast(1)
                val totalFrames = ((durationUs * sampleRate) / 1_000_000L).coerceAtLeast(1L)

                fun addPcm16Samples(buffer: java.nio.ByteBuffer, presentationTimeUs: Long) {
                    val bytesPerFrame = 2 * channels
                    var frameIndex = ((presentationTimeUs.coerceAtLeast(0L) * sampleRate) / 1_000_000L)
                    while (buffer.remaining() >= bytesPerFrame) {
                        var framePeak = 0f
                        var frameSum = 0.0
                        repeat(channels) {
                            val lo = buffer.get().toInt() and 0xFF
                            val hi = buffer.get().toInt()
                            val sample = ((hi shl 8) or lo).toShort().toInt()
                            val value = abs(sample / 32768f)
                            framePeak = max(framePeak, value)
                            frameSum += value.toDouble() * value.toDouble()
                        }
                        val bin = ((frameIndex * output.size) / totalFrames)
                            .toInt().coerceIn(0, output.lastIndex)
                        sumSquares[bin] += frameSum / channels
                        counts[bin]++
                        peaks[bin] = max(peaks[bin], framePeak)
                        frameIndex++
                    }
                }

                /* WAV/AIFF PCM can be exposed directly by MediaExtractor. */
                if (mime == "audio/raw") {
                    while (true) {
                        val buffer = java.nio.ByteBuffer.allocateDirect(64 * 1024)
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        buffer.limit(size)
                        val time = extractor.sampleTime
                        addPcm16Samples(buffer, time)
                        if (!extractor.advance()) break
                    }
                    for (i in output.indices) {
                        val rms = if (counts[i] > 0) {
                            kotlin.math.sqrt(sumSquares[i] / counts[i]).toFloat()
                        } else 0f
                        output[i] = max(rms * 0.72f, peaks[i] * 0.28f)
                    }
                    return output
                }

                val codec = android.media.MediaCodec.createDecoderByType(mime)
                decoder = codec
                codec.configure(format, null, null, 0)
                codec.start()

                val bufferInfo = android.media.MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000L)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inputIndex, 0, 0, 0L,
                                        android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outputIndex == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = codec.outputFormat
                            if (out.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = out.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (out.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                                channels = out.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            }
                        }
                        outputIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                val start = bufferInfo.offset.coerceIn(0, outputBuffer.capacity())
                                val end = (bufferInfo.offset + bufferInfo.size)
                                    .coerceIn(start, outputBuffer.capacity())
                                outputBuffer.position(start)
                                outputBuffer.limit(end)
                                addPcm16Samples(outputBuffer, bufferInfo.presentationTimeUs)
                            }

                            val eos = (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (eos) outputDone = true
                        }
                    }
                }

                for (i in output.indices) {
                    val rms = if (counts[i] > 0) {
                        kotlin.math.sqrt(sumSquares[i] / counts[i]).toFloat()
                    } else 0f
                    output[i] = max(rms * 0.72f, peaks[i] * 0.28f)
                }

                return output
            } finally {
                try { decoder?.stop() } catch (_: Exception) {}
                try { decoder?.release() } catch (_: Exception) {}
                try { extractor.release() } catch (_: Exception) {}
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

            // Subtle waveform grid.
            paint.color = Color.rgb(28, 28, 28)
            paint.strokeWidth = 1f

            for (i in 1..7) {
                val x = w * i / 8f
                canvas.drawLine(x, 0f, x, h, paint)
            }

            canvas.drawLine(0f, h / 2f, w, h / 2f, paint)

            // A second horizontal guide makes the larger waveform easier to read.
            canvas.drawLine(0f, h * 0.25f, w, h * 0.25f, paint)
            canvas.drawLine(0f, h * 0.75f, w, h * 0.75f, paint)

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

                paint.strokeWidth = 3f

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
