package com.visor.tv.data

import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Una cámara de casa. [url] es la dirección del vídeo (normalmente rtsp://…, por ejemplo la que da
 * go2rtc para las cámaras Xiaomi). [gridUrl] es opcional: una versión en baja calidad para el mosaico,
 * que pesa menos cuando se ven varias a la vez.
 */
@Serializable
data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val gridUrl: String = "",
) {
    val mosaicUrl: String get() = gridUrl.ifBlank { url }
}

/** Lista de cámaras, guardada cifrada en el propio aparato. */
class Cameras(private val vault: SecureStore) {
    private val json = Json { ignoreUnknownKeys = true }
    val items = mutableStateListOf<Camera>()

    init {
        try {
            vault.loadCameras()?.let { items.addAll(json.decodeFromString<List<Camera>>(it)) }
        } catch (_: Exception) { }
    }

    fun upsert(c: Camera) {
        val i = items.indexOfFirst { it.id == c.id }
        if (i >= 0) items[i] = c else items.add(c)
        save()
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        save()
    }

    /** Mueve una cámara una posición (-1 antes, +1 después) para ordenar el mosaico. */
    fun move(id: String, delta: Int) {
        val i = items.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in items.indices) return
        val c = items.removeAt(i)
        items.add(j, c)
        save()
    }

    private fun save() {
        try { vault.saveCameras(if (items.isEmpty()) null else json.encodeToString(items.toList())) } catch (_: Exception) { }
    }
}
