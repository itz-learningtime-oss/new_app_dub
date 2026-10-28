package com.voxstudio.app
import org.junit.Test
import org.junit.Assert.*
class GenerationProgressTest {
    @Test fun elapsedContinuesDuringLongPreparationAndStopsAtFinish() {
        var clock=1000L
        val p=GenerationProgress { clock }
        p.startProject(2);p.startSection(1);p.stage("Preparing speech",0,0.0)
        clock+=65000
        assertEquals("1:05",p.snapshot().elapsed)
        assertEquals(3,p.snapshot().stageNumber)
        assertEquals(0,p.snapshot().completed)
        p.finish();clock+=10000
        assertEquals("1:05",p.snapshot().elapsed)
    }
    @Test fun audioLengthAndCompletedSectionsReflectCallbacksRatherThanSafetyLimit() {
        var clock=0L;val p=GenerationProgress { clock }
        p.startProject(2);p.startSection(1);p.stage("Preparing speech",0,0.0)
        clock=3000;p.stage("Generating speech",12,.96)
        val s=p.snapshot();assertEquals(12,s.frames);assertEquals(.96,s.audioSeconds,.001)
        assertEquals(3000L,s.stageTimes["Preparing speech"])
        p.sectionDone();p.startSection(2)
        assertEquals(1,p.snapshot().completed);assertEquals(0,p.snapshot().frames)
        assertEquals(0.0,p.snapshot().audioSeconds,.001)
    }
}
