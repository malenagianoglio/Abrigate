package com.utn.abrigate.data

import com.utn.abrigate.BuildConfig
import com.utn.abrigate.data.remote.RetrofitClient
import com.utn.abrigate.data.remote.WeatherResponseDto
import com.utn.abrigate.domain.Clima
import com.utn.abrigate.domain.ClimaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ClimaRepositoryImpl : ClimaRepository {

    override suspend fun obtenerClima(lat: Double, lon: Double): Result<Clima> =
        withContext(Dispatchers.IO) {
            try {
                val dto = RetrofitClient.api.obtenerClimaActual(
                    lat = lat,
                    lon = lon,
                    apiKey = BuildConfig.OPENWEATHER_API_KEY
                )
                Result.success(dto.aDominio())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

private fun WeatherResponseDto.aDominio() = Clima(
    ciudad = ciudad,
    temperatura = main.temp.roundToInt(),
    sensacion = main.sensacion.roundToInt(),
    descripcion = weather.firstOrNull()?.texto ?: "sin datos",
    humedad = main.humedad,
    vientoKmh = (wind.velocidad * 3.6).roundToInt()
)
