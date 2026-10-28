package com.voxstudio.app

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TextScrollingTest {
    @Test fun draggingLongScriptScrollsEditorBeforePage() {
        val inst=InstrumentationRegistry.getInstrumentation()
        // Provide a long draft for the test and restore the user's own draft afterwards.
        val prefs=StudioStore(inst.targetContext).prefs
        val previousScript=prefs.getString("script",null)
        if((previousScript?.length ?: 0)<=800) prefs.edit().putString("script",List(20) { "This line exists so the script editor has more text than fits on screen." }.joinToString("\n")).commit()
        try { dragInEditor(inst) } finally { prefs.edit().putString("script",previousScript).commit() }
    }
    private fun dragInEditor(inst: android.app.Instrumentation) {
        val activity=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun descendants(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        lateinit var editor: EditText; lateinit var page: ScrollView
        var oldPageY=0; val point=IntArray(2)
        inst.runOnMainSync {
            val views=descendants(activity.window.decorView)
            editor=views.filterIsInstance<EditText>().first { it.hint.toString().startsWith("Write or paste") }
            assertTrue("Paste a long script before this test",editor.text.length>800)
            page=views.filterIsInstance<ScrollView>().first()
            editor.showSoftInputOnFocus=false
            page.scrollTo(0,editor.top-100); editor.scrollTo(0,0)
        }
        inst.waitForIdleSync(); SystemClock.sleep(300)
        inst.runOnMainSync { oldPageY=page.scrollY;editor.getLocationOnScreen(point) }
        val start=SystemClock.uptimeMillis()
        val x=point[0]+editor.width/2f
        val y=point[1]+editor.height*.8f
        fun touch(action: Int,at: Long,py: Float) {
            val event=MotionEvent.obtain(start,at,action,x,py,0)
            inst.sendPointerSync(event);event.recycle()
        }
        try {
            touch(MotionEvent.ACTION_DOWN,start,y)
            for(i in 1..12) {
                SystemClock.sleep(25)
                touch(MotionEvent.ACTION_MOVE,SystemClock.uptimeMillis(),y-editor.height*.5f*i/12)
            }
            touch(MotionEvent.ACTION_UP,SystemClock.uptimeMillis(),y-editor.height*.5f)
            inst.waitForIdleSync()
            inst.runOnMainSync {
                assertTrue("Script text should scroll inside its editor",editor.scrollY>0)
                assertEquals("Page must stay still while editor can scroll",oldPageY,page.scrollY)
                android.util.Log.i("VoxScrollTest","editor_scroll_y=${editor.scrollY} page_scroll_y=${page.scrollY} script_chars=${editor.text.length}")
            }
        } finally { inst.runOnMainSync { editor.showSoftInputOnFocus=true } }
    }
}
