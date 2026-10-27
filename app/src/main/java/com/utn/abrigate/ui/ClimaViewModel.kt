package com.utn.abrigate.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.utn.abrigate.data.*
import com.utn.abrigate.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Inicial : UiState
    data object Cargando : UiState
    data class Exito(val clima: Clima, val reco: Recomendacion) : UiState
    data class Error(val mensaje: String) : UiState
}

class ClimaViewModel(
    private val climaRepo: ClimaRepository = ClimaRepositoryImpl(),
    private val recoRepo: RecomendacionRepository = RecomendacionRepositoryImpl()
) : ViewModel() {

    private val _estado = MutableStateFlow<UiState>(UiState.Inicial)
    val estado: StateFlow<UiState> = _estado.asStateFlow()

    fun consultar(lat: Double, lon: Double) {
        viewModelScope.launch {
            _estado.value = UiState.Cargando

            val clima = climaRepo.obtenerClima(lat, lon).getOrElse { e ->
                AnalyticsManager.errorIntegracion("openweather", e.message ?: "?")
                _estado.value = UiState.Error("No pudimos traer el clima. Revisá la conexión.")
                return@launch
            }
            AnalyticsManager.climaConsultado(clima)

            val inicio = System.currentTimeMillis()
            val reco = recoRepo.generar(clima).getOrElse { e ->
                AnalyticsManager.errorIntegracion("gemini", e.message ?: "?")
                _estado.value = UiState.Error("Tenemos el clima, pero falló la recomendación.")
                return@launch
            }
            AnalyticsManager.recomendacionGenerada(System.currentTimeMillis() - inicio)

            _estado.value = UiState.Exito(clima, reco)
        }
    }

    fun valorar(util: Boolean) {
        val actual = _estado.value as? UiState.Exito ?: return
        AnalyticsManager.recomendacionValorada(util, actual.clima.temperatura)
    }
}
