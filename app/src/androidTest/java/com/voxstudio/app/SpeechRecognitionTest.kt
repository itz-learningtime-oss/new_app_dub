package com.voxstudio.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SpeechRecognitionTest {
    @Test fun savedReferenceCanBeTranscribedWithoutChangingAudio() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudioStore(context)
        assertTrue("Import a clear English voice reference first",store.reference.isFile)
        val before=MessageDigest.getInstance("SHA-256").digest(store.reference.readBytes())
        val started=android.os.SystemClock.elapsedRealtime()
        val text=SpeechTranscriber.transcribe(context,store.reference)
        assertTrue(text.split(" ").size>=3)
        assertArrayEquals(before,MessageDigest.getInstance("SHA-256").digest(store.reference.readBytes()))
        android.util.Log.i("VoxSpeechTest","reference_transcription_ms=${android.os.SystemClock.elapsedRealtime()-started} transcript=$text")
    }
    @Test fun transcribedShortReferenceCanGenerateClonedSpeech() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudioStore(context)
        val reference=File(context.cacheDir,"speech-clone-reference.wav")
        val output=File(context.cacheDir,"speech-clone-output.wav")
        try {
            AudioFiles.reference(store.reference,reference,0.0,5.0)
            val text=SpeechTranscriber.transcribe(context,reference)
            val started=android.os.SystemClock.elapsedRealtime()
            assertEquals("",NativeEngine.synthesize(store.model.absolutePath,reference.absolutePath,text,
                "This voice was created on my phone.",output.absolutePath,6,2))
            val audio=AudioFiles.read(output)
            assertTrue(audio.seconds>.5)
            assertTrue(audio.samples.any { kotlin.math.abs(it.toInt())>100 })
            android.util.Log.i("VoxSpeechTest","transcribed_clone_ms=${android.os.SystemClock.elapsedRealtime()-started} output_seconds=${audio.seconds} reference_words=$text")
        } finally { reference.delete(); output.delete() }
    }
}
