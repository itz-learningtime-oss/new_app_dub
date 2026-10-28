package com.voxstudio.app

/** Stage counts are discrete milestones, never an estimate of percent time remaining. */
class GenerationProgress(private val clock: () -> Long) {
    data class Snapshot(val section: Int, val total: Int, val completed: Int, val stage: String,
        val stageNumber: Int, val frames: Int, val audioSeconds: Double, val elapsedMs: Long,
        val stageElapsedMs: Long, val stageTimes: Map<String,Long>) {
        val elapsed get() = duration(elapsedMs)
        val stageElapsed get() = duration(stageElapsedMs)
        companion object {
            fun duration(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0)/60000,ms.coerceAtLeast(0)/1000%60)
        }
    }
    private var started = 0L; private var ended: Long? = null; private var section = 0
    private var total = 0; private var completed = 0; private var phase = ""
    private var phaseAt = 0L; private var frames = 0; private var audio = 0.0
    private val times = linkedMapOf<String,Long>()
    @Synchronized fun reset() { started=clock(); ended=null; section=0;total=0;completed=0;phase="";phaseAt=started;frames=0;audio=0.0;times.clear() }
    @Synchronized fun startProject(count: Int) { require(count>0); reset();total=count }
    @Synchronized fun startSection(number: Int) { section=number;phase="";phaseAt=clock();frames=0;audio=0.0;times.clear() }
    @Synchronized fun stage(name: String, current: Int, audioSeconds: Double) {
        if (name!=phase) { recordStage();phase=name;phaseAt=clock() }
        if(current>0) { frames=current;audio=audioSeconds.coerceAtLeast(0.0) }
    }
    private fun recordStage() { if(phase.isNotEmpty()) times[phase]=(times[phase]?:0)+(clock()-phaseAt).coerceAtLeast(0) }
    @Synchronized fun sectionDone() { recordStage();phaseAt=clock();completed++ }
    @Synchronized fun finish() { if(ended==null) ended=clock() }
    @Synchronized fun snapshot(): Snapshot {
        val now=ended?:clock()
        val number=when(phase) { "Loading model"->1; "Encoding voice reference","Preparing script"->2; "Preparing speech"->3; "Generating speech"->4; "Decoding waveform"->5; else->0 }
        val durations=times.toMutableMap()
        if(phase.isNotEmpty()) durations[phase]=(durations[phase]?:0)+(now-phaseAt).coerceAtLeast(0)
        return Snapshot(section,total,completed,phase,number,frames,audio,(now-started).coerceAtLeast(0),
            (now-phaseAt).coerceAtLeast(0),durations)
    }
}
