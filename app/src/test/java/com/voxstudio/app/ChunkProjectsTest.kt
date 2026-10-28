package com.voxstudio.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChunkProjectsTest {
    @Test fun blankLinesStartNewChunksAndShortSentencesShareOne() {
        val chunks=ChunkProjects.split("First sentence. Second sentence.\n\n\n  \nThird paragraph here.")
        assertEquals(listOf("First sentence. Second sentence.","Third paragraph here."),chunks)
    }
    @Test fun longParagraphsStayWithinModelLimit() {
        val sentence="This sentence has a reasonable length for narration tests."
        val chunks=ChunkProjects.split(List(12) { sentence }.joinToString(" "))
        assertTrue(chunks.size>1)
        assertTrue(chunks.all { it.length<=220 })
        assertEquals(List(12) { sentence }.joinToString(" "),chunks.joinToString(" "))
    }
    @Test fun pruneKeepsOnlyThreeNewestTakesForThatChunk() {
        val dir=Files.createTempDirectory("takes").toFile()
        try {
            (1..5).forEach { File(dir,"section-2.previous-170000000000$it.wav").writeText("$it") }
            File(dir,"section-1.previous-1700000000001.wav").writeText("other")
            File(dir,"section-2.wav").writeText("current")
            ChunkProjects.pruneTakes(dir,"section-2")
            val names=dir.list()!!.sorted()
            assertEquals(listOf("section-1.previous-1700000000001.wav","section-2.previous-1700000000003.wav",
                "section-2.previous-1700000000004.wav","section-2.previous-1700000000005.wav","section-2.wav"),names)
        } finally { dir.deleteRecursively() }
    }
}
