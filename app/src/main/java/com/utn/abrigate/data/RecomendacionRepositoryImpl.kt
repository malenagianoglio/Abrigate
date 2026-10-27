package com.utn.abrigate.data

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.generationConfig
import com.google.gson.Gson
import com.utn.abrigate.domain.Clima
import com.utn.abrigate.domain.Recomendacion
import com.utn.abrigate.domain.RecomendacionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecomendacionRepositoryImpl : RecomendacionRepository {

    private val modelo = Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.8f
                responseMimeType = "application/json"
            }
        )

    override suspend fun generar(clima: Clima): Result<Recomendacion> =
        withContext(Dispatchers.IO) {
            val prompt = """
                Sos un asistente de vestimenta argentino. Hablás en español rioplatense,
                breve y directo, sin emojis.

                Clima actual en ${clima.ciudad}: ${clima.temperatura}°C,
                sensación térmica ${clima.sensacion}°C, ${clima.descripcion},
                humedad ${clima.humedad}%, viento ${clima.vientoKmh} km/h.

                Respondé únicamente con un JSON con esta forma exacta:
                {"outfit": "...", "actividad": "...", "tip": "..."}

                outfit: qué ponerse hoy, máximo 20 palabras.
                actividad: una actividad recomendada para este clima, máximo 15 palabras.
                tip: un consejo práctico y concreto, máximo 15 palabras.
            """.trimIndent()

            try {
                val texto = modelo.generateContent(prompt).text
                    ?: return@withContext Result.failure(Exception("Respuesta vacía"))
                Result.success(Gson().fromJson(texto, Recomendacion::class.java))
            } catch (e: Exception) {
                Log.e("Abrigate", "Falló Gemini", e)
                Result.failure(e)
            }
        }
}
