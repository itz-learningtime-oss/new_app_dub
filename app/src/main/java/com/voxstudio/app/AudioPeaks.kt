package com.voxstudio.app

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import kotlin.math.abs

/** Stream app-native PCM into a bounded waveform; long narration never fills a sample array. */
object AudioPeaks {
    data class Info(val offset: Long, val bytes: Long, val rate: Int) {
        val durationMs get()=(bytes*1000/(rate*2)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    private fun info(f: RandomAccessFile): Info {
        fun tag()=ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
        fun u16()=f.readUnsignedByte() or (f.readUnsignedByte() shl 8)
        fun u32()=u16().toLong() or (u16().toLong() shl 16)
        require(f.length()>=44 && tag()=="RIFF") { "Invalid audio file." }
        val end=u32()+8;require(end<=f.length() && tag()=="WAVE")
        var rate=0;var offset=-1L;var bytes=0L
        while(f.filePointer+8<=end) {
            val id=tag();val size=u32();val start=f.filePointer
            require(start+size<=end)
            if(id=="fmt ") {
                require(size>=16 && u16()==1 && u16()==1)
                rate=u32().toInt();u32();u16();require(u16()==16 && rate in 8000..96000)
            }
            if(id=="data" && offset<0) { offset=start;bytes=size }
            f.seek(start+size+size%2)
        }
        require(offset>=0 && bytes>0 && bytes%2==0L && rate>0)
        return Info(offset,bytes,rate)
    }
    fun durationMs(file: File)=RandomAccessFile(file,"r").use { info(it).durationMs }
    fun read(file: File,bars: Int=96): FloatArray=RandomAccessFile(file,"r").use { f ->
        require(bars in 1..512)
        val info=info(f);val peaks=IntArray(bars);val samples=info.bytes/2
        f.seek(info.offset);val buffer=ByteArray(65536);var at=0L
        while(at<samples) {
            if(Thread.currentThread().isInterrupted) throw CancellationException()
            val count=minOf(buffer.size.toLong()/2,samples-at).toInt()
            f.readFully(buffer,0,count*2)
            for(i in 0 until count) {
                val value=((buffer[i*2].toInt() and 255) or (buffer[i*2+1].toInt() shl 8)).toShort().toInt()
                val bin=((at+i)*bars/samples).toInt().coerceAtMost(bars-1)
                peaks[bin]=maxOf(peaks[bin],abs(value))
            }
            at+=count
        }
        val max=peaks.maxOrNull()?.coerceAtLeast(1) ?: 1
        FloatArray(bars) { peaks[it].toFloat()/max }
    }
}
