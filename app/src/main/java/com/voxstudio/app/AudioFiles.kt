package com.voxstudio.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Bounded PCM WAV reader/writer. Keeps audio at the model's native 16 kHz. */
object AudioFiles {
    data class Wave(val rate: Int, val channels: Int, val samples: ShortArray) {
        val seconds: Double get() = samples.size.toDouble() / rate / channels
    }
    private fun RandomAccessFile.u16(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)
    private fun RandomAccessFile.u32(): Long = (u16().toLong() or (u16().toLong() shl 16))
    fun read(file: File, maxSeconds: Int = 180): Wave = RandomAccessFile(file, "r").use { f ->
        require(f.length() in 44..(32L * 1024 * 1024)) { "WAV file is empty or too large." }
        fun tag(): String = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
        require(tag() == "RIFF") { "Choose an uncompressed PCM WAV file." }
        val riffEnd = f.u32() + 8
        require(riffEnd <= f.length() && tag() == "WAVE") { "Incomplete WAV file." }
        var rate = 0; var channels = 0; var bits = 0; var format = 0
        var dataAt = -1L; var bytes = 0L
        while (f.filePointer + 8 <= riffEnd) {
            val id = tag(); val size = f.u32(); val start = f.filePointer
            require(start + size <= riffEnd) { "Invalid WAV chunk length." }
            if (id == "fmt ") {
                require(size >= 16) { "Invalid WAV format." }
                format = f.u16(); channels = f.u16(); rate = f.u32().toInt()
                f.u32(); f.u16(); bits = f.u16()
            }
            if (id == "data" && dataAt < 0) { dataAt = start; bytes = size }
            f.seek(start + size + size % 2)
        }
        require(format == 1 && bits == 16 && channels in 1..2 && rate in 8000..96000) {
            "Use a 16-bit PCM WAV (mono or stereo, 8–96 kHz)."
        }
        require(dataAt >= 0 && bytes > 0 && bytes % (channels * 2) == 0L) { "WAV has no valid PCM audio." }
        require(bytes <= rate.toLong() * channels * 2 * maxSeconds) { "Audio exceeds $maxSeconds seconds." }
        f.seek(dataAt)
        val data = ByteArray(bytes.toInt()).also { f.readFully(it) }
        val pcm = ShortArray((bytes / 2).toInt())
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        Wave(rate, channels, pcm)
    }
    private fun pcmBytes(samples: ShortArray): ByteArray {
        val data = ByteArray(samples.size * 2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        return data
    }
    fun write(file: File, samples: ShortArray, rate: Int = 16000) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            fun u16(n: Int) { f.write(n and 255); f.write((n ushr 8) and 255) }
            fun u32(n: Int) { u16(n); u16(n ushr 16) }
            f.writeBytes("RIFF"); u32(36 + samples.size * 2); f.writeBytes("WAVEfmt ")
            u32(16); u16(1); u16(1); u32(rate); u32(rate * 2); u16(2); u16(16)
            f.writeBytes("data"); u32(samples.size * 2)
            f.write(pcmBytes(samples))
        }
    }
    fun reference(source: File, output: File, startSeconds: Double = 0.0, durationSeconds: Double? = null) {
        val input = read(source, 900)
        val duration = durationSeconds ?: input.seconds
        require(startSeconds.isFinite() && duration.isFinite() && startSeconds >= 0 && duration in 3.0..20.0) {
            "Choose a 3–20 second sample and a valid start time."
        }
        require(startSeconds + duration <= input.seconds + 0.00001) { "The sample extends past the end of this file." }
        val firstFrame = (startSeconds * input.rate).toInt()
        val frames = (duration * input.rate).roundToInt().coerceAtMost(input.samples.size / input.channels - firstFrame)
        val count = (frames.toDouble() * 16000 / input.rate).toInt()
        val mono = ShortArray(count) { i ->
            val pos = i.toDouble() * input.rate / 16000
            val a = pos.toInt().coerceAtMost(frames - 1); val b = (a + 1).coerceAtMost(frames - 1)
            fun sample(frame: Int) = (0 until input.channels).sumOf { input.samples[(firstFrame + frame) * input.channels + it].toInt() }.toDouble() / input.channels
            (sample(a) * (1 - (pos - a)) + sample(b) * (pos - a)).roundToInt().coerceIn(-32768,32767).toShort()
        }
        val rms = kotlin.math.sqrt(mono.sumOf { it.toDouble() * it } / mono.size)
        require(rms > 30) { "Reference is silent or too quiet. Record closer to the microphone." }
        write(output, mono)
    }
    fun join(inputs: List<File>, output: File) {
        require(inputs.isNotEmpty()) { "No completed sections." }
        // Stream the joined file; a long script never needs all audio in memory.
        RandomAccessFile(output, "rw").use { dest ->
            dest.setLength(0); dest.write(ByteArray(44))
            var frames = 0L
            inputs.forEachIndexed { index, file ->
                val wave = read(file)
                require(wave.rate == 16000 && wave.channels == 1) { "Unexpected model output format." }
                if (index > 0) { dest.write(ByteArray(8000)); frames += 4000 }
                dest.write(pcmBytes(wave.samples))
                frames += wave.samples.size
            }
            require(frames * 2 < Int.MAX_VALUE - 36) { "Project is too long for WAV export." }
            dest.seek(0)
            fun u16(n: Int) { dest.write(n and 255); dest.write((n ushr 8) and 255) }
            fun u32(n: Int) { u16(n); u16(n ushr 16) }
            dest.writeBytes("RIFF"); u32((frames * 2 + 36).toInt()); dest.writeBytes("WAVEfmt ")
            u32(16); u16(1); u16(1); u32(16000); u32(32000); u16(2); u16(16)
            dest.writeBytes("data"); u32((frames * 2).toInt())
        }
    }
    /** RIFF INFO tags marking an export as AI-generated speech (title, comment, software, date). */
    fun aiLabel(title: String, date: java.util.Date = java.util.Date()) = linkedMapOf(
        "INAM" to title,
        "ICMT" to "AI-generated speech. Created offline with Hush Studio using the VoxCPM speech model.",
        "ISFT" to "Hush Studio ${BuildConfig.VERSION_NAME}",
        "ICRD" to java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(date))
    /**
     * Streams a PCM WAV to [output] with a LIST/INFO chunk of [tags] between "fmt " and "data".
     * The audio bytes are copied unchanged, so the export sounds identical to the narration.
     */
    fun writeLabeled(source: File, output: java.io.OutputStream, tags: Map<String, String>) = RandomAccessFile(source, "r").use { f ->
        fun tag(): String = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
        require(tag() == "RIFF") { "Narration audio is not a WAV file." }
        val riffEnd = f.u32() + 8
        require(riffEnd <= f.length() && tag() == "WAVE") { "Incomplete WAV file." }
        var fmt: ByteArray? = null; var dataAt = -1L; var dataSize = 0L
        while (f.filePointer + 8 <= riffEnd) {
            val id = tag(); val size = f.u32(); val start = f.filePointer
            require(start + size <= riffEnd) { "Invalid WAV chunk length." }
            if (id == "fmt ") fmt = ByteArray(size.toInt()).also { f.readFully(it) }
            if (id == "data" && dataAt < 0) { dataAt = start; dataSize = size }
            f.seek(start + size + size % 2)
        }
        require(fmt != null && dataAt >= 0) { "WAV has no audio." }
        val info = java.io.ByteArrayOutputStream()
        fun le32(out: java.io.OutputStream, n: Long) { for (i in 0 until 4) out.write(((n ushr (8 * i)) and 255).toInt()) }
        info.write("INFO".toByteArray(Charsets.US_ASCII))
        tags.forEach { (id, value) ->
            require(id.length == 4) { "RIFF tag ids are four characters." }
            val bytes = value.toByteArray(Charsets.UTF_8) + 0
            info.write(id.toByteArray(Charsets.US_ASCII)); le32(info, bytes.size.toLong()); info.write(bytes)
            if (bytes.size % 2 == 1) info.write(0)
        }
        val list = info.toByteArray()
        val out = java.io.BufferedOutputStream(output)
        val riffSize = 4 + (8 + fmt!!.size + fmt!!.size % 2) + (8 + list.size) + (8 + dataSize + dataSize % 2)
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); le32(out, riffSize); out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII)); le32(out, fmt!!.size.toLong()); out.write(fmt!!); if (fmt!!.size % 2 == 1) out.write(0)
        out.write("LIST".toByteArray(Charsets.US_ASCII)); le32(out, list.size.toLong()); out.write(list)
        out.write("data".toByteArray(Charsets.US_ASCII)); le32(out, dataSize)
        f.seek(dataAt)
        val buffer = ByteArray(256 * 1024); var left = dataSize
        while (left > 0) { val n = f.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()); require(n > 0) { "WAV ended early." }; out.write(buffer, 0, n); left -= n }
        if (dataSize % 2 == 1L) out.write(0)
        out.flush()
    }
}

object ScriptSections {
    fun split(text: String, limit: Int = 220): List<String> {
        require(limit >= 32)
        val clean = text.trim().replace(Regex("[\\t ]+"), " ")
        require(clean.isNotBlank()) { "Write a script first." }
        require(clean.length <= 6000) { "Keep each project under 6,000 characters." }
        val result = mutableListOf<String>()
        clean.split(Regex("(?<=[.!?])\\s+|\\n+")).filter { it.isNotBlank() }.forEach { sentence ->
            var remaining = sentence.trim()
            while (remaining.length > limit) {
                val space = remaining.lastIndexOf(' ', limit).takeIf { it >= limit / 2 } ?: limit
                result += remaining.substring(0, space).trim()
                remaining = remaining.substring(space).trim()
            }
            if (remaining.isNotBlank()) result += remaining
        }
        return result
    }
}
