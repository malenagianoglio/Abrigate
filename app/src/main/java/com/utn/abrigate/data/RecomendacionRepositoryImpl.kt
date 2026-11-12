package com.utn.abrigate.data

import android.util.Log
import com.utn.abrigate.data.remote.AbrigateApiClient
import com.utn.abrigate.data.remote.RecomendacionPedido
import com.utn.abrigate.domain.Clima
import com.utn.abrigate.domain.Recomendacion
import com.utn.abrigate.domain.RecomendacionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pide la recomendación a abrigate-api, que llama a Gemini con una clave de un
 * proyecto sin facturación (nivel gratuito). Antes la app llamaba a Gemini directo
 * con Firebase AI Logic, pero como abrigate-fed6f tiene facturación vinculada,
 * Google lo cobraba con créditos prepagos.
 */
class RecomendacionRepositoryImpl : RecomendacionRepository {

    override suspend fun generar(clima: Clima): Result<Recomendacion> =
        withContext(Dispatchers.IO) {
            val api = AbrigateApiClient.api
                ?: return@withContext Result.failure(
                    IllegalStateException("Falta configurar ABRIGATE_API_URL en local.properties")
                )
            try {
                val dto = api.recomendar(AuthManager.idToken(), RecomendacionPedido(clima))
                val outfit = dto.outfit ?: error("Respuesta sin outfit")
                Result.success(
                    Recomendacion(
                        outfit = outfit,
                        actividad = dto.actividad.orEmpty(),
                        tip = dto.tip.orEmpty()
                    )
                )
            } catch (e: Exception) {
                Log.e("Abrigate", "Falló la recomendación", e)
                Result.failure(e)
            }
        }
}
