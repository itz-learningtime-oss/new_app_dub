package com.voxstudio.app

import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

class AudioPlaybackTest {
    @Test fun playerCanPauseSeekChangeSpeedAndRelease() {
        val inst=InstrumentationRegistry.getInstrumentation()
        // Self-contained: a 5 s tone, so the test also passes on a fresh install with an empty library.
        val source=File(inst.targetContext.cacheDir,"playback-test.wav")
        AudioFiles.write(source,ShortArray(16000*5) { (kotlin.math.sin(it*2.0*Math.PI*220/16000)*4000).toInt().toShort() })
        lateinit var player: AudioPlayback
        val ready=CountDownLatch(1)
        inst.runOnMainSync {
            player=AudioPlayback(inst.targetContext) { if(!player.loading && player.duration>0) ready.countDown() }
            player.load(source)
        }
        try {
            assertTrue("Player should prepare asynchronously",ready.await(15,TimeUnit.SECONDS))
            inst.runOnMainSync {
                assertFalse(player.playing)
                player.seek(2000)
            }
            SystemClock.sleep(250)
            inst.runOnMainSync {
                assertTrue(kotlin.math.abs(player.position-2000)<=400)
                player.changeSpeed(1.25f)
                assertFalse("Changing speed must preserve pause",player.playing)
                assertEquals(1.25f,player.speed,0f)
                player.skip(-10000)
            }
            SystemClock.sleep(250)
            inst.runOnMainSync { assertTrue(player.position<400);player.play() }
            SystemClock.sleep(600)
            inst.runOnMainSync {
                assertTrue(player.playing);player.pause()
                assertFalse(player.playing)
                android.util.Log.i("VoxPlayerTest","Prepared, sought, speed changed while paused, skipped to start, played and paused")
            }
        } finally { inst.runOnMainSync { player.release() };source.delete() }
    }
}
