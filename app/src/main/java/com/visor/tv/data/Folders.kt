package com.visor.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

/**
 * Carpetas del proveedor a gusto del usuario: ocultas, en otro orden o bloqueadas con PIN.
 * Tipos: "live", "vod" y "series". Se guarda en las preferencias de ajustes, así entra en la copia de seguridad.
 */
class Folders(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Cambia con cada modificación, para que las pantallas se recompongan. */
    var version by mutableIntStateOf(0)
        private set

    /** Carpetas bloqueadas abiertas con el PIN en esta sesión ("tipo:id"). */
    private val unlocked = mutableStateListOf<String>()

    private fun read(key: String): List<String> = p.getString(key, "").orEmpty().split(SEP).filter { it.isNotEmpty() }
    private fun write(key: String, v: Collection<String>) {
        p.edit().putString(key, v.joinToString(SEP)).apply()
        version++
    }

    private fun hiddenKey(type: String) = "fold_hidden_$type"
    private fun lockedKey(type: String) = "fold_locked_$type"
    private fun orderKey(type: String) = "fold_order_$type"

    fun hidden(type: String): Set<String> = read(hiddenKey(type)).toSet()
    fun locked(type: String): Set<String> = read(lockedKey(type)).toSet()

    fun isHidden(type: String, id: String) = id in hidden(type)
    fun isLocked(type: String, id: String) = id in locked(type)
    fun isUnlocked(type: String, id: String) = "$type:$id" in unlocked

    /** Bloqueada y sin abrir con el PIN en esta sesión. */
    fun isBlocked(type: String, id: String) = isLocked(type, id) && !isUnlocked(type, id)

    /** Fuera de búsquedas, Novedades e Inicio: carpetas ocultas o bloqueadas sin abrir. */
    fun excludes(type: String): (String) -> Boolean {
        val h = hidden(type)
        val l = locked(type)
        if (h.isEmpty() && l.isEmpty()) return { false }
        return { id -> id in h || (id in l && "$type:$id" !in unlocked) }
    }

    /** Solo bloqueadas sin abrir (para "Seguir viendo"); null si no hay ninguna bloqueada. */
    fun excludesLocked(type: String): ((String) -> Boolean)? {
        val l = locked(type)
        if (l.isEmpty()) return null
        return { id -> id in l && "$type:$id" !in unlocked }
    }

    fun hasAnyLocked(): Boolean = TYPES.any { locked(it).isNotEmpty() }

    /** Las carpetas en el orden elegido; las que añada el proveedor después van al final, en su orden. */
    fun arrange(type: String, cats: List<Category>, includeHidden: Boolean = false): List<Category> {
        val order = read(orderKey(type))
        val pos = order.withIndex().associate { (i, id) -> id to i }
        val sorted = cats.withIndex()
            .sortedWith(compareBy({ pos[it.value.id] ?: (order.size + it.index) }, { it.index }))
            .map { it.value }
        if (includeHidden) return sorted
        val h = hidden(type)
        return if (h.isEmpty()) sorted else sorted.filter { it.id !in h }
    }

    fun setHidden(type: String, id: String, hide: Boolean) {
        val s = hidden(type).toMutableSet()
        if (hide) s += id else s -= id
        write(hiddenKey(type), s)
    }

    fun setAllHidden(type: String, all: List<Category>, hide: Boolean) {
        write(hiddenKey(type), if (hide) all.map { it.id } else emptyList())
    }

    fun setLocked(type: String, id: String, lock: Boolean) {
        val s = locked(type).toMutableSet()
        if (lock) s += id else { s -= id; unlocked.remove("$type:$id") }
        write(lockedKey(type), s)
    }

    fun unlock(type: String, id: String) {
        if ("$type:$id" !in unlocked) unlocked += "$type:$id"
        version++
    }

    /** Vuelve a cerrar todas las carpetas bloqueadas (al salir de la app). */
    fun relockAll() {
        if (unlocked.isEmpty()) return
        unlocked.clear()
        version++
    }

    /** Mueve una carpeta [delta] puestos (o al principio con [toTop]) dentro del orden completo. */
    fun move(type: String, id: String, all: List<Category>, delta: Int = 0, toTop: Boolean = false) {
        val ids = arrange(type, all, includeHidden = true).map { it.id }.toMutableList()
        val i = ids.indexOf(id)
        if (i < 0) return
        ids.removeAt(i)
        val to = if (toTop) 0 else (i + delta).coerceIn(0, ids.size)
        ids.add(to, id)
        write(orderKey(type), ids)
    }

    /** Sin PIN no se podría abrir nada: al quitar el PIN se quitan también los bloqueos. */
    fun clearLocks() {
        TYPES.forEach { write(lockedKey(it), emptyList()) }
        unlocked.clear()
    }

    fun resetOrder(type: String) = write(orderKey(type), emptyList())

    companion object {
        private const val SEP = "\u001F"
        val TYPES = listOf("live", "vod", "series")
    }
}
