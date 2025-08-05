package com.example.app6mwt.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app6mwt.data.model.Paciente
import com.example.app6mwt.data.model.PruebaRealizada
import com.example.app6mwt.data.PacienteRepository
import com.example.app6mwt.data.local.PruebaRealizadaDao
import com.example.app6mwt.util.SixMinuteWalkTestPdfGenerator
import com.example.app6mwt.util.DateTimeFormatterUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// Representa un ítem en la lista del historial que se muestra en la UI
data class HistorialItemUi(
    val pruebaIdOriginal: Int,
    val pacienteIdOriginal: String,
    val numeroPruebaEnLista: Int,
    val fechaFormateada: String,
    val horaFormateada: String,
    val distanciaMostrada: Float,
    val porcentajeTeoricoMostrado: Float,
    val spo2MinimaMostrada: Int?,
    val numeroParadasMostrado: Int?,
    val rawPruebaRealizada: PruebaRealizada
)

// Datos para el diálogo de "Consultar Datos de Preparación"
data class DatosPreparacionUi(
    val sexoDelPacienteAlMomentoDeLaPrueba: String?,
    val edadDelPacienteAlMomentoDeLaPrueba: Int?,
    val alturaDelPacienteAlMomentoDeLaPrueba: Int?,
    val pesoDelPacienteAlMomentoDeLaPrueba: Int?,
    val distanciaTeoricaCalculada: Double?,
    val longitudPaso: Float?,
    val usaInhaladores: Boolean?,
    val tieneOxigenoDomiciliario: Boolean?
)

