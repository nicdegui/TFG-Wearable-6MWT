package com.example.app6mwt.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app6mwt.bluetooth.BleConnectionStatus
import com.example.app6mwt.bluetooth.BleDeviceData
import com.example.app6mwt.bluetooth.BluetoothService
import com.example.app6mwt.bluetooth.DeviceCategory
import com.example.app6mwt.data.DefaultThresholdValues
import com.example.app6mwt.data.PacienteRepository
import com.example.app6mwt.data.SettingsRepository
import com.example.app6mwt.data.model.PruebaRealizada
import com.example.app6mwt.di.RecoveryData
import com.example.app6mwt.di.TestStateHolder
import com.example.app6mwt.ui.theme.SuccessGreenColor
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import com.example.app6mwt.ui.theme.*
import com.example.app6mwt.util.SixMinuteWalkTestPdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// --- Enums y Data Classes ---
/**
 * Enum para identificar los campos de entrada de datos postprueba.
 * Esto ayuda a manejar los cambios de valor y la validación de forma más estructurada.
 */
enum class PostTestField {
    BLOOD_PRESSURE, // Presión arterial
    RESPIRATORY_RATE, // Frecuencia respiratoria
    DYSPNEA_BORG, // Escala de Borg para disnea
    LEG_PAIN_BORG // Escala de Borg para dolor en miembros inferiores
}

/**
 * Enum para representar el estado visual del icono de Bluetooth en la UI.
 * Cada estado tiene un icono, un color y una propiedad que indica si es clickeable.
 */
enum class BluetoothIconStatus2(
    val icon: ImageVector, // El icono vectorial a mostrar.
    val color: androidx.compose.ui.graphics.Color, // El color del icono.
    val isClickable: Boolean // Indica si el icono debe responder a clics.
) {
    GREEN( // Estado conectado y funcionando correctamente.
        icon = Icons.Filled.BluetoothConnected,
        color = SuccessGreenColor, // Color verde para éxito.
        isClickable = false // No clickeable cuando está todo bien.
    ),
    YELLOW( // Estado de advertencia (ej. conectado pero con problemas de señal o datos).
        icon = Icons.Filled.Warning,
        color = WarningYellowColor, // Color amarillo para advertencia.
        isClickable = true // Clickeable para intentar solucionar.
    ),
    RED( // Estado de error o desconexión crítica.
        icon = Icons.Filled.BluetoothDisabled,
        color = ErrorRedColor, // Color rojo para error.
        isClickable = true // Clickeable para intentar reconectar o solucionar.
    ),
    CONNECTING( // Estado mientras se intenta establecer una conexión.
        icon = Icons.AutoMirrored.Filled.BluetoothSearching,
        color = ConnectingBlueColor, // Color azul para estado de conexión.
        isClickable = false // No clickeable durante el proceso de conexión.
    ),
    GRAY( // Estado inactivo o desconectado por el usuario.
        icon = Icons.Filled.Bluetooth,
        color = DisabledGrayColor, // Color gris para estado inactivo/deshabilitado.
        isClickable = true // Clickeable para iniciar conexión.
    );
}

/**
 * Data class para encapsular todos los detalles de una prueba completa,
 * incluyendo los datos del resumen de ejecución y los valores postprueba.
 * Usado para guardar en la base de datos y generar el PDF.
 */
data class PruebaCompletaDetalles(
    val summaryData: TestExecutionSummaryData?, // Datos resumidos de la ejecución de la prueba.
    val postTestSpo2: Int?, // SpO2 medido en el periodo de recuperación.
    val postTestHeartRate: Int?, // Frecuencia cardíaca medida en el periodo de recuperación.
    val postTestSystolicBP: Int?, // Presión arterial sistólica postprueba (entrada manual).
    val postTestDiastolicBP: Int?, // Presión arterial diastólica postprueba (entrada manual).
    val postTestRespiratoryRate: Int?, // Frecuencia respiratoria postprueba (entrada manual).
    val postTestDyspneaBorg: Int?, // Escala de Borg para disnea postprueba (entrada manual).
    val postTestLegPainBorg: Int?, // Escala de Borg para dolor en piernas postprueba (entrada manual).
    val observations: String? // Observaciones adicionales sobre la prueba.
)

/**
 * Data class que representa el estado completo de la UI para la pantalla de resultados.
 * Contiene toda la información que la Composable function necesita para renderizarse.
 * Se utiliza con StateFlow para que la UI reaccione a los cambios.
 */
data class TestResultsUiState(
    // --- Estados Generales de la Pantalla ---
    val isLoading: Boolean = true, // Indica si los datos iniciales se están cargando.
    val patientId: String = "", // ID del paciente.
    val patientFullName: String = "", // Nombre completo del paciente.
    val testDate: Long = 0L, // Fecha y hora de la prueba (timestamp).

    // --- Datos del Resumen de la Prueba (si existe) ---
    val summaryData: TestExecutionSummaryData? = null, // Contiene los datos de la fase de ejecución de la prueba.

    // --- Datos Calculados y Derivados del Resumen ---
    val totalDistanceMeters: Float = 0f, // Distancia total recorrida en metros.
    val theoreticalDistanceMeters: Double = 0.0, // Distancia teórica que debería haber recorrido el paciente.
    val percentageOfTheoretical: Float = 0f, // Porcentaje de la distancia teórica alcanzada.
    val minuteSnapshotsForTable: List<MinuteDataSnapshot> = emptyList(), // Datos por minuto para la tabla.
    val stopRecordsForTable: List<StopRecord> = emptyList(), // Registros de paradas durante la prueba.
    val numberOfStops: Int = 0, // Número total de paradas.
    val minSpo2ForDisplay: CriticalValueRecord? = null, // Registro del valor mínimo de SpO2.
    val maxHeartRateForDisplay: CriticalValueRecord? = null, // Registro del valor máximo de FC.
    val minHeartRateForDisplay: CriticalValueRecord? = null, // Registro del valor mínimo de FC.

    // --- Valores Basales (Pre-Prueba) ---
    val basalSpo2: Int? = null, // SpO2 basal.
    val basalHeartRate: Int? = null, // Frecuencia cardíaca basal.
    val basalBloodPressureSystolic: Int? = null, // Presión arterial sistólica basal.
    val basalBloodPressureDiastolic: Int? = null, // Presión arterial diastólica basal.
    val basalBloodPressureFormatted: String = "", // Presión arterial basal formateada (ej. "120/80 mmHg").
    val basalRespiratoryRate: Int? = null, // Frecuencia respiratoria basal.
    val basalDyspneaBorg: Int? = null, // Escala de Borg para disnea basal.
    val basalLegPainBorg: Int? = null, // Escala de Borg para dolor en piernas basal.

    // --- Entradas de Usuario para Valores Postprueba ---
    val postTestBloodPressureInput: String = "", // Entrada de texto para presión arterial postprueba.
    val postTestRespiratoryRateInput: String = "", // Entrada de texto para frecuencia respiratoria postprueba.
    val postTestDyspneaBorgInput: String = "", // Entrada de texto para disnea (Borg) postprueba.
    val postTestLegPainBorgInput: String = "", // Entrada de texto para dolor en piernas (Borg) postprueba.

    // --- Valores Postprueba Parseados y Validados ---
    val postTestSystolicBP: Int? = null, // Presión arterial sistólica postprueba (parseada).
    val postTestDiastolicBP: Int? = null, // Presión arterial diastólica postprueba (parseada).
    val postTestRespiratoryRate: Int? = null, // Frecuencia respiratoria postprueba (parseada).
    val postTestDyspneaBorg: Int? = null, // Disnea (Borg) postprueba (parseada).
    val postTestLegPainBorg: Int? = null, // Dolor en piernas (Borg) postprueba (parseada).

    // --- Estado de Validación de Campos Postprueba ---
    val isPostTestBloodPressureValid: Boolean = true, // Indica si la entrada de presión arterial es válida.
    val isPostTestRespiratoryRateValid: Boolean = true, // Indica si la entrada de frecuencia respiratoria es válida.
    val isPostTestDyspneaBorgValid: Boolean = true, // Indica si la entrada de disnea (Borg) es válida.
    val isPostTestLegPainBorgValid: Boolean = true, // Indica si la entrada de dolor en piernas (Borg) es válida.
    val arePostTestValuesCompleteAndValid: Boolean = false, // Indica si TODOS los campos postprueba están completos y son válidos.
    val validationMessage: String? = null, // Mensaje de validación general para los campos postprueba.

    // --- Datos de Recuperación (Postprueba del Sensor) ---
    val recoverySpo2: Int? = null, // SpO2 medido durante el minuto de recuperación.
    val recoveryHeartRate: Int? = null, // Frecuencia cardíaca medida durante el minuto de recuperación.
    val isRecoveryPeriodOver: Boolean = false, // Indica si el minuto de recuperación ha finalizado.
    val wasRecoveryDataCapturedInitially: Boolean = false, // Indica si los datos de SpO2/FC se capturaron al final del minuto de recuperación por el TestStateHolder.
    val isAwaitingPostTimeoutRecoveryData: Boolean = false, // Indica si, tras el minuto de recuperación, aún se esperan datos de SpO2/FC (ej. por desconexión).

    // --- Observaciones ---
    val observations: String = "", // Campo de texto para observaciones adicionales.
    val showObservationsDialog: Boolean = false, // Controla la visibilidad del diálogo de observaciones.

    // --- Datos del Sensor en Tiempo Real (Durante la Recuperación o si se mantiene conectado) ---
    val currentSpo2: Int? = null, // Valor actual de SpO2 del sensor.
    val currentHeartRate: Int? = null, // Valor actual de frecuencia cardíaca del sensor.
    val spo2Trend: Trend = Trend.STABLE, // Tendencia del SpO2 (ARRIBA, ABAJO, ESTABLE).
    val spo2AlarmStatus: StatusColor = StatusColor.UNKNOWN, // Estado de alarma del SpO2 (NORMAL, WARNING, CRITICAL).
    val heartRateTrend: Trend = Trend.STABLE, // Tendencia de la frecuencia cardíaca.
    val heartRateAlarmStatus: StatusColor = StatusColor.UNKNOWN, // Estado de alarma de la FC.
    val isDeviceConnected: Boolean = false, // Indica si el dispositivo Bluetooth está actualmente conectado y transmitiendo.
    val bluetoothVisualStatus: BluetoothIconStatus2 = BluetoothIconStatus2.GRAY, // Estado visual del icono de Bluetooth.
    val bluetoothStatusMessage: String = "Iniciando...", // Mensaje descriptivo del estado de Bluetooth.
    val isAttemptingForceReconnect: Boolean = false, // Indica si se está intentando una reconexión forzada.
    val criticalAlarmMessage: String? = null, // Mensaje para alarmas críticas de SpO2/FC.

// --- Mensajes y Navegación ---
    val userMessage: String? = null, // Mensaje temporal para el usuario (ej. "Guardado exitosamente").
    val shouldNavigateToHome: Boolean = false, // Flag para indicar que se debe navegar a la pantalla de inicio.
    val showExitConfirmationDialog: Boolean = false, // Controla la visibilidad del diálogo de confirmación de salida.

    // --- Generación de PDF ---
    val isGeneratingPdf: Boolean = false, // Indica si se está generando un PDF.
    val pdfGeneratedUri: Uri? = null, // URI del archivo PDF generado.
    val pdfGenerationError: String? = null, // Mensaje de error si falla la generación del PDF.

    // --- Estado de Guardado de la Prueba ---
    val savedTestDatabaseId: Int? = null, // ID de la prueba en la base de datos una vez guardada.
    val isTestSaved: Boolean = false, // Indica si la prueba actual ya ha sido guardada en la BD.
    val savedTestNumeroPruebaPaciente: Int? = null, // Número de prueba asignado al paciente para esta prueba (una vez guardada).
    val hasUnsavedChanges: Boolean = false // Indica si hay cambios pendientes de guardar después de un guardado inicial.
)

// Patrón Regex para validar la entrada de presión arterial en formato "número/número".
// Permite 2 o 3 dígitos para sistólica y diastólica, con un "/" opcionalmente rodeado de espacios.
private val BLOOD_PRESSURE_PATTERN = Regex("^\\d{2,3}\\s*/\\s*\\d{2,3}\$")


/**
 * ViewModel para la pantalla de resultados de la prueba de caminata de 6 minutos.
 * Maneja la lógica de negocio, la interacción con los repositorios,
 * el servicio Bluetooth, y expone el estado de la UI a través de StateFlow.
 * Utiliza Hilt para la inyección de dependencias.
 */
