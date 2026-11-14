package com.visor.tv.addons

import com.visor.tv.data.asArr
import com.visor.tv.data.asObj
import com.visor.tv.data.i
import com.visor.tv.data.l
import com.visor.tv.data.s
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// Modelos del protocolo de addons de Stremio (el mismo que usa Nuvio). Un addon es un servidor web que
// responde JSON a /catalog, /meta, /stream y /subtitles. El JSON real es irregular (números como texto,
// campos que faltan, formas antiguas y nuevas), así que se lee a mano y con tolerancia.

/** Un catálogo que ofrece un addon: una fila de Descubrir. */
@Serializable
data class AddonCatalog(
    val type: String,
    val id: String,
    val name: String = "",
    /** Admite búsqueda por texto. */
    val search: Boolean = false,
    /** Solo responde si se le pasa algo obligatorio (búsqueda, género…): no sirve como fila fija. */
    val needsExtra: Boolean = false,
    /** Solo sirve para buscar. */
    val searchOnly: Boolean = false,
)

/** Qué sabe hacer un addon (catalog, meta, stream, subtitles) y con qué tipos e identificadores. */
@Serializable
data class AddonResource(
    val name: String,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
)

@Serializable
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String = "",
    val description: String = "",
    val logo: String = "",
    val types: List<String> = emptyList(),
    val catalogs: List<AddonCatalog> = emptyList(),
    val resources: List<AddonResource> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    /** El addon no funciona hasta configurarlo en su web (p. ej. poner la clave del debrid). */
    val configurationRequired: Boolean = false,
) {
    /** ¿Responde este addon a [resource] para un título de este [type] con este [id]? */
    fun supports(resource: String, type: String, id: String): Boolean {
        val r = resources.firstOrNull { it.name == resource } ?: return false
        val ts = r.types.ifEmpty { types }
        if (ts.isNotEmpty() && type !in ts) return false
        val prefixes = r.idPrefixes.ifEmpty { idPrefixes }
        return prefixes.isEmpty() || prefixes.any { id.startsWith(it) }
    }

    fun has(resource: String) = resources.any { it.name == resource }

    /** Resumen para la lista de addons: "Catálogos · Fuentes · Subtítulos". */
    val summary: String
        get() = listOfNotNull(
            "Catálogos".takeIf { has("catalog") && catalogs.isNotEmpty() },
            "Fichas".takeIf { has("meta") },
            "Fuentes".takeIf { has("stream") },
            "Subtítulos".takeIf { has("subtitles") },
        ).joinToString(" · ")
}

/**
 * Un addon instalado. [base] es la dirección sin "/manifest.json" y [query] lo que llevara detrás (algunos
 * addons ponen ahí un token). La dirección puede contener la clave del debrid: se guarda siempre cifrada.
 */
@Serializable
data class InstalledAddon(
    val base: String,
    val manifest: AddonManifest,
    val enabled: Boolean = true,
    val query: String = "",
) {
    val key: String get() = manifest.id.ifBlank { base }

    /** Servidor sin la ruta (que es donde va la configuración y las claves), para enseñarlo en pantalla. */
    val host: String get() = base.substringAfter("://").substringBefore('/')
}

/** Lo mínimo para pintar un póster en una fila. */
@Serializable
data class MetaPreview(
    val type: String,
    val id: String,
    val name: String,
    val poster: String = "",
    val background: String = "",
    val logo: String = "",
    val description: String = "",
    /** "2019" o "2019-2023". */
    val year: String = "",
    val rating: String = "",
    val genres: String = "",
) {
    val isSeries: Boolean get() = type == "series"
    val key: String get() = "$type/$id"
}

/** Episodio de una serie. */
data class MetaVideo(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val released: String,
    val thumbnail: String,
    val overview: String,
)

