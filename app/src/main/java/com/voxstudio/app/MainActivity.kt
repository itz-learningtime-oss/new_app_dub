package com.voxstudio.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.net.Uri
import android.os.*
import android.text.*
import android.view.*
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*

private const val PRIVACY_POLICY_URL="https://github.com/orailnoor/hush-studio/blob/main/PRIVACY.md"

class MainActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private val bg get() = Palette.bg
    private val ink get() = Palette.ink
    private val muted get() = Palette.muted
    private val accent get() = Palette.accent
    private lateinit var store: StudioStore
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var tab = "Create"
    private var voiceEditor=false
    private var voiceFilter="All"
    private var voiceNotice=""
    private var cloneVoiceAction: (() -> Unit)?=null
    private var generateSpeechAction: (() -> Unit)?=null
    private var footerReady: (() -> Boolean)?=null
    private var footerMessage: (() -> String)?=null
    private var footerButton: Button?=null
    private var footerHint: TextView?=null
    private var pageScroll: ScrollView?=null
    private var drawnTab=""
    private val scrollPositions=mutableMapOf<String,Int>()
    private var libraryQuery=""
    private val handler = Handler(Looper.getMainLooper())
    private var seenRevision = -1
    private var referenceStamp = 0L
    private var observedBusy = false
    private var jobText: TextView? = null
    private var timingText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var stopButton: Button? = null
    private var exportProject: File? = null
    private var playerDialog: StudioPlayerDialog? = null
    private var chunkStudio: ChunkStudioDialog?=null
    private var sampleAdjustment: SampleAdjustmentDialog? = null
    private var recorder: VoiceRecorder? = null
    private var recordLabel: TextView? = null
    private val io = Executors.newSingleThreadExecutor()
    private val tick = object : Runnable {
        override fun run() {
            if (seenRevision != StudioJobs.revision) {
                val changed = observedBusy != StudioJobs.busy
                observedBusy = StudioJobs.busy; seenRevision = StudioJobs.revision
                if (changed) draw() else updateProgress()
            }
            if (StudioJobs.busy) updateProgress()
            if (referenceStamp != store.reference.lastModified()) { referenceStamp = store.reference.lastModified(); draw() }
            val recordingStatus = when {
                recorder?.running == true -> "Recording · ${recorder!!.seconds} s of 20 s"
                recorder?.active == true -> "Processing sample…"
                store.reference.isFile -> "Sample saved on this phone"
                else -> "Record yourself or import a recording"
            }
            if(recordLabel?.text?.toString()!=recordingStatus) recordLabel?.text=recordingStatus
            handler.postDelayed(this,500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.update(this)
        store = StudioStore(applicationContext)
        referenceStamp=store.reference.lastModified()
        if (!StudioJobs.busy) {
            File(store.model.parentFile,"download.part").delete()
            File(store.model.parentFile,"import.part").delete()
            store.projects.listFiles()?.filter { !File(it,"project.json").isFile }?.forEach { it.deleteRecursively() }
            // Partial outputs from a process that died mid-chunk; finished chunk audio is untouched.
            store.projects.listFiles()?.forEach { dir -> dir.listFiles { f -> f.name.endsWith(".part.wav") || f.name=="project.json.tmp" }?.forEach { it.delete() } }
        }
        tab = savedInstanceState?.getString("tab") ?: "Create"
        if(tab=="Setup") tab="Settings"
        libraryQuery=savedInstanceState?.getString("libraryQuery") ?: ""
        listOf("Create","Voices","Library","Settings").forEach { key -> scrollPositions[key]=savedInstanceState?.getInt("scroll$key") ?: 0 }
        voiceEditor = savedInstanceState?.getBoolean("voiceEditor") ?: false
        voiceFilter = savedInstanceState?.getString("voiceFilter") ?: "All"
        exportProject = savedInstanceState?.getString("exportProject")?.let { File(store.projects,it) }
        Reports.flush(applicationContext)
        draw()
    }
    override fun onResume() { super.onResume();chunkStudio?.resume();handler.post(tick) }
    override fun onPause() { chunkStudio?.pause();sampleAdjustment?.pausePreview();handler.removeCallbacks(tick); playerDialog?.pause(); recorder?.stop(); super.onPause() }
    // Free the warm model when the system needs memory or the UI is hidden; unsaved work is unaffected.
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if(level>=TRIM_MEMORY_RUNNING_LOW && !StudioJobs.busy) StudioJobs.scheduleModelRelease(0) }
    override fun onDestroy() { chunkStudio?.setOnDismissListener(null);chunkStudio?.dismiss();chunkStudio=null;stopPlayback(); io.shutdown(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { pageScroll?.let { scrollPositions[drawnTab]=it.scrollY };scrollPositions.forEach { (key,value) -> outState.putInt("scroll$key",value) };outState.putString("libraryQuery",libraryQuery);outState.putString("tab",tab); outState.putBoolean("voiceEditor",voiceEditor);outState.putString("voiceFilter",voiceFilter); outState.putString("exportProject",exportProject?.name); super.onSaveInstanceState(outState) }
    private fun dp(n: Int) = ui.dp(n)
    /** Multi-line fields scroll internally first; the page takes over at their edges. */
    private fun field(parent: LinearLayout, hintText: String, value: String, lines: Int = 1, key: String? = null, surface: Int = Palette.panel): EditText = EditText(this).apply {
        hint = hintText; setText(value); setTextColor(ink); setHintTextColor(Palette.faint); textSize = 17f
        gravity = Gravity.TOP or Gravity.START; minLines = lines; maxLines = lines
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            (if (lines > 1) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if(lines>1) {
            setLines(lines); setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled=true
            overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS
            var lastY=0f; var downY=0f
            val slop=ViewConfiguration.get(context).scaledTouchSlop
            setOnTouchListener { view,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastY=event.y; downY=event.y
                        view.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val distance=lastY-event.y
                        if(abs(event.y-downY)>slop && distance!=0f) {
                            val direction=if(distance>0) 1 else -1
                            view.parent.requestDisallowInterceptTouchEvent(view.canScrollVertically(direction))
                        }
                        lastY=event.y
                    }
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> view.parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        background = ui.rounded(surface,12); setPadding(dp(16),dp(14),dp(16),dp(14))
        isEnabled = !StudioJobs.busy && recorder?.active!=true; contentDescription=hintText; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        parent.addView(this,LinearLayout.LayoutParams(-1,-2))
        if (key != null) addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { store.prefs.edit().putString(key,s.toString()).apply();updateFooter() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    private fun hideKeyboard() {
        currentFocus?.let { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.windowToken,0);it.clearFocus() }
    }
    private fun navigate(destination: String) {
        if(recorder?.active==true) { toast("Finish your recording before switching screens.");return }
        stopPlayback();hideKeyboard();tab=destination;draw()
    }
    // Android 16 (API 36 target) no longer calls onBackPressed; the callback is registered only while
    // this screen has somewhere to go back to, so the system back gesture can leave the app otherwise.
    private val backCallback=if(Build.VERSION.SDK_INT>=33) android.window.OnBackInvokedCallback { handleBack() } else null
    private var backRegistered=false
    private fun handlesBack()=recorder?.active==true || tab!="Create"
    private fun updateBackCallback() {
        if(Build.VERSION.SDK_INT<33) return
        val wanted=handlesBack()
        if(wanted==backRegistered) return
        if(wanted) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback!!)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback!!)
        backRegistered=wanted
    }
    private fun handleBack() {
        if(recorder?.active==true) { toast("Finish your recording before leaving.");return }
        if(tab=="Voices" && voiceEditor) { voiceEditor=false;voiceNotice="";draw() }
        else if(tab!="Create") navigate("Create")
    }
    // Only reached below API 33; API 33+ back gestures go through backCallback above.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Used below API 33; newer releases use OnBackInvokedCallback")
    override fun onBackPressed() {
        if(handlesBack()) handleBack() else super.onBackPressed()
    }
    private fun updateFooter() {
        val blocked=StudioJobs.busy || recorder?.active==true
        val ready=footerReady?.invoke() ?: true
        footerButton?.let {
            ui.setEnabled(it,!blocked && ready)
            val title=if(StudioJobs.busy) "Working…" else if(cloneVoiceAction!=null) "Save Voice" else if(!store.ready) "Set Up Speech" else "Generate"
            if(it.text.toString()!=title) it.text=title
        }
        val hint=if(StudioJobs.busy) "You can keep browsing while this finishes." else footerMessage?.invoke() ?: ""
        footerHint?.let { if(it.text.toString()!=hint) it.text=hint;it.visibility=if(hint.isEmpty()) View.GONE else View.VISIBLE }
    }
    private fun pageTitle(title: String,subtitle: String?=null,trailing: View?=null) {
        val top=ui.row(content).apply { setPadding(dp(4),dp(12),0,0) }
        top.addView(ui.largeTitle(title).apply { isFocusable=true;isAccessibilityHeading=true },LinearLayout.LayoutParams(0,-2,1f))
        trailing?.let { top.addView(it) }
        subtitle?.let { ui.gap(content,2);content.addView(ui.subhead(it).apply { setPadding(dp(4),0,dp(4),0) }) }
        ui.gap(content,8)
    }
    private fun systemBars() {
        window.statusBarColor=bg;window.navigationBarColor=Palette.bar
        if(Build.VERSION.SDK_INT>=30) {
            val light=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if(Palette.dark) 0 else light,light)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility=if(Palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
    private fun draw() {
        Palette.update(this)
        pageScroll?.let { scrollPositions[drawnTab]=it.scrollY }
        cloneVoiceAction=null;generateSpeechAction=null;footerReady=null;footerMessage=null;footerButton=null;footerHint=null
        jobText=null;timingText=null;progressBar=null;stopButton=null;recordLabel=null
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(bg) }
        var navigationView: View?=null
        root.setOnApplyWindowInsetsListener { view,insets ->
            if(Build.VERSION.SDK_INT>=30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
                navigationView?.visibility=if(insets.isVisible(WindowInsets.Type.ime())) View.GONE else View.VISIBLE
            } else view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        // The decor view (and its insets controller) exists only after setContentView.
        systemBars()
        val scroll=ScrollView(this).apply { isFillViewport=true;clipToPadding=false }
        pageScroll=scroll;drawnTab=tab
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(32)) }
        scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        when(tab) { "Create" -> createScreen();"Voices" -> voiceScreen();"Library" -> libraryScreen();else -> setupScreen() }
        (cloneVoiceAction ?: generateSpeechAction)?.let { action ->
            root.addView(ui.hairline(),LinearLayout.LayoutParams(-1,maxOf(1,dp(1)/2)))
            val footer=ui.column(root).apply { setPadding(dp(16),dp(10),dp(16),dp(10));setBackgroundColor(Palette.bar) }
            footerHint=ui.footnote("").apply { gravity=Gravity.CENTER;setPadding(0,0,0,dp(8)) }.also { footer.addView(it) }
            footerButton=ui.button("","primary") { hideKeyboard();safely(action) }.also { footer.addView(it,LinearLayout.LayoutParams(-1,-2)) }
            updateFooter()
        }
        root.addView(ui.hairline(),LinearLayout.LayoutParams(-1,maxOf(1,dp(1)/2)))
        val navigation=ui.row(root).apply { setPadding(dp(4),dp(6),dp(4),dp(4));setBackgroundColor(Palette.bar) }
        navigationView=navigation
        // Some resize-mode windows consume IME insets before they reach our root.
        // Observe the visible window as well so typing always gets the tab space.
        val visibleWindow=Rect()
        root.viewTreeObserver.addOnGlobalLayoutListener {
            root.getWindowVisibleDisplayFrame(visibleWindow)
            val keyboardVisible=(Build.VERSION.SDK_INT>=30 && root.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true) ||
                window.decorView.height-visibleWindow.bottom>dp(180)
            val visibility=if(keyboardVisible) View.GONE else View.VISIBLE
            if(navigation.visibility!=visibility) navigation.visibility=visibility
        }
        listOf("Create" to R.drawable.ic_tab_create,"Voices" to R.drawable.ic_tab_voices,"Library" to R.drawable.ic_tab_library,"Settings" to R.drawable.ic_tab_settings).forEach { (item,icon) ->
            val chosen=tab==item;val color=if(chosen) accent else muted
            val nav=ui.column().apply {
                gravity=Gravity.CENTER;minimumHeight=dp(52);isClickable=true;isFocusable=true;background=ui.pressable(null,12)
                contentDescription="$item tab";isSelected=chosen
                setOnClickListener { navigate(item) }
            }
            nav.addView(ImageView(this).apply { setImageResource(icon);imageTintList=android.content.res.ColorStateList.valueOf(color) },LinearLayout.LayoutParams(dp(26),dp(26)))
            nav.addView(ui.caption(item,color).apply { textSize=10f;gravity=Gravity.CENTER })
            navigation.addView(nav,LinearLayout.LayoutParams(0,-2,1f))
        }
        updateBackCallback()
        val restore=scrollPositions[tab] ?: 0
        scroll.post { if(pageScroll===scroll) scroll.scrollTo(0,restore) }
        root.requestApplyInsets()
    }
    /** Shown on every tab while a task runs, and for an unacknowledged error. */
    private fun statusCard() {
        if(StudioJobs.busy) {
            ui.gap(content,8)
            val box=ui.group(content).apply { setPadding(dp(16),dp(14),dp(16),dp(8)) }
            jobText=ui.headline(StudioJobs.status).also { box.addView(it) }
            timingText=ui.footnote("").also { box.addView(it) }
            ui.gap(box,10)
            progressBar=ui.progressBar(100).also { box.addView(it,LinearLayout.LayoutParams(-1,dp(4))) }
            stopButton=ui.button("Stop","plain") { StudioJobs.cancel();updateProgress() }.also { box.addView(it,LinearLayout.LayoutParams(-2,-2)) }
            updateProgress()
        } else StudioJobs.lastError?.let { message ->
            ui.gap(content,8)
            val box=ui.group(content).apply { setPadding(dp(16),dp(14),dp(16),dp(8)) }
            box.addView(ui.headline("Couldn’t Finish",Palette.error));ui.gap(box,4)
            box.addView(ui.subhead(message))
            box.addView(ui.button("Dismiss","plain") { StudioJobs.dismissError();draw() },LinearLayout.LayoutParams(-2,-2))
        }
    }
    private fun updateProgress() {
        updateFooter()
        val status=StudioJobs.status.lineSequence().first()
        if(jobText?.text?.toString()!=status) jobText?.text=status
        val s=StudioJobs.generation.snapshot()
        val native=StudioJobs.nativeActive
        val summary = if(s.total>0) {
            val speech=if(s.frames>0) " · ${"%.1f".format(s.audioSeconds)} s of audio" else ""
            "${s.stage.ifEmpty { "Starting" }} · ${s.elapsed} elapsed$speech · ${s.completed} of ${s.total} chunks done"
        } else ""
        timingText?.visibility=if(summary.isEmpty()) View.GONE else View.VISIBLE
        if(timingText?.text?.toString()!=summary) timingText?.text=summary
        progressBar?.max=if(native) 5 else 100
        progressBar?.isIndeterminate=if(native) false else StudioJobs.progress<0
        progressBar?.progress=if(native) (s.stageNumber-1).coerceAtLeast(0) else StudioJobs.progress.coerceAtLeast(0)
        stopButton?.let { ui.setEnabled(it,!StudioJobs.stop) }
        stopButton?.text = if(StudioJobs.stop) "Stopping…" else if (StudioJobs.isTransfer) "Cancel" else "Stop"
    }
    private fun qualityName(steps: Int)=when(steps) { 4 -> "Fast Preview";10 -> "Best Quality";else -> "Balanced" }
    private fun qualityDetail(steps: Int)=when(steps) { 4 -> "Quick drafts; words may be less clear";10 -> "Slower; best for final takes";else -> "A balance of speed and quality" }
    private fun chooseQuality(changed: () -> Unit) {
        val values=listOf(4,6,10)
        val names=values.map { "${qualityName(it)} — ${qualityDetail(it)}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Quality")
            .setSingleChoiceItems(names,values.indexOf(store.prefs.getInt("steps",6))) { dialog,index ->
                store.prefs.edit().putInt("steps",values[index]).apply();dialog.dismiss();changed();updateFooter()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun voiceAvatar(voice: VoiceLibrary.Voice?): View {
        val entry=voice?.let { BuiltInVoices.entry(this,it.id) }
        return ui.avatar(voice?.name ?: "?",entry?.let { genderHue(it) })
    }
    private fun genderHue(entry: BuiltInVoices.Entry)=(if(entry.gender=="female") 330f else 205f)+((entry.id.hashCode() and 0xff)%30)-15f
    private fun createScreen() {
        pageTitle("Create")
        statusCard()
        if(!store.ready) {
            ui.gap(content,8)
            val setup=ui.group(content).apply { setPadding(dp(16),dp(16),dp(16),dp(16)) }
            setup.addView(ui.headline("Set Up Offline Speech"));ui.gap(setup,4)
            setup.addView(ui.subhead("Download the speech model once, about 500 MB. After that, everything runs on this phone."));ui.gap(setup,14)
            setup.addView(ui.button("Set Up","primary") { navigate("Settings") },LinearLayout.LayoutParams(-1,-2))
        }
        store.prefs.getString("studioProjectId",null)?.let { id ->
            val dir=File(store.projects,id)
            val meta=if(File(dir,"project.json").isFile) runCatching { store.metadata(dir) }.getOrNull() else null
            if(meta!=null) {
                val total=meta.optJSONArray("sections")?.length() ?: 0
                val done=(0 until total).count { File(dir,"section-$it.wav").isFile }
                ui.sectionHeader(content,"Continue")
                ui.groupRow(ui.group(content),meta.optString("title"),"$done of $total chunks ready",chevron=true,
                    description="Continue ${meta.optString("title")}, $done of $total chunks ready") { safely { openStudio(dir) } }
            }
        }
        val selectedVoice=store.selectedVoice
        val selectedVoiceName=selectedVoice?.name ?: "Model Voice"
        ui.sectionHeader(content,"Voice")
        val settings=ui.group(content)
        ui.groupRow(settings,selectedVoiceName,voiceDescription(selectedVoice),leading=voiceAvatar(selectedVoice),chevron=true,
            description="Voice: $selectedVoiceName. Change voice") { voiceEditor=false;navigate("Voices") }
        ui.groupRow(settings,"Quality",value=qualityName(store.prefs.getInt("steps",6)),chevron=true) { chooseQuality { draw() } }
        ui.sectionHeader(content,"Script",ui.footnote("Saved automatically"))
        val script=field(content,"Write or paste your English script…",store.prefs.getString("script","") ?: "",8,"script")
        script.contentDescription="Script";script.filters=arrayOf(InputFilter.LengthFilter(6000))
        val count=ui.footnote("").apply { setPadding(dp(16),dp(7),dp(16),0) };content.addView(count)
        fun updateCount() {
            val words=script.text.toString().trim().split(Regex("\\s+")).count { it.isNotBlank() }
            val chunks=runCatching { ChunkProjects.split(script.text.toString()).size }.getOrDefault(0)
            count.text="${"%,d".format(script.length())} of 6,000 characters · $words words${if(chunks>0) " · $chunks ${if(chunks==1) "chunk" else "chunks"}" else ""}"
            updateFooter()
        }
        script.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { updateCount() }
            override fun afterTextChanged(s: Editable?) {}
        });updateCount()
        ui.sectionHeader(content,"Title")
        val title=field(content,"Optional",store.prefs.getString("title","") ?: "",key="title")
        title.filters=arrayOf(InputFilter.LengthFilter(80));title.contentDescription="Project title, optional"
        ui.sectionFooter(content,"Leave blank to use the first words of your script.")
        footerReady={ !store.ready || script.text.isNotBlank() }
        footerMessage={ when { !store.ready -> "Finish the one-time setup to start creating.";script.text.isBlank() -> "Add your script to get started.";else -> "$selectedVoiceName · ${qualityName(store.prefs.getInt("steps",6))}" } }
        generateSpeechAction={
            if(!store.ready) navigate("Settings") else {
                val text=script.text.toString();ScriptSections.split(text)
                val voiceId=store.selectedVoice?.id
                stopPlayback()
                val dir=ChunkProjects.create(this,text,title.text.toString(),voiceId,store.prefs.getInt("steps",6))
                openStudio(dir,autoGenerate=true)
            }
        }
    }
    private fun voiceDescription(voice: VoiceLibrary.Voice?): String {
        if(voice==null) return "Built-in voices are not installed in this build"
        return BuiltInVoices.entry(this,voice.id)?.label ?: "Your cloned voice"
    }
    private fun editName(title: String,current: String,limit: Int,save: (String) -> Unit) {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(10),dp(20),0) }
        val name=field(box,"Name",current,surface=Palette.raised).apply { filters=arrayOf(InputFilter.LengthFilter(limit));setSelectAllOnFocus(true) }
        val dialog=AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { save(name.text.toString());dialog.dismiss() }
            catch(e: Exception) { name.error=e.message ?: "Choose a different name.";name.requestFocus() }
        } }
        dialog.show()
    }
    private fun startNewVoice() {
        fun open(clear: Boolean) {
            if(clear) { store.reference.delete();store.prefs.edit().putString("voiceName","").putString("transcript","").putBoolean("cloneConsent",false).apply() }
            voiceEditor=true;voiceNotice="";scrollPositions["Voices"]=0;pageScroll?.scrollTo(0,0);draw()
        }
        val name=store.prefs.getString("voiceName","") ?: ""
        val words=store.prefs.getString("transcript","") ?: ""
        val saved=store.voices.list().any { it.name==name && it.transcript==words && store.reference.isFile && it.reference.readBytes().contentEquals(store.reference.readBytes()) }
        val draft=store.reference.isFile || name.isNotBlank() || words.isNotBlank()
        if(draft && !saved) AlertDialog.Builder(this).setTitle("Continue Your Draft?")
            .setMessage("You have an unfinished voice. Continue it, or start fresh with a new sample.")
            .setPositiveButton("Continue") { _,_ -> open(false) }
            .setNegativeButton("Start Fresh") { _,_ -> open(true) }.setNeutralButton("Cancel",null).show()
        else open(true)
    }
    private fun voiceMenu(anchor: View,saved: VoiceLibrary.Voice) {
        PopupMenu(this,anchor).apply {
            menu.add("Rename");menu.add("Delete Voice")
            setOnMenuItemClickListener { item ->
                when(item.title.toString()) {
                    "Rename" -> editName("Rename Voice",saved.name,60) { store.voices.rename(saved.id,it);draw() }
                    else -> AlertDialog.Builder(this@MainActivity).setTitle("Delete “${saved.name}”?")
                        .setMessage("This removes the voice from this phone. Narrations you already made keep their audio.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ -> safely {
                            store.voices.delete(saved.id)
                            if(store.prefs.getString("selectedVoiceId",null)==saved.id) store.prefs.edit().remove("selectedVoiceId").apply()
                            voiceEditor=false;voiceNotice="";draw()
                        } }.show()
                };true
            };show()
        }
    }
    private fun selectVoice(id: String) { store.prefs.edit().putString("selectedVoiceId",id).apply();draw() }
    private fun voiceScreen() {
        if(voiceEditor) { cloneScreen();return }
        val voices=store.voices.list()
        val selectedId=store.selectedVoice?.id
        val builtIn=BuiltInVoices.catalog(this)
        pageTitle("Voices","${builtIn.size} built-in · ${voices.size} yours · all on this phone",
            ui.iconButton(R.drawable.ic_plus,"Clone a new voice",tint=accent) { safely { startNewVoice() } })
        statusCard()
        if(voiceNotice.isNotBlank()) { ui.gap(content,8);content.addView(ui.subhead(voiceNotice,accent).apply { setPadding(dp(4),0,dp(4),0) }) }
        ui.sectionHeader(content,"My Voices")
        val mine=ui.group(content)
        voices.forEach { saved ->
            val selected=selectedId==saved.id
            val seconds=runCatching { AudioPeaks.durationMs(saved.reference)/1000.0 }.getOrDefault(0.0)
            val trailing=ui.row()
            trailing.addView(ui.iconButton(R.drawable.ic_play,"Listen to ${saved.name}",filled=false,tint=accent) { safely { play(saved.reference,saved.name) } })
            lateinit var more: ImageButton
            more=ui.iconButton(R.drawable.ic_more,"Options for ${saved.name}",filled=false,tint=muted) { voiceMenu(more,saved) }
            trailing.addView(more);trailing.addView(ui.check(selected))
            ui.groupRow(mine,saved.name,"Cloned · ${"%.1f".format(seconds)} s sample",leading=ui.avatar(saved.name),trailing=trailing,
                description="${saved.name}, your cloned voice${if(selected) ", selected" else ""}") { selectVoice(saved.id) }
        }
        ui.groupRow(mine,"Clone a Voice","Record or import 5–10 seconds of clear speech",titleColor=accent,
            leading=ImageView(this).apply { setImageResource(R.drawable.ic_plus);imageTintList=android.content.res.ColorStateList.valueOf(accent);background=ui.oval(Palette.raised);scaleType=ImageView.ScaleType.CENTER }) { safely { startNewVoice() } }
        if(builtIn.isEmpty()) return
        ui.sectionHeader(content,"Built-in")
        ui.segmented(content,listOf("All","Female","Male"),voiceFilter) { voiceFilter=it;draw() }
        ui.gap(content,10)
        val list=ui.group(content)
        builtIn.filter { voiceFilter=="All" || it.gender.equals(voiceFilter,true) }.forEach { entry ->
            val selected=selectedId==entry.voiceId
            val trailing=ui.row()
            trailing.addView(ui.iconButton(R.drawable.ic_play,"Listen to ${entry.name}",filled=false,tint=accent) { safely { play(store.findVoice(entry.voiceId)!!.reference,entry.name) } })
            trailing.addView(ui.check(selected))
            ui.groupRow(list,entry.name,entry.label,leading=ui.avatar(entry.name,genderHue(entry)),trailing=trailing,
                description="${entry.name}, ${entry.label}${if(selected) ", selected" else ""}") { selectVoice(entry.voiceId) }
        }
        if(builtIn.any { it.placeholder }) ui.sectionFooter(content,"Development placeholders. These clips are replaced with licensed recordings before release.",Palette.error)
        else ui.sectionFooter(content,"Every voice runs offline through the same speech model.")
    }
    private fun cloneScreen() {
        val voices=store.voices.list()
        content.addView(ui.button("‹ Voices","plain") { hideKeyboard();voiceEditor=false;voiceNotice="";draw() }.apply {
            setPadding(dp(4),0,dp(12),0);ui.setEnabled(this,!StudioJobs.busy)
        },LinearLayout.LayoutParams(-2,-2))
        pageTitle("New Voice","A clear 5–10 second recording works best. Nothing leaves your phone.")
        statusCard()
        val sample=if(store.reference.isFile) runCatching { AudioFiles.read(store.reference,20) }.getOrNull() else null
        ui.sectionHeader(content,"Sample")
        val card=ui.group(content)
        val inner=ui.column(card).apply { setPadding(dp(16),dp(16),dp(16),dp(16)) }
        inner.addView(WaveformView(this,accent,Palette.faint).apply { audio=sample?.samples },LinearLayout.LayoutParams(-1,dp(48)))
        ui.gap(inner,8)
        recordLabel=ui.footnote(if(sample==null) "Record yourself or import a recording" else "${"%.1f".format(sample.seconds)} s sample ready").apply { gravity=Gravity.CENTER }.also { inner.addView(it,LinearLayout.LayoutParams(-1,-2)) }
        ui.gap(inner,12)
        val actions=ui.row(inner)
        val recording=recorder?.running==true
        actions.addView(ui.button(if(recording) "Stop" else "Record") { safely { toggleRecording() } }.apply {
            setTextColor(if(recording) Palette.error else ink)
            setCompoundDrawablesRelativeWithIntrinsicBounds(if(recording) R.drawable.ic_stop else R.drawable.ic_tab_voices,0,0,0)
            compoundDrawableTintList=android.content.res.ColorStateList.valueOf(Palette.error);compoundDrawablePadding=dp(6)
        },LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(Space(this),LinearLayout.LayoutParams(dp(10),1))
        actions.addView(ui.button("Import") { safely { chooseFile(41,"audio/*") } }.apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_import,0,0,0)
            compoundDrawableTintList=android.content.res.ColorStateList.valueOf(ink);compoundDrawablePadding=dp(6)
            ui.setEnabled(this,!StudioJobs.busy && !recording)
        },LinearLayout.LayoutParams(0,-2,1f))
        if(sample!=null) {
            ui.groupRow(card,"Play Sample",trailing=ui.iconButton(R.drawable.ic_play,"Play sample",filled=false,tint=accent) { safely { play(store.reference) } }) { safely { play(store.reference) } }
            val importedSource=File(cacheDir,"last-voice-source.bin")
            if(importedSource.isFile) ui.groupRow(card,"Adjust Sample","Choose a different part of the recording",chevron=true) { safely {
                val copy=File.createTempFile("voice-adjust-",".bin",cacheDir)
                importedSource.copyTo(copy,true)
                try { showVoiceSamplePicker(copy,AudioImport.duration(copy)) } catch(e: Exception) { copy.delete();throw e }
            } }
        }
        ui.sectionHeader(content,"Name")
        val name=field(content,"e.g. My Narrator",store.prefs.getString("voiceName","") ?: "",key="voiceName")
        name.filters=arrayOf(InputFilter.LengthFilter(60));name.contentDescription="Voice name"
        val extract=ui.button("Extract","plain") {
            if(store.prefs.getString("transcript","").isNullOrBlank()) safely { transcribeReference() }
            else AlertDialog.Builder(this).setTitle("Extract Words Again?").setMessage("This replaces your edited transcript with a new offline recognition result.")
                .setNegativeButton("Keep Edits",null).setPositiveButton("Extract") { _,_ -> safely { transcribeReference() } }.show()
        }.apply { minHeight=dp(36);minimumHeight=dp(36);setPadding(dp(8),0,0,0);textSize=15f;ui.setEnabled(this,!StudioJobs.busy && sample!=null);contentDescription="Extract transcript from sample" }
        ui.sectionHeader(content,"Transcript",extract)
        val words=field(content,"The exact words spoken in your sample",store.prefs.getString("transcript","") ?: "",4,"transcript")
        words.filters=arrayOf(InputFilter.LengthFilter(600));words.contentDescription="Transcript of the sample"
        ui.sectionFooter(content,"Listen to the sample and fix any missed words. Exact words make the voice sound right.")
        ui.sectionHeader(content,"Permission")
        val consentBox=ui.group(content).apply { setPadding(dp(12),dp(10),dp(16),dp(10)) }
        val consent=CheckBox(this).apply {
            text="I have permission to use this voice, and I won't use it to impersonate or mislead anyone."
            textSize=15f;setTextColor(ink);buttonTintList=android.content.res.ColorStateList.valueOf(accent)
            isChecked=store.prefs.getBoolean("cloneConsent",false);isEnabled=!StudioJobs.busy
            setOnCheckedChangeListener { _,on -> store.prefs.edit().putBoolean("cloneConsent",on).apply();updateFooter() }
        }
        consentBox.addView(consent,LinearLayout.LayoutParams(-1,-2))
        footerReady={ store.reference.isFile && name.text.isNotBlank() && words.text.isNotBlank() && consent.isChecked && voices.none { it.name.equals(name.text.toString().trim(),true) } }
        footerMessage={ when {
            !store.reference.isFile -> "Record or import a sample."
            name.text.isBlank() -> "Give your voice a name."
            voices.any { it.name.equals(name.text.toString().trim(),true) } -> "That name is already used."
            words.text.isBlank() -> "Extract or type the sample’s words."
            !consent.isChecked -> "Confirm you have permission to use this voice."
            else -> "Saves “${name.text.toString().trim()}” on this phone."
        } }
        cloneVoiceAction={
            val title=name.text.toString().trim();val transcript=words.text.toString().trim()
            stopPlayback()
            startJob("Saving your voice…",true) { context ->
                val saved=StudioStore(context).voices.save(title,transcript,StudioStore(context).reference,consentAt=System.currentTimeMillis())
                StudioStore(context).prefs.edit().putString("selectedVoiceId",saved.id).putBoolean("cloneConsent",false).apply()
                runOnUiThread { voiceEditor=false;voiceNotice="${saved.name} is ready and selected.";scrollPositions["Voices"]=0;pageScroll?.scrollTo(0,0) }
                StudioJobs.update("${saved.name} saved on this phone")
            }
        }
    }
    private fun narrationMenu(anchor: View,dir: File) {
        val meta=store.metadata(dir)
        PopupMenu(this,anchor).apply {
            menu.add("Rename");if(File(dir,"full.wav").isFile) menu.add("Export WAV");menu.add("Report");menu.add("Delete")
            setOnMenuItemClickListener { item ->
                when(item.title.toString()) {
                    "Rename" -> editName("Rename Narration",meta.getString("title"),80) { store.renameProject(dir,it);draw() }
                    "Export WAV" -> safely { export(dir) }
                    "Report" -> safely {
                        val sections=meta.getJSONArray("sections")
                        val text=(0 until sections.length()).joinToString(" ") { sections.getString(it) }
                        val voiceId=meta.optString("voiceId")
                        ReportDialog.show(this@MainActivity,text,if(voiceId.startsWith(BuiltInVoices.PREFIX)) "built-in" else if(voiceId.isNotEmpty()) "cloned" else "model")
                    }
                    else -> AlertDialog.Builder(this@MainActivity).setTitle("Delete “${meta.getString("title")}”?")
                        .setMessage("Its audio is removed from this phone. Export a copy first if you want to keep it. Voices are unaffected.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ -> safely { store.deleteProject(dir);draw() } }.show()
                };true
            };show()
        }
    }
    private fun libraryScreen() {
        val history=store.history()
        pageTitle("Library")
        statusCard()
        if(history.isEmpty()) {
            val empty=ui.column(content).apply { gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(24),dp(80),dp(24),0) }
            empty.addView(ImageView(this).apply { setImageResource(R.drawable.ic_tab_library);imageTintList=android.content.res.ColorStateList.valueOf(Palette.faint) },LinearLayout.LayoutParams(dp(48),dp(48)))
            ui.gap(empty,14);empty.addView(ui.title("No Narrations Yet").apply { gravity=Gravity.CENTER })
            ui.gap(empty,6);empty.addView(ui.subhead("Narrations you create appear here, ready to play, edit or export.").apply { gravity=Gravity.CENTER })
            ui.gap(empty,20);empty.addView(ui.button("Create Narration","primary") { navigate("Create") },LinearLayout.LayoutParams(-2,-2))
            return
        }
        ui.gap(content,8)
        val searchBox=ui.row(content).apply { background=ui.rounded(Palette.raised,10);setPadding(dp(10),0,dp(10),0) }
        searchBox.addView(ImageView(this).apply { setImageResource(R.drawable.ic_search);imageTintList=android.content.res.ColorStateList.valueOf(muted) },LinearLayout.LayoutParams(dp(20),dp(20)))
        val search=EditText(this).apply {
            hint="Search";setText(libraryQuery);textSize=17f;setTextColor(ink);setHintTextColor(muted);background=null
            setPadding(dp(8),dp(10),dp(4),dp(10));isSingleLine=true;contentDescription="Search narrations or voices"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
        }
        searchBox.addView(search,LinearLayout.LayoutParams(0,-2,1f))
        val results=ui.column(content)
        fun render() {
            results.removeAllViews()
            val query=libraryQuery.trim()
            val matches=history.mapNotNull { dir -> runCatching { dir to store.metadata(dir) }.getOrNull() }.filter { (_,meta) ->
                query.isEmpty() || (meta.optString("title")+" "+meta.optString("voiceName")+" "+meta.optJSONArray("sections").toString()).contains(query,true)
            }
            ui.sectionHeader(results,if(query.isEmpty()) "${matches.size} ${if(matches.size==1) "Narration" else "Narrations"}" else "Results")
            if(matches.isEmpty()) {
                results.addView(ui.subhead("No narrations match “$query”.").apply { setPadding(dp(16),0,dp(16),0) })
                results.addView(ui.button("Clear Search","plain") { search.setText("") },LinearLayout.LayoutParams(-2,-2));return
            }
            val list=ui.group(results)
            matches.forEach { (dir,meta) ->
                val duration=runCatching { AudioPeaks.durationMs(File(dir,"full.wav")) }.getOrDefault(0)
                val totalChunks=meta.getJSONArray("sections").length()
                val readyChunks=(0 until totalChunks).count { File(dir,"section-$it.wav").isFile }
                val state=if(File(dir,"full.wav").isFile) "%d:%02d".format(duration/60000,duration/1000%60) else "$readyChunks of $totalChunks chunks"
                val voice=meta.optString("voiceName",if(meta.optBoolean("voice")) "Cloned voice" else "Model voice")
                val date=java.text.SimpleDateFormat("d MMM",java.util.Locale.getDefault()).format(java.util.Date(meta.optLong("created",dir.lastModified())))
                lateinit var more: ImageButton
                more=ui.iconButton(R.drawable.ic_more,"Options for ${meta.getString("title")}",filled=false,tint=muted) { narrationMenu(more,dir) }
                ui.groupRow(list,meta.getString("title"),"$state · $voice · $date",trailing=more,chevron=true,
                    description="${meta.getString("title")}, $state, $voice, $date") { safely { openStudio(dir) } }
                    .setOnLongClickListener { narrationMenu(it,dir);true }
            }
        }
        search.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { libraryQuery=s.toString();render() }
            override fun afterTextChanged(s: Editable?) {}
        });render()
    }
    private fun manageSpeechModel() {
        AlertDialog.Builder(this).setTitle("Speech Model")
            .setItems(arrayOf("Import Model File","Download Again")) { _,which ->
                if(which==0) safely { chooseFile(42,"*/*") }
                else AlertDialog.Builder(this).setTitle("Download Again?")
                    .setMessage("This uses about 500 MB of data. Your voices and narrations stay on this phone.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Download") { _,_ -> safely { startJob("Connecting to model host…",true) { StudioJobs.download(it) } } }.show()
            }.setNegativeButton("Close",null).show()
    }
    private fun showLicenses() {
        val text=ui.text(assets.open("THIRD-PARTY-NOTICES.txt").bufferedReader().use { it.readText() },12f)
        text.setPadding(dp(20),dp(16),dp(20),dp(16))
        AlertDialog.Builder(this).setTitle("Open-Source Licenses").setView(ScrollView(this).apply { addView(text) }).setPositiveButton("Close",null).show()
    }
    private fun setupScreen() {
        pageTitle("Settings")
        statusCard()
        ui.sectionHeader(content,"Appearance")
        val appearance=ui.group(content)
        val currentTheme=store.prefs.getString("appearance","light") ?: "light"
        val themeLabel=when(currentTheme) { "dark" -> "Dark"; "system" -> "Follow System"; else -> "Light" }
        ui.groupRow(appearance,"Theme",value=themeLabel,chevron=true) {
            val options=arrayOf("Light","Dark","Follow System")
            val keys=listOf("light","dark","system")
            AlertDialog.Builder(this).setTitle("Theme")
                .setSingleChoiceItems(options,keys.indexOf(currentTheme)) { dialog,index ->
                    Palette.setAppearance(this,keys[index])
                    dialog.dismiss()
                    draw()
                }.setNegativeButton("Cancel",null).show()
        }
        ui.sectionFooter(content,"Light mode provides clean, high-contrast readability across all studio screens.")
        ui.sectionHeader(content,"Speech Model")
        val model=ui.group(content)
        val modelStatus=if(store.voxModelInstalled) "VoxCPM Installed" else "Offline Engine Ready"
        ui.groupRow(model,"Status",trailing=ui.text(modelStatus,17f,Palette.success))
        if(store.voxModelInstalled) ui.groupRow(model,"Manage Model Files",chevron=true) { manageSpeechModel() }
        else {
            ui.groupRow(model,"Download VoxCPM Model","About 500 MB · optional neural model",titleColor=accent) { safely { startJob("Connecting to model host…",true) { StudioJobs.download(it) } } }
            ui.groupRow(model,"Import Model File",titleColor=accent) { safely { chooseFile(42,"*/*") } }
        }
        ui.sectionFooter(content,"Voice cloning, speech synthesis and narration run entirely offline on this phone.")
        ui.sectionHeader(content,"Defaults")
        ui.groupRow(ui.group(content),"Quality",value=qualityName(store.prefs.getInt("steps",6)),chevron=true) { chooseQuality { draw() } }
        ui.sectionFooter(content,"Fast Preview is for drafts. Choose Best Quality if words sound blurred.")
        ui.sectionHeader(content,"Performance")
        val performance=ui.group(content)
        val threads=store.prefs.getInt("threads",NativeCpuEngine.defaultThreads)
        ui.groupRow(performance,"CPU Threads",value=threads.toString(),chevron=true) {
            val values=listOf(2,4)
            AlertDialog.Builder(this).setTitle("CPU Threads").setSingleChoiceItems(values.map { "$it threads${if(it==NativeCpuEngine.defaultThreads) " (recommended)" else ""}" }.toTypedArray(),values.indexOf(threads)) { dialog,index ->
                store.prefs.edit().putInt("threads",values[index]).apply();dialog.dismiss();draw()
            }.setNegativeButton("Cancel",null).show()
        }
        if(NativeEngine.gpuPackaged(this)) {
            val gpu=store.prefs.getBoolean("gpuExperimental",false)
            val toggle=Switch(this).apply {
                isChecked=gpu;isEnabled=!StudioJobs.busy;contentDescription="Experimental GPU"
                thumbTintList=android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                trackTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Palette.success,Palette.raised))
                setOnCheckedChangeListener { _,on -> store.prefs.edit().putBoolean("gpuExperimental",on).apply() }
            }
            ui.groupRow(performance,"Experimental GPU",trailing=toggle)
        }
        val gpuNote=if(NativeEngine.gpuPackaged(this)) " GPU speed-up is not verified on any phone; if it fails, Hush Studio returns to CPU and turns it off." else ""
        ui.sectionFooter(content,(if(NativeCpuEngine.fast) "Two threads suit most phones. Four can be slightly faster but uses the slower cores, adding heat and battery drain." else "This phone uses the compatible engine, which runs best with four threads.")+gpuNote)
        store.prefs.getString("gpuDisabledReason",null)?.takeIf { NativeEngine.gpuPackaged(this) && !store.prefs.getBoolean("gpuExperimental",false) }?.let { ui.sectionFooter(content,"Last GPU attempt: $it") }
        ui.sectionHeader(content,"This Phone")
        val device=ui.group(content)
        val info=ActivityManager.MemoryInfo();(getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        ui.groupRow(device,"Model",value="${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
        ui.groupRow(device,"Memory",value="${"%.1f".format(info.totalMem/1073741824.0)} GB")
        ui.groupRow(device,"Engine",value=if(NativeCpuEngine.fast) "Accelerated CPU" else if(NativeCpuEngine.available) "Compatible CPU" else "Offline Speech Engine")
        ui.groupRow(device,"Speech Model",value=if(store.voxModelInstalled) "VoxCPM 0.5B · Q4" else "Offline Synthesis")
        ui.sectionHeader(content,"Privacy")
        val privacy=ui.group(content).apply { setPadding(dp(16),dp(14),dp(16),dp(14)) }
        privacy.addView(ui.subhead("Samples, transcripts and narrations stay on this phone unless you export them. The internet is used to download the speech model and, only if you choose to report a narration, to send that report. Uninstalling removes your voices and narrations, so export anything you want to keep.",ink))
        ui.sectionHeader(content,"About")
        val about=ui.group(content)
        ui.groupRow(about,"Version",value=BuildConfig.VERSION_NAME)
        ui.groupRow(about,"Privacy Policy",chevron=true) { safely { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(PRIVACY_POLICY_URL))) } }
        ui.groupRow(about,"Open-Source Licenses",chevron=true) { safely { showLicenses() } }
        ui.sectionFooter(content,"Built with OpenBMB VoxCPM, the VoxCPM.cpp runtime and Vosk offline speech recognition.")
    }
    private fun startJob(label: String, transfer: Boolean = false, task: (Context) -> Unit) {
        require(recorder?.active != true) { "Stop recording before starting another task." }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),52)
        StudioJobs.submit(this,label,transfer,task);observedBusy=true;scrollPositions[tab]=0;pageScroll?.scrollTo(0,0);draw()
    }
    private fun chooseFile(code: Int, mime: String) {
        require(recorder?.active != true) { "Stop recording first." }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type=mime; addCategory(Intent.CATEGORY_OPENABLE) },code)
    }
    private fun showVoiceSamplePicker(input: File, seconds: Double) {
        val dialog=SampleAdjustmentDialog(this,input,seconds,
            store.prefs.getFloat("sampleStart",0f).toDouble(),
            store.prefs.getFloat("sampleLength",5f).toDouble()) { at,length -> safely {
                val chosen=File.createTempFile("voice-chosen-",".bin",cacheDir)
                input.copyTo(chosen,true)
                try { startJob("Updating voice sample…",true) { context ->
                    val target=File(context.filesDir,"voice/reference.new.wav")
                    try {
                        target.parentFile!!.mkdirs()
                        AudioImport.convert(chosen,target,{ StudioJobs.checkStop() },startSeconds=at,clipSeconds=length)
                        StudioJobs.checkStop()
                        check(target.renameTo(StudioStore(context).reference)) { "Could not save voice sample." }
                        StudioStore(context).prefs.edit().putString("transcript","").putBoolean("useVoice",true)
                            .putFloat("sampleStart",at.toFloat()).putFloat("sampleLength",length.toFloat()).apply()
                        extractSavedTranscript(context)
                    } finally { chosen.delete();target.delete() }
                } } catch(e: Exception) { chosen.delete();throw e }
            } }
        sampleAdjustment=dialog
        dialog.setOnDismissListener { sampleAdjustment=null;input.delete() }
        dialog.show()
    }
    private fun export(dir: File) {
        require(!StudioJobs.busy) { "Wait for the current task before exporting." }
        require(File(dir,"full.wav").isFile) { "Generate all chunks before exporting." }
        exportProject=dir
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="audio/wav"
            putExtra(Intent.EXTRA_TITLE,store.metadata(dir).getString("title").replace(Regex("[^a-zA-Z0-9 _-]"),"").take(60).ifBlank { "Narration" }+".wav")
        },43)
    }
    @Deprecated("Platform activity results are sufficient for this dependency-light prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        when(requestCode) {
            41 -> safely { startJob("Detecting and converting audio…",true) { context ->
                val input=File.createTempFile("audio-source-", ".bin", context.cacheDir)
                try {
                    copyBounded(context,uri,input,100L*1024*1024)
                    val seconds=AudioImport.duration(input)
                    val candidate=File.createTempFile("voice-scan-",".wav",context.cacheDir)
                    val selected=File.createTempFile("voice-auto-",".wav",context.cacheDir)
                    var bestScore=Double.NEGATIVE_INFINITY
                    var selectedStart=0.0
                    var selectedLength=5.0
                    try {
                        var offset=0.0
                        while(offset<minOf(seconds,60.0) && seconds-offset>=3) {
                            StudioJobs.update("Finding a clear voice sample…")
                            AudioImport.convert(input,candidate,{ StudioJobs.checkStop() },startSeconds=offset,clipSeconds=minOf(20.0,seconds-offset))
                            val wave=AudioFiles.read(candidate)
                            val choice=runCatching { AutoVoiceSample.choose(wave) }.getOrNull()
                            if(choice!=null && choice.score>bestScore) {
                                AudioFiles.reference(candidate,selected,choice.start,choice.duration)
                                bestScore=choice.score
                                selectedStart=offset+choice.start;selectedLength=choice.duration
                            }
                            if(bestScore>=.7) break
                            offset+=20.0
                        }
                        require(selected.length()>44) { "Couldn’t find a clear sample in the first minute. Try a speech recording or record a sample." }
                        StudioJobs.checkStop()
                        val store=StudioStore(context)
                        selected.copyTo(File(store.reference.parentFile!!.also { it.mkdirs() },"reference.new.wav"),true)
                        check(File(store.reference.parentFile,"reference.new.wav").renameTo(store.reference))
                        store.prefs.edit().putString("transcript","").putBoolean("useVoice",true).putFloat("sampleStart",selectedStart.toFloat()).putFloat("sampleLength",selectedLength.toFloat()).apply()
                        input.copyTo(File(context.cacheDir,"last-voice-source.bin"),true)
                        extractSavedTranscript(context)
                        StudioJobs.update("Sample selected and words extracted · listen and review before cloning")
                    } finally { candidate.delete();selected.delete();input.delete() }
                } catch(e: Exception) { input.delete();throw e }
            } }
            42 -> safely { startJob("Importing and verifying model…",true) { context ->
                val s=StudioStore(context); s.model.parentFile!!.mkdirs(); val temp=File(s.model.parentFile,"import.part")
                try {
                    require(s.model.parentFile!!.usableSpace>StudioStore.MODEL_BYTES+100_000_000) { "Free at least 650 MB of storage first." }
                    copyBounded(context,uri,temp,StudioStore.MODEL_BYTES)
                    StudioJobs.checkStop(); require(StudioStore.verify(temp)) { "Choose the exact voxcpm-0.5b-q4_k.gguf model from the documented source." }
                    check(temp.renameTo(s.model)); s.prefs.edit().putBoolean("modelVerified",true).apply(); StudioJobs.update("Model installed · ready for offline generation")
                } finally { temp.delete() }
            } }
            43 -> {
                val dir=exportProject ?: return
                io.execute {
                    try {
                        val source=File(dir,"full.wav")
                        val title=runCatching { store.metadata(dir).getString("title") }.getOrDefault("Narration")
                        contentResolver.openOutputStream(uri,"wt")!!.use { output -> AudioFiles.writeLabeled(source,output,AudioFiles.aiLabel(title)) }
                        runOnUiThread { toast("WAV exported, labelled as AI-generated") }
                    } catch(e: Exception) { StudioJobs.reportFailure(e.message ?: "Export failed. Please try another location.");runOnUiThread { draw() } }
                }
            }
        }
    }
    private fun copyBounded(context: Context, uri: Uri, output: File, limit: Long) {
        context.contentResolver.openInputStream(uri)!!.buffered().use { input -> output.outputStream().buffered().use { dest ->
            val buffer=ByteArray(256*1024); var total=0L
            while(true) { StudioJobs.checkStop(); val n=input.read(buffer); if(n<0) break; total+=n; require(total<=limit) { "Selected file is too large." }; dest.write(buffer,0,n) }
        } }
    }
    private fun openStudio(dir: File,autoGenerate: Boolean=false) {
        stopPlayback();hideKeyboard();chunkStudio?.dismiss()
        val meta=store.metadata(dir)
        if(!meta.optBoolean("studio")) { meta.put("studio",true);store.saveMetadata(dir,meta) }
        store.prefs.edit().putString("studioProjectId",dir.name).apply()
        chunkStudio=ChunkStudioDialog(this,dir,{ title,task -> startJob(title,task=task) },{ safely { export(dir) } },autoGenerate).also { dialog ->
            dialog.setOnDismissListener { chunkStudio=null;if(!isFinishing && !isDestroyed)draw() };dialog.show()
        }
    }
    private fun showProject(dir: File) {
        require(!StudioJobs.busy && recorder?.active!=true) { "Wait for the current task or recording." }
        stopPlayback()
        val meta=store.metadata(dir);val parts=meta.getJSONArray("sections")
        playerDialog=StudioPlayerDialog(this,meta.getString("title"),
            meta.optString("voiceName",if(meta.getBoolean("voice")) "Cloned voice" else "Model voice"),
            File(dir,"full.wav"),(0 until parts.length()).map { parts.getString(it) to File(dir,"section-$it.wav") },
            exportAudio={ safely { export(dir) } },
            regenerate={ index -> safely { startJob("Preparing section…") { StudioJobs.regenerate(it,dir,index) } } },
            closed={ playerDialog=null }).also { it.show() }
    }
    private fun play(file: File,title: String?=null) {
        require(!StudioJobs.busy && recorder?.active != true) { "Wait for the current task or recording." }
        stopPlayback()
        val saved=store.voices.list().find { it.reference==file }
        playerDialog=StudioPlayerDialog(this,title ?: saved?.name ?: "Voice sample","Reference audio",file,
            closed={ playerDialog=null }).also { it.show();it.startPreview() }
    }
    private fun stopPlayback() { playerDialog?.dismiss();playerDialog=null }
    private fun transcribeReference() {
        stopPlayback()
        require(store.reference.isFile) { "Record or import a voice sample first." }
        startJob("Extracting transcript offline…",true) { context -> extractSavedTranscript(context) }
    }
    private fun extractSavedTranscript(context: Context) {
        val saved=StudioStore(context)
        try {
            val text=SpeechTranscriber.transcribe(context,saved.reference)
            StudioJobs.checkStop()
            saved.prefs.edit().putString("transcript",text).apply()
            StudioJobs.update("Transcript extracted · listen and correct any missed words before cloning")
        } catch(e: java.util.concurrent.CancellationException) { throw e }
        catch(e: Exception) { StudioJobs.reportFailure("Voice sample kept · ${e.message ?: "Recognition failed. Try again or type its words."}") }
    }
    private fun toggleRecording() {
        if(recorder?.running==true) { recorder?.stop(); return }
        require(!StudioJobs.busy) { "Wait for the current task." }
        require(recorder?.active != true) { "Finishing the previous recording. Please wait." }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),51); return }
        stopPlayback()
        recorder=VoiceRecorder(this) { result -> runOnUiThread {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if(!isDestroyed) {
                result.exceptionOrNull()?.let { toast(it.message ?: "Recording failed") }
                draw()
                if(result.isSuccess && hasWindowFocus()) safely { transcribeReference() }
            }
        } }.also { it.start() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); draw()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode!=51) return
        if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) { safely { toggleRecording() };return }
        // Denied: explain, and offer the paths that still work. "Don't ask again" needs app settings.
        val blocked=!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        AlertDialog.Builder(this).setTitle("Microphone not allowed")
            .setMessage(if(blocked) "Recording is turned off for Hush Studio. Allow the microphone in app settings, or import an existing recording instead."
                else "Hush Studio needs the microphone only while you record a voice sample. You can import an existing recording instead.")
            .setNegativeButton("Import audio") { _,_ -> safely { chooseFile(41,"audio/*") } }
            .setPositiveButton(if(blocked) "Open app settings" else "Close") { _,_ ->
                if(blocked) safely { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",packageName,null))) }
            }.show()
    }
    private fun safely(action: () -> Unit) { try { action() } catch(e: Exception) { AlertDialog.Builder(this).setTitle("Unable to continue").setMessage(e.message ?: "Please try again.").setPositiveButton("Close",null).show() } }
    private fun toast(message: String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
}

