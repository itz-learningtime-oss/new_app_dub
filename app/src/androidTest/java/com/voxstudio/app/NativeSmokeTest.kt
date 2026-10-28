package com.voxstudio.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Run on an ARM64 device after installing/importing the pinned model in the app. */
class NativeSmokeTest {
    @Test fun nativeGenerationProducesPcmAudio() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudioStore(context)
        assertTrue("Install the model in the app's Setup screen first",store.ready)
        val output=File(context.cacheDir,"native-smoke.wav")
        try {
            val error=NativeEngine.synthesize(store.model.absolutePath,null,null,"Hello from Vox Studio.",output.absolutePath,6,2)
            assertEquals("",error)
            val wave=AudioFiles.read(output)
            assertEquals(16000,wave.rate)
            assertEquals(1,wave.channels)
            assertTrue("Audio should be at least half a second",wave.seconds>.5)
            assertTrue("Audio must not be silent",wave.samples.any { kotlin.math.abs(it.toInt())>100 })
        } finally { output.delete() }
    }
    @Test fun shortSyntheticReferenceCloningProducesPcmAudio() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudioStore(context)
        assertTrue("Install the model first",store.ready)
        val reference=File(context.cacheDir,"clone-test-reference.wav")
        val output=File(context.cacheDir,"clone-test-output.wav")
        val words="Hello from Vox Studio. This is a local voice test."
        try {
            assertEquals("",NativeEngine.synthesize(store.model.absolutePath,null,null,words,reference.absolutePath,6,4))
            val before=android.os.SystemClock.elapsedRealtime()
            assertEquals("",NativeEngine.synthesize(store.model.absolutePath,reference.absolutePath,words,
                "Your next story starts here.",output.absolutePath,6,4))
            val wave=AudioFiles.read(output)
            assertEquals(16000,wave.rate)
            assertTrue(wave.seconds>.5)
            assertTrue(wave.samples.any { kotlin.math.abs(it.toInt())>100 })
            android.util.Log.i("VoxDeviceTest","short_reference_seconds=${AudioFiles.read(reference).seconds} clone_wall_ms=${android.os.SystemClock.elapsedRealtime()-before} output_seconds=${wave.seconds}")
        } finally { reference.delete(); output.delete() }
    }

    @Test fun repeatableNarrationBenchmark() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudioStore(context);assertTrue(store.ready)
        val output=File(context.cacheDir,"repeatable-benchmark.wav")
        val args=InstrumentationRegistry.getArguments()
        NativeEngine.backendForTesting=args.getString("benchBackend") ?: "auto"
        val threads=args.getString("benchThreads")?.toIntOrNull() ?: 4
        val steps=args.getString("benchSteps")?.toIntOrNull() ?: 10
        try {
            NativeEngine.setBenchmarkSeedForTesting(4242)
            val before=android.os.SystemClock.elapsedRealtime()
            val error=NativeEngine.synthesize(store.model.absolutePath,null,null,
                "Today in this video I am going to show you how we can install Kali Linux on any PC",output.absolutePath,steps,threads)
            val nativeMs=android.os.SystemClock.elapsedRealtime()-before
            assertEquals("",error)
            val wave=AudioFiles.read(output);val totalMs=android.os.SystemClock.elapsedRealtime()-before
            assertEquals(16000,wave.rate);assertTrue(wave.seconds>.5)
            assertTrue(wave.samples.any { kotlin.math.abs(it.toInt())>100 })
            android.util.Log.i("VoxDeviceTest","benchmark fast=${NativeCpuEngine.fast} seed=4242 steps=$steps threads=$threads native_ms=$nativeMs total_ms=$totalMs audio_seconds=${wave.seconds} rtf=${totalMs/1000.0/wave.seconds}")
        } finally { output.delete() }
    }

    @Test fun cancellationStopsAtDecoderBoundary() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudioStore(context);assertTrue(store.ready)
        val output=File(context.cacheDir,"cancel-test.wav")
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        val task=executor.submit<String> {
            NativeEngine.synthesize(store.model.absolutePath,null,null,
                "This is a generation cancellation test with several words to keep the decoder active.",output.absolutePath,10,2)
        }
        try {
            val deadline=android.os.SystemClock.elapsedRealtime()+40000
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                val s=StudioJobs.generation.snapshot()
                if(s.stage=="Generating speech" && s.frames>0) break
                Thread.sleep(30)
            }
            NativeEngine.cancel()
            val result=task.get(25,java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("Native cancellation should be reported safely: $result",result.contains("stopped by user"))
        } finally { NativeEngine.cancel();executor.shutdown();output.delete() }
    }

}
