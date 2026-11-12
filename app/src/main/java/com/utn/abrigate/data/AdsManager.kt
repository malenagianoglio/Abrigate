package com.utn.abrigate.data

import android.content.Context
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Inicializa AdMob fuera del hilo principal para no demorar el arranque. */
object AdsManager {

    @Volatile private var iniciado = false

    fun inicializar(context: Context) {
        if (iniciado) return
        iniciado = true
        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(context.applicationContext) {}
        }
    }
}
