package com.visor.tv.addons

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.visor.tv.data.PlayItem
import com.visor.tv.data.SecureStore
import com.visor.tv.data.Settings
import com.visor.tv.debrid.AllDebrid
import com.visor.tv.debrid.DebridAccount
import com.visor.tv.debrid.DebridAuthException
import com.visor.tv.debrid.DebridKind
import com.visor.tv.debrid.DebridService
import com.visor.tv.debrid.NotCachedException
import com.visor.tv.debrid.Premiumize
import com.visor.tv.debrid.RealDebrid
import com.visor.tv.debrid.TorrentRequest
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/** Una fila de Descubrir: un catálogo de un addon. */
data class CatalogRow(val addon: InstalledAddon, val catalog: AddonCatalog) {
    val key: String get() = addon.key + "/" + catalog.type + "/" + catalog.id
    val title: String
        get() {
            val kind = if (catalog.type == "series") "Series" else "Películas"
            val name = NAMES[catalog.name.trim().lowercase()] ?: catalog.name.trim()
            return if (name.isBlank()) kind else "$kind · $name"
        }

    private companion object {
        // Los catálogos más comunes vienen en inglés
        val NAMES = mapOf(
            "popular" to "Populares", "featured" to "Destacadas", "top" to "Populares", "new" to "Novedades",
            "trending" to "Tendencias", "top rated" to "Mejor valoradas", "latest" to "Últimas",
        )
    }
}

/** Fuentes encontradas para un título, ya ordenadas, y qué addons fallaron al pedirlas. */
data class StreamsResult(val streams: List<StreamInfo>, val failed: List<String>)

/** Estado de una fila de Descubrir: se carga una vez y se queda aunque la fila salga de la pantalla. */
data class RowData(val items: List<MetaPreview>? = null, val error: String? = null) {
    val loading: Boolean get() = items == null && error == null
}

/** Lo que el reproductor necesita para empezar. */
data class PlaySource(val url: String, val streamId: String, val label: String, val subtitles: List<AddonSubtitle> = emptyList())

/**
 * Copia de seguridad de Descubrir: addons (con su dirección completa, que puede llevar claves) y cuentas debrid.
 * Se descarga desde el PC con la página de "Añadir desde el móvil o el PC"; nunca entra en la copia general.
 */
@Serializable
data class DiscoverBackup(
    val app: String = "visor",
    val kind: String = "discover",
    val version: Int = 1,
    val created: Long = 0,
    val addons: List<InstalledAddon> = emptyList(),
    val debrid: List<DebridAccount> = emptyList(),
)

/** Fuente elegida la última vez para un título o una serie (sin direcciones: solo para reconocerla). */
@Serializable
private data class Pick(val streamId: String = "", val bingeGroup: String = "")

/**
 * La sección Descubrir: addons instalados, cuentas debrid y el camino de un título hasta un enlace reproducible.
 * Todo lo sensible (direcciones de addons con clave, tokens) vive en el almacén cifrado.
 * Los métodos se llaman desde el hilo principal; la red va por dentro en segundo plano.
 */
class Discover(context: Context, http: OkHttpClient, private val vault: SecureStore, private val settings: Settings) {
    val client = AddonClient(http)
    val addons = mutableStateListOf<InstalledAddon>()
    val accounts = mutableStateListOf<DebridAccount>()
    /** Cambia cuando cambian los addons: las pantallas vuelven a cargar. */
    var version by mutableIntStateOf(0)
        private set

    /** Filas ya pedidas, por [CatalogRow.key]. */
    val rowData = mutableStateMapOf<String, RowData>()
    private val rowJobs = HashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Unas pocas a la vez y en orden: las primeras filas llegan antes y no se satura un addon lento
    private val rowGate = Semaphore(4)

    private val json = Json { ignoreUnknownKeys = true }
    private val services: Map<DebridKind, DebridService> =
        listOf(RealDebrid(http), AllDebrid(http), Premiumize(http)).associateBy { it.kind }
    private val picksFile = File(context.filesDir, "discover_picks.json")
    private val picks = LinkedHashMap<String, Pick>()
    /** Fuente que el usuario acaba de elegir a mano, por título: manda sobre la elección automática. */
    private val chosen = HashMap<String, AddonStream>()

    init {
        val saved = vault.loadSecret(K_ADDONS)?.let { try { json.decodeFromString<List<InstalledAddon>>(it) } catch (e: Exception) { null } }
        // La primera vez viene Cinemeta, el catálogo oficial de Stremio: solo datos de películas y series, sin fuentes
        addons.addAll(saved ?: listOf(CINEMETA))
        vault.loadSecret(K_DEBRID)?.let { try { accounts.addAll(json.decodeFromString<List<DebridAccount>>(it)) } catch (e: Exception) { } }
        try { if (picksFile.exists()) picks.putAll(json.decodeFromString<Map<String, Pick>>(picksFile.readText())) } catch (e: Exception) { }
    }