/** Ficha completa. */
data class MetaDetail(
    val preview: MetaPreview,
    val runtime: String = "",
    val cast: String = "",
    val director: String = "",
    val country: String = "",
    /** Identificador de YouTube del tráiler. */
    val trailer: String = "",
    val imdbId: String = "",
    val videos: List<MetaVideo> = emptyList(),
) {
    /** Temporadas con sus episodios en orden. Los "especiales" (temporada 0) van al final. */
    val seasons: Map<Int, List<MetaVideo>> by lazy {
        videos.filter { it.episode > 0 }.groupBy { it.season }.mapValues { (_, v) -> v.sortedBy { it.episode } }
            .toSortedMap(compareBy<Int> { if (it == 0) Int.MAX_VALUE else it })
    }

    /** Todos los episodios seguidos, para saber cuál va después de cuál. */
    val ordered: List<MetaVideo> by lazy { seasons.values.flatten() }
}

/** Una fuente para ver un título. Trae una dirección directa ([url]) o solo el torrent ([infoHash]). */
data class AddonStream(
    val addon: String,
    val name: String,
    val description: String,
    val url: String = "",
    val infoHash: String = "",
    val fileIdx: Int? = null,
    /** Fuentes con el mismo grupo son "la misma versión" de otro episodio (calidad, grupo, idioma). */
    val bingeGroup: String = "",
    val filename: String = "",
    val videoSize: Long = 0,
    val trackers: List<String> = emptyList(),
) {
    val isDirect: Boolean get() = url.isNotBlank()
    val isTorrent: Boolean get() = !isDirect && infoHash.isNotBlank()

    /** Identifica la fuente dentro de una ficha (para recordar cuál elegiste). */
    val id: String get() = if (isDirect) "u:" + url.hashCode() else "h:$infoHash:${fileIdx ?: -1}"
}

data class AddonSubtitle(val id: String, val url: String, val lang: String)

// ---------------- Lectura del JSON ----------------

private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: (s(k) == "true")

private fun JsonElement?.strings(): List<String> = when (this) {
    is JsonPrimitive -> listOfNotNull(contentOrNull?.takeIf { it.isNotBlank() })
    else -> asArr().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
}

internal fun parseManifest(o: JsonObject): AddonManifest {
    val types = o["types"].strings()
    val catalogs = o["catalogs"].asArr().mapNotNull { it.asObj() }.mapNotNull { c ->
        val type = c.s("type"); val id = c.s("id")
        if (type.isBlank() || id.isBlank()) return@mapNotNull null
        // Forma nueva: extra = [{name, isRequired}]; forma antigua: extraSupported / extraRequired
        val extra = c["extra"].asArr().mapNotNull { it.asObj() }
        val supported = extra.map { it.s("name") } + c["extraSupported"].strings()
        val required = extra.filter { it.bool("isRequired") }.map { it.s("name") } + c["extraRequired"].strings()
        AddonCatalog(
            type = type, id = id, name = c.s("name"),
            search = "search" in supported,
            needsExtra = required.isNotEmpty(),
            searchOnly = required.isNotEmpty() && required.all { it == "search" },
        )
    }
    val resources = o["resources"].asArr().mapNotNull { r ->
        when (r) {
            is JsonPrimitive -> r.contentOrNull?.takeIf { it.isNotBlank() }?.let { AddonResource(it) }
            is JsonObject -> r.s("name").takeIf { it.isNotBlank() }?.let { AddonResource(it, r["types"].strings(), r["idPrefixes"].strings()) }
            else -> null
        }
    }
    return AddonManifest(
        id = o.s("id"),
        name = o.s("name").ifBlank { o.s("id") },
        version = o.s("version"),
        description = o.s("description"),
        logo = o.s("logo"),
        types = types,
        catalogs = catalogs,
        resources = resources,
        idPrefixes = o["idPrefixes"].strings(),
        configurationRequired = o["behaviorHints"].asObj()?.bool("configurationRequired") == true,
    )
}

private val YEAR = Regex("""(19|20)\d{2}""")

