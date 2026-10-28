package com.voxstudio.app

import android.content.Context
import android.media.*
import java.io.File

/** Activity-scoped playback; never starts again automatically after losing audio focus. */
class AudioPlayback(context: Context, private val changed: () -> Unit) {
    private val manager=context.getSystemService(AudioManager::class.java)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { if(it<0) pause() }.build()
    private var media: MediaPlayer?=null
    private var pendingPlay=false
    private var savedPosition=0
    var file: File?=null; private set
    var duration=0; private set
    var loading=false; private set
    var error: String?=null; private set
    var speed=1f; private set
    val playing: Boolean get()=runCatching { media?.isPlaying==true }.getOrDefault(false)
    val position: Int get()=if(loading) savedPosition else runCatching { media?.currentPosition ?: savedPosition }.getOrDefault(savedPosition)
    fun load(source: File, autoPlay: Boolean=false) {
        release(); file=source;duration=0;savedPosition=0;error=null;loading=true;pendingPlay=autoPlay
        val next=MediaPlayer();media=next
        try {
            next.setAudioAttributes(attributes)
            next.setOnPreparedListener {
                if(media!==it) return@setOnPreparedListener
                duration=it.duration;loading=false
                if(speed!=1f) {
                    try { it.playbackParams=PlaybackParams().setSpeed(speed).setPitch(1f);it.pause() }
                    catch(_: Exception) { speed=1f }
                }
                if(pendingPlay) start() else changed()
            }
            next.setOnCompletionListener { savedPosition=it.duration;pendingPlay=false;manager.abandonAudioFocusRequest(focus);changed() }
            next.setOnErrorListener { _,_,_ -> failed("Could not play this audio. Tap Play to retry.");true }
            next.setDataSource(source.absolutePath);next.prepareAsync();changed()
        } catch(e: Exception) { failed(e.message ?: "Could not open audio.") }
    }
    private fun failed(message: String) { release();loading=false;error=message;changed() }
    fun toggle() {
        if(loading) return
        if(media==null) { file?.let { load(it,true) };return }
        if(playing) pause() else start()
    }
    fun play() { start() }
    private fun start() {
        if(loading) { pendingPlay=true;return }
        if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { error="Audio is in use. Try Play again.";changed();return }
        try {
            if(position>=duration-50) seek(0)
            error=null;media?.start();pendingPlay=true;changed()
        } catch(e: Exception) { failed(e.message ?: "Playback failed.") }
    }
    fun pause() {
        pendingPlay=false
        if(!loading && playing) { savedPosition=position;media?.pause() }
        manager.abandonAudioFocusRequest(focus);changed()
    }
    fun seek(milliseconds: Int) {
        if(loading || media==null) return
        savedPosition=milliseconds.coerceIn(0,duration)
        media?.seekTo(savedPosition.toLong(),MediaPlayer.SEEK_CLOSEST);changed()
    }
    fun skip(milliseconds: Int) = seek(position+milliseconds)
    fun changeSpeed(value: Float) {
        require(value in .5f..2f)
        val previous=speed
        try {
            if(media!=null && !loading) {
                val resume=playing
                media!!.playbackParams=PlaybackParams().setSpeed(value).setPitch(1f)
                if(!resume) media!!.pause()
            }
            speed=value;changed()
        } catch(e: Exception) { speed=previous;error="This playback speed is unavailable.";changed() }
    }
    fun release() {
        pendingPlay=false
        media?.setOnPreparedListener(null);media?.setOnCompletionListener(null);media?.setOnErrorListener(null)
        media?.release();media=null;loading=false
        manager.abandonAudioFocusRequest(focus)
    }
}
