package com.utn.abrigate.ui

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.utn.abrigate.data.AnalyticsManager
import com.utn.abrigate.data.BillingManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaClima(
    vm: ClimaViewModel = viewModel(),
    plusVm: PlusViewModel = viewModel(),
    abrirRecomendacion: Boolean = false,
    onRecomendacionAbierta: () -> Unit = {}
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val racha by vm.estadoRacha.collectAsStateWithLifecycle()
    val avisosActivados by vm.avisosActivados.collectAsStateWithLifecycle()
    val horaAviso by vm.horaAviso.collectAsStateWithLifecycle()
    val mostrarOnboarding by vm.mostrarOnboarding.collectAsStateWithLifecycle()
    var ofrecerAvisos by remember { mutableStateOf(false) }
    val esPlus by plusVm.esPlus.collectAsStateWithLifecycle()
    val planes by plusVm.planes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var valorada by remember { mutableStateOf(false) }
    var mostrarPaywall by remember { mutableStateOf(false) }

    fun abrirPaywall(origen: String) {
        plusVm.paywallMostrado(origen)
        mostrarPaywall = true
    }

    // Avisos de compra y restauración
    LaunchedEffect(Unit) {
        plusVm.eventos.collect { evento ->
            val texto = when (evento) {
                BillingManager.Evento.CompraOk -> "¡Listo! Ya sos Plus: chau anuncios."
                BillingManager.Evento.Restaurada -> "Recuperamos tu suscripción Plus."
                BillingManager.Evento.NadaParaRestaurar -> "No encontramos compras para restaurar."
                BillingManager.Evento.Pendiente -> "Tu pago está pendiente. Te avisamos cuando se acredite."
                is BillingManager.Evento.Error -> evento.mensaje
            }
            if (evento == BillingManager.Evento.CompraOk || evento == BillingManager.Evento.Restaurada) {
                mostrarPaywall = false
            }
            Toast.makeText(context, texto, Toast.LENGTH_LONG).show()
        }
    }

    val pedirPermiso = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        scope.launch {
            valorada = false
            val (lat, lon) = obtenerUbicacion(context)
            vm.consultar(lat, lon)
        }
    }

    // TP6: avisos de retención (insignias, permiso de notificaciones y reseña)
    val pedirPermisoAvisos = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { aceptado -> vm.resultadoPermisoAvisos(aceptado) }

    LaunchedEffect(Unit) {
        vm.eventos.collect { evento ->
            when (evento) {
                is EventoRetencion.InsigniaNueva -> Toast.makeText(
                    context,
                    "¡Nueva insignia! ${evento.insignia.nombre}: ${evento.insignia.descripcion}",
                    Toast.LENGTH_LONG
                ).show()
                EventoRetencion.OfrecerAvisos -> ofrecerAvisos = true
                EventoRetencion.PedirResena -> activity?.let { pedirResena(it) }
            }
        }
    }

    // Al tocar una notificación se abre directamente la recomendación.
    LaunchedEffect(abrirRecomendacion) {
        if (abrirRecomendacion) {
            onRecomendacionAbierta()
            pedirPermiso.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Abrigate",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.weight(1f)
                )
                if (esPlus) {
                    AssistChip(onClick = {}, label = { Text("Plus") })
                } else {
                    TextButton(onClick = { abrirPaywall("encabezado") }) { Text("Probar Plus") }
                }
            }

            FilaRacha(
                racha = racha,
                avisosActivados = avisosActivados,
                horaAviso = horaAviso,
                onPerfil = { vm.editarPerfil() },
                onCambiarHora = {
                    TimePickerDialog(context, { _, h, m -> vm.cambiarHoraAviso(h * 60 + m) },
                        horaAviso / 60, horaAviso % 60, true).show()
                }
            )

            Button(
                onClick = { pedirPermiso.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                enabled = estado !is UiState.Cargando
            ) {
                Text("¿Qué me pongo hoy?")
            }

            when (val e = estado) {
                is UiState.Inicial -> Text("Tocá el botón para ver el clima de tu zona.")

                is UiState.Cargando -> CircularProgressIndicator()

                is UiState.Error -> Card {
                    Text(e.mensaje, modifier = Modifier.padding(16.dp))
                }

                is UiState.Exito -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(e.clima.ciudad, style = MaterialTheme.typography.titleLarge)
                            Text("${e.clima.temperatura}°C · ST ${e.clima.sensacion}°C")
                            Text(e.clima.descripcion.replaceFirstChar { it.uppercase() })
                            Text("Humedad ${e.clima.humedad}% · Viento ${e.clima.vientoKmh} km/h")
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ponete", style = MaterialTheme.typography.labelLarge)
                            Text(e.reco.outfit)
                            Text("Plan del día", style = MaterialTheme.typography.labelLarge)
                            Text(e.reco.actividad)
                            Text("Tip", style = MaterialTheme.typography.labelLarge)
                            Text(e.reco.tip)
                        }
                    }

                    if (!valorada) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { vm.valorar(true); valorada = true }) {
                                Text("Me sirvió")
                            }
                            OutlinedButton(onClick = { vm.valorar(false); valorada = true }) {
                                Text("No me sirvió")
                            }
                        }
                    } else {
                        Text("¡Gracias! Tu respuesta nos ayuda a mejorar.")
                    }
                }
            }
        }

        // El banner aparece recién cuando ya se mostró la recomendación, para no
        // demorarla ni taparla. Sin intersticiales: la sesión dura segundos.
        if (!esPlus && estado is UiState.Exito) {
            BannerAnuncio(
                onQuitarAnuncios = {
                    AnalyticsManager.quitarAnunciosClick()
                    abrirPaywall("banner")
                }
            )
        }
    }

    if (mostrarOnboarding) {
        DialogoOnboarding(
            inicial = vm.perfilActual(),
            onListo = { s, t -> vm.terminarOnboarding(s, t) },
            onSaltear = { vm.saltearOnboarding() }
        )
    }

    if (ofrecerAvisos) {
        DialogoAvisos(
            onAceptar = {
                ofrecerAvisos = false
                if (Build.VERSION.SDK_INT >= 33) pedirPermisoAvisos.launch(Manifest.permission.POST_NOTIFICATIONS)
                else vm.resultadoPermisoAvisos(true)   // antes de Android 13 no hace falta pedirlo
            },
            onAhoraNo = {
                ofrecerAvisos = false
                vm.posponerAvisos()
            }
        )
    }

    if (mostrarPaywall) {
        Paywall(
            planes = planes,
            onElegir = { planId -> activity?.let { plusVm.comprar(it, planId) } },
            onRestaurar = { plusVm.restaurar() },
            onCerrar = { mostrarPaywall = false }
        )
    }
}
