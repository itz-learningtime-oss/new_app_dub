package com.voxstudio.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SavedVoiceTest {
    @Test fun generationUsesSavedVoiceSnapshot() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudioStore(context)
        val sample=File(context.cacheDir,"saved-voice-test.wav")
        var saved: VoiceLibrary.Voice?=null
        var project: File?=null
        try {
            AudioFiles.reference(store.reference,sample,0.0,5.0)
            val words=SpeechTranscriber.transcribe(context,sample)
            saved=store.voices.save("Device test ${UUID.randomUUID()}",words,sample)
            StudioJobs.generate(context,"This is my saved voice.","Saved voice integration test",saved.id,4)
            project=File(store.projects,StudioJobs.lastProject!!)
            val metadata=store.metadata(project)
            assertEquals(saved.id,metadata.getString("voiceId"))
            assertEquals(saved.name,metadata.getString("voiceName"))
            assertEquals(words,metadata.getString("transcript"))
            assertArrayEquals(sample.readBytes(),File(project,"reference.wav").readBytes())
            assertTrue(AudioFiles.read(File(project,"full.wav")).samples.any { kotlin.math.abs(it.toInt())>100 })
            store.voices.delete(saved.id); saved=null
            assertTrue(File(project,"reference.wav").isFile)
            android.util.Log.i("VoxSavedVoiceTest","Saved voice selected, cloned PCM generated, project reference retained after profile deletion")
        } finally {
            project?.deleteRecursively()
            saved?.let { store.voices.delete(it.id) }
            sample.delete()
        }
    }
}
