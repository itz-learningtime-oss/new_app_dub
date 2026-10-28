package com.voxstudio.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BuiltInVoicesTest {
    private val catalog=BuiltInVoices.parse(File("src/main/assets/voices/catalog.json").readText())
    @Test fun catalogShipsSixLicensedVoicesWithClips() {
        assertEquals(listOf("aria","jenny","maya","ethan","cole","priya"),catalog.map { it.id })
        assertEquals(catalog.size,catalog.map { it.name.lowercase() }.toSet().size)
        assertTrue(catalog.none { it.placeholder })
        assertEquals(4,catalog.count { it.gender=="female" });assertEquals(2,catalog.count { it.gender=="male" })
        catalog.forEach { assertTrue("${it.id} clip missing",File("src/main/assets/voices/${it.file}").isFile) }
    }
    @Test fun everyVoiceHasASpokenTranscriptAndSafeFileName() {
        catalog.forEach {
            assertTrue(it.id,it.transcript.split(" ").size>=8)
            assertFalse("${it.id} transcript must spell numbers as spoken",it.transcript.any { c -> c.isDigit() })
            assertTrue(it.id,it.file.matches(Regex("[a-z0-9-]+\\.wav")))
            assertTrue(it.voiceId.startsWith(BuiltInVoices.PREFIX))
        }
    }
    @Test fun jennyKeepsTheNameItsLicenceRequires() {
        assertEquals("Jenny (Dioco)",catalog.first { it.id=="jenny" }.name)
    }
    @Test fun labelsOmitUnknownAccents() {
        assertEquals("Female · American · Audiobook narrator",catalog.first { it.id=="aria" }.label)
        assertEquals("Male · Audiobook narrator",catalog.first { it.id=="ethan" }.label)
    }
}
