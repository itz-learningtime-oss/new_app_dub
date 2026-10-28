package com.voxstudio.app

import android.app.Service
import android.content.*
import android.os.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/** GPU driver failures are contained in :gpu; the caller can retry on CPU. */
class GpuWorkerService: Service() {
    companion object {
        @Volatile var progress: ((String,Int,Int,Double)->Unit)?=null
    }
    private val executor=Executors.newSingleThreadExecutor()
    private val messenger=Messenger(Handler(Looper.getMainLooper()) { message ->
        if(message.what==2) { if(NativeGpuEngine.available) NativeGpuEngine.cancel();true }
        else {
            val reply=message.replyTo;val b=message.data
            executor.execute {
                progress={ stage,current,total,seconds ->
                    try { reply.send(Message.obtain(null,3).apply { data=Bundle().apply {
                        putString("stage",stage);putInt("current",current);putInt("total",total);putDouble("seconds",seconds)
                    } }) } catch(_: RemoteException) {}
                }
                val error=try {
                    if(!NativeGpuEngine.available) "GPU library unavailable" else {
                        NativeGpuEngine.setBenchmarkSeedForTesting(b.getInt("seed",-1))
                        NativeGpuEngine.synthesize(b.getString("model")!!,b.getString("reference"),b.getString("transcript"),b.getString("text")!!,b.getString("output")!!,b.getInt("steps"),b.getInt("threads"))
                    }
                } catch(e: Throwable) { e.message ?: "GPU execution failed" }
                // The :gpu process outlives the request; never leave a second model copy resident.
                if(NativeGpuEngine.available) runCatching { NativeGpuEngine.release() }
                progress=null
                try { reply.send(Message.obtain(null,1).apply { data=Bundle().apply { putString("error",error) } }) } catch(_: RemoteException) {}
            };true
        }
    })
    override fun onBind(intent: Intent)=messenger.binder
    override fun onDestroy() { executor.shutdown();super.onDestroy() }
}

object GpuWorkerClient {
    @Volatile private var worker: Messenger?=null
    fun cancel() { try { worker?.send(Message.obtain(null,2)) } catch(_: RemoteException) {} }
    fun synthesize(context: Context,bundle: Bundle): String {
        val done=CountDownLatch(1)
        var error="GPU process disconnected"
        val response=Messenger(Handler(Looper.getMainLooper()) { message ->
            if(message.what==3) { val b=message.data;StudioJobs.nativeProgress(b.getString("stage")!!,b.getInt("current"),b.getInt("total"),b.getDouble("seconds")) }
            else { error=message.data.getString("error") ?: "GPU returned no result";done.countDown() };true
        })
        val connection=object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName,binder: IBinder) {
                worker=Messenger(binder)
                try { worker!!.send(Message.obtain(null,1).apply { data=bundle;replyTo=response }) }
                catch(_: RemoteException) { done.countDown() }
            }
            override fun onServiceDisconnected(name: ComponentName) { done.countDown() }
            override fun onNullBinding(name: ComponentName) { done.countDown() }
            override fun onBindingDied(name: ComponentName) { done.countDown() }
        }
        val bound=try { context.bindService(Intent(context,GpuWorkerService::class.java),connection,Context.BIND_AUTO_CREATE) } catch(_: Exception) { false }
        if(!bound)return "GPU service unavailable"
        return try {
            val deadline=SystemClock.elapsedRealtime()+2*60*1000
            while(!done.await(200,TimeUnit.MILLISECONDS)) {
                if(StudioJobs.stop) { cancel();return "GPU generation cancelled" }
                if(SystemClock.elapsedRealtime()>deadline) { cancel();return "GPU generation timed out" }
            }
            error
        } finally { worker=null;context.unbindService(connection) }
    }
}
