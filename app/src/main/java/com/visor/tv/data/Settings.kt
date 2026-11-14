package com.visor.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val label: String) { OLED("Negro OLED"), LIGHT("Claro") }
enum class Engine(val label: String) { EXO("ExoPlayer"), VLC("VLC") }
/** Cómo encaja la imagen en la pantalla: entera con bandas, estirada o recortada para llenar. */
enum class Aspect(val label: String) { FIT("Ajustar"), FILL("Rellenar"), ZOOM("Zoom") }
enum class LiveFormat(val label: String, val ext: String) { TS("MPEG-TS", "ts"), HLS("HLS", "m3u8") }
enum class ArtSource(val label: String) { PROVIDER("Proveedor"), TMDB("TMDB") }
enum class AudioLang(val label: String, val code: String?) { ES("Español", "es"), EN("Inglés", "en"), ORIGINAL("Original", null) }
enum class RefreshEvery(val label: String, val hours: Int) { OFF("Nunca", 0), H6("Cada 6 horas", 6), H12("Cada 12 horas", 12), H24("Cada día", 24) }
enum class SortBy(val label: String) {
    NEWEST("Añadidas recientemente"), OLDEST("Añadidas hace más tiempo"),
    YEAR_DESC("Año: más nuevas"), YEAR_ASC("Año: más antiguas"),
    AZ("Alfabético A–Z"), ZA("Alfabético Z–A"),
}
/** Sección Descubrir: calidad máxima de las fuentes que se eligen solas. */
enum class MaxQuality(val label: String, val height: Int) { UHD("4K", 2160), FHD("1080p", 1080), HD("720p", 720) }
/** Sección Descubrir: tamaño máximo de archivo. */
enum class MaxSize(val label: String, val gb: Int) { ANY("Sin límite", 0), G40("40 GB", 40), G20("20 GB", 20), G10("10 GB", 10), G5("5 GB", 5), G2("2 GB", 2) }
enum class SubsDefault(val label: String, val code: String?) { OFF("Desactivados", null), ES("Español", "es"), EN("Inglés", "en") }

/** Ajustes no sensibles. Son estado de Compose, así que la interfaz se actualiza al cambiarlos. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> load(key: String, def: T): T =
        try { enumValueOf<T>(p.getString(key, def.name)!!) } catch (e: Exception) { def }

    var theme by mutableStateOf(load("theme", ThemeMode.OLED))
        private set
    /** Reproductor para la TV en directo. */
    var engine by mutableStateOf(load("engine", Engine.EXO))
        private set
    /** Reproductor para películas, series y grabaciones. Si nunca se ha elegido, empieza igual que el de directo. */
    var vodEngine by mutableStateOf(load("vodEngine", engine))
        private set
    var aspect by mutableStateOf(load("aspect", Aspect.FIT))
        private set
    var liveFormat by mutableStateOf(load("liveFormat", LiveFormat.TS))
        private set
    var artSource by mutableStateOf(load("art", ArtSource.PROVIDER))
        private set
    var audioLang by mutableStateOf(load("audio", AudioLang.ES))
        private set
    var subsDefault by mutableStateOf(load("subs", SubsDefault.OFF))
        private set
    var storageIndex by mutableStateOf(p.getInt("storage", 0))
        private set
    var marginBefore by mutableStateOf(p.getInt("mBefore", 2))
        private set
    var marginAfter by mutableStateOf(p.getInt("mAfter", 5))
        private set
    var pinOnStart by mutableStateOf(p.getBoolean("pinStart", false))
        private set
    var refreshEvery by mutableStateOf(load("refresh", RefreshEvery.H12))
        private set

    // ---- Descubrir: cómo se elige la fuente ----
    var maxQuality by mutableStateOf(load("dMaxQuality", MaxQuality.UHD))
        private set
    var maxSize by mutableStateOf(load("dMaxSize", MaxSize.ANY))
        private set
    var spanishFirst by mutableStateOf(p.getBoolean("dSpanish", true))
        private set
    var hideCam by mutableStateOf(p.getBoolean("dHideCam", true))
        private set
    /** OK en "Ver" elige sola la mejor fuente; si no, siempre se pregunta. */
    var autoSource by mutableStateOf(p.getBoolean("dAuto", true))
        private set
    fun updateMaxQuality(v: MaxQuality) { maxQuality = v; p.edit().putString("dMaxQuality", v.name).apply() }
    fun updateMaxSize(v: MaxSize) { maxSize = v; p.edit().putString("dMaxSize", v.name).apply() }
    fun updateSpanishFirst(v: Boolean) { spanishFirst = v; p.edit().putBoolean("dSpanish", v).apply() }
    fun updateHideCam(v: Boolean) { hideCam = v; p.edit().putBoolean("dHideCam", v).apply() }
    fun updateAutoSource(v: Boolean) { autoSource = v; p.edit().putBoolean("dAuto", v).apply() }

    var vodSort by mutableStateOf(load("vodSort", SortBy.NEWEST))
        private set
    var seriesSort by mutableStateOf(load("seriesSort", SortBy.NEWEST))
        private set
    fun updateVodSort(v: SortBy) { vodSort = v; p.edit().putString("vodSort", v.name).apply() }
    fun updateSeriesSort(v: SortBy) { seriesSort = v; p.edit().putString("seriesSort", v.name).apply() }

    fun updateRefresh(v: RefreshEvery) { refreshEvery = v; p.edit().putString("refresh", v.name).apply() }

    fun updateTheme(v: ThemeMode) { theme = v; p.edit().putString("theme", v.name).apply() }
    fun updateEngine(v: Engine) { engine = v; p.edit().putString("engine", v.name).apply() }
    fun updateAspect(v: Aspect) { aspect = v; p.edit().putString("aspect", v.name).apply() }
    fun updateVodEngine(v: Engine) { vodEngine = v; p.edit().putString("vodEngine", v.name).apply() }
    fun engineFor(kind: String): Engine = if (kind == "live") engine else vodEngine
    fun updateLiveFormat(v: LiveFormat) { liveFormat = v; p.edit().putString("liveFormat", v.name).apply() }
    fun updateArt(v: ArtSource) { artSource = v; p.edit().putString("art", v.name).apply() }
    fun updateAudio(v: AudioLang) { audioLang = v; p.edit().putString("audio", v.name).apply() }
    fun updateSubs(v: SubsDefault) { subsDefault = v; p.edit().putString("subs", v.name).apply() }
    fun updateStorage(v: Int) { storageIndex = v; p.edit().putInt("storage", v).apply() }
    fun updateMargins(before: Int, after: Int) {
        marginBefore = before.coerceIn(0, 30); marginAfter = after.coerceIn(0, 60)
        p.edit().putInt("mBefore", marginBefore).putInt("mAfter", marginAfter).apply()
    }
    fun updatePinOnStart(v: Boolean) { pinOnStart = v; p.edit().putBoolean("pinStart", v).apply() }
}

/** Devuelve el siguiente valor de un enum, en bucle. Útil para ajustes que se cambian con OK. */
inline fun <reified T : Enum<T>> T.next(): T {
    val all = enumValues<T>()
    return all[(ordinal + 1) % all.size]
}
