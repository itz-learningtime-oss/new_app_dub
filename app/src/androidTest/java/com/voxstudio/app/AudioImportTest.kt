package com.voxstudio.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.sin

class AudioImportTest {
    private val cache get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    @Test fun detectsMp3ContentDespiteBinExtensionAndMakesCloneReadyClip() {
        val source=File(cache,"import-test/user-audio.bin")
        assertTrue("Push the supplied MP3 fixture to the test cache first",source.isFile)
        val output=File(cache,"import-test/decoded.wav")
        val clip=File(cache,"import-test/clip.wav")
        try {
            val progress=mutableListOf<Int>()
            AudioImport.convert(source,output,progress={ progress.add(it) })
            val wave=AudioFiles.read(output,AudioImport.MAX_SECONDS)
            assertEquals(16000,wave.rate);assertEquals(1,wave.channels)
            assertTrue(wave.seconds in 240.0..243.0)
            assertTrue(wave.samples.any { kotlin.math.abs(it.toInt())>300 })
            assertEquals(100,progress.last())
            AudioFiles.reference(output,clip,0.0,5.0)
            assertEquals(5.0,AudioFiles.read(clip).seconds,0.001)
        } finally { output.delete();clip.delete() }
    }
    @Test fun selectedClipSkipsFullFileConversion() {
        val source=File(cache,"import-test/user-audio.bin")
        val output=File(cache,"import-test/range.wav")
        try {
            val started=android.os.SystemClock.elapsedRealtime()
            assertTrue(AudioImport.duration(source) in 240.0..243.0)
            for(at in listOf(0.0,120.0)) {
                AudioImport.convert(source,output,startSeconds=at,clipSeconds=5.0)
                assertEquals(5.0,AudioFiles.read(output).seconds,0.03)
            }
            println("Two five-second clips plus metadata: ${android.os.SystemClock.elapsedRealtime()-started} ms")
        } finally { output.delete() }
    }
    @Test fun convertsPcmWavAndAacWithTheSameEntryPoint() {
        val source=File(cache,"import-test/synthetic.wav")
        val output=File(cache,"import-test/normalized.wav")
        try {
            AudioFiles.write(source,ShortArray(44100*4) { (sin(it*2.0*Math.PI*440/44100)*4000).toInt().toShort() },44100)
            for(input in listOf(source,File(cache,"import-test/synthetic.m4a"))) {
                AudioImport.convert(input,output)
                val wave=AudioFiles.read(output)
                assertEquals(16000,wave.rate);assertEquals(1,wave.channels)
                assertTrue(wave.seconds in 3.9..4.2)
            }
        } finally { source.delete();output.delete() }
    }
    @Test fun invalidFilesAndCancellationLeaveNoPartialOutput() {
        val bad=File(cache,"import-test/invalid.mp3").apply { writeText("not audio") }
        val output=File(cache,"import-test/partial.wav")
        try {
            assertThrows(Exception::class.java) { AudioImport.convert(bad,output) }
            assertFalse(output.exists())
            assertThrows(CancellationException::class.java) {
                AudioImport.convert(File(cache,"import-test/user-audio.bin"),output,{ throw CancellationException() })
            }
            assertFalse(output.exists())
        } finally { bad.delete();output.delete() }
    }
}
