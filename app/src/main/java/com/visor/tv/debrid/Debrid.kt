package com.visor.tv.debrid

import com.visor.tv.addons.StreamRanker
import com.visor.tv.data.asArr
import com.visor.tv.data.asObj
import com.visor.tv.data.i
import com.visor.tv.data.l
import com.visor.tv.data.s
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// Servicios debrid: convierten un torrent que ya tienen guardado ("en caché") en un enlace HTTPS directo.
// Visor solo reproduce lo que ya está en caché: no pone a descargar nada en tu cuenta.

enum class DebridKind(val label: String, val short: String, val site: String) {
    REAL_DEBRID("Real-Debrid", "RD", "real-debrid.com"),
    ALL_DEBRID("AllDebrid", "AD", "alldebrid.com"),
    PREMIUMIZE("Premiumize", "PM", "premiumize.me"),
}

/** Cuenta conectada. Se guarda cifrada; nunca entra en la copia de seguridad. */
@Serializable
data class DebridAccount(
    val kind: DebridKind,
    val token: String,
    // Solo Real-Debrid con código en pantalla: el token caduca y se renueva con estos datos
    val refresh: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val tokenExpires: Long = 0,
    val user: String = "",
    val premium: Boolean = false,
    /** Fin de la suscripción (ms); 0 si no se sabe. */
    val premiumUntil: Long = 0,
)

/** Código que el usuario escribe en la web del servicio, desde el móvil, para conectar la cuenta. */
data class DeviceCode(
    val userCode: String,
    val verifyUrl: String,
    /** Parte secreta con la que Visor pregunta si ya se ha autorizado. */
    val secret: String,
    val intervalSec: Int,
    val expiresAt: Long,
)

/** Lo que hace falta para pedir un vídeo al debrid. */
data class TorrentRequest(
    val infoHash: String,
    val fileIdx: Int? = null,
    val filename: String = "",
    /** Temporada y episodio, para elegir el archivo dentro de un pack de temporada. */
    val season: Int = 0,
    val episode: Int = 0,
    val trackers: List<String> = emptyList(),
) {
    val magnet: String
        get() = "magnet:?xt=urn:btih:$infoHash" + trackers.take(8).joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
}

/** El torrent no está guardado en el debrid: habría que descargarlo antes. */
class NotCachedException(service: String) : IOException("No está en la caché de $service")

/** El servicio rechazó la clave: hay que volver a conectar la cuenta. */
class DebridAuthException(service: String) : IOException("$service no reconoce la cuenta. Vuelve a conectarla en Ajustes")

interface DebridService {
    val kind: DebridKind
    /** Se puede conectar con un código en pantalla, sin teclear la clave. */
    val deviceCode: Boolean

    suspend fun startDevice(): DeviceCode
    /** null mientras el usuario no haya autorizado todavía. */
    suspend fun pollDevice(code: DeviceCode): DebridAccount?

    /** Comprueba la cuenta y devuelve sus datos al día (usuario, premium, caducidad). */
    suspend fun check(account: DebridAccount): DebridAccount
    /** Renueva el token si hace falta. Devuelve la misma cuenta si no ha cambiado nada. */
    suspend fun fresh(account: DebridAccount): DebridAccount = account
    /** Enlace directo al vídeo. Lanza [NotCachedException] si no está en caché. */
    suspend fun resolve(account: DebridAccount, req: TorrentRequest): String
}

/** Archivo dentro de un torrent, tal como lo lista cada servicio. */
internal data class TorrentFile(val index: Int, val path: String, val size: Long, val ref: String = "")

/**
 * Elige el archivo de vídeo: el que indica el addon por nombre, el del episodio pedido, el que indica el
 * addon por posición (solo si el servicio lista los archivos en el orden del torrent) o el más grande.
 */
