package com.utn.abrigate.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.utn.abrigate.data.*
import com.utn.abrigate.domain.*
import com.utn.abrigate.notificaciones.Notificaciones
import com.utn.abrigate.notificaciones.Programador
import com.utn.abrigate.retencion.EstadoRacha
import com.utn.abrigate.retencion.Insignia
import com.utn.abrigate.retencion.PerfilRepository
import com.utn.abrigate.retencion.RachaRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Inicial : UiState
    data object Cargando : UiState
    data class Exito(val clima: Clima, val reco: Recomendacion) : UiState
    data class Error(val mensaje: String) : UiState
}

/** TP6: avisos de una sola vez para la pantalla. */
sealed interface EventoRetencion {
    data class InsigniaNueva(val insignia: Insignia) : EventoRetencion
    /** Mostrar la explicación previa al permiso de notificaciones. */
    data object OfrecerAvisos : EventoRetencion
    data object PedirResena : EventoRetencion
}

class ClimaViewModel(app: Application) : AndroidViewModel(app) {

    private val climaRepo: ClimaRepository = ClimaRepositoryImpl()
    private val recoRepo: RecomendacionRepository = RecomendacionRepositoryImpl()
    private val perfil = PerfilRepository(app)
    private val racha = RachaRepository(app)

    private val _estado = MutableStateFlow<UiState>(UiState.Inicial)
    val estado: StateFlow<UiState> = _estado.asStateFlow()

    private val _racha = MutableStateFlow(racha.estado())
    val estadoRacha: StateFlow<EstadoRacha> = _racha.asStateFlow()

    private val _avisos = MutableStateFlow(perfil.avisosActivados)
    val avisosActivados: StateFlow<Boolean> = _avisos.asStateFlow()

    private val _horaAviso = MutableStateFlow(perfil.horaAviso)
    val horaAviso: StateFlow<Int> = _horaAviso.asStateFlow()

    private val _mostrarOnboarding = MutableStateFlow(!perfil.onboardingVisto)
    val mostrarOnboarding: StateFlow<Boolean> = _mostrarOnboarding.asStateFlow()

    private val _eventos = MutableSharedFlow<EventoRetencion>(extraBufferCapacity = 4)
    val eventos: SharedFlow<EventoRetencion> = _eventos.asSharedFlow()

    /** Para el pedido de reseña: solo si la sesión no tuvo errores (capítulo 15). */
    private var huboError = false

    fun consultar(lat: Double, lon: Double) {
        viewModelScope.launch {
            _estado.value = UiState.Cargando

            val clima = climaRepo.obtenerClima(lat, lon).getOrElse { e ->
                AnalyticsManager.errorIntegracion("openweather", e.message ?: "?")
                huboError = true
                _estado.value = UiState.Error("No pudimos traer el clima. Revisá la conexión.")
                return@launch
            }
            AnalyticsManager.climaConsultado(clima)
            perfil.guardarUbicacion(lat, lon, clima.ciudad)
            val zona = Notificaciones.zonaDe(lat, lon)

            val inicio = System.currentTimeMillis()
            val reco = recoRepo.generar(clima, perfil.perfil(), zona).getOrElse { e ->
                AnalyticsManager.errorIntegracion("gemini", e.message ?: "?")
                huboError = true
                _estado.value = UiState.Error("Tenemos el clima, pero falló la recomendación.")
                return@launch
            }
            AnalyticsManager.recomendacionGenerada(System.currentTimeMillis() - inicio)

            _estado.value = UiState.Exito(clima, reco)
            despuesDeRecomendar(clima, zona)
        }
    }

    private suspend fun despuesDeRecomendar(clima: Clima, zona: String) {
        // Gamificación: racha e insignias
        val nuevas = racha.registrarUso(clima.temperatura, clima.ciudad)
        _racha.value = racha.estado()
        AnalyticsManager.rachaActualizada(_racha.value.dias)
        anunciar(nuevas)

        // Notificaciones: tema de la zona, o la explicación previa al permiso
        if (perfil.avisosActivados && Notificaciones.permitidas(getApplication())) {
            suscribirZona(zona)
        } else if (perfil.debePreguntarAvisos()) {
            _eventos.emit(EventoRetencion.OfrecerAvisos)
        }
    }

    private suspend fun suscribirZona(zona: String) {
        runCatching { Notificaciones.suscribirZona(getApplication(), zona) }
            .onFailure { Log.w("AbrigateFCM", "No se pudo suscribir al tema de la zona", it) }
    }

    private suspend fun anunciar(insignias: List<Insignia>) {
        insignias.forEach {
            AnalyticsManager.insigniaObtenida(it.id)
            _eventos.emit(EventoRetencion.InsigniaNueva(it))
        }
    }

    fun valorar(util: Boolean) {
        val actual = _estado.value as? UiState.Exito ?: return
        AnalyticsManager.recomendacionValorada(util, actual.clima.temperatura)
        perfil.registrarValoracion(util, actual.clima.temperatura)
        viewModelScope.launch {
            anunciar(racha.registrarValoracion())
            _racha.value = racha.estado()
            // Pedir la reseña a quien está conforme, en el momento en que lo está.
            if (util && !huboError && _racha.value.diasDeUso >= 5 && perfil.puedePedirResena()) {
                perfil.marcarResenaPedida()
                AnalyticsManager.resenaSolicitada()
                _eventos.emit(EventoRetencion.PedirResena)
            }
        }
    }

    // --- Onboarding ---

    fun terminarOnboarding(sensibilidad: String?, transporte: String?) {
        perfil.guardarPerfil(sensibilidad, transporte)
        AnalyticsManager.onboarding(completado = true)
        _mostrarOnboarding.value = false
    }

    fun saltearOnboarding() {
        perfil.saltearOnboarding()
        AnalyticsManager.onboarding(completado = false)
        _mostrarOnboarding.value = false
    }

    fun editarPerfil() { _mostrarOnboarding.value = true }

    fun perfilActual() = perfil.sensibilidad to perfil.transporte

    // --- Avisos ---

    /** Resultado del permiso (o true directo antes de Android 13). */
    fun resultadoPermisoAvisos(aceptado: Boolean) {
        AnalyticsManager.permisoNotificaciones(aceptado)
        if (!aceptado) {
            perfil.posponerAvisos(dias = 7)
            return
        }
        perfil.avisosActivados = true
        _avisos.value = true
        AnalyticsManager.avisoActivado("explicacion_previa")
        Programador.programarAviso(getApplication(), perfil.horaAviso)
        val (lat, lon) = perfil.ultimaUbicacion() ?: return
        viewModelScope.launch { suscribirZona(Notificaciones.zonaDe(lat, lon)) }
    }

    fun posponerAvisos() = perfil.posponerAvisos(dias = 7)

    fun cambiarHoraAviso(minutosDelDia: Int) {
        perfil.horaAviso = minutosDelDia
        _horaAviso.value = minutosDelDia
        if (perfil.avisosActivados) Programador.programarAviso(getApplication(), minutosDelDia)
    }
}
