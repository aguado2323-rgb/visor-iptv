package com.visor.tv.addons

// Los addons no dan la calidad, el tamaño ni el idioma en campos aparte: van escritos en el nombre y la
// descripción de cada fuente ("4k HDR", "💾 15.2 GB", "🇪🇸"…). Aquí se leen y se ordenan las fuentes.

/** Lo que se ha podido leer de una fuente. */
data class StreamInfo(
    val stream: AddonStream,
    /** Altura de imagen: 2160, 1080, 720, 480 o 0 si no se sabe. */
    val height: Int,
    val hdr: String,
    val codec: String,
    val audio: String,
    val sizeBytes: Long,
    /** Español: 3 = de España (castellano, 🇪🇸), 2 = sin decir cuál, 1 = latino, 0 = no lleva. */
    val spanishLevel: Int,
    val multi: Boolean,
    /** En la caché del debrid: empieza al instante. null = el addon no lo dice. */
    val cached: Boolean?,
    /** Grabada en un cine o copia previa (CAM, TS, screener). */
    val cam: Boolean,
) {
    val quality: String
        get() = when (height) { 2160 -> "4K"; 1080 -> "1080p"; 720 -> "720p"; 480 -> "480p"; else -> "" }

    val size: String
        get() = when {
            sizeBytes <= 0 -> ""
            sizeBytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.US, sizeBytes / 1073741824.0).replace(".0 GB", " GB")
            else -> "${sizeBytes / (1L shl 20)} MB"
        }

    /** Etiquetas cortas para la fila: 4K, HDR, HEVC, Atmos… */
    val tags: List<String> get() = listOf(quality, hdr, codec, audio).filter { it.isNotBlank() }

    val spanish: Boolean get() = spanishLevel > 0

    /** Idioma para enseñar en la lista de fuentes. */
    val language: String?
        get() = when (spanishLevel) { 3 -> "Castellano"; 2 -> "Español"; 1 -> "Latino"; else -> if (multi) "Multi" else null }
}

/** Preferencias de Ajustes para elegir fuente. */
data class StreamPrefs(
    /** Calidad máxima: fuentes por encima van al final (una tele 1080p no gana nada con 4K). */
    val maxHeight: Int = 2160,
    /** Tamaño máximo en GB; 0 = sin límite. */
    val maxSizeGb: Int = 0,
    val spanishFirst: Boolean = true,
    val hideCam: Boolean = true,
)