@HiltViewModel
class TestResultsViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context, // Contexto de la aplicación, para acceso a directorios, etc.
    private val savedStateHandle: SavedStateHandle, // Para recuperar argumentos de navegación y estado guardado.
    private val bluetoothService: BluetoothService, // Servicio para interactuar con el dispositivo BLE.
    private val testStateHolder: TestStateHolder, // Holder para el estado de la prueba, especialmente datos de recuperación.
    private val pacienteRepository: PacienteRepository, // Repositorio para acceder a datos de pacientes y pruebas.
    private val gson: Gson, // Instancia de Gson para serialización/deserialización JSON.
    private val settingsRepository: SettingsRepository // Repositorio para acceder a las configuraciones de la aplicación.
) : ViewModel() {

    // Flujo mutable privado que contiene el estado actual de la UI.
    private val _uiState = MutableStateFlow(TestResultsUiState())
    // Flujo público e inmutable del estado de la UI, para ser observado por la pantalla.
    val uiState: StateFlow<TestResultsUiState> = _uiState.asStateFlow()

    // Job para controlar la corutina que limpia los mensajes de usuario después de un tiempo.
    private var userMessageClearJob: Job? = null
    // Job para la corutina que observa los datos de recuperación del TestStateHolder.
    private var recoveryDataJob: Job? = null
    // Job para la corutina que observa los datos del sensor en tiempo real del BluetoothService.
    private var liveSensorDataJob: Job? = null
    // Job para la corutina que observa el estado de la conexión Bluetooth.
    private var bluetoothStatusJob: Job? = null
    // Job para controlar la corutina que limpia los mensajes de alarma crítica.
    private var criticalAlarmMessageClearJob: Job? = null
    // Lista mutable para almacenar los últimos valores de SpO2 recibidos desde el último cálculo de tendencia.
    private var spo2ValuesSinceLastTrendCalc = mutableListOf<Int>()
    // Lista mutable para almacenar los últimos valores de frecuencia cardíaca recibidos desde el último cálculo de tendencia.
    private var hrValuesSinceLastTrendCalc = mutableListOf<Int>()
    // Timestamp de la última vez que se procesaron los datos del sensor. Usado para el muestreo.
    private var lastSensorUpdateTime = 0L
    // Flag interno para rastrear si el período de recuperación de 1 minuto ha finalizado.
    // Se actualiza desde testStateHolder.recoveryDataFlow.
    private var internalRecoveryPeriodOver = false
    // Flag interno que indica si TestStateHolder capturó datos de SpO2/FC válidos
    // exactamente al finalizar el minuto de recuperación inicial.
    private var internalRecoveryDataCapturedDuringInitialMinute = false
    // Valor de SpO2 de recuperación inicial obtenido directamente del TestStateHolder.
    private var initialRecoverySpo2FromFlow: Int? = null
    // Valor de frecuencia cardíaca de recuperación inicial obtenido directamente del TestStateHolder.
    private var initialRecoveryHrFromFlow: Int? = null

    // --- VARIABLES PARA LAS ALARMAS DESDE SETTINGS ---
    // Umbral de advertencia para SpO2. Se carga desde SettingsRepository.
    private var userSpo2WarningThreshold = DefaultThresholdValues.SPO2_WARNING_DEFAULT
    // Umbral crítico para SpO2. Se carga desde SettingsRepository.
    private var userSpo2CriticalThreshold = DefaultThresholdValues.SPO2_CRITICAL_DEFAULT
    // Umbral crítico bajo para frecuencia cardíaca. Se carga desde SettingsRepository.
    private var userHrCriticalLowThreshold = DefaultThresholdValues.HR_CRITICAL_LOW_DEFAULT
    // Umbral de advertencia bajo para frecuencia cardíaca. Se carga desde SettingsRepository.
    private var userHrWarningLowThreshold = DefaultThresholdValues.HR_WARNING_LOW_DEFAULT
    // Umbral de advertencia alto para frecuencia cardíaca. Se carga desde SettingsRepository.
    private var userHrWarningHighThreshold = DefaultThresholdValues.HR_WARNING_HIGH_DEFAULT
    // Umbral crítico alto para frecuencia cardíaca. Se carga desde SettingsRepository.
    private var userHrCriticalHighThreshold = DefaultThresholdValues.HR_CRITICAL_HIGH_DEFAULT

    // --- NUEVAS VARIABLES PARA RANGOS DE ENTRADA ---
    // Valor mínimo permitido para la entrada de SpO2. Se carga desde SettingsRepository.
    private var inputSpo2Min = DefaultThresholdValues.SPO2_INPUT_MIN_DEFAULT
    // Valor máximo permitido para la entrada de SpO2. Se carga desde SettingsRepository.
    private var inputSpo2Max = DefaultThresholdValues.SPO2_INPUT_MAX_DEFAULT
    // Valor mínimo permitido para la entrada de frecuencia cardíaca. Se carga desde SettingsRepository.
    private var inputHrMin = DefaultThresholdValues.HR_INPUT_MIN_DEFAULT
    // Valor máximo permitido para la entrada de frecuencia cardíaca. Se carga desde SettingsRepository.
    private var inputHrMax = DefaultThresholdValues.HR_INPUT_MAX_DEFAULT
    // Valor mínimo permitido para la entrada de presión arterial sistólica. Se carga desde SettingsRepository.
    private var inputBpSystolicMin = DefaultThresholdValues.BP_SYSTOLIC_INPUT_MIN_DEFAULT
    // Valor máximo permitido para la entrada de presión arterial sistólica. Se carga desde SettingsRepository.
    private var inputBpSystolicMax = DefaultThresholdValues.BP_SYSTOLIC_INPUT_MAX_DEFAULT
    // Valor mínimo permitido para la entrada de presión arterial diastólica. Se carga desde SettingsRepository.
    private var inputBpDiastolicMin = DefaultThresholdValues.BP_DIASTOLIC_INPUT_MIN_DEFAULT
    // Valor máximo permitido para la entrada de presión arterial diastólica. Se carga desde SettingsRepository.
    private var inputBpDiastolicMax = DefaultThresholdValues.BP_DIASTOLIC_INPUT_MAX_DEFAULT
    // Valor mínimo permitido para la entrada de frecuencia respiratoria. Se carga desde SettingsRepository.
    private var inputRrMin = DefaultThresholdValues.RR_INPUT_MIN_DEFAULT
    // Valor máximo permitido para la entrada de frecuencia respiratoria. Se carga desde SettingsRepository.
    private var inputRrMax = DefaultThresholdValues.RR_INPUT_MAX_DEFAULT
    // Valor mínimo permitido para la entrada de la escala de Borg. Se carga desde SettingsRepository.
    private var inputBorgMin = DefaultThresholdValues.BORG_INPUT_MIN_DEFAULT
    // Valor máximo permitido para la entrada de la escala de Borg. Se carga desde SettingsRepository.
    private var inputBorgMax = DefaultThresholdValues.BORG_INPUT_MAX_DEFAULT

    // --- NUEVOS STATES PÚBLICOS PARA LOS HINTS DE PLACEHOLDERS ---
    // Estado mutable para el hint del rango de SpO2, visible en la UI.
    private val _spo2RangeHint = mutableStateOf("Rango (${DefaultThresholdValues.SPO2_INPUT_MIN_DEFAULT}-${DefaultThresholdValues.SPO2_INPUT_MAX_DEFAULT})")
    // Estado inmutable del hint del rango de SpO2, expuesto a la UI.
    val spo2RangeHint: State<String> = _spo2RangeHint

    // Estado mutable para el hint del rango de frecuencia cardíaca, visible en la UI.
    private val _hrRangeHint = mutableStateOf("Rango (${DefaultThresholdValues.HR_INPUT_MIN_DEFAULT}-${DefaultThresholdValues.HR_INPUT_MAX_DEFAULT})")
    // Estado inmutable del hint del rango de frecuencia cardíaca, expuesto a la UI.
    val hrRangeHint: State<String> = _hrRangeHint

    // Estado mutable para el hint del rango de presión arterial, visible en la UI.
    private val _bpRangeHint = mutableStateOf("S(${DefaultThresholdValues.BP_SYSTOLIC_INPUT_MIN_DEFAULT}-${DefaultThresholdValues.BP_SYSTOLIC_INPUT_MAX_DEFAULT}), D(${DefaultThresholdValues.BP_DIASTOLIC_INPUT_MIN_DEFAULT}-${DefaultThresholdValues.BP_DIASTOLIC_INPUT_MAX_DEFAULT})")
    // Estado inmutable del hint del rango de presión arterial, expuesto a la UI.
    val bpRangeHint: State<String> = _bpRangeHint

    // Estado mutable para el hint del rango de frecuencia respiratoria, visible en la UI.
    private val _rrRangeHint = mutableStateOf("Rango (${DefaultThresholdValues.RR_INPUT_MIN_DEFAULT}-${DefaultThresholdValues.RR_INPUT_MAX_DEFAULT})")
    // Estado inmutable del hint del rango de frecuencia respiratoria, expuesto a la UI.
    val rrRangeHint: State<String> = _rrRangeHint

    // Estado mutable para el hint del rango de la escala de Borg, visible en la UI.
    private val _borgRangeHint = mutableStateOf("Rango (${DefaultThresholdValues.BORG_INPUT_MIN_DEFAULT}-${DefaultThresholdValues.BORG_INPUT_MAX_DEFAULT})")
    // Estado inmutable del hint del rango de la escala de Borg, expuesto a la UI.
    val borgRangeHint: State<String> = _borgRangeHint

    // Intervalo en milisegundos para procesar los datos del sensor (muestreo).
    private val SENSOR_PROCESSING_INTERVAL_MS = 1000L
    // Lista mutable para almacenar los valores de SpO2 utilizados para el cálculo de la nueva tendencia.
    // Esta lista se mantiene con un tamaño máximo (ej. 6 valores) para el cálculo.
    private val spo2ValuesForNewTrend = mutableListOf<Int>()
    // Lista mutable para almacenar los valores de frecuencia cardíaca utilizados para el cálculo de la nueva tendencia.
    // Similar a spo2ValuesForNewTrend.
    private val hrValuesForNewTrend = mutableListOf<Int>()

    // Bloque de inicialización del ViewModel. Se ejecuta cuando se crea una instancia del ViewModel.
    init {
        Log.d("TestResultsVM", "ViewModel inicializado.")
        // --- Cargar Umbrales de Alarma ---
        // Lanza una corutina en el viewModelScope para cargar configuraciones de forma asíncrona.
        viewModelScope.launch {
            // Carga el umbral de advertencia de SpO2 desde el repositorio.
            // .first() toma el primer valor emitido por el Flow y luego cancela la colección.
            userSpo2WarningThreshold = settingsRepository.spo2WarningThresholdFlow.first()
            // Carga el umbral crítico de SpO2.
            userSpo2CriticalThreshold = settingsRepository.spo2CriticalThresholdFlow.first()
            // Carga el umbral crítico bajo de frecuencia cardíaca.
            userHrCriticalLowThreshold = settingsRepository.hrCriticalLowThresholdFlow.first()
            // Carga el umbral de advertencia bajo de frecuencia cardíaca.
            userHrWarningLowThreshold = settingsRepository.hrWarningLowThresholdFlow.first()
            // Carga el umbral de advertencia alto de frecuencia cardíaca.
            userHrWarningHighThreshold = settingsRepository.hrWarningHighThresholdFlow.first()
            // Carga el umbral crítico alto de frecuencia cardíaca.
            userHrCriticalHighThreshold = settingsRepository.hrCriticalHighThresholdFlow.first()

            // Registra los umbrales cargados para depuración.
            Log.i("TestResultsVM", "Umbrales de SpO2/FC cargados: " +
                    "SpO2 Warn=$userSpo2WarningThreshold, SpO2 Crit=$userSpo2CriticalThreshold, " +
                    "HR CritLow=$userHrCriticalLowThreshold, HR WarnLow=$userHrWarningLowThreshold, " +
                    "HR WarnHigh=$userHrWarningHighThreshold, HR CritHigh=$userHrCriticalHighThreshold")

            // --- Cargar Rangos de Entrada ---
            // Carga el rango mínimo para la entrada de SpO2.
            inputSpo2Min = settingsRepository.spo2InputMinFlow.first()
            // Carga el rango máximo para la entrada de SpO2.
            inputSpo2Max = settingsRepository.spo2InputMaxFlow.first()
            // Carga el rango mínimo para la entrada de frecuencia cardíaca.
            inputHrMin = settingsRepository.hrInputMinFlow.first()
            // Carga el rango máximo para la entrada de frecuencia cardíaca.
            inputHrMax = settingsRepository.hrInputMaxFlow.first()
            // Carga el rango mínimo para la entrada de presión arterial sistólica.
            inputBpSystolicMin = settingsRepository.bpSystolicInputMinFlow.first()
            // Carga el rango máximo para la entrada de presión arterial sistólica.
            inputBpSystolicMax = settingsRepository.bpSystolicInputMaxFlow.first()
            // Carga el rango mínimo para la entrada de presión arterial diastólica.
            inputBpDiastolicMin = settingsRepository.bpDiastolicInputMinFlow.first()
            // Carga el rango máximo para la entrada de presión arterial diastólica.
            inputBpDiastolicMax = settingsRepository.bpDiastolicInputMaxFlow.first()
            // Carga el rango mínimo para la entrada de frecuencia respiratoria.
            inputRrMin = settingsRepository.rrInputMinFlow.first()
            // Carga el rango máximo para la entrada de frecuencia respiratoria.
            inputRrMax = settingsRepository.rrInputMaxFlow.first()
            // Carga el rango mínimo para la entrada de la escala de Borg.
            inputBorgMin = settingsRepository.borgInputMinFlow.first()
            // Carga el rango máximo para la entrada de la escala de Borg.
            inputBorgMax = settingsRepository.borgInputMaxFlow.first()

            // Actualiza los hints (placeholders) de los rangos para la UI con los valores cargados.
            _spo2RangeHint.value = "(${inputSpo2Min}-${inputSpo2Max})"
            _hrRangeHint.value = "(${inputHrMin}-${inputHrMax})"
            _bpRangeHint.value = "S(${inputBpSystolicMin}-${inputBpSystolicMax}), D(${inputBpDiastolicMin}-${inputBpDiastolicMax})"
            _rrRangeHint.value = "(${inputRrMin}-${inputRrMax})"
            _borgRangeHint.value = "(${inputBorgMin}-${inputBorgMax})"

            // Registra los rangos de entrada cargados para depuración.
            Log.i("TestResultsVM", "Rangos de Entrada cargados: " +
                    "BP Sys Min=$inputBpSystolicMin, BP Sys Max=$inputBpSystolicMax, " +
                    "RR Min=$inputRrMin, RR Max=$inputRrMax, Borg Min=$inputBorgMin, Borg Max=$inputBorgMax"
            )
        }
        // Inicia la observación de los datos de recuperación.
        observeRecoveryData()
        // Carga los datos iniciales de la prueba (ID de paciente, datos del resumen si existen).
        loadInitialData()
        // Inicia la observación de los datos del sensor en tiempo real y el estado de Bluetooth.
        observeLiveSensorDataAndBluetoothStatus()
    }

    /**
     * Carga los datos iniciales necesarios para la pantalla de resultados.
     * Esto incluye el ID del paciente y, si está disponible, el resumen de la ejecución de la prueba
     * (pasado como argumento de navegación en formato JSON).
     * También carga el nombre completo del paciente desde el repositorio.
     * Actualiza el `_uiState` con los datos cargados o mensajes de error.
     */
    fun loadInitialData() {
        // Lanza una corutina para realizar operaciones asíncronas.
        viewModelScope.launch {
            // Actualiza el estado de la UI para indicar que la carga está en progreso.
            _uiState.update { it.copy(isLoading = true) }
            // Obtiene el ID del paciente del SavedStateHandle (argumento de navegación).
            val patientIdArg: String? = savedStateHandle[AppDestinations.PATIENT_ID_ARG]
            // Obtiene el JSON con los datos del resumen de la prueba del SavedStateHandle.
            val summaryDataJson: String? = savedStateHandle[AppDestinations.TEST_FINAL_DATA_ARG]

            // Verifica si el ID del paciente es nulo. Si es así, muestra un error y termina.
            if (patientIdArg == null) {
                _uiState.update {
                    it.copy(isLoading = false, userMessage = "Error: ID de paciente no encontrado.")
                }
                // Limpia el mensaje de error después de un retraso.
                clearUserMessageAfterDelay()
                return@launch
            }

            // Variable para almacenar el objeto TestExecutionSummaryData deserializado.
            var summary: TestExecutionSummaryData? = null
            // Si hay un JSON de resumen de datos, intenta deserializarlo.
            if (summaryDataJson != null) {
                try {
                    // Deserializa el JSON a un objeto TestExecutionSummaryData usando Gson.
                    summary = gson.fromJson(summaryDataJson, TestExecutionSummaryData::class.java)
                } catch (e: Exception) {
                    // Si ocurre un error durante la deserialización, lo registra y actualiza la UI.
                    Log.e("TestResultsVM", "Error al deserializar JSON: ${e.message}")
                    _uiState.update {
                        it.copy(isLoading = false, patientId = patientIdArg, userMessage = "Error al cargar datos de la prueba.")
                    }
                    clearUserMessageAfterDelay()
                    // No se retorna aquí, se intenta cargar el nombre del paciente igualmente.
                }
            } else {
                // Si no hay JSON de resumen, lo registra.
                Log.w("TestResultsVM", "No se encontró TestExecutionSummaryData para paciente $patientIdArg.")
            }

            // Variable para almacenar el nombre completo del paciente obtenido del repositorio.
            var patientFullNameFromRepo: String? = null
            try {
                // Obtiene los datos del paciente desde el repositorio usando su ID.
                val paciente = pacienteRepository.obtenerPacientePorId(patientIdArg)
                // Asigna el nombre del paciente.
                patientFullNameFromRepo = paciente?.nombre
            } catch (e: Exception) {
                // Si ocurre un error al cargar datos del paciente, lo registra.
                Log.e("TestResultsVM", "Error al cargar datos del paciente desde el repositorio: ${e.message}")
            }

            // Si el objeto 'summary' no es nulo (es decir, se cargaron datos de una prueba previa),
            // actualiza el _uiState con esta información.
            if (summary != null) {
                // Calcula el porcentaje de la distancia teórica alcanzada.
                val percentage = if (summary.theoreticalDistance > 0) {
                    (summary.distanceMetersFinal / summary.theoreticalDistance.toFloat()) * 100
                } else 0f
                // Formatea la presión arterial basal si los valores son válidos.
                val bpFormatted = if (summary.basalBloodPressureSystolic > 0 && summary.basalBloodPressureDiastolic > 0) {
                    "${summary.basalBloodPressureSystolic}/${summary.basalBloodPressureDiastolic} mmHg"
                } else ""

                // Actualiza el _uiState con todos los datos del resumen.
                _uiState.update { currentState ->
                    currentState.copy(
                        isLoading = false, // Carga completada.
                        patientId = summary.patientId,
                        patientFullName = summary.patientFullName,
                        testDate = summary.testActualStartTimeMillis,
                        summaryData = summary, // Guarda el objeto de resumen completo.
                        totalDistanceMeters = summary.distanceMetersFinal,
                        theoreticalDistanceMeters = summary.theoreticalDistance,
                        percentageOfTheoretical = percentage,
                        minuteSnapshotsForTable = summary.minuteReadings, // Datos para la tabla de minutos.
                        stopRecordsForTable = summary.stopRecords, // Datos para la tabla de paradas.
                        numberOfStops = summary.stopRecords.size,
                        minSpo2ForDisplay = summary.minSpo2Record, // Valor mínimo de SpO2 registrado.
                        maxHeartRateForDisplay = summary.maxHeartRateRecord, // Valor máximo de FC registrado.
                        minHeartRateForDisplay = summary.minHeartRateRecord, // Valor mínimo de FC registrado.
                        // Valores basales, usando .takeIf para convertirlos a null si no son válidos (ej. <= 0).
                        basalSpo2 = summary.basalSpo2.takeIf { s -> s > 0 },
                        basalHeartRate = summary.basalHeartRate.takeIf { hr -> hr > 0 },
                        basalBloodPressureSystolic = summary.basalBloodPressureSystolic.takeIf { bp -> bp > 0 },
                        basalBloodPressureDiastolic = summary.basalBloodPressureDiastolic.takeIf { bp -> bp > 0 },
                        basalBloodPressureFormatted = bpFormatted,
                        basalRespiratoryRate = summary.basalRespiratoryRate.takeIf { rr -> rr > 0 },
                        basalDyspneaBorg = summary.basalDyspneaBorg.takeIf { b -> b >= 0 }, // Borg puede ser 0.
                        basalLegPainBorg = summary.basalLegPainBorg.takeIf { b -> b >= 0 }, // Borg puede ser 0.
                        userMessage = null, // Limpia cualquier mensaje de usuario previo.
                        // Inicializa los valores de recuperación como nulos, se llenarán después.
                        recoverySpo2 = null,
                        recoveryHeartRate = null
                    )
                }
            } else {
                // Si 'summary' es nulo (no hay datos de prueba previa o hubo un error al leerlos),
                // actualiza el _uiState con la información básica del paciente y un mensaje apropiado.
                _uiState.update {
                    it.copy(
                        isLoading = false, // Carga completada (o fallida parcialmente).
                        patientId = patientIdArg, // ID del paciente del argumento.
                        // Usa el nombre del paciente del repositorio, o "Paciente desconocido" si no se pudo cargar.
                        patientFullName = patientFullNameFromRepo ?: "Paciente desconocido",
                        testDate = System.currentTimeMillis(), // Fecha actual como placeholder.
                        summaryData = null, // No hay datos de resumen.
                        // Mensaje al usuario indicando la situación.
                        userMessage = if (summaryDataJson != null) "Error al leer datos de la prueba." else "No hay datos de prueba previa. Ingrese valores postprueba."
                    )
                }
                // Si no había JSON de resumen (es una entrada nueva), muestra el mensaje por más tiempo.
                if (summaryDataJson == null) clearUserMessageAfterDelay(4000)
            }
            // Valida todos los campos postprueba para actualizar su estado inicial (probablemente todos incompletos).
            validateAllPostTestFields()
        }
    }

    /**
     * Observa el flujo `recoveryDataFlow` del `TestStateHolder`.
     * Este flujo emite datos de SpO2 y FC capturados durante el minuto de recuperación postprueba,
     * y también indica si el período de recuperación ha terminado y si se capturaron datos válidos.
     * Actualiza el `_uiState` con estos datos y gestiona el estado `isAwaitingPostTimeoutRecoveryData`.
     */
    fun observeRecoveryData() {
        // Cancela cualquier job de observación anterior para evitar múltiples colectores.
        recoveryDataJob?.cancel()
        // Lanza una nueva corutina para observar el flujo.
        recoveryDataJob = viewModelScope.launch {
            // Colecta los últimos datos emitidos por recoveryDataFlow.
            // collectLatest cancela el bloque anterior si llega un nuevo valor antes de que termine de procesarse.
            testStateHolder.recoveryDataFlow.collectLatest { recoveryData: RecoveryData ->
                // Registra los datos recibidos para depuración.
                Log.d(
                    "TestResultsVM",
                    "RecoveryDataFlow rcvd: SpO2=${recoveryData.spo2}, HR=${recoveryData.hr}, PeriodOver=${recoveryData.isRecoveryPeriodOver}, Captured=${recoveryData.wasDataCapturedDuringPeriod}"
                )

                // Actualiza las variables internas con los datos del flujo.
                internalRecoveryPeriodOver = recoveryData.isRecoveryPeriodOver
                internalRecoveryDataCapturedDuringInitialMinute = recoveryData.wasDataCapturedDuringPeriod
                // Usa takeIf para asegurar que solo se almacenan valores de SpO2/FC > 0.
                initialRecoverySpo2FromFlow = recoveryData.spo2?.takeIf { it > 0 }
                initialRecoveryHrFromFlow = recoveryData.hr?.takeIf { it > 0 }

                // Obtiene el estado actual de 'isAwaitingPostTimeoutRecoveryData' y los valores de recuperación del UIState.
                var newAwaitingPostTimeout = _uiState.value.isAwaitingPostTimeoutRecoveryData
                var finalRecoverySpo2: Int? = _uiState.value.recoverySpo2
                var finalRecoveryHr: Int? = _uiState.value.recoveryHeartRate

                // Lógica principal basada en si el período de recuperación ha terminado.
                if (internalRecoveryPeriodOver) {
                    // El minuto de recuperación ha terminado.
                    if (internalRecoveryDataCapturedDuringInitialMinute && initialRecoverySpo2FromFlow != null && initialRecoveryHrFromFlow != null) {
                        // Se capturaron datos válidos (ambos SpO2 y FC) EXACTAMENTE al final del minuto por RecoveryDataFlow. Úsalos.
                        finalRecoverySpo2 = initialRecoverySpo2FromFlow
                        finalRecoveryHr = initialRecoveryHrFromFlow
                        newAwaitingPostTimeout = false // Ya tenemos los datos, no necesitamos esperar.
                        Log.i("TestResultsVM", "RECOVERY: Periodo terminado. Datos CAPTURADOS por RecoveryDataFlow: SpO2=$finalRecoverySpo2, HR=$finalRecoveryHr")
                    } else {
                        // El minuto terminó, pero RecoveryDataFlow NO proveyó datos válidos en ese instante
                        // (o solo proveyó uno de ellos, o ninguno).
                        // Ahora dependeremos de los datos del sensor en tiempo real (liveSensorData) para rellenarlos
                        // si el sensor está conectado, o el usuario tendrá que actuar (ej. reconectar).
                        // Si ya tenemos valores en el UIState (quizás de una reconexión previa y live data), no los borramos.
                        if (finalRecoverySpo2 == null || finalRecoveryHr == null) {
                            // Si alguno (o ambos) de los valores de recuperación sigue siendo null.
                            newAwaitingPostTimeout = true // Necesitamos esperar/obtenerlos, ya sea por live data o acción del usuario.
                            Log.i("TestResultsVM", "RECOVERY: Periodo terminado. NO se capturaron datos completos por RecoveryDataFlow. Se esperará a live data o acción del usuario.")
                        } else {
                            // Ya teníamos ambos valores (quizás de un live data anterior tras una reconexión), y el periodo terminó.
                            // En este caso, ya no estamos "awaiting" por datos que ya tenemos.
                            newAwaitingPostTimeout = false
                        }
                    }
                } else {
                    // El minuto de recuperación AÚN NO ha terminado.
                    // NO actualizamos recoverySpo2/HR en uiState todavía con los valores de initialRecovery...FromFlow.
                    // Los valores de recuperación en la UI se mantendrán como null (o sus valores previos si se reconectó)
                    // hasta que el período termine, para mostrar "Esperando..." o similar.
                    finalRecoverySpo2 = null // Forzar a null en la UI mientras el periodo no termina para que muestre "esperando".
                    finalRecoveryHr = null   // Forzar a null en la UI mientras el periodo no termina.
                    newAwaitingPostTimeout = false // No estamos "post-timeout" si el timeout (fin del minuto) aún no ha ocurrido.
                    Log.d("TestResultsVM", "RECOVERY: Periodo AÚN NO terminado. recoverySpo2/HR se mantienen null en UI.")
                }

                // Actualiza el _uiState con los nuevos valores de recuperación y estados relacionados.
                _uiState.update { currentState ->
                    currentState.copy(
                        recoverySpo2 = finalRecoverySpo2,
                        recoveryHeartRate = finalRecoveryHr,
                        isRecoveryPeriodOver = internalRecoveryPeriodOver, // Actualiza el estado del período en la UI.
                        wasRecoveryDataCapturedInitially = internalRecoveryDataCapturedDuringInitialMinute, // Actualiza si se capturaron inicialmente.
                        isAwaitingPostTimeoutRecoveryData = newAwaitingPostTimeout // Actualiza si se está esperando datos post-timeout.
                    )
                }
                // Revalida todos los campos postprueba cada vez que cambian estos estados,
                // ya que la completitud de los datos de recuperación afecta la validación general.
                validateAllPostTestFields()
            }
        }
    }

    /**
     * Inicia la observación de los datos del sensor en tiempo real (SpO2, FC) desde `bluetoothService.bleDeviceData`
     * y el estado de la conexión Bluetooth desde `bluetoothService.connectionStatus`.
     * Procesa los datos del sensor con un intervalo de muestreo, calcula tendencias,
     * determina estados de alarma y actualiza el `_uiState`.
     * También gestiona la lógica para rellenar los datos de recuperación si el período ha terminado
     * y los datos no se capturaron inicialmente.
     */
    fun observeLiveSensorDataAndBluetoothStatus() {
        // Cancela jobs existentes para evitar múltiples observadores si esta función se llama de nuevo.
        liveSensorDataJob?.cancel()
        Log.d("TestResultsVM_TREND", "Re-iniciando observación de datos del sensor (con muestreo de 1s).")

        // Limpia listas y resetea el estado de tendencia al (re)iniciar la observación
        // para asegurar que no se usan datos viejos para calcular nuevas tendencias.
        spo2ValuesForNewTrend.clear() // Lista principal para el cálculo de tendencia de SpO2 (últimos N valores).
        hrValuesForNewTrend.clear()   // Lista principal para el cálculo de tendencia de FC.
        spo2ValuesSinceLastTrendCalc.clear() // Lista temporal para acumular nuevos valores de SpO2 desde el último cálculo de tendencia.
        hrValuesSinceLastTrendCalc.clear()   // Lista temporal para acumular nuevos valores de FC.
        lastSensorUpdateTime = 0L // Resetea el temporizador de procesamiento para el muestreo.

        // Resetea tendencias y valores actuales en UiState para evitar mostrar datos viejos
        // hasta que lleguen los nuevos datos muestreados.
        _uiState.update { currentState ->
            currentState.copy(
                currentSpo2 = null, // SpO2 actual del sensor.
                currentHeartRate = null, // FC actual del sensor.
                spo2Trend = Trend.STABLE, // Tendencia de SpO2.
                heartRateTrend = Trend.STABLE, // Tendencia de FC.
                spo2AlarmStatus = StatusColor.UNKNOWN, // Estado de alarma de SpO2.
                heartRateAlarmStatus = StatusColor.UNKNOWN, // Estado de alarma de FC.
                criticalAlarmMessage = null // Limpia mensajes de alarma críticos viejos.
            )
        }

        // Observa el flujo de datos del dispositivo BLE (BleDeviceData).
        liveSensorDataJob = bluetoothService.oximeterDeviceData
            .onEach { data: BleDeviceData -> // Se ejecuta cada vez que el servicio emite nuevos datos del sensor.
                val currentTime = System.currentTimeMillis()

                // Procesa los datos solo si ha pasado al menos SENSOR_PROCESSING_INTERVAL_MS (1 segundo)
                // desde la última vez que se procesaron. Esto implementa un muestreo.
                if (currentTime - lastSensorUpdateTime >= SENSOR_PROCESSING_INTERVAL_MS) {
                    lastSensorUpdateTime = currentTime // Actualiza el timestamp del último procesamiento.
                    Log.d("TestResultsVM_SENSOR", "PROCESANDO DATOS (Muestreo 1s): SpO2=${data.spo2}, HR=${data.heartRate}, NoFinger=${data.noFingerDetected}, Signal=${data.signalStrength}")

                    // Captura el estado actual DEL UI ANTES de este ciclo de procesamiento.
                    // Esto es importante para comparar y tomar decisiones basadas en el estado previo.
                    val currentUiStateValues = _uiState.value

                    // --- A. Determinar si hay datos válidos del sensor (dedo puesto y señal adecuada) ---
                    val hasValidFingerData = !( // Verdadero si el dedo está puesto y los datos son válidos.
                            data.noFingerDetected == true || // Si el sensor indica que no hay dedo.
                                    data.signalStrength == NO_FINGER_OR_RECALIBRATING_SIGNAL || // Si la señal indica que no hay dedo o se está recalibrando.
                                    data.spo2 == null || data.spo2 == 0 || // Si SpO2 es nulo o cero.
                                    data.heartRate == null || data.heartRate == 0 // Si FC es nulo o cero.
                            )

                    // Variables para los nuevos valores actuales de SpO2 y FC, y sus estados de alarma.
                    var newCurrentSpo2: Int? = null
                    var newCurrentHeartRate: Int? = null
                    var newSpo2Alarm = StatusColor.UNKNOWN
                    var newHrAlarm = StatusColor.UNKNOWN

                    // Variables para las tendencias finales que se aplicarán en esta actualización.
                    // Inicializadas con las tendencias actuales del UIState.
                    var finalSpo2TrendForThisUpdate: Trend = currentUiStateValues.spo2Trend
                    var finalHrTrendForThisUpdate: Trend = currentUiStateValues.heartRateTrend

                    // Variables para los datos de recuperación.
                    // Se inicializan con los valores actuales del UIState.
                    var updatedRecoverySpo2 = currentUiStateValues.recoverySpo2
                    var updatedRecoveryHeartRate = currentUiStateValues.recoveryHeartRate
                    var newIsAwaitingPostTimeoutRecoveryData = currentUiStateValues.isAwaitingPostTimeoutRecoveryData
                    var userMessageForRecoveryUpdate: String? = null // Mensaje para el usuario si los datos de recuperación se actualizan.

                    var recoveryDataJustCapturedByLive = false // Flag para indicar si los datos de recuperación se acaban de capturar en este ciclo.

                    // Si hay datos válidos del sensor (dedo puesto):
                    if (hasValidFingerData) {
                        newCurrentSpo2 = data.spo2 // Asigna el SpO2 actual.
                        newCurrentHeartRate = data.heartRate // Asigna la FC actual.
                        Log.d("TestResultsVM_TREND", "Dedo PUESTO. SpO2: $newCurrentSpo2, HR: $newCurrentHeartRate")

                        // ***** INICIO: Lógica de RELLENO para datos de recuperación *****
                        // Intenta rellenar los datos de recuperación (SpO2 y FC postprueba)
                        // utilizando los datos actuales del sensor si se cumplen ciertas condiciones.
                        // Solo se intenta rellenar si:
                        // 1. El minuto de recuperación YA HA TERMINADO (internalRecoveryPeriodOver es true).
                        // 2. Y todavía faltan datos de recuperación en el UIState (updatedRecoverySpo2 o updatedRecoveryHeartRate son null).
                        if (internalRecoveryPeriodOver) {
                            if (updatedRecoverySpo2 == null && newCurrentSpo2 != null && newCurrentSpo2 > 0) {
                                // Si el SpO2 de recuperación es null y el SpO2 actual es válido.
                                updatedRecoverySpo2 = newCurrentSpo2 // Actualiza el SpO2 de recuperación.
                                Log.i("TestResultsVM", "LIVE_FILL: recoverySpo2 ($updatedRecoverySpo2) actualizado por live sensor data POST-PERIODO.")
                                recoveryDataJustCapturedByLive = true // Marca que se capturó un dato de recuperación.
                            }
                            if (updatedRecoveryHeartRate == null && newCurrentHeartRate != null && newCurrentHeartRate > 0) {
                                // Si la FC de recuperación es null y la FC actual es válida.
                                updatedRecoveryHeartRate = newCurrentHeartRate // Actualiza la FC de recuperación.
                                Log.i("TestResultsVM", "LIVE_FILL: recoveryHeartRate ($updatedRecoveryHeartRate) actualizado por live sensor data POST-PERIODO.")
                                recoveryDataJustCapturedByLive = true // Marca que se capturó un dato de recuperación.
                            }

                            // Si se acaba de capturar algún dato de recuperación mediante live data:
                            if (recoveryDataJustCapturedByLive) {
                                if (updatedRecoverySpo2 != null && updatedRecoveryHeartRate != null) {
                                    // Si ya se tienen AMBOS datos de recuperación.
                                    // 'newIsAwaitingPostTimeoutRecoveryData' se gestiona de forma más robusta
                                    // en observeRecoveryData y onBluetoothIconClicked. Aquí solo se confirma que tenemos los datos.
                                    if(newIsAwaitingPostTimeoutRecoveryData){ // Si estábamos esperando (UI indicaba "conectar sensor" o similar) y ya los tenemos.
                                        userMessageForRecoveryUpdate = "Datos de recuperación obtenidos."
                                    }
                                } else if (updatedRecoverySpo2 != null) {
                                    // Si solo se obtuvo el SpO2.
                                    userMessageForRecoveryUpdate = "SpO2 de recuperación actualizada. Esperando FC..."
                                } else if (updatedRecoveryHeartRate != null) {
                                    // Si solo se obtuvo la FC.
                                    userMessageForRecoveryUpdate = "FC de recuperación actualizada. Esperando SpO2..."
                                }
                            }
                        } else {
                            // El minuto de recuperación AÚN NO ha terminado.
                            // NO se rellenan updatedRecoverySpo2/Hr aquí, incluso si hay datos válidos del sensor.
                            // Se deja que observeRecoveryData maneje la lógica de captura inicial al final del minuto.
                            Log.d("TestResultsVM", "LIVE_SENSOR: Periodo de recuperación NO terminado. No se actualizan recoverySpo2/HR desde live data todavía.")
                        }
                        // ***** FIN: Lógica de RELLENO para datos de recuperación *****


                        // --- LÓGICA DE TENDENCIA SpO2 ---
                        newCurrentSpo2?.let { // Si newCurrentSpo2 no es null.
                            spo2ValuesForNewTrend.add(it) // Añade el valor a la lista principal de SpO2 para tendencia.
                            spo2ValuesSinceLastTrendCalc.add(it) // Añade el valor a la lista temporal de SpO2.
                        }
                        // Mantiene la lista spo2ValuesForNewTrend con un tamaño máximo de 6 (para comparar 2 grupos de 3).
                        while (spo2ValuesForNewTrend.size > 6) {
                            if (spo2ValuesForNewTrend.isNotEmpty()) spo2ValuesForNewTrend.removeAt(0) // Elimina el más antiguo.
                        }
                        Log.d("TestResultsVM_TREND", "SpO2 Lists: main=${spo2ValuesForNewTrend.joinToString()}, sinceLastCalc=${spo2ValuesSinceLastTrendCalc.joinToString()}")

                        // Si se han acumulado al menos 3 nuevos valores de SpO2 desde el último cálculo de tendencia.
                        if (spo2ValuesSinceLastTrendCalc.size >= 3) {
                            if (spo2ValuesForNewTrend.size == 6) { // Si ya tenemos 6 valores en la lista principal.
                                // Calcula la nueva tendencia de SpO2.
                                finalSpo2TrendForThisUpdate = calculateTrendFromAverageOfLastThree(spo2ValuesForNewTrend.toList())
                                Log.i("TestResultsVM_TREND", "SpO2: CALCULADA nueva tendencia: $finalSpo2TrendForThisUpdate (desde ${spo2ValuesForNewTrend.toList()})")
                            } else {
                                // Hay 3 nuevos valores, pero aún no suficientes (menos de 6) para un cálculo completo de tendencia.
                                // Se mantiene la tendencia anterior.
                                Log.d("TestResultsVM_TREND", "SpO2: 3 nuevos valores, pero menos de 6 en total (${spo2ValuesForNewTrend.size}). Manteniendo tendencia: $finalSpo2TrendForThisUpdate")
                            }
                            spo2ValuesSinceLastTrendCalc.clear() // Limpia la lista temporal después del cálculo o intento.
                        } else {
                            // No hay suficientes nuevos valores (menos de 3) para recalcular la tendencia.
                            // Se mantiene la tendencia anterior.
                            Log.d("TestResultsVM_TREND", "SpO2: Menos de 3 nuevos valores (${spo2ValuesSinceLastTrendCalc.size}). Manteniendo tendencia: $finalSpo2TrendForThisUpdate")
                        }

                        // --- LÓGICA DE TENDENCIA HR (similar a SpO2) ---
                        newCurrentHeartRate?.let {
                            hrValuesForNewTrend.add(it)
                            hrValuesSinceLastTrendCalc.add(it)
                        }
                        while (hrValuesForNewTrend.size > 6) {
                            if (hrValuesForNewTrend.isNotEmpty()) hrValuesForNewTrend.removeAt(0)
                        }
                        Log.d("TestResultsVM_TREND", "HR Lists: main=${hrValuesForNewTrend.joinToString()}, sinceLastCalc=${hrValuesSinceLastTrendCalc.joinToString()}")

                        if (hrValuesSinceLastTrendCalc.size >= 3) {
                            if (hrValuesForNewTrend.size == 6) {
                                finalHrTrendForThisUpdate = calculateTrendFromAverageOfLastThree(hrValuesForNewTrend.toList())
                                Log.i("TestResultsVM_TREND", "HR: CALCULADA nueva tendencia: $finalHrTrendForThisUpdate (desde ${hrValuesForNewTrend.toList()})")
                            } else {
                                Log.d("TestResultsVM_TREND", "HR: 3 nuevos valores, pero menos de 6 en total (${hrValuesForNewTrend.size}). Manteniendo tendencia: $finalHrTrendForThisUpdate")
                            }
                            hrValuesSinceLastTrendCalc.clear()
                        } else {
                            Log.d("TestResultsVM_TREND", "HR: Menos de 3 nuevos valores (${hrValuesSinceLastTrendCalc.size}). Manteniendo tendencia: $finalHrTrendForThisUpdate")
                        }

                        // Determina el estado de alarma para SpO2 y FC actuales.
                        newSpo2Alarm = newCurrentSpo2?.let { determineSpo2AlarmStatus(it) } ?: StatusColor.UNKNOWN
                        newHrAlarm = newCurrentHeartRate?.let { determineHeartRateAlarmStatus(it) } ?: StatusColor.UNKNOWN

                    } else { // Si no hay datos válidos del sensor (dedo no puesto o señal mala).
                        Log.w("TestResultsVM_TREND", "Dedo NO PUESTO o datos inválidos (Muestreo 1s). Reseteando listas y tendencias.")
                        // Limpia las listas de tendencia.
                        spo2ValuesForNewTrend.clear()
                        hrValuesForNewTrend.clear()
                        spo2ValuesSinceLastTrendCalc.clear()
                        hrValuesSinceLastTrendCalc.clear()
                        // Resetea las tendencias a ESTABLE.
                        finalSpo2TrendForThisUpdate = Trend.STABLE
                        finalHrTrendForThisUpdate = Trend.STABLE
                        // (newCurrentSpo2 y newCurrentHeartRate permanecen null, newSpo2Alarm y newHrAlarm permanecen UNKNOWN)
                    }

                    // --- B. Determinar estado visual del Bluetooth ---
                    // Esta parte utiliza el 'data' del ciclo actual (el BleDeviceData más reciente) y el estado de conexión del servicio.
                    val (currentBtIcon, currentBtMsg) = determineResultsBluetoothVisualStatus(
                        connectionStatus = bluetoothService.oximeterConnectionStatus.value, // Estado actual de la conexión.
                        deviceData = data, // Datos del sensor de este ciclo.
                        isBluetoothAdapterEnabled = bluetoothService.isBluetoothEnabled() // Si el adaptador BT está encendido.
                    )

                    // --- C. Manejar Mensaje de Alarma Crítica ---
                    // Gestiona la visualización y borrado automático de mensajes de alarma crítica.
                    var alarmMessageToShow: String? = currentUiStateValues.criticalAlarmMessage // Mensaje de alarma actual en UI.
                    var shouldStartNewTimerForCriticalAlarm = false // Flag para reiniciar el temporizador del mensaje.
                    val previousUiStateSpo2Alarm = currentUiStateValues.spo2AlarmStatus // Estado de alarma SpO2 previo.
                    val previousUiStateHrAlarm = currentUiStateValues.heartRateAlarmStatus // Estado de alarma FC previo.

                    // Si la nueva alarma de SpO2 es CRÍTICA y hay un valor de SpO2.
                    if (newSpo2Alarm == StatusColor.CRITICAL && newCurrentSpo2 != null) {
                        val spo2Msg = "¡Alerta! SpO2 en nivel crítico: $newCurrentSpo2%"
                        // Añade el mensaje de SpO2 al mensaje de alarma general, evitando duplicados.
                        alarmMessageToShow = if (_uiState.value.criticalAlarmMessage?.contains(spo2Msg) == false) {
                            _uiState.value.criticalAlarmMessage?.let { "$it\n$spo2Msg" } ?: spo2Msg
                        } else {
                            _uiState.value.criticalAlarmMessage ?: spo2Msg
                        }
                        // Si la alarma de SpO2 acaba de volverse crítica, se debe iniciar un nuevo temporizador.
                        if (previousUiStateSpo2Alarm != StatusColor.CRITICAL) {
                            shouldStartNewTimerForCriticalAlarm = true
                        }
                    } else if (previousUiStateSpo2Alarm == StatusColor.CRITICAL && newSpo2Alarm != StatusColor.CRITICAL) {
                        // Si la alarma de SpO2 dejó de ser crítica, elimina el mensaje de SpO2 del general.
                        alarmMessageToShow = alarmMessageToShow?.lines()?.filterNot { it.contains("SpO2") }?.joinToString("\n")
                        if (alarmMessageToShow?.isBlank() == true) alarmMessageToShow = null // Si queda vacío, poner a null.
                    }

                    // Lógica similar para la alarma de Frecuencia Cardíaca.
                    if (newHrAlarm == StatusColor.CRITICAL && newCurrentHeartRate != null) {
                        val hrMsg = "¡Alerta! Frecuencia Cardíaca en nivel crítico: $newCurrentHeartRate lpm"
                        alarmMessageToShow = if (_uiState.value.criticalAlarmMessage?.contains(hrMsg) == false) {
                            alarmMessageToShow?.let { "$it\n$hrMsg" } ?: hrMsg
                        } else {
                            alarmMessageToShow ?: hrMsg
                        }
                        if (previousUiStateHrAlarm != StatusColor.CRITICAL) {
                            shouldStartNewTimerForCriticalAlarm = true
                        }
                    } else if (previousUiStateHrAlarm == StatusColor.CRITICAL && newHrAlarm != StatusColor.CRITICAL) {
                        alarmMessageToShow = alarmMessageToShow?.lines()?.filterNot { it.contains("Frecuencia Cardíaca") }?.joinToString("\n")
                        if (alarmMessageToShow?.isBlank() == true) alarmMessageToShow = null
                    }

                    // Si se debe iniciar un nuevo temporizador y hay un mensaje de alarma, inicia el temporizador.
                    if (shouldStartNewTimerForCriticalAlarm && alarmMessageToShow != null) {
                        clearCriticalAlarmMessageAfterDelay()
                    }

                    // Si ninguna alarma es crítica actualmente pero había un mensaje de alarma, límpialo.
                    if (newSpo2Alarm != StatusColor.CRITICAL && newHrAlarm != StatusColor.CRITICAL && _uiState.value.criticalAlarmMessage != null) {
                        alarmMessageToShow = null
                        clearCriticalAlarmMessage() // Cancela el temporizador también.
                    }

                    // --- D. Actualizar UiState ---
                    Log.d("TestResultsVM_TREND", "ACTUALIZANDO UI STATE (Muestreo 1s). SpO2 Trend: $finalSpo2TrendForThisUpdate, HR Trend: $finalHrTrendForThisUpdate")

                    var recoveryDataChangedInThisUpdate = false // Flag para saber si los valores de SpO2/FC de recuperación cambiaron en esta actualización.
                    val previousRecoverySpo2 = _uiState.value.recoverySpo2 // Valor de SpO2 de recuperación antes de esta actualización.
                    val previousRecoveryHr = _uiState.value.recoveryHeartRate // Valor de FC de recuperación antes de esta actualización.

                    // Actualiza el _uiState con todos los nuevos valores calculados y determinados.
                    _uiState.update { currentStateInternal ->
                        // Determina los nuevos valores de recuperación para el UIState.
                        // Solo se actualizan si el período de recuperación ha terminado.
                        // Si no ha terminado, se mantienen los que ya estaban en el UI (probablemente null o de una reconexión).
                        val newRecoverySpo2Value = if (internalRecoveryPeriodOver) updatedRecoverySpo2 else currentStateInternal.recoverySpo2
                        val newRecoveryHrValue = if (internalRecoveryPeriodOver) updatedRecoveryHeartRate else currentStateInternal.recoveryHeartRate

                        // Comprueba si los valores de recuperación realmente cambiaron respecto al estado anterior del UI.
                        if (newRecoverySpo2Value != previousRecoverySpo2 || newRecoveryHrValue != previousRecoveryHr) {
                            recoveryDataChangedInThisUpdate = true
                        }

                        currentStateInternal.copy(
                            currentSpo2 = newCurrentSpo2, // SpO2 actual del sensor.
                            currentHeartRate = newCurrentHeartRate, // FC actual del sensor.
                            spo2Trend = finalSpo2TrendForThisUpdate, // Tendencia de SpO2.
                            heartRateTrend = finalHrTrendForThisUpdate, // Tendencia de FC.
                            spo2AlarmStatus = newSpo2Alarm, // Estado de alarma de SpO2.
                            heartRateAlarmStatus = newHrAlarm, // Estado de alarma de FC.
                            criticalAlarmMessage = alarmMessageToShow, // Mensaje de alarma crítica.
                            // Estado visual del Bluetooth: si se está intentando reconectar, muestra "CONNECTING", sino el estado determinado.
                            bluetoothVisualStatus = if (currentStateInternal.isAttemptingForceReconnect) BluetoothIconStatus2.CONNECTING else currentBtIcon,
                            // Mensaje de estado del Bluetooth: si se está intentando reconectar, muestra "Reconectando...", sino el mensaje determinado.
                            bluetoothStatusMessage = if (currentStateInternal.isAttemptingForceReconnect) "Reconectando..." else currentBtMsg,

                            // Actualiza valores de recuperación y el flag 'isAwaitingPostTimeoutRecoveryData'.
                            recoverySpo2 = newRecoverySpo2Value,
                            recoveryHeartRate = newRecoveryHrValue,
                            // 'newIsAwaitingPostTimeoutRecoveryData' se determinó al inicio de este bloque 'onEach'
                            // y se actualiza aquí en el UIState.
                            isAwaitingPostTimeoutRecoveryData = newIsAwaitingPostTimeoutRecoveryData,
                            // Mensaje para el usuario: usa el mensaje de actualización de recuperación si existe,
                            // sino, mantiene cualquier mensaje de usuario existente.
                            userMessage = userMessageForRecoveryUpdate ?: currentStateInternal.userMessage
                        )
                    }

                    // Si los datos de recuperación (SpO2 o FC) cambiaron en esta actualización,
                    // O si se acaba de capturar un dato de recuperación por live data y había un mensaje al respecto (indicando que se estaba esperando),
                    // entonces, se deben revalidar todos los campos postprueba.
                    if (recoveryDataChangedInThisUpdate || (recoveryDataJustCapturedByLive && userMessageForRecoveryUpdate != null)) {
                        validateAllPostTestFields() // Llamar DESPUÉS de actualizar el state para que la validación use los últimos valores.
                    }

                    // Si se generó un mensaje para el usuario relacionado con la actualización de datos de recuperación,
                    // programa su limpieza después de un retraso.
                    if (userMessageForRecoveryUpdate != null) {
                        clearUserMessageAfterDelay()
                    }

                } else {
                    // Si no ha pasado el intervalo SENSOR_PROCESSING_INTERVAL_MS, los datos se descartan (no se procesan).
                    // Opcional: Log para ver la frecuencia real de llegada de datos del sensor.
                    Log.v("TestResultsVM_SENSOR", "DATO DESCARTADO (muy frecuente, no ha pasado 1s): SpO2=${data.spo2}, HR=${data.heartRate}")
                }
            }
            .catch { e -> // Bloque para manejar cualquier excepción que ocurra en el flujo de BleDeviceData.
                Log.e("TestResultsVM_TREND", "Error en flow de BleDeviceData: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        userMessage = "Error al procesar datos del sensor.",
                        // Limpia valores del sensor en UI también en caso de error del flow.
                        currentSpo2 = null,
                        currentHeartRate = null,
                        spo2Trend = Trend.STABLE,
                        heartRateTrend = Trend.STABLE,
                        spo2AlarmStatus = StatusColor.UNKNOWN,
                        heartRateAlarmStatus = StatusColor.UNKNOWN
                    )
                }
                clearUserMessageAfterDelay()
            }
            .launchIn(viewModelScope) // Lanza la colección del flujo en el viewModelScope.

        // Observa el flujo del estado de la conexión Bluetooth (BleConnectionStatus).
        // Este observador es importante para actualizar la UI según el estado general de la conexión
        // (conectado, desconectado, error, etc.), independientemente de los datos del sensor.
        // Se asegura de no relanzarlo si ya está activo.
        if (bluetoothStatusJob == null || bluetoothStatusJob?.isActive == false) {
            bluetoothStatusJob = bluetoothService.oximeterConnectionStatus
                .onEach { status: BleConnectionStatus -> // Se ejecuta cada vez que el estado de la conexión cambia.
                    _uiState.update { currentState ->
                        // Determina el nuevo icono y mensaje de estado de Bluetooth basados en el nuevo 'status'.
                        val (newIcon, newMsg) = determineResultsBluetoothVisualStatus(
                            connectionStatus = status,
                            deviceData = bluetoothService.oximeterDeviceData.value, // Puede ser el último valor conocido o null.
                            isBluetoothAdapterEnabled = bluetoothService.isBluetoothEnabled()
                        )
                        // Determina si el dispositivo se considera conectado o suscrito basado en el 'status'.
                        val isConnectedByStatus = status.isConsideredConnectedOrSubscribed()

                        // Lógica para manejar el flag 'isAttemptingForceReconnect'.
                        // Si se estaba intentando una reconexión forzada y el estado cambia a uno "final"
                        // (suscrito, error, desconectado por usuario, o idle), se resetea el flag.
                        var isStillAttemptingReconnect = currentState.isAttemptingForceReconnect
                        if (isStillAttemptingReconnect && (status == BleConnectionStatus.SUBSCRIBED || status.isErrorStatus() || status == BleConnectionStatus.DISCONNECTED_BY_USER || status == BleConnectionStatus.IDLE)) {
                            isStillAttemptingReconnect = false
                        }

                        // Lógica para el flag 'isAwaitingPostTimeoutRecoveryData' basada en el estado de conexión.
                        var currentAwaiting = currentState.isAwaitingPostTimeoutRecoveryData
                        if (internalRecoveryPeriodOver) { // Solo importa "awaiting" si el periodo de recuperación ya terminó.
                            if (!isConnectedByStatus && (currentState.recoverySpo2 == null || currentState.recoveryHeartRate == null)) {
                                // Si está desconectado, el periodo terminó, y faltan datos de recuperación -> sí, estamos esperando.
                                currentAwaiting = true
                            } else if (isConnectedByStatus && (currentState.recoverySpo2 != null && currentState.recoveryHeartRate != null)) {
                                // Si está conectado, el periodo terminó y TENEMOS ambos datos de recuperación -> no estamos esperando.
                                currentAwaiting = false
                            }
                            // Si está conectado pero aún faltan datos, 'currentAwaiting' se mantendrá como estaba (probablemente true).
                            // Si está desconectado pero ya tenemos ambos datos (raro, pero posible si se desconectó justo después de obtenerlos),
                            // 'currentAwaiting' se mantendrá como estaba (probablemente false).
                        } else {
                            // Si el periodo de recuperación aún no ha terminado, no estamos en estado "awaiting post timeout".
                            currentAwaiting = false
                        }

                        currentState.copy(
                            isDeviceConnected = isConnectedByStatus, // Actualiza el estado de conexión general.
                            // Si se está intentando reconectar, muestra el icono y mensaje de "CONNECTING"/"Reconectando...",
                            // sino, muestra el icono y mensaje determinados por el estado actual.
                            bluetoothVisualStatus = if (isStillAttemptingReconnect) BluetoothIconStatus2.CONNECTING else newIcon,
                            bluetoothStatusMessage = if (isStillAttemptingReconnect) "Reconectando..." else newMsg,
                            isAttemptingForceReconnect = isStillAttemptingReconnect, // Actualiza el flag de intento de reconexión.
                            isAwaitingPostTimeoutRecoveryData = currentAwaiting, // Actualiza el flag de espera de datos de recuperación.

                            currentSpo2 = if (!isConnectedByStatus && !isStillAttemptingReconnect) null else currentState.currentSpo2,
                            currentHeartRate = if (!isConnectedByStatus && !isStillAttemptingReconnect) null else currentState.currentHeartRate,
                            spo2Trend = if (!isConnectedByStatus && !isStillAttemptingReconnect) Trend.STABLE else currentState.spo2Trend,
                            heartRateTrend = if (!isConnectedByStatus && !isStillAttemptingReconnect) Trend.STABLE else currentState.heartRateTrend,
                            spo2AlarmStatus = if (!isConnectedByStatus && !isStillAttemptingReconnect) StatusColor.UNKNOWN else currentState.spo2AlarmStatus,
                            heartRateAlarmStatus = if (!isConnectedByStatus && !isStillAttemptingReconnect) StatusColor.UNKNOWN else currentState.heartRateAlarmStatus,
                            criticalAlarmMessage = if (!isConnectedByStatus && !isStillAttemptingReconnect) null else currentState.criticalAlarmMessage
                        )
                    }
                    // Importante: revalidar si el estado de conexión cambia, ya que puede afectar
                    // si podemos obtener los datos de recuperación.
                    if (internalRecoveryPeriodOver) {
                        validateAllPostTestFields()
                    }
                }
                .catch { e ->
                    Log.e("TestResultsVM", "Error en flow de BleConnectionStatus: ${e.message}", e)
                    _uiState.update { it.copy(userMessage = "Error de conexión Bluetooth.") }
                    clearUserMessageAfterDelay()
                }
                .launchIn(viewModelScope)
        }
    }

    private fun determineResultsBluetoothVisualStatus(
        connectionStatus: BleConnectionStatus,
        deviceData: BleDeviceData?, // Puede ser null si aún no se han recibido datos.
        isBluetoothAdapterEnabled: Boolean
    ): Pair<BluetoothIconStatus2, String> {
        if (!isBluetoothAdapterEnabled) {
            return BluetoothIconStatus2.RED to "Bluetooth desactivado"
        }

        return when (connectionStatus) {
            BleConnectionStatus.SUBSCRIBED -> {
                if (deviceData == null) {
                    BluetoothIconStatus2.YELLOW to "Conectado (esperando datos)"
                } else if (deviceData.noFingerDetected == true ||
                    deviceData.signalStrength == NO_FINGER_OR_RECALIBRATING_SIGNAL ||
                    deviceData.spo2 == null || deviceData.spo2 == 0 ||
                    deviceData.heartRate == null || deviceData.heartRate == 0) {
                    BluetoothIconStatus2.YELLOW to "Sensor: sin dedo/datos"
                } else if (deviceData.signalStrength != null && deviceData.signalStrength <= POOR_SIGNAL_THRESHOLD) {
                    BluetoothIconStatus2.YELLOW to "Sensor: señal baja"
                } else {
                    BluetoothIconStatus2.GREEN to "Sensor conectado"
                }
            }
            BleConnectionStatus.CONNECTED -> {
                BluetoothIconStatus2.CONNECTING /* O YELLOW */ to "Conectado (configurando...)"
            }
            BleConnectionStatus.CONNECTING, BleConnectionStatus.RECONNECTING -> {
                BluetoothIconStatus2.CONNECTING to "Conectando..."
            }
            BleConnectionStatus.SCANNING -> {
                BluetoothIconStatus2.CONNECTING to "Buscando dispositivo..."
            }
            BleConnectionStatus.DISCONNECTED_BY_USER -> {
                BluetoothIconStatus2.GRAY to "Desconectado (toque para conectar)"
            }
            BleConnectionStatus.DISCONNECTED_ERROR,
            BleConnectionStatus.ERROR_DEVICE_NOT_FOUND,
            BleConnectionStatus.ERROR_SERVICE_NOT_FOUND,
            BleConnectionStatus.ERROR_CHARACTERISTIC_NOT_FOUND,
            BleConnectionStatus.ERROR_SUBSCRIBE_FAILED,
            BleConnectionStatus.ERROR_GENERIC -> {
                BluetoothIconStatus2.RED to "Error conexión (toque para reintentar)"
            }
            BleConnectionStatus.IDLE -> {
                val lastDevice = bluetoothService.lastKnownOximeterAddress.value
                if (lastDevice != null) {
                    BluetoothIconStatus2.GRAY to "Inactivo (toque para reconectar)"
                } else {
                    BluetoothIconStatus2.GRAY to "Inactivo (sin disp. previo)"
                }
            }
            else -> BluetoothIconStatus2.GRAY to "Estado BT: ${connectionStatus.name}"
        }
    }

    internal fun determineSpo2AlarmStatus(spo2: Int): StatusColor {
        return when {
            spo2 == 0 -> StatusColor.UNKNOWN
            // Usar los umbrales cargados desde SettingsRepository
            spo2 <= userSpo2CriticalThreshold -> StatusColor.CRITICAL
            spo2 < userSpo2WarningThreshold  -> StatusColor.WARNING // Si es < umbral de warning (y no crítico)
            else -> StatusColor.NORMAL
        }
    }

    internal fun determineHeartRateAlarmStatus(hr: Int): StatusColor {
        return when {
            hr == 0 -> StatusColor.UNKNOWN // Valor no válido o no disponible
            // Usar los umbrales cargados desde SettingsRepository
            hr < userHrCriticalLowThreshold || hr > userHrCriticalHighThreshold -> StatusColor.CRITICAL
            hr < userHrWarningLowThreshold || hr > userHrWarningHighThreshold -> StatusColor.WARNING
            else -> StatusColor.NORMAL
        }
    }

    private fun calculateTrendFromAverageOfLastThree(
        currentValues: List<Int> // Lista con los últimos 6 valores
    ): Trend {
        // Necesitamos al menos 6 valores para comparar dos grupos de 3
        if (currentValues.size < 6) {
            return Trend.STABLE // O la tendencia inicial que prefieras (guion)
        }

        // Tomar los últimos 6 valores. Asegúrate de que la lista se llena en orden cronológico.
        // Si los nuevos valores se añaden al final, esto es correcto.
        val lastSixValues = currentValues.takeLast(6)

        val previousThree = lastSixValues.subList(0, 3)
        val currentThree = lastSixValues.subList(3, 6)

        val averagePreviousThree = previousThree.average()
        val averageCurrentThree = currentThree.average()

        return when {
            averageCurrentThree > averagePreviousThree -> Trend.UP
            averageCurrentThree < averagePreviousThree -> Trend.DOWN
            else -> Trend.STABLE
        }
    }

    fun onPostTestValueChange(field: PostTestField, value: String) {
        _uiState.update { currentState ->
            val newState = when (field) {
                PostTestField.BLOOD_PRESSURE -> currentState.copy(postTestBloodPressureInput = value)
                PostTestField.RESPIRATORY_RATE -> currentState.copy(postTestRespiratoryRateInput = value)
                PostTestField.DYSPNEA_BORG -> currentState.copy(postTestDyspneaBorgInput = value)
                PostTestField.LEG_PAIN_BORG -> currentState.copy(postTestLegPainBorgInput = value)
            }
            newState.copy(hasUnsavedChanges = if (newState.isTestSaved) true else newState.hasUnsavedChanges)
        }
        validateSinglePostTestField(field, value)
        validateAllPostTestFields()
    }

    private fun validateSinglePostTestField(field: PostTestField, value: String) {
        _uiState.update { currentState ->
            when (field) {
                PostTestField.BLOOD_PRESSURE -> {
                    val (isValidFormat, sys, dia) = parseAndValidateBloodPressure(value)
                    currentState.copy(
                        isPostTestBloodPressureValid = isValidFormat,
                        postTestSystolicBP = if (isValidFormat) sys else null,
                        postTestDiastolicBP = if (isValidFormat) dia else null
                    )
                }
                PostTestField.RESPIRATORY_RATE -> {
                    val intValue = value.toIntOrNull()
                    val isValid = intValue != null && intValue in inputRrMin..inputRrMax
                    currentState.copy(
                        isPostTestRespiratoryRateValid = isValid,
                        postTestRespiratoryRate = if (isValid) intValue else null
                    )
                }
                PostTestField.DYSPNEA_BORG -> {
                    val intValue = value.toIntOrNull() // Borg es entero
                    val isValid = intValue != null && intValue in inputBorgMin..inputBorgMax
                    currentState.copy(
                        isPostTestDyspneaBorgValid = isValid,
                        postTestDyspneaBorg = if (isValid) intValue else null
                    )
                }
                PostTestField.LEG_PAIN_BORG -> {
                    val intValue = value.toIntOrNull() // Borg es entero
                    val isValid = intValue != null && intValue in inputBorgMin..inputBorgMax
                    currentState.copy(
                        isPostTestLegPainBorgValid = isValid,
                        postTestLegPainBorg = if (isValid) intValue else null
                    )
                }
            }
        }
    }

    private fun parseAndValidateBloodPressure(bpInput: String): Triple<Boolean, Int?, Int?> {
        if (bpInput.isBlank()) {
            return Triple(true, null, null) // Considerar vacío como válido para no mostrar error inmediato, pero no completo
        }
        if (!BLOOD_PRESSURE_PATTERN.matches(bpInput)) {
            return Triple(false, null, null) // Formato incorrecto
        }
        val parts = bpInput.split("/").map { it.trim().toIntOrNull() }
        if (parts.size == 2 && parts[0] != null && parts[1] != null) {
            val sys = parts[0]!!
            val dia = parts[1]!!
            if (sys in inputBpSystolicMin..inputBpSystolicMax &&
                dia in inputBpDiastolicMin..inputBpDiastolicMax &&
                sys >= dia) {
                return Triple(true, sys, dia) // Válido
            }
        }
        return Triple(false, null, null) // Error de rango o lógica (sys < dia)
    }

    private fun validateAllPostTestFields() {
        _uiState.update { currentState ->
            val tempMissingOrInvalidFields = mutableListOf<String>()

            // Lógica de validación para recoverySpo2
            if (currentState.recoverySpo2 == null) {
                if (!internalRecoveryPeriodOver) { // Usamos la variable interna que es más directa
                    tempMissingOrInvalidFields.add("SpO2 Post (esperando 1 min rec.)")
                } else if (!currentState.isDeviceConnected && currentState.isAwaitingPostTimeoutRecoveryData) {
                    tempMissingOrInvalidFields.add("SpO2 Post (conectar sensor)")
                } else if (currentState.isDeviceConnected && currentState.isAwaitingPostTimeoutRecoveryData) {
                    tempMissingOrInvalidFields.add("SpO2 Post (esperando dato válido)")
                } else if (internalRecoveryPeriodOver && !currentState.isAwaitingPostTimeoutRecoveryData && !currentState.wasRecoveryDataCapturedInitially){
                    // Periodo terminó, no estamos "awaiting" (quizás porque el usuario no ha clicado en reconectar y estaba desconectado),
                    // Y no se capturaron inicialmente.
                    tempMissingOrInvalidFields.add("SpO2 Post (dato no obtenido)")
                } else { // Caso por defecto si los anteriores no aplican
                    tempMissingOrInvalidFields.add("SpO2 Postprueba")
                }
            }

            // Lógica de validación para recoveryHeartRate (similar a SpO2)
            if (currentState.recoveryHeartRate == null) {
                if (!internalRecoveryPeriodOver) {
                    tempMissingOrInvalidFields.add("FC Post (esperando 1 min rec.)")
                } else if (!currentState.isDeviceConnected && currentState.isAwaitingPostTimeoutRecoveryData) {
                    tempMissingOrInvalidFields.add("FC Post (conectar sensor)")
                } else if (currentState.isDeviceConnected && currentState.isAwaitingPostTimeoutRecoveryData) {
                    tempMissingOrInvalidFields.add("FC Post (esperando dato válido)")
                } else if (internalRecoveryPeriodOver && !currentState.isAwaitingPostTimeoutRecoveryData && !currentState.wasRecoveryDataCapturedInitially){
                    tempMissingOrInvalidFields.add("FC Post (dato no obtenido)")
                } else {
                    tempMissingOrInvalidFields.add("FC Postprueba")
                }
            }

            val (isBpFormatOk, parsedSysBp, parsedDiaBp) = parseAndValidateBloodPressure(currentState.postTestBloodPressureInput)
            val rrInputVal = currentState.postTestRespiratoryRateInput
            val dysInputVal = currentState.postTestDyspneaBorgInput
            val legInputVal = currentState.postTestLegPainBorgInput

            val rr = rrInputVal.toIntOrNull()
            val dys = dysInputVal.toIntOrNull()
            val leg = legInputVal.toIntOrNull()

            val isRrFormatOk = rrInputVal.isBlank() || (rr != null && rr in inputRrMin..inputRrMax)
            val isDysFormatOk = dysInputVal.isBlank() || (dys != null && dys in inputBorgMin..inputBorgMax)
            val isLegFormatOk = legInputVal.isBlank() || (leg != null && leg in inputBorgMin..inputBorgMax)

            val finalSysBp = if (isBpFormatOk && parsedSysBp != null) parsedSysBp else null
            val finalDiaBp = if (isBpFormatOk && parsedDiaBp != null) parsedDiaBp else null
            val finalRr = if (isRrFormatOk && rr != null) rr else null
            val finalDys = if (isDysFormatOk && dys != null) dys else null
            val finalLeg = if (isLegFormatOk && leg != null) leg else null

            val recoverySpo2Available = currentState.recoverySpo2 != null
            val recoveryHrAvailable = currentState.recoveryHeartRate != null
            val manualFieldsCompleteAndValid = finalSysBp != null && finalDiaBp != null && finalRr != null && finalDys != null && finalLeg != null
            val areAllFieldsCompleteAndValid = manualFieldsCompleteAndValid && recoverySpo2Available && recoveryHrAvailable

            // Re-evaluar los mensajes para los campos manuales basado en si están completos
            if (currentState.postTestBloodPressureInput.isBlank()) tempMissingOrInvalidFields.add("TA Post")
            else if (!isBpFormatOk) tempMissingOrInvalidFields.add("TA Post (formato/rango incorrecto)")

            if (rrInputVal.isBlank()) tempMissingOrInvalidFields.add("FR Post")
            else if (!isRrFormatOk) tempMissingOrInvalidFields.add("FR Post (rango incorrecto)")

            if (dysInputVal.isBlank()) tempMissingOrInvalidFields.add("Disnea Post")
            else if (!isDysFormatOk) tempMissingOrInvalidFields.add("Disnea Post (rango incorrecto)")

            if (legInputVal.isBlank()) tempMissingOrInvalidFields.add("Dolor MII Post")
            else if (!isLegFormatOk) tempMissingOrInvalidFields.add("Dolor MII Post (rango incorrecto)")

            val validationMsg = when {
                !internalRecoveryPeriodOver && (currentState.recoverySpo2 == null || currentState.recoveryHeartRate == null) ->
                    "Esperando finalización del minuto de recuperación para SpO2/FC Postprueba..."
                areAllFieldsCompleteAndValid -> "Todos los campos del registro postprueba completo son válidos."
                tempMissingOrInvalidFields.isNotEmpty() -> {
                    val prefix = if (tempMissingOrInvalidFields.size > 1) "Faltan o son incorrectos: " else "Falta o es incorrecto: "
                    prefix + tempMissingOrInvalidFields.joinToString(", ") + "."
                }
                else -> "Por favor, complete todos los campos postprueba." // Mensaje por defecto si no hay errores específicos pero no está todo completo
            }

            currentState.copy(
                isPostTestBloodPressureValid = isBpFormatOk,
                isPostTestRespiratoryRateValid = isRrFormatOk,
                isPostTestDyspneaBorgValid = isDysFormatOk,
                isPostTestLegPainBorgValid = isLegFormatOk,
                arePostTestValuesCompleteAndValid = areAllFieldsCompleteAndValid,
                validationMessage = validationMsg
            )
        }
    }

    fun onObservationsChange(text: String) {
        _uiState.update {
            it.copy(
                observations = text,
                hasUnsavedChanges = if (it.isTestSaved) true else it.hasUnsavedChanges
            )
        }
    }

    fun onShowObservationsDialog(show: Boolean) {
        _uiState.update { it.copy(showObservationsDialog = show) }
    }

    fun onSaveTestClicked() {
        val currentState = _uiState.value
        if (!currentState.arePostTestValuesCompleteAndValid) {
            _uiState.update { it.copy(userMessage = "Complete todos los campos postprueba antes de guardar.") }
            clearUserMessageAfterDelay(3500)
            return
        }

        val patientIdToSave = currentState.summaryData?.patientId
            ?: currentState.patientId.takeIf { it.isNotBlank() } ?: run {
                Log.e("TestResultsVM", "ID de paciente vacío, no se puede guardar.")
                _uiState.update { it.copy(userMessage = "Error: Falta ID de paciente para guardar.") }
                clearUserMessageAfterDelay()
                return
            }

        viewModelScope.launch {
            val testDetails = PruebaCompletaDetalles(
                summaryData = currentState.summaryData,
                postTestSpo2 = currentState.recoverySpo2,
                postTestHeartRate = currentState.recoveryHeartRate,
                postTestSystolicBP = currentState.postTestSystolicBP,
                postTestDiastolicBP = currentState.postTestDiastolicBP,
                postTestRespiratoryRate = currentState.postTestRespiratoryRate,
                postTestDyspneaBorg = currentState.postTestDyspneaBorg,
                postTestLegPainBorg = currentState.postTestLegPainBorg,
                observations = currentState.observations.ifEmpty { null }
            )

            val testDate = currentState.summaryData?.testActualStartTimeMillis ?: currentState.testDate
            val distance = currentState.summaryData?.distanceMetersFinal ?: 0f
            val percentage = currentState.percentageOfTheoretical
            val minSpo2Value = currentState.summaryData?.minSpo2Record?.value ?: 0
            val stopsValue = currentState.summaryData?.stopRecords?.size ?: 0

            try {
                if (currentState.isTestSaved && currentState.savedTestDatabaseId != null) {
                    // Actualizar prueba existente
                    val pruebaIdExistente = currentState.savedTestDatabaseId // Ya es Int
                    val existingTestNumber = currentState.savedTestNumeroPruebaPaciente
                        ?: pacienteRepository.getNumeroPruebaById(pruebaIdExistente) // Llamada correcta
                        ?: 0 // Debería existir

                    val pruebaActualizada = PruebaRealizada(
                        pruebaId = pruebaIdExistente,
                        pacienteId = patientIdToSave,
                        fechaTimestamp = testDate,
                        numeroPruebaPaciente = existingTestNumber,
                        distanciaRecorrida = distance,
                        porcentajeTeorico = percentage,
                        spo2min = minSpo2Value,
                        stops = stopsValue,
                        datosCompletos = testDetails
                    )
                    pacienteRepository.actualizarPruebaRealizada(pruebaActualizada)
                    Log.i("TestResultsVM", "Prueba ID ${currentState.savedTestDatabaseId} actualizada para paciente: $patientIdToSave")
                    _uiState.update {
                        it.copy(
                            userMessage = "Cambios en la Prueba N.º$existingTestNumber guardados.",
                            hasUnsavedChanges = false // Cambios guardados
                        )
                    }
                } else {
                    // Guardar nueva prueba
                    val nextTestNumber = pacienteRepository.getProximoNumeroPruebaParaPaciente(patientIdToSave)
                    val nuevaPruebaSinId = PruebaRealizada(
                        pacienteId = patientIdToSave,
                        fechaTimestamp = testDate,
                        numeroPruebaPaciente = nextTestNumber,
                        distanciaRecorrida = distance,
                        porcentajeTeorico = percentage,
                        spo2min = minSpo2Value,
                        stops = stopsValue,
                        datosCompletos = testDetails
                    )
                    val pruebaGuardada = pacienteRepository.guardarPruebaRealizada(nuevaPruebaSinId)
                    if (pruebaGuardada != null) {
                        Log.i("TestResultsVM", "Prueba N.º${pruebaGuardada.numeroPruebaPaciente} guardada (ID: ${pruebaGuardada.pruebaId}) para paciente: $patientIdToSave")
                        _uiState.update {
                            it.copy(
                                isTestSaved = true,
                                savedTestDatabaseId = pruebaGuardada.pruebaId,
                                savedTestNumeroPruebaPaciente = pruebaGuardada.numeroPruebaPaciente,
                                userMessage = "Prueba N.º${pruebaGuardada.numeroPruebaPaciente} guardada exitosamente.",
                                hasUnsavedChanges = false
                            )
                        }
                    } else {
                        Log.e("TestResultsVM", "Error al guardar la nueva prueba y obtener su ID.")
                        _uiState.update {
                            it.copy(userMessage = "Error al guardar la prueba en la base de datos.")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TestResultsVM", "Error al guardar/actualizar la prueba para $patientIdToSave: ${e.message}")
                _uiState.update {
                    it.copy(userMessage = "Error al guardar los resultados en la base de datos.")
                }
            } finally {
                clearUserMessageAfterDelay(3000)
            }
        }
    }

    fun onNavigationHandled() { // Renombrado para claridad
        _uiState.update { it.copy(shouldNavigateToHome = false) }
    }

    fun onGeneratePdfClicked() {
        val currentState = _uiState.value
        // Condición 1: Campos postprueba completos y válidos
        if (!currentState.arePostTestValuesCompleteAndValid) {
            _uiState.update { it.copy(userMessage = "PDF no generado: Complete y guarde todos los campos postprueba.") }
            clearUserMessageAfterDelay(3500)
            return
        }

        // Condición 2: La prueba debe estar guardada
        if (!currentState.isTestSaved) {
            _uiState.update { it.copy(userMessage = "Guarde la prueba primero para generar el PDF.") }
            clearUserMessageAfterDelay(3500)
            return
        }

        // Condición 3: No debe haber cambios sin guardar sobre una prueba ya guardada
        if (currentState.hasUnsavedChanges) { // Implícitamente, isTestSaved es true aquí
            _uiState.update { it.copy(userMessage = "Guarde los cambios pendientes para generar el PDF.") }
            clearUserMessageAfterDelay(3500)
            return
        }

        // Si llegamos aquí, la prueba está guardada, completa, y sin cambios pendientes.
        generatePdfReportInternal()
    }

    private fun generatePdfReportInternal() { // Renombrada para evitar confusión con el onClick handler
        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingPdf = true, pdfGeneratedUri = null, pdfGenerationError = null) }

            val currentUiState = _uiState.value // Tomar el estado más reciente

            // Validaciones básicas que ya estaban
            if (currentUiState.patientId.isBlank()) {
                Log.e("TestResultsVM", "generatePdfReportInternal: Patient ID está vacío.")
                _uiState.update { it.copy(isGeneratingPdf = false, pdfGenerationError = "Falta ID de paciente.", userMessage = "Error: Falta ID de paciente.") }
                clearUserMessageAfterDelay()
                return@launch
            }
            if (currentUiState.summaryData == null) {
                Log.e("TestResultsVM", "generatePdfReportInternal: summaryData es null.")
                _uiState.update { it.copy(isGeneratingPdf = false, pdfGenerationError = "Faltan datos de la prueba.", userMessage = "Error: Faltan datos de la prueba.") }
                clearUserMessageAfterDelay()
                return@launch
            }

            // --- Determinar el número de prueba para el PDF ---
            // Si la prueba ya ha sido guardada, usa el número de prueba guardado.
            // Si no, obtén el "próximo" número como antes (sería para una prueba aún no guardada).
            val numeroPruebaParaPdf: Int = currentUiState.savedTestNumeroPruebaPaciente ?: try {
                val idPaciente = currentUiState.summaryData.patientId.ifBlank { currentUiState.patientId }
                if (idPaciente.isNotBlank()) {
                    pacienteRepository.getProximoNumeroPruebaParaPaciente(idPaciente)
                } else {
                    Log.w("TestResultsVM", "generatePdfReportInternal: No se pudo obtener ID de paciente para número de prueba.")
                    0 // O un valor por defecto / manejar como error
                }
            } catch (e: Exception) {
                Log.e("TestResultsVM", "generatePdfReportInternal: Error al obtener próximo número de prueba para PDF", e)
                0 // O un valor por defecto / manejar como error
            }

            val databaseIdPruebaParaPdf: Int? = currentUiState.savedTestDatabaseId
            if (databaseIdPruebaParaPdf == null) {
                Log.e("TestResultsVM", "generatePdfReportInternal: savedTestDatabaseId es null. No se puede generar PDF.")
                _uiState.update {
                    it.copy(
                        isGeneratingPdf = false,
                        pdfGenerationError = "Error: Falta ID de la prueba guardada para el PDF.",
                        userMessage = "Error: Falta ID de la prueba guardada para el PDF."
                    )
                }
                clearUserMessageAfterDelay()
                return@launch
            }

            val detallesParaPdf = PruebaCompletaDetalles(
                summaryData = currentUiState.summaryData,
                postTestSpo2 = currentUiState.recoverySpo2,
                postTestHeartRate = currentUiState.recoveryHeartRate,
                postTestSystolicBP = currentUiState.postTestSystolicBP,
                postTestDiastolicBP = currentUiState.postTestDiastolicBP,
                postTestRespiratoryRate = currentUiState.postTestRespiratoryRate,
                postTestDyspneaBorg = currentUiState.postTestDyspneaBorg,
                postTestLegPainBorg = currentUiState.postTestLegPainBorg,
                observations = currentUiState.observations.ifEmpty { null }
            )

            try {
                val pdfFile: File? = withContext(Dispatchers.IO) {
                    SixMinuteWalkTestPdfGenerator.generatePdf(
                        context = applicationContext,
                        detallesPrueba = detallesParaPdf,
                        numeroPrueba = numeroPruebaParaPdf, // Usar el número determinado
                        pruebaId = databaseIdPruebaParaPdf
                    )
                }

                if (pdfFile != null) {
                    val pdfUri = FileProvider.getUriForFile(
                        applicationContext,
                        "${applicationContext.packageName}.provider",
                        pdfFile
                    )
                    _uiState.update {
                        it.copy(
                            isGeneratingPdf = false,
                            pdfGeneratedUri = pdfUri,
                            userMessage = "Informe PDF (Prueba N.º$numeroPruebaParaPdf) guardado."
                        )
                    }
                    clearUserMessageAfterDelay(4000)
                    Log.i("TestResultsVM", "PDF generado (Prueba N.º$numeroPruebaParaPdf): $pdfUri")
                } else {
                    Log.e("TestResultsVM", "SixMinuteWalkTestPdfGenerator.generatePdf devolvió null.")
                    _uiState.update {
                        it.copy(
                            isGeneratingPdf = false,
                            pdfGenerationError = "No se pudo generar el archivo PDF.",
                            userMessage = "Error al crear el informe PDF."
                        )
                    }
                    clearUserMessageAfterDelay(4000)
                }
            } catch (e: Exception) {
                Log.e("TestResultsVM", "Error al generar o guardar el PDF", e)
                _uiState.update {
                    it.copy(
                        isGeneratingPdf = false,
                        pdfGenerationError = "Error al generar PDF: ${e.localizedMessage}",
                        userMessage = "Error al guardar el PDF."
                    )
                }
                clearUserMessageAfterDelay(4000)
            }
        }
    }

    fun requestFinalizeTest() { // Nueva función para la acción del botón "Finalizar"
        val currentState = _uiState.value
        if (!currentState.arePostTestValuesCompleteAndValid) {
            _uiState.update { it.copy(userMessage = "Complete y guarde todos los campos para finalizar.") }
            clearUserMessageAfterDelay(4000)
            return
        }
        if (!currentState.isTestSaved) {
            _uiState.update { it.copy(userMessage = "Guarde la prueba primero para poder finalizar.") }
            clearUserMessageAfterDelay(4000)
            return
        }
        if (currentState.hasUnsavedChanges) {
            _uiState.update { it.copy(userMessage = "Guarde los cambios pendientes antes de finalizar.") }
            clearUserMessageAfterDelay(4000)
            return
        }
        // Si todo está OK, procede a la navegación
        _uiState.update { it.copy(shouldNavigateToHome = true) }
    }

    fun onBluetoothIconClicked() {
        viewModelScope.launch {
            if (_uiState.value.isAttemptingForceReconnect) {
                Log.d("TestResultsVM", "Intento de reconexión ya en curso.")
                _uiState.update { it.copy(userMessage = "Reconexión en progreso...") }
                clearUserMessageAfterDelay(1500)
                return@launch
            }

            if (!bluetoothService.isBluetoothEnabled()) {
                _uiState.update { it.copy(userMessage = "Active Bluetooth en los ajustes del sistema.") }
                clearUserMessageAfterDelay(3500)
                return@launch
            }

            val currentUiStateValues = _uiState.value
            val visualStatus = currentUiStateValues.bluetoothVisualStatus
            val currentServiceStatus = bluetoothService.oximeterConnectionStatus.value

            // Si el periodo de recuperación ha terminado Y faltan datos de SpO2 o HR
            var shouldExplicitlyAwaitRecoveryData = false
            if (internalRecoveryPeriodOver && (currentUiStateValues.recoverySpo2 == null || currentUiStateValues.recoveryHeartRate == null)) {
                shouldExplicitlyAwaitRecoveryData = true
                Log.d("TestResultsVM", "BT Icon Clicked: Periodo terminado, faltan datos de recuperación. Marcando isAwaitingPostTimeoutRecoveryData=true.")
            }

            if (visualStatus == BluetoothIconStatus2.RED ||
                (visualStatus == BluetoothIconStatus2.GRAY &&
                        (currentUiStateValues.bluetoothStatusMessage.contains("reconectar", ignoreCase = true) ||
                                currentUiStateValues.bluetoothStatusMessage.contains("inactivo", ignoreCase = true) ||
                                currentServiceStatus == BleConnectionStatus.IDLE || currentServiceStatus.isErrorStatus()
                                )
                        )
            ) {
                val deviceAddressToReconnect = bluetoothService.lastKnownOximeterAddress.value
                if (deviceAddressToReconnect != null) {
                    _uiState.update {
                        it.copy(
                            isAttemptingForceReconnect = true,
                            // Si ya marcamos shouldExplicitlyAwaitRecoveryData, lo mantenemos, si no, mantenemos el valor actual.
                            isAwaitingPostTimeoutRecoveryData = if (shouldExplicitlyAwaitRecoveryData) true else it.isAwaitingPostTimeoutRecoveryData
                        )
                    }
                    attemptDeviceReconnection(deviceAddressToReconnect)
                } else {
                    Log.e("TestResultsVM", "onBluetoothIconClicked: Se intentó reconectar pero lastKnownConnectedDeviceAddress es null. Esto no debería ocurrir.")
                    _uiState.update { it.copy(userMessage = "Error interno: No se encontró dispositivo previo.") }
                    clearUserMessageAfterDelay()
                }
            } else if (currentServiceStatus.isConsideredConnectedOrSubscribed()) {
                if (shouldExplicitlyAwaitRecoveryData) { // Ya está conectado, pero faltan datos de recuperación (y el periodo terminó)
                    _uiState.update { it.copy(
                        userMessage = "Sensor conectado. Esperando datos válidos de SpO2/FC de recuperación...",
                        isAwaitingPostTimeoutRecoveryData = true // Confirmar que estamos esperando activamente
                    )}
                    clearUserMessageAfterDelay(3500)
                } else if (!internalRecoveryPeriodOver && (currentUiStateValues.recoverySpo2 == null || currentUiStateValues.recoveryHeartRate == null)){
                    _uiState.update { it.copy(userMessage = "Sensor conectado. Esperando fin del minuto de recuperación para SpO2/FC.") }
                    clearUserMessageAfterDelay()
                } else {
                    _uiState.update { it.copy(userMessage = "Sensor ya ${currentUiStateValues.bluetoothStatusMessage.lowercase()}.") }
                    clearUserMessageAfterDelay()
                }
            } else {
                Log.d("TestResultsVM", "Icono BT pulsado, estado visual: $visualStatus. Mensaje: ${_uiState.value.bluetoothStatusMessage}. No se requiere acción o ya en curso.")
                _uiState.update { it.copy(userMessage = "Sensor: ${_uiState.value.bluetoothStatusMessage}") }
                clearUserMessageAfterDelay()
            }
            validateAllPostTestFields()
        }
    }

    private suspend fun attemptDeviceReconnection(deviceAddress: String) {
        Log.i("TestResultsVM", "Intentando reconexión forzada con $deviceAddress")

        if (bluetoothService.oximeterConnectionStatus.value.isConsideredConnectedOrSubscribed()) {
            bluetoothService.disconnect(deviceAddress)
            delay(500)
        }
        bluetoothService.connect(deviceAddress, DeviceCategory.OXIMETER)

        delay(FORCE_RECONNECT_TIMEOUT_SECONDS * 1000L)

        if (_uiState.value.isAttemptingForceReconnect &&
            bluetoothService.oximeterConnectionStatus.value != BleConnectionStatus.SUBSCRIBED) {
            Log.w("TestResultsVM", "Timeout de reconexión. El servicio no conectó a tiempo.")
            _uiState.update {
                it.copy(
                isAttemptingForceReconnect = false,
                userMessage = "Fallo al reconectar con el dispositivo."
                )
            }
            clearUserMessageAfterDelay()
        } else if (_uiState.value.isAttemptingForceReconnect &&
            bluetoothService.oximeterConnectionStatus.value == BleConnectionStatus.SUBSCRIBED) {
            Log.i("TestResultsVM", "Reconexión forzada exitosa, confirmada tras delay.")
            _uiState.update { it.copy(isAttemptingForceReconnect = false, userMessage = "Reconexión exitosa.") }
            clearUserMessageAfterDelay()
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
        userMessageClearJob?.cancel()
    }

    fun clearUserMessageAfterDelay(delayMillis: Long = 2500) {
        userMessageClearJob?.cancel()
        userMessageClearJob = viewModelScope.launch {
            delay(delayMillis)
            clearUserMessage()
        }
    }

    fun clearCriticalAlarmMessage() {
        criticalAlarmMessageClearJob?.cancel()
        _uiState.update { it.copy(criticalAlarmMessage = null) }
    }

    private fun clearCriticalAlarmMessageAfterDelay(delayMillis: Long = 5000L) {
        criticalAlarmMessageClearJob?.cancel()
        criticalAlarmMessageClearJob = viewModelScope.launch {
            delay(delayMillis)
            clearCriticalAlarmMessage()
        }
    }

    override fun onCleared() {
        super.onCleared()
        recoveryDataJob?.cancel()
        liveSensorDataJob?.cancel()
        bluetoothStatusJob?.cancel()
        userMessageClearJob?.cancel()
        criticalAlarmMessageClearJob?.cancel()
        Log.d("TestResultsVM", "ViewModel onCleared.")
    }

    fun clearPdfUri() {
        _uiState.update { it.copy(pdfGeneratedUri = null) }
    }

    fun BleConnectionStatus.isConsideredConnectedOrSubscribed(): Boolean {
        return this == BleConnectionStatus.CONNECTED || this == BleConnectionStatus.SUBSCRIBED
    }

    fun BleConnectionStatus.isErrorStatus(): Boolean {
        return this == BleConnectionStatus.DISCONNECTED_ERROR ||
                this == BleConnectionStatus.ERROR_DEVICE_NOT_FOUND ||
                this == BleConnectionStatus.ERROR_SERVICE_NOT_FOUND ||
                this == BleConnectionStatus.ERROR_CHARACTERISTIC_NOT_FOUND ||
                this == BleConnectionStatus.ERROR_SUBSCRIBE_FAILED ||
                this == BleConnectionStatus.ERROR_GENERIC
    }
}

fun formatDurationMillis(millis: Long): String {
    if (millis < 0) return "N/A"
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
