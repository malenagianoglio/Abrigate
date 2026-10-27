package com.utn.abrigate.ui

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await

val SAN_FRANCISCO = -31.4258 to -62.0836

@SuppressLint("MissingPermission")
suspend fun obtenerUbicacion(context: Context): Pair<Double, Double> = try {
    val loc = LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
    if (loc != null) loc.latitude to loc.longitude else SAN_FRANCISCO
} catch (e: Exception) {
    SAN_FRANCISCO
}
