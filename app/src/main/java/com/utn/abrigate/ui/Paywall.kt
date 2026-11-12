package com.utn.abrigate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.utn.abrigate.domain.PlanPlus

private val NOMBRES = mapOf("semanal" to "Semanal", "mensual" to "Mensual", "anual" to "Anual")
private val PERIODOS = mapOf("semanal" to "por semana", "mensual" to "por mes", "anual" to "por año")

/**
 * Paywall de Abrigate Plus con los tres price points del capítulo 17:
 * una opción barata, la mensual destacada en el medio y la anual como ancla.
 *
 * Por ahora Plus solo quita los anuncios. El resto de los beneficios figura como
 * «próximamente»: es la prueba de puerta falsa del TP4 para medir interés antes
 * de construirlos, sin cobrar por algo que todavía no existe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Paywall(
    planes: List<PlanPlus>,
    onElegir: (String) -> Unit,
    onRestaurar: () -> Unit,
    onCerrar: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onCerrar) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Abrigate Plus", style = MaterialTheme.typography.headlineSmall)
            Text("✓ Sin anuncios")
            Text(
                "Próximamente: recomendación para todo el día, perfil personal " +
                    "y aviso a la mañana.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (planes.isEmpty()) {
                Text(
                    "No pudimos cargar los planes. Para comprar, la app tiene que estar " +
                        "instalada desde Google Play.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            planes.forEach { plan -> TarjetaPlan(plan, onElegir) }

            TextButton(onClick = onRestaurar, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Restaurar compras")
            }
            Text(
                "La suscripción se renueva sola. Podés cancelarla cuando quieras desde Google Play.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TarjetaPlan(plan: PlanPlus, onElegir: (String) -> Unit) {
    val destacado = plan.id == "mensual"
    OutlinedCard(
        onClick = { onElegir(plan.id) },
        modifier = Modifier.fillMaxWidth(),
        border = if (destacado) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                if (destacado) {
                    Text(
                        "El más elegido",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(NOMBRES[plan.id] ?: plan.id, fontWeight = FontWeight.Bold)
                if (plan.tienePrueba) Text("7 días gratis", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(plan.precio, fontWeight = FontWeight.Bold)
                Text(PERIODOS[plan.id] ?: "", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
