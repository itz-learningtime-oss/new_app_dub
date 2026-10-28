package com.voxstudio.app

import android.app.*
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import java.io.File

/**
 * Paragraph-oriented studio. Each chunk carries its own actions when selected; the bottom bar holds
 * either generation progress or the primary "generate remaining" action, above a pinned player.
 */
class ChunkStudioDialog(private val activity: Activity,private val dir: File,
    private val startTask: (String,(android.content.Context)->Unit)->Unit,
    private val export: ()->Unit,private val autoGenerate: Boolean=false): Dialog(activity,if(Palette.dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar) {
    /** Status and navigation bar icons follow the light or dark appearance. */
    private fun Window.matchSystemBars() {
        statusBarColor=Palette.bg;navigationBarColor=Palette.bg
        if(Build.VERSION.SDK_INT>=30) {
            val light=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            insetsController?.setSystemBarsAppearance(if(Palette.dark) 0 else light,light)
        } else decorView.systemUiVisibility=if(Palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    private val store=StudioStore(activity)
    private val handler=Handler(Looper.getMainLooper())
    private var selected=0;private var loaded=-1;private var playingAll=false;private var revision=""
    private lateinit var document: LinearLayout;private lateinit var scroll: ScrollView
    private lateinit var subtitle: TextView;private lateinit var exportButton: Button
    private lateinit var progressBox: LinearLayout;private lateinit var progressTitle: TextView;private lateinit var progressDetail: TextView
    private lateinit var generationProgress: ProgressBar;private lateinit var stop: Button
    private lateinit var primary: Button;private lateinit var notice: TextView
    private lateinit var play: ImageButton;private lateinit var previous: ImageButton;private lateinit var next: ImageButton
    private lateinit var speed: Button;private lateinit var position: TextView;private lateinit var seek: SeekBar
    private var dragging=false
    private val audio=AudioPlayback(activity) { if(::play.isInitialized) refreshTransport() }
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun shape(color: Int,radius: Int=12,border: Int?=null,width: Int=1)=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(radius).toFloat();if(border!=null) setStroke(dp(width),border)
    }
    private fun text(value: String,size: Float=14f,color: Int=Palette.ink,bold: Boolean=false)=TextView(activity).apply {
        this.text=value;textSize=size;setTextColor(color);setLineSpacing(dp(3).toFloat(),1f)
        if(bold) typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    private fun button(value: String,primary: Boolean=false,click: ()->Unit)=Button(activity).apply {
        this.text=value;isAllCaps=false;textSize=14f;minWidth=0;minimumWidth=0;minHeight=dp(48);minimumHeight=dp(48)
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setTextColor(if(primary) Palette.bg else Palette.ink);background=shape(if(primary) Palette.ink else Palette.raised,14);stateListAnimator=null
        setPadding(dp(14),dp(6),dp(14),dp(6));setOnClickListener { guard(click) }
    }
    private fun icon(res: Int,description: String,primary: Boolean=false,click: ()->Unit)=ImageButton(activity).apply {
        setImageResource(res);contentDescription=description;scaleType=ImageView.ScaleType.CENTER
        imageTintList=ColorStateList.valueOf(if(primary) Palette.bg else Palette.ink)
        background=if(primary) shape(Palette.ink,28) else null
        setOnClickListener { guard(click) }
    }
    private fun row(parent: LinearLayout)=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL;parent.addView(this,LinearLayout.LayoutParams(-1,-2)) }
    private fun gap(parent: LinearLayout,n: Int=12) { parent.addView(Space(activity),LinearLayout.LayoutParams(1,dp(n))) }
    private fun meta()=store.metadata(dir)
    private fun voiceKind()=meta().optString("voiceId").let { if(it.startsWith(BuiltInVoices.PREFIX)) "built-in" else if(it.isNotEmpty()) "cloned" else "model" }
    private fun parts()=meta().getJSONArray("sections")
    private fun file(index: Int)=File(dir,"section-$index.wav")
    private fun missing()=(0 until parts().length()).filter { !file(it).isFile }
    private fun ownsJob()=StudioJobs.busy && StudioJobs.chunkProject==dir.name
    private fun guard(task: ()->Unit) { try { task() } catch(e: Exception) { AlertDialog.Builder(activity).setTitle("Couldn't continue").setMessage(e.message).setPositiveButton("Close",null).show() } }
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val root=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Palette.bg) }
        setContentView(root)
        window?.matchSystemBars()
        val header=row(root).apply { setPadding(dp(8),dp(8),dp(16),dp(8)) }
        header.addView(icon(R.drawable.ic_back,"Close studio") { dismiss() }.apply { imageTintList=ColorStateList.valueOf(Palette.accent) },LinearLayout.LayoutParams(dp(48),dp(48)))
        val heading=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(4),0,dp(10),0) }
        heading.addView(text(meta().getString("title"),17f,bold=true).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END })
        subtitle=text("",13f,Palette.muted).also { heading.addView(it) }
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        exportButton=button("Export") { requestExport() }
        header.addView(exportButton,LinearLayout.LayoutParams(-2,-2))
        root.addView(View(activity).apply { setBackgroundColor(Palette.line) },LinearLayout.LayoutParams(-1,dp(1)))
        scroll=ScrollView(activity).apply { isFillViewport=true }
        document=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(24)) }
        scroll.addView(document);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val footer=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(10),dp(16),dp(12));setBackgroundColor(Palette.bar) }
        root.addView(View(activity).apply { setBackgroundColor(Palette.line) },LinearLayout.LayoutParams(-1,dp(1)))
        root.addView(footer)
        // Top bar and page clear the status bar; the player bar takes the navigation-bar edge so its colour runs to the bottom.
        root.padForSystemBars(bottom=false);footer.padForSystemBars(top=false)
        progressBox=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        progressTitle=text("",14f,Palette.accent,true).also { progressBox.addView(it) }
        progressDetail=text("",12f,Palette.muted).also { progressBox.addView(it) }
        gap(progressBox,8)
        generationProgress=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000;progressTintList=ColorStateList.valueOf(Palette.accent) }
        progressBox.addView(generationProgress,LinearLayout.LayoutParams(-1,dp(6)))
        gap(progressBox,8)
        stop=button("Stop after this step") { StudioJobs.cancel();refreshTransport() }
        progressBox.addView(stop,LinearLayout.LayoutParams(-1,-2))
        footer.addView(progressBox,LinearLayout.LayoutParams(-1,-2))
        notice=text("",12f,Palette.muted).also { footer.addView(it) }
        primary=button("",true) { generate(missing(),false) }
        footer.addView(primary,LinearLayout.LayoutParams(-1,-2))
        gap(footer,6)
        seek=SeekBar(activity).apply { max=1000;progressTintList=ColorStateList.valueOf(Palette.accent);thumbTintList=ColorStateList.valueOf(Palette.accent);contentDescription="Playback position" }
        footer.addView(seek,LinearLayout.LayoutParams(-1,dp(36)))
        seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(b: SeekBar?) { dragging=true }
            override fun onStopTrackingTouch(b: SeekBar?) { audio.seek((audio.duration.toLong()*seek.progress/1000).toInt());dragging=false }
            override fun onProgressChanged(b: SeekBar?,p: Int,user: Boolean) { if(user && !dragging)audio.seek((audio.duration.toLong()*p/1000).toInt()) }
        })
        val transport=row(footer)
        position=text("0:00 / 0:00",13f,Palette.muted);transport.addView(position,LinearLayout.LayoutParams(0,-2,1f))
        previous=icon(R.drawable.ic_prev,"Previous chunk") { select((selected-1).coerceAtLeast(0)) }
        play=icon(R.drawable.ic_play,"Play chunk",true) { if(loaded!=selected) load(true) else { playingAll=!audio.playing;audio.toggle() } }
        next=icon(R.drawable.ic_next,"Next chunk") { select((selected+1).coerceAtMost(parts().length()-1)) }
        speed=button("1×") { val speeds=floatArrayOf(.75f,1f,1.25f,1.5f,2f);val value=speeds[(speeds.indexOfFirst { kotlin.math.abs(it-audio.speed)<.01f }+1)%speeds.size];audio.changeSpeed(value);speed.text=speedLabel(value) }
        speed.contentDescription="Playback speed"
        for((view,size) in listOf(previous to 48,play to 56,next to 48)) transport.addView(view,LinearLayout.LayoutParams(dp(size),dp(size)).apply { setMargins(dp(4),0,dp(4),0) })
        transport.addView(speed,LinearLayout.LayoutParams(dp(64),-2).apply { setMargins(dp(8),0,0,0) })
        renderDocument();handler.post(tick)
        if(autoGenerate && !StudioJobs.busy && missing().isNotEmpty()) guard { generate(missing(),false) }
    }
    private fun speedLabel(value: Float)=(if(value==value.toInt().toFloat()) value.toInt().toString() else value.toString())+"×"
    private fun TextView.assign(value: String) { if(text.toString()!=value)text=value }
    private fun time(ms: Int)="%d:%02d".format(ms/60000,ms/1000%60)
    private fun select(index: Int) { audio.pause();playingAll=false;selected=index;loaded=-1;renderDocument();refreshTransport() }
    private fun load(auto: Boolean) { require(file(selected).isFile) { "Generate this chunk first." };loaded=selected;playingAll=auto;audio.load(file(selected),auto) }
    private fun requestExport() {
        val remaining=missing()
        if(remaining.isEmpty() && File(dir,"full.wav").isFile) { pause();export();return }
        require(!StudioJobs.busy) { "Export becomes available when generation finishes." }
        if(remaining.isEmpty()) { startTask("Assembling your narration…") { StudioJobs.assemble(it,dir) };return }
        AlertDialog.Builder(activity).setTitle("Not ready to export")
            .setMessage("${remaining.size} of ${parts().length()} chunks still need audio. Generate them now? Chunks that already have audio are kept.")
            .setNegativeButton("Not now",null).setPositiveButton("Generate ${remaining.size}") { _,_ -> guard { generate(remaining,false) } }.show()
    }
    private fun refreshTransport() {
        val ready=file(selected).isFile
        val total=parts().length();val done=total-missing().size
        val voice=meta().optString("voiceName","Default voice")
        subtitle.assign("$voice · $done of $total chunks ready")
        val mine=ownsJob();val otherJob=StudioJobs.busy && !mine
        progressBox.visibility=if(mine) View.VISIBLE else View.GONE
        if(mine) {
            val s=StudioJobs.generation.snapshot()
            val active=StudioJobs.activeChunk
            progressTitle.assign(if(active>=0) "Generating chunk ${active+1}${if(s.total>1) " · ${(s.completed+1).coerceAtMost(s.total)} of ${s.total}" else ""}" else StudioJobs.status.lineSequence().first())
            val speech=if(s.frames>0) " · ${"%.1f".format(s.audioSeconds)}s of audio so far" else ""
            progressDetail.assign("${s.stage.ifEmpty { "Starting" }} · ${s.elapsed} elapsed$speech")
            // Milestones only: chunks done plus the current chunk's stage. Never presented as time remaining.
            generationProgress.progress=if(s.total>0) ((s.completed+(s.stageNumber-1).coerceAtLeast(0)/5f)*1000/s.total).toInt() else 0
            stop.isEnabled=!StudioJobs.stop;stop.assign(if(StudioJobs.stop) "Stopping…" else "Stop after this step")
        }
        val remaining=total-done
        primary.visibility=if(!mine && remaining>0) View.VISIBLE else View.GONE
        primary.assign(when { total==1 -> "Generate";done==0 -> "Generate All $total Chunks";else -> "Generate $remaining Remaining ${if(remaining==1) "Chunk" else "Chunks"}" })
        primary.isEnabled=!StudioJobs.busy
        val message=when {
            otherJob -> "Another task is running. You can listen while it finishes."
            !mine && StudioJobs.lastError!=null -> "Couldn't generate: ${StudioJobs.lastError}"
            audio.error!=null -> audio.error!!
            !mine && remaining==0 -> "All chunks ready. Tap a paragraph to listen, edit or regenerate it."
            else -> ""
        }
        notice.assign(message);notice.visibility=if(message.isEmpty()) View.GONE else View.VISIBLE
        notice.setTextColor(if(message.startsWith("Couldn't")) Palette.error else Palette.muted)
        play.isEnabled=ready && !audio.loading
        play.setImageResource(if(audio.playing) R.drawable.ic_pause else R.drawable.ic_play)
        play.contentDescription=if(audio.playing) "Pause" else "Play chunk ${selected+1}"
        previous.isEnabled=selected>0;next.isEnabled=selected<total-1
        if(!dragging)seek.progress=if(loaded==selected && audio.duration>0) (audio.position.toLong()*1000/audio.duration).toInt() else 0
        seek.isEnabled=ready && loaded==selected && !audio.loading
        position.assign(if(loaded==selected) "${time(audio.position)} / ${time(audio.duration)}" else if(ready) "Chunk ${selected+1}" else "No audio yet")
        exportButton.isEnabled=!StudioJobs.busy
        exportButton.setTextColor(if(remaining==0) Palette.bg else Palette.ink)
        exportButton.background=shape(if(remaining==0) Palette.ink else Palette.raised,14)
        for(control in listOf<View>(exportButton,primary,play,previous,next,speed))control.alpha=if(control.isEnabled) 1f else .35f
    }
    private fun chunkStatus(index: Int): Pair<String,Int> = when {
        StudioJobs.busy && StudioJobs.chunkProject==dir.name && StudioJobs.activeChunk==index -> "Generating…" to Palette.accent
        StudioJobs.busy && StudioJobs.chunkProject==dir.name && index in StudioJobs.queuedChunks -> "Queued" to Palette.muted
        file(index).isFile -> "Ready · ${time(runCatching { AudioPeaks.durationMs(file(index)).toInt() }.getOrDefault(0))}" to Palette.muted
        else -> "No audio yet" to Palette.muted
    }
    private fun renderDocument() {
        val y=scroll.scrollY;document.removeAllViews();val chunks=parts()
        val busy=StudioJobs.busy
        for(i in 0 until chunks.length()) {
            val chosen=i==selected
            val (status,statusColor)=chunkStatus(i)
            val card=LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12))
                background=shape(Palette.panel,12,if(chosen) Palette.accent else null,2)
                isSelected=chosen;isClickable=true;isFocusable=true
                contentDescription="Chunk ${i+1}, $status. ${chunks.getString(i)}"
                setOnClickListener { if(selected!=i) select(i) }
            }
            val top=row(card)
            top.addView(text("${i+1}",12f,if(chosen) Palette.accent else Palette.muted,true),LinearLayout.LayoutParams(0,-2,1f))
            top.addView(text(status,12f,statusColor,status=="Generating…"))
            gap(card,6)
            card.addView(text(chunks.getString(i),17f,if(chosen || file(i).isFile) Palette.ink else Palette.muted))
            if(chosen) {
                gap(card,12)
                val actions=row(card)
                val ready=file(i).isFile
                if(ready) actions.addView(button("Play") { if(loaded!=selected) load(true) else { playingAll=!audio.playing;audio.toggle() } }.apply { isEnabled=true },LinearLayout.LayoutParams(0,-2,1f))
                if(ready) actions.addView(Space(activity),LinearLayout.LayoutParams(dp(8),1))
                actions.addView(button(if(ready) "Regenerate" else "Generate",!ready) { generate(listOf(i),ready) }.apply { isEnabled=!busy;alpha=if(busy) .35f else 1f },LinearLayout.LayoutParams(0,-2,1.2f))
                actions.addView(Space(activity),LinearLayout.LayoutParams(dp(8),1))
                actions.addView(button("Edit") { editChunk() }.apply { isEnabled=!busy;alpha=if(busy) .35f else 1f;contentDescription="Edit chunk ${i+1} text" },LinearLayout.LayoutParams(0,-2,.9f))
                if(ready) card.addView(text("Report this audio",13f,Palette.muted).apply {
                    setPadding(0,dp(12),0,dp(2));isClickable=true;isFocusable=true;contentDescription="Report chunk ${i+1} audio"
                    setOnClickListener { guard { ReportDialog.show(activity,chunks.getString(i),voiceKind()) } }
                })
            }
            document.addView(card,LinearLayout.LayoutParams(-1,-2));gap(document,10)
        }
        document.addView(text("Blank lines in your script start a new chunk. Long paragraphs are split at about 220 characters so each chunk stays within what the model handles well.",12f,Palette.muted).apply { setPadding(dp(4),dp(8),dp(4),0) })
        scroll.post { scroll.scrollTo(0,y) }
    }
    private fun generate(indices: List<Int>,fresh: Boolean) {
        require(!StudioJobs.busy) { "Wait for the current task." }
        require(indices.isNotEmpty()) { "Every chunk already has audio. Select one to regenerate it." }
        audio.release();loaded=-1;playingAll=false
        startTask(if(fresh) "Regenerating chunk ${indices.first()+1}…" else "Generating chunks…") { StudioJobs.generateChunks(it,dir,indices,fresh) }
        renderDocument();refreshTransport()
    }
    private fun editChunk() {
        require(!StudioJobs.busy) { "Wait for generation to finish." }
        val editContext=ContextThemeWrapper(activity,android.R.style.Theme_Material_Dialog_Alert)
        val box=LinearLayout(editContext).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0) }
        val field=EditText(editContext).apply {
            setText(parts().getString(selected));minLines=4;maxLines=8;filters=arrayOf(android.text.InputFilter.LengthFilter(220))
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            contentDescription="Chunk text"
        }
        val count=text("",12f,Palette.muted)
        fun updateCount() { count.text="${field.length()} / 220 characters" }
        field.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,a: Int,b: Int,c: Int) {}
            override fun onTextChanged(s: CharSequence?,a: Int,b: Int,c: Int) { updateCount() }
            override fun afterTextChanged(s: Editable?) {}
        });updateCount()
        box.addView(field);box.addView(count)
        val dialog=AlertDialog.Builder(editContext).setTitle("Edit chunk ${selected+1}")
            .setMessage(if(file(selected).isFile) "Saving new words removes this chunk's audio. The previous take is kept on this phone." else "Generate this chunk after saving.")
            .setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { guard { ChunkProjects.edit(activity,dir,selected,field.text.toString());audio.release();loaded=-1;renderDocument();refreshTransport();dialog.dismiss() } } };dialog.show()
    }
    private var lastJobState=""
    private val tick=object: Runnable { override fun run() {
        val jobState="${StudioJobs.busy}:${StudioJobs.activeChunk}:${StudioJobs.queuedChunks.size}"
        val nextRevision=File(dir,"project.json").lastModified().toString()+ (0 until parts().length()).joinToString { "${file(it).lastModified()}:${file(it).length()}" }
        if(revision!=nextRevision || lastJobState!=jobState) { revision=nextRevision;lastJobState=jobState;renderDocument() }
        if(playingAll && loaded==selected && !audio.loading && !audio.playing && audio.duration>0 && audio.position>=audio.duration-40) {
            if(selected+1<parts().length() && file(selected+1).isFile) { selected++;renderDocument();load(true) } else playingAll=false
        }
        if(loaded!=selected && file(selected).isFile && !(ownsJob() && StudioJobs.activeChunk==selected))load(false)
        refreshTransport();handler.postDelayed(this,250)
    } }
    fun pause() { handler.removeCallbacks(tick);audio.pause();playingAll=false }
    fun resume() { handler.removeCallbacks(tick);handler.post(tick) }
    override fun onStart() { super.onStart();window?.setLayout(-1,-1) }
    override fun onStop() { handler.removeCallbacks(tick);audio.release();super.onStop() }
}
