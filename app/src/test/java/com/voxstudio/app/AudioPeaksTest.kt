package com.voxstudio.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class AudioPeaksTest {
    @Test fun waveformPreservesPositionOfQuietAndLoudRegions() {
        val dir=Files.createTempDirectory("wave-peaks").toFile()
        try {
            val source=File(dir,"sample.wav")
            AudioFiles.write(source,ShortArray(64000) { if(it in 16000 until 32000) 15000 else 0 })
            assertEquals(4000,AudioPeaks.durationMs(source))
            val peaks=AudioPeaks.read(source,4)
            assertArrayEquals(floatArrayOf(0f,1f,0f,0f),peaks,.001f)
            assertEquals(96,AudioPeaks.read(source).size)
            source.writeBytes(source.readBytes().copyOf(50))
            assertThrows(IllegalArgumentException::class.java) { AudioPeaks.read(source) }
        } finally { dir.deleteRecursively() }
    }
}
