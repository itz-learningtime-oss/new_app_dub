package com.voxstudio.app

import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
    @Test fun referenceAndTargetWordsCannotRunTogether() {
        assertEquals("flashing just So, I built a PC.",SpeechText.referencePrompt("flashing just")+"So, I built a PC.")
        assertEquals("Hello. Next sentence.",SpeechText.referencePrompt("Hello.  \n")+"Next sentence.")
        assertEquals("with you ",SpeechText.referencePrompt("with you"))
        assertThrows(IllegalArgumentException::class.java) { SpeechText.referencePrompt(" \n") }
    }
}
