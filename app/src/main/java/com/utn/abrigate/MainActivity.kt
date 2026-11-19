package com.utn.abrigate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.utn.abrigate.data.AdsManager
import com.utn.abrigate.notificaciones.Notificaciones
import com.utn.abrigate.notificaciones.Programador
import com.utn.abrigate.ui.PantallaClima
import com.utn.abrigate.ui.theme.AbrigateTheme

class MainActivity : ComponentActivity() {

    /** TP6: true si se abrió desde una notificación que lleva a la recomendación. */
    private var abrirRecomendacion by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // App Check: el proveedor de debug sirve solo para el emulador.
        // Para un celular real en release hay que usar Play Integrity.
        if (BuildConfig.DEBUG) {
            Firebase.appCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
        }

        AdsManager.inicializar(this)

        // TP6: canales de notificación y token de prueba (solo en debug)
        Notificaciones.crearCanales(this)
        Notificaciones.registrarTokenDeDepuracion()
        if (savedInstanceState == null) leerDestino(intent)

        enableEdgeToEdge()
        setContent {
            AbrigateTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        PantallaClima(
                            abrirRecomendacion = abrirRecomendacion,
                            onRecomendacionAbierta = { abrirRecomendacion = false }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        leerDestino(intent)
    }

    private fun leerDestino(intent: Intent?) {
        if (intent?.getStringExtra(Notificaciones.EXTRA_DESTINO) == Notificaciones.DESTINO_RECOMENDACION) {
            abrirRecomendacion = true
        }
    }

    override fun onStop() {
        super.onStop()
        // Recordatorio a 7 días: se reprograma cada vez que el usuario sale de la app,
        // así que solo llega si deja de usarla (ejemplo del capítulo 15).
        Programador.programarRecordatorio(this)
    }
}
