package com.example.app6mwt.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app6mwt.bluetooth.BleConnectionStatus
import com.example.app6mwt.bluetooth.BleDeviceData
import com.example.app6mwt.bluetooth.BluetoothService
import com.example.app6mwt.bluetooth.DeviceCategory
import com.example.app6mwt.data.DefaultThresholdValues
import com.example.app6mwt.data.SettingsRepository
import com.example.app6mwt.di.RecoveryData
import com.example.app6mwt.di.TestStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject


// --- Constantes ---
// Duración estándar de la prueba 6MWT en milisegundos (6 minutos)
const val TEST_DURATION_MILLIS = 6 * 60 * 1000L
// Duración de la cuenta atrás para confirmar la detención de la prueba
const val STOP_CONFIRMATION_COUNTDOWN_SECONDS = 5

// Mensajes para diálogos de confirmación e información
const val RESTART_TEST_CONFIRMATION_MESSAGE = "¿Reiniciar la prueba? Se perderán los datos actuales y la prueba comenzará de nuevo desde el principio."
const val REINITIALIZE_TEST_CONFIRMATION_MESSAGE = "¿Volver a la configuración inicial? Se perderán los datos de la prueba actual."
const val TEST_COMPLETED_INFO_MESSAGE = "Prueba completada."
const val NAVIGATE_TO_RESULTS_CONFIRMATION_MESSAGE = "¿Navegar a la pantalla de resultados?"

// Constantes para el cálculo de tendencias de SpO2 y FC
private const val TREND_WINDOW_SIZE_FOR_CALC = 6 // Número de valores a considerar para la tendencia
private const val NEW_VALUES_THRESHOLD_FOR_TREND_CALC = 3 // Recalcular tendencia cada 3 nuevos valores

// Constantes relacionadas con Bluetooth y la calidad de la señal del sensor
const val FORCE_RECONNECT_TIMEOUT_SECONDS = 10 // Tiempo máximo para intento de reconexión forzada
const val POOR_SIGNAL_THRESHOLD = 4 // Umbral para considerar señal del sensor como baja
const val NO_FINGER_OR_RECALIBRATING_SIGNAL = 15 // Valor de señal que indica no dedo o recalibración

// --- Data classes y Enums ---
/**
 * Representa un punto de dato individual para SpO2 o FC, con su tiempo y distancia.
 * @param timeMillis El tiempo en milisegundos desde el inicio de la prueba.
 * @param value El valor del sensor (SpO2 o FC).
 * @param distanceAtTime La distancia recorrida en el momento de esta medición.
 */
data class DataPoint(val timeMillis: Long, val value: Float, val distanceAtTime: Float)

/**
 * Representa un registro de una parada durante la prueba.
 * @param stopTimeMillis Tiempo en milisegundos de la parada.
 * @param spo2AtStopTime SpO2 en el momento de la parada.
 * @param heartRateAtStopTime Frecuencia cardíaca en el momento de la parada.
 * @param distanceAtStopTime Distancia recorrida hasta la parada.
 * @param stopTimeFormatted Tiempo de la parada formateado (ej. "mm:ss").
 */
data class StopRecord(
    val stopTimeMillis: Long,
    val spo2AtStopTime: Int,
    val heartRateAtStopTime: Int,
    val distanceAtStopTime: Float,
    val stopTimeFormatted: String
)

/**
 * Representa un registro de un valor crítico (mínimo/máximo) alcanzado durante la prueba.
 * @param value El valor crítico (ej. SpO2 mínimo, FC máxima).
 * @param timeMillis El tiempo en que se registró este valor.
 * @param distanceAtTime La distancia recorrida cuando se registró este valor.
 */
data class CriticalValueRecord(
    val value: Int,
    val timeMillis: Long,
    val distanceAtTime: Float
)

/**
 * Almacena una instantánea de los datos clave al final de cada minuto de la prueba.
 * @param minuteMark El número del minuto (1 a 6).
 * @param minSpo2Overall El SpO2 mínimo registrado desde el inicio de la prueba HASTA este minuto.
 * @param maxHrOverall La FC máxima registrada desde el inicio de la prueba HASTA este minuto.
 * @param distanceAtMinuteEnd La distancia total recorrida al final exacto de este minuto.
 */
data class MinuteDataSnapshot(
    val minuteMark: Int, // 1, 2, 3, 4, 5, 6
    val minSpo2Overall: Int?, // El SpO2 mínimo desde el inicio HASTA ESTE MINUTO
    val maxHrOverall: Int?,   // El FC máximo desde el inicio HASTA ESTE MINUTO
    val distanceAtMinuteEnd: Float? // La distancia al final exacto de este minuto
)

// Enumeración para la tendencia de los valores de SpO2 y FC
enum class Trend { UP, DOWN, STABLE }

// Enumeración para el color de estado de los valores de SpO2 y FC (Normal, Advertencia, Crítico)
enum class StatusColor { NORMAL, WARNING, CRITICAL, UNKNOWN }

// Enumeración para la acción del botón principal (Iniciar, Reiniciar durante prueba, Reinicializar después de prueba)
enum class MainButtonAction { START, RESTART_DURING_TEST, REINITIALIZE_AFTER_TEST }

// Enumeración para el estado visual del icono de Bluetooth
enum class BluetoothIconStatus { GREEN, YELLOW, RED, GRAY, CONNECTING }

/**
 * Representa el estado completo de la UI para la pantalla de ejecución de la prueba.
 * Contiene todos los datos necesarios para renderizar la interfaz.
 */
data class TestExecutionUiState(
    // Datos del paciente y configuración de la prueba
    val patientFullName: String = "",
    val patientId: String = "",
    val patientStrideLengthMeters: Float = 0.0f, // NUEVO: Longitud de paso

    // Estado en tiempo real de la prueba
    val currentTimeMillis: Long = 0L, // Tiempo transcurrido de la prueba
    val currentTimeFormatted: String = "00:00", // Tiempo formateado para mostrar
    val distanceMeters: Float = 0f, // Distancia total recorrida
    val accumulatedDistanceBeforeLastReconnect: Float = 0f,
    val lastKnownTotalStepsBeforeReconnect: Int? = null,

    // Datos de sensores en tiempo real (visibles incluso fuera de la prueba)
    val currentSpo2: Int? = null,
    val currentHeartRate: Int? = null,
    val spo2Trend: Trend = Trend.STABLE,
    val heartRateTrend: Trend = Trend.STABLE,
    val spo2StatusColor: StatusColor = StatusColor.UNKNOWN,
    val heartRateStatusColor: StatusColor = StatusColor.UNKNOWN,
    val isSensorFingerPresent: Boolean = true, // Indica si el sensor detecta el dedo
    val currentSignalStrength: Int? = null, // Calidad de la señal del sensor

    // Datos acumulados durante la prueba
    val spo2DataPoints: List<DataPoint> = emptyList(), // Historial de SpO2
    val heartRateDataPoints: List<DataPoint> = emptyList(), // Historial de FC
    val stopRecords: List<StopRecord> = emptyList(), // Registros de paradas
    val stopsCount: Int = 0, // Número total de paradas

    // Registros de valores críticos durante la prueba
    val minSpo2Record: CriticalValueRecord? = null,
    val minHeartRateRecord: CriticalValueRecord? = null,
    val maxHeartRateRecord: CriticalValueRecord? = null,

    // Datos por minuto
    val minuteMarkerData: List<MinuteDataSnapshot> = emptyList(),

    // Control de estado de la prueba y la UI
    val mainButtonAction: MainButtonAction = MainButtonAction.START,
    val isConfigPhase: Boolean = true, // Indica si se está en la fase de configuración previa al inicio
    val isTestRunning: Boolean = false, // Indica si la prueba está en curso
    val isTestFinished: Boolean = false, // Indica si la prueba ha finalizado
    val preparationDataLoaded: Boolean = false, // Indica si los datos de preparación del paciente se han cargado
    val canNavigateToResults: Boolean = false, // Indica si se puede navegar a la pantalla de resultados

    // Estado de Bluetooth - Pulsioxímetro
    val oximeterBluetoothIconStatus: BluetoothIconStatus = BluetoothIconStatus.GRAY,
    val oximeterBluetoothStatusMessage: String = "Pulsioxímetro: Iniciando...",
    val isAttemptingOximeterForceReconnect: Boolean = false,

    // Estado de Bluetooth - Acelerómetro
    val accelerometerBluetoothIconStatus: BluetoothIconStatus = BluetoothIconStatus.GRAY,
    val accelerometerBluetoothStatusMessage: String = "Acelerómetro: Iniciando...",
    val isAccelerometerConnected: Boolean = false, // Para saber si el acelerómetro está al menos conectado
    val isAttemptingAccelerometerForceReconnect: Boolean = false,

    // Control de visibilidad de diálogos
    val showExitConfirmationDialog: Boolean = false,
    val showStopConfirmationDialog: Boolean = false,
    val stopCountdownSeconds: Int = STOP_CONFIRMATION_COUNTDOWN_SECONDS,
    val showMainActionConfirmationDialog: Boolean = false,
    val mainActionConfirmationMessage: String = "",
    val showNavigateToResultsConfirmationDialog: Boolean = false,
    val testFinishedInfoMessage: String? = null, // Mensaje al finalizar la prueba
    val showDeleteLastStopConfirmationDialog: Boolean = false,

    // Mensajes para el usuario y datos para navegación
    val userMessage: String? = null,
    val testSummaryDataForNavigation: TestExecutionSummaryData? = null, // Datos para pasar a la pantalla de resultados
)

/**
 * Contiene todos los datos relevantes de una prueba 6MWT completada, combinando
 * datos de la preparación y datos de la ejecución.
 * Se utiliza para pasar la información a la pantalla de resultados.
 */
data class TestExecutionSummaryData(
    // --- Campos de TestPreparationData (datos del paciente y basales) ---
    val patientId: String,
    val patientFullName: String,
    val patientSex: String,
    val patientAge: Int,
    val patientHeightCm: Int,
    val patientWeightKg: Int,
    val usesInhalers: Boolean,
    val usesOxygen: Boolean,
    val theoreticalDistance: Double,
    val basalSpo2: Int,
    val basalHeartRate: Int,
    val basalBloodPressureSystolic: Int,
    val basalBloodPressureDiastolic: Int,
    val basalRespiratoryRate: Int,
    val basalDyspneaBorg: Int,
    val basalLegPainBorg: Int,
    val accelerometerPlacementLocation: String,
    val oximeterDevicePlacementLocation: String,
    val isFirstTestForPatient: Boolean,

    // --- Campos específicos de la ejecución de la prueba ---
    val testActualStartTimeMillis: Long, // Tiempo de inicio real de la prueba (epoch)
    val actualTestDurationMillis: Long, // Duración real de la prueba
    val distanceMetersFinal: Float, // Distancia final recorrida
    val strideLengthUsedForTestMeters: Float, // Longitud de paso
    val minSpo2Record: CriticalValueRecord?, // SpO2 mínimo durante la prueba
    val maxHeartRateRecord: CriticalValueRecord?, // FC máxima durante la prueba
    val minHeartRateRecord: CriticalValueRecord?, // FC mínima durante la prueba
    val stopRecords: List<StopRecord>, // Lista de paradas
    val spo2DataPoints: List<DataPoint>, // Historial de SpO2
    val heartRateDataPoints: List<DataPoint>, // Historial de FC
    val minuteReadings: List<MinuteDataSnapshot> = emptyList() // Datos por minuto
)

/**
 * ViewModel para la pantalla de ejecución de la prueba 6MWT.
 * Maneja la lógica de la prueba, la interacción con el servicio Bluetooth,
 * y la actualización del estado de la UI.
 *
 * @param bluetoothService Servicio para la comunicación Bluetooth con el pulsioxímetro.
 * @param testStateHolder Utilidad para mantener estado entre pantallas (ej. datos de recuperación).
 * @param settingsRepository Repositorio para acceder a las configuraciones del usuario (umbrales).
 */