    // ---------------- Addons ----------------

    private fun saveAddons() {
        vault.saveSecret(K_ADDONS, json.encodeToString(addons.toList()))
        client.clearCache()
        rowJobs.values.forEach { it.cancel() }
        rowJobs.clear()
        rowData.clear()
        version++
        prefetch()
    }

    /**
     * Pide una fila si aún no está pedida (o si falló la última vez). La carga no depende de la pantalla:
     * si la fila sale de la vista o se cambia de pestaña, sigue y el resultado queda guardado.
     */
    fun ensure(row: CatalogRow) {
        val running = rowJobs[row.key]?.isActive == true
        if (running || rowData[row.key]?.items != null) return
        rowData[row.key] = RowData()
        rowJobs[row.key] = scope.launch {
            rowGate.withPermit {
                rowData[row.key] = try {
                    RowData(items = catalog(row))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RowData(error = e.message ?: "no se pudo cargar")
                }
            }
        }
    }

    /** Empieza a cargar todas las filas, para que Descubrir aparezca ya lleno al abrirlo. */
    fun prefetch() = rows().forEach { ensure(it) }

    /** Instala (o actualiza, si ya estaba) el addon de esa dirección. */
    suspend fun install(input: String): InstalledAddon {
        val a = client.install(input)
        val i = addons.indexOfFirst { it.key == a.key }
        if (i >= 0) addons[i] = a.copy(enabled = addons[i].enabled) else addons.add(a)
        saveAddons()
        return a
    }

    fun remove(a: InstalledAddon) { addons.removeAll { it.key == a.key }; saveAddons() }

    fun setEnabled(a: InstalledAddon, on: Boolean) {
        val i = addons.indexOfFirst { it.key == a.key }
        if (i >= 0) { addons[i] = addons[i].copy(enabled = on); saveAddons() }
    }

    /** Sube o baja un addon: el orden decide el de las filas y qué addon da las fichas. */
    fun move(a: InstalledAddon, delta: Int) {
        val i = addons.indexOfFirst { it.key == a.key }
        val j = i + delta
        if (i < 0 || j !in addons.indices) return
        val x = addons.removeAt(i)
        addons.add(j, x)
        saveAddons()
    }

    private val active: List<InstalledAddon> get() = addons.filter { it.enabled }

    val hasStreamAddon: Boolean get() = active.any { it.manifest.has("stream") }

    /** Filas fijas de Descubrir: catálogos de películas y series que no piden nada obligatorio. */
    fun rows(type: String? = null): List<CatalogRow> = active.flatMap { a ->
        if (!a.manifest.has("catalog")) emptyList()
        else a.manifest.catalogs.filter { c -> c.type in TYPES && !c.needsExtra && (type == null || c.type == type) }.map { CatalogRow(a, it) }
    }

    suspend fun catalog(row: CatalogRow): List<MetaPreview> = client.catalog(row.addon, row.catalog)

    /** Busca en todos los catálogos que lo admiten, a la vez; un addon que falla no estropea el resto. */
    suspend fun search(query: String): List<MetaPreview> = coroutineScope {
        active.flatMap { a ->
            if (!a.manifest.has("catalog")) emptyList()
            else a.manifest.catalogs.filter { it.type in TYPES && it.search && (!it.needsExtra || it.searchOnly) }.map { a to it }
        }.map { (a, c) ->
            async { quiet { client.catalog(a, c, search = query) }.orEmpty() }
        }.awaitAll().flatten().distinctBy { it.key }
    }

    /** Ficha completa: del primer addon (por orden) que la tenga. */
    suspend fun meta(type: String, id: String): MetaDetail? {
        for (a in active) {
            if (!a.manifest.supports("meta", type, id)) continue
            quiet { client.meta(a, type, id) }?.let { return it }
        }
        return null
    }

    // ---------------- Fuentes ----------------

    val prefs: StreamPrefs
        get() = StreamPrefs(settings.maxQuality.height, settings.maxSize.gb, settings.spanishFirst, settings.hideCam)

    /** Una fuente de torrent solo sirve si hay una cuenta debrid que la convierta en enlace. */
    fun playable(s: AddonStream): Boolean = s.isDirect || accounts.isNotEmpty()

