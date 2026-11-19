package com.utn.abrigate.domain

data class Clima(
    val ciudad: String,
    val temperatura: Int,
    val sensacion: Int,
    val descripcion: String,
    val humedad: Int,
    val vientoKmh: Int
)

data class Recomendacion(
    val outfit: String,
    val actividad: String,
    val tip: String
)

/**
 * Un plan de la suscripción Abrigate Plus, tal como lo muestra el paywall.
 * [id] es el ID del plan base en Play Console: "semanal", "mensual" o "anual".
 */
data class PlanPlus(
    val id: String,
    val precio: String,
    val tienePrueba: Boolean
)

interface ClimaRepository {
    suspend fun obtenerClima(lat: Double, lon: Double): Result<Clima>
}

/**
 * TP6: personalización. Valores de una lista cerrada (el backend descarta otros):
 * sensibilidad "friolento" o "caluroso"; transporte "a pie", "bici", "auto" o
 * "colectivo"; ajuste "mas_abrigo" o "menos_abrigo", calculado con las valoraciones.
 */
data class PerfilUsuario(
    val sensibilidad: String?,
    val transporte: String?,
    val ajuste: String?
)

interface RecomendacionRepository {
    /** [zona]: celda de 0,1° para las alertas del TP6; no identifica al usuario. */
    suspend fun generar(clima: Clima, perfil: PerfilUsuario? = null, zona: String? = null): Result<Recomendacion>
}
