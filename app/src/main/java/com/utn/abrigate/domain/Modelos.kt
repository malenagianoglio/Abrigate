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

interface RecomendacionRepository {
    suspend fun generar(clima: Clima): Result<Recomendacion>
}