@HiltViewModel
class TestExecutionViewModel @Inject constructor(
    private val bluetoothService: BluetoothService,
    private val testStateHolder: TestStateHolder,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // Flujo mutable privado para el estado de la UI, expuesto como StateFlow inmutable
    private val _uiState = MutableStateFlow(TestExecutionUiState())
    val uiState: StateFlow<TestExecutionUiState> = _uiState.asStateFlow()

    // Jobs de coroutines para manejar tareas asíncronas (temporizador, cuenta atrás, etc.)
    private var timerJob: Job? = null // Job para el temporizador principal de la prueba
    private var stopCountdownJob: Job? = null // Job para la cuenta atrás de detención de prueba
    private var userMessageClearJob: Job? = null // Job para limpiar mensajes al usuario
    private var recoveryDataCaptureJob: Job? =
        null // Job para capturar datos de recuperación post-prueba
    private var oximeterForceReconnectJob: Job? =
        null  // Job para el intento de reconexión forzada de Bluetooth en pulsioxímetro
    private var accelerometerForceReconnectJob: Job? = null

    // Datos de preparación de la prueba actual (paciente, etc.)
    private var currentTestPreparationData: TestPreparationData? = null

    // Tiempo de inicio real de la prueba (timestamp epoch)
    private var testActualStartTimeMillis: Long = 0L

    // Variable para la longitud de zancada del paciente
    private var patientStrideLength: Double = 0.0

    // Variable para los pasos iniciales al comenzar la prueba
    private var initialStepsAtTestStart: Int? = null

    // --- Variables para la lógica de cálculo de tendencia ---
    private var liveDataProcessingJob: Job? =
        null // Job para el bucle de procesamiento de datos en vivo

    // Listas para almacenar los últimos valores de los sensores para calcular la tendencia
    private val spo2ValuesForTrendCalculation = mutableListOf<Int>()
    private val hrValuesForTrendCalculation = mutableListOf<Int>()

    // Contadores para determinar cuándo recalcular la tendencia
    private var spo2ReadingsSinceLastTrendCalc = 0
    private var hrReadingsSinceLastTrendCalc = 0

    // Variables para almacenar el último dato válido recibido del sensor
    // Se actualizan directamente desde el flujo de Bluetooth y se usan en el bucle de procesamiento
    private var lastValidSpo2FromSensor: Int? = null
    private var lastValidHrFromSensor: Int? = null
    private var lastNoFingerDetectedFromSensor: Boolean? = null
    private var lastSignalStrengthFromSensor: Int? = null

    // Umbrales definidos por el usuario (cargados desde SettingsRepository)
    private var userSpo2WarningThreshold = DefaultThresholdValues.SPO2_WARNING_DEFAULT
    private var userSpo2CriticalThreshold = DefaultThresholdValues.SPO2_CRITICAL_DEFAULT
    private var userHrCriticalLowThreshold = DefaultThresholdValues.HR_CRITICAL_LOW_DEFAULT
    private var userHrWarningLowThreshold = DefaultThresholdValues.HR_WARNING_LOW_DEFAULT
    private var userHrWarningHighThreshold = DefaultThresholdValues.HR_WARNING_HIGH_DEFAULT
    private var userHrCriticalHighThreshold = DefaultThresholdValues.HR_CRITICAL_HIGH_DEFAULT

    // Formateador de tiempo para mostrar "mm:ss"
    private val timeFormatter = SimpleDateFormat("mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("GMT") // Usar GMT para evitar problemas con zonas horarias
    }

    /**
     * Bloque de inicialización del ViewModel.
     * Se ejecuta cuando se crea la instancia del ViewModel.
     * Carga los umbrales del usuario, observa los datos y el estado de Bluetooth.
     */
    init {
        Log.d("TestExecutionVM", "ViewModel inicializado. Observando datos y conexión Bluetooth.")
        // Cargar los umbrales del usuario de forma asíncrona al inicio
        viewModelScope.launch {
            userSpo2WarningThreshold = settingsRepository.spo2WarningThresholdFlow.first()
            userSpo2CriticalThreshold = settingsRepository.spo2CriticalThresholdFlow.first()
            userHrCriticalLowThreshold = settingsRepository.hrCriticalLowThresholdFlow.first()
            userHrWarningLowThreshold = settingsRepository.hrWarningLowThresholdFlow.first()
            userHrWarningHighThreshold = settingsRepository.hrWarningHighThresholdFlow.first()
            userHrCriticalHighThreshold = settingsRepository.hrCriticalHighThresholdFlow.first()

            Log.i(
                "TestExecutionVM", "Umbrales de usuario cargados: " +
                        "SpO2 Warn=$userSpo2WarningThreshold, SpO2 Crit=$userSpo2CriticalThreshold, " +
                        "HR CritLow=$userHrCriticalLowThreshold, HR WarnLow=$userHrWarningLowThreshold, " +
                        "HR WarnHigh=$userHrWarningHighThreshold, HR CritHigh=$userHrCriticalHighThreshold"
            )

            // La lógica que depende de estos umbrales (ej. colores iniciales) puede proceder.
        }

        // --- Observación Pulsioxímetro ---
        observeRawOximeterData() // Renombrado
        observeOximeterConnectionStatus() // Renombrado

        // --- Observación Acelerómetro ---
        observeRawAccelerometerData() // NUEVO
        observeAccelerometerConnectionStatus() // NUEVO

        // Iniciar el bucle de procesamiento de datos en vivo (cálculo de tendencias, etc.)
        startLiveDataProcessingLoop()

        // Inicializar el estado visual de Bluetooth en la UI al arrancar el ViewModel
        _uiState.update { currentState ->
            // Pulsioxímetro
            val (initialOximeterIcon, initialOximeterMsg) = determineOximeterVisualStatus(
                connectionStatus = bluetoothService.oximeterConnectionStatus.value, // Oximeter status
                deviceData = bluetoothService.oximeterDeviceData.value,        // Oximeter data
                isBluetoothAdapterEnabled = bluetoothService.isBluetoothEnabled(),
            )
            // Acelerómetro
            val (initialAccelerometerIcon, initialAccelerometerMsg) = determineAccelerometerVisualStatus(
                connectionStatus = bluetoothService.wearableConnectionStatus.value, // Accelerometer status
                // wearableData = bluetoothService.wearableDeviceData.value, // Puede no ser necesario para el estado inicial si solo depende de la conexión
                isBluetoothAdapterEnabled = bluetoothService.isBluetoothEnabled(),
            )
            val initialAccelerometerConnected =
                bluetoothService.wearableConnectionStatus.value.isConsideredConnectedOrSubscribed()


            currentState.copy(
                oximeterBluetoothIconStatus = initialOximeterIcon,
                oximeterBluetoothStatusMessage = initialOximeterMsg,
                accelerometerBluetoothIconStatus = initialAccelerometerIcon,
                accelerometerBluetoothStatusMessage = initialAccelerometerMsg,
                isAccelerometerConnected = initialAccelerometerConnected
            )
        }
    }

    /**
     * Observa el flujo de datos crudos (BleDeviceData) provenientes del BluetoothService.
     * Esta función se encarga de:
     * 1. Guardar los últimos valores válidos de SpO2, FC, detección de dedo y señal
     *    en variables locales (lastValidSpo2FromSensor, etc.). Estos valores serán
     *    utilizados por `startLiveDataProcessingLoop` para cálculos de tendencia y estado.
     * 2. Actualizar INMEDIATAMENTE ciertos aspectos del `_uiState` que dependen directamente
     *    de la llegada de nuevos datos crudos:
     *    - `currentSpo2` y `currentHeartRate` (mostrados en la UI).
     *    - `isSensorFingerPresent` (para reflejar si el dedo está puesto).
     *    - `currentSignalStrength`.
     *    - El icono y mensaje de estado de Bluetooth (`bluetoothIconStatus`, `bluetoothStatusMessage`),
     *      determinados por `determineBluetoothVisualStatus` usando los datos más recientes.
     *
     * Esto asegura que la UI refleje lo más rápido posible los datos del sensor y el estado de conexión,
     * mientras que el procesamiento más intensivo (tendencias) se hace en un bucle separado.
     */
    private fun observeRawOximeterData() {
        bluetoothService.oximeterDeviceData
            .onEach { data: BleDeviceData ->
                // Guardar los últimos datos recibidos del sensor para procesamiento posterior
                lastValidSpo2FromSensor = data.spo2
                lastValidHrFromSensor = data.heartRate
                lastNoFingerDetectedFromSensor = data.noFingerDetected
                lastSignalStrengthFromSensor = data.signalStrength

                // Determinar si el dedo está presente BASADO EN LOS DATOS CRUDOS MÁS RECIENTES
                val isFingerCurrentlyPresent = !(data.noFingerDetected == true ||
                        data.signalStrength == NO_FINGER_OR_RECALIBRATING_SIGNAL ||
                        data.spo2 == null || data.spo2 <= 0 ||
                        data.heartRate == null || data.heartRate <= 0)

                // Los valores a mostrar en la UI serán null si no hay dedo
                val displaySpo2 = if (isFingerCurrentlyPresent) data.spo2 else null
                val displayHr = if (isFingerCurrentlyPresent) data.heartRate else null

                // Actualizar el estado de la UI con los datos directos del sensor y el estado de BT
                _uiState.update { currentState ->
                    val (newIconStatus, newStatusMessage) = determineOximeterVisualStatus(
                        connectionStatus = bluetoothService.oximeterConnectionStatus.value, // Estado de conexión actual
                        deviceData = data, // Usar los datos más recientes que acaban de llegar
                        isBluetoothAdapterEnabled = bluetoothService.isBluetoothEnabled()
                    )
                    currentState.copy(
                        currentSpo2 = displaySpo2, // Actualización inmediata de SpO2 visible
                        currentHeartRate = displayHr, // Actualización inmediata de FC visible
                        isSensorFingerPresent = isFingerCurrentlyPresent, // Actualización inmediata de presencia de dedo
                        currentSignalStrength = data.signalStrength,   // Actualización inmediata de señal
                        // El icono de BT se actualiza, a menos que esté en reconexión forzada
                        oximeterBluetoothIconStatus = if (currentState.isAttemptingOximeterForceReconnect) BluetoothIconStatus.CONNECTING else newIconStatus,
                        oximeterBluetoothStatusMessage = if (currentState.isAttemptingOximeterForceReconnect) "Reconectando..." else newStatusMessage
                        // Las tendencias (spo2Trend, heartRateTrend) y colores de estado (spo2StatusColor, etc.)
                        // se actualizan en el bucle startLiveDataProcessingLoop.
                    )
                }
            }
            .catch { e -> Log.e("TestExecutionVM", "Error en flow BleDeviceData: ${e.message}", e) }
            .launchIn(viewModelScope) // Lanzar la coroutina en el viewModelScope
    }

    /**
     * Observa el flujo de datos (WearableDeviceData) provenientes del ACELERÓMETRO.
     * Calcula la distancia si la prueba está en curso.
     * Actualiza el estado visual del acelerómetro en la UI.
     */
    private fun observeRawAccelerometerData() {
        var previousTotalStepsFromWearable: Int? = null // Para detectar la primera lectura tras una conexión

        bluetoothService.wearableDeviceData
            .onEach { wearableData ->
                _uiState.update { currentState ->
                    var newDistance = currentState.distanceMeters
                    var currentSegmentInitialSteps = initialStepsAtTestStart // Este es el del inicio de la PRUEBA o del ÚLTIMO reinicio del wearable.
                    var accumulatedDist = currentState.accumulatedDistanceBeforeLastReconnect
                    var newLastKnownSteps = currentState.lastKnownTotalStepsBeforeReconnect


                    val isCurrentlyConnected = bluetoothService.wearableConnectionStatus.value.isConsideredConnectedOrSubscribed()

                    if (currentState.isTestRunning && patientStrideLength > 0f) {
                        val currentTotalStepsFromWearable = wearableData.totalSteps

                        if (currentTotalStepsFromWearable != null) {
                            // Escenario A: Es la primera lectura válida después de que isTestRunning es true
                            // O es la primera lectura después de una RECONEXIÓN
                            if (currentSegmentInitialSteps == null || (previousTotalStepsFromWearable == null && isCurrentlyConnected)) {
                                Log.d("AccelData", "Nueva referencia de pasos. Total actual: $currentTotalStepsFromWearable. Dist acumulada: $accumulatedDist. initialStepsAtTestStart ANTES: $initialStepsAtTestStart")
                                // Si previousTotalStepsFromWearable era null, es la primera lectura de este flujo o tras reconexión.
                                // La 'accumulatedDist' ya debería tener la distancia antes de la desconexión.
                                // Establecemos los pasos iniciales para ESTE NUEVO SEGMENTO de conexión.
                                initialStepsAtTestStart = currentTotalStepsFromWearable // Referencia para el nuevo segmento
                                currentSegmentInitialSteps = currentTotalStepsFromWearable
                                Log.d("AccelData", "initialStepsAtTestStart DESPUÉS: $initialStepsAtTestStart")
                                // No se suman pasos en esta primera lectura, la distancia es la acumulada.
                                newDistance = accumulatedDist
                            }
                            // Escenario B: Reinicio del contador del wearable DETECTADO
                            else if (newLastKnownSteps != null &&
                                currentTotalStepsFromWearable < newLastKnownSteps &&
                                currentTotalStepsFromWearable < currentSegmentInitialSteps // Importante: menor que el inicio del segmento actual
                            ) {
                                Log.w("AccelData", "Reinicio de contador de pasos del wearable detectado. Pasos actuales: $currentTotalStepsFromWearable, Últimos conocidos: $newLastKnownSteps, Iniciales de segmento: $currentSegmentInitialSteps")
                                // La distancia calculada hasta *antes* de este reinicio se convierte en la nueva base acumulada.
                                accumulatedDist = currentState.distanceMeters // Guardar la distancia TOTAL lograda hasta ahora
                                initialStepsAtTestStart = currentTotalStepsFromWearable // Nuevo inicio para este segmento
                                currentSegmentInitialSteps = currentTotalStepsFromWearable
                                newDistance = accumulatedDist // La distancia es la acumulada hasta este punto
                                Log.d("AccelData", "Nueva distancia acumulada por reinicio wearable: $accumulatedDist. Nuevos pasos iniciales para este segmento: $currentSegmentInitialSteps")
                            }
                            // Escenario C: Flujo normal de datos, calcular incremento de distancia
                            else if (currentSegmentInitialSteps != null) { // Debería estar definido
                                val stepsTakenSinceLastReference = (currentTotalStepsFromWearable - currentSegmentInitialSteps).coerceAtLeast(0)
                                val distanceThisSegment = stepsTakenSinceLastReference * patientStrideLength.toFloat()
                                newDistance = accumulatedDist + distanceThisSegment
                            } else {
                                Log.w("AccelData", "currentSegmentInitialSteps es null inesperadamente. Usando distancia acumulada: $accumulatedDist")
                                newDistance = accumulatedDist
                            }

                            newLastKnownSteps = currentTotalStepsFromWearable // Actualizar para la próxima iteración
                        } else { // currentTotalStepsFromWearable es null
                            Log.w("AccelData", "Total steps from wearable is null. Manteniendo distancia: $newDistance")
                            // Mantener la distancia (que incluye la accumulatedDist).
                            // Si se desconecta, `accumulatedDist` se actualiza en `observeAccelerometerConnectionStatus`.
                        }
                        previousTotalStepsFromWearable = currentTotalStepsFromWearable // Guardar para la próxima
                    } else if (!currentState.isTestRunning && !currentState.isTestFinished) {
                        // Solo resetear si la prueba no está corriendo Y no ha finalizado
                        newDistance = 0f
                        accumulatedDist = 0f
                        newLastKnownSteps = null
                        initialStepsAtTestStart = null // Resetear al detener/reiniciar prueba
                        previousTotalStepsFromWearable = null
                    }

                    currentState.copy(
                        distanceMeters = newDistance,
                        isAccelerometerConnected = isCurrentlyConnected,
                        accumulatedDistanceBeforeLastReconnect = accumulatedDist,
                        lastKnownTotalStepsBeforeReconnect = newLastKnownSteps
                    )
                }
            }
            .catch { e -> Log.e("TestExecutionVM", "Error en flow WearableDeviceData (Accelerometer): ${e.message}", e)
                _uiState.update { it.copy(isAccelerometerConnected = false) } // Marcar como desconectado en error
            }
            .launchIn(viewModelScope)
    }


    /**
     * Inicia un bucle de coroutine que se ejecuta cada segundo mientras el ViewModel esté activo.
     * Este bucle es responsable de:
     * 1. Tomar los últimos valores de SpO2 y FC guardados por `observeRawBluetoothDeviceData`
     *    (a través de `lastValidSpo2FromSensor` y `lastValidHrFromSensor`).
     * 2. Determinar si el dedo está presente (basado en `_uiState.value.isSensorFingerPresent`,
     *    que ya fue actualizado por `observeRawBluetoothDeviceData`).
     * 3. Si el dedo está presente y los datos son válidos:
     *    a. Calcular y actualizar los colores de estado para SpO2 y FC (`spo2StatusColor`, `heartRateStatusColor`)
     *       basándose en los umbrales.
     *    b. Recolectar los nuevos valores de SpO2 y FC en `spo2ValuesForTrendCalculation` y
     *       `hrValuesForTrendCalculation`.
     *    c. Cuando se hayan recolectado suficientes nuevos valores (`NEW_VALUES_THRESHOLD_FOR_TREND_CALC`),
     *       recalcular las tendencias (`spo2Trend`, `heartRateTrend`) usando `calculateTrendFromAverageOfLastThree`.
     * 4. Si el dedo no está presente o los datos no son válidos:
     *    a. Resetear las tendencias a `STABLE`.
     *    b. Establecer los colores de estado a `UNKNOWN`.
     *    c. Limpiar las listas usadas para el cálculo de tendencias.
     * 5. Actualizar el `_uiState` con las nuevas tendencias y colores de estado.
     *
     * Este enfoque desacopla el cálculo de tendencias (que puede ser menos frecuente o
     * basarse en una ventana de datos) de la actualización inmediata de los valores del sensor.
     */
    private fun startLiveDataProcessingLoop() {
        liveDataProcessingJob?.cancel() // Cancelar cualquier job anterior
        liveDataProcessingJob = viewModelScope.launch {
            while (isActive) { // El bucle se ejecuta mientras la coroutina esté activa
                delay(1000L) // Pausa de 1 segundo entre procesamientos

                // Obtener los últimos valores guardados por observeRawBluetoothDeviceData
                val spo2ForProcessing = lastValidSpo2FromSensor
                val hrForProcessing = lastValidHrFromSensor
                // Obtener el estado actual de presencia de dedo desde la UI (ya actualizado)
                val isFingerPresentCurrently = _uiState.value.isSensorFingerPresent

                _uiState.update { currentState ->
                    var newSpo2Trend: Trend = currentState.spo2Trend
                    var newHrTrend: Trend = currentState.heartRateTrend
                    var newSpo2StatusColor: StatusColor = currentState.spo2StatusColor
                    var newHrStatusColor: StatusColor = currentState.heartRateStatusColor

                    // Solo procesar si el dedo está presente y los datos del sensor son válidos
                    if (isFingerPresentCurrently && spo2ForProcessing != null && hrForProcessing != null && spo2ForProcessing > 0 && hrForProcessing > 0) {
                        // Determinar colores de estado basados en umbrales
                        newSpo2StatusColor = determineStatusColorSpo2(spo2ForProcessing)
                        newHrStatusColor = determineStatusColorHr(hrForProcessing)

                        // Calcular tendencias siempre
                        if (true) {
                            // Calcular tendencias (se hace siempre si hay datos válidos)
                            // Lógica de TENDENCIA para SpO2
                            spo2ValuesForTrendCalculation.add(spo2ForProcessing)
                            // Mantener solo los últimos TREND_WINDOW_SIZE_FOR_CALC valores
                            while (spo2ValuesForTrendCalculation.size > TREND_WINDOW_SIZE_FOR_CALC) {
                                if (spo2ValuesForTrendCalculation.isNotEmpty()) spo2ValuesForTrendCalculation.removeAt(
                                    0
                                )
                            }
                            spo2ReadingsSinceLastTrendCalc++
                            // Recalcular tendencia si se alcanza el umbral de nuevos valores
                            if (spo2ReadingsSinceLastTrendCalc >= NEW_VALUES_THRESHOLD_FOR_TREND_CALC) {
                                if (spo2ValuesForTrendCalculation.size == TREND_WINDOW_SIZE_FOR_CALC) {
                                    newSpo2Trend = calculateTrendFromAverageOfLastThree(
                                        spo2ValuesForTrendCalculation.toList()
                                    )
                                }
                                spo2ReadingsSinceLastTrendCalc = 0 // Resetear contador
                            }

                            // Lógica de TENDENCIA para Heart Rate (similar a SpO2)
                            hrValuesForTrendCalculation.add(hrForProcessing)
                            while (hrValuesForTrendCalculation.size > TREND_WINDOW_SIZE_FOR_CALC) {
                                if (hrValuesForTrendCalculation.isNotEmpty()) hrValuesForTrendCalculation.removeAt(
                                    0
                                )
                            }
                            hrReadingsSinceLastTrendCalc++
                            if (hrReadingsSinceLastTrendCalc >= NEW_VALUES_THRESHOLD_FOR_TREND_CALC) {
                                if (hrValuesForTrendCalculation.size == TREND_WINDOW_SIZE_FOR_CALC) {
                                    newHrTrend = calculateTrendFromAverageOfLastThree(
                                        hrValuesForTrendCalculation.toList()
                                    )
                                }
                                hrReadingsSinceLastTrendCalc = 0
                            }
                        }
                    } else {
                        // Si no hay dedo o los datos son inválidos:
                        // Resetear tendencias y colores a valores por defecto/desconocido
                        newSpo2Trend = Trend.STABLE
                        newHrTrend = Trend.STABLE
                        newSpo2StatusColor = StatusColor.UNKNOWN
                        newHrStatusColor = StatusColor.UNKNOWN

                        // Limpiar las listas de cálculo de tendencia y los contadores
                        spo2ValuesForTrendCalculation.clear()
                        hrValuesForTrendCalculation.clear()
                        spo2ReadingsSinceLastTrendCalc = 0
                        hrReadingsSinceLastTrendCalc = 0
                    }

                    // Actualizar el estado de la UI con las nuevas tendencias y colores
                    currentState.copy(
                        spo2Trend = newSpo2Trend,
                        heartRateTrend = newHrTrend,
                        spo2StatusColor = newSpo2StatusColor,
                        heartRateStatusColor = newHrStatusColor
                        // currentSpo2, currentHeartRate, isSensorFingerPresent ya se actualizan en observeRawBluetoothDeviceData
                    )
                }
            }
        }
    }

    /**
     * Calcula la tendencia (SUBE, BAJA, ESTABLE) comparando el promedio de los
     * 3 valores más recientes con el promedio de los 3 valores anteriores a esos.
     * Requiere que `TREND_WINDOW_SIZE_FOR_CALC` sea 6.
     * @param currentValues Lista de los últimos `TREND_WINDOW_SIZE_FOR_CALC` valores (deberían ser 6).
     * @return La [Trend] calculada.
     */
    private fun calculateTrendFromAverageOfLastThree(currentValues: List<Int>): Trend {
        if (currentValues.size < TREND_WINDOW_SIZE_FOR_CALC) {
            return Trend.STABLE // No hay suficientes datos
        }

        // Divide la lista de 6 en dos sublistas de 3
        val previousThree = currentValues.subList(0, 3) // Los 3 más antiguos
        val currentThree = currentValues.subList(3, 6) // Los 3 más recientes

        val averagePreviousThree = previousThree.average()
        val averageCurrentThree = currentThree.average()

        return when {
            averageCurrentThree > averagePreviousThree -> Trend.UP
            averageCurrentThree < averagePreviousThree -> Trend.DOWN
            else -> Trend.STABLE
        }
    }

    /**
     * Determina el icono de Bluetooth y el mensaje de estado para la UI.
     * Esta función es la ÚNICA FUENTE DE VERDAD para el estado visual del icono BT y su mensaje.
     * Considera el estado de conexión del servicio, los datos del dispositivo (si el dedo está puesto, señal)
     * y si el adaptador Bluetooth del teléfono está habilitado.
     *
     * @param connectionStatus El estado actual de la conexión Bluetooth.
     * @param deviceData Los datos más recientes del dispositivo Bluetooth (sensor).
     * @param isBluetoothAdapterEnabled True si el adaptador Bluetooth del teléfono está activado.
     * @return Un par con el [BluetoothIconStatus] y el [String] del mensaje.
     */
    private fun determineOximeterVisualStatus(
        connectionStatus: BleConnectionStatus,
        deviceData: BleDeviceData, // Usar siempre los datos más recientes del sensor
        isBluetoothAdapterEnabled: Boolean,
    ): Pair<BluetoothIconStatus, String> {

        // CASO 1: Bluetooth del teléfono/tablet DESACTIVADO
        if (!isBluetoothAdapterEnabled) {
            return Pair(BluetoothIconStatus.RED, "Pulsioxímetro: active Bluetooth")
        }

        // CASOS 2: Basados en el estado de la conexión del servicio Bluetooth
        return when (connectionStatus) {
            BleConnectionStatus.SUBSCRIBED -> { // Conectado y recibiendo datos
                // Evaluar la calidad de los datos del sensor
                if (deviceData.noFingerDetected == true ||
                    deviceData.signalStrength == NO_FINGER_OR_RECALIBRATING_SIGNAL ||
                    deviceData.spo2 == null || deviceData.spo2 <= 0 ||
                    deviceData.heartRate == null || deviceData.heartRate <= 0
                ) {
                    // Problema con el dedo o datos inválidos
                    Pair(BluetoothIconStatus.YELLOW, "Pulsioxímetro: coloque el dedo")
                } else if (deviceData.signalStrength != null && deviceData.signalStrength <= POOR_SIGNAL_THRESHOLD) {
                    // Señal baja
                    Pair(BluetoothIconStatus.YELLOW, "Pulsioxímetro: señal baja")
                } else {
                    // Todo OK, datos válidos y buena señal
                    Pair(
                        BluetoothIconStatus.GREEN,
                        "Pulsioxímetro: conectado"
                    ) // Manteniendo tu mensaje original por ahora
                }
            }

            BleConnectionStatus.CONNECTED -> { // Conectado pero aún no suscrito (o fallo al suscribir)
                // Evaluar si el sensor da alguna indicación temprana
                if (deviceData.noFingerDetected == true ||
                    deviceData.signalStrength == NO_FINGER_OR_RECALIBRATING_SIGNAL ||
                    deviceData.spo2 == null || deviceData.spo2 == 0 || // podría ser 0 si no hay dato aún
                    deviceData.heartRate == null || deviceData.heartRate == 0
                ) {
                    Pair(BluetoothIconStatus.YELLOW, "Pulsioxímetro: sensor no listo / sin dedo")
                } else {
                    Pair(
                        BluetoothIconStatus.YELLOW,
                        "Pulsioxímetro: conectado (parcial)"
                    ) // Esperando datos/suscripción
                }
            }

            BleConnectionStatus.CONNECTING, BleConnectionStatus.RECONNECTING -> { // En proceso de conexión/reconexión
                Pair(BluetoothIconStatus.CONNECTING, " Pulsioxímetro: conectando...")
            }
            // Casos de desconexión o error
            BleConnectionStatus.IDLE, // Estado inicial o después de desconexión manual
            BleConnectionStatus.SCANNING, // Durante el escaneo (no directamente usado aquí pero para ser exhaustivos)
            BleConnectionStatus.DISCONNECTED_BY_USER,
            BleConnectionStatus.DISCONNECTED_ERROR,
            BleConnectionStatus.ERROR_GENERIC, // Todos los errores que implican no conexión
            BleConnectionStatus.ERROR_DEVICE_NOT_FOUND,
            BleConnectionStatus.ERROR_SERVICE_NOT_FOUND,
            BleConnectionStatus.ERROR_CHARACTERISTIC_NOT_FOUND,
            BleConnectionStatus.ERROR_SUBSCRIBE_FAILED -> {
                Pair(
                    BluetoothIconStatus.RED,
                    "Pulsioxímetro: pérdida de conexión, pulse para reconectar"
                )
            }

            else -> {
                // Estado desconocido o no manejado explícitamente
                Log.w("TestExecutionVM", "Estado Oximeter BT no manejado: $connectionStatus. Defecto a GRIS.")
                Pair(
                    BluetoothIconStatus.GRAY,
                    "Pulsioxímetro: ${connectionStatus.name}"
                ) // Mensaje genérico
            }
        }
    }

    /**
     * NUEVA: Determina el icono y mensaje para el ACELERÓMETRO.
     */
    private fun determineAccelerometerVisualStatus(
        connectionStatus: BleConnectionStatus, // Estado del Acelerómetro (Wearable)
        // wearableData: WearableDeviceData, // Podría usarse si el wearable da más info de estado
        isBluetoothAdapterEnabled: Boolean,
    ): Pair<BluetoothIconStatus, String> {
        if (!isBluetoothAdapterEnabled) {
            return Pair(BluetoothIconStatus.RED, "Acelerómetro: Active Bluetooth")
        }

        return when (connectionStatus) {
            // Para el acelerómetro, "CONNECTED" suele ser suficiente si ya está enviando datos.
            // O si tienes un estado específico como "STREAMING" o "SUBSCRIBED" para el wearable, úsalo.
            BleConnectionStatus.SUBSCRIBED, BleConnectionStatus.CONNECTED -> { // Asumiendo que CONNECTED implica que puede enviar pasos
                // Aquí podrías verificar wearableData.totalSteps si fuera necesario,
                // pero si solo te importa la conexión, esto está bien.
                // if (wearableData.totalSteps == null && _uiState.value.isTestRunning) {
                //     Pair(BluetoothIconStatus.YELLOW, "Acelerómetro: Sin datos de pasos")
                // } else {
                Pair(BluetoothIconStatus.GREEN, "Acelerómetro: Conectado")
                // }
            }

            BleConnectionStatus.CONNECTING, BleConnectionStatus.RECONNECTING -> {
                Pair(BluetoothIconStatus.CONNECTING, "Acelerómetro: Conectando...")
            }

            BleConnectionStatus.IDLE,
            BleConnectionStatus.SCANNING,
            BleConnectionStatus.DISCONNECTED_BY_USER,
            BleConnectionStatus.DISCONNECTED_ERROR,
            BleConnectionStatus.ERROR_GENERIC,
            BleConnectionStatus.ERROR_DEVICE_NOT_FOUND,
            BleConnectionStatus.ERROR_SERVICE_NOT_FOUND,
            BleConnectionStatus.ERROR_CHARACTERISTIC_NOT_FOUND,
            BleConnectionStatus.ERROR_SUBSCRIBE_FAILED -> {
                Pair(
                    BluetoothIconStatus.RED,
                    "Acelerómetro: pérdida de conexión, pulse para reconectar"
                )
            }

            else -> {
                Log.w("TestExecutionVM", "Estado Accelerometer BT no manejado: $connectionStatus.")
                Pair(BluetoothIconStatus.GRAY, "Acelerómetro: ${connectionStatus.name}")
            }
        }
    }

    /**
     * Observa los cambios en el estado de la conexión Bluetooth desde el `BluetoothService`.
     * Actualiza la UI con el icono y mensaje apropiados usando `determineBluetoothVisualStatus`.
     * También maneja la presentación de mensajes al usuario (`userMessage`) relacionados
     * con la conexión, dependiendo de si la prueba está en curso, en configuración o finalizada.
     */
    private fun observeOximeterConnectionStatus() {
        bluetoothService.oximeterConnectionStatus
            .onEach { status: BleConnectionStatus ->
                Log.i("TestExecutionVM", "Estado de conexión Bluetooth: $status")
                // Siempre usar los datos más recientes del sensor para determinar el estado visual
                val currentDeviceData = bluetoothService.oximeterDeviceData.value
                _uiState.update { currentState ->
                    // Obtener el nuevo estado visual (icono y mensaje)
                    val (newIconStatus, newStatusMessage) = determineOximeterVisualStatus(
                        connectionStatus = status,
                        deviceData = currentDeviceData,
                        isBluetoothAdapterEnabled = bluetoothService.isBluetoothEnabled()
                    )

                    var userMessageUpdate: String? = currentState.userMessage // Mantener mensaje actual por defecto
                    val isNowConnected = status.isConsideredConnectedOrSubscribed()

                    // Lógica para mensajes al usuario basados en el estado de la prueba y la conexión
                    if (currentState.isTestRunning) {
                        if (!isNowConnected && currentState.oximeterBluetoothIconStatus != BluetoothIconStatus.RED /* y no estaba ya en un estado de error/desconexión reportado */) { // Revisar si estaba previamente bien
                            userMessageUpdate = when (status) {
                                BleConnectionStatus.DISCONNECTED_ERROR,
                                BleConnectionStatus.ERROR_GENERIC -> "¡Conexión con pulsioxímetro perdida!"
                                BleConnectionStatus.ERROR_BLUETOOTH_DISABLED -> "Bluetooth desactivado. Se perdió conexión con pulsioxímetro."
                                else -> "Pulsioxímetro desconectado inesperadamente."
                            }
                        } else if (isNowConnected &&
                                    (currentState.oximeterBluetoothIconStatus == BluetoothIconStatus.RED ||
                                    currentState.userMessage?.contains("Pulsioxímetro perdida", ignoreCase = true) == true ||
                                    currentState.userMessage?.contains("Pulsioxímetro desconectado", ignoreCase = true) == true )
                                  ) {
                            userMessageUpdate = "Conexión con pulsioxímetro restaurada."
                        }
                    } else if (currentState.isConfigPhase || currentState.isTestFinished) {
                        if (!isNowConnected) {
                            userMessageUpdate = when (status) {
                                BleConnectionStatus.ERROR_BLUETOOTH_DISABLED -> "Bluetooth desactivado. Actívelo para conectar el pulsioxímetro."
                                else -> if (status.isErrorStatus() && !currentState.userMessage.orEmpty().contains("Acelerómetro")) "Pulsioxímetro: ${newStatusMessage}" else currentState.userMessage
                            }
                        } else {
                            if (currentState.userMessage?.contains("Pulsioxímetro", ignoreCase = true) == true &&
                                (currentState.userMessage?.contains("Bluetooth", ignoreCase = true) == true ||
                                currentState.userMessage?.contains("conexión", ignoreCase = true) == true)) {
                                userMessageUpdate = null
                            }
                        }
                    }

                    // Si el estado del acelerómetro cambia a conectado mientras se intentaba reconectar, limpiar el flag.
                    var attemptingReconnect = currentState.isAttemptingOximeterForceReconnect
                    if (attemptingReconnect && isNowConnected) {
                        attemptingReconnect = false
                    }

                    currentState.copy(
                        userMessage = userMessageUpdate,
                        oximeterBluetoothIconStatus = if (attemptingReconnect) BluetoothIconStatus.CONNECTING else newIconStatus,
                        oximeterBluetoothStatusMessage = if (attemptingReconnect) "Pulsioxímetro: Reconectando..." else newStatusMessage,
                        isAttemptingOximeterForceReconnect = attemptingReconnect // Actualizar el flag
                    )
                }
            }
            .catch { e ->
                Log.e("TestExecutionVM", "Error en el flow de BleConnectionStatus (Oximeter): ${e.message}", e)
                _uiState.update {
                    it.copy(
                        userMessage = "Error interno (pulsioxímetro).",
                        oximeterBluetoothIconStatus = BluetoothIconStatus.RED, // Indicar error
                        oximeterBluetoothStatusMessage = "Pulsioxímetro: error interno"
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Observa los cambios en el estado de la conexión del ACELERÓMETRO.
     */
    private fun observeAccelerometerConnectionStatus() {
        bluetoothService.wearableConnectionStatus // Estado del Acelerómetro
            .onEach { status: BleConnectionStatus ->
                Log.i("TestExecutionVM", "Estado de conexión Acelerómetro: $status")
                _uiState.update { currentState ->
                    val (newIconStatus, newStatusMessage) = determineAccelerometerVisualStatus(
                        connectionStatus = status,
                        isBluetoothAdapterEnabled = bluetoothService.isBluetoothEnabled()
                    )

                    var newAccumulatedDistance = currentState.accumulatedDistanceBeforeLastReconnect
                    var userMessageUpdate: String? = currentState.userMessage
                    val isNowConnected = status.isConsideredConnectedOrSubscribed()

                    if (currentState.isTestRunning) {
                        // Mensajes específicos si se pierde la conexión del acelerómetro DURANTE la prueba
                        if (!isNowConnected && currentState.isAccelerometerConnected) { // Si estaba conectado y ahora no
                            Log.d("AccelConnection", "Acelerómetro desconectado durante la prueba. Distancia actual: ${currentState.distanceMeters}")
                            newAccumulatedDistance = currentState.distanceMeters
                            userMessageUpdate = when (status) {
                                BleConnectionStatus.DISCONNECTED_ERROR,
                                BleConnectionStatus.ERROR_GENERIC -> "¡Conexión con acelerómetro perdida!"

                                BleConnectionStatus.ERROR_BLUETOOTH_DISABLED -> "Bluetooth desactivado. Se perdió conexión con acelerómetro."
                                else -> "Acelerómetro desconectado inesperadamente."
                            }
                        } else if (isNowConnected && !currentState.isAccelerometerConnected) { // Si NO estaba conectado y AHORA SÍ
                            Log.d("AccelConnection", "Acelerómetro RECONECTADO durante la prueba. Distancia acumulada previa: $newAccumulatedDistance")
                            // En este punto, `newAccumulatedDistance` debería tener la distancia guardada
                            // y `initialStepsAtTestStart` se establecerá con la *primera lectura de pasos post-reconexión*
                            // dentro de `observeRawAccelerometerData`.
                            // lastKnownTotalStepsBeforeReconnect también se usará/actualizará allí.

                            // Lógica de mensajes de usuario
                            if (currentState.userMessage?.contains("Acelerómetro perdida", ignoreCase = true) == true ||
                                currentState.userMessage?.contains("Acelerómetro desconectado", ignoreCase = true) == true) {
                                userMessageUpdate = "Conexión con acelerómetro restaurada."
                            }
                        }
                    } else if (currentState.isConfigPhase || currentState.isTestFinished) {
                        // Mensajes si no está conectado fuera de la prueba
                        if (!isNowConnected) {
                            userMessageUpdate = when (status) {
                                BleConnectionStatus.ERROR_BLUETOOTH_DISABLED -> "Bluetooth desactivado. Actívelo para conectar el acelerómetro."
                                else -> if (status.isErrorStatus()) "Acelerómetro: ${newStatusMessage}" else currentState.userMessage // No sobreescribir mensajes del oximetro
                            }
                        } else { // Conectado fuera de la prueba
                            if (currentState.userMessage?.contains("Acelerómetro", ignoreCase = true) == true &&
                                (currentState.userMessage?.contains("Bluetooth", ignoreCase = true) == true ||
                                currentState.userMessage?.contains("conexión", ignoreCase = true) == true)
                            ) {
                                userMessageUpdate = null // Limpiar si era un mensaje sobre el acelerómetro
                            }
                        }
                    }

                    currentState.copy(
                        userMessage = userMessageUpdate,
                        accelerometerBluetoothIconStatus = if (currentState.isAttemptingAccelerometerForceReconnect) currentState.accelerometerBluetoothIconStatus else newIconStatus, // Evitar cambiar si el OTRO se está reconectando
                        accelerometerBluetoothStatusMessage = if (currentState.isAttemptingAccelerometerForceReconnect) currentState.accelerometerBluetoothStatusMessage else newStatusMessage,
                        isAccelerometerConnected = isNowConnected,
                        accumulatedDistanceBeforeLastReconnect = newAccumulatedDistance
                    )
                }
            }
            .catch { e ->
                Log.e("TestExecutionVM", "Error en flow WearableConnectionStatus (Accelerometer): ${e.message}", e)
                _uiState.update {
                    it.copy(
                        userMessage = "Error interno (acelerómetro).",
                        accelerometerBluetoothIconStatus = BluetoothIconStatus.RED,
                        accelerometerBluetoothStatusMessage = "Acelerómetro: Error interno"
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Función de extensión para [BleConnectionStatus] que determina si el estado es un error de conexión.
     * @return `true` si el estado representa un error, `false` en caso contrario.
     */
    fun BleConnectionStatus.isErrorStatus(): Boolean {
        return this == BleConnectionStatus.DISCONNECTED_ERROR ||
                this == BleConnectionStatus.ERROR_BLUETOOTH_DISABLED ||
                this == BleConnectionStatus.ERROR_PERMISSIONS ||
                this == BleConnectionStatus.ERROR_DEVICE_NOT_FOUND ||
                this == BleConnectionStatus.ERROR_SERVICE_NOT_FOUND ||
                this == BleConnectionStatus.ERROR_CHARACTERISTIC_NOT_FOUND ||
                this == BleConnectionStatus.ERROR_SUBSCRIBE_FAILED ||
                this == BleConnectionStatus.ERROR_GENERIC
    }

    /**
     * Función de extensión para [BleConnectionStatus] que determina si el estado
     * se considera "conectado o suscrito", indicando una conexión funcional.
     * @return `true` si el estado es `CONNECTED` o `SUBSCRIBED`, `false` en caso contrario.
     */
    private fun BleConnectionStatus.isConsideredConnectedOrSubscribed(): Boolean {
        return this == BleConnectionStatus.CONNECTED || this == BleConnectionStatus.SUBSCRIBED
    }

    /**
     * Inicializa o reinicializa la pantalla de ejecución de la prueba con los datos
     * de preparación del paciente.
     * Restablece el estado de la prueba a su configuración inicial.
     *
     * @param preparationData Los datos del paciente y la configuración basal para la prueba.
     */
    fun initializeTest(preparationData: TestPreparationData) {
        // Si la prueba ya está corriendo y solo es una recomposición (ej. rotación),
        // no queremos resetear todo. Solo asegurarnos que los datos del paciente están y preparationDataLoaded es true.
        if (_uiState.value.isTestRunning) {
            Log.d("ViewModel", "initializeTest llamado pero el test en ya en ejecución. Actualizando datos de paciente y asegurando datos cargados.")
            _uiState.update {
                it.copy(
                    patientId = preparationData.patientId,
                    patientFullName = preparationData.patientFullName,
                    preparationDataLoaded = true
                )
            }
            return
        }

        Log.d("TestExecutionVM", "Inicializando prueba con datos para: ${preparationData.patientFullName}")
        // Cancelar actividades de prueba en curso (timers, etc.), pero no el procesamiento de datos en vivo.
        cancelTestInProgressActivities(restartLiveDataProcessing = false)
        currentTestPreparationData = preparationData // Guardar los datos de preparación
        testStateHolder.resetRecoveryState() // Limpiar datos de recuperación de pruebas anteriores

        // NUEVO: Guardar la longitud de zancada
        patientStrideLength = preparationData.strideLengthMeters
        // Resetear los pasos iniciales
        initialStepsAtTestStart = null

        // Obtener el estado actual de los sensores y Bluetooth para la UI inicial
        val currentOximeterData = bluetoothService.oximeterDeviceData.value
        val currentOximeterStatus = bluetoothService.oximeterConnectionStatus.value
        val currentAccelerometerStatus = bluetoothService.wearableConnectionStatus.value
        val isBtEnabled = bluetoothService.isBluetoothEnabled()

        // Determinar el estado visual inicial del icono de Bluetooth
        val (initialOximeterIcon, initialOximeterMsg) = determineOximeterVisualStatus(
            connectionStatus = currentOximeterStatus,
            deviceData = currentOximeterData,
            isBluetoothAdapterEnabled = isBtEnabled
        )

        // Determinar el estado visual inicial del ACELERÓMETRO
        val (initialAccelerometerIcon, initialAccelerometerMsg) = determineAccelerometerVisualStatus(
            connectionStatus = currentAccelerometerStatus,
            isBluetoothAdapterEnabled = isBtEnabled
        )
        val isAccelerometerCurrentlyConnected =
            currentAccelerometerStatus.isConsideredConnectedOrSubscribed()

        // Determinar si el dedo está presente en el sensor en este momento
        val isFingerPresentNow = !(currentOximeterData.noFingerDetected == true ||
                currentOximeterData.signalStrength == NO_FINGER_OR_RECALIBRATING_SIGNAL ||
                currentOximeterData.spo2 == null || currentOximeterData.spo2 <= 0 ||
                currentOximeterData.heartRate == null || currentOximeterData.heartRate <= 0)

        // Usar los datos basales si están disponibles y son válidos, sino los del sensor
        val initialSpo2FromSensor = if (isFingerPresentNow) currentOximeterData.spo2 else null
        val initialHrFromSensor = if (isFingerPresentNow) currentOximeterData.heartRate else null

        val displaySpo2 = preparationData.basalSpo2.takeIf { it in 1..100 } ?: initialSpo2FromSensor
        val displayHr = preparationData.basalHeartRate.takeIf { it > 0 } ?: initialHrFromSensor

        // Limpiar datos de tendencia para la nueva prueba/configuración
        spo2ValuesForTrendCalculation.clear()
        hrValuesForTrendCalculation.clear()
        spo2ReadingsSinceLastTrendCalc = 0
        hrReadingsSinceLastTrendCalc = 0

        // Restablecer completamente el TestExecutionUiState
        _uiState.value = TestExecutionUiState(
            patientId = preparationData.patientId,
            patientFullName = preparationData.patientFullName,
            patientStrideLengthMeters = patientStrideLength.toFloat(),
            currentTimeMillis = 0L,
            currentTimeFormatted = formatTimeDisplay(0L),
            distanceMeters = 0f,
            accumulatedDistanceBeforeLastReconnect = 0f,
            lastKnownTotalStepsBeforeReconnect = null,

            // Estado inicial de los sensores
            currentSpo2 = displaySpo2,
            currentHeartRate = displayHr,
            isSensorFingerPresent = isFingerPresentNow,
            currentSignalStrength = currentOximeterData.signalStrength,
            spo2StatusColor = displaySpo2?.let { determineStatusColorSpo2(it) }
                ?: StatusColor.UNKNOWN,
            heartRateStatusColor = displayHr?.let { determineStatusColorHr(it) }
                ?: StatusColor.UNKNOWN,
            spo2Trend = Trend.STABLE, // Tendencia inicial
            heartRateTrend = Trend.STABLE,

            // Estado inicial de Bluetooth - Pulsioxímetro
            oximeterBluetoothIconStatus = initialOximeterIcon,
            oximeterBluetoothStatusMessage = initialOximeterMsg,
            isAttemptingOximeterForceReconnect = false,

            // NUEVO: Estado inicial de Bluetooth - Acelerómetro
            accelerometerBluetoothIconStatus = initialAccelerometerIcon,
            accelerometerBluetoothStatusMessage = initialAccelerometerMsg,
            isAccelerometerConnected = isAccelerometerCurrentlyConnected,
            isAttemptingAccelerometerForceReconnect = false,

            // Reiniciar datos acumulados de la prueba
            spo2DataPoints = emptyList(),
            heartRateDataPoints = emptyList(),
            stopRecords = emptyList(),
            stopsCount = 0,
            minSpo2Record = null,
            minHeartRateRecord = null,
            maxHeartRateRecord = null,
            minuteMarkerData = emptyList(),

            // Reiniciar control de estado de la prueba
            mainButtonAction = MainButtonAction.START,
            isConfigPhase = true,
            isTestRunning = false,
            isTestFinished = false,
            preparationDataLoaded = true, // Marcar que los datos de preparación están listos
            canNavigateToResults = false,


            // Reiniciar estado de diálogos
            showExitConfirmationDialog = false,
            showStopConfirmationDialog = false,
            stopCountdownSeconds = STOP_CONFIRMATION_COUNTDOWN_SECONDS,
            showMainActionConfirmationDialog = false,
            mainActionConfirmationMessage = "",
            showNavigateToResultsConfirmationDialog = false,
            testFinishedInfoMessage = null,
            showDeleteLastStopConfirmationDialog = false,

            // Reiniciar mensajes y datos de navegación
            userMessage = null,
            testSummaryDataForNavigation = null
        )
        // (Re)iniciar el bucle de procesamiento de datos en vivo (tendencias, etc.)
        // Es importante hacerlo después de que _uiState se haya reinicializado.
        startLiveDataProcessingLoop()
        Log.d(
            "TestExecutionVM",
            "ViewModel inicializado. UI State: ${_uiState.value}. Paso: $patientStrideLength m."
        )
    }

    /**
     * Inicia la ejecución de la prueba 6MWT.
     * Verifica las precondiciones (datos de preparación, conexión Bluetooth, dedo en sensor).
     * Actualiza el estado de la UI para reflejar que la prueba ha comenzado.
     * Inicia el temporizador y la recolección de datos.
     */
    private fun startTestExecution() {
        Log.i("TestExecutionVM", "startTestExecution llamada.")
        if (_uiState.value.isTestRunning) {
            Log.w("TestExecutionVM", "Intento de iniciar una prueba ya en curso.")
            return
        }
        if (!_uiState.value.preparationDataLoaded || currentTestPreparationData == null) {
            _uiState.update { it.copy(userMessage = "Datos de preparación no cargados.") }
            Log.e("TestExecutionVM", "Intento de iniciar prueba sin datos de preparación.")
            return
        }

        // Verificar el estado de la conexión Bluetooth del PULSIOXÍMETRO
        val currentOximeterStatus = bluetoothService.oximeterConnectionStatus.value
        if (currentOximeterStatus != BleConnectionStatus.SUBSCRIBED) {
            val message = when (currentOximeterStatus) {
                BleConnectionStatus.DISCONNECTED_ERROR,
                BleConnectionStatus.DISCONNECTED_BY_USER -> "Pulsioxímetro desconectado."

                BleConnectionStatus.ERROR_BLUETOOTH_DISABLED -> "Bluetooth está desactivado."
                BleConnectionStatus.ERROR_PERMISSIONS -> "Faltan permisos de Bluetooth."
                BleConnectionStatus.SCANNING, BleConnectionStatus.CONNECTING,
                BleConnectionStatus.RECONNECTING -> "Esperando conexión con el pulsioxímetro (${currentOximeterStatus.name})..."

                BleConnectionStatus.CONNECTED -> "Pulsioxímetro conectado, esperando datos..."
                else -> "Pulsioxímetro no está listo (${currentOximeterStatus.name}). Verifique la conexión."
            }
            _uiState.update { it.copy(userMessage = "No se puede iniciar: $message") }
            clearUserMessageAfterDelay(4000)
            return
        }

        // Verificar que el dedo esté en el sensor, usando el estado más actualizado de la UI
        if (!_uiState.value.isSensorFingerPresent) {
            _uiState.update { it.copy(userMessage = "Sensor pulsioxímetro: sin dedo o datos no válidos.") }
            clearUserMessageAfterDelay(3500)
            return
        }
        // Tomar los valores iniciales de SpO2 y FC del _uiState, que ya deberían estar
        // actualizados por el bucle de procesamiento de datos en vivo.
        val initialSpo2ForTest = _uiState.value.currentSpo2
        val initialHrForTest = _uiState.value.currentHeartRate

        if (initialSpo2ForTest == null || initialSpo2ForTest <= 0 || initialHrForTest == null || initialHrForTest <= 0) {
            _uiState.update { it.copy(userMessage = "Sensor pulsioxímetro: Datos no válidos. Verifique el sensor.") }
            clearUserMessageAfterDelay(3500)
            return
        }

        // NUEVO: Verificar conexión del ACELERÓMETRO antes de iniciar (Opcional pero recomendado)
        // Puedes decidir si es un bloqueador o solo una advertencia
        if (!_uiState.value.isAccelerometerConnected) {
            _uiState.update { it.copy(userMessage = "No se puede iniciar: Acelerómetro no conectado.") }
            clearUserMessageAfterDelay()
            return
        }

        // --- CAPTURAR PASOS INICIALES ---
        // Hacerlo ANTES del _uiState.update que cambia isTestRunning
        val currentWearableDataOnStart = bluetoothService.wearableDeviceData.value // Captura una vez
        initialStepsAtTestStart = currentWearableDataOnStart?.totalSteps
        var accelerometerWarning: String? = null
        if (initialStepsAtTestStart == null && _uiState.value.isAccelerometerConnected) {
            Log.w("TestExecutionVM", "Pasos iniciales del acelerómetro son NULL al iniciar la prueba, aunque esté conectado.")
            accelerometerWarning = "Acelerómetro sin datos de pasos. Distancia podría no medirse inicialmente."
        } else if (initialStepsAtTestStart != null) {
            Log.i("TestExecutionVM", "Pasos iniciales del acelerómetro al inicio de la prueba: $initialStepsAtTestStart")
        }
        // --- FIN CAPTURA PASOS INICIALES ---

        // Registrar el tiempo de inicio real de la prueba
        testActualStartTimeMillis = System.currentTimeMillis()

        // Limpiar listas de cálculo de tendencia antes de iniciar
        spo2ValuesForTrendCalculation.clear()
        hrValuesForTrendCalculation.clear()
        spo2ReadingsSinceLastTrendCalc = 0
        hrReadingsSinceLastTrendCalc = 0

        // Actualizar el estado de la UI para el inicio de la prueba
        _uiState.update {
            it.copy(
                isConfigPhase = false,
                isTestRunning = true,
                isTestFinished = false,
                mainButtonAction = MainButtonAction.RESTART_DURING_TEST, // El botón ahora será para reiniciar
                currentTimeMillis = 0L,
                currentTimeFormatted = formatTimeDisplay(0L),
                distanceMeters = 0.0f,
                accumulatedDistanceBeforeLastReconnect = 0f,
                lastKnownTotalStepsBeforeReconnect = initialStepsAtTestStart,
                // Añadir el primer punto de dato si los valores iniciales son válidos
                spo2DataPoints = if (initialSpo2ForTest != null && initialSpo2ForTest > 0) listOf(
                    DataPoint(0L, initialSpo2ForTest.toFloat(), 0f)
                ) else emptyList(),
                heartRateDataPoints = if (initialHrForTest != null && initialHrForTest > 0) listOf(
                    DataPoint(0L, initialHrForTest.toFloat(), 0f)
                ) else emptyList(),
                minSpo2Record = null, // Reiniciar récords
                minHeartRateRecord = null,
                maxHeartRateRecord = null,
                stopRecords = emptyList(),
                stopsCount = 0,
                userMessage = accelerometerWarning ?: "Prueba iniciada.",
                testSummaryDataForNavigation = null,
                showMainActionConfirmationDialog = false,
                testFinishedInfoMessage = null,
                canNavigateToResults = false,
                minuteMarkerData = emptyList() // Reiniciar datos por minuto
            )
        }
        // Iniciar el temporizador y la recolección de datos por segundo
        startTimerAndDataCollection()
        clearUserMessageAfterDelay() // Limpiar el mensaje "Prueba iniciada"
        Log.i(
            "TestExecutionVM",
            "Prueba iniciada con SpO2: $initialSpo2ForTest, HR: $initialHrForTest. Tiempo Epoch: $testActualStartTimeMillis"
        )
    }

    // --- Lógica del Botón Principal (Start/Restart/Reinitialize) ---
    /**
     * Se llama cuando se hace clic en el botón principal de la pantalla.
     * La acción depende del estado actual de la prueba (mainButtonAction).
     */
    fun onMainButtonClicked() {
        when (_uiState.value.mainButtonAction) {
            MainButtonAction.START -> startTestExecution() // Iniciar la prueba
            MainButtonAction.RESTART_DURING_TEST -> requestRestartTestConfirmation() // Pedir confirmación para reiniciar
            MainButtonAction.REINITIALIZE_AFTER_TEST -> requestReinitializeTestConfirmation() // Pedir confirmación para volver a config.
        }
    }

    /**
     * Muestra el diálogo de confirmación para reiniciar la prueba mientras está en curso.
     */
    private fun requestRestartTestConfirmation() { // Solo si la prueba está corriendo
        if (!_uiState.value.isTestRunning) return
        _uiState.update {
            it.copy(
                showMainActionConfirmationDialog = true,
                mainActionConfirmationMessage = RESTART_TEST_CONFIRMATION_MESSAGE
            )
        }
    }

    /**
     * Confirma el reinicio de la prueba.
     * Cancela la prueba actual y vuelve al estado de configuración inicial,
     * manteniendo los datos del paciente.
     */
    fun confirmRestartTestAndReturnToConfig() {
        timerJob?.cancel() // Detener temporizador
        recoveryDataCaptureJob?.cancel() // Detener captura de datos de recuperación si estaba activa

        // Limpiar datos de tendencia
        spo2ValuesForTrendCalculation.clear()
        hrValuesForTrendCalculation.clear()
        spo2ReadingsSinceLastTrendCalc = 0
        hrReadingsSinceLastTrendCalc = 0

        initialStepsAtTestStart = null

        // Restablecer el estado de la UI a la fase de configuración
        _uiState.update {
            it.copy(
                isConfigPhase = true,
                isTestRunning = false,
                isTestFinished = false,
                mainButtonAction = MainButtonAction.START, // Botón principal vuelve a ser "START"
                currentTimeMillis = 0L,
                currentTimeFormatted = formatTimeDisplay(0L),

                distanceMeters = 0f,
                accumulatedDistanceBeforeLastReconnect = 0f,
                lastKnownTotalStepsBeforeReconnect = null,
                spo2Trend = Trend.STABLE, // Resetear tendencias
                heartRateTrend = Trend.STABLE,
                spo2DataPoints = emptyList(),
                heartRateDataPoints = emptyList(), // Limpiar datos de la prueba
                stopRecords = emptyList(),
                stopsCount = 0,
                minSpo2Record = null,
                minHeartRateRecord = null,
                maxHeartRateRecord = null,
                showMainActionConfirmationDialog = false, // Cerrar diálogo
                userMessage = "Prueba reiniciada. Listo para comenzar.",
                testSummaryDataForNavigation = null,
                testFinishedInfoMessage = null,
                canNavigateToResults = false,
                minuteMarkerData = emptyList()
            )
        }
        clearUserMessageAfterDelay()
    }

    /**
     * Muestra el diálogo de confirmación para reinicializar la pantalla
     * después de que una prueba ha finalizado.
     */
    private fun requestReinitializeTestConfirmation() {
        if (!_uiState.value.isTestFinished) return // Solo si la prueba ha terminado
        _uiState.update {
            it.copy(
                showMainActionConfirmationDialog = true,
                mainActionConfirmationMessage = REINITIALIZE_TEST_CONFIRMATION_MESSAGE
            )
        }
    }

    /**
     * Confirma la reinicialización de la pantalla.
     * Llama a `initializeTest` con los datos del paciente actual para volver
     * al estado de configuración como si se empezara una nueva prueba para ese paciente.
     */
    fun confirmReinitializeTestToConfig() {
        _uiState.update { it.copy(showMainActionConfirmationDialog = false) } // Cerrar diálogo
        currentTestPreparationData?.let { prepData ->
            initializeTest(prepData) // Reinicializar con los mismos datos de paciente
        }
        _uiState.update { it.copy(userMessage = "Pantalla reiniciada. Lista para nueva configuración.") }
        clearUserMessageAfterDelay()
    }

    /**
     * Cierra el diálogo de confirmación de la acción principal (reiniciar/reinicializar).
     */
    fun dismissMainActionConfirmationDialog() {
        _uiState.update {
            it.copy(
                showMainActionConfirmationDialog = false,
                mainActionConfirmationMessage = ""
            )
        }
    }

    /**
     * Finaliza la ejecución de la prueba.
     * Detiene el temporizador y actualiza el estado de la UI.
     * Inicia un temporizador en segundo plano para capturar datos de recuperación post-prueba.
     *
     * @param testCompletedNormally `true` si la prueba completó sus 6 minutos, `false` si fue detenida por el usuario.
     */
    private fun finishTestExecution(testCompletedNormally: Boolean) {
        timerJob?.cancel() // Detener el temporizador principal de la prueba
        stopCountdownJob?.cancel() // Cancelar cualquier cuenta atrás de detención
        val finalMessage =
            if (testCompletedNormally) TEST_COMPLETED_INFO_MESSAGE else "Prueba detenida por el usuario."

        // Iniciar captura de datos de recuperación (60 segundos en segundo plano)
        recoveryDataCaptureJob?.cancel()
        recoveryDataCaptureJob = viewModelScope.launch {
            Log.d("TestExecutionVM", "Temporizador de recuperación de 60s iniciado.")
            delay(60000L) // Esperar 1 minuto

            // Después del minuto, capturar los últimos datos disponibles del sensor
            val recoverySensorData = bluetoothService.oximeterDeviceData.value
            // Validar si los datos de recuperación son significativos (dedo puesto, etc.)
            val isRecoveryFingerPresent = !(recoverySensorData.noFingerDetected == true ||
                    recoverySensorData.signalStrength == NO_FINGER_OR_RECALIBRATING_SIGNAL ||
                    recoverySensorData.spo2 == null || recoverySensorData.spo2 <= 0 ||
                    recoverySensorData.heartRate == null || recoverySensorData.heartRate <= 0)

            val validSpo2 = if (isRecoveryFingerPresent) recoverySensorData.spo2 else null
            val validHr = if (isRecoveryFingerPresent) recoverySensorData.heartRate else null

            // Enviar los datos de recuperación a través de TestStateHolder
            testStateHolder.postRecoveryData(
                RecoveryData(
                    spo2 = validSpo2,
                    hr = validHr,
                    isRecoveryPeriodOver = true, // Indicar que el período de recuperación ha terminado
                    wasDataCapturedDuringPeriod = validSpo2 != null && validHr != null // Si se capturaron datos válidos
                )
            )
            if (validSpo2 != null && validHr != null) {
                Log.d(
                    "TestExecutionVM",
                    "Datos de recuperación emitidos: SpO2=$validSpo2, HR=$validHr."
                )
            } else {
                Log.w(
                    "TestExecutionVM",
                    "No se pudieron capturar datos de recuperación válidos tras 1 min."
                )
            }
        }

        // Actualizar el estado de la UI para reflejar que la prueba ha terminado
        _uiState.update {
            it.copy(
                isTestRunning = false,
                isTestFinished = true,
                mainButtonAction = MainButtonAction.REINITIALIZE_AFTER_TEST, // Botón ahora para reinicializar
                testFinishedInfoMessage = finalMessage, // Mostrar mensaje de finalización
                canNavigateToResults = true, // Permitir navegación a resultados
                showStopConfirmationDialog = false // Asegurar que el diálogo de parada esté cerrado
            )
        }
    }

    /**
     * Cierra el diálogo informativo que aparece al finalizar la prueba.
     */
    fun dismissTestFinishedInfoDialog() {
        _uiState.update { it.copy(testFinishedInfoMessage = null) }
    }

    /**
     * Inicia el temporizador principal de la prueba y la recolección de datos por segundo.
     * Actualiza el tiempo transcurrido, los puntos de datos para gráficos,
     * los valores críticos (SpO2 min, FC max/min) y los snapshots por minuto.
     * Finaliza la prueba automáticamente cuando se alcanza TEST_DURATION_MILLIS.
     */
    private fun startTimerAndDataCollection() {
        timerJob?.cancel() // Cancelar cualquier temporizador anterior
        timerJob = viewModelScope.launch {
            var elapsedMillis = 0L // Tiempo transcurrido desde el inicio de la prueba

            // Variables para rastrear el SpO2 mínimo y FC máximo acumulativos para los snapshots por minuto
            var currentOverallMinSpo2: Int? = null
            var currentOverallMaxHr: Int? = null
            val minuteSnapshots =
                mutableListOf<MinuteDataSnapshot>() // Lista para guardar los snapshots

            // El primer punto de datos ya se añade en startTestExecution()
            // si los datos iniciales del sensor son válidos.

            while (isActive && _uiState.value.isTestRunning && elapsedMillis < TEST_DURATION_MILLIS) {
                delay(1000) // Procesamos cada segundo
                val previousTimeMillis = elapsedMillis
                elapsedMillis += 1000

                _uiState.update { currentState ->
                    // Salir si la prueba ya no está corriendo (p.ej. detenida manualmente)
                    if (!currentState.isTestRunning) return@update currentState

                    // Obtener valores actuales del sensor (ya actualizados por liveDataProcessingLoop)
                    val currentSpo2Val = currentState.currentSpo2
                    val currentHrVal = currentState.currentHeartRate
                    val sensorFingerPresent = currentState.isSensorFingerPresent
                    val currentDistance = currentState.distanceMeters // Distancia actual

                    var newSpo2DataPoints = currentState.spo2DataPoints
                    var newHrDataPoints = currentState.heartRateDataPoints
                    // Mantener los récords globales de la prueba (min/max)
                    var newMinSpo2RecordOverallTest = currentState.minSpo2Record
                    var newMinHrRecordOverallTest = currentState.minHeartRateRecord
                    var newMaxHrRecordOverallTest = currentState.maxHeartRateRecord

                    // --- LÓGICA DE ACTUALIZACIÓN DE DATOS POR SEGUNDO ---
                    if (sensorFingerPresent) { // Solo si el dedo está puesto y los datos son válidos
                        if (currentSpo2Val != null && currentSpo2Val in 1..100) {
                            // Añadir nuevo punto de dato para SpO2
                            newSpo2DataPoints = (currentState.spo2DataPoints + DataPoint(
                                elapsedMillis,
                                currentSpo2Val.toFloat(),
                                currentDistance
                            )).takeLast(360) // Limitar a 6 minutos de datos
                            // Actualizar SpO2 mínimo global de la prueba
                            if (newMinSpo2RecordOverallTest == null || currentSpo2Val < newMinSpo2RecordOverallTest.value) {
                                newMinSpo2RecordOverallTest = CriticalValueRecord(
                                    currentSpo2Val,
                                    elapsedMillis,
                                    currentDistance
                                )
                            }
                            // Actualizar SpO2 mínimo para los snapshots por minuto (acumulativo)
                            if (currentOverallMinSpo2 == null || currentSpo2Val < currentOverallMinSpo2!!) {
                                currentOverallMinSpo2 = currentSpo2Val
                            }
                        }
                        if (currentHrVal != null && currentHrVal > 0) {
                            // Añadir nuevo punto de dato para FC
                            newHrDataPoints = (currentState.heartRateDataPoints + DataPoint(
                                elapsedMillis,
                                currentHrVal.toFloat(),
                                currentDistance
                            )).takeLast(360)
                            // Actualizar HR mínimo/máximo global de la prueba
                            if (newMinHrRecordOverallTest == null || currentHrVal < newMinHrRecordOverallTest.value) {
                                newMinHrRecordOverallTest = CriticalValueRecord(
                                    currentHrVal,
                                    elapsedMillis,
                                    currentDistance
                                )
                            }
                            if (newMaxHrRecordOverallTest == null || currentHrVal > newMaxHrRecordOverallTest.value) {
                                newMaxHrRecordOverallTest = CriticalValueRecord(
                                    currentHrVal,
                                    elapsedMillis,
                                    currentDistance
                                )
                            }
                            // Actualizar FC máximo para los snapshots por minuto (acumulativo)
                            if (currentOverallMaxHr == null || currentHrVal > currentOverallMaxHr!!) {
                                currentOverallMaxHr = currentHrVal
                            }
                        }
                    } else {
                        // Si no hay dedo, no se añaden nuevos DataPoints.
                        Log.v(
                            "TimerDataCollection",
                            "No hay dedo o datos inválidos en el segundo $elapsedMillis. No se añaden DataPoints."
                        )
                    }

                    // --- LÓGICA DE SNAPSHOT POR MINUTO ---
                    val currentMinute = (elapsedMillis / 60000L).toInt() // Minuto actual (1-6)
                    val previousMinute =
                        (previousTimeMillis / 60000L).toInt() // Minuto en el tick anterior

                    // Si hemos cruzado un límite de minuto (y no es el minuto 0)
                    if (currentMinute > previousMinute && currentMinute > 0 && currentMinute <= 6) {
                        // Y si aún no hemos guardado un snapshot para ESTE minuto
                        if (minuteSnapshots.none { it.minuteMark == currentMinute }) {
                            val snapshot = MinuteDataSnapshot(
                                minuteMark = currentMinute,
                                minSpo2Overall = currentOverallMinSpo2, // El SpO2 mínimo HASTA ESTE MINUTO
                                maxHrOverall = currentOverallMaxHr,     // La FC máxima HASTA ESTE MINUTO
                                distanceAtMinuteEnd = currentDistance   // Distancia al final de este minuto
                            )
                            minuteSnapshots.add(snapshot)
                            Log.d(
                                "TestExecutionVM",
                                "Snapshot Minuto $currentMinute: SpO2Min=${snapshot.minSpo2Overall}, HrMax=${snapshot.maxHrOverall}, Dist=${snapshot.distanceAtMinuteEnd}"
                            )
                        }
                    }

                    // Actualizar el estado de la UI con los nuevos datos
                    currentState.copy(
                        currentTimeMillis = elapsedMillis,
                        currentTimeFormatted = formatTimeDisplay(elapsedMillis),
                        spo2DataPoints = newSpo2DataPoints,
                        heartRateDataPoints = newHrDataPoints,
                        minSpo2Record = newMinSpo2RecordOverallTest, // Para el resumen final
                        minHeartRateRecord = newMinHrRecordOverallTest, // Para el resumen final
                        maxHeartRateRecord = newMaxHrRecordOverallTest, // Para el resumen final
                        minuteMarkerData = minuteSnapshots.toList() // Actualizar con la lista de snapshots
                    )
                }

                // Verificar si la prueba ha alcanzado la duración máxima
                if (elapsedMillis >= TEST_DURATION_MILLIS) {
                    // Asegurar que el último snapshot (minuto 6) se capture si la prueba termina exactamente en 6:00
                    val finalMinute = (elapsedMillis / 60000L).toInt()
                    if (finalMinute == 6 && minuteSnapshots.none { it.minuteMark == 6 }) {
                        val snapshot = MinuteDataSnapshot(
                            minuteMark = 6,
                            minSpo2Overall = currentOverallMinSpo2,
                            maxHrOverall = currentOverallMaxHr,
                            distanceAtMinuteEnd = _uiState.value.distanceMeters // Distancia final
                        )
                        minuteSnapshots.add(snapshot)
                        _uiState.update { it.copy(minuteMarkerData = minuteSnapshots.toList()) }
                        Log.d(
                            "TestExecutionVM",
                            "Snapshot Minuto 6 (final): SpO2Min=${snapshot.minSpo2Overall}, HrMax=${snapshot.maxHrOverall}, Dist=${snapshot.distanceAtMinuteEnd}"
                        )
                    }
                    finishTestExecution(testCompletedNormally = true) // Finalizar la prueba como completada
                }
            }
        }
    }

    /**
     * Inicia el proceso para detener la prueba manualmente.
     * Muestra un diálogo de confirmación con una cuenta atrás.
     * Si la cuenta atrás llega a cero, la prueba se detiene automáticamente.
     */
    fun onStopTestInitiated() {
        if (!_uiState.value.isTestRunning) return // Solo si la prueba está corriendo
        _uiState.update {
            it.copy(
                showStopConfirmationDialog = true, // Mostrar diálogo
                stopCountdownSeconds = STOP_CONFIRMATION_COUNTDOWN_SECONDS // Iniciar cuenta atrás
            )
        }

        stopCountdownJob?.cancel() // Cancelar cuenta atrás anterior si existiera
        stopCountdownJob = viewModelScope.launch {
            for (i in STOP_CONFIRMATION_COUNTDOWN_SECONDS downTo 1) {
                // Salir si el diálogo se cierra o la prueba ya no corre
                if (!_uiState.value.showStopConfirmationDialog || !_uiState.value.isTestRunning) {
                    _uiState.update { it.copy(stopCountdownSeconds = STOP_CONFIRMATION_COUNTDOWN_SECONDS) } // Resetear
                    return@launch // Salir si el usuario canceló o la prueba ya no corre
                }
                _uiState.update { it.copy(stopCountdownSeconds = i - 1) } // Actualizar segundos restantes
                delay(1000)
            }
            // Si la cuenta atrás llega a 0 y el diálogo sigue activo y la prueba corriendo
            if (_uiState.value.showStopConfirmationDialog && _uiState.value.stopCountdownSeconds <= 0 && _uiState.value.isTestRunning) {
                confirmStopTest() // Confirmar parada automáticamente
            }
        }
    }

    /**
     * Cancela el proceso de detención de la prueba y cierra el diálogo de confirmación.
     */
    fun cancelStopTest() {
        stopCountdownJob?.cancel() // Detener la cuenta atrás
        _uiState.update {
            it.copy(
                showStopConfirmationDialog = false, // Ocultar diálogo
                stopCountdownSeconds = STOP_CONFIRMATION_COUNTDOWN_SECONDS // Resetear segundos
            )
        }
    }

    /**
     * Confirma la detención de la prueba.
     * Llama a `finishTestExecution` marcando que no se completó normalmente.
     */
    fun confirmStopTest() {
        stopCountdownJob?.cancel() // Detener la cuenta atrás si se confirma manualmente
        if (_uiState.value.isTestRunning) {
            finishTestExecution(testCompletedNormally = false) // Marcar como detenida por el usuario
        }
        // Asegurar que el diálogo se cierre (aunque finishTestExecution también lo hace)
        _uiState.update { it.copy(showStopConfirmationDialog = false) }
    }

    // --- Navegación a Resultados ---
    /**
     * Se llama cuando el usuario hace clic en el botón "Continuar a Resultados".
     * Verifica si la prueba ha finalizado y luego llama a `requestNavigateToResultsConfirmation`.
     */
    fun onContinueToResultsClicked() {
        if (!_uiState.value.isTestFinished) {
            _uiState.update { it.copy(userMessage = "La prueba debe finalizar para ver los resultados.") }
            clearUserMessageAfterDelay()
            return
        }
        requestNavigateToResultsConfirmation() // Mostrar diálogo de confirmación
    }

    /**
     * Construye el resumen de la prueba y muestra el diálogo de confirmación
     * para navegar a la pantalla de resultados.
     */
    fun requestNavigateToResultsConfirmation() {
        if (!_uiState.value.isTestFinished) {
            _uiState.update { it.copy(userMessage = "La prueba debe finalizar antes de ver los resultados.") }
            clearUserMessageAfterDelay()
            return
        }

        val summary = buildTestExecutionSummary() // Construir el objeto de resumen
        if (summary == null) {
            _uiState.update { it.copy(userMessage = "Error al generar el resumen de la prueba.") }
            clearUserMessageAfterDelay()
            return
        }
        // Actualizar UI para mostrar diálogo y guardar resumen para navegación
        _uiState.update {
            it.copy(
                testSummaryDataForNavigation = summary,
                showNavigateToResultsConfirmationDialog = true,
                testFinishedInfoMessage = null // Ocultar diálogo de "prueba finalizada" si estaba visible
            )
        }
    }

    /**
     * Cierra el diálogo de confirmación para navegar a resultados.
     */
    fun dismissNavigateToResultsConfirmation() {
        _uiState.update {
            it.copy(
                showNavigateToResultsConfirmationDialog = false
            )
        }
    }

    /**
     * Se llama cuando el usuario confirma la navegación a la pantalla de resultados.
     * El `testSummaryDataForNavigation` ya está en el `_uiState` y será observado
     * por la Composable para disparar la navegación.
     * Aquí solo se cierra el diálogo.
     */
    fun confirmNavigateToResults() {
        if (_uiState.value.testSummaryDataForNavigation == null) {
            // Esto no debería ocurrir si el diálogo se mostró, pero por seguridad
            _uiState.update {
                it.copy(
                    userMessage = "No hay datos de resumen para navegar.",
                    showNavigateToResultsConfirmationDialog = false
                )
            }
            clearUserMessageAfterDelay()
            return
        }
        // Cerrar el diálogo. La navegación se gestiona en la UI observando testSummaryDataForNavigation.
        _uiState.update {
            it.copy(
                showNavigateToResultsConfirmationDialog = false,
                testFinishedInfoMessage = null // Asegurar que otros diálogos informativos estén cerrados
            )
        }
    }

    /**
     * Se llama desde la UI después de que la navegación a la pantalla de resultados
     * se haya completado, para limpiar el `testSummaryDataForNavigation` y evitar
     * una nueva navegación si la pantalla se recompone.
     */
    fun onNavigationToResultsCompleted() {
        _uiState.update { it.copy(testSummaryDataForNavigation = null) }
    }

    // --- Gestión de Paradas (Stops) ---
    /**
     * Añade una parada (StopRecord) a la lista de paradas de la prueba.
     * Solo se pueden añadir paradas si la prueba está en curso y hay datos válidos del sensor.
     */
    fun onAddStop() {
        if (!_uiState.value.isTestRunning) {
            _uiState.update { it.copy(userMessage = "Solo se pueden añadir paradas durante la prueba.") }
            clearUserMessageAfterDelay()
            return
        }

        val currentState = _uiState.value
        val currentTimeMs = currentState.currentTimeMillis
        val spo2AtStop = currentState.currentSpo2
        val hrAtStop = currentState.currentHeartRate

        // Verificar que haya datos válidos del sensor para registrar la parada
        if (currentState.isSensorFingerPresent && spo2AtStop != null && hrAtStop != null) {
            val newStop = StopRecord(
                stopTimeMillis = currentTimeMs,
                spo2AtStopTime = spo2AtStop,
                heartRateAtStopTime = hrAtStop,
                distanceAtStopTime = currentState.distanceMeters, // Guardar la distancia en la parada
                stopTimeFormatted = formatTimeDisplay(currentTimeMs) // Tiempo formateado
            )
            _uiState.update {
                it.copy(
                    stopRecords = it.stopRecords + newStop, // Añadir a la lista
                    stopsCount = it.stopsCount + 1 // Incrementar contador
                )
            }
        } else {
            _uiState.update { it.copy(userMessage = "No se puede registrar parada: datos de sensor no válidos.") }
            clearUserMessageAfterDelay()
        }
    }

    // --- Gestión de Vueltas y Distancia ---


    /**
     * Muestra el diálogo de confirmación para eliminar la última parada registrada.
     * Solo si hay paradas y la prueba está en curso o finalizada.
     */
    fun requestDeleteLastStopConfirmation() {
        if (_uiState.value.stopRecords.isNotEmpty() && (_uiState.value.isTestRunning || _uiState.value.isTestFinished)) {
            _uiState.update { it.copy(showDeleteLastStopConfirmationDialog = true) }
        } else if (_uiState.value.stopRecords.isEmpty()) {
            _uiState.update { it.copy(userMessage = "No hay paradas para eliminar.") }
            clearUserMessageAfterDelay()
        } else {
            _uiState.update { it.copy(userMessage = "Las paradas solo se pueden gestionar durante o después de la prueba.") }
            clearUserMessageAfterDelay()
        }
    }

    /**
     * Cierra el diálogo de confirmación para eliminar la última parada.
     */
    fun dismissDeleteLastStopConfirmation() {
        _uiState.update { it.copy(showDeleteLastStopConfirmationDialog = false) }
    }

    /**
     * Confirma la eliminación de la última parada registrada.
     * Actualiza la lista de paradas y el contador.
     */
    fun confirmDeleteLastStop() {
        _uiState.update { it.copy(showDeleteLastStopConfirmationDialog = false) } // Cerrar diálogo primero
        if (_uiState.value.stopRecords.isNotEmpty()) {
            _uiState.update {
                val updatedRecords = it.stopRecords.dropLast(1)
                it.copy(
                    stopsCount = updatedRecords.size,
                    stopRecords = updatedRecords
                )
            }
        }
    }

    /**
     * Limpia el mensaje de usuario mostrado en la UI después de un retraso.
     * @param delayMillis El tiempo en milisegundos antes de limpiar el mensaje.
     */
    private fun clearUserMessageAfterDelay(delayMillis: Long = 3000) {
        userMessageClearJob?.cancel()
        userMessageClearJob = viewModelScope.launch {
            delay(delayMillis)
            _uiState.update { it.copy(userMessage = null) }
        }
    }

    // --- Navegación y Salida de Pantalla ---
    /**
     * Muestra el diálogo de confirmación para salir de la pantalla de prueba.
     */
    fun requestExitConfirmation() {
        _uiState.update { it.copy(showExitConfirmationDialog = true) }
    }

    /**
     * Cierra el diálogo de confirmación para salir de la pantalla.
     */
    fun dismissExitConfirmation() {
        _uiState.update { it.copy(showExitConfirmationDialog = false) }
    }

    /**
     * Confirma la salida de la pantalla de prueba.
     * Cancela todas las actividades relacionadas con la prueba.
     */
    fun confirmExitTest() {
        _uiState.update { it.copy(showExitConfirmationDialog = false) } // Cerrar el diálogo
        // Cancelar todas las actividades de prueba. No reiniciar el procesamiento de datos en vivo
        // ya que estamos saliendo de la pantalla.
        cancelTestInProgressActivities(restartLiveDataProcessing = false)
        // La navegación se gestionará por el llamador (la Composable observando algún estado o evento)
    }

    // --- Funciones de Formato y Ayuda ---
    /**
     * Determina el color de estado ([StatusColor]) para un valor de SpO2.
     * @param spo2 El valor de SpO2.
     * @return El [StatusColor] correspondiente.
     */
    internal fun determineStatusColorSpo2(spo2: Int): StatusColor {
        return when {
            spo2 <= 0 -> StatusColor.UNKNOWN
            // Usar los umbrales cargados desde SettingsRepository
            spo2 <= userSpo2CriticalThreshold -> StatusColor.CRITICAL
            spo2 < userSpo2WarningThreshold -> StatusColor.WARNING // Si es < umbral de warning (y no crítico)
            else -> StatusColor.NORMAL
        }
    }

    /**
     * Determina el color de estado ([StatusColor]) para un valor de frecuencia cardíaca (HR).
     * @param hr El valor de HR.
     * @return El [StatusColor] correspondiente.
     */
    internal fun determineStatusColorHr(hr: Int): StatusColor {
        return when {
            hr <= 0 -> StatusColor.UNKNOWN // Valor no válido o no disponible
            // Usar los umbrales cargados desde SettingsRepository
            hr < userHrCriticalLowThreshold || hr > userHrCriticalHighThreshold -> StatusColor.CRITICAL
            hr < userHrWarningLowThreshold || hr > userHrWarningHighThreshold -> StatusColor.WARNING
            else -> StatusColor.NORMAL
        }
    }

    /**
     * Formatea un tiempo en milisegundos al formato "mm:ss".
     * @param millis El tiempo en milisegundos.
     * @return El tiempo formateado como String.
     */
    fun formatTimeDisplay(millis: Long): String {
        return timeFormatter.format(millis.coerceAtLeast(0L))
    }

    /**
     * Construye el objeto [TestExecutionSummaryData] con los datos de la prueba completada.
     * Este objeto se usa para pasar la información a la pantalla de resultados.
     * @return El [TestExecutionSummaryData] o `null` si falta información esencial.
     */
    fun buildTestExecutionSummary(): TestExecutionSummaryData? {
        val prepData = currentTestPreparationData ?: run {
            Log.e("SummaryBuilder", "No hay TestPreparationData para construir el resumen.")
            return null
        }
        val finalUiState = _uiState.value

        val actualDuration = if (finalUiState.isTestFinished && testActualStartTimeMillis > 0) {
            finalUiState.currentTimeMillis // Este es el tiempo final de la prueba
        } else if (testActualStartTimeMillis > 0) {
            // Si la prueba fue interrumpida y no "terminó" formalmente via finishTestExecution
            System.currentTimeMillis() - testActualStartTimeMillis
        } else {
            0L
        }

        // El tiempo de inicio real de la prueba
        val startTime = if (testActualStartTimeMillis > 0L) {
            testActualStartTimeMillis
        } else {
            Log.w("SummaryBuilder", "testActualStartTimeMillis es 0, el tiempo de inicio puede no ser preciso.")
            // Estimar si la prueba corrió pero el tiempo de inicio no se registró (debería ser raro)
            if (actualDuration > 0L) System.currentTimeMillis() - actualDuration else 0L
        }

        Log.i("TestExecutionVM", "Construyendo resumen: Duración actual ${finalUiState.currentTimeMillis}, Distancia final: ${finalUiState.distanceMeters}")

        return TestExecutionSummaryData(
            // --- Campos de TestPreparationData ---
            patientId = prepData.patientId,
            patientFullName = prepData.patientFullName,
            patientSex = prepData.patientSex,
            patientAge = prepData.patientAge,
            patientHeightCm = prepData.patientHeightCm,
            patientWeightKg = prepData.patientWeightKg,
            usesInhalers = prepData.usesInhalers,
            usesOxygen = prepData.usesOxygen,
            theoreticalDistance = prepData.theoreticalDistance,
            basalSpo2 = prepData.basalSpo2,
            basalHeartRate = prepData.basalHeartRate,
            basalBloodPressureSystolic = prepData.basalBloodPressureSystolic,
            basalBloodPressureDiastolic = prepData.basalBloodPressureDiastolic,
            basalRespiratoryRate = prepData.basalRespiratoryRate,
            basalDyspneaBorg = prepData.basalDyspneaBorg,
            basalLegPainBorg = prepData.basalLegPainBorg,
            oximeterDevicePlacementLocation = prepData.devicePlacementLocation,
            accelerometerPlacementLocation = prepData.accelerometerPlacementLocation,
            isFirstTestForPatient = prepData.isFirstTestForPatient,
            // --- Campos específicos de la ejecución ---
            testActualStartTimeMillis = startTime,
            actualTestDurationMillis = actualDuration,
            distanceMetersFinal = finalUiState.distanceMeters,
            strideLengthUsedForTestMeters = patientStrideLength.toFloat(),
            minSpo2Record = finalUiState.minSpo2Record,
            maxHeartRateRecord = finalUiState.maxHeartRateRecord,
            minHeartRateRecord = finalUiState.minHeartRateRecord,
            stopRecords = finalUiState.stopRecords,
            spo2DataPoints = finalUiState.spo2DataPoints,
            heartRateDataPoints = finalUiState.heartRateDataPoints,
            minuteReadings = finalUiState.minuteMarkerData // Añadir los datos por minuto
        )
    }

    /**
     * Cancela jobs relacionados con una prueba en curso (timer, countdowns).
     *
     * @param restartLiveDataProcessing `true` (por defecto) si el bucle de procesamiento de datos en vivo
     * (tendencias, etc.) debe reiniciarse después de cancelar.
     * `false` si el bucle también debe detenerse (ej. al salir de la pantalla).
     */
    private fun cancelTestInProgressActivities(restartLiveDataProcessing: Boolean = true) {
        timerJob?.cancel()
        stopCountdownJob?.cancel()
        recoveryDataCaptureJob?.cancel() // Cancelar captura de datos de recuperación
        oximeterForceReconnectJob?.cancel() // Cancelar job del pulsioxímetro
        accelerometerForceReconnectJob?.cancel() // Cancelar job del acelerómetro

        if (!restartLiveDataProcessing) {
            liveDataProcessingJob?.cancel() // Cancelar solo si no se va a reiniciar
        }

        _uiState.update {
            it.copy(
                showStopConfirmationDialog = false, // Asegurar que diálogos de acción se cierren
                stopCountdownSeconds = STOP_CONFIRMATION_COUNTDOWN_SECONDS,
                isAttemptingOximeterForceReconnect = false, // Resetear flag del pulsioxímetro
                isAttemptingAccelerometerForceReconnect = false // Resetear flag del acelerómetro
            )
        }
        // Si se va a reiniciar el procesamiento de datos en vivo, se haría explícitamente después,
        // típicamente en initializeTest o al iniciar una nueva prueba.
    }

    /**
     * Se llama cuando el ViewModel está a punto de ser destruido.
     * Limpia recursos, como cancelar todos los jobs de coroutines.
     */
    override fun onCleared() {
        super.onCleared()
        // Cancelar todas las actividades y también el procesamiento de datos en vivo.
        cancelTestInProgressActivities(restartLiveDataProcessing = false)
        Log.d("TestExecutionVM", "ViewModel onCleared.")
    }

    /**
     * Limpia el mensaje de usuario inmediatamente.
     */
    fun clearUserMessage() {
        userMessageClearJob?.cancel() // Cancela cualquier job de limpieza de mensaje anterior
        _uiState.update { it.copy(userMessage = null) }
    }

    /**
     * Renombrada: Se llama cuando se hace clic en el icono de estado Bluetooth del PULSIOXÍMETRO.
     * Intenta forzar una reconexión si el dispositivo no está conectado.
     */
    fun onOximeterIconClicked() {
        val currentState = _uiState.value // Capturar el estado actual una vez

        // 1. Prevenir múltiples clics rápidos o si ya está en proceso
        if (currentState.isAttemptingOximeterForceReconnect || oximeterForceReconnectJob?.isActive == true) {
            Log.d("TestExecutionVM", "Oximeter: Intento de reconexión ya en curso.")
            _uiState.update { it.copy(userMessage = "Pulsioxímetro: Reconexión en progreso...") }
            clearUserMessageAfterDelay(1500)
            return
        }

        // 2. Verificar si el Bluetooth del sistema está activado
        if (!bluetoothService.isBluetoothEnabled()) {
            _uiState.update { it.copy(userMessage = "Active Bluetooth en ajustes del sistema.") }
            clearUserMessageAfterDelay(3500)
            // Actualizar el estado visual del icono del oximetro por si acaso
            _uiState.update {
                val (icon, msg) = determineOximeterVisualStatus(
                    connectionStatus = bluetoothService.oximeterConnectionStatus.value, // O oximeterConnectionStatus
                    deviceData = bluetoothService.oximeterDeviceData.value,      // O oximeterDeviceData
                    isBluetoothAdapterEnabled = false // Bluetooth está apagado
                )
                it.copy(oximeterBluetoothIconStatus = icon, oximeterBluetoothStatusMessage = msg)
            }
            return
        }

        // 3. Lógica principal: si el icono del PULSIOXÍMETRO está en ROJO por "Pérdida de conexión" o similar.
        // O si simplemente no está conectado y queremos intentar reconectar.
        val oximeterStatus = bluetoothService.oximeterConnectionStatus.value // O oximeterConnectionStatus
        val canAttemptReconnect = !oximeterStatus.isConsideredConnectedOrSubscribed() &&
                oximeterStatus != BleConnectionStatus.CONNECTING &&
                oximeterStatus != BleConnectionStatus.RECONNECTING
        // Podrías añadir una condición más específica como:
        // currentState.oximeterBluetoothIconStatus == BluetoothIconStatus.RED

        if (canAttemptReconnect) {
            // USA EL NOMBRE CORRECTO DE TU SERVICIO AQUÍ:
            val deviceAddressToReconnect = bluetoothService.lastKnownOximeterAddress.value // Asumo que es para el oximetro
            if (deviceAddressToReconnect == null) {
                _uiState.update { it.copy(userMessage = "Pulsioxímetro: No hay dispositivo previo para reconectar.") }
                clearUserMessageAfterDelay()
                _uiState.update { cs ->
                    val (icon, msg) = determineOximeterVisualStatus(
                        connectionStatus = oximeterStatus,
                        deviceData = bluetoothService.oximeterDeviceData.value, // O oximeterDeviceData
                        isBluetoothAdapterEnabled = bluetoothService.isBluetoothEnabled()
                    )
                    cs.copy(oximeterBluetoothIconStatus = icon, oximeterBluetoothStatusMessage = msg)
                }
                return
            }

            oximeterForceReconnectJob?.cancel()
            oximeterForceReconnectJob = viewModelScope.launch {
                Log.i("TestExecutionVM", "Oximeter: Iniciando reconexión forzada a: $deviceAddressToReconnect")
                _uiState.update {
                    it.copy(
                        isAttemptingOximeterForceReconnect = true,
                        oximeterBluetoothIconStatus = BluetoothIconStatus.CONNECTING,
                        oximeterBluetoothStatusMessage = "Pulsioxímetro: Reconectando..."
                    )
                }

                var operationSuccessful = false
                try {
                    withTimeout(FORCE_RECONNECT_TIMEOUT_SECONDS * 1000L) {
                        // USA LAS FUNCIONES CORRECTAS DE TU SERVICIO PARA EL OXÍMETRO
                        val currentServiceStatus = bluetoothService.oximeterConnectionStatus.value // O oximeterConnectionStatus
                        if (currentServiceStatus.isConsideredConnectedOrSubscribed() ||
                            currentServiceStatus == BleConnectionStatus.CONNECTING ||
                            currentServiceStatus == BleConnectionStatus.CONNECTED) {
                            Log.d("TestExecutionVM", "Oximeter: Desconectando antes de forzar reconexión...")
                            bluetoothService.disconnect(deviceAddressToReconnect) // Asumo que esto desconecta el oximetro
                            delay(700L)
                        }
                        bluetoothService.connect(deviceAddressToReconnect, DeviceCategory.OXIMETER) // Asumo que esto conecta el oximetro

                        val finalStatus = bluetoothService.oximeterConnectionStatus.first { status -> // O oximeterConnectionStatus
                            status == BleConnectionStatus.SUBSCRIBED || status.isErrorStatus() || status == BleConnectionStatus.DISCONNECTED_ERROR
                        }
                        operationSuccessful = (finalStatus == BleConnectionStatus.SUBSCRIBED)
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w("TestExecutionVM", "Oximeter: Reconexión forzada TIMED OUT.")
                    operationSuccessful = false
                    // USA LA FUNCIÓN CORRECTA PARA DESCONECTAR EL OXÍMETRO SI ES NECESARIO
                    if (bluetoothService.oximeterConnectionStatus.value != BleConnectionStatus.DISCONNECTED_ERROR && // O oximeterConnectionStatus
                        bluetoothService.oximeterConnectionStatus.value != BleConnectionStatus.IDLE &&
                        bluetoothService.oximeterConnectionStatus.value != BleConnectionStatus.DISCONNECTED_BY_USER) {
                        bluetoothService.disconnect(deviceAddressToReconnect) // Asumo oximetro
                    }
                } catch (e: Exception) {
                    Log.e("TestExecutionVM", "Oximeter: Error durante reconexión: ${e.message}", e)
                    operationSuccessful = false
                }

                _uiState.update { it.copy(isAttemptingOximeterForceReconnect = false) }

                if (operationSuccessful) {
                    _uiState.update { it.copy(userMessage = "Pulsioxímetro: Conexión restaurada.") }
                    clearUserMessageAfterDelay()
                } else {
                    _uiState.update { it.copy(userMessage = "Pulsioxímetro: No se pudo reconectar.") }
                    clearUserMessageAfterDelay()
                }

                val (finalIcon, finalMsg) = determineOximeterVisualStatus(
                    connectionStatus = bluetoothService.oximeterConnectionStatus.value, // O oximeterConnectionStatus
                    deviceData = bluetoothService.oximeterDeviceData.value,      // O oximeterDeviceData
                    isBluetoothAdapterEnabled = bluetoothService.isBluetoothEnabled()
                )
                _uiState.update { it.copy(oximeterBluetoothIconStatus = finalIcon, oximeterBluetoothStatusMessage = finalMsg) }
            }
        } else {
            // Si no se puede intentar la reconexión (ya conectado, conectando, etc.)
            // Mostrar el estado actual.
            val (currentIcon, currentMsg) = determineOximeterVisualStatus(
                connectionStatus = oximeterStatus,
                deviceData = bluetoothService.oximeterDeviceData.value, // O oximeterDeviceData
                isBluetoothAdapterEnabled = bluetoothService.isBluetoothEnabled()
            )
            _uiState.update { it.copy(
                userMessage = "Pulsioxímetro: ${currentMsg}",
                oximeterBluetoothIconStatus = currentIcon, // Asegurar que el icono también se actualice
                oximeterBluetoothStatusMessage = currentMsg
            )}
            clearUserMessageAfterDelay(3000)
        }
    }

    /**
     * NUEVA: Se llama cuando se hace clic en el icono de estado Bluetooth del ACELERÓMETRO.
     * Intenta forzar una reconexión si el dispositivo no está conectado.
     * Asume que BluetoothService tiene un método para reconectar el wearable (ej. forceReconnectWearable).
     */
    fun onAccelerometerIconClicked() {
        val currentState = _uiState.value // Capturar el estado actual una vez

        // 1. Prevenir múltiples clics rápidos o si ya está en proceso
        if (currentState.isAttemptingAccelerometerForceReconnect || accelerometerForceReconnectJob?.isActive == true) {
            Log.d("TestExecutionVM", "Accelerometer: Intento de reconexión ya en curso.")
            _uiState.update { it.copy(userMessage = "Acelerómetro: Reconexión en progreso...") }
            clearUserMessageAfterDelay(1500)
            return
        }

        // 2. Verificar si el Bluetooth del sistema está activado
        if (!bluetoothService.isBluetoothEnabled()) {
            _uiState.update { it.copy(userMessage = "Active Bluetooth en ajustes del sistema.") }
            clearUserMessageAfterDelay(3500)
            // Actualizar el estado visual del icono del acelerómetro
            _uiState.update {
                val (icon, msg) = determineAccelerometerVisualStatus(
                    connectionStatus = bluetoothService.wearableConnectionStatus.value,
                    isBluetoothAdapterEnabled = false // Bluetooth está apagado
                )
                it.copy(accelerometerBluetoothIconStatus = icon, accelerometerBluetoothStatusMessage = msg)
            }
            return
        }

        // 3. Lógica principal: si el icono del ACELERÓMETRO está en ROJO, o si no está conectado.
        val accelerometerCurrentStatus = bluetoothService.wearableConnectionStatus.value
        val canAttemptReconnect = !accelerometerCurrentStatus.isConsideredConnectedOrSubscribed() &&
                accelerometerCurrentStatus != BleConnectionStatus.CONNECTING &&
                accelerometerCurrentStatus != BleConnectionStatus.RECONNECTING
        // Podrías añadir una condición más específica aquí si es necesario,
        // como verificar currentState.accelerometerBluetoothIconStatus == BluetoothIconStatus.RED

        if (canAttemptReconnect) {
            val deviceAddressToReconnect = bluetoothService.lastKnownWearableAddress.value
            if (deviceAddressToReconnect == null) {
                _uiState.update { it.copy(userMessage = "Acelerómetro: No hay dispositivo previo para reconectar.") }
                clearUserMessageAfterDelay()
                // Reevaluar estado visual
                _uiState.update { cs ->
                    val (icon, msg) = determineAccelerometerVisualStatus(
                        connectionStatus = accelerometerCurrentStatus, // Usar el estado ya obtenido
                        isBluetoothAdapterEnabled = bluetoothService.isBluetoothEnabled()
                    )
                    cs.copy(accelerometerBluetoothIconStatus = icon, accelerometerBluetoothStatusMessage = msg)
                }
                return
            }

            accelerometerForceReconnectJob?.cancel() // Cancelar intento anterior
            accelerometerForceReconnectJob = viewModelScope.launch {
                Log.i("TestExecutionVM", "Accelerometer: Iniciando reconexión forzada a: $deviceAddressToReconnect")
                _uiState.update {
                    it.copy(
                        isAttemptingAccelerometerForceReconnect = true,
                        accelerometerBluetoothIconStatus = BluetoothIconStatus.CONNECTING,
                        accelerometerBluetoothStatusMessage = "Acelerómetro: Reconectando..."
                    )
                }

                var operationSuccessful = false
                try {
                    withTimeout(FORCE_RECONNECT_TIMEOUT_SECONDS * 1000L) {
                        // Paso 1: Desconectar explícitamente si es necesario.
                        // Tu servicio usa disconnect(deviceAddress), así que le pasamos la MAC.
                        val currentServiceStatus = bluetoothService.wearableConnectionStatus.value
                        if (currentServiceStatus.isConsideredConnectedOrSubscribed() ||
                            currentServiceStatus == BleConnectionStatus.CONNECTING ||
                            currentServiceStatus == BleConnectionStatus.CONNECTED) {
                            Log.d("TestExecutionVM", "Accelerometer: Desconectando $deviceAddressToReconnect antes de forzar reconexión...")
                            bluetoothService.disconnect(deviceAddressToReconnect)
                            delay(700L) // Espera para que la desconexión se procese
                        }

                        // Paso 2: Intentar conectar al dispositivo.
                        bluetoothService.connect(deviceAddressToReconnect, DeviceCategory.WEARABLE)

                        // Paso 3: Esperar a que el estado de conexión llegue a SUBSCRIBED o a un estado de error/desconexión.
                        val finalStatus = bluetoothService.wearableConnectionStatus.first { status ->
                            status == BleConnectionStatus.SUBSCRIBED || status.isErrorStatus() || status == BleConnectionStatus.DISCONNECTED_ERROR
                        }
                        operationSuccessful = (finalStatus == BleConnectionStatus.SUBSCRIBED)
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w("TestExecutionVM", "Accelerometer: Reconexión forzada TIMED OUT para $deviceAddressToReconnect.")
                    operationSuccessful = false
                    // Si hubo timeout, desconectar para evitar estados inconsistentes.
                    val statusAfterTimeout = bluetoothService.wearableConnectionStatus.value
                    if (statusAfterTimeout != BleConnectionStatus.DISCONNECTED_ERROR &&
                        statusAfterTimeout != BleConnectionStatus.IDLE &&
                        statusAfterTimeout != BleConnectionStatus.DISCONNECTED_BY_USER) {
                        Log.d("TestExecutionVM", "Accelerometer: Desconectando $deviceAddressToReconnect después de timeout.")
                        bluetoothService.disconnect(deviceAddressToReconnect)
                    }
                } catch (e: Exception) {
                    Log.e("TestExecutionVM", "Accelerometer: Error durante reconexión a $deviceAddressToReconnect: ${e.message}", e)
                    operationSuccessful = false
                }

                // Paso 4: Actualizar la UI después del intento de reconexión
                _uiState.update { it.copy(isAttemptingAccelerometerForceReconnect = false) } // Marcar fin del intento

                if (operationSuccessful) {
                    Log.i("TestExecutionVM", "Accelerometer: Reconexión forzada a $deviceAddressToReconnect parece haber tenido éxito (SUBSCRIBED).")
                    _uiState.update { it.copy(userMessage = "Acelerómetro: Conexión restaurada.") }
                    clearUserMessageAfterDelay()
                } else {
                    Log.w("TestExecutionVM", "Accelerometer: Reconexión forzada a $deviceAddressToReconnect falló o tuvo timeout.")
                    _uiState.update {
                        it.copy(
                            userMessage = "Acelerómetro: No se pudo reconectar. Verifique el dispositivo.",
                        )
                    }
                    clearUserMessageAfterDelay()
                }

                // Siempre reevaluar el estado visual final basado en lo que diga el servicio
                val (finalIcon, finalMsg) = determineAccelerometerVisualStatus(
                    connectionStatus = bluetoothService.wearableConnectionStatus.value,
                    isBluetoothAdapterEnabled = bluetoothService.isBluetoothEnabled()
                )
                _uiState.update { it.copy(accelerometerBluetoothIconStatus = finalIcon, accelerometerBluetoothStatusMessage = finalMsg) }
            }
        } else {
            // Si no se puede intentar la reconexión (ya conectado, conectando, etc.)
            // Mostrar el estado actual.
            val (currentIcon, currentMsg) = determineAccelerometerVisualStatus(
                connectionStatus = accelerometerCurrentStatus, // Usar el estado ya obtenido
                isBluetoothAdapterEnabled = bluetoothService.isBluetoothEnabled()
            )
            _uiState.update { it.copy(
                userMessage = "Acelerómetro: ${currentMsg}",
                accelerometerBluetoothIconStatus = currentIcon, // Asegurar que el icono también se actualice
                accelerometerBluetoothStatusMessage = currentMsg
            )}
            clearUserMessageAfterDelay(3000)
        }
    }
}