internal fun parsePreview(o: JsonObject, fallbackType: String = ""): MetaPreview? {
    val id = o.s("id")
    val name = o.s("name")
    if (id.isBlank() || name.isBlank()) return null
    val release = o.s("releaseInfo").ifBlank { o.s("year") }.ifBlank { YEAR.find(o.s("released"))?.value.orEmpty() }
    return MetaPreview(
        type = o.s("type").ifBlank { fallbackType },
        id = id,
        name = name,
        poster = o.s("poster"),
        background = o.s("background"),
        logo = o.s("logo"),
        description = o.s("description"),
        year = release.replace('–', '-').trim().trimEnd('-'),
        rating = o.s("imdbRating").toDoubleOrNull()?.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.US, it) } ?: "",
        genres = (o["genres"].strings().ifEmpty { o["genre"].strings() }).take(2).joinToString(" · "),
    )
}

internal fun parseMeta(o: JsonObject, fallbackType: String): MetaDetail? {
    val p = parsePreview(o, fallbackType) ?: return null
    val videos = o["videos"].asArr().mapNotNull { it.asObj() }.mapNotNull { v ->
        val id = v.s("id")
        if (id.isBlank()) return@mapNotNull null
        MetaVideo(
            id = id,
            title = v.s("title").ifBlank { v.s("name") },
            season = v.i("season"),
            episode = v.i("episode").takeIf { it > 0 } ?: v.i("number"),
            released = v.s("released").ifBlank { v.s("firstAired") },
            thumbnail = v.s("thumbnail"),
            overview = v.s("overview").ifBlank { v.s("description") },
        )
    }
    // Reparto y dirección: campos directos o, en la forma nueva, dentro de "links"
    val links = o["links"].asArr().mapNotNull { it.asObj() }
    fun linked(cat: String) = links.filter { it.s("category").equals(cat, true) }.map { it.s("name") }.filter { it.isNotBlank() }
    val cast = o["cast"].strings().ifEmpty { linked("Cast") }
    val director = o["director"].strings().ifEmpty { linked("Directors") }
    val trailer = o["trailers"].asArr().firstNotNullOfOrNull { it.asObj()?.s("source")?.takeIf { s -> s.isNotBlank() } }
        ?: o["trailerStreams"].asArr().firstNotNullOfOrNull { it.asObj()?.s("ytId")?.takeIf { s -> s.isNotBlank() } }
    return MetaDetail(
        preview = p,
        runtime = o.s("runtime"),
        cast = cast.take(4).joinToString(", "),
        director = director.take(2).joinToString(", "),
        country = o.s("country"),
        trailer = trailer.orEmpty(),
        imdbId = o.s("imdb_id").ifBlank { p.id.substringBefore(':').takeIf { it.startsWith("tt") }.orEmpty() },
        videos = videos,
    )
}

internal fun parseStream(o: JsonObject, addon: String): AddonStream? {
    val url = o.s("url")
    val hash = o.s("infoHash").lowercase()
    // externalUrl y ytId abren otra app o YouTube: no son vídeo que Visor pueda reproducir
    if (url.isBlank() && hash.isBlank()) return null
    if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) return null
    val hints = o["behaviorHints"].asObj()
    return AddonStream(
        addon = addon,
        name = o.s("name"),
        // "title" es la forma antigua de "description"; algunos addons mandan los dos
        description = o.s("description").ifBlank { o.s("title") },
        url = url,
        infoHash = hash,
        fileIdx = (o["fileIdx"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
        bingeGroup = hints?.s("bingeGroup").orEmpty(),
        filename = hints?.s("filename").orEmpty(),
        videoSize = hints?.l("videoSize") ?: 0L,
        trackers = o["sources"].strings().filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") },
    )
}

internal fun parseSubtitle(o: JsonObject): AddonSubtitle? {
    val url = o.s("url")
    if (!url.startsWith("http")) return null
    return AddonSubtitle(id = o.s("id").ifBlank { url }, url = url, lang = o.s("lang"))
}
