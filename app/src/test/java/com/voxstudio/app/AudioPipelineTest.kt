package com.voxstudio.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.sin

class AudioPipelineTest {
    @Test fun stereoReferenceIsDownmixedAndResampled() {
        val dir = Files.createTempDirectory("vox-audio").toFile()
        try {
            // Construct stereo 48 kHz WAV, including an ancillary odd-sized JUNK chunk.
            val source=File(dir,"source.wav")
            val samples=ShortArray(48000*6) { (sin((it/2) * .05)*10000).toInt().toShort() }
            AudioFiles.write(source,samples,48000)
            val bytes=source.readBytes()
            bytes[22]=2; bytes[28]=0; bytes[29]=(-18).toByte(); bytes[30]=2; bytes[31]=0
            bytes[32]=4
            val extended=bytes.copyOfRange(0,36)+byteArrayOf(74,85,78,75,1,0,0,0,99,0)+bytes.copyOfRange(36,bytes.size)
            fun int32(at: Int,n: Int) { repeat(4) { extended[at+it]=(n ushr (8*it)).toByte() } }
            int32(4,extended.size-8)
            source.writeBytes(extended)
            val output=File(dir,"reference.wav")
            // Stereo 48k input tests downmixing and resampling with an ancillary WAV chunk.
            AudioFiles.reference(source,output)
            val result=AudioFiles.read(output,20)
            assertEquals(16000,result.rate); assertEquals(1,result.channels)
            assertEquals(3.0,result.seconds,.001)
            assertTrue(result.samples.any { it>1000 })
        } finally { dir.deleteRecursively() }
    }
    @Test fun truncatedAndSilentReferencesAreRejected() {
        val dir=Files.createTempDirectory("vox-invalid").toFile()
        try {
            val source=File(dir,"bad.wav"); val output=File(dir,"ref.wav")
            AudioFiles.write(source,ShortArray(48000))
            assertThrows(IllegalArgumentException::class.java) { AudioFiles.reference(source,output) }
            source.writeBytes(source.readBytes().copyOf(48))
            assertThrows(IllegalArgumentException::class.java) { AudioFiles.read(source) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun longImportCanSelectTwentySecondClipAndShortTail() {
        val dir=Files.createTempDirectory("vox-trim").toFile()
        try {
            val source=File(dir,"long.wav"); val output=File(dir,"selected.wav")
            val rate=44100
            val samples=ShortArray(rate*124) { if(it<rate*20) 1000 else if(it<rate*120) 6000 else -4000 }
            AudioFiles.write(source,samples,rate)
            assertThrows(IllegalArgumentException::class.java) { AudioFiles.reference(source,output) }
            AudioFiles.reference(source,output,20.0,20.0)
            val clip=AudioFiles.read(output,20)
            assertEquals(20.0,clip.seconds,.0001)
            assertTrue(clip.samples.all { it.toInt()==6000 })
            AudioFiles.reference(source,output,120.0,4.0)
            assertTrue(AudioFiles.read(output,20).samples.all { it.toInt()==-4000 })
            val saved=output.readBytes()
            assertThrows(IllegalArgumentException::class.java) { AudioFiles.reference(source,output,122.0,5.0) }
            assertThrows(IllegalArgumentException::class.java) { AudioFiles.reference(source,output,Double.NaN,5.0) }
            assertArrayEquals(saved,output.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun joinAddsQuarterSecondGapWithoutChangingSamples() {
        val dir=Files.createTempDirectory("vox-join").toFile()
        try {
            val a=File(dir,"a.wav"); val b=File(dir,"b.wav"); val output=File(dir,"all.wav")
            AudioFiles.write(a,shortArrayOf(1,2,3)); AudioFiles.write(b,shortArrayOf(-4,-5))
            AudioFiles.join(listOf(a,b),output)
            val result=AudioFiles.read(output)
            assertEquals(4005,result.samples.size)
            assertArrayEquals(shortArrayOf(1,2,3),result.samples.copyOfRange(0,3))
            assertEquals(0,result.samples[2000].toInt())
            assertArrayEquals(shortArrayOf(-4,-5),result.samples.takeLast(2).toShortArray())
        } finally { dir.deleteRecursively() }
    }
    @Test fun sentenceSplittingBoundsLongWordsAndPreservesText() {
        val text="Hello there. "+"a".repeat(500)+"\nThe next section!"
        val sections=ScriptSections.split(text)
        assertTrue(sections.all { it.length<=220 })
        assertEquals(text.filterNot { it.isWhitespace() },sections.joinToString("").filterNot { it.isWhitespace() })
        assertThrows(IllegalArgumentException::class.java) { ScriptSections.split(" ") }
        assertThrows(IllegalArgumentException::class.java) { ScriptSections.split("x".repeat(6001)) }
    }
}
