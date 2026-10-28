package com.voxstudio.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ChunkStudioTest {
    private fun views(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun partialGenerationEditingAndRegenerationPreserveOtherChunks() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudioStore(context);val previous=store.prefs.getString("studioProjectId",null)
        val dir=ChunkProjects.create(context,"Hello there.\n\nWelcome back.\n\nLet's begin.","Chunk studio verification",null,4)
        NativeEngine.backendForTesting="cpu";NativeEngine.setBenchmarkSeedForTesting(4242)
        try {
            assertEquals(3,store.metadata(dir).getJSONArray("sections").length())
            assertTrue(store.history().contains(dir))
            StudioJobs.generateChunks(context,dir,listOf(0,2))
            assertTrue(File(dir,"section-0.wav").isFile);assertFalse(File(dir,"section-1.wav").exists());assertFalse(File(dir,"full.wav").exists())
            val first=File(dir,"section-0.wav").readBytes();val last=File(dir,"section-2.wav").readBytes()
            StudioJobs.generateChunks(context,dir,listOf(1));assertTrue(File(dir,"full.wav").isFile)
            StudioJobs.generateChunks(context,dir,listOf(1),true)
            assertArrayEquals(first,File(dir,"section-0.wav").readBytes());assertArrayEquals(last,File(dir,"section-2.wav").readBytes())
            ChunkProjects.edit(context,dir,1,"Welcome to the studio.")
            assertFalse(File(dir,"section-1.wav").exists());assertFalse(File(dir,"full.wav").exists())
            assertTrue(dir.listFiles()!!.any { it.name.startsWith("section-1.previous-") })
            assertArrayEquals(first,File(dir,"section-0.wav").readBytes());assertArrayEquals(last,File(dir,"section-2.wav").readBytes())
            StudioJobs.generateChunks(context,dir,listOf(1));assertTrue(AudioFiles.read(File(dir,"full.wav")).seconds>1)
        } finally {
            NativeEngine.backendForTesting="auto";NativeEngine.setBenchmarkSeedForTesting(-1)
            store.deleteProject(dir);store.prefs.edit().putString("studioProjectId",previous).apply()
        }
    }
    /** Opens a partly generated project in the studio, then removes it so no test data stays in the library. */
    @Test fun createVisibleStudioPreview() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val store=StudioStore(context);val previous=store.prefs.getString("studioProjectId",null)
        val text="So, I built a PC and attached it to the back of my phone. It's a complete Linux computer, but it's small enough to fit in my pocket, which means I can carry it around wherever I go.\n\nIn this video, I'll show you how I built it, how everything works, and what I can actually do with this setup. Let's get started.\n\nFor this build, I'm using a Raspberry Pi Zero. Its small size lets it fit behind my phone, and its low power consumption means I can power it directly from the phone."
        val dir=ChunkProjects.create(context,text,"Studio preview",null,4)
        NativeEngine.backendForTesting="cpu"
        try { StudioJobs.generateChunks(context,dir,listOf(0)) } finally { NativeEngine.backendForTesting="auto" }
        val activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        inst.runOnMainSync {
            activity.javaClass.getDeclaredMethod("openStudio",File::class.java,Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,dir,false)
        }
        inst.waitForIdleSync()
        try {
            inst.runOnMainSync {
                // The studio is a full-screen dialog with its own window.
                val studio=activity.javaClass.getDeclaredField("chunkStudio").apply { isAccessible=true }.get(activity) as android.app.Dialog
                assertTrue(studio.isShowing)
                assertTrue(views(studio.window!!.decorView).any { it.contentDescription?.toString()?.startsWith("Chunk 1, Ready")==true })
            }
        } finally {
            inst.runOnMainSync { activity.finish() };inst.waitForIdleSync()
            store.deleteProject(dir);store.prefs.edit().putString("studioProjectId",previous).apply()
        }
    }
}
