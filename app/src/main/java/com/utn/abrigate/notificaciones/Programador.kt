package com.utn.abrigate.notificaciones

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.utn.abrigate.data.ClimaRepositoryImpl
import com.utn.abrigate.data.EntitlementRepository
import com.utn.abrigate.data.RecomendacionRepositoryImpl
import com.utn.abrigate.retencion.PerfilRepository
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * TP6: notificaciones locales con WorkManager. No necesitan servidor: las programa
 * el propio teléfono y respetan los límites de batería de Android.
 */
object Programador {

    private const val RECORDATORIO = "reactivacion"
    private const val AVISO = "aviso_matutino"
    const val DIAS_RECORDATORIO = 7L

    /**
     * Ejemplo del capítulo 15: se programa a 7 días cada vez que el usuario sale de la
     * app. Como REPLACE reinicia el plazo, el aviso solo llega si deja de usarla.
     */
    fun programarRecordatorio(ctx: Context) {
        val pedido = OneTimeWorkRequestBuilder<RecordatorioWorker>()
            .setInitialDelay(DIAS_RECORDATORIO, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(RECORDATORIO, ExistingWorkPolicy.REPLACE, pedido)
    }

    /** Aviso de todos los días a la hora elegida (minutos desde la medianoche). */
    fun programarAviso(ctx: Context, minutosDelDia: Int) {
        val pedido = PeriodicWorkRequestBuilder<AvisoMatutinoWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(minutosHasta(minutosDelDia), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(AVISO, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, pedido)
    }

    fun minutosHasta(minutosDelDia: Int, ahora: LocalDateTime = LocalDateTime.now()): Long {
        var objetivo = ahora.toLocalDate().atTime(LocalTime.of(minutosDelDia / 60, minutosDelDia % 60))
        if (!objetivo.isAfter(ahora)) objetivo = objetivo.plusDays(1)
        return Duration.between(ahora, objetivo).toMinutes()
    }
}

/** Recordatorio de reactivación: «¿Cómo viene el clima en San Francisco?». */
class RecordatorioWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ciudad = PerfilRepository(applicationContext).ciudad
        Notificaciones.mostrar(
            ctx = applicationContext,
            canal = Notificaciones.CANAL_RECORDATORIOS,
            titulo = if (ciudad != null) "¿Cómo viene el clima en $ciudad?" else "¿Cómo viene el clima hoy?",
            texto = "Mirá qué ponerte antes de salir.",
            destino = Notificaciones.DESTINO_RECOMENDACION,
            id = Notificaciones.ID_RECORDATORIO
        )
        return Result.success()
    }
}

/**
 * Aviso de la mañana. Gratis: temperatura y una prenda, calculadas en el teléfono.
 * Plus: la recomendación completa de Gemini (TP4).
 */
class AvisoMatutinoWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val perfil = PerfilRepository(applicationContext)
        val (lat, lon) = perfil.ultimaUbicacion() ?: return Result.success()
        val clima = ClimaRepositoryImpl().obtenerClima(lat, lon).getOrElse { return Result.success() }

        val esPlus = EntitlementRepository(applicationContext).esPlus.value
        val texto = if (esPlus) {
            RecomendacionRepositoryImpl()
                .generar(clima, perfil.perfil(), Notificaciones.zonaDe(lat, lon))
                .map { it.outfit }
                .getOrNull()
        } else null

        Notificaciones.mostrar(
            ctx = applicationContext,
            canal = Notificaciones.CANAL_AVISO,
            titulo = "${clima.temperatura}° en ${clima.ciudad}",
            texto = texto ?: prendaBasica(clima.sensacion, perfil.sensibilidad),
            destino = Notificaciones.DESTINO_RECOMENDACION,
            id = Notificaciones.ID_AVISO
        )
        return Result.success()
    }
}

/** Sugerencia corta sin IA para el aviso gratis. Corrige 3 °C según el perfil. */
fun prendaBasica(sensacion: Int, sensibilidad: String?): String {
    val t = sensacion + when (sensibilidad) {
        "friolento" -> -3
        "caluroso" -> 3
        else -> 0
    }
    return when {
        t <= 5 -> "Abrigo grueso, bufanda y guantes. Tocá para ver la recomendación."
        t <= 12 -> "Campera de abrigo y manga larga. Tocá para ver la recomendación."
        t <= 18 -> "Buzo o campera liviana. Tocá para ver la recomendación."
        t <= 24 -> "Remera y algo liviano por si refresca. Tocá para ver más."
        else -> "Ropa fresca y protector solar. Tocá para ver más."
    }
}
