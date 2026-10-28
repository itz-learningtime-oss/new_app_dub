package com.voxstudio.app

import java.io.File
import java.util.Properties
import java.util.UUID

/** Immutable voice snapshots: changing the draft never changes a saved voice. */
class VoiceLibrary(private val root: File) {
    data class Voice(val id: String, val name: String, val transcript: String, val created: Long, val reference: File)
    companion object { private val lock=Any() }
    fun list(): List<Voice> = synchronized(lock) {
        root.listFiles()?.mapNotNull { load(it) }?.sortedByDescending { it.created } ?: emptyList()
    }
    fun find(id: String?): Voice? = synchronized(lock) {
        if(id==null || !id.matches(Regex("[a-f0-9-]{36}"))) null else load(File(root,id))
    }
    private fun load(dir: File): Voice? = runCatching {
        require(dir.name.matches(Regex("[a-f0-9-]{36}")))
        val data=Properties().apply { File(dir,"voice.properties").reader(Charsets.UTF_8).use { load(it) } }
        val name=data.getProperty("name"); val text=data.getProperty("transcript")
        require(!name.isNullOrBlank() && !text.isNullOrBlank() && File(dir,"reference.wav").isFile)
        Voice(dir.name,name,text,data.getProperty("created").toLong(),File(dir,"reference.wav"))
    }.getOrNull()
    /** [consentAt] records when the user confirmed they may use this voice (shown before saving a clone). */
    fun save(name: String, transcript: String, reference: File, consentAt: Long? = null): Voice = synchronized(lock) {
        val title=name.trim(); val words=transcript.trim()
        require(title.isNotEmpty() && title.length<=60) { "Give this voice a name, up to 60 characters." }
        require(words.isNotEmpty() && words.length<=600) { "Extract and review the reference transcript first." }
        require(list().none { it.name.equals(title,true) }) { "A voice with this name already exists. Choose another name." }
        val wave=AudioFiles.read(reference,20)
        require(wave.rate==16000 && wave.channels==1 && wave.seconds in 3.0..20.0) { "Record or import a 3–20 second voice sample first." }
        require(wave.samples.any { kotlin.math.abs(it.toInt())>100 }) { "This voice sample is too quiet." }
        root.mkdirs()
        val id=UUID.randomUUID().toString(); val temp=File(root,".$id.tmp"); val destination=File(root,id)
        try {
            check(temp.mkdirs()) { "Could not prepare voice storage." }
            reference.copyTo(File(temp,"reference.wav"))
            val created=System.currentTimeMillis()
            Properties().apply {
                setProperty("name",title); setProperty("transcript",words); setProperty("created",created.toString())
                consentAt?.let { setProperty("consentAt",it.toString()) }
            }.also { data -> File(temp,"voice.properties").writer(Charsets.UTF_8).use { data.store(it,"Hush Studio saved voice") } }
            check(temp.renameTo(destination)) { "Could not save cloned voice." }
            Voice(id,title,words,created,File(destination,"reference.wav"))
        } finally { temp.deleteRecursively() }
    }
    fun rename(id: String, name: String): Voice = synchronized(lock) {
        val voice=find(id) ?: error("Voice is no longer available.")
        val title=name.trim()
        require(title.isNotBlank() && title.length<=60) { "Use a voice name between 1 and 60 characters." }
        require(list().none { it.id!=id && it.name.equals(title,true) }) { "A voice already has that name." }
        val dir=voice.reference.parentFile!!
        val data=Properties().apply { File(dir,"voice.properties").reader(Charsets.UTF_8).use { load(it) } }
        data.setProperty("name",title)
        val temp=File(dir,"voice.properties.tmp")
        try {
            temp.writer(Charsets.UTF_8).use { data.store(it,"Hush Studio saved voice") }
            check(temp.renameTo(File(dir,"voice.properties"))) { "Could not rename voice." }
        } finally { temp.delete() }
        voice.copy(name=title)
    }
    fun delete(id: String) = synchronized(lock) {
        val voice=find(id) ?: error("Voice is no longer available.")
        check(voice.reference.parentFile!!.deleteRecursively()) { "Could not remove this voice." }
    }
}
