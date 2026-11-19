package com.utn.abrigate.notificaciones

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging
import com.utn.abrigate.BuildConfig
import com.utn.abrigate.MainActivity
import com.utn.abrigate.R
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * TP6: notificaciones de Abrigate.
 *
 * - Push (FCM): alertas de cambio brusco por zona y campañas de la consola de Firebase.
 * - Locales (WorkManager, ver [Programador]): aviso de la mañana y recordatorio de 7 días.
 *
 * Cada tipo va en su propio canal para que el usuario pueda apagar uno sin perder los demás.
 */
object Notificaciones {

    const val CANAL_ALERTAS = "alertas_clima"
    const val CANAL_AVISO = "aviso_matutino"
    const val CANAL_RECORDATORIOS = "recordatorios"
    const val CANAL_NOVEDADES = "novedades"

    /** Extra del Intent que indica qué abrir al tocar la notificación. */
    const val EXTRA_DESTINO = "destino"
    const val DESTINO_RECOMENDACION = "recomendacion"

    const val ID_ALERTA = 1
    const val ID_AVISO = 2
    const val ID_RECORDATORIO = 3
    const val ID_NOVEDAD = 4

    private const val TAG = "AbrigateFCM"
    private const val TEMA_NOVEDADES = "novedades"

    fun crearCanales(ctx: Context) {
        val canales = listOf(
            canal(CANAL_ALERTAS, "Cambios bruscos de clima", NotificationManagerCompat.IMPORTANCE_HIGH,
                "Te avisamos la noche anterior si mañana cambia mucho la temperatura en tu zona."),
            canal(CANAL_AVISO, "Aviso de la mañana", NotificationManagerCompat.IMPORTANCE_DEFAULT,
                "Qué ponerte, a la hora en que salís."),
            canal(CANAL_RECORDATORIOS, "Recordatorios", NotificationManagerCompat.IMPORTANCE_LOW,
                "Un recordatorio si hace una semana que no usás la app."),
            canal(CANAL_NOVEDADES, "Novedades y consejos", NotificationManagerCompat.IMPORTANCE_LOW,
                "Funciones nuevas y consejos de temporada."),
        )
        NotificationManagerCompat.from(ctx).createNotificationChannelsCompat(canales)
    }

    private fun canal(id: String, nombre: String, importancia: Int, descripcion: String) =
        NotificationChannelCompat.Builder(id, importancia)
            .setName(nombre)
            .setDescription(descripcion)
            .build()

    /** En Android 13+ es false hasta que el usuario acepta el permiso POST_NOTIFICATIONS. */
    fun permitidas(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    @SuppressLint("MissingPermission") // se comprueba con permitidas()
    fun mostrar(ctx: Context, canal: String, titulo: String, texto: String, destino: String?, id: Int) {
        if (!permitidas(ctx)) return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (destino != null) putExtra(EXTRA_DESTINO, destino)
        }
        val alTocar = PendingIntent.getActivity(
            ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notificacion = NotificationCompat.Builder(ctx, canal)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(alTocar)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, notificacion)
    }

    /**
     * Celda de 0,1° (unos 11 km). Es más grande que la del caché de /clima (≈ 1 km)
     * para que el tema de FCM no revele el barrio del usuario.
     * San Francisco, Córdoba → "-31.4_-62.1".
     */
    fun zonaDe(lat: Double, lon: Double): String = "%.1f_%.1f".format(Locale.US, lat, lon)

    fun temaDeZona(zona: String) = "zona_$zona"

    /**
     * Suscribe el teléfono al tema de su zona (y a "novedades"). Si cambió de zona,
     * se da de baja de la anterior. Solo se llama con las notificaciones activadas.
     */
    suspend fun suscribirZona(ctx: Context, zona: String) {
        val prefs = ctx.getSharedPreferences("notificaciones", Context.MODE_PRIVATE)
        val anterior = prefs.getString("zona", null)
        if (anterior == zona) return
        val mensajeria = Firebase.messaging
        if (anterior != null) mensajeria.unsubscribeFromTopic(temaDeZona(anterior)).await()
        mensajeria.subscribeToTopic(temaDeZona(zona)).await()
        mensajeria.subscribeToTopic(TEMA_NOVEDADES).await()
        prefs.edit().putString("zona", zona).apply()
        Log.d(TAG, "Suscripto al tema ${temaDeZona(zona)}")
    }

    /** Se olvida la zona guardada para que la próxima consulta vuelva a suscribir. */
    fun olvidarZona(ctx: Context) {
        ctx.getSharedPreferences("notificaciones", Context.MODE_PRIVATE).edit().remove("zona").apply()
    }

    /**
     * Solo en la versión de depuración: escribe el token FCM en Logcat para poder usar
     * «Enviar mensaje de prueba» en la consola de Firebase. En release no se muestra.
     */
    fun registrarTokenDeDepuracion() {
        if (!BuildConfig.DEBUG) return
        Firebase.messaging.token.addOnSuccessListener { Log.d(TAG, "Token FCM: $it") }
    }
}
