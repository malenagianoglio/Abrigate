package com.utn.abrigate.retencion

import android.content.Context
import com.utn.abrigate.domain.PerfilUsuario

/**
 * TP6: perfil del usuario y preferencias de los avisos. Todo queda en el teléfono;
 * al backend solo viaja el perfil junto con cada pedido de recomendación, sin
 * nombre ni correo (la sesión es anónima).
 */
class PerfilRepository(context: Context) {

    private val prefs = context.getSharedPreferences("perfil", Context.MODE_PRIVATE)

    // --- Onboarding de dos preguntas ---

    val onboardingVisto: Boolean get() = prefs.getBoolean("onboarding_visto", false)

    /** sensibilidad: "friolento", "normal" o "caluroso"; transporte: "a pie", "bici", "auto" o "colectivo". */
    fun guardarPerfil(sensibilidad: String?, transporte: String?) {
        prefs.edit()
            .putString("sensibilidad", sensibilidad)
            .putString("transporte", transporte)
            .putBoolean("onboarding_visto", true)
            .apply()
    }

    fun saltearOnboarding() = prefs.edit().putBoolean("onboarding_visto", true).apply()

    val sensibilidad: String? get() = prefs.getString("sensibilidad", null)
    val transporte: String? get() = prefs.getString("transporte", null)

    fun perfil() = PerfilUsuario(
        sensibilidad = sensibilidad?.takeIf { it != "normal" },
        transporte = transporte,
        ajuste = ajuste()
    )

    // --- Aprender de las valoraciones ---

    /**
     * Cuenta las valoraciones con frío (< 15 °C) y con calor (> 25 °C). Si con frío
     * el usuario marca más veces «no me sirvió» que «me sirvió» (y al menos 3),
     * suponemos que la recomendación le queda corta de abrigo, y al revés con calor.
     */
    fun registrarValoracion(util: Boolean, temperatura: Int) {
        val rango = when {
            temperatura < 15 -> "frio"
            temperatura > 25 -> "calor"
            else -> return
        }
        val clave = "${rango}_${if (util) "util" else "no_util"}"
        prefs.edit().putInt(clave, prefs.getInt(clave, 0) + 1).apply()
    }

    private fun ajuste(): String? {
        fun quejas(rango: String): Boolean {
            val no = prefs.getInt("${rango}_no_util", 0)
            return no >= 3 && no > prefs.getInt("${rango}_util", 0)
        }
        return when {
            quejas("frio") -> "mas_abrigo"
            quejas("calor") -> "menos_abrigo"
            else -> null
        }
    }

    // --- Última ubicación consultada (la usa el aviso de la mañana) ---

    fun guardarUbicacion(lat: Double, lon: Double, ciudad: String) {
        prefs.edit()
            .putString("lat", lat.toString())
            .putString("lon", lon.toString())
            .putString("ciudad", ciudad)
            .apply()
    }

    fun ultimaUbicacion(): Pair<Double, Double>? {
        val lat = prefs.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString("lon", null)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    val ciudad: String? get() = prefs.getString("ciudad", null)

    // --- Avisos ---

    /** Minutos desde la medianoche. Por defecto, 7:30. */
    var horaAviso: Int
        get() = prefs.getInt("hora_aviso", 7 * 60 + 30)
        set(v) = prefs.edit().putInt("hora_aviso", v).apply()

    /** true cuando el usuario aceptó los avisos desde la explicación previa. */
    var avisosActivados: Boolean
        get() = prefs.getBoolean("avisos_activados", false)
        set(v) = prefs.edit().putBoolean("avisos_activados", v).apply()

    /** Se pregunta una vez; si el usuario elige «Ahora no», se vuelve a ofrecer a los 7 días. */
    fun debePreguntarAvisos(ahora: Long = System.currentTimeMillis()): Boolean =
        !avisosActivados && ahora >= prefs.getLong("avisos_pospuestos_hasta", 0L)

    fun posponerAvisos(dias: Int, ahora: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("avisos_pospuestos_hasta", ahora + dias * 24L * 60 * 60 * 1000).apply()
    }

    // --- Pedido de reseña ---

    fun puedePedirResena(ahora: Long = System.currentTimeMillis()): Boolean =
        ahora - prefs.getLong("resena_pedida", 0L) > 30L * 24 * 60 * 60 * 1000

    fun marcarResenaPedida(ahora: Long = System.currentTimeMillis()) =
        prefs.edit().putLong("resena_pedida", ahora).apply()
}
