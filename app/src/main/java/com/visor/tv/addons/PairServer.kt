package com.visor.tv.addons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * "Añadir desde el móvil": una página web mínima, servida por la propia tele en la red de casa, para pegar
 * la dirección de un addon o la clave de un debrid sin teclearla con el mando.
 *
 * Solo vive mientras el diálogo está abierto, solo atiende a aparatos de la red local, pide el código de
 * seis cifras que se ve en la tele y se apaga sola tras unos cuantos códigos equivocados.
 * La conexión dentro de casa va sin cifrar (http): por eso se avisa en pantalla de usarla solo en una red propia.
 */
class PairServer(
    /** Opciones del desplegable: clave interna y texto visible. */
    private val options: List<Pair<String, String>>,
    /** Recibe (opción, texto pegado) en el hilo principal y devuelve el mensaje para el móvil. Lanza si falla. */
    private val onSubmit: suspend (what: String, value: String) -> String,
) {
    val code: String = "%06d".format(SecureRandom().nextInt(1_000_000))
    private var socket: ServerSocket? = null
    @Volatile private var failures = 0
    /** Se llama (en un hilo cualquiera) cuando el servidor se cierra solo por demasiados intentos fallidos. */
    @Volatile var onLocked: (() -> Unit)? = null

    /** Dirección que hay que abrir en el móvil, o null si la tele no está en una red local. */
    fun start(): String? {
        val ip = localIp() ?: return null
        val s = try { bind(PORT) } catch (e: Exception) { try { bind(0) } catch (e2: Exception) { return null } }
        socket = s
        thread(name = "visor-pair", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                try { c.use { serve(it) } } catch (e: Exception) { }
            }
        }
        return "http://$ip" + if (s.localPort == 80) "" else ":${s.localPort}"
    }

    fun stop() { try { socket?.close() } catch (e: Exception) { } ; socket = null }

    private fun bind(port: Int) = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port), 4) }

    private fun serve(c: Socket) {
        c.soTimeout = 8000
        val from = c.inetAddress
        if (!(from.isSiteLocalAddress || from.isLoopbackAddress || from.isLinkLocalAddress)) return
        val input = c.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return
        val method = parts[0]; val path = parts[1].substringBefore('?')
        val length = lines.drop(1).firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0

        val page = when {
            method == "GET" && path == "/" -> form(null, false)
            method == "POST" && path == "/" -> {
                if (length !in 1..MAX_BODY) form("El texto es demasiado largo.", true)
                else {
                    val body = ByteArray(length)
                    var n = 0
                    while (n < length) { val r = input.read(body, n, length - n); if (r < 0) break; n += r }
                    submit(parseForm(String(body, 0, n, Charsets.UTF_8)))
                }
            }
            else -> null
        }
        val out = c.getOutputStream()
        if (page == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            val bytes = page.toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.write(bytes)
        }
        out.flush()
    }

    private fun submit(f: Map<String, String>): String {
        if (f["code"]?.trim() != code) {
            if (++failures >= MAX_FAILURES) { stop(); onLocked?.invoke(); return form("Demasiados intentos. Vuelve a abrirlo en la tele.", true) }
            return form("El código no coincide con el de la tele.", true)
        }
        val what = f["what"].orEmpty()
        val value = f["value"].orEmpty().trim()
        if (options.none { it.first == what } || value.isBlank()) return form("Falta pegar el texto.", true)
        return try {
            val msg = runBlocking { withContext(Dispatchers.Main) { onSubmit(what, value) } }
            form(msg, false)
        } catch (e: Exception) {
            form(e.message ?: "No se pudo añadir.", true)
        }
    }

    private fun form(message: String?, error: Boolean): String {
        val opts = options.joinToString("") { (k, label) -> "<option value=\"${esc(k)}\">${esc(label)}</option>" }
        val note = if (message == null) "" else "<p class=\"${if (error) "err" else "ok"}\">${esc(message)}</p>"
        return """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Visor</title>
<style>
body{font-family:system-ui,sans-serif;background:#0b0b0d;color:#f2f2f3;margin:0;padding:24px;max-width:520px;margin:auto}
h1{font-size:22px;margin:8px 0 4px}p{color:#a0a0a8;font-size:14px;line-height:1.45}
label{display:block;font-size:13px;color:#a0a0a8;margin:16px 0 6px}
input,select,textarea,button{width:100%;box-sizing:border-box;font-size:16px;padding:12px;border-radius:12px;border:1px solid #2c2c33;background:#16161a;color:#f2f2f3}
textarea{min-height:96px}button{margin-top:20px;background:#f2f2f3;color:#0b0b0d;font-weight:600;border:0}
.ok{color:#5ad28a}.err{color:#ff7a7f}
</style></head><body>
<h1>Visor</h1><p>Pega aquí la dirección del addon o la clave de tu cuenta. Se guarda cifrada en la tele.</p>
$note
<form method="post" action="/" autocomplete="off">
<label>Código que ves en la tele</label><input name="code" inputmode="numeric" maxlength="6" required>
<label>Qué vas a añadir</label><select name="what">$opts</select>
<label>Dirección o clave</label><textarea name="value" required autocapitalize="off" autocorrect="off" spellcheck="false"></textarea>
<button type="submit">Enviar a la tele</button>
</form></body></html>"""
    }

    private companion object {
        const val PORT = 8765
        const val MAX_BODY = 8192
        const val MAX_HEAD = 8192
        const val MAX_FAILURES = 5

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** Lee hasta la línea en blanco que separa las cabeceras del cuerpo. */
        fun readHead(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            var state = 0
            while (buf.size() < MAX_HEAD) {
                val b = input.read()
                if (b < 0) return null
                buf.write(b)
                state = when {
                    b == '\r'.code && (state == 0 || state == 2) -> state + 1
                    b == '\n'.code && (state == 1 || state == 3) -> state + 1
                    else -> 0
                }
                if (state == 4) return buf.toString("ISO-8859-1").trimEnd()
            }
            return null
        }

        fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else try {
                URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            } catch (e: Exception) { null }
        }.toMap()

        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
