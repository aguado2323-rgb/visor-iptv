package com.visor.tv.player

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Pausar y rebobinar la TV en directo: un hilo va leyendo el canal y lo guarda en memoria, en un anillo
 * de trozos de 1 MB. El reproductor lee de aquí y no del servidor, así puede quedarse atrás del directo
 * (pausa) o volver unos minutos (rebobinar). Lo más antiguo se va sobrescribiendo.
 * Si la conexión se corta, se vuelve a abrir sola y se sigue añadiendo.
 */
class TimeshiftBuffer(private val http: OkHttpClient, private val url: String, capacityBytes: Long) {
    private val chunkSize = 1 shl 20
    private val nChunks = (capacityBytes / chunkSize).toInt().coerceAtLeast(16)
    private val chunks = arrayOfNulls<ByteArray>(nChunks)
    private val lock = Object()

    /** Bytes escritos desde el principio (posición absoluta). */
    @Volatile var writePos = 0L
        private set
    @Volatile private var running = true
    /** Último error de red (se reintenta solo). */
    @Volatile var lastError: String? = null
        private set

    // Marcas (momento, posición) cada medio segundo, para saber dónde estaba el directo hace X segundos
    private val index = ArrayDeque<LongArray>()

    /** Lo más antiguo que aún se puede leer. Se deja un trozo de margen frente al que se está escribiendo. */
    val oldestPos: Long get() = (writePos - (nChunks - 2).toLong() * chunkSize).coerceAtLeast(0)

    private val thread = Thread({ loop() }, "visor-timeshift").apply { isDaemon = true; start() }

    private fun loop() {
        var backoff = 500L
        while (running) {
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val input = r.body?.byteStream() ?: throw IOException("Respuesta vacía")
                    val buf = ByteArray(64 * 1024)
                    backoff = 500L
                    lastError = null
                    while (running) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) write(buf, n)
                    }
                }
            } catch (e: Exception) {
                if (!running) break
                lastError = e.message ?: "Error de red"
            }
            if (!running) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(5000L)
        }
    }

    private fun write(b: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val p = writePos
            val ci = ((p / chunkSize) % nChunks).toInt()
            val co = (p % chunkSize).toInt()
            val c = chunks[ci] ?: ByteArray(chunkSize).also { chunks[ci] = it }
            val len = minOf(n - off, chunkSize - co)
            System.arraycopy(b, off, c, co, len)
            off += len
            synchronized(lock) {
                writePos = p + len
                lock.notifyAll()
            }
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(index) {
            if (index.isEmpty() || now - index.last()[0] >= 500) index.addLast(longArrayOf(now, writePos))
            val oldest = oldestPos
            while (index.size > 1 && index[1][1] <= oldest) index.removeFirst()
        }
    }

    /**
     * Copia bytes desde [pos]. Espera si aún no han llegado. Devuelve el número de bytes copiados,
     * [OVERWRITTEN] si esa parte ya se sobrescribió o [C.RESULT_END_OF_INPUT] si se ha cerrado.
     */
    fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        synchronized(lock) {
            while (running && writePos <= pos) {
                try { lock.wait(250) } catch (e: InterruptedException) { throw InterruptedIOException() }
            }
        }
        if (!running) return C.RESULT_END_OF_INPUT
        if (pos < oldestPos) return OVERWRITTEN
        val available = (writePos - pos).coerceAtMost(len.toLong()).toInt()
        val ci = ((pos / chunkSize) % nChunks).toInt()
        val co = (pos % chunkSize).toInt()
        val n = minOf(available, chunkSize - co)
        val c = chunks[ci] ?: return OVERWRITTEN
        System.arraycopy(c, co, dst, off, n)
        return n
    }

    /** Posición donde estaba el directo hace [agoMs] (o lo más antiguo que quede). */
    fun positionAgo(agoMs: Long): Long {
        val target = SystemClock.elapsedRealtime() - agoMs
        synchronized(index) {
            var best = index.firstOrNull()?.get(1) ?: 0L
            for (e in index) { if (e[0] <= target) best = e[1] else break }
            return best.coerceAtLeast(oldestPos)
        }
    }

    /** Cuánto se puede ir hacia atrás ahora mismo (ms). */
    fun windowMs(): Long = synchronized(index) {
        val first = index.firstOrNull() ?: return 0L
        (SystemClock.elapsedRealtime() - first[0]).coerceAtLeast(0)
    }

    fun close() {
        running = false
        thread.interrupt()
        synchronized(lock) { lock.notifyAll() }
        for (i in chunks.indices) chunks[i] = null
    }

    companion object {
        const val OVERWRITTEN = -2

        /** Hasta 160 MB (unos 3 minutos de un canal HD), sin pasar de un tercio de la memoria de la app. */
        fun defaultCapacity(): Long = minOf(160L shl 20, Runtime.getRuntime().maxMemory() / 3)
    }
}

/**
 * Fuente de datos para ExoPlayer que lee del anillo del directo desde la posición indicada en la URI
 * (`timeshift://live?pos=N`). Si lo que se iba a leer ya se ha sobrescrito (pausa demasiado larga),
 * salta al directo y avisa.
 */
@OptIn(UnstableApi::class)
class TimeshiftDataSource(private val buffer: TimeshiftBuffer, private val onOverflow: () -> Unit) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var readPos = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val start = (dataSpec.uri.getQueryParameter("pos")?.toLongOrNull() ?: 0L) + dataSpec.position
        readPos = start.coerceAtLeast(buffer.oldestPos)
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val n = buffer.read(readPos, target, offset, length)
            if (n == TimeshiftBuffer.OVERWRITTEN) {
                readPos = buffer.writePos
                onOverflow()
                continue
            }
            if (n > 0) { readPos += n; bytesTransferred(n) }
            return n
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (uri != null) { uri = null; transferEnded() }
    }

    class Factory(private val buffer: TimeshiftBuffer, private val onOverflow: () -> Unit) : DataSource.Factory {
        override fun createDataSource(): DataSource = TimeshiftDataSource(buffer, onOverflow)
    }
}
