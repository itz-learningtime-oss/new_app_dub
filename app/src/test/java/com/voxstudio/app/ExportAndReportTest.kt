package com.voxstudio.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Date

class ExportAndReportTest {
    @Test fun labeledExportKeepsAudioIdenticalAndAddsAiTags() {
        val dir = Files.createTempDirectory("export").toFile()
        try {
            val source = File(dir, "full.wav")
            val samples = ShortArray(16001) { (it % 200 - 100).toShort() }
            AudioFiles.write(source, samples)
            val exported = File(dir, "export.wav")
            exported.outputStream().use { AudioFiles.writeLabeled(source, it, AudioFiles.aiLabel("My narration", Date(0))) }
            val wave = AudioFiles.read(exported)
            assertEquals(16000, wave.rate); assertEquals(1, wave.channels)
            assertArrayEquals(samples, wave.samples)
            val bytes = exported.readBytes()
            val text = String(bytes, Charsets.ISO_8859_1)
            assertTrue(text.contains("LIST") && text.contains("INFO") && text.contains("AI-generated speech"))
            assertTrue(text.contains("My narration"))
            // RIFF size field must match the file length minus the 8-byte header.
            val riffSize = (bytes[4].toLong() and 255) or ((bytes[5].toLong() and 255) shl 8) or ((bytes[6].toLong() and 255) shl 16) or ((bytes[7].toLong() and 255) shl 24)
            assertEquals(bytes.size - 8L, riffSize)
        } finally { dir.deleteRecursively() }
    }
    @Test fun reportDocumentUsesFirestoreFieldsAndLimits() {
        val report = Reports.Report("Other", "n".repeat(5000), "t".repeat(5000), "cloned")
        val fields = JSONObject(Reports.document(report, Date(0))).getJSONObject("fields")
        assertEquals("Other", fields.getJSONObject("reason").getString("stringValue"))
        assertEquals(Reports.NOTE_LIMIT, fields.getJSONObject("note").getString("stringValue").length)
        assertEquals(Reports.TEXT_LIMIT, fields.getJSONObject("text").getString("stringValue").length)
        assertEquals("cloned", fields.getJSONObject("voiceKind").getString("stringValue"))
        assertEquals("1970-01-01T00:00:00Z", fields.getJSONObject("createdAt").getString("timestampValue"))
        assertEquals(setOf("reason", "note", "text", "voiceKind", "appVersion", "android", "createdAt"), fields.keys().asSequence().toSet())
    }
}
