package com.utn.abrigate.notificaciones

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Recibe las notificaciones push de FCM.
 *
 * Con la app cerrada o en segundo plano, Android muestra la notificación solo, en el
 * canal que indica el mensaje, y al tocarla abre MainActivity con los datos como
 * extras. Este servicio solo interviene cuando la app está abierta.
 */
class AbrigateMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Usamos temas, así que el backend no necesita guardar el token. Si la
        // instalación cambió, la próxima consulta vuelve a suscribir la zona.
        Notificaciones.olvidarZona(applicationContext)
    }

    override fun onMessageReceived(mensaje: RemoteMessage) {
        val n = mensaje.notification ?: return
        val canal = mensaje.data["canal"] ?: n.channelId ?: Notificaciones.CANAL_NOVEDADES
        Notificaciones.mostrar(
            ctx = this,
            canal = canal,
            titulo = n.title.orEmpty(),
            texto = n.body.orEmpty(),
            destino = mensaje.data[Notificaciones.EXTRA_DESTINO],
            id = if (canal == Notificaciones.CANAL_ALERTAS) Notificaciones.ID_ALERTA else Notificaciones.ID_NOVEDAD
        )
    }
}
