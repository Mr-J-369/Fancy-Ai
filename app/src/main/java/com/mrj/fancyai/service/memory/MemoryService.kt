package com.mrj.fancyai.service.memory

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import com.mrj.fancyai.memory.MemoryEmbedder
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class MemoryService : Service() {
    private var embedder: MemoryEmbedder? = null

    private val binder = object : IMemoryService.Stub() {
        @Synchronized
        override fun embed(text: String?): FloatArray {
            val input = requireNotNull(text)
            var active = embedder
            if (active == null) {
                AppLog.write(android.util.Log.INFO, "Memory", "Model loading")
                active = MemoryEmbedder(MemoryPackage.directory(this@MemoryService)).also { embedder = it }
                AppLog.write(android.util.Log.INFO, "Memory", "Model loaded")
            }
            try {
                return active.embed(input)
            } catch (failure: Throwable) {
                if (embedder === active) {
                    runCatching { active.close() }
                    embedder = null
                }
                throw failure
            }
        }

        @Synchronized
        override fun release() {
            runCatching { embedder?.close() }
            embedder = null
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { embedder?.close() }
        embedder = null
        super.onDestroy()
    }
}

internal object MemoryClient {
    private val lock = Mutex()
    @Volatile private var service: IMemoryService? = null

    suspend fun embed(context: Context, text: String): FloatArray {
        try {
            return connected(context.applicationContext).embed(text)
                ?: throw IllegalStateException("Memory embedding failed")
        } catch (dead: DeadObjectException) {
            service = null
            throw IllegalStateException("Memory service disconnected", dead)
        }
    }

    private suspend fun connected(app: Context): IMemoryService {
        service?.let { return it }
        return lock.withLock {
            service?.let { return it }
            val connected = CompletableDeferred<IBinder>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null) {
                        connected.completeExceptionally(IllegalStateException("Memory service unavailable"))
                        return
                    }
                    try {
                        binder.linkToDeath({ service = null }, 0)
                    } catch (_: Exception) {
                        connected.completeExceptionally(IllegalStateException("Memory service disconnected"))
                        return
                    }
                    service = IMemoryService.Stub.asInterface(binder)
                    connected.complete(binder)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                    if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException("Memory service disconnected"))
                }

                override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
                override fun onNullBinding(name: ComponentName?) = onServiceDisconnected(name)
            }
            if (!app.bindService(Intent(app, MemoryService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                throw IllegalStateException("Memory service unavailable")
            }
            try {
                withTimeout(CONNECT_TIMEOUT) { connected.await() }
            } catch (failure: Exception) {
                runCatching { app.unbindService(connection) }
                service = null
                throw failure
            }
            service ?: throw IllegalStateException("Memory service unavailable")
        }
    }

    private val CONNECT_TIMEOUT = 20.seconds
}
