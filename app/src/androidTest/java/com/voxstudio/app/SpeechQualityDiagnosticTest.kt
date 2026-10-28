package com.voxstudio.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** ASR is a diagnostic aid, not a human speech-quality or identity score. */
class SpeechQualityDiagnosticTest {
    @Test fun compareExistingSectionsAndShortReferenceGeneration() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudioStore(context)
        val report=JSONArray()
        val out=File(context.cacheDir,"quality-diagnostics").also { it.mkdirs() }
        val projectIds=InstrumentationRegistry.getArguments().getString("qualityProjects", "").split(",").filter { it.isNotBlank() }
        for(id in projectIds) {
            val project=File(store.projects,id)
            if(!File(project,"project.json").isFile) continue
            val meta=store.metadata(project)
            for(i in listOf(0,4,7,11)) {
                val file=File(project,"section-$i.wav")
                if(!file.isFile) continue
                val recognized=runCatching { SpeechTranscriber.transcribe(context,file) }.getOrElse { "ASR unavailable: ${it.message}" }
                val row=JSONObject().put("kind","existing").put("steps",meta.getInt("steps"))
                    .put("section",i).put("expected",meta.getJSONArray("sections").getString(i)).put("recognized",recognized)
                report.put(row)
                android.util.Log.i("VoxQualityTest",row.toString())
            }
        }
        val reference=File(out,"reference-5s.wav")
        val source=store.selectedVoice?.reference ?: store.reference
        AudioFiles.reference(source,reference,0.0,minOf(5.0,AudioFiles.read(source,20).seconds))
        val words=SpeechTranscriber.transcribe(context,reference)
        val text="So, I built a PC and attached it to the back of my phone."
        for((name,steps,cloned) in listOf(Triple("short-reference-4",4,true),Triple("short-reference-10",10,true),Triple("default-voice-10",10,false))) {
            val file=File(out,"$name.wav")
            NativeEngine.setBenchmarkSeedForTesting(4242)
            val before=android.os.SystemClock.elapsedRealtime()
            assertEquals("",NativeEngine.synthesize(store.model.absolutePath,if(cloned) reference.absolutePath else null,
                if(cloned) words else null,text,file.absolutePath,steps,2))
            val ms=android.os.SystemClock.elapsedRealtime()-before
            val audio=AudioFiles.read(file)
            assertTrue(audio.seconds>.5)
            val recognized=runCatching { SpeechTranscriber.transcribe(context,file) }.getOrElse { "ASR unavailable: ${it.message}" }
            val row=JSONObject().put("kind",name).put("steps",steps).put("expected",text).put("recognized",recognized)
                .put("wall_ms",ms).put("seconds",audio.seconds).put("reference_words",if(cloned) words else "")
            report.put(row); android.util.Log.i("VoxQualityTest",row.toString())
        }
        File(out,"report.json").writeText(report.toString(2))
    }
}