internal fun pickFile(files: List<TorrentFile>, req: TorrentRequest, indexReliable: Boolean): TorrentFile? {
    val videos = files.filter { StreamRanker.isVideo(it.path) && !it.path.contains("sample", ignoreCase = true) }
        .ifEmpty { files.filter { StreamRanker.isVideo(it.path) } }
    if (videos.isEmpty()) return null
    if (req.filename.isNotBlank()) videos.firstOrNull { it.path.substringAfterLast('/').equals(req.filename, true) }?.let { return it }
    if (indexReliable && req.fileIdx != null) videos.firstOrNull { it.index == req.fileIdx }?.let { return it }
    if (req.season > 0 && req.episode > 0) {
        videos.filter { StreamRanker.matchesEpisode(it.path, req.season, req.episode) }.maxByOrNull { it.size }?.let { return it }
        // Un pack de varios episodios sin el que buscamos: mejor fallar que poner otro capítulo
        if (videos.size > 1 && videos.count { f -> (1..99).any { e -> StreamRanker.matchesEpisode(f.path, req.season, e) } } > 1) return null
    }
    return videos.maxByOrNull { it.size }
}

/** Peticiones JSON a la API de un servicio. Los errores nunca incluyen direcciones ni claves. */
internal class Api(private val http: OkHttpClient, private val service: String) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    class Reply(val code: Int, val body: JsonElement?) {
        val obj: JsonObject? get() = body.asObj()
        val ok: Boolean get() = code in 200..299
    }

    suspend fun get(url: String, token: String? = null, query: List<Pair<String, String>> = emptyList()): Reply {
        val u = url.toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return call(Request.Builder().url(u), token)
    }

    suspend fun post(url: String, token: String? = null, form: List<Pair<String, String>> = emptyList()): Reply {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return call(Request.Builder().url(url).post(body), token)
    }

    suspend fun delete(url: String, token: String?): Reply = call(Request.Builder().url(url).delete(), token)

    private suspend fun call(b: Request.Builder, token: String?): Reply = withContext(Dispatchers.IO) {
        if (token != null) b.header("Authorization", "Bearer $token")
        try {
            http.newCall(b.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                Reply(r.code, try { json.parseToJsonElement(text) } catch (e: Exception) { null })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw IOException("No se pudo conectar con $service")
        }
    }
}

