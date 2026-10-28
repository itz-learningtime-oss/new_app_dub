package com.voxstudio.app

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.*
import android.media.AudioManager
import android.os.*
import android.text.TextUtils
import android.view.*
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

/** A dedicated player shared by narration projects and saved voice samples. */
class StudioPlayerDialog(
    private val activity: Activity,
    private val title: String,
    private val voice: String,
    private val full: File,
    private val sections: List<Pair<String,File>> = emptyList(),
    private val exportAudio: (() -> Unit)? = null,
    private val regenerate: ((Int) -> Unit)? = null,
    private val closed: () -> Unit
) : Dialog(activity,if(Palette.dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar) {
    private val bg get()=Palette.bg;private val panel get()=Palette.panel
    private val ink get()=Palette.ink;private val muted get()=Palette.muted;private val orange get()=Palette.accent
    /** Status and navigation bar icons follow the light or dark appearance. */
    private fun Window.matchSystemBars() {
        statusBarColor=Palette.bg;navigationBarColor=Palette.bg
        if(Build.VERSION.SDK_INT>=30) {
            val light=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            insetsController?.setSystemBarsAppearance(if(Palette.dark) 0 else light,light)
        } else decorView.systemUiVisibility=if(Palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    private val handler=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor();private var waveJob: Future<*>?=null
    private lateinit var audio: AudioPlayback
    private lateinit var status: TextView;private lateinit var elapsed: TextView;private lateinit var total: TextView
    private lateinit var seek: SeekBar;private lateinit var toggle: Button;private lateinit var backTen: Button;private lateinit var forwardTen: Button
    private lateinit var speed: Button;private lateinit var mode: Button;private lateinit var previous: Button;private lateinit var next: Button
    private lateinit var waveform: PlaybackWaveform;private lateinit var list: LinearLayout
    private var selected=-1;private var dragging=false;private var receiverRegistered=false
    private var savedSpeed=activity.getSharedPreferences("studio",Context.MODE_PRIVATE).getFloat("playbackSpeed",1f)
    private val noisy=object: BroadcastReceiver() { override fun onReceive(context: Context?,intent: Intent?) { pause() } }
    private val ticker=object: Runnable { override fun run() { if(isShowing) { refresh();handler.postDelayed(this,250) } } }
    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).roundToInt()
    private fun shape(color: Int,radius: Int=12)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
    private fun text(value: String,size: Float=14f,color: Int=ink)=TextView(activity).apply { this.text=value;textSize=size;setTextColor(color);setLineSpacing(dp(2).toFloat(),1f) }
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private fun gap(parent: LinearLayout,size: Int=10) { parent.addView(Space(activity),LinearLayout.LayoutParams(1,dp(size))) }
    private fun button(value: String,primary: Boolean=false,action: () -> Unit)=Button(activity).apply {
        text=value;isAllCaps=false;textSize=16f;minWidth=0;minimumWidth=0;minHeight=dp(48);stateListAnimator=null
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setTextColor(if(primary) bg else ink);background=shape(if(primary) ink else Palette.raised,12)
        setPadding(dp(8),dp(6),dp(8),dp(6));setOnClickListener { action() }
    }
    private fun row(parent: LinearLayout)=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;parent.addView(this,LinearLayout.LayoutParams(-1,-2)) }
    private fun add(row: LinearLayout,view: View,weight: Float=1f) { row.addView(view,LinearLayout.LayoutParams(0,dp(50),weight).apply { setMargins(dp(3),0,dp(3),0) }) }
    private fun time(ms: Int)="%d:%02d".format(ms.coerceAtLeast(0)/60000,ms.coerceAtLeast(0)/1000%60)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root=column().apply { setBackgroundColor(bg);setPadding(dp(18),dp(14),dp(18),dp(14)) }
        setContentView(root)
        root.padForSystemBars()
        window?.apply { setBackgroundDrawable(ColorDrawable(bg));matchSystemBars();setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN) }
        val heading=row(root)
        val headingText=column()
        headingText.addView(text("Now Playing",13f,orange).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) });gap(headingText,2)
        headingText.addView(text(title,28f).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END;typeface=Typeface.DEFAULT_BOLD })
        heading.addView(headingText,LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(button("Done") { dismiss() }.apply { background=null;setTextColor(orange);contentDescription="Close player" },LinearLayout.LayoutParams(-2,dp(48)))
        gap(root,8)
        root.addView(text(if(sections.isEmpty()) voice else "$voice · ${sections.size} sections",15f,muted));gap(root,16)
        val card=column().apply { background=shape(panel);setPadding(dp(14),dp(12),dp(14),dp(14)) }
        root.addView(card,LinearLayout.LayoutParams(-1,-2))
        mode=button(if(sections.isEmpty()) "Voice Sample" else "Full Narration") { if(sections.isNotEmpty()) chooseTrack() }
        mode.isEnabled=sections.isNotEmpty();card.addView(mode,LinearLayout.LayoutParams(-1,dp(42)))
        gap(card,8)
        waveform=PlaybackWaveform(activity,orange,muted)
        waveform.contentDescription="Audio waveform. Use Playback position slider to seek."
        card.addView(waveform,LinearLayout.LayoutParams(-1,dp(60)))
        seek=SeekBar(activity).apply {
            max=1000;progressTintList=android.content.res.ColorStateList.valueOf(orange)
            thumbTintList=android.content.res.ColorStateList.valueOf(orange);contentDescription="Playback position"
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { dragging=true }
                override fun onStopTrackingTouch(bar: SeekBar) { audio.seek((audio.duration.toLong()*bar.progress/1000).toInt());dragging=false;refresh() }
                override fun onProgressChanged(bar: SeekBar,value: Int,fromUser: Boolean) {
                    if(fromUser) {
                        val position=(audio.duration.toLong()*value/1000).toInt()
                        elapsed.text=time(position);waveform.progress=value/1000f
                        if(!dragging) audio.seek(position) // Accessibility / keyboard changes also seek.
                    }
                }
            })
        }
        card.addView(seek,LinearLayout.LayoutParams(-1,dp(36)))
        val times=row(card);elapsed=text("0:00",12f,muted);total=text("0:00",12f,muted)
        times.addView(elapsed,LinearLayout.LayoutParams(0,-2,1f));times.addView(total)
        gap(card,12)
        val transport=row(card)
        backTen=button("−10 s") { audio.skip(-10000);refresh() }.apply { contentDescription="Rewind ten seconds" }
        toggle=button("Play",true) { audio.toggle();refresh() }.apply { contentDescription="Play audio" }
        forwardTen=button("+10 s") { audio.skip(10000);refresh() }.apply { contentDescription="Forward ten seconds" }
        add(transport,backTen);add(transport,toggle,1.5f);add(transport,forwardTen)
        gap(card,8)
        val secondary=row(card)
        previous=button("Previous") { select(selected-1,audio.playing) }
        speed=button("1×") { chooseSpeed() }
        next=button("Next") { select(selected+1,audio.playing) }
        if(sections.isNotEmpty()) add(secondary,previous)
        add(secondary,speed)
        if(sections.isNotEmpty()) add(secondary,next)
        status=text("Preparing audio…",12f,muted);gap(card,8);card.addView(status)
        gap(root,16)
        val listHeading=row(root)
        listHeading.addView(text(if(sections.isEmpty()) "VOICE PREVIEW" else "SECTIONS",13f,muted),LinearLayout.LayoutParams(0,-2,1f))
        if(sections.isNotEmpty()) listHeading.addView(text("Tap to listen",13f,muted))
        gap(root,8)
        val scroll=ScrollView(activity).apply { isFillViewport=true }
        list=column();scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        gap(root,12)
        root.addView(button(if(exportAudio==null) "Done" else "Export WAV",true) { if(exportAudio==null) dismiss() else exportAudio.invoke() },LinearLayout.LayoutParams(-1,dp(50)))
        audio=AudioPlayback(activity) { if(::status.isInitialized) refresh() }
        audio.changeSpeed(savedSpeed.coerceIn(.5f,2f))
        setOnDismissListener {
            handler.removeCallbacks(ticker);waveJob?.cancel(true);worker.shutdownNow();audio.release()
            if(receiverRegistered) { activity.unregisterReceiver(noisy);receiverRegistered=false }
            closed()
        }
        val filter=IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if(Build.VERSION.SDK_INT>=33) activity.registerReceiver(noisy,filter,Context.RECEIVER_NOT_EXPORTED) else activity.registerReceiver(noisy,filter)
        receiverRegistered=true
        select(-1,false)
    }
    override fun show() { super.show();window?.setLayout(-1,-1);window?.decorView?.requestApplyInsets();handler.post(ticker) }
    fun pause() { if(::audio.isInitialized) audio.pause() }
    fun startPreview() { if(::audio.isInitialized) audio.play() }
    private fun select(index: Int,autoPlay: Boolean) {
        if(index !in -1 until sections.size) return
        selected=index
        val file=if(index<0) full else sections[index].second
        mode.text=if(sections.isEmpty()) "Voice Sample" else if(index<0) "Full Narration" else "Section ${index+1} of ${sections.size}"
        audio.load(file,autoPlay)
        waveform.peaks=FloatArray(96)
        waveJob?.cancel(true)
        waveJob=worker.submit {
            val peaks=runCatching { AudioPeaks.read(file) }.getOrNull() ?: return@submit
            activity.runOnUiThread { if(isShowing && audio.file==file) waveform.peaks=peaks }
        }
        drawSections();refresh()
    }
    private fun chooseTrack() {
        val names=(listOf("Full narration")+sections.mapIndexed { i,item -> "${i+1}. ${item.first.take(70)}" }).toTypedArray()
        AlertDialog.Builder(activity).setTitle("Listen to")
            .setSingleChoiceItems(names,selected+1) { dialog,which -> select(which-1,audio.playing);dialog.dismiss() }
            .setNegativeButton("Cancel",null).show()
    }
    private fun chooseSpeed() {
        val values=listOf(.75f,1f,1.25f,1.5f,2f)
        AlertDialog.Builder(activity).setTitle("Playback speed")
            .setSingleChoiceItems(values.map { "${it.toString().removeSuffix(".0")}×" }.toTypedArray(),values.indexOf(audio.speed)) { dialog,index ->
                audio.changeSpeed(values[index]);savedSpeed=audio.speed
                activity.getSharedPreferences("studio",Context.MODE_PRIVATE).edit().putFloat("playbackSpeed",savedSpeed).apply()
                dialog.dismiss();refresh()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun drawSections() {
        list.removeAllViews()
        if(sections.isEmpty()) {
            list.addView(text("Listen to your saved sample here. Close the player to return to your voice.",14f,muted))
            return
        }
        sections.forEachIndexed { index,item ->
            val card=column().apply {
                setPadding(dp(14),dp(12),dp(14),dp(12))
                background=shape(panel,12).apply { if(index==selected) setStroke(dp(2),orange) }
            }
            val header=row(card)
            header.addView(text("%02d".format(index+1),12f,if(index==selected) orange else muted),LinearLayout.LayoutParams(0,-2,1f))
            header.addView(text(if(index==selected) "Selected" else "Play",13f,orange))
            gap(card,6);card.addView(text(item.first,16f))
            card.contentDescription="Section ${index+1}. ${item.first}. Tap to listen."
            card.isFocusable=true;card.setOnClickListener { select(index,true) }
            if(index==selected && regenerate!=null) {
                gap(card,10)
                card.addView(button("Regenerate section ${index+1}") {
                    audio.pause()
                    AlertDialog.Builder(activity).setTitle("Regenerate section ${index+1}?")
                        .setMessage("This replaces this section using the project's saved voice and quality settings.\n\n${item.first}")
                        .setNegativeButton("Cancel",null).setPositiveButton("Regenerate") { _,_ -> dismiss();regenerate.invoke(index) }.show()
                },LinearLayout.LayoutParams(-1,dp(48)))
            }
            list.addView(card,LinearLayout.LayoutParams(-1,-2));gap(list,8)
        }
    }
    private fun TextView.show(value: String) { if(text.toString()!=value) text=value }
    private fun refresh() {
        if(!::audio.isInitialized) return
        val canPlay=!audio.loading
        toggle.isEnabled=canPlay;backTen.isEnabled=canPlay && audio.duration>0;forwardTen.isEnabled=backTen.isEnabled;seek.isEnabled=backTen.isEnabled
        toggle.show(if(audio.playing) "Pause" else "Play")
        val toggleDescription=if(audio.playing) "Pause audio" else "Play audio"
        if(toggle.contentDescription!=toggleDescription) toggle.contentDescription=toggleDescription
        previous.isEnabled=selected>=0
        next.isEnabled=sections.isNotEmpty() && selected<sections.lastIndex
        speed.show("${audio.speed.toString().removeSuffix(".0")}×")
        speed.isEnabled=!audio.loading
        status.show(audio.error ?: when { audio.loading -> "Preparing audio…";audio.playing -> "Playing";audio.position>=audio.duration && audio.duration>0 -> "Finished · tap Play to replay";audio.position>0 -> "Paused";else -> "Ready to play" })
        if(!dragging) {
            val progress=if(audio.duration>0) audio.position.toFloat()/audio.duration else 0f
            seek.progress=(progress*1000).roundToInt();waveform.progress=progress
            elapsed.show(time(audio.position))
        }
        total.show(time(audio.duration))
        for(control in listOf(toggle,backTen,forwardTen,previous,next,speed)) control.alpha=if(control.isEnabled) 1f else .35f
    }
}

private class PlaybackWaveform(context: Context,private val accent: Int,private val muted: Int): View(context) {
    var peaks=FloatArray(96);set(value) { field=value;invalidate() }
    var progress=0f;set(value) { val next=value.coerceIn(0f,1f);if(field!=next) { field=next;invalidate() } }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        if(peaks.isEmpty()) return
        val step=width.toFloat()/peaks.size
        paint.strokeWidth=(step*.48f).coerceAtLeast(1f);paint.strokeCap=Paint.Cap.ROUND
        peaks.forEachIndexed { i,peak ->
            val x=(i+.5f)*step;val h=maxOf(3f,peak*height*.86f)
            paint.color=if((i+.5f)/peaks.size<=progress) accent else muted
            paint.alpha=if(paint.color==accent) 255 else 100
            canvas.drawLine(x,(height-h)/2,x,(height+h)/2,paint)
        }
        paint.color=accent;paint.alpha=255;paint.strokeWidth=2f
        canvas.drawLine(width*progress,0f,width*progress,height.toFloat(),paint)
    }
}
