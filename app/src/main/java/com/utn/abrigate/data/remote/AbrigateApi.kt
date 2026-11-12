package com.utn.abrigate.data.remote

import com.utn.abrigate.BuildConfig
import com.utn.abrigate.domain.Clima
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/** Endpoints de abrigate-api (Cloud Run): recomendación y suscripción Plus. */
interface AbrigateApi {

    @POST("recomendacion")
    suspend fun recomendar(
        @Header("X-Firebase-Auth") tokenFirebase: String,
        @Body pedido: RecomendacionPedido
    ): RecomendacionDto

    @POST("suscripcion/verificar")
    suspend fun verificar(
        @Header("X-Firebase-Auth") tokenFirebase: String,
        @Body pedido: VerificarPedido
    ): EstadoSuscripcionDto

    @GET("suscripcion/estado")
    suspend fun estado(
        @Header("X-Firebase-Auth") tokenFirebase: String
    ): EstadoSuscripcionDto
}

data class RecomendacionPedido(val clima: Clima)

data class RecomendacionDto(
    val outfit: String?,
    val actividad: String?,
    val tip: String?
)

data class VerificarPedido(val purchaseToken: String)

data class EstadoSuscripcionDto(
    val plus: Boolean,
    val plan: String?,
    val vence: String?
)

object AbrigateApiClient {

    /** null si no se configuró ABRIGATE_API_URL en local.properties. */
    val api: AbrigateApi? by lazy {
        val url = BuildConfig.ABRIGATE_API_URL
        if (url.isBlank()) return@lazy null
        // 30 s: el backend reintenta a Gemini si está saturado, y el arranque en
        // frío de Cloud Run suma unos segundos. El valor por defecto (10 s) cortaba antes.
        val http = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl(if (url.endsWith("/")) url else "$url/")
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AbrigateApi::class.java)
    }
}
