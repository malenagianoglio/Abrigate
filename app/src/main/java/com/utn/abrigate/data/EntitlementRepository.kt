package com.utn.abrigate.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Recuerda si el usuario es Plus y hasta cuándo, para no mostrar un anuncio
 * mientras la app espera la respuesta del backend al abrir.
 * La fuente de verdad sigue siendo el backend: esto es solo una caché.
 */
class EntitlementRepository(context: Context) {

    private val prefs = context.getSharedPreferences("plus", Context.MODE_PRIVATE)

    private val _esPlus = MutableStateFlow(leer())
    val esPlus: StateFlow<Boolean> = _esPlus.asStateFlow()

    private fun leer(): Boolean {
        if (!prefs.getBoolean("activa", false)) return false
        val vence = prefs.getLong("vence", 0L)
        return vence == 0L || vence > System.currentTimeMillis()
    }

    fun guardar(activa: Boolean, venceIso: String?) {
        val vence = venceIso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
        prefs.edit().putBoolean("activa", activa).putLong("vence", vence).apply()
        _esPlus.value = leer()
    }
}
