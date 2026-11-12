package com.utn.abrigate.data

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.utn.abrigate.data.remote.AbrigateApiClient
import com.utn.abrigate.data.remote.VerificarPedido
import com.utn.abrigate.domain.PlanPlus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Compra y restauración de Abrigate Plus con Google Play Billing.
 *
 * La app nunca decide sola si alguien es Plus: después de cada compra manda el
 * purchaseToken a abrigate-api, que lo valida con la Play Developer API, lo
 * confirma (acknowledge) y devuelve el estado. Así no alcanza con modificar el
 * APK para desbloquear Plus.
 */
class BillingManager(
    context: Context,
    private val scope: CoroutineScope,
    private val entitlement: EntitlementRepository
) : PurchasesUpdatedListener {

    companion object {
        const val PRODUCTO = "abrigate_plus"
        const val OFERTA_PRUEBA = "prueba-7-dias"
        /** Orden de los price points en el paywall: barato, objetivo, ancla alta. */
        val ORDEN_PLANES = listOf("semanal", "mensual", "anual")
        private const val TAG = "AbrigateBilling"
    }

    sealed interface Evento {
        data object CompraOk : Evento
        data object Pendiente : Evento
        data object Restaurada : Evento
        data object NadaParaRestaurar : Evento
        data class Error(val mensaje: String) : Evento
    }

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    private val _planes = MutableStateFlow<List<PlanPlus>>(emptyList())
    val planes: StateFlow<List<PlanPlus>> = _planes.asStateFlow()

    private val _eventos = MutableSharedFlow<Evento>(extraBufferCapacity = 4)
    val eventos: SharedFlow<Evento> = _eventos.asSharedFlow()

    val esPlus: StateFlow<Boolean> = entitlement.esPlus

    private var producto: ProductDetails? = null
    private val tokensDeOferta = mutableMapOf<String, String>()
    private var planElegido: String? = null

    fun conectar() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(resultado: BillingResult) {
                if (resultado.responseCode == BillingResponseCode.OK) {
                    cargarPlanes()
                    restaurar(silencioso = true)
                } else {
                    Log.w(TAG, "Billing no disponible: ${resultado.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection() reconecta solo en la próxima llamada.
            }
        })
    }

    private fun cargarPlanes() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCTO)
                        .setProductType(ProductType.SUBS)
                        .build()
                )
            ).build()

        client.queryProductDetailsAsync(params) { resultado, detalles ->
            if (resultado.responseCode != BillingResponseCode.OK) {
                Log.w(TAG, "No se pudieron cargar los planes: ${resultado.debugMessage}")
                return@queryProductDetailsAsync
            }
            val plus = detalles.productDetailsList.firstOrNull() ?: return@queryProductDetailsAsync
            producto = plus
            _planes.value = armarPlanes(plus)
        }
    }

    /**
     * Para cada plan base elige la oferta de prueba gratis si el usuario es
     * elegible (Play solo la devuelve en ese caso) o, si no, el plan base solo.
     */
    private fun armarPlanes(plus: ProductDetails): List<PlanPlus> {
        tokensDeOferta.clear()
        val ofertas = plus.subscriptionOfferDetails.orEmpty()
        return ORDEN_PLANES.mapNotNull { planId ->
            val delPlan = ofertas.filter { it.basePlanId == planId }
            val oferta = delPlan.firstOrNull { it.offerId == OFERTA_PRUEBA }
                ?: delPlan.firstOrNull { it.offerId == null }
                ?: return@mapNotNull null
            val fases = oferta.pricingPhases.pricingPhaseList
            tokensDeOferta[planId] = oferta.offerToken
            PlanPlus(
                id = planId,
                // la última fase es el precio que se paga en cada renovación
                precio = fases.last().formattedPrice,
                tienePrueba = fases.any { it.priceAmountMicros == 0L }
            )
        }
    }

    fun comprar(activity: Activity, planId: String) {
        val plus = producto
        val token = tokensDeOferta[planId]
        if (plus == null || token == null) {
            _eventos.tryEmit(Evento.Error("Los planes todavía no están disponibles."))
            return
        }
        planElegido = planId
        AnalyticsManager.planSeleccionado(planId)

        scope.launch {
            val uid = runCatching { AuthManager.uid() }.getOrNull()
            val builder = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(plus)
                            .setOfferToken(token)
                            .build()
                    )
                )
            // Asocia la compra al usuario anónimo para que el backend lo reconozca.
            if (uid != null) builder.setObfuscatedAccountId(uid)
            client.launchBillingFlow(activity, builder.build())
        }
    }

    override fun onPurchasesUpdated(resultado: BillingResult, compras: List<Purchase>?) {
        when (resultado.responseCode) {
            BillingResponseCode.OK -> compras.orEmpty().forEach { compra ->
                when (compra.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> scope.launch {
                        if (verificar(compra)) {
                            AnalyticsManager.compraCompletada(planElegido)
                            _eventos.emit(Evento.CompraOk)
                        }
                    }
                    Purchase.PurchaseState.PENDING -> _eventos.tryEmit(Evento.Pendiente)
                    else -> Unit
                }
            }
            BillingResponseCode.USER_CANCELED -> AnalyticsManager.compraCancelada(planElegido)
            BillingResponseCode.ITEM_ALREADY_OWNED -> restaurar(silencioso = false)
            else -> _eventos.tryEmit(Evento.Error("No se pudo completar la compra."))
        }
    }

    /**
     * Restaura compras previas (otro teléfono, reinstalación). Google Play exige
     * esta opción. Al abrir la app se llama en silencio para actualizar el estado.
     */
    fun restaurar(silencioso: Boolean) {
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()
        client.queryPurchasesAsync(params) { resultado, compras ->
            if (resultado.responseCode != BillingResponseCode.OK) {
                if (!silencioso) {
                    _eventos.tryEmit(Evento.Error("Google Play no está disponible en este dispositivo."))
                }
                return@queryPurchasesAsync
            }
            val vigentes = compras.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            scope.launch {
                if (vigentes.isEmpty()) {
                    entitlement.guardar(activa = false, venceIso = null)
                    if (!silencioso) _eventos.emit(Evento.NadaParaRestaurar)
                    return@launch
                }
                val algunaActiva = vigentes.map { verificar(it) }.any { it }
                if (!silencioso && algunaActiva) _eventos.emit(Evento.Restaurada)
            }
        }
    }

    /** Devuelve true si el backend confirma que la suscripción está activa. */
    private suspend fun verificar(compra: Purchase): Boolean {
        val api = AbrigateApiClient.api
        if (api == null) {
            _eventos.emit(Evento.Error("Falta configurar ABRIGATE_API_URL para validar la compra."))
            return false
        }
        return try {
            val estado = api.verificar(AuthManager.idToken(), VerificarPedido(compra.purchaseToken))
            entitlement.guardar(estado.plus, estado.vence)
            estado.plus
        } catch (e: Exception) {
            Log.e(TAG, "Falló la verificación de la compra", e)
            AnalyticsManager.errorIntegracion("abrigate-api", e.message ?: "?")
            _eventos.emit(Evento.Error("No pudimos validar la compra. Probá restaurarla más tarde."))
            false
        }
    }

    fun cerrar() = client.endConnection()
}
