package com.voxstudio.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoiceLibraryTest {
    @Test fun namedVoicesSurviveRestartAndDraftChanges() {
        val root=Files.createTempDirectory("voices").toFile()
        try {
            val draft=File(root,"draft.wav"); AudioFiles.write(draft,ShortArray(48000) { 3000 })
            val library=VoiceLibrary(File(root,"saved"))
            val first=library.save("Narrator","First sample words.",draft)
            AudioFiles.write(draft,ShortArray(64000) { -6000 })
            val second=library.save("Second voice","Second sample words.",draft)
            val reopened=VoiceLibrary(File(root,"saved"))
            assertEquals(2,reopened.list().size)
            assertEquals("First sample words.",reopened.find(first.id)!!.transcript)
            assertTrue(AudioFiles.read(reopened.find(first.id)!!.reference).samples.all { it.toInt()==3000 })
            assertTrue(AudioFiles.read(reopened.find(second.id)!!.reference).samples.all { it.toInt()==-6000 })
            reopened.delete(first.id)
            assertNull(reopened.find(first.id)); assertNotNull(reopened.find(second.id)); assertTrue(draft.isFile)
        } finally { root.deleteRecursively() }
    }
    @Test fun renameKeepsIdentityAudioAndTranscript() {
        val root=Files.createTempDirectory("voices-rename").toFile()
        try {
            val draft=File(root,"draft.wav");AudioFiles.write(draft,ShortArray(48000) { 3000 })
            val library=VoiceLibrary(File(root,"saved"));val voice=library.save("Old name","Keep these words.",draft)
            val bytes=voice.reference.readBytes()
            val renamed=library.rename(voice.id,"New name")
            assertEquals(voice.id,renamed.id);assertEquals(voice.created,renamed.created)
            assertEquals(voice.transcript,renamed.transcript);assertArrayEquals(bytes,renamed.reference.readBytes())
            assertEquals("New name",VoiceLibrary(File(root,"saved")).find(voice.id)!!.name)
            library.save("Another","Other words.",draft)
            assertThrows(IllegalArgumentException::class.java) { library.rename(voice.id,"another") }
            assertEquals("New name",library.find(voice.id)!!.name)
        } finally { root.deleteRecursively() }
    }
    @Test fun failedSavePreservesLibraryAndRejectsInvalidPaths() {
        val root=Files.createTempDirectory("voices-invalid").toFile()
        try {
            val draft=File(root,"draft.wav"); AudioFiles.write(draft,ShortArray(48000) { 4000 })
            val library=VoiceLibrary(File(root,"saved")); val saved=library.save("Voice","Hello world",draft)
            assertThrows(IllegalArgumentException::class.java) { library.save("voice","Other words",draft) }
            assertThrows(IllegalArgumentException::class.java) { library.save("Other"," ",draft) }
            AudioFiles.write(draft,ShortArray(400))
            assertThrows(IllegalArgumentException::class.java) { library.save("Other","Words",draft) }
            assertEquals(1,library.list().size); assertNotNull(library.find(saved.id))
            assertNull(library.find("../../draft.wav"))
            assertTrue(File(root,"saved").listFiles()!!.none { it.name.endsWith(".tmp") })
        } finally { root.deleteRecursively() }
    }
}
