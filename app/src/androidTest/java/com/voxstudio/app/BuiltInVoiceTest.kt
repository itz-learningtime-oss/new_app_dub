package com.voxstudio.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Built-in voices must steer the model: the same sentence generated with a female and a male
 * built-in voice should differ in pitch the way their reference clips do. Pitch is a coarse
 * objective check, not a measure of speaker similarity or quality.
 */
class BuiltInVoiceTest {
    /** Median autocorrelation pitch (Hz) over voiced 40 ms frames, 70-400 Hz. */
    private fun medianPitch(file: File): Double {
        val wave=AudioFiles.read(file,60)
        val rate=wave.rate;val frame=rate*40/1000
        val samples=wave.samples.map { it/32768.0 }
        val pitches=mutableListOf<Double>()
        var start=0
        while(start+frame<=samples.size) {
            val x=samples.subList(start,start+frame)
            val energy=x.sumOf { it*it }/frame
            if(energy>1e-4) {
                var best=0.0;var bestLag=0
                for(lag in rate/400..rate/70) {
                    var sum=0.0;for(i in 0 until frame-lag) sum+=x[i]*x[i+lag]
                    if(sum>best) { best=sum;bestLag=lag }
                }
                if(bestLag>0 && best>.3*energy*frame) pitches.add(rate.toDouble()/bestLag)
            }
            start+=frame
        }
        assertTrue("Too few voiced frames in ${file.name}",pitches.size>=10)
        return pitches.sorted()[pitches.size/2]
    }
    private val sentence="The river was calm this morning, and the boats drifted slowly past the old stone bridge."
    private val keyWords=listOf("river","calm","morning","boats","drifted","slowly","stone","bridge")
    /** Generates the sentence through the chunk pipeline; returns (output pitch Hz, key words heard). */
    private fun generate(voiceId: String?,seed: Int): Pair<Double,Int> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudioStore(context)
        val dir=ChunkProjects.create(context,sentence,"Built-in voice test",voiceId,6)
        try {
            NativeEngine.setBenchmarkSeedForTesting(seed)
            StudioJobs.generateChunks(context,dir,listOf(0),true)
            val output=File(dir,"section-0.wav")
            val heard=SpeechTranscriber.transcribe(context,output).lowercase()
            val found=keyWords.count { heard.contains(it) }
            val pitch=medianPitch(output)
            android.util.Log.i("VoxVoiceTest","voice=${voiceId ?: "model"} seed=$seed output_hz=${"%.0f".format(pitch)} seconds=${"%.2f".format(AudioFiles.read(output).seconds)} key_words=$found/${keyWords.size} asr=\"$heard\"")
            return pitch to found
        } finally { store.deleteProject(dir) }
    }
    /**
     * Run with -e voiceSeeds 11,22,33 to measure reliability across seeds. Asserts that the voices
     * steer pitch and that most takes are intelligible; individual takes can still fail.
     */
    @Test fun femaleAndMaleBuiltInVoicesProduceDistinctIntelligibleSpeech() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudioStore(context);assertTrue("Install the model first",store.ready)
        val seeds=(InstrumentationRegistry.getArguments().getString("voiceSeeds") ?: "4242").split(",").map { it.trim().toInt() }
        val includeModel=InstrumentationRegistry.getArguments().getString("voiceControl")=="true"
        val previousProject=store.prefs.getString("studioProjectId",null)
        val runs=mutableMapOf<String,List<Pair<Double,Int>>>()
        NativeEngine.backendForTesting="cpu"
        try {
            for(id in listOf("aria","ethan")) {
                val voiceId=BuiltInVoices.PREFIX+id
                assertNotNull("Built-in clip $id is not packaged",store.findVoice(voiceId))
                android.util.Log.i("VoxVoiceTest","voice=$voiceId reference_hz=${"%.0f".format(medianPitch(store.findVoice(voiceId)!!.reference))}")
                runs[id]=seeds.map { generate(voiceId,it) }
            }
            if(includeModel) runs["model"]=seeds.map { generate(null,it) }
        } finally {
            NativeEngine.backendForTesting="auto";NativeEngine.setBenchmarkSeedForTesting(-1);NativeEngine.releaseForTesting()
            store.prefs.edit().putString("studioProjectId",previousProject).apply()
        }
        val female=runs.getValue("aria").map { it.first }.sorted()[seeds.size/2]
        val male=runs.getValue("ethan").map { it.first }.sorted()[seeds.size/2]
        assertTrue("Female output ($female Hz) should be clearly higher than male output ($male Hz)",female>male*1.3)
        val takes=runs.filterKeys { it!="model" }.values.flatten()
        val intelligible=takes.count { it.second>=5 }
        android.util.Log.i("VoxVoiceTest","summary intelligible_takes=$intelligible/${takes.size}")
        assertTrue("Most built-in voice takes should be intelligible ($intelligible/${takes.size})",intelligible*2>takes.size)
    }
}
