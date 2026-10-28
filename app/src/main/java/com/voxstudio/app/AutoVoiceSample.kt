package com.voxstudio.app

import kotlin.math.*

/** Energy-based sample ranking, not speaker identification or music detection. */
object AutoVoiceSample {
    data class Choice(val start: Double,val duration: Double,val score: Double)
    fun choose(wave: AudioFiles.Wave): Choice {
        require(wave.channels==1 && wave.rate==16000)
        val block=320
        val energy=DoubleArray((wave.samples.size+block-1)/block) { frame ->
            val from=frame*block;val to=min(from+block,wave.samples.size)
            sqrt((from until to).sumOf { wave.samples[it].toDouble().pow(2) }/(to-from))
        }
        val peak=energy.maxOrNull() ?: 0.0
        require(peak>50) { "No clear speech found. Try another recording or record a sample." }
        val threshold=max(45.0,peak*.07)
        val frames=min(250,energy.size)
        require(frames>=150) { "Use at least 3 seconds of audio." }
        var best=Choice(0.0,frames*.02,Double.NEGATIVE_INFINITY)
        for(start in 0..energy.size-frames step 5) {
            val end=start+frames
            val active=(start until end).count { energy[it]>threshold }.toDouble()/frames
            val boundary=(energy[start]+energy[end-1])/(2*peak)
            val clipping=(start*block until min(end*block,wave.samples.size)).count { abs(wave.samples[it].toInt())>=32760 }.toDouble()/(frames*block)
            val score=active-boundary*.18-clipping*4
            if(score>best.score) best=Choice(start*.02,min(frames*.02,wave.seconds-start*.02),score)
        }
        require(best.score>.25) { "No clear speech found. Try a recording with less silence." }
        return best
    }
}
