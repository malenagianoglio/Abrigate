package com.utn.abrigate.ui

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import com.utn.abrigate.retencion.EstadoRacha

// TP6: piezas de la pantalla para la retención (onboarding, permiso, racha y reseña).

private val SENSIBILIDADES = listOf("friolento" to "Friolento/a", "normal" to "Normal", "caluroso" to "Caluroso/a")
private val TRANSPORTES = listOf("a pie" to "A pie", "bici" to "En bici", "auto" to "En auto", "colectivo" to "En colectivo")

/** Onboarding de dos preguntas. Se puede saltear y se puede volver a abrir desde «Mi perfil». */
@Composable
fun DialogoOnboarding(
    inicial: Pair<String?, String?>,
    onListo: (sensibilidad: String?, transporte: String?) -> Unit,
    onSaltear: () -> Unit
) {
    var sensibilidad by remember { mutableStateOf(inicial.first) }
    var transporte by remember { mutableStateOf(inicial.second) }
    AlertDialog(
        onDismissRequest = onSaltear,
        title = { Text("Contanos un poco de vos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Así la recomendación se ajusta a vos.")
                Text("¿Cómo sos con el frío?", style = MaterialTheme.typography.labelLarge)
                FilasDeA2(SENSIBILIDADES) { (id, texto) ->
                    FilterChip(selected = sensibilidad == id, onClick = { sensibilidad = id }, label = { Text(texto) })
                }
                Text("¿Cómo te movés?", style = MaterialTheme.typography.labelLarge)
                FilasDeA2(TRANSPORTES) { (id, texto) ->
                    FilterChip(selected = transporte == id, onClick = { transporte = id }, label = { Text(texto) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onListo(sensibilidad, transporte) }) { Text("Listo") } },
        dismissButton = { TextButton(onClick = onSaltear) { Text("Saltear") } }
    )
}

/**
 * Explicación previa al permiso de notificaciones. Se muestra después de la primera
 * recomendación, nunca al abrir la app por primera vez: si el usuario rechaza el
 * permiso del sistema dos veces, Android no lo vuelve a mostrar.
 */
@Composable
fun DialogoAvisos(onAceptar: () -> Unit, onAhoraNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAhoraNo,
        title = { Text("¿Te avisamos cuando cambie el clima?") },
        text = {
            Text(
                "Si mañana cambia mucho la temperatura en tu zona, te avisamos la noche anterior. " +
                    "También te mandamos un aviso a la mañana con qué ponerte. " +
                    "Podés apagar cada aviso cuando quieras."
            )
        },
        confirmButton = { TextButton(onClick = onAceptar) { Text("Sí, avisame") } },
        dismissButton = { TextButton(onClick = onAhoraNo) { Text("Ahora no") } }
    )
}

/** Racha, insignias y accesos al perfil y a la hora del aviso. */
@Composable
fun FilaRacha(
    racha: EstadoRacha,
    avisosActivados: Boolean,
    horaAviso: Int,
    onPerfil: () -> Unit,
    onCambiarHora: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (racha.dias > 0) "Racha abrigada: ${racha.dias} ${if (racha.dias == 1) "día" else "días"}"
                else "Empezá tu racha consultando hoy",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onPerfil) { Text("Mi perfil") }
        }
        if (racha.insignias.isNotEmpty()) {
            FilasDeA2(racha.insignias) { AssistChip(onClick = {}, label = { Text(it.nombre) }) }
        }
        if (avisosActivados) {
            TextButton(onClick = onCambiarHora, contentPadding = PaddingValues(0.dp)) {
                Text("Aviso de la mañana: %d:%02d · cambiar".format(horaAviso / 60, horaAviso % 60))
            }
        }
    }
}

/**
 * Acomoda los elementos de a dos por fila. Reemplaza a FlowRow, que no es compatible
 * con la versión de Compose que traen otras dependencias del proyecto.
 */
@Composable
private fun <T> FilasDeA2(elementos: List<T>, contenido: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        elementos.chunked(2).forEach { fila ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { fila.forEach { contenido(it) } }
        }
    }
}

/** In-App Review de Google Play. Google decide si lo muestra y cuántas veces. */
suspend fun pedirResena(activity: Activity) {
    try {
        val manager = ReviewManagerFactory.create(activity)
        val info = manager.requestReview()
        manager.launchReview(activity, info)
    } catch (e: Exception) {
        Log.w("Abrigate", "No se pudo pedir la reseña", e)
    }
}