internal fun JsonObject.flag(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: (s(k) == "true" || s(k) == "1")

// ---------------- Real-Debrid ----------------

class RealDebrid(http: OkHttpClient) : DebridService {
    override val kind = DebridKind.REAL_DEBRID
    override val deviceCode = true
    private val api = Api(http, kind.label)

    override suspend fun startDevice(): DeviceCode {
        val o = api.get("$OAUTH/device/code", query = listOf("client_id" to OPEN_CLIENT, "new_credentials" to "yes")).obj
            ?: throw IOException("Real-Debrid no respondió")
        val code = o.s("user_code"); val device = o.s("device_code")
        if (code.isBlank() || device.isBlank()) throw IOException("Real-Debrid no dio un código")
        return DeviceCode(
            userCode = code,
            verifyUrl = o.s("verification_url").ifBlank { "https://real-debrid.com/device" },
            secret = device,
            intervalSec = o.i("interval").coerceIn(3, 15),
            expiresAt = System.currentTimeMillis() + o.i("expires_in").coerceAtLeast(120) * 1000L,
        )
    }

    override suspend fun pollDevice(code: DeviceCode): DebridAccount? {
        // Hasta que el usuario autoriza, esta petición devuelve un error: es lo esperado
        val cred = api.get("$OAUTH/device/credentials", query = listOf("client_id" to OPEN_CLIENT, "code" to code.secret)).obj ?: return null
        val id = cred.s("client_id"); val secret = cred.s("client_secret")
        if (id.isBlank() || secret.isBlank()) return null
        val acc = token(id, secret, code.secret) ?: throw IOException("Real-Debrid no entregó el acceso")
        return check(acc)
    }

    private suspend fun token(clientId: String, secret: String, code: String): DebridAccount? {
        val o = api.post(
            "$OAUTH/token",
            form = listOf("client_id" to clientId, "client_secret" to secret, "code" to code, "grant_type" to "http://oauth.net/grant_type/device/1.0"),
        ).obj ?: return null
        val access = o.s("access_token").ifBlank { return null }
        return DebridAccount(
            kind = kind, token = access, refresh = o.s("refresh_token"), clientId = clientId, clientSecret = secret,
            tokenExpires = System.currentTimeMillis() + o.l("expires_in").coerceAtLeast(60) * 1000L,
        )
    }

    override suspend fun fresh(account: DebridAccount): DebridAccount {
        // Con la clave privada de la web (sin renovación) el token no caduca
        if (account.refresh.isBlank() || account.tokenExpires - System.currentTimeMillis() > 5 * 60_000L) return account
        val t = token(account.clientId, account.clientSecret, account.refresh) ?: throw DebridAuthException(kind.label)
        return t.copy(user = account.user, premium = account.premium, premiumUntil = account.premiumUntil)
    }

    override suspend fun check(account: DebridAccount): DebridAccount {
        val r = api.get("$REST/user", account.token)
        if (r.code == 401 || r.code == 403) throw DebridAuthException(kind.label)
        val o = r.obj?.takeIf { r.ok } ?: throw IOException("Real-Debrid no respondió")
        return account.copy(
            user = o.s("username"),
            premium = o.s("type") == "premium",
            premiumUntil = parseIsoDate(o.s("expiration")),
        )
    }

    override suspend fun resolve(account: DebridAccount, req: TorrentRequest): String {
        val t = account.token
        val added = api.post("$REST/torrents/addMagnet", t, listOf("magnet" to req.magnet))
        if (added.code == 401) throw DebridAuthException(kind.label)
        val id = added.obj?.s("id")?.ifBlank { null } ?: throw IOException(errorOf(added, "Real-Debrid no aceptó el torrent"))
        var keep = false
        try {
            // Recién añadido puede estar unos instantes convirtiendo el magnet
            var info = info(t, id)
            var tries = 0
            while (info.s("status") == "magnet_conversion" && tries++ < 6) { delay(700); info = info(t, id) }
            if (info.s("status") in DEAD) throw IOException("Real-Debrid no puede usar este torrent")
            val files = info["files"].asArr().mapNotNull { it.asObj() }.map {
                // Los identificadores de archivo de Real-Debrid empiezan en 1 y siguen el orden del torrent
                TorrentFile(index = it.i("id") - 1, path = it.s("path"), size = it.l("bytes"), ref = it.s("id"))
            }
            val file = pickFile(files, req, indexReliable = true) ?: throw IOException("El torrent no contiene el vídeo que buscas")
            if (info.s("status") == "waiting_files_selection") {
                api.post("$REST/torrents/selectFiles/$id", t, listOf("files" to file.ref))
                info = info(t, id)
                tries = 0
                // Si está en caché pasa a "downloaded" casi al momento
                while (info.s("status") in setOf("queued", "waiting_files_selection") && tries++ < 3) { delay(600); info = info(t, id) }
            }
            if (info.s("status") != "downloaded") throw NotCachedException(kind.label)
            val link = info["links"].asArr().firstNotNullOfOrNull { (it as? JsonPrimitive)?.content?.takeIf { l -> l.startsWith("http") } }
                ?: throw IOException("Real-Debrid no dio el enlace del archivo")
            val un = api.post("$REST/unrestrict/link", t, listOf("link" to link))
            val url = un.obj?.s("download")?.takeIf { it.startsWith("http") } ?: throw IOException(errorOf(un, "Real-Debrid no generó el enlace"))
            keep = true
            return url
        } finally {
            // Lo que no se va a ver no se queda ocupando sitio en la cuenta
            if (!keep) withContext(kotlinx.coroutines.NonCancellable) { try { api.delete("$REST/torrents/delete/$id", t) } catch (_: Exception) { } }
        }
    }

    private suspend fun info(token: String, id: String): JsonObject =
        api.get("$REST/torrents/info/$id", token).obj ?: throw IOException("Real-Debrid no respondió")

    private fun errorOf(r: Api.Reply, fallback: String): String = when (r.obj?.i("error_code")) {
        8, 9 -> "Real-Debrid no reconoce la cuenta. Vuelve a conectarla en Ajustes"
        33 -> "Real-Debrid ya tiene este torrent activo en tu cuenta"
        5, 34 -> "Real-Debrid pide ir más despacio. Prueba en un minuto"
        else -> if (r.code == 403) "Tu cuenta de Real-Debrid no es premium o está bloqueada" else fallback
    }

    companion object {
        private const val REST = "https://api.real-debrid.com/rest/1.0"
        private const val OAUTH = "https://api.real-debrid.com/oauth/v2"
        /** Identificador público que Real-Debrid ofrece a las aplicaciones de código abierto. */
        private const val OPEN_CLIENT = "X245A4XAIBGVM"
        private val DEAD = setOf("magnet_error", "error", "virus", "dead")
    }
}

// ---------------- AllDebrid ----------------

class AllDebrid(http: OkHttpClient) : DebridService {
    override val kind = DebridKind.ALL_DEBRID
    override val deviceCode = true
    private val api = Api(http, kind.label)

    private fun data(r: Api.Reply, fallback: String): JsonObject {
        val o = r.obj ?: throw IOException(fallback)
        if (o.s("status") != "success") {
            val code = o["error"].asObj()?.s("code").orEmpty()
            throw when {
                code.startsWith("AUTH") -> DebridAuthException(kind.label)
                code == "MAGNET_MUST_BE_PREMIUM" || code == "MUST_BE_PREMIUM" -> IOException("Tu cuenta de AllDebrid no es premium")
                code == "MAGNET_TOO_MANY_ACTIVE" || code == "MAGNET_TOO_MANY" -> IOException("AllDebrid tiene demasiados torrents activos en tu cuenta")
                else -> IOException(fallback)
            }
        }
        return o["data"].asObj() ?: throw IOException(fallback)
    }

    override suspend fun startDevice(): DeviceCode {
        val d = data(api.get("$BASE/v4.1/pin/get"), "AllDebrid no dio un código")
        val pin = d.s("pin"); val check = d.s("check")
        if (pin.isBlank() || check.isBlank()) throw IOException("AllDebrid no dio un código")
        return DeviceCode(
            userCode = pin,
            verifyUrl = d.s("base_url").ifBlank { "https://alldebrid.com/pin" },
            secret = check,
            intervalSec = 5,
            expiresAt = System.currentTimeMillis() + d.i("expires_in").coerceAtLeast(120) * 1000L,
        )
    }

    override suspend fun pollDevice(code: DeviceCode): DebridAccount? {
        val d = data(api.post("$BASE/v4/pin/check", form = listOf("pin" to code.userCode, "check" to code.secret)), "El código de AllDebrid ha caducado")
        val key = d.s("apikey")
        if (!d.flag("activated") || key.isBlank()) return null
        return check(DebridAccount(kind = kind, token = key))
    }

    override suspend fun check(account: DebridAccount): DebridAccount {
        val u = data(api.get("$BASE/v4/user", account.token), "AllDebrid no respondió")["user"].asObj() ?: throw IOException("AllDebrid no respondió")
        return account.copy(user = u.s("username"), premium = u.flag("isPremium"), premiumUntil = u.l("premiumUntil") * 1000L)
    }

    override suspend fun resolve(account: DebridAccount, req: TorrentRequest): String {
        val t = account.token
        val up = data(api.post("$BASE/v4/magnet/upload", t, listOf("magnets[]" to req.magnet)), "AllDebrid no aceptó el torrent")
        val m = up["magnets"].asArr().firstOrNull().asObj() ?: throw IOException("AllDebrid no aceptó el torrent")
        if (m["error"].asObj() != null) throw IOException("AllDebrid no puede usar este torrent")
        val id = m.s("id").ifBlank { throw IOException("AllDebrid no aceptó el torrent") }
        var keep = false
        try {
            if (!m.flag("ready")) throw NotCachedException(kind.label)
            val tree = data(api.post("$BASE/v4/magnet/files", t, listOf("id[]" to id)), "AllDebrid no dio los archivos")["magnets"].asArr()
                .firstOrNull().asObj()?.get("files").asArr()
            val files = mutableListOf<TorrentFile>()
            // Árbol de carpetas: n = nombre, s = tamaño, l = enlace, e = contenido de la carpeta
            fun walk(nodes: List<JsonElement>, prefix: String) {
                for (n in nodes.mapNotNull { it.asObj() }) {
                    val path = prefix + "/" + n.s("n")
                    val link = n.s("l")
                    if (link.isNotBlank()) files += TorrentFile(files.size, path, n.l("s"), link) else walk(n["e"].asArr(), path)
                }
            }
            walk(tree, "")
            val file = pickFile(files, req, indexReliable = false) ?: throw IOException("El torrent no contiene el vídeo que buscas")
            val un = data(api.post("$BASE/v4/link/unlock", t, listOf("link" to file.ref)), "AllDebrid no generó el enlace")
            val url = un.s("link").takeIf { it.startsWith("http") } ?: throw IOException("AllDebrid todavía está preparando el archivo. Prueba otra fuente")
            keep = true
            return url
        } finally {
            if (!keep) withContext(kotlinx.coroutines.NonCancellable) { try { api.post("$BASE/v4/magnet/delete", t, listOf("id" to id)) } catch (_: Exception) { } }
        }
    }

    companion object {
        private const val BASE = "https://api.alldebrid.com"
    }
}

// ---------------- Premiumize ----------------

class Premiumize(http: OkHttpClient) : DebridService {
    override val kind = DebridKind.PREMIUMIZE
    // Su código en pantalla exige registrar la aplicación: de momento se conecta con la clave de la cuenta
    override val deviceCode = false
    private val api = Api(http, kind.label)

    override suspend fun startDevice(): DeviceCode = throw IOException("Premiumize se conecta con la clave de tu cuenta")
    override suspend fun pollDevice(code: DeviceCode): DebridAccount? = null

    override suspend fun check(account: DebridAccount): DebridAccount {
        val r = api.get("$BASE/account/info", account.token)
        val o = r.obj ?: throw IOException("Premiumize no respondió")
        if (r.code == 401 || o.s("status") != "success") throw DebridAuthException(kind.label)
        val until = o.l("premium_until") * 1000L
        return account.copy(user = o.s("customer_id"), premium = until > System.currentTimeMillis(), premiumUntil = until)
    }

    override suspend fun resolve(account: DebridAccount, req: TorrentRequest): String {
        val r = api.post("$BASE/transfer/directdl", account.token, listOf("src" to req.magnet))
        if (r.code == 401) throw DebridAuthException(kind.label)
        val o = r.obj ?: throw IOException("Premiumize no respondió")
        // Solo entrega enlaces directos de lo que ya tiene en caché
        if (o.s("status") != "success") {
            val msg = o.s("message")
            throw if (msg.contains("premium", true)) IOException("Tu cuenta de Premiumize no es premium") else NotCachedException(kind.label)
        }
        val files = o["content"].asArr().mapNotNull { it.asObj() }.mapIndexed { i, f -> TorrentFile(i, f.s("path"), f.l("size"), f.s("link")) }
        val file = pickFile(files, req, indexReliable = false) ?: throw IOException("El torrent no contiene el vídeo que buscas")
        return file.ref.takeIf { it.startsWith("http") } ?: throw IOException("Premiumize no dio el enlace del archivo")
    }

    companion object {
        private const val BASE = "https://www.premiumize.me/api"
    }
}

/** "2026-12-31T23:59:59.000Z" → milisegundos; 0 si no se entiende. */
internal fun parseIsoDate(s: String): Long = try {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
    f.timeZone = java.util.TimeZone.getTimeZone("UTC")
    f.parse(s.take(19))?.time ?: 0L
} catch (e: Exception) {
    0L
}
