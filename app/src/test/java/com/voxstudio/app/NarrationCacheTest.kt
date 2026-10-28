package com.voxstudio.app
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.File
class NarrationCacheTest {
 @Test fun identityAndCorruptionAreHandled() {
  val root=Files.createTempDirectory("narration-cache").toFile()
  try {
   val cache=NarrationCache(File(root,"cache"));val source=File(root,"source.wav");val out=File(root,"out.wav")
   AudioFiles.write(source,ShortArray(16000) { 1000 })
   val key=cache.key("hello","",null,"model:6")
   assertNotEquals(key,cache.key("hello","",null,"model:10"))
   assertNotEquals(key,cache.key("hello!","",null,"model:6"))
   cache.save(key,source);assertTrue(cache.restore(key,out));assertArrayEquals(source.readBytes(),out.readBytes())
   File(root,"cache/$key.wav").writeText("broken");assertFalse(cache.restore(key,out))
  } finally { root.deleteRecursively() }
 }
}