private class WaveformView(context: Context, private val tint: Int, private val muted: Int) : View(context) {
    var audio: ShortArray? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth=5f; strokeCap=Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        val samples=audio
        if(samples==null) { paint.color=muted; paint.strokeWidth=2f; canvas.drawLine(0f,height/2f,width.toFloat(),height/2f,paint); return }
        paint.color=tint
        val bars=64; val stride=(samples.size/bars).coerceAtLeast(1)
        for(i in 0 until bars) {
            var peak=0
            for(j in i*stride until min((i+1)*stride,samples.size)) peak=max(peak,abs(samples[j].toInt()))
            val h=max(3f,peak/32768f*height*.9f)
            val x=(i+.5f)*width/bars
            canvas.drawLine(x,(height-h)/2,x,(height+h)/2,paint)
        }
    }
}

private class VoiceRecorder(private val context: Context, private val finish: (Result<Unit>) -> Unit) {
    @Volatile var running=false; private set
    @Volatile var active=false; private set
    @Volatile var seconds=0; private set
    fun stop() { running=false }
    fun start() {
        running=true; active=true
        Thread {
            var input: AudioRecord?=null
            val store=StudioStore(context); val temp=File(context.cacheDir,"recorded.wav")
            val target=File(context.filesDir,"voice/recording.new.wav")
            val result=runCatching {
                val size=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                require(size>0) { "Microphone does not support 16 kHz recording." }
                require(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { "Microphone permission is required." }
                input=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,max(size,4096))
                check(input!!.state==AudioRecord.STATE_INITIALIZED) { "Could not open microphone." }
                val all=ShortArray(320000); val buffer=ShortArray(1024); var count=0
                input!!.startRecording()
                while(running && count<all.size) {
                    val n=input!!.read(buffer,0,min(buffer.size,all.size-count))
                    check(n>0) { "Microphone stopped unexpectedly." }
                    buffer.copyInto(all,count,0,n); count+=n; seconds=count/16000
                }
                input!!.stop()
                require(count>=48000) { "Record at least 3 seconds. Previous reference kept." }
                AudioFiles.write(temp,all.copyOf(count)); target.parentFile!!.mkdirs(); AudioFiles.reference(temp,target)
                check(target.renameTo(store.reference))
                store.prefs.edit().putString("transcript","").putBoolean("useVoice",true).apply()
            }
            input?.release(); running=false; active=false; temp.delete(); target.delete(); finish(result)
        }.apply { name="VoiceRecorder"; start() }
    }
}
