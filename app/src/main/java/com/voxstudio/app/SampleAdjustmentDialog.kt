package com.voxstudio.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.os.*
import android.view.*
import android.widget.*
import java.io.File
import java.util.Locale
import kotlin.math.*

/** Range editor previews the original source; conversion occurs only on acceptance. */
class SampleAdjustmentDialog(context: Context,private val source: File,private val seconds: Double,
    initialStart: Double,initialLength: Double,private val accepted: (Double,Double)->Unit): Dialog(context) {
    private val bg get()=Palette.panel;private val panel get()=Palette.raised
    private val ink get()=Palette.ink;private val muted get()=Palette.muted;private val accent get()=Palette.accent
    private fun dp(n: Int)=(n*context.resources.displayMetrics.density).roundToInt()
    private var length=initialLength.coerceIn(3.0,min(20.0,seconds))
    private var at=initialStart.coerceIn(0.0,(seconds-length).coerceAtLeast(0.0))
    private var player: MediaPlayer?=null
    private val audio=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change -> if(change<0) stopPreview() }.build()
    fun pausePreview() { stopPreview() }
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var range: TextView
    private lateinit var timeline: SeekBar
    private lateinit var preview: Button
    private lateinit var status: TextView
    private val lengths=mutableListOf<Pair<Double,Button>>()
    private fun shape(color: Int)=GradientDrawable().apply { setColor(color);cornerRadius=dp(14).toFloat() }
    private fun label(text: String,size: Float=14f,color: Int=ink)=TextView(context).apply { this.text=text;textSize=size;setTextColor(color) }
    private fun button(text: String,primary: Boolean=false,click: ()->Unit)=Button(context).apply {
        this.text=text;isAllCaps=false;textSize=16f;setTextColor(if(primary) bg else ink);stateListAnimator=null
        typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
        background=shape(if(primary) ink else panel);minHeight=dp(48);setPadding(dp(10),0,dp(10),0)
        setOnClickListener { click() }
    }
    private fun time(value: Double): String {
        val tenths=(value*10).roundToInt();return "%d:%02d.%d".format(Locale.US,tenths/600,tenths/10%60,tenths%10)
    }
    private fun stopPreview() {
        handler.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.release() } };player=null
        audio.abandonAudioFocusRequest(focus)
        if(::preview.isInitialized) preview.text="Preview"
    }
    private fun refresh() {
        stopPreview()
        at=at.coerceIn(0.0,(seconds-length).coerceAtLeast(0.0))
        range.text="${time(at)}  —  ${time(at+length)}"
        timeline.max=((seconds-length)*10).roundToInt().coerceAtLeast(0)
        timeline.progress=(at*10).roundToInt()
        lengths.forEach { (value,button) ->
            button.background=shape(if(abs(value-length)<.01) ink else panel)
            button.setTextColor(if(abs(value-length)<.01) bg else ink)
            button.isSelected=abs(value-length)<.01
        }
        status.text="${"%.1f".format(Locale.US,length)} seconds selected · source ${time(seconds)}"
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        val root=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;background=shape(bg);setPadding(dp(20),dp(20),dp(20),dp(14)) }
        fun gap(n: Int) { root.addView(Space(context),LinearLayout.LayoutParams(1,dp(n))) }
        root.addView(label("Adjust Sample",22f).apply { typeface=android.graphics.Typeface.DEFAULT_BOLD });gap(8)
        root.addView(label("Choose a clear passage with one speaker. Five seconds is recommended.",15f,muted));gap(20)
        range=label("",26f).apply { gravity=Gravity.CENTER;setPadding(0,dp(16),0,dp(16));background=shape(panel) };root.addView(range)
        timeline=SeekBar(context).apply { contentDescription="Sample start position";progressTintList=android.content.res.ColorStateList.valueOf(accent);thumbTintList=android.content.res.ColorStateList.valueOf(accent) }
        root.addView(timeline,LinearLayout.LayoutParams(-1,dp(52)))
        status=label("",12f,muted);root.addView(status);gap(20)
        root.addView(label("LENGTH",13f,muted));gap(8)
        val presets=LinearLayout(context)
        listOf(5.0,10.0,20.0).forEach { value ->
            val b=button("${value.toInt()} sec") { length=min(value,seconds);refresh() }.apply { isEnabled=seconds>=value }
            lengths.add(value to b);presets.addView(b,LinearLayout.LayoutParams(0,dp(48),1f).apply { setMargins(dp(3),0,dp(3),0) })
        };root.addView(presets);gap(14)
        val moves=LinearLayout(context)
        for((text,delta) in listOf("−1 sec" to -1.0,"+1 sec" to 1.0)) {
            moves.addView(button(text) { at+=delta;refresh() },LinearLayout.LayoutParams(0,dp(48),1f).apply { setMargins(dp(3),0,dp(3),0) })
        };root.addView(moves);gap(12)
        preview=button("Preview") {
            if(player!=null) stopPreview() else {
                if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    status.text="Audio is busy. Try preview again shortly."
                } else {
                val media=MediaPlayer();media.setAudioAttributes(attributes);player=media;preview.text="Preparing preview…"
                media.setOnPreparedListener { if(player===it) { it.seekTo((at*1000).toInt()) } }
                media.setOnSeekCompleteListener {
                    if(player===it) {
                        it.start();preview.text="Ⅱ  Stop preview"
                        handler.post(object: Runnable { override fun run() {
                            if(player!==media)return
                            if(runCatching { media.currentPosition>=((at+length)*1000).toInt() }.getOrDefault(true)) stopPreview()
                            else handler.postDelayed(this,40)
                        } })
                    }
                }
                media.setOnCompletionListener { stopPreview() }
                media.setOnErrorListener { _,_,_ -> stopPreview();status.text="Preview unavailable. Choose another range or save to hear the converted sample.";true }
                try { media.setDataSource(source.absolutePath);media.prepareAsync() }
                catch(_: Exception) { stopPreview();status.text="Could not preview this file." }
                }
            }
        };root.addView(preview,LinearLayout.LayoutParams(-1,dp(50)));gap(18)
        root.addView(label("Words will be extracted automatically. You can review them on the next screen.",12f,muted));gap(16)
        root.addView(button("Use this sample",true) { stopPreview();accepted(at,length);dismiss() },LinearLayout.LayoutParams(-1,dp(52)));gap(8)
        root.addView(button("Cancel") { dismiss() },LinearLayout.LayoutParams(-1,dp(48)))
        val scroll=ScrollView(context).apply { addView(root) };setContentView(scroll)
        timeline.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?,progress: Int,fromUser: Boolean) { if(fromUser) { at=progress/10.0;refresh() } }
            override fun onStartTrackingTouch(bar: SeekBar?) { stopPreview() }
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        refresh()
    }
    override fun onStart() { super.onStart();window?.setLayout(context.resources.displayMetrics.widthPixels-dp(24),-2) }
    override fun onStop() { stopPreview();super.onStop() }
}
