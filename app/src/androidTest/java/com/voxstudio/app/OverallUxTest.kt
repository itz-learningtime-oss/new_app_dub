package com.voxstudio.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class OverallUxTest {
    private fun views(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    @Test fun mainNavigationAndLibrarySearchPreserveDraft() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val store=StudioStore(inst.targetContext)
        val before=store.prefs.getString("script","")
        // A temporary narration so Library shows its search field even on a fresh install.
        val sample=File(store.projects,UUID.randomUUID().toString()).also { it.mkdirs() }
        AudioFiles.write(File(sample,"full.wav"),ShortArray(16000) { 2000 })
        AudioFiles.write(File(sample,"section-0.wav"),ShortArray(16000) { 2000 })
        store.saveMetadata(sample,JSONObject().put("title","UX search sample").put("created",1L).put("voice",false)
            .put("sections",JSONArray().put("Sample words")).put("transcript","").put("steps",6))
        try { navigateAndSearch(inst,store,before) } finally { sample.deleteRecursively() }
    }
    private fun navigateAndSearch(inst: android.app.Instrumentation,store: StudioStore,before: String?) {
        val activity=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun tab(name: String) {
            inst.runOnMainSync { views(activity.window.decorView).first { it.contentDescription=="$name tab" }.performClick() }
            inst.waitForIdleSync()
        }
        tab("Voices")
        inst.runOnMainSync { assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString()=="Clone a Voice" }) }
        tab("Library")
        inst.runOnMainSync {
            val search=views(activity.window.decorView).filterIsInstance<EditText>().first { it.contentDescription=="Search narrations or voices" }
            search.setText("no_results_ux_check_8172")
            assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().startsWith("No narrations match") })
            search.setText("")
        }
        tab("Settings")
        inst.runOnMainSync { assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString()=="Open-Source Licenses" }) }
        tab("Create")
        inst.runOnMainSync { assertEquals(before,views(activity.window.decorView).filterIsInstance<EditText>().first { it.contentDescription=="Script" }.text.toString()) }
        assertEquals(before,store.prefs.getString("script",""))
    }
    @Test fun renderMainScreensForVisualReview() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val activity=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory=File(inst.targetContext.cacheDir,"ux-renders").also { it.mkdirs() }
        for(name in listOf("Create","Voices","Library","Settings")) {
            inst.runOnMainSync { views(activity.window.decorView).first { it.contentDescription=="$name tab" }.performClick() }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                // Render only our app's view hierarchy; this is a layout preview, not a lock-screen capture.
                val view=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val metrics=activity.resources.displayMetrics
                val width=metrics.widthPixels;val height=metrics.heightPixels
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                view.layout(0,0,width,height)
                assertTrue(view.width>0 && view.height>0)
                val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(directory,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        }
        inst.runOnMainSync { views(activity.window.decorView).first { it.contentDescription=="Create tab" }.performClick() }
    }
    @Test fun projectRenamePreservesDataAndDeleteStaysWithinProjects() {
        val inst=InstrumentationRegistry.getInstrumentation();val store=StudioStore(inst.targetContext)
        val dir=File(store.projects,UUID.randomUUID().toString()).also { it.mkdirs() }
        val outside=File(inst.targetContext.cacheDir,"ux-outside-marker").also { it.mkdirs() }
        try {
            AudioFiles.write(File(dir,"full.wav"),ShortArray(48000) { 3000 })
            val audio=File(dir,"full.wav").readBytes()
            val meta=JSONObject().put("title","UX test").put("created",12345L).put("voice",false)
                .put("sections",JSONArray().put("Keep these words")).put("transcript","").put("steps",6)
            store.saveMetadata(dir,meta)
            store.renameProject(dir,"Renamed UX test")
            assertEquals(12345L,store.metadata(dir).getLong("created"))
            assertEquals("Renamed UX test",store.metadata(dir).getString("title"))
            assertArrayEquals(audio,File(dir,"full.wav").readBytes())
            assertThrows(IllegalArgumentException::class.java) { store.deleteProject(outside) }
            assertTrue(outside.isDirectory)
            store.deleteProject(dir);assertFalse(dir.exists())
        } finally { dir.deleteRecursively();outside.deleteRecursively() }
    }
}
