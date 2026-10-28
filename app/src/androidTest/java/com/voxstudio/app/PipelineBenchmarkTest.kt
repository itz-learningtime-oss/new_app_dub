package com.voxstudio.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Fixed-script benchmark through the real chunk pipeline (StudioJobs.generateChunks).
 * Same three chunks, seed, steps and threads on every build; the narration cache is bypassed
 * (fresh=true) so cache hits never count as speed. Run with:
 *   am instrument -w -e class com.voxstudio.app.PipelineBenchmarkTest -e benchThreads 2 -e benchSteps 6 ...
 * Results are logged under the VoxBenchmark tag.
 */
class PipelineBenchmarkTest {
    private val chunks=listOf(
        "The morning train was late again, so Maya opened her notebook and began to sketch the people waiting on the platform.",
        "An old man fed the pigeons, a student practiced lines from a play, and a tired nurse closed her eyes for just a moment.",
        "When the train finally arrived, Maya realized she had drawn a story, not just a crowd, and she smiled all the way to work.")
    private val referenceWords="Hello from Vox Studio. This is a local voice test."

    private fun peakRssKb(): Long=File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }
        ?.filter { it.isDigit() }?.toLongOrNull() ?: -1
    private fun resetPeakRss() { runCatching { File("/proc/self/clear_refs").writeText("5") } }
    private fun temperature(context: android.content.Context): Int=context.registerReceiver(null,
        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.getIntExtra("temperature",-1) ?: -1

    private fun run(label: String,voice: Boolean) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val args=InstrumentationRegistry.getArguments()
        val threads=args.getString("benchThreads")?.toIntOrNull() ?: 2
        val steps=args.getString("benchSteps")?.toIntOrNull() ?: 6
        val store=StudioStore(context);assertTrue("Install the model first",store.ready)
        val previousProject=store.prefs.getString("studioProjectId",null)
        val previousThreads=if(store.prefs.contains("threads")) store.prefs.getInt("threads",2) else null
        store.prefs.edit().putInt("threads",threads).apply()
        val dir=ChunkProjects.create(context,chunks.joinToString("\n\n"),"Benchmark $label",null,steps)
        NativeEngine.backendForTesting="cpu"
        try {
            assertEquals(chunks.size,store.metadata(dir).getJSONArray("sections").length())
            if(voice) {
                // Deterministic synthetic reference: no personal recordings in benchmark logs.
                NativeEngine.setBenchmarkSeedForTesting(77)
                assertEquals("",NativeEngine.synthesize(store.model.absolutePath,null,null,referenceWords,File(dir,"reference.wav").absolutePath,steps,threads))
                NativeEngine.releaseForTesting()
                store.saveMetadata(dir,store.metadata(dir).put("voice",true).put("transcript",referenceWords).put("voiceName","Benchmark reference"))
            }
            NativeEngine.setBenchmarkSeedForTesting(4242)
            val tempBefore=temperature(context)
            resetPeakRss()
            val start=android.os.SystemClock.elapsedRealtime()
            StudioJobs.generateChunks(context,dir,chunks.indices.toList(),true)
            val wallMs=android.os.SystemClock.elapsedRealtime()-start
            val peak=peakRssKb()
            var audio=0.0
            chunks.indices.forEach { index ->
                val wave=AudioFiles.read(File(dir,"section-$index.wav"))
                assertEquals(16000,wave.rate);assertTrue(wave.seconds>1.0)
                assertTrue("Chunk $index must not be silent",wave.samples.any { kotlin.math.abs(it.toInt())>100 })
                audio+=wave.seconds
                val timing=File(dir,"timing-$index.txt").readText().lines().filter { it.isNotBlank() }.joinToString(" ")
                assertFalse("Benchmark must not use cached audio",timing.contains("cache_hit=true"))
                val sha=java.security.MessageDigest.getInstance("SHA-256").digest(File(dir,"section-$index.wav").readBytes()).joinToString("") { "%02x".format(it) }.take(16)
                android.util.Log.i("VoxBenchmark","$label chunk=$index audio_s=${"%.2f".format(wave.seconds)} wav_sha=$sha $timing")
            }
            assertTrue(File(dir,"full.wav").isFile)
            android.util.Log.i("VoxBenchmark","$label SUMMARY version=${BuildConfig.VERSION_NAME} fast=${NativeCpuEngine.fast} steps=$steps threads=$threads " +
                "chunks=${chunks.size} wall_ms=$wallMs audio_s=${"%.2f".format(audio)} rtf=${"%.3f".format(wallMs/1000.0/audio)} " +
                "peak_rss_mb=${peak/1024} temp_before=${tempBefore/10.0} temp_after=${temperature(context)/10.0}")
        } finally {
            NativeEngine.backendForTesting="auto";NativeEngine.setBenchmarkSeedForTesting(-1);NativeEngine.releaseForTesting()
            store.deleteProject(dir)
            store.prefs.edit().putString("studioProjectId",previousProject).apply()
            if(previousThreads==null) store.prefs.edit().remove("threads").apply() else store.prefs.edit().putInt("threads",previousThreads).apply()
        }
    }
    @Test fun defaultVoiceThreeChunks()=run("default",false)
    @Test fun clonedVoiceThreeChunks()=run("clone",true)
}
