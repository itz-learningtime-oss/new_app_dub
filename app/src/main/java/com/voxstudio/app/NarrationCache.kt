package com.voxstudio.app

import java.io.File
import java.security.MessageDigest

/** Bounded app-private cache; generated audio is reusable only for identical inputs. */
class NarrationCache(private val directory: File) {
    fun key(text: String, transcript: String, reference: File?, settings: String): String {
        val digest=MessageDigest.getInstance("SHA-256")
        for(value in listOf(text,transcript,settings)) {
            val bytes=value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
        }
        reference?.inputStream()?.use { input -> val bytes=ByteArray(8192);while(true) { val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun restore(key: String, destination: File): Boolean {
        val source=File(directory,"$key.wav")
        if(!source.isFile)return false
        return runCatching { val wave=AudioFiles.read(source);require(wave.rate==16000 && wave.channels==1 && wave.samples.isNotEmpty());source.copyTo(destination,true);source.setLastModified(System.currentTimeMillis());true }.getOrElse { source.delete();false }
    }
    fun save(key: String, source: File) {
        runCatching {
            directory.mkdirs()
            val temp=File(directory,"$key.tmp")
            source.copyTo(temp,true)
            check(temp.renameTo(File(directory,"$key.wav")))
            var total=0L
            directory.listFiles()?.filter { it.extension=="wav" }?.sortedByDescending { it.lastModified() }?.forEach { total+=it.length();if(total>32L*1024*1024)it.delete() }
        }
    }
}
