package com.utn.abrigate.retencion

import android.content.Context
import java.time.LocalDate

/** TP6: insignias de la gamificación liviana. */
enum class Insignia(val id: String, val nombre: String, val descripcion: String) {
    PREVISOR("previsor", "Previsor/a", "7 días seguidos de racha"),
    PRIMERA_HELADA("primera_helada", "Primera helada", "Consultaste un día bajo cero"),
    TERMOMETRO("termometro_humano", "Termómetro humano", "Valoraste 10 recomendaciones"),
    TROTAMUNDOS("trotamundos", "Trotamundos", "Consultaste el clima en 3 ciudades");

    companion object {
        fun deId(id: String) = entries.firstOrNull { it.id == id }
    }
}

data class EstadoRacha(val dias: Int, val diasDeUso: Int, val insignias: List<Insignia>)

/**
 * TP6: racha de días seguidos consultando la recomendación, e insignias.
 *
 * Para que no genere culpa, si el usuario saltea UN día la racha no se corta: se
 * «congela», como máximo una vez por semana. Se guarda solo en el teléfono.
 */
class RachaRepository(context: Context, private val hoy: () -> LocalDate = { LocalDate.now() }) {

    private val prefs = context.getSharedPreferences("racha", Context.MODE_PRIVATE)

    fun estado() = EstadoRacha(
        dias = rachaVigente(),
        diasDeUso = prefs.getInt("dias_de_uso", 0),
        insignias = obtenidas().mapNotNull { Insignia.deId(it) }
    )

    /** La racha que se muestra: si pasaron más de 2 días desde la última consulta, ya se cortó. */
    private fun rachaVigente(): Int {
        val ultima = prefs.getLong("ultima", Long.MIN_VALUE)
        val dias = prefs.getInt("racha", 0)
        return if (ultima != Long.MIN_VALUE && hoy().toEpochDay() - ultima <= 2) dias else 0
    }

    /** Se llama cada vez que se muestra una recomendación. Devuelve las insignias nuevas. */
    fun registrarUso(temperatura: Int, ciudad: String): List<Insignia> {
        val dia = hoy().toEpochDay()
        val ultima = prefs.getLong("ultima", Long.MIN_VALUE)
        val congelado = prefs.getLong("congelado", Long.MIN_VALUE)
        var racha = prefs.getInt("racha", 0)
        val editor = prefs.edit()

        if (ultima != dia) {
            racha = when {
                ultima == dia - 1 -> racha + 1
                ultima == dia - 2 && (congelado == Long.MIN_VALUE || dia - congelado >= 7) -> {
                    editor.putLong("congelado", dia)
                    racha + 1
                }
                else -> 1
            }
            editor.putLong("ultima", dia)
                .putInt("racha", racha)
                .putInt("dias_de_uso", prefs.getInt("dias_de_uso", 0) + 1)
        }
        val ciudades = prefs.getStringSet("ciudades", emptySet())!! + ciudad.trim().lowercase()
        editor.putStringSet("ciudades", ciudades).apply()

        return otorgar(buildList {
            if (racha >= 7) add(Insignia.PREVISOR)
            if (temperatura < 0) add(Insignia.PRIMERA_HELADA)
            if (ciudades.size >= 3) add(Insignia.TROTAMUNDOS)
        })
    }

    /** Se llama con cada «me sirvió» / «no me sirvió». Devuelve las insignias nuevas. */
    fun registrarValoracion(): List<Insignia> {
        val total = prefs.getInt("valoraciones", 0) + 1
        prefs.edit().putInt("valoraciones", total).apply()
        return otorgar(if (total >= 10) listOf(Insignia.TERMOMETRO) else emptyList())
    }

    private fun obtenidas(): Set<String> = prefs.getStringSet("insignias", emptySet())!!

    private fun otorgar(candidatas: List<Insignia>): List<Insignia> {
        val ya = obtenidas()
        val nuevas = candidatas.filter { it.id !in ya }
        if (nuevas.isNotEmpty()) {
            prefs.edit().putStringSet("insignias", ya + nuevas.map { it.id }).apply()
        }
        return nuevas
    }
}
