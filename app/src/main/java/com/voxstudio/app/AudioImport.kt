package com.voxstudio.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Detect content with Android's extractors and decode locally, independently of the filename. */
object AudioImport {
    const val MAX_SECONDS = 900
    fun duration(source: File): Double {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val track=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true }
                ?: error("This file has no readable audio.")
            val format=extractor.getTrackFormat(track)
            require(format.containsKey(MediaFormat.KEY_DURATION)) { "Could not determine audio duration. Try another recording." }
            val seconds=format.getLong(MediaFormat.KEY_DURATION)/1_000_000.0
            require(seconds in 3.0..MAX_SECONDS.toDouble()) { "Choose audio between 3 seconds and 15 minutes." }
            return seconds
        } finally { extractor.release() }
    }
    fun convert(source: File, destination: File, checkStop: () -> Unit = {}, progress: (Int) -> Unit = {}, startSeconds: Double = 0.0, clipSeconds: Double? = null) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("This file has no readable audio. Choose an audio recording such as MP3, M4A or WAV.")
            val format = extractor.getTrackFormat(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            require(duration <= MAX_SECONDS * 1_000_000L) { "Choose audio up to 15 minutes long, then select a short voice sample." }
            require(startSeconds.isFinite() && startSeconds>=0 && (clipSeconds==null || clipSeconds.isFinite() && clipSeconds in 3.0..20.0))
            val startUs=(startSeconds*1_000_000).toLong()
            val endUs=clipSeconds?.let { startUs+(it*1_000_000).toLong() } ?: Long.MAX_VALUE
            extractor.selectTrack(track)
            if(startUs>0) extractor.seekTo((startUs-1_000_000).coerceAtLeast(0),MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0); decoder.start(); started = true
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false; var outputDone = false
            var frame = 0L; var nextPosition = 0.0; var previous = 0.0; var outputFrames = 0
            var lastProgress = -1
            var lastActivity = SystemClock.elapsedRealtime()
            val info = MediaCodec.BufferInfo()
            AudioFiles.write(destination, ShortArray(0))
            java.io.FileOutputStream(destination, true).buffered().use { out ->
                while (!outputDone) {
                    checkStop()
                    require(SystemClock.elapsedRealtime() - lastActivity < 30_000) { "Audio decoding stalled. Try another recording." }
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = decoder.getInputBuffer(index)!!
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                            } else {
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance()
                            }
                            lastActivity = SystemClock.elapsedRealtime()
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val decoded = decoder.outputFormat
                        val newRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newChannels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(frame == 0L || (rate == newRate && channels == newChannels)) { "Audio format changed within this file." }
                        rate = newRate; channels = newChannels
                        encoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(rate in 8000..192000 && channels in 1..8 && encoding in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) { "This device cannot convert this audio format. Try MP3, M4A or 16-bit WAV." }
                    } else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buffer = decoder.getOutputBuffer(index)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val stride = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                require(info.size % stride == 0) { "The audio decoder returned an incomplete frame." }
                                val pcm = java.io.ByteArrayOutputStream()
                                var bufferFrame=0L
                                while (buffer.remaining() >= stride) {
                                    var sum = 0.0
                                    repeat(channels) { sum += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float.toDouble() * 32768.0 else buffer.short.toDouble() }
                                    val timeUs=info.presentationTimeUs + bufferFrame++ * 1_000_000 / rate
                                    if(timeUs<startUs) continue
                                    if(timeUs>=endUs) { outputDone=true; break }
                                    val current = (sum / channels).let { if (it.isFinite()) it else 0.0 }
                                    require(frame < rate.toLong() * MAX_SECONDS) { "Choose audio up to 15 minutes long." }
                                    while (nextPosition <= frame) {
                                        val fraction = if (frame == 0L) 1.0 else nextPosition - (frame - 1)
                                        val sample = (previous + (current - previous) * fraction).roundToInt().coerceIn(-32768,32767)
                                        pcm.write(sample and 255); pcm.write((sample shr 8) and 255)
                                        outputFrames++; nextPosition = outputFrames.toDouble() * rate / 16000
                                    }
                                    previous = current; frame++
                                }
                                out.write(pcm.toByteArray())
                                val percent = if (duration > 0) (info.presentationTimeUs * 100 / duration).toInt().coerceIn(0,99) else -1
                                if (percent != lastProgress) { progress(percent); lastProgress = percent }
                            }
                            outputDone = outputDone || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { decoder.releaseOutputBuffer(index, false) }
                        lastActivity = SystemClock.elapsedRealtime()
                    }
                }
            }
            if(clipSeconds!=null) {
                val missing=(clipSeconds*16000).roundToInt()-outputFrames
                // Codec delay/padding can remove a few milliseconds at the stream boundary.
                require(missing in -1600..1600) { "The selected clip extends past the decoded audio." }
                if(missing>0) java.io.FileOutputStream(destination,true).use { it.write(ByteArray(missing*2)) }
                if(missing<0) RandomAccessFile(destination,"rw").use { it.setLength(44L+(outputFrames+missing)*2L) }
                outputFrames+=missing
            }
            require(outputFrames >= 3 * 16000) { "Use a file with at least 3 seconds of speech." }
            RandomAccessFile(destination, "rw").use { out ->
                fun lengthAt(at: Long, value: Int) { out.seek(at); repeat(4) { out.write((value ushr (it * 8)) and 255) } }
                lengthAt(4, 36 + outputFrames * 2); lengthAt(40, outputFrames * 2)
            }
            progress(100)
        } catch (e: Exception) {
            destination.delete()
            if (e is java.util.concurrent.CancellationException || e is IllegalArgumentException || e is IllegalStateException && e !is MediaCodec.CodecException) throw e
            throw IllegalArgumentException("Could not read this audio on your phone. Try another MP3, M4A or WAV recording.", e)
        } finally {
            try { if (started) codec?.stop() } finally { try { codec?.release() } finally { extractor.release() } }
        }
    }
}
