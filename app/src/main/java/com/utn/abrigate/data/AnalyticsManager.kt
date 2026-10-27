package com.utn.abrigate.data

import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import com.utn.abrigate.domain.Clima

object AnalyticsManager {

    private val analytics: FirebaseAnalytics by lazy { Firebase.analytics }

    fun climaConsultado(clima: Clima) {
        analytics.logEvent("clima_consultado") {
            param("ciudad", clima.ciudad)
            param("temperatura", clima.temperatura.toLong())
        }
    }

    fun recomendacionGenerada(latenciaMs: Long) {
        analytics.logEvent("recomendacion_generada") {
            param("latencia_ms", latenciaMs)
        }
    }

    fun recomendacionValorada(util: Boolean, temperatura: Int) {
        analytics.logEvent("recomendacion_valorada") {
            param("util", if (util) 1L else 0L)
            param("temperatura", temperatura.toLong())
        }
    }

    fun errorIntegracion(servicio: String, detalle: String) {
        analytics.logEvent("error_integracion") {
            param("servicio", servicio)
            param("detalle", detalle.take(100))
        }
    }
}
