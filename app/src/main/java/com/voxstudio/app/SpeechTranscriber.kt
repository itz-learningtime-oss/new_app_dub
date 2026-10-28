package com.voxstudio.app

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** English file recognition runs locally and releases the model before TTS starts. */
object SpeechTranscriber {
    const val MODEL_SHA = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    private const val ROOT = "vosk-model-small-en-us-0.15"
    private fun model(context: Context): File {
        val destination=File(context.filesDir,"speech-model")
        if(File(destination,".verified").takeIf { it.isFile }?.readText()==MODEL_SHA) return destination
        require(context.filesDir.usableSpace>100_000_000) { "Free 100 MB to prepare offline speech recognition." }
        StudioJobs.update("Preparing offline English recognition…")
        val temp=File(context.filesDir,"speech-model.install")
        temp.deleteRecursively(); temp.mkdirs()
        try {
            val hasAsset = try { context.assets.open("speech-model.zip").close(); true } catch (_: Exception) { false }
            require(hasAsset) { "Offline recognition model is not packaged in this build. Please type the voice transcript directly." }
            val digest=MessageDigest.getInstance("SHA-256")
            context.assets.open("speech-model.zip").use { source ->
                DigestInputStream(source,digest).use { hashed ->
                    ZipInputStream(hashed).use { zip ->
                        var total=0L; var entries=0
                        while(true) {
                            StudioJobs.checkStop()
                            val entry=zip.nextEntry ?: break
                            require(++entries<=100 && entry.name.startsWith("$ROOT/")) { "Invalid speech model archive." }
                            val name=entry.name.removePrefix("$ROOT/")
                            if(name.isEmpty()) continue
                            val file=File(temp,name)
                            require(file.canonicalPath.startsWith(temp.canonicalPath+File.separator)) { "Invalid speech model path." }
                            if(entry.isDirectory) file.mkdirs() else {
                                file.parentFile!!.mkdirs()
                                file.outputStream().buffered().use { output ->
                                    val buffer=ByteArray(65536)
                                    while(true) {
                                        StudioJobs.checkStop()
                                        val n=zip.read(buffer); if(n<0) break
                                        total+=n; require(total<=100_000_000) { "Speech model is too large." }
                                        output.write(buffer,0,n)
                                    }
                                }
                            }
                            zip.closeEntry()
                        }
                        // ZIP readers may stop before the central directory has been hashed.
                        val buffer=ByteArray(65536)
                        while(hashed.read(buffer)>=0) StudioJobs.checkStop()
                    }
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) }==MODEL_SHA) { "Speech model verification failed." }
            require(File(temp,"am/final.mdl").isFile && File(temp,"graph/HCLr.fst").isFile) { "Speech model is incomplete." }
            File(temp,".verified").writeText(MODEL_SHA)
            destination.deleteRecursively()
            check(temp.renameTo(destination)) { "Could not install offline speech model." }
            return destination
        } finally { temp.deleteRecursively() }
    }
    fun transcribe(context: Context, reference: File): String {
        val audio=AudioFiles.read(reference,20)
        require(audio.channels==1 && audio.rate==16000) { "Save a voice reference before extracting its words." }
        val directory=model(context)
        StudioJobs.checkStop(); StudioJobs.update("Loading offline English recognition…")
        val words=mutableListOf<String>()
        Model(directory.absolutePath).use { model ->
            Recognizer(model,16000f).use { recognizer ->
                var at=0
                while(at<audio.samples.size) {
                    StudioJobs.checkStop()
                    val end=minOf(at+4000,audio.samples.size)
                    val chunk=audio.samples.copyOfRange(at,end)
                    if(recognizer.acceptWaveForm(chunk,chunk.size)) {
                        JSONObject(recognizer.result).optString("text").trim().takeIf { it.isNotEmpty() }?.let(words::add)
                    }
                    at=end
                    StudioJobs.update("Extracting transcript offline · ${at/16000}s / ${audio.samples.size/16000}s",at*100/audio.samples.size)
                }
                JSONObject(recognizer.finalResult).optString("text").trim().takeIf { it.isNotEmpty() }?.let(words::add)
            }
        }
        StudioJobs.checkStop()
        val text=words.joinToString(" ").trim()
        require(text.isNotBlank()) { "No clear English speech found. Try a cleaner clip or type its words." }
        require(text.length<=600) { "Transcript is too long. Choose a shorter voice clip." }
        return text
    }
}
