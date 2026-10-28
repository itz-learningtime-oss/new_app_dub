package com.voxstudio.app

object SpeechText {
    /** The pinned CLI concatenates prompt + target text; keep the last and first words separate. */
    fun referencePrompt(transcript: String): String {
        require(transcript.isNotBlank()) { "The voice reference needs its transcript." }
        return transcript.trimEnd()+" "
    }
}
