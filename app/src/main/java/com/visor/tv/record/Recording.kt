package com.visor.tv.record

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.UUID

@Serializable
data class Recording(
    val id: String = UUID.randomUUID().toString(),
    val streamId: Int,
    val channel: String,
    val title: String,
    val start: Long,
    val end: Long,
    val repeat: String = REPEAT_ONCE, // once | daily | weekdays | weekly
    val source: String = "timer", // epg | timer | manual
    val status: String = STATUS_SCHEDULED, // scheduled | recording | done | failed | cancelled
    val file: String = "",
    val bytes: Long = 0,
    val error: String = "",
    /** Dónde guardarla (índice de almacenamiento); -1 = el de Ajustes. */
    val storage: Int = -1,
) {
    companion object {
        const val REPEAT_ONCE = "once"
        const val REPEAT_DAILY = "daily"
        const val REPEAT_WEEKDAYS = "weekdays"
        const val REPEAT_WEEKLY = "weekly"
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_RECORDING = "recording"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
    }
}

/** Lista de grabaciones y su programación con alarmas exactas. */
class RecordingStore(private val ctx: Context) {
    private val file = File(ctx.filesDir, "recordings.json")
    private val json = Json { ignoreUnknownKeys = true }
    val items = mutableStateListOf<Recording>()

    init {
        try {
            if (file.exists()) items.addAll(json.decodeFromString<List<Recording>>(file.readText()))
        } catch (_: Exception) { }
    }

    @Synchronized
    private fun save() {
        try { file.writeText(json.encodeToString(items.toList())) } catch (_: Exception) { }
    }

    fun get(id: String) = items.firstOrNull { it.id == id }

    fun upsert(r: Recording) {
        val i = items.indexOfFirst { it.id == r.id }
        if (i >= 0) items[i] = r else items.add(r)
        save()
    }

    fun remove(id: String) {
        get(id)?.let { r -> if (r.file.isNotBlank()) File(r.file).delete() }
        items.removeAll { it.id == id }
        save()
    }

    fun activeFor(streamId: Int) = items.firstOrNull { it.streamId == streamId && it.status == Recording.STATUS_RECORDING }

    /** Programa una grabación. Si ya debería haber empezado, arranca en el acto. */
    fun schedule(r: Recording) {
        upsert(r)
        if (r.start <= System.currentTimeMillis()) {
            RecordService.start(ctx, r.id)
        } else {
            setAlarm(r)
        }
    }

    fun cancel(id: String) {
        val r = get(id) ?: return
        cancelAlarm(r)
        if (r.status == Recording.STATUS_RECORDING) {
            // Detener una grabación en curso conserva lo grabado hasta ese momento.
            RecordService.stop(ctx, id)
        } else {
            upsert(r.copy(status = Recording.STATUS_CANCELLED))
        }
    }

    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        items.toList().forEach { r ->
            when {
                r.status == Recording.STATUS_RECORDING -> upsert(r.copy(status = Recording.STATUS_FAILED, error = "Interrumpida por un reinicio"))
                r.status == Recording.STATUS_SCHEDULED && r.end > now -> schedule(r)
                r.status == Recording.STATUS_SCHEDULED -> upsert(r.copy(status = Recording.STATUS_FAILED, error = "El aparato estaba apagado"))
            }
        }
    }

    /** Crea la siguiente repetición de una grabación periódica. */
    fun scheduleNext(r: Recording) {
        if (r.repeat == Recording.REPEAT_ONCE) return
        val cal = Calendar.getInstance().apply { timeInMillis = r.start }
        val len = r.end - r.start
        do {
            when (r.repeat) {
                Recording.REPEAT_WEEKLY -> cal.add(Calendar.DAY_OF_YEAR, 7)
                else -> cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        } while (r.repeat == Recording.REPEAT_WEEKDAYS &&
            (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY))
        schedule(
            r.copy(id = UUID.randomUUID().toString(), start = cal.timeInMillis, end = cal.timeInMillis + len,
                status = Recording.STATUS_SCHEDULED, file = "", bytes = 0, error = "")
        )
    }

    private fun pending(r: Recording): PendingIntent {
        val i = Intent(ctx, RecordAlarmReceiver::class.java).putExtra(RecordService.EXTRA_ID, r.id)
        return PendingIntent.getBroadcast(ctx, r.id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAlarm(r: Recording) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.start, pending(r))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.start, pending(r))
    }

    private fun cancelAlarm(r: Recording) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(r))
    }

    // ---------- Almacenamiento ----------

    data class Volume(val index: Int, val dir: File, val label: String, val freeBytes: Long)

    fun volumes(): List<Volume> {
        val dirs = ctx.getExternalFilesDirs(null).filterNotNull()
        val list = dirs.mapIndexed { i, d ->
            val free = try { StatFs(d.path).availableBytes } catch (e: Exception) { 0L }
            Volume(i, d, if (i == 0) "Memoria interna" else "Almacenamiento externo $i", free)
        }
        return list.ifEmpty { listOf(Volume(0, ctx.filesDir, "Memoria interna", 0L)) }
    }

    fun targetDir(index: Int): File {
        val v = volumes()
        val base = (v.firstOrNull { it.index == index } ?: v.first()).dir
        return File(base, "Grabaciones").also { it.mkdirs() }
    }
}
