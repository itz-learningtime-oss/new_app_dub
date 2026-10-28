package com.voxstudio.app
import org.junit.Assert.*
import org.junit.Test
class AutoVoiceSampleTest {
 @Test fun avoidsLeadingSilence() {
  val samples=ShortArray(16000*20) { if(it<16000*8 || it>16000*16) 0 else if(it%2==0) 2000 else -2000 }
  val choice=AutoVoiceSample.choose(AudioFiles.Wave(16000,1,samples))
  assertTrue(choice.start>=7.5);assertEquals(5.0,choice.duration,.001)
  assertTrue(choice.start+choice.duration<=20)
 }
 @Test fun rejectsSilence() { assertThrows(IllegalArgumentException::class.java) { AutoVoiceSample.choose(AudioFiles.Wave(16000,1,ShortArray(160000))) } }
}
