package com.utn.abrigate.data.remote

import com.google.gson.annotations.SerializedName

data class WeatherResponseDto(
    @SerializedName("name") val ciudad: String,
    @SerializedName("main") val main: MainDto,
    @SerializedName("weather") val weather: List<DescripcionDto>,
    @SerializedName("wind") val wind: WindDto
)

data class MainDto(
    @SerializedName("temp") val temp: Double,
    @SerializedName("feels_like") val sensacion: Double,
    @SerializedName("humidity") val humedad: Int
)

data class DescripcionDto(@SerializedName("description") val texto: String)
data class WindDto(@SerializedName("speed") val velocidad: Double)
