package com.utn.abrigate.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.utn.abrigate.BuildConfig

/**
 * Banner adaptativo fijo abajo, con el botón «Quitar anuncios» justo arriba,
 * como propone el capítulo 17. Solo se muestra a quien no es Plus.
 */
@Composable
fun BannerAnuncio(onQuitarAnuncios: () -> Unit, modifier: Modifier = Modifier) {
    val anchoDp = LocalConfiguration.current.screenWidthDp
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onQuitarAnuncios) { Text("Quitar anuncios") }
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                AdView(ctx).apply {
                    setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, anchoDp))
                    adUnitId = BuildConfig.ADMOB_BANNER_ID
                    loadAd(AdRequest.Builder().build())
                }
            },
            onRelease = { it.destroy() }
        )
    }
}
