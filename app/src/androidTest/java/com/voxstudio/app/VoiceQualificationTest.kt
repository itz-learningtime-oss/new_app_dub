package com.voxstudio.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Qualifies built-in voice clips before they ship: each voice reads fixed sentences with several
 * seeds and offline ASR counts the expected words. A clip whose takes often drop or invent words is
 * not fit to ship, whatever it sounds like. Report: cache/voice-qualification.json and the
 * VoxVoiceQualify log tag. Args: -e voices aria,ethan (default all) -e seeds 11,22,33.
 * ASR is a coarse intelligibility proxy, not a judgement of naturalness or likeness.
 */
class VoiceQualificationTest {
    private val sentences=listOf(
        "The river was calm this morning, and the boats drifted slowly past the old stone bridge." to
            listOf("river","calm","morning","boats","drifted","slowly","stone","bridge"),
        "Please remember to bring your notebook, a pencil, and a warm jacket for the afternoon walk." to
            listOf("remember","bring","notebook","pencil","warm","jacket","afternoon","walk"))
    @Test fun qualifyBuiltInVoices() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudioStore(context);assertTrue("Install the model first",store.ready)
        val args=InstrumentationRegistry.getArguments()
        val wanted=args.getString("voices")?.split(",")?.map { it.trim() }?.toSet()
        val seeds=(args.getString("seeds") ?: "11,22,33").split(",").map { it.trim().toInt() }
        val voices=BuiltInVoices.catalog(context).filter { wanted==null || it.id in wanted }
        assertTrue("No built-in voices packaged",voices.isNotEmpty())
        val previousProject=store.prefs.getString("studioProjectId",null)
        val report=JSONArray()
        NativeEngine.backendForTesting="cpu"
        try {
            for(entry in voices) {
                var passed=0;var takes=0
                for(seed in seeds) for((text,keys) in sentences) {
                    val dir=ChunkProjects.create(context,text,"Voice qualification",entry.voiceId,6)
                    try {
                        NativeEngine.setBenchmarkSeedForTesting(seed)
                        StudioJobs.generateChunks(context,dir,listOf(0),true)
                        val output=File(dir,"section-0.wav")
                        val heard=SpeechTranscriber.transcribe(context,output).lowercase()
                        val found=keys.count { heard.contains(it) }
                        takes++;if(found>=6) passed++
                        report.put(JSONObject().put("voice",entry.id).put("seed",seed).put("found",found).put("keys",keys.size)
                            .put("seconds",AudioFiles.read(output).seconds).put("heard",heard))
                    } finally { store.deleteProject(dir) }
                }
                android.util.Log.i("VoxVoiceQualify","voice=${entry.id} source_placeholder=${entry.placeholder} passed=$passed/$takes verdict=${if(passed*10>=takes*8) "PASS" else "FAIL"}")
                File(context.cacheDir,"voice-qualification.json").writeText(report.toString(1))
            }
        } finally {
            NativeEngine.backendForTesting="auto";NativeEngine.setBenchmarkSeedForTesting(-1);NativeEngine.releaseForTesting()
            store.prefs.edit().putString("studioProjectId",previousProject).apply()
        }
    }
}