    /** Pide las fuentes a todos los addons a la vez y las ordena según las preferencias. */
    suspend fun streams(item: PlayItem): StreamsResult = coroutineScope {
        val type = item.ref.substringBefore('/')
        val videoId = item.ref.substringAfter('/')
        val sources = active.filter { it.manifest.supports("stream", type, videoId) }
        val results = sources.map { a ->
            async {
                try {
                    a.manifest.name to client.streams(a, type, videoId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    a.manifest.name to null
                }
            }
        }.awaitAll()
        val pick = picks[item.key] ?: picks[item.parent]
        val ranked = StreamRanker.rank(results.flatMap { it.second.orEmpty() }, prefs, pick?.bingeGroup.orEmpty(), ::playable)
        // La misma fuente con la que se empezó a ver este vídeo va la primera: así la reanudación cuadra
        val same = picks[item.key]?.streamId
        val ordered = if (same.isNullOrBlank()) ranked else ranked.sortedByDescending { it.stream.id == same }
        StreamsResult(ordered, results.filter { it.second == null }.map { it.first })
    }

    /** El usuario elige una fuente a mano para este título. */
    fun choose(item: PlayItem, s: AddonStream) { chosen[item.key] = s }

    private fun remember(item: PlayItem, s: AddonStream) {
        val p = Pick(s.id, s.bingeGroup)
        picks.remove(item.key); picks[item.key] = p
        if (item.parent.isNotBlank() && item.parent != item.ref) { picks.remove(item.parent); picks[item.parent] = p }
        while (picks.size > MAX_PICKS) picks.remove(picks.keys.first())
        try { picksFile.writeText(json.encodeToString(picks.toMap())) } catch (e: Exception) { }
    }

    /** Enlace reproducible de una fuente: el suyo, o el que dé la primera cuenta debrid que la tenga en caché. */
    suspend fun resolve(s: AddonStream, item: PlayItem): String {
        if (s.isDirect) return s.url
        if (accounts.isEmpty()) throw IOException("Esta fuente necesita una cuenta debrid. Conéctala en Ajustes → Descubrir")
        val req = TorrentRequest(s.infoHash, s.fileIdx, s.filename, item.season, item.episode, s.trackers)
        var last: Exception = IOException("No se pudo abrir la fuente")
        for (acc in accounts.toList()) {
            val svc = services[acc.kind] ?: continue
            try {
                val fresh = svc.fresh(acc)
                if (fresh !== acc) replaceAccount(fresh)
                return svc.resolve(fresh, req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotCachedException) {
                last = e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last
    }

    /**
     * Lo que va a reproducirse para [item]: la fuente elegida a mano si la hay y, si no (o si falla), la mejor
     * según las preferencias. [tried] son las fuentes que ya han fallado en esta sesión de reproducción.
     */
    suspend fun source(item: PlayItem, tried: Set<String> = emptySet()): PlaySource {
        if (!hasStreamAddon) throw IOException("No hay ningún addon de fuentes. Añade uno en Ajustes → Descubrir")
        var last: Exception? = null
        suspend fun attempt(s: AddonStream, label: String): PlaySource? = try {
            val url = resolve(s, item)
            remember(item, s)
            // Los subtítulos son un extra: si el addon tarda, se empieza sin ellos
            PlaySource(url, s.id, label, kotlinx.coroutines.withTimeoutOrNull(4000) { subtitlesFor(item) }.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            last = e
            null
        }

        chosen[item.key]?.takeIf { it.id !in tried }?.let { s ->
            attempt(s, StreamRanker.parse(s).let { labelOf(it) })?.let { return it }
        }
        val found = streams(item)
        val candidates = found.streams.filter { playable(it.stream) && it.stream.id !in tried && it.stream.id != chosen[item.key]?.id }
        // Con debrid propio, las primeras pueden no estar en caché: se prueban unas cuantas antes de rendirse
        for (c in candidates.take(MAX_ATTEMPTS)) attempt(c.stream, labelOf(c))?.let { return it }
        throw IOException(
            when {
                last is DebridAuthException -> last!!.message!!
                found.streams.isEmpty() && found.failed.isNotEmpty() -> "Los addons de fuentes no responden (${found.failed.joinToString()})"
                found.streams.isEmpty() -> "Ningún addon tiene fuentes para este título"
                candidates.isEmpty() && tried.isEmpty() && accounts.isEmpty() -> "Las fuentes encontradas necesitan una cuenta debrid. Conéctala en Ajustes → Descubrir"
                candidates.isEmpty() -> "No quedan más fuentes que probar"
                else -> last?.message ?: "No se pudo abrir ninguna fuente"
            }
        )
    }

    private fun labelOf(i: StreamInfo): String = (i.tags.take(3) + listOf(i.size)).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { i.stream.addon }

    /** Subtítulos externos de los addons que los ofrecen, en los idiomas que interesan. */
    private suspend fun subtitlesFor(item: PlayItem): List<AddonSubtitle> = coroutineScope {
        val type = item.ref.substringBefore('/')
        val videoId = item.ref.substringAfter('/')
        val wanted = listOfNotNull(settings.subsDefault.code, "es", "en").distinct()
        active.filter { it.manifest.supports("subtitles", type, videoId) }.map { a ->
            async { quiet { client.subtitles(a, type, videoId) }.orEmpty() }
        }.awaitAll().flatten()
            .mapNotNull { s -> iso2(s.lang)?.takeIf { it in wanted }?.let { s.copy(lang = it) } }
            .groupBy { it.lang }.flatMap { it.value.take(2) }
            .sortedBy { wanted.indexOf(it.lang) }
    }

    // ---------------- Copia de seguridad ----------------

    private val pretty = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun exportBackup(): String =
        pretty.encodeToString(DiscoverBackup(created = System.currentTimeMillis(), addons = addons.toList(), debrid = accounts.toList()))

    /** Sustituye addons y cuentas por los de la copia. Devuelve un resumen para mostrar. */
    fun restoreBackup(text: String): String {
        val b = try { json.decodeFromString<DiscoverBackup>(text.trim()) } catch (e: Exception) { null }
        if (b == null || b.app != "visor" || b.kind != "discover") throw IOException("Eso no es una copia de seguridad de Descubrir")
        if (b.addons.isEmpty() && b.debrid.isEmpty()) throw IOException("La copia está vacía")
        if (b.addons.isNotEmpty()) {
            addons.clear(); addons.addAll(b.addons.distinctBy { it.key })
            saveAddons()
        }
        if (b.debrid.isNotEmpty()) {
            accounts.clear(); accounts.addAll(b.debrid.distinctBy { it.kind })
            saveAccounts()
        }
        return "Copia restaurada: ${b.addons.size} addons y ${b.debrid.size} cuentas debrid"
    }

    // ---------------- Cuentas debrid ----------------

    fun service(kind: DebridKind): DebridService = services.getValue(kind)
    fun account(kind: DebridKind): DebridAccount? = accounts.firstOrNull { it.kind == kind }

    private fun saveAccounts() = vault.saveSecret(K_DEBRID, json.encodeToString(accounts.toList()))

    fun replaceAccount(a: DebridAccount) {
        val i = accounts.indexOfFirst { it.kind == a.kind }
        if (i >= 0) accounts[i] = a else accounts.add(a)
        saveAccounts()
    }

    fun disconnect(kind: DebridKind) { accounts.removeAll { it.kind == kind }; saveAccounts() }

    /** Conecta con la clave de la cuenta (la "API key" que da la web del servicio). */
    suspend fun connectWithKey(kind: DebridKind, key: String): DebridAccount {
        val k = key.trim()
        if (k.length < 8 || k.any { it.isWhitespace() }) throw IOException("La clave no parece válida")
        return service(kind).check(DebridAccount(kind = kind, token = k)).also { replaceAccount(it) }
    }

    /** Pone al día el estado de las cuentas (premium, caducidad). */
    suspend fun refreshAccounts() {
        for (acc in accounts.toList()) quiet {
            val svc = service(acc.kind)
            replaceAccount(svc.check(svc.fresh(acc)))
        }
    }

    private suspend fun <T> quiet(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val K_ADDONS = "addons"
        private const val K_DEBRID = "debrid"
        private const val MAX_PICKS = 300
        private const val MAX_ATTEMPTS = 6
        private val TYPES = setOf("movie", "series")

        private val ISO3 = mapOf("spa" to "es", "eng" to "en", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de", "ita" to "it", "por" to "pt", "pob" to "pt", "cat" to "ca")
        /** Los addons dan el idioma en tres letras ("spa"); el reproductor lo quiere en dos ("es"). */
        private fun iso2(lang: String): String? {
            val l = lang.trim().lowercase()
            return when {
                l.length == 2 -> l
                l.startsWith("spanish") || l.startsWith("espa") -> "es"
                l.startsWith("english") -> "en"
                else -> ISO3[l.take(3)]
            }
        }

        /** Cinemeta tal como lo publica Stremio. Va incluido para que Descubrir tenga catálogos desde el primer momento. */
        val CINEMETA = InstalledAddon(
            base = "https://v3-cinemeta.strem.io",
            manifest = AddonManifest(
                id = "com.linvo.cinemeta",
                name = "Cinemeta",
                description = "Catálogo oficial de Stremio: películas y series populares, con sus fichas",
                types = listOf("movie", "series"),
                idPrefixes = listOf("tt"),
                resources = listOf(AddonResource("catalog"), AddonResource("meta")),
                catalogs = listOf(
                    AddonCatalog("movie", "top", "Popular", search = true),
                    AddonCatalog("series", "top", "Popular", search = true),
                    AddonCatalog("movie", "imdbRating", "Featured"),
                    AddonCatalog("series", "imdbRating", "Featured"),
                ),
            ),
        )
    }
}
