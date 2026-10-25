package com.visor.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val label: String) { LIGHT("Claro"), OLED("Negro OLED") }
enum class Engine(val label: String) { EXO("ExoPlayer"), VLC("VLC") }
enum class LiveFormat(val label: String, val ext: String) { TS("MPEG-TS", "ts"), HLS("HLS", "m3u8") }
enum class ArtSource(val label: String) { PROVIDER("Proveedor"), TMDB("TMDB") }
enum class AudioLang(val label: String, val code: String?) { ES("Español", "es"), EN("Inglés", "en"), ORIGINAL("Original", null) }
enum class SubsDefault(val label: String, val code: String?) { OFF("Desactivados", null), ES("Español", "es"), EN("Inglés", "en") }

/** Ajustes no sensibles. Son estado de Compose, así que la interfaz se actualiza al cambiarlos. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> load(key: String, def: T): T =
        try { enumValueOf<T>(p.getString(key, def.name)!!) } catch (e: Exception) { def }

    var theme by mutableStateOf(load("theme", ThemeMode.LIGHT))
        private set
    var engine by mutableStateOf(load("engine", Engine.EXO))
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

    fun updateTheme(v: ThemeMode) { theme = v; p.edit().putString("theme", v.name).apply() }
    fun updateEngine(v: Engine) { engine = v; p.edit().putString("engine", v.name).apply() }
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
