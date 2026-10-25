package com.visor.tv.record

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.visor.tv.R
import com.visor.tv.VisorApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Graba copiando el flujo MPEG-TS del canal a un archivo, en paralelo a lo que se esté viendo.
 * Ojo: cada grabación abre una conexión más con el proveedor.
 */
class RecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val stopping: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_STOP -> if (id != null) stopping.add(id)
            else -> if (id != null && !jobs.containsKey(id)) {
                jobs[id] = scope.launch { record(id) }
            }
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val n = NotificationCompat.Builder(this, VisorApp.CHANNEL_REC)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Visor")
            .setContentText("Grabando")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, 77, n, type)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "visor:rec").apply { acquire(6 * 60 * 60_000L) }
        }
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty()) {
            wake?.let { if (it.isHeld) it.release() }
            wake = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun update(r: Recording) = withContext(Dispatchers.Main) { VisorApp.instance.recordings.upsert(r) }

    private suspend fun record(id: String) {
        val app = VisorApp.instance
        val store = app.recordings
        var r = withContext(Dispatchers.Main) { store.get(id) } ?: return finish(id)
        if (r.status == Recording.STATUS_CANCELLED || r.end <= System.currentTimeMillis()) return finish(id)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(r.start))
        val safe = r.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifBlank { "grabacion" }
        val out = File(store.targetDir(if (r.storage >= 0) r.storage else app.settings.storageIndex), "${stamp}_$safe.ts")
        r = r.copy(status = Recording.STATUS_RECORDING, file = out.absolutePath, error = "")
        update(r)

        val client = app.http.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()
        var written = 0L
        var lastError = ""
        try {
            FileOutputStream(out, true).use { fos ->
                val buf = ByteArray(64 * 1024)
                while (scope.isActive && id !in stopping && System.currentTimeMillis() < r.end) {
                    try {
                        val url = app.repo.liveUrl(r.streamId, "ts")
                        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                            if (!resp.isSuccessful) throw java.io.IOException("El servidor respondió ${resp.code}")
                            val input = resp.body?.byteStream() ?: throw java.io.IOException("Sin datos")
                            var lastSave = System.currentTimeMillis()
                            while (id !in stopping && System.currentTimeMillis() < r.end) {
                                val n = input.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                                written += n
                                if (System.currentTimeMillis() - lastSave > 15_000) {
                                    lastSave = System.currentTimeMillis()
                                    update(r.copy(bytes = written))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        lastError = e.message ?: "Error de conexión"
                        delay(3000) // corte de red o del proveedor: se reintenta hasta la hora de fin
                    }
                }
            }
        } catch (e: Exception) {
            lastError = e.message ?: "No se pudo escribir el archivo"
        }

        val cancelled = withContext(Dispatchers.Main) { store.get(id)?.status == Recording.STATUS_CANCELLED }
        val result = when {
            cancelled -> r.copy(status = Recording.STATUS_CANCELLED, bytes = written)
            written > 0 -> r.copy(status = Recording.STATUS_DONE, bytes = written)
            else -> r.copy(status = Recording.STATUS_FAILED, bytes = 0, error = lastError.ifBlank { "Sin datos" })
        }
        if (written == 0L) out.delete()
        update(result)
        withContext(Dispatchers.Main) { store.scheduleNext(r) }
        finish(id)
    }

    private suspend fun finish(id: String) {
        jobs.remove(id)
        stopping.remove(id)
        withContext(Dispatchers.Main) { stopIfIdle() }
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "rec_id"
        private const val ACTION_START = "com.visor.tv.REC_START"
        private const val ACTION_STOP = "com.visor.tv.REC_STOP"

        fun start(ctx: Context, id: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecordService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id))
        }

        fun stop(ctx: Context, id: String) {
            ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id))
        }
    }
}

class RecordAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(RecordService.EXTRA_ID) ?: return
        RecordService.start(context, id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            VisorApp.instance.recordings.rescheduleAll()
        }
    }
}