object StreamRanker {
    private val R2160 = Regex("""(?i)\b(2160p?|4k|uhd)\b""")
    private val R1080 = Regex("""(?i)\b(1080[pi]?|fhd|full\s?hd)\b""")
    private val R720 = Regex("""(?i)\b(720p?)\b""")
    private val R480 = Regex("""(?i)\b(480p?|576p?|360p?|sd|dvdrip)\b""")
    private val DV = Regex("""(?i)\b(dv|dovi|dolby[\s.]?vision)\b""")
    private val HDR10P = Regex("""(?i)\bhdr10(\+|plus)""")
    private val HDR = Regex("""(?i)\bhdr(10)?\b""")
    private val HEVC = Regex("""(?i)\b(hevc|[xh][\s.]?265)\b""")
    private val AVC = Regex("""(?i)\b(avc|[xh][\s.]?264)\b""")
    private val AV1 = Regex("""(?i)\bav1\b""")
    private val ATMOS = Regex("""(?i)\batmos\b""")
    private val TRUEHD = Regex("""(?i)\btrue[\s.-]?hd\b""")
    private val DTS = Regex("""(?i)\bdts(-?(hd|x|ma))?\b""")
    private val DDP = Regex("""(?i)\b(ddp|dd\+|e-?ac-?3)""")
    private val CH = Regex("""\b([57])[. ]1\b""")
    private val SIZE = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(gb|gib|mb|mib)\b""")
    private val CAM = Regex("""(?i)\b(cam|camrip|hdcam|ts|hdts|telesync|tc|hdtc|telecine|scr|screener|dvdscr)\b""")
    // Español de España, latino y "español" a secas, por separado: el de España va primero
    private val SPAIN = Regex("""(?i)(🇪🇸|\b(castellano|cast|es-es|spain|españa|spanish\s*\(spain\)|spanish\s*\(es\))\b)""")
    private val LATIN = Regex("""(?i)(🇲🇽|🇦🇷|🇨🇴|🇨🇱|🇵🇪|🇻🇪|\b(latino|latin|lat|latam|es-mx|es-419|mexican|mexicano)\b)""")
    private val SPANISH = Regex("""(?i)\b(spanish|espa[ñn]ol|spa|esp)\b""")
    private val MULTI = Regex("""(?i)\b(multi|dual)\b""")
    // "[RD+]" o "⚡" = en caché; "[RD download]" o "⏳" = habría que descargarlo antes
    private val CACHED = Regex("""(\[[A-Z]{2,3}\+]|⚡|\binstant\b)""")
    private val UNCACHED = Regex("""(?i)(\[[A-Z]{2,3}\s*download]|⏳|⬇️|\buncached\b)""")
    private val EPISODE = listOf(
        Regex("""(?i)s(\d{1,2})[\s._-]*e(\d{1,3})"""),
        Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b"""),
    )
    private val EXT = Regex("""\.[A-Za-z0-9]{2,4}$""")
    private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "m4v", "mov", "ts", "m2ts", "wmv", "webm", "mpg", "mpeg")

    fun parse(s: AddonStream): StreamInfo {
        val text = listOf(s.name, s.description, s.filename, s.bingeGroup).joinToString("\n")
        val height = when {
            R2160.containsMatchIn(text) -> 2160
            R1080.containsMatchIn(text) -> 1080
            R720.containsMatchIn(text) -> 720
            R480.containsMatchIn(text) -> 480
            else -> 0
        }
        val hdr = when {
            DV.containsMatchIn(text) -> "Dolby Vision"
            HDR10P.containsMatchIn(text) -> "HDR10+"
            HDR.containsMatchIn(text) -> "HDR"
            else -> ""
        }
        val codec = when {
            AV1.containsMatchIn(text) -> "AV1"
            HEVC.containsMatchIn(text) -> "HEVC"
            AVC.containsMatchIn(text) -> "H.264"
            else -> ""
        }
        val channels = CH.find(text)?.value?.replace(' ', '.').orEmpty()
        val audio = when {
            ATMOS.containsMatchIn(text) -> "Atmos"
            TRUEHD.containsMatchIn(text) -> "TrueHD"
            DTS.containsMatchIn(text) -> "DTS"
            DDP.containsMatchIn(text) -> listOf("DD+", channels).filter { it.isNotBlank() }.joinToString(" ")
            else -> channels
        }
        val size = if (s.videoSize > 0) s.videoSize else SIZE.findAll(text).map { m ->
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
            (n * if (m.groupValues[2].startsWith("g", true)) 1073741824.0 else 1048576.0).toLong()
        }.maxOrNull() ?: 0L
        val cached = when {
            UNCACHED.containsMatchIn(text) -> false
            CACHED.containsMatchIn(text) -> true
            else -> null
        }
        return StreamInfo(
            stream = s, height = height, hdr = hdr, codec = codec, audio = audio, sizeBytes = size,
            spanishLevel = spanishLevel(text), multi = MULTI.containsMatchIn(text),
            cached = cached,
            // La extensión ".ts" de un archivo no es un "telesync": se quita antes de mirar
            cam = CAM.containsMatchIn(s.filename.ifBlank { s.description.lineSequence().firstOrNull().orEmpty() }.replace(EXT, "")),
        )
    }

    /** Una versión con audio de España y latino a la vez cuenta como de España. */
    fun spanishLevel(text: String): Int = when {
        SPAIN.containsMatchIn(text) -> 3
        LATIN.containsMatchIn(text) -> 1
        SPANISH.containsMatchIn(text) -> 2
        else -> 0
    }

    /**
     * Ordena de mejor a peor según las preferencias. Con [bingeGroup], la fuente del mismo grupo que la que
     * se estaba viendo va la primera (misma calidad y versión en el siguiente episodio).
     * [playable] dice si una fuente se puede reproducir ahora (las de torrent necesitan una cuenta debrid).
     */
    fun rank(streams: List<AddonStream>, prefs: StreamPrefs, bingeGroup: String = "", playable: (AddonStream) -> Boolean = { true }): List<StreamInfo> {
        val maxBytes = if (prefs.maxSizeGb > 0) prefs.maxSizeGb * 1073741824L else Long.MAX_VALUE
        return streams.distinctBy { it.id }.map(::parse)
            .filter { !(prefs.hideCam && it.cam) }
            .sortedWith(
                compareByDescending<StreamInfo> { playable(it.stream) }
                    .thenByDescending { bingeGroup.isNotBlank() && it.stream.bingeGroup == bingeGroup }
                    // Lo que no está en caché obliga a esperar la descarga: siempre después
                    .thenByDescending { it.cached != false }
                    .thenByDescending { it.height <= prefs.maxHeight && it.sizeBytes <= maxBytes }
                    // Español de España, luego español sin especificar, luego latino
                    .thenByDescending { if (prefs.spanishFirst) it.spanishLevel else 0 }
                    .thenByDescending { if (it.height <= prefs.maxHeight) it.height else -it.height }
                    .thenByDescending { prefs.spanishFirst && it.multi }
                    .thenByDescending { it.cached == true }
                    .thenByDescending { it.hdr.isNotBlank() }
                    .thenByDescending { it.sizeBytes }
            )
    }

    fun isVideo(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    /** ¿Es este archivo el episodio [season]x[episode]? */
    fun matchesEpisode(name: String, season: Int, episode: Int): Boolean {
        val base = name.substringAfterLast('/')
        return EPISODE.any { r -> r.findAll(base).any { m -> m.groupValues[1].toIntOrNull() == season && m.groupValues[2].toIntOrNull() == episode } }
    }
}
