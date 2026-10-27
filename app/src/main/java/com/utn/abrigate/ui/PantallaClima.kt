package com.utn.abrigate.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaClima(vm: ClimaViewModel = viewModel()) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var valorada by remember { mutableStateOf(false) }

    val pedirPermiso = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        scope.launch {
            valorada = false
            val (lat, lon) = obtenerUbicacion(context)
            vm.consultar(lat, lon)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Abrigate", style = MaterialTheme.typography.headlineLarge)

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
}
