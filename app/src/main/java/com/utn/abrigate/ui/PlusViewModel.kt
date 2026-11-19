package com.utn.abrigate.ui

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.utn.abrigate.data.AnalyticsManager
import com.utn.abrigate.data.BillingManager
import com.utn.abrigate.data.EntitlementRepository
import com.utn.abrigate.domain.PlanPlus
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Estado de Abrigate Plus para la UI: planes, si el usuario es Plus y avisos. */
class PlusViewModel(app: Application) : AndroidViewModel(app) {

    private val billing = BillingManager(app, viewModelScope, EntitlementRepository(app))

    val esPlus: StateFlow<Boolean> = billing.esPlus
    val planes: StateFlow<List<PlanPlus>> = billing.planes
    val eventos: SharedFlow<BillingManager.Evento> = billing.eventos

    init {
        billing.conectar()
        // TP6: las campañas se segmentan por esta propiedad de Analytics.
        viewModelScope.launch { esPlus.collect { AnalyticsManager.esPlus(it) } }
    }

    fun paywallMostrado(origen: String) = AnalyticsManager.paywallMostrado(origen)

    fun comprar(activity: Activity, planId: String) = billing.comprar(activity, planId)

    fun restaurar() = billing.restaurar(silencioso = false)

    override fun onCleared() {
        billing.cerrar()
    }
}