data class TestHistoryScreenUiState(
    val isLoadingPaciente: Boolean = true,
    val isLoadingHistorial: Boolean = true,
    val pacienteId: String? = null,
    val nombrePaciente: String = "",
    val historialDePruebas: List<HistorialItemUi> = emptyList(),
    val errorMensaje: String? = null,
    val pruebaSeleccionadaParaDetalleCompleto: PruebaRealizada? = null,
    val mostrarDialogoDetalleCompleto: Boolean = false,
    val datosPreparacionSeleccionados: DatosPreparacionUi? = null,
    val mostrarDialogoDatosPreparacion: Boolean = false,
    val isGeneratingPdf: Boolean = false,
    val pdfGeneratedUri: Uri? = null,
    val pdfGenerationError: String? = null,
    val pruebaParaPdfFileName: String? = null,
    val pruebaParaEliminar: HistorialItemUi? = null,
    val mostrarDialogoConfirmacionEliminar: Boolean = false,
    val userMessage: String? = null,
    val showNavigateBackDialog: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TestHistoryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val pacienteRepository: PacienteRepository,
    private val pruebaRealizadaDao: PruebaRealizadaDao,
    @ApplicationContext private val applicationContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(TestHistoryScreenUiState())
    val uiState: StateFlow<TestHistoryScreenUiState> = _uiState.asStateFlow()

    private val argumentPatientId: String? = savedStateHandle["patientId"]
    private val applicationId = "com.example.app6mwt"

    private val _navigateBackEvent = MutableSharedFlow<Unit>()
    val navigateBackEvent = _navigateBackEvent.asSharedFlow()

    init {
        if (argumentPatientId == null) {
            _uiState.update {
                it.copy(
                    isLoadingPaciente = false,
                    isLoadingHistorial = false,
                    errorMensaje = "ID de paciente no proporcionado."
                )
            }
        } else {
            _uiState.update { it.copy(pacienteId = argumentPatientId) }
            observePacienteYSuHistorial(argumentPatientId)
        }
    }

    private fun observePacienteYSuHistorial(pacienteId: String) {
        viewModelScope.launch {
            val pacienteFlow = flowOf(pacienteId)
                .flatMapLatest { id ->
                    try {
                        val paciente = pacienteRepository.obtenerPacientePorId(id)
                        flowOf(paciente)
                    } catch (e: Exception) {
                        flowOf<Paciente?>(null)
                            .catch { _uiState.update { it.copy(errorMensaje = "Error al cargar paciente: ${e.message}") } }
                    }
                }
                .onStart { _uiState.update { it.copy(isLoadingPaciente = true) } }
                .map { paciente ->
                    _uiState.update {
                        it.copy(
                            isLoadingPaciente = false,
                            nombrePaciente = paciente?.nombre ?: "Paciente no encontrado"
                        )
                    }
                    paciente
                }

            val historialFlow = pruebaRealizadaDao.observarPruebasDePaciente(pacienteId)
                .map { listaDePruebas ->
                    listaDePruebas.sortedByDescending { it.fechaTimestamp }
                        .mapIndexed { index, prueba ->
                            val spo2Minima = prueba.datosCompletos?.summaryData?.minSpo2Record?.value
                            val numeroParadas = prueba.datosCompletos?.summaryData?.stopRecords?.size
                        HistorialItemUi(
                            pruebaIdOriginal = prueba.pruebaId,
                            pacienteIdOriginal = prueba.pacienteId,
                            numeroPruebaEnLista = listaDePruebas.size - index,
                            fechaFormateada = DateTimeFormatterUtil.formatMillisToDateUserFriendly(prueba.fechaTimestamp),
                            horaFormateada = DateTimeFormatterUtil.formatMillisToTimeUserFriendly(prueba.fechaTimestamp), // Solo hora
                            distanciaMostrada = prueba.distanciaRecorrida,
                            porcentajeTeoricoMostrado = prueba.porcentajeTeorico,
                            spo2MinimaMostrada = spo2Minima,
                            numeroParadasMostrado = numeroParadas,
                            rawPruebaRealizada = prueba
                        )
                    }
                }
                .onStart { _uiState.update { it.copy(isLoadingHistorial = true) } }
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            isLoadingHistorial = false,
                            errorMensaje = "Error al cargar historial: ${e.message}"
                        )
                    }
                }

            pacienteFlow.combine(historialFlow) { _, historial ->
                _uiState.update {
                    it.copy(
                        isLoadingHistorial = false,
                        isLoadingPaciente = false,
                        historialDePruebas = historial,
                        errorMensaje = if (it.nombrePaciente == "Paciente no encontrado" && historial.isEmpty()) "No se encontró paciente ni historial." else it.errorMensaje
                    )
                }
            }.collect {} // Necesario para que el Flow se ejecute
        }
    }

    fun requestNavigateBack() {
        _uiState.update { it.copy(showNavigateBackDialog = true) }
    }

    fun confirmNavigateBack() {
        _uiState.update { it.copy(showNavigateBackDialog = false) }
        viewModelScope.launch {
            _navigateBackEvent.emit(Unit)
        }
    }

    fun cancelNavigateBack() {
        _uiState.update { it.copy(showNavigateBackDialog = false) }
    }

    fun onVerDetallesCompletosClicked(itemUi: HistorialItemUi) {
        _uiState.update {
            it.copy(
                pruebaSeleccionadaParaDetalleCompleto = itemUi.rawPruebaRealizada,
                mostrarDialogoDetalleCompleto = true
            )
        }
    }

    fun onDismissDialogoDetalleCompleto() {
        _uiState.update {
            it.copy(
                pruebaSeleccionadaParaDetalleCompleto = null,
                mostrarDialogoDetalleCompleto = false
            )
        }
    }

    fun onConsultarDatosPreparacionClicked(itemUi: HistorialItemUi) {
        val prueba = itemUi.rawPruebaRealizada
        val datosCompletos = prueba.datosCompletos

        val datosPreparacion = DatosPreparacionUi(
            sexoDelPacienteAlMomentoDeLaPrueba = datosCompletos?.summaryData?.patientSex,
            edadDelPacienteAlMomentoDeLaPrueba = datosCompletos?.summaryData?.patientAge,
            alturaDelPacienteAlMomentoDeLaPrueba = datosCompletos?.summaryData?.patientHeightCm,
            pesoDelPacienteAlMomentoDeLaPrueba = datosCompletos?.summaryData?.patientWeightKg,
            distanciaTeoricaCalculada = datosCompletos?.summaryData?.theoreticalDistance,
            longitudPaso = datosCompletos?.summaryData?.strideLengthUsedForTestMeters,
            usaInhaladores = datosCompletos?.summaryData?.usesInhalers,
            tieneOxigenoDomiciliario = datosCompletos?.summaryData?.usesOxygen
        )

        _uiState.update {
            it.copy(
                datosPreparacionSeleccionados = datosPreparacion,
                mostrarDialogoDatosPreparacion = true
            )
        }
    }

    fun onDismissDialogoDatosPreparacion() {
        _uiState.update { it.copy(datosPreparacionSeleccionados = null, mostrarDialogoDatosPreparacion = false) }
    }

    fun onImprimirResultadosClicked(itemUi: HistorialItemUi) {
        val pruebaOriginal = itemUi.rawPruebaRealizada
        val detallesDeLaPrueba = pruebaOriginal.datosCompletos

        if (detallesDeLaPrueba == null || detallesDeLaPrueba.summaryData == null) {
            _uiState.update {
                it.copy(
                    isGeneratingPdf = false,
                    pdfGenerationError = "Datos completos de la prueba no disponibles para generar PDF."
                )
            }
            clearUserMessageAfterDelay()
            return
        }

        // --- Obtener los nuevos parámetros ---
        val numeroPruebaParaPdf = itemUi.numeroPruebaEnLista
        val idPruebaParaPdf = itemUi.pruebaIdOriginal

        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingPdf = true, pdfGenerationError = null, pdfGeneratedUri = null) }

            try {
                val file = withContext(Dispatchers.IO) {
                    SixMinuteWalkTestPdfGenerator.generatePdf(
                        context = applicationContext,
                        detallesPrueba = detallesDeLaPrueba,
                        numeroPrueba = numeroPruebaParaPdf,
                        pruebaId = idPruebaParaPdf
                    )
                }

                if (file != null) {
                    val uri = FileProvider.getUriForFile(
                        applicationContext,
                        "$applicationId.provider",
                        file
                    )
                    _uiState.update {
                        it.copy(
                            isGeneratingPdf = false,
                            pdfGeneratedUri = uri,
                            pruebaParaPdfFileName = file.name,
                            userMessage = "PDF generado: ${file.name}"
                        )
                    }
                    clearUserMessageAfterDelay(4000L)
                } else {
                    _uiState.update {
                        it.copy(
                            isGeneratingPdf = false,
                            pdfGenerationError = "Error al generar PDF: el generador devolvió nulo."
                        )
                    }
                    clearUserMessageAfterDelay()
                }

            } catch (e: Exception) {
                Log.e("TestHistoryVM", "Excepción al generar PDF", e) // Loguear la excepción completa
                _uiState.update {
                    it.copy(
                        isGeneratingPdf = false,
                        pdfGenerationError = "Excepción al generar PDF: ${e.localizedMessage}" // Usar localizedMessage
                    )
                }
                clearUserMessageAfterDelay()
            }
        }
    }

    fun onEliminarPruebaClicked(itemUi: HistorialItemUi) {
        _uiState.update { it.copy(pruebaParaEliminar = itemUi, mostrarDialogoConfirmacionEliminar = true) }
    }

    fun onConfirmarEliminacion() {
        val pruebaAEliminarUi = _uiState.value.pruebaParaEliminar
        if (pruebaAEliminarUi != null) {
            viewModelScope.launch {
                try {
                    pruebaRealizadaDao.eliminarPruebaPorSuId(pruebaAEliminarUi.pruebaIdOriginal)

                    val conteoRestante = pruebaRealizadaDao.getConteoPruebasDePacienteSync(pruebaAEliminarUi.pacienteIdOriginal)
                    if (conteoRestante == 0) {
                        pacienteRepository.actualizarEstadoHistorialPaciente(pruebaAEliminarUi.pacienteIdOriginal, false)
                    }

                    _uiState.update {
                        it.copy(
                            pruebaParaEliminar = null,
                            mostrarDialogoConfirmacionEliminar = false,
                            userMessage = "Prueba N°${pruebaAEliminarUi.numeroPruebaEnLista} eliminada."
                        )
                    }
                    clearUserMessageAfterDelay()

                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            pruebaParaEliminar = null,
                            mostrarDialogoConfirmacionEliminar = false,
                            userMessage = "Error al eliminar la prueba: ${e.message}"
                        )
                    }
                    clearUserMessageAfterDelay()
                }
            }
        }
    }

    fun onDismissDialogoEliminacion() {
        _uiState.update { it.copy(pruebaParaEliminar = null, mostrarDialogoConfirmacionEliminar = false) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null, pdfGenerationError = null) }
    }

    private fun clearUserMessageAfterDelay(delayMillis: Long = 3000L) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(delayMillis)
            clearUserMessage()
        }
    }

    fun clearPdfDialogState() {
        _uiState.update {
            it.copy(
                pdfGeneratedUri = null,
                pruebaParaPdfFileName = null
            )
        }
    }
}
