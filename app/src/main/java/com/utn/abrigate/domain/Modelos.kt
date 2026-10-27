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

interface ClimaRepository {
    suspend fun obtenerClima(lat: Double, lon: Double): Result<Clima>
}

interface RecomendacionRepository {
    suspend fun generar(clima: Clima): Result<Recomendacion>
}
