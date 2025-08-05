package com.example.app6mwt.ui

import android.Manifest
import android.app.Application
import android.content.ContentValues.TAG
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app6mwt.bluetooth.BleConnectionStatus
import com.example.app6mwt.bluetooth.BluetoothService
import com.example.app6mwt.bluetooth.DeviceCategory
import com.example.app6mwt.data.DefaultThresholdValues
import com.example.app6mwt.data.PacienteRepository
import com.example.app6mwt.data.SettingsRepository
import com.example.app6mwt.data.model.PruebaRealizada
import com.example.app6mwt.bluetooth.UiScannedDevice as ServiceUiScannedDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import kotlin.collections.toTypedArray

/**
 * Clase de datos que almacena los detalles demográficos y clínicos del paciente.
 * Esta información es cargada y puede ser editada en la pantalla de preparación.
 *
 * @property id Identificador único del paciente.
 * @property fullName Nombre completo del paciente.
 * @property sex Sexo del paciente (ej. "M", "F").
 * @property age Edad del paciente en años.
 * @property heightCm Altura del paciente en centímetros.
 * @property weightKg Peso del paciente en kilogramos.
 * @property usesInhalers Indica si el paciente utiliza inhaladores.
 * @property usesOxygen Indica si el paciente utiliza oxígeno suplementario.
 */
data class PatientDetails(
    val id: String,
    val fullName: String,
    var sex: String,
    var age: Int,
    var heightCm: Int,
    var weightKg: Int,
    var usesInhalers: Boolean,
    var usesOxygen: Boolean
)

/**
 * Clase de datos que agrupa toda la información recopilada y validada en la pantalla de preparación
 * y que se pasará a la pantalla de ejecución del test.
 *
 * @property patientId ID del paciente.
 * @property patientFullName Nombre completo del paciente.
 * @property patientSex Sexo del paciente.
 * @property patientAge Edad del paciente.
 * @property patientHeightCm Altura del paciente en cm.
 * @property patientWeightKg Peso del paciente en kg.
 * @property usesInhalers Si el paciente usa inhaladores.
 * @property usesOxygen Si el paciente usa oxígeno suplementario.
 * @property theoreticalDistance Distancia teórica calculada para el test de marcha.
 * @property basalSpo2 Saturación de oxígeno basal.
 * @property basalHeartRate Frecuencia cardíaca basal.
 * @property basalBloodPressureSystolic Presión arterial sistólica basal.
 * @property basalBloodPressureDiastolic Presión arterial diastólica basal.
 * @property basalRespiratoryRate Frecuencia respiratoria basal.
 * @property basalDyspneaBorg Nivel de disnea basal según la escala de Borg.
 * @property basalLegPainBorg Nivel de dolor en las piernas basal según la escala de Borg.
 * @property devicePlacementLocation Lugar de colocación del dispositivo (ej. oxímetro), como String.
 * @property isFirstTestForPatient Indica si esta es la primera prueba para el paciente.
 */
data class TestPreparationData(
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
    val devicePlacementLocation: String,
    val accelerometerPlacementLocation: String,
    val isFirstTestForPatient: Boolean,
    val strideLengthMeters: Double
)

/**
 * Enum que define las posibles ubicaciones donde se puede colocar el dispositivo de medición
 */
enum class DevicePlacementLocation {
    NONE, FINGER, EAR
}

enum class AccelerometerPlacementLocation {
    NONE, SOCK, POCKET
}

// --- Eventos de Navegación ---
/**
 * Clase sellada que define los posibles eventos de navegación que pueden originarse
 * desde `PreparationViewModel`. La UI observa estos eventos para realizar la navegación.
 */
sealed class PreparationNavigationEvent {
    /**
     * Evento para navegar a la pantalla de ejecución del test.
     * @property preparationData Contiene todos los datos recopilados en la preparación.
     */
    data class ToTestExecution(val preparationData: TestPreparationData) : PreparationNavigationEvent()
}


/**
 * ViewModel para la pantalla de preparación del test de marcha de 6 minutos.
 * Gestiona la lógica de:
 * - Carga y edición de datos del paciente.
 * - Conexión y obtención de datos de un dispositivo Bluetooth (oxímetro).
 * - Entrada y validación de valores fisiológicos basales.
 * - Cálculo de la distancia teórica.
 * - Gestión de permisos necesarios (Bluetooth, Ubicación).
 * - Navegación hacia la ejecución del test.
 *
 * @param application Contexto de la aplicación, necesario para acceder a servicios del sistema y recursos.
 * @param bluetoothService Servicio que encapsula la lógica de comunicación Bluetooth Low Energy (BLE).
 * @param settingsRepository Repositorio para acceder a los ajustes de la aplicación, como los rangos de validación.
 * @param pacienteRepository Repositorio para interactuar con los datos de los pacientes y sus pruebas.
 */
@HiltViewModel
class PreparationViewModel @Inject constructor(
    private val application: Application, // Se usa para obtener el contexto.
    val bluetoothService: BluetoothService, // Servicio BLE que gestiona la conexión y comunicación.
    private val settingsRepository: SettingsRepository, // Para cargar configuraciones (rangos de validación).
    private val pacienteRepository: PacienteRepository // Para cargar datos del paciente (última prueba).
) : ViewModel() {

    // --- RANGOS DE ENTRADA DESDE SETTINGS (ALINEADO CON TESTRESULTSVIEWMODEL) ---
    // Variables privadas para almacenar los rangos de validación. Se inicializan con valores por defecto
    // y luego se actualizan desde `SettingsRepository`.
    private var inputSpo2Min = DefaultThresholdValues.SPO2_INPUT_MIN_DEFAULT
    private var inputSpo2Max = DefaultThresholdValues.SPO2_INPUT_MAX_DEFAULT
    private var inputHrMin = DefaultThresholdValues.HR_INPUT_MIN_DEFAULT
    private var inputHrMax = DefaultThresholdValues.HR_INPUT_MAX_DEFAULT
    private var inputBpSysMin = DefaultThresholdValues.BP_SYSTOLIC_INPUT_MIN_DEFAULT
    private var inputBpSysMax = DefaultThresholdValues.BP_SYSTOLIC_INPUT_MAX_DEFAULT
    private var inputBpDiaMin = DefaultThresholdValues.BP_DIASTOLIC_INPUT_MIN_DEFAULT
    private var inputBpDiaMax = DefaultThresholdValues.BP_DIASTOLIC_INPUT_MAX_DEFAULT
    private var inputRrMin = DefaultThresholdValues.RR_INPUT_MIN_DEFAULT
    private var inputRrMax = DefaultThresholdValues.RR_INPUT_MAX_DEFAULT
    private var inputBorgMin = DefaultThresholdValues.BORG_INPUT_MIN_DEFAULT
    private var inputBorgMax = DefaultThresholdValues.BORG_INPUT_MAX_DEFAULT

    // Estados para los hints de los rangos en la UI.
    // Estos `State` de Compose se actualizan con los valores cargados desde `SettingsRepository`.
    private val _spo2RangeHint = mutableStateOf("Rango (${DefaultThresholdValues.SPO2_INPUT_MIN_DEFAULT}-${DefaultThresholdValues.SPO2_INPUT_MAX_DEFAULT})")
    val spo2RangeHint: State<String> = _spo2RangeHint // Expuesto a la UI como State inmutable.

    private val _hrRangeHint = mutableStateOf("Rango (${DefaultThresholdValues.HR_INPUT_MIN_DEFAULT}-${DefaultThresholdValues.HR_INPUT_MAX_DEFAULT})")
    val hrRangeHint: State<String> = _hrRangeHint

    private val _bpRangeHint = mutableStateOf("S(${DefaultThresholdValues.BP_SYSTOLIC_INPUT_MIN_DEFAULT}-${DefaultThresholdValues.BP_SYSTOLIC_INPUT_MAX_DEFAULT}), D(${DefaultThresholdValues.BP_DIASTOLIC_INPUT_MIN_DEFAULT}-${DefaultThresholdValues.BP_DIASTOLIC_INPUT_MAX_DEFAULT})")
    val bpRangeHint: State<String> = _bpRangeHint

    private val _rrRangeHint = mutableStateOf("Rango (${DefaultThresholdValues.RR_INPUT_MIN_DEFAULT}-${DefaultThresholdValues.RR_INPUT_MAX_DEFAULT})")
    val rrRangeHint: State<String> = _rrRangeHint

    private val _borgRangeHint = mutableStateOf("Rango (${DefaultThresholdValues.BORG_INPUT_MIN_DEFAULT}-${DefaultThresholdValues.BORG_INPUT_MAX_DEFAULT})")
    val borgRangeHint: State<String> = _borgRangeHint

    // --- Estados para la UI (Información del Paciente) ---
    /** ID del paciente actualmente seleccionado para la preparación del test. Es un `StateFlow` para ser observado. */
    private val _patientId = MutableStateFlow<String?>(null)
    val patientId: StateFlow<String?> = _patientId.asStateFlow() // Expuesto como StateFlow inmutable.

    /**
     * Se usa `by mutableStateOf` para la delegación de propiedades de Compose.
     * Esta es la fuente de verdad para los datos del paciente que se pueden editar.
     */
    private var _internalPatientDetails by mutableStateOf<PatientDetails?>(null)
    val internalPatientDetails: PatientDetails? // Getter personalizado para exponerlo como de solo lectura.
        get() = _internalPatientDetails

    /** Indica si el paciente tiene un historial de pruebas previas (cargado o inferido). */
    private val _patientHasPreviousHistory = MutableStateFlow(false)
    val patientHasPreviousHistory: StateFlow<Boolean> = _patientHasPreviousHistory.asStateFlow()

    /** Nombre completo del paciente. Se actualiza al inicializar el VM. */
    private val _patientFullName = MutableStateFlow("")
    val patientFullName: StateFlow<String> = _patientFullName.asStateFlow()

    // Los siguientes StateFlows están vinculados a los campos de `_internalPatientDetails` y se usan para la UI (TextFields).
    /** Sexo del paciente (editable). */
    private val _patientSex = MutableStateFlow("")
    val patientSex: StateFlow<String> = _patientSex.asStateFlow()

    /** Edad del paciente (editable), como String para el TextField. */
    private val _patientAge = MutableStateFlow("")
    val patientAge: StateFlow<String> = _patientAge.asStateFlow()

    /** Altura del paciente en cm (editable), como String. */
    private val _patientHeightCm = MutableStateFlow("")
    val patientHeightCm: StateFlow<String> = _patientHeightCm.asStateFlow()

    /** Peso del paciente en kg (editable), como String. */
    private val _patientWeightKg = MutableStateFlow("")
    val patientWeightKg: StateFlow<String> = _patientWeightKg.asStateFlow()

    /** Indica si el paciente usa inhaladores (editable). */
    private val _usesInhalers = MutableStateFlow(false)
    val usesInhalers: StateFlow<Boolean> = _usesInhalers.asStateFlow()

    /** Indica si el paciente usa oxígeno suplementario (editable). */
    private val _usesOxygen = MutableStateFlow(false)
    val usesOxygen: StateFlow<Boolean> = _usesOxygen.asStateFlow()

    /** Distancia teórica calculada para el test, usando `mutableDoubleStateOf` para Compose. */
    private val _theoreticalDistance = mutableDoubleStateOf(0.0)
    val theoreticalDistance: State<Double> = _theoreticalDistance // Expuesto como State inmutable.

    // --- ESTADOS BLE ADAPTADOS ---
    /**
     * Indica si todos los prerrequisitos de Bluetooth (permisos, Bluetooth activado, ubicación activada si es necesaria)
     * están cumplidos. Este estado es crucial para habilitar funcionalidades BLE.
     */
    private val _isBleReady = MutableStateFlow(false)
    val isBleReady: StateFlow<Boolean> = _isBleReady.asStateFlow()

    // --- ESTADOS BLE PARA ACELERÓMETRO (WEARABLE) ---
    /** Estado actual de la conexión del acelerómetro, obtenido del BluetoothService. */
    val wearableConnectionStatus: StateFlow<BleConnectionStatus> = bluetoothService.wearableConnectionStatus

    /**
     * Nombre del acelerómetro (wearable) conectado. Si no está disponible, muestra la dirección MAC.
     */
    val connectedWearableName: StateFlow<String?> = bluetoothService.connectedWearable
        .map { serviceDevice -> serviceDevice?.deviceName ?: serviceDevice?.address }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Dirección MAC del acelerómetro (wearable) conectado. */
    val connectedWearableAddress: StateFlow<String?> = bluetoothService.connectedWearable
        .map { serviceDevice -> serviceDevice?.address }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Indica si se está en proceso de conexión o reconexión al acelerómetro. */
    val isConnectingWearable: StateFlow<Boolean> = bluetoothService.wearableConnectionStatus.map {
        it == BleConnectionStatus.CONNECTING || it == BleConnectionStatus.RECONNECTING
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Último valor de distancia recibido del acelerómetro (para comprobación). Null si no hay dato. */
    private val _latestWearableSteps = MutableStateFlow<Int?>(null)
    val latestWearableSteps: StateFlow<Int?> = _latestWearableSteps.asStateFlow()

    /** Indica si el usuario ha confirmado que el acelerómetro está correctamente colocado. */
    private val _isAccelerometerPlaced = MutableStateFlow(false)
    val isAccelerometerPlaced: StateFlow<Boolean> = _isAccelerometerPlaced.asStateFlow()

    /** Almacena la ubicación seleccionada para la colocación del acelerómetro. */
    private val _accelerometerPlacementLocation = MutableStateFlow(AccelerometerPlacementLocation.NONE) // Usar el nuevo enum
    val accelerometerPlacementLocation: StateFlow<AccelerometerPlacementLocation> = _accelerometerPlacementLocation.asStateFlow()

    /** Mensaje de UI relacionado con el estado de Bluetooth. */
    private val _uiBluetoothMessage = MutableStateFlow<String?>("Verificando estado de Bluetooth...") // Mensaje inicial
    val uiBluetoothMessage: StateFlow<String?> = _uiBluetoothMessage.asStateFlow()

    // --- ESTADOS BLE PARA PULSIOXÍMETRO (Oximeter) ---
    /** Estado actual de la conexión BLE del pulsioxímetro, obtenido directamente del `BluetoothService`. */
    val oximeterConnectionStatus: StateFlow<BleConnectionStatus> = bluetoothService.oximeterConnectionStatus // Cambio aquí

    /** Mensaje de UI relacionado con el estado de Bluetooth (general). */
// _uiBluetoothMessage se mantiene como está, pero su lógica de actualización en checkBleRequirementsAndReadyState cambiará.

    /** Lista de dispositivos BLE escaneados, obtenida directamente del `BluetoothService`. */
    val scannedDevices: StateFlow<List<ServiceUiScannedDevice>> = bluetoothService.scannedDevices // Se mantiene general

    /**
     * Nombre del dispositivo BLE (pulsioxímetro) conectado.
     */
    val connectedOximeterDeviceName: StateFlow<String?> = bluetoothService.connectedOximeter // Cambio aquí
        .map { serviceDevice -> serviceDevice?.deviceName ?: serviceDevice?.address }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Dirección MAC del pulsioxímetro BLE conectado. */
    val connectedOximeterDeviceAddress: StateFlow<String?> = bluetoothService.connectedOximeter // Cambio aquí
        .map { serviceDevice -> serviceDevice?.address }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Indica si se está en proceso de conexión o reconexión a un dispositivo BLE (pulsioxímetro). */
    val isConnectingOximeter: StateFlow<Boolean> = bluetoothService.oximeterConnectionStatus.map { // Cambio aquí
        it == BleConnectionStatus.CONNECTING || it == BleConnectionStatus.RECONNECTING
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Indica si el `BluetoothService` está actualmente escaneando dispositivos. */
    val isScanning: StateFlow<Boolean> = bluetoothService.isScanning // Se mantiene general
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // --- Estados de Datos BLE (actualizados por el SERVICIO ahora) ---
    // Estos StateFlows se actualizan cuando el `BluetoothService` emite nuevos datos del dispositivo conectado.
    /** Último valor de SpO2 recibido del dispositivo BLE. Null si no hay dato. */
    private val _latestBleSpo2 = MutableStateFlow<Int?>(null)
    val latestBleSpo2: StateFlow<Int?> = _latestBleSpo2.asStateFlow()

    /** Último valor de frecuencia cardíaca (HeartRate) recibido. Null si no hay dato. */
    private val _latestBleHeartRate = MutableStateFlow<Int?>(null)
    val latestBleHeartRate: StateFlow<Int?> = _latestBleHeartRate.asStateFlow()

    /** Intensidad de la señal BLE (si el dispositivo la provee). Null si no hay dato. */
    private val _latestBleSignalStrength = MutableStateFlow<Int?>(null)
    val latestBleSignalStrength: StateFlow<Int?> = _latestBleSignalStrength.asStateFlow()

    /**
     * Indica si el pulsioxímetro detecta que no hay un dedo colocado.
     * `true` si no hay dedo o el estado es desconocido/nulo (valor por defecto para seguridad).
     */
    private val _latestBleNoFinger = MutableStateFlow<Boolean?>(true)
    val latestBleNoFinger: StateFlow<Boolean?> = _latestBleNoFinger.asStateFlow()

    /** Valor para la curva pletismográfica. Null si no hay dato. */
    private val _latestBlePleth = MutableStateFlow<Int?>(null)
    val latestBlePleth: StateFlow<Int?> = _latestBlePleth.asStateFlow()

    /** Valor para la barra de calidad de señal. Null si no hay dato. */
    private val _latestBleBarGraph = MutableStateFlow<Int?>(null)
    val latestBleBarGraph: StateFlow<Int?> = _latestBleBarGraph.asStateFlow()


    // --- ESTADOS DE ENTRADA MANUAL DE BASALES (SIN CAMBIOS RESPECTO A LÓGICA PREVIA) ---
    // StateFlows para vincular con los TextField de Compose donde el usuario introduce los valores basales manualmente.
    /** Valor de SpO2 introducido manualmente. */
    private val _spo2Input = MutableStateFlow("")
    val spo2Input: StateFlow<String> = _spo2Input.asStateFlow()

    /** Valor de frecuencia cardíaca introducido manualmente. */
    private val _heartRateInput = MutableStateFlow("")
    val heartRateInput: StateFlow<String> = _heartRateInput.asStateFlow()

    /** Valor de presión arterial (sistólica/diastólica) introducido manualmente. */
    private val _bloodPressureInput = MutableStateFlow("")
    val bloodPressureInput: StateFlow<String> = _bloodPressureInput.asStateFlow()

    /** Valor de frecuencia respiratoria introducido manualmente. */
    private val _respiratoryRateInput = MutableStateFlow("")
    val respiratoryRateInput: StateFlow<String> = _respiratoryRateInput.asStateFlow()

    /** Valor de disnea (escala de Borg) introducido manualmente. */
    private val _dyspneaBorgInput = MutableStateFlow("")
    val dyspneaBorgInput: StateFlow<String> = _dyspneaBorgInput.asStateFlow()

    /** Valor de dolor en piernas (escala de Borg) introducido manualmente. */
    private val _legPainBorgInput = MutableStateFlow("")
    val legPainBorgInput: StateFlow<String> = _legPainBorgInput.asStateFlow()

    // --- Estados de Validación de Valores Basales ---
    /** Mensaje para la UI sobre el estado de completitud y validez de los campos basales. */
    private val _basalValuesStatusMessage = MutableStateFlow("Complete los campos basales.")
    val basalValuesStatusMessage: StateFlow<String> = _basalValuesStatusMessage.asStateFlow()

    /** Indica si TODOS los campos basales requeridos están completos Y son válidos. */
    private val _areBasalsValid = MutableStateFlow(false)
    val areBasalsValid: StateFlow<Boolean> = _areBasalsValid.asStateFlow()

    // Estados de validez individuales para cada campo basal, para mostrar feedback específico en la UI.
    // Se inicializan a `true` para no mostrar error antes de que el usuario interactúe.
    private val _isValidSpo2 = MutableStateFlow(true)
    val isValidSpo2: StateFlow<Boolean> = _isValidSpo2.asStateFlow()

    private val _isValidHeartRate = MutableStateFlow(true)
    val isValidHeartRate: StateFlow<Boolean> = _isValidHeartRate.asStateFlow()

    private val _isValidBloodPressure = MutableStateFlow(true)
    val isValidBloodPressure: StateFlow<Boolean> = _isValidBloodPressure.asStateFlow()

    private val _isValidRespiratoryRate = MutableStateFlow(true)
    val isValidRespiratoryRate: StateFlow<Boolean> = _isValidRespiratoryRate.asStateFlow()

    private val _isValidDyspneaBorg = MutableStateFlow(true)
    val isValidDyspneaBorg: StateFlow<Boolean> = _isValidDyspneaBorg.asStateFlow()

    private val _isValidLegPainBorg = MutableStateFlow(true)
    val isValidLegPainBorg: StateFlow<Boolean> = _isValidLegPainBorg.asStateFlow()

    // --- OTROS ESTADOS DE UI Y EVENTOS (SIN CAMBIOS RESPECTO A LÓGICA PREVIA) ---
    /** Controla la visibilidad del diálogo de confirmación para navegar hacia atrás. */
    private val _showNavigateBackDialog = MutableStateFlow(false)
    val showNavigateBackDialog: StateFlow<Boolean> = _showNavigateBackDialog.asStateFlow()

    /**
     * Evento (`SharedFlow`) para indicar a la UI que debe realizar la acción de navegar hacia atrás.
     * Se usa `SharedFlow` porque es un evento que ocurre una vez y no necesita mantener un estado.
     */
    private val _navigateBackEvent = MutableSharedFlow<Unit>()
    val navigateBackEvent: SharedFlow<Unit> = _navigateBackEvent.asSharedFlow()

    /** Indica si el usuario ha confirmado que el dispositivo de medición está correctamente colocado. */
    private val _isDevicePlaced = MutableStateFlow(false)
    val isDevicePlaced: StateFlow<Boolean> = _isDevicePlaced.asStateFlow()

    /** Almacena la ubicación seleccionada para la colocación del dispositivo (Dedo, Oreja, etc.). */
    private val _devicePlacementLocation = MutableStateFlow(DevicePlacementLocation.NONE)
    val devicePlacementLocation: StateFlow<DevicePlacementLocation> = _devicePlacementLocation.asStateFlow()

    /** Evento (`SharedFlow`) para navegar a otra pantalla, emitiendo un `PreparationNavigationEvent`. */
    private val _navigateToEvent = MutableSharedFlow<PreparationNavigationEvent>()
    val navigateToEvent = _navigateToEvent.asSharedFlow() // Expuesto como SharedFlow inmutable.

    private val _calculatedStrideLengthMeters = mutableDoubleStateOf(0.0)
    val calculatedStrideLengthMeters: State<Double> = _calculatedStrideLengthMeters

    // --- GESTIÓN DE PERMISOS Y ACTIVACIONES ---
    // SharedFlows para emitir eventos que la UI observará para solicitar acciones del sistema.
    /** Evento para solicitar al usuario que active el Bluetooth. */
    private val _requestEnableBluetoothEvent = MutableSharedFlow<Unit>()
    val requestEnableBluetoothEvent = _requestEnableBluetoothEvent.asSharedFlow()

    /** Evento para solicitar permisos de Android (puede ser una lista de permisos). */
    private val _requestPermissionsEvent = MutableSharedFlow<Array<String>>() // Emite un array de nombres de permisos.
    val requestPermissionsEvent: SharedFlow<Array<String>> = _requestPermissionsEvent.asSharedFlow()

    /** Evento para solicitar al usuario que active los servicios de ubicación. */
    private val _requestLocationServicesEvent = MutableSharedFlow<Unit>()
    val requestLocationServicesEvent = _requestLocationServicesEvent.asSharedFlow()

    /** Almacena el último mensaje de error relevante emitido por `BluetoothService`. Podría usarse para mostrar errores más detallados. */
    private val _lastServiceErrorMessage = MutableStateFlow<String?>(null)

    val oximeterDisplayStatus: StateFlow<String> = oximeterConnectionStatus
        .map { status ->
            when (status) {
                BleConnectionStatus.SUBSCRIBED -> "Conectado"
                BleConnectionStatus.CONNECTED -> "Configurando" // O simplemente "Conectado"
                BleConnectionStatus.CONNECTING, BleConnectionStatus.RECONNECTING -> "Conectando..."
                BleConnectionStatus.IDLE, BleConnectionStatus.DISCONNECTED_BY_USER -> "Desconectado"
                BleConnectionStatus.DISCONNECTED_ERROR,
                BleConnectionStatus.ERROR_DEVICE_NOT_FOUND, // Pérdida o error
                BleConnectionStatus.ERROR_SERVICE_NOT_FOUND,
                BleConnectionStatus.ERROR_CHARACTERISTIC_NOT_FOUND,
                BleConnectionStatus.ERROR_SUBSCRIBE_FAILED,
                BleConnectionStatus.ERROR_GENERIC -> "Error de conexión" // O "Pérdida de conexión" si puedes distinguirlo
                BleConnectionStatus.ERROR_PERMISSIONS -> "Error: Permisos" // Estos podrían ir al globalUiMessage
                BleConnectionStatus.ERROR_BLUETOOTH_DISABLED -> "Error: Bluetooth off"// Estos también
                BleConnectionStatus.SCANNING -> if (oximeterConnectionStatus.value == BleConnectionStatus.IDLE) "Desconectado" else oximeterConnectionStatus.value.toUserFriendlyMessage(null,"").substringBefore(".").take(20) // Mantener estado previo si estaba conectando/conectado
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Desconectado")

    val wearableDisplayStatus: StateFlow<String> = wearableConnectionStatus
        .map { status ->
            // Lógica similar a oximeterDisplayStatus
            when (status) {
                BleConnectionStatus.SUBSCRIBED -> "Conectado"
                BleConnectionStatus.CONNECTED -> "Configurando"
                BleConnectionStatus.CONNECTING, BleConnectionStatus.RECONNECTING -> "Conectando..."
                BleConnectionStatus.IDLE, BleConnectionStatus.DISCONNECTED_BY_USER -> "Desconectado"
                BleConnectionStatus.DISCONNECTED_ERROR,
                BleConnectionStatus.ERROR_DEVICE_NOT_FOUND,
                BleConnectionStatus.ERROR_SERVICE_NOT_FOUND,
                BleConnectionStatus.ERROR_CHARACTERISTIC_NOT_FOUND,
                BleConnectionStatus.ERROR_SUBSCRIBE_FAILED,
                BleConnectionStatus.ERROR_GENERIC -> "Error de conexión"
                BleConnectionStatus.ERROR_PERMISSIONS -> "Error: Permisos"
                BleConnectionStatus.ERROR_BLUETOOTH_DISABLED -> "Error: Bluetooth off"
                BleConnectionStatus.SCANNING -> if (wearableConnectionStatus.value == BleConnectionStatus.IDLE) "Desconectado" else wearableConnectionStatus.value.toUserFriendlyMessage(null,"").substringBefore(".").take(20)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Desconectado")

    private val _connectingOximeterAddress = MutableStateFlow<String?>(null)
    val connectingOximeterAddress: StateFlow<String?> = _connectingOximeterAddress

    private val _connectingWearableAddress = MutableStateFlow<String?>(null)
    val connectingWearableAddress: StateFlow<String?> = _connectingWearableAddress

    // --- Definiciones de conjuntos de permisos (Constantes dentro de companion object) ---
    companion object {
        // Tag para logging específico de este ViewModel.
        private const val TAG_VM = "PreparationViewModel" // Renombrado para evitar conflicto con el import ContentValues.TAG

        /** Permisos de Bluetooth requeridos para Android 12 (API 31) y superior. */
        val BT_PERMISSIONS_S_AND_ABOVE = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, // Para descubrir dispositivos BLE.
            Manifest.permission.BLUETOOTH_CONNECT // Para conectar a dispositivos BLE.
        )

        /** Nombre del permiso de ubicación fina. */
        const val ACCESS_FINE_LOCATION_STRING = Manifest.permission.ACCESS_FINE_LOCATION

        /** Permisos de Bluetooth para versiones inferiores a Android 12 (API < 31). */
        val BT_PERMISSIONS_BELOW_S = arrayOf(
            Manifest.permission.BLUETOOTH, // Permiso general de Bluetooth.
            Manifest.permission.BLUETOOTH_ADMIN, // Para funciones administrativas de Bluetooth (como escanear).
            ACCESS_FINE_LOCATION_STRING // La ubicación fina es necesaria para escanear BLE en < Android S.
        )

        /**
         * Obtiene los permisos de Bluetooth necesarios según la versión del SDK de Android.
         * @param context Contexto de la aplicación (no usado directamente en esta implementación, pero podría serlo).
         * @return Array de Strings con los nombres de los permisos requeridos.
         */
        fun getRequiredBluetoothPermissions(context: Context): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BT_PERMISSIONS_S_AND_ABOVE
            } else {
                BT_PERMISSIONS_BELOW_S
            }
        }
    }

    // --- LÓGICA MANUAL PARA CANSTARTTEST ---
    /**
     * `StateFlow` que indica si se cumplen todas las condiciones para iniciar el test.
     * Su valor se actualiza mediante `calculateCanStartTest()` y los listeners configurados
     * en `setupCanStartTestManualListeners()`.
     */
    private val _combinedCanStartTestConditions = MutableStateFlow(calculateCanStartTest())
    val canStartTest: StateFlow<Boolean> = _combinedCanStartTestConditions.asStateFlow()

    /**
     * Calcula si se cumplen todas las condiciones necesarias para poder iniciar el test.
     * Esta función es central para habilitar/deshabilitar el botón "Iniciar Test".
     *
     * @return `true` si todas las condiciones se cumplen, `false` en caso contrario.
     */
    private fun calculateCanStartTest(): Boolean {
        val currentIsBleReady = _isBleReady.value // Prerrequisitos generales (permisos, BT, ubicación)
        // Pulsioxímetro
        val oximeterStatus = bluetoothService.oximeterConnectionStatus.value
        val isOximeterConnectedAndSubscribed = oximeterStatus == BleConnectionStatus.SUBSCRIBED
        val currentNoFinger = _latestBleNoFinger.value ?: true
        val oximeterPlacementOk = if (_isDevicePlaced.value) { // _isDevicePlaced es para el oximetro
            _devicePlacementLocation.value != DevicePlacementLocation.NONE
        } else {
            false
        }

        // Acelerómetro
        val wearableStatus = bluetoothService.wearableConnectionStatus.value
        val isWearableConnectedAndSubscribed = wearableStatus == BleConnectionStatus.SUBSCRIBED
        val accelerometerPlacementOk = if (_isAccelerometerPlaced.value) { // Nuevo estado para el acelerómetro
            _accelerometerPlacementLocation.value != AccelerometerPlacementLocation.NONE
        } else {
            false
        }

        val currentAreBasalsValid = _areBasalsValid.value
        val currentPatientId = _patientId.value
        val currentPatientFullName = _patientFullName.value
        val currentInternalPatientDetails = _internalPatientDetails

        val patientInfoOk = !currentPatientId.isNullOrBlank() &&
                currentPatientFullName.isNotBlank() &&
                currentInternalPatientDetails != null &&
                currentInternalPatientDetails.sex.isNotBlank() &&
                currentInternalPatientDetails.age > 0 &&
                currentInternalPatientDetails.heightCm > 0 &&
                currentInternalPatientDetails.weightKg > 0

        val strideLengthOk = _calculatedStrideLengthMeters.value > 0.0 // Asegura que la zancada se pudo calcular y es positiva

        val allConditionsMet = currentIsBleReady &&
                isOximeterConnectedAndSubscribed &&
                isWearableConnectedAndSubscribed &&
                oximeterPlacementOk &&
                accelerometerPlacementOk &&
                patientInfoOk &&
                currentAreBasalsValid &&
                !currentNoFinger &&
                strideLengthOk

        Log.d("CanStartTestLogic", "Calculating: isBleReady=$currentIsBleReady, " +
                "OxiStatus=${oximeterStatus}(${isOximeterConnectedAndSubscribed}), NoFinger=${!currentNoFinger}, OxiPlaced=$oximeterPlacementOk, " +
                "WearStatus=${wearableStatus}(${isWearableConnectedAndSubscribed}), WearPlaced=$accelerometerPlacementOk, WearData=${_latestWearableSteps.value != null}" +
                "Basals=$currentAreBasalsValid, PatientInfo=$patientInfoOk, Final=$allConditionsMet")
        return allConditionsMet
    }

    /**
     * Configura "listeners" (observadores de `Flow`) para las dependencias de `canStartTest`.
     * Cuando cualquiera de estos `StateFlow`s cambia su valor, se recalcula `_combinedCanStartTestConditions`
     * llamando a `calculateCanStartTest()`. Esto asegura que el estado `canStartTest` esté siempre actualizado.
     */
    private fun setupCanStartTestManualListeners() {
        val flowsToObserve = listOf(
            _isBleReady,
            // Pulsioxímetro
            bluetoothService.oximeterConnectionStatus, // Observar directamente del servicio
            _areBasalsValid,
            _isDevicePlaced, // Para el oximetro
            _devicePlacementLocation.asStateFlow(), // Para el oximetro
            _latestBleNoFinger,
            // Paciente
            _patientId,
            _patientFullName,
            _patientSex,
            _patientAge,
            _patientHeightCm,
            _patientWeightKg,
            // Acelerómetro (NUEVO)
            bluetoothService.wearableConnectionStatus, // Observar directamente del servicio
            _isAccelerometerPlaced,
            _accelerometerPlacementLocation.asStateFlow(),
            _latestWearableSteps // Si la recepción de datos del wearable es condición
        )

        flowsToObserve.forEach { flow ->
            flow.onEach {
                _combinedCanStartTestConditions.value = calculateCanStartTest()
            }.launchIn(viewModelScope)
        }
    }

    /**
     * Bloque `init`: Se ejecuta cuando se crea una instancia del ViewModel.
     * Responsable de la configuración inicial.
     */
    init {
        Log.d(TAG_VM, "PreparationViewModel init") // Usar TAG_VM definido en companion object.
        viewModelScope.launch { // Se lanza una coroutine para operaciones asíncronas.
            // Cargar los rangos de entrada UNA VEZ desde el SettingsRepository.
            // `first()` suspende hasta que el Flow emite el primer valor.
            inputSpo2Min = settingsRepository.spo2InputMinFlow.first()
            inputSpo2Max = settingsRepository.spo2InputMaxFlow.first()
            inputHrMin = settingsRepository.hrInputMinFlow.first()
            inputHrMax = settingsRepository.hrInputMaxFlow.first()
            inputBpSysMin = settingsRepository.bpSystolicInputMinFlow.first()
            inputBpSysMax = settingsRepository.bpSystolicInputMaxFlow.first()
            inputBpDiaMin = settingsRepository.bpDiastolicInputMinFlow.first()
            inputBpDiaMax = settingsRepository.bpDiastolicInputMaxFlow.first()
            inputRrMin = settingsRepository.rrInputMinFlow.first()
            inputRrMax = settingsRepository.rrInputMaxFlow.first()
            inputBorgMin = settingsRepository.borgInputMinFlow.first()
            inputBorgMax = settingsRepository.borgInputMaxFlow.first()

            Log.i(TAG_VM, "Rangos de entrada cargados desde SettingsRepository: " +
                    "SpO2 (${inputSpo2Min}-${inputSpo2Max}), HR (${inputHrMin}-${inputHrMax}), etc.")

            // Actualizar los hints de los rangos para la UI con los valores cargados
            _spo2RangeHint.value = "(${inputSpo2Min}-${inputSpo2Max})"
            _hrRangeHint.value = "(${inputHrMin}-${inputHrMax})"
            _bpRangeHint.value = "S(${inputBpSysMin}-${inputBpSysMax}), D(${inputBpDiaMin}-${inputBpDiaMax})"
            _rrRangeHint.value = "(${inputRrMin}-${inputRrMax})"
            _borgRangeHint.value = "(${inputBorgMin}-${inputBorgMax})"


            // Validar los campos basales (que podrían estar vacíos o pre-rellenados)
            // con los rangos recién cargados.
            validateAllBasalInputs()
        }
        // Inicia la observación del estado y datos del BluetoothService.
        observeBluetoothServiceStateAndData()
        // Configura los listeners para actualizar `canStartTest` automáticamente.
        setupCanStartTestManualListeners()

        // Verifica los requisitos de BLE (permisos, estado de BT/Ubicación) al inicio.
        viewModelScope.launch {
            checkBleRequirementsAndReadyState() // Actualiza _isBleReady y _uiBluetoothMessage.
        }
    }

    /**
     * Inicializa el ViewModel con los datos del paciente recibidos (generalmente desde la navegación).
     * Carga información de la última prueba si el paciente tiene historial.
     * Calcula la distancia teórica y actualiza los estados relevantes.
     *
     * @param patientIdArg ID del paciente.
     * @param patientNameArg Nombre completo del paciente.
     * @param patientHasHistoryFromNav Booleano que indica si se sabe que el paciente tiene historial
     *                                (pasado desde la pantalla anterior, ej. PatientManagementScreen).
     */
    fun initialize(patientIdArg: String, patientNameArg: String, patientHasHistoryFromNav: Boolean) {
        // Comprobar si el ViewModel ya fue inicializado con los mismos datos para evitar recargas innecesarias.
        val alreadyInitialized = _patientId.value == patientIdArg &&
                _patientFullName.value == patientNameArg &&
                _patientHasPreviousHistory.value == patientHasHistoryFromNav &&
                _internalPatientDetails?.id == patientIdArg // También verificar _internalPatientDetails

        if (alreadyInitialized) {
            Log.d("InitializeVM", "ViewModel ya inicializado con los mismos datos para $patientIdArg. Verificando BLE.")
            // Asegurar que el estado BLE y canStartTest se verifiquen incluso si no se recargan datos del paciente.
            viewModelScope.launch {
                checkBleRequirementsAndReadyState()
                _combinedCanStartTestConditions.value = calculateCanStartTest()
            }
            return // Salir temprano si ya está inicializado.
        }

        // Actualizar los StateFlows con los argumentos recibidos.
        _patientId.value = patientIdArg
        _patientFullName.value = patientNameArg
        _patientHasPreviousHistory.value = patientHasHistoryFromNav // Actualiza si tiene historial, basado en la navegación.
        Log.d("InitializeVM", "Inicializando ViewModel para paciente ID: $patientIdArg, Nombre: $patientNameArg, TieneHistoria (desde Nav): $patientHasHistoryFromNav")

        viewModelScope.launch { // Coroutine para operaciones asíncronas de carga de datos.
            // Variables temporales para almacenar los datos recuperados del paciente.
            var fetchedSex = ""
            var fetchedAgeString = ""
            var fetchedHeightCm = ""
            var fetchedWeightKg = ""
            var fetchedUsesInhalers = false
            var fetchedUsesOxygen = false

            // Si se indica que el paciente tiene historial, intentar cargar datos de su última prueba.
            if (patientHasHistoryFromNav) {
                Log.d("InitializeVM", "Paciente con historial indicado. Intentando cargar datos de la última prueba para $patientIdArg.")
                val lastTest: PruebaRealizada? = pacienteRepository.getPruebaMasRecienteParaPaciente(patientIdArg)

                if (lastTest?.datosCompletos?.summaryData != null) {
                    val summaryFromTest = lastTest.datosCompletos.summaryData
                    Log.i("InitializeVM", "Datos del sumario de la última prueba encontrados (Prueba ID: ${lastTest.pruebaId}): $summaryFromTest")

                    // Extraer los datos del sumario de la última prueba.
                    fetchedSex = summaryFromTest.patientSex
                    val ageAtLastTest = summaryFromTest.patientAge
                    fetchedHeightCm = if (summaryFromTest.patientHeightCm > 0) summaryFromTest.patientHeightCm.toString() else ""
                    fetchedWeightKg = if (summaryFromTest.patientWeightKg > 0) summaryFromTest.patientWeightKg.toString() else ""
                    fetchedUsesInhalers = summaryFromTest.usesInhalers
                    fetchedUsesOxygen = summaryFromTest.usesOxygen

                    // Calcular la edad actual del paciente basándose en la edad registrada en la última prueba y su fecha.
                    if (ageAtLastTest > 0 && lastTest.fechaTimestamp > 0) {
                        val calendarPrueba = Calendar.getInstance().apply { timeInMillis = lastTest.fechaTimestamp }
                        val calendarActual = Calendar.getInstance()

                        var aniosDiferencia = calendarActual.get(Calendar.YEAR) - calendarPrueba.get(Calendar.YEAR)
                        // Ajustar la diferencia de años si aún no ha pasado el día del cumpleaños de este año.
                        if (calendarActual.get(Calendar.DAY_OF_YEAR) < calendarPrueba.get(Calendar.DAY_OF_YEAR) && aniosDiferencia > 0) {
                            aniosDiferencia--
                        }
                        val edadActual = ageAtLastTest + aniosDiferencia
                        fetchedAgeString = if (edadActual > 0) edadActual.toString() else ""
                        Log.d("InitializeVM", "Edad en prueba: $ageAtLastTest, Fecha prueba: ${calendarPrueba.time}, Edad actual calculada: $edadActual")
                    } else {
                        // Si no se puede calcular la edad actual, usar la edad de la última prueba como fallback.
                        fetchedAgeString = if (ageAtLastTest > 0) ageAtLastTest.toString() else "" // Fallback
                        Log.d("InitializeVM", "Usando edad de la prueba ($ageAtLastTest) como fallback o porque no se pudo calcular la actual.")
                    }

                } else {
                    // Si no se encuentran datos de la última prueba o son inválidos.
                    Log.w("InitializeVM", "Paciente con historial indicado, pero no se encontraron datos válidos (summaryData null o prueba null) para $patientIdArg. Se usarán campos vacíos/default.")
                    // Se limpiarán los campos, lo que indica que es la primera prueba o los datos no están disponibles.
                    _patientHasPreviousHistory.value = false // Corregir si no se pudieron cargar datos.
                }
            } else {
                // Si el paciente no tiene historial (es nuevo) o se están introduciendo datos por primera vez.
                Log.d("InitializeVM", "Paciente sin historial indicado desde la navegación, o se están introduciendo datos nuevos. Campos permanecerán vacíos/default.")
                _patientHasPreviousHistory.value = false // Confirmar que no hay historial previo.
            }

            // Actualizar los StateFlows de la UI y la instancia interna `_internalPatientDetails`.
            _patientSex.value = fetchedSex
            _patientAge.value = fetchedAgeString
            _patientHeightCm.value = fetchedHeightCm
            _patientWeightKg.value = fetchedWeightKg
            _usesInhalers.value = fetchedUsesInhalers
            _usesOxygen.value = fetchedUsesOxygen

            // Crear o actualizar `_internalPatientDetails` con los datos recuperados o por defecto.
            _internalPatientDetails = PatientDetails(
                id = patientIdArg,
                fullName = patientNameArg, // El nombre siempre viene de la navegación.
                sex = fetchedSex,
                age = fetchedAgeString.toIntOrNull() ?: 0, // Convertir a Int, o 0 si es inválido.
                heightCm = fetchedHeightCm.toIntOrNull() ?: 0,
                weightKg = fetchedWeightKg.toIntOrNull() ?: 0,
                usesInhalers = fetchedUsesInhalers,
                usesOxygen = fetchedUsesOxygen
            )

            // Estas llamadas son cruciales y deben ocurrir después de que `_internalPatientDetails` se haya actualizado.
            calculateTheoreticalDistance() // Calcular distancia teórica basada en los nuevos datos.
            calculateStrideLength()
            validateAllBasalInputs() // Validar los campos basales (si hay alguno pre-rellenado).
            checkBleRequirementsAndReadyState() // Verificar nuevamente el estado de BLE.
            _combinedCanStartTestConditions.value = calculateCanStartTest() // Recalcular la posibilidad de iniciar el test.
        }
    }

    /**
     * Observa los diferentes `StateFlow`s del `BluetoothService` para reaccionar a cambios en el estado
     * de la conexión, escaneo, errores y datos recibidos del dispositivo BLE.
     */
    private fun observeBluetoothServiceStateAndData() {
        // --- OBSERVACIÓN DEL PULSIOXÍMETRO ---
        bluetoothService.oximeterConnectionStatus
            .onEach { status ->
                Log.d(TAG_VM, "OximeterConnectionStatus del servicio cambió a: $status")

                // Lógica para limpiar _connectingOximeterAddress:
                if (_connectingOximeterAddress.value != null && status != BleConnectionStatus.CONNECTING) {
                    // Limpiar si el estado ya NO es CONNECTING y teníamos una dirección almacenada.
                    // Esto significa que el intento de conexión a ESE dispositivo específico ha terminado.
                    if (status == BleConnectionStatus.SUBSCRIBED ||
                        status == BleConnectionStatus.IDLE ||
                        status == BleConnectionStatus.DISCONNECTED_BY_USER ||
                        status == BleConnectionStatus.DISCONNECTED_ERROR ||
                        status.isErrorStatus() // Tu helper para BleConnectionStatus.ERROR_...
                    ) {
                        Log.d(TAG_VM, "Limpiando _connectingOximeterAddress. Dirección previa: ${_connectingOximeterAddress.value}, Estado actual: $status")
                        _connectingOximeterAddress.value = null
                    }
                }

                if (status == BleConnectionStatus.DISCONNECTED_BY_USER || status == BleConnectionStatus.DISCONNECTED_ERROR || status == BleConnectionStatus.IDLE) {
                    // Considera limpiar también en IDLE si eso implica una desconexión no intencionada
                    clearOximeterBleDataStates()
                }
                checkBleRequirementsAndReadyState()
            }
            .launchIn(viewModelScope)

        // Observar si el servicio está escaneando.
        bluetoothService.isScanning
            .onEach { scanning ->
                Log.d(TAG_VM, "isScanning del servicio cambió a: $scanning")
                checkBleRequirementsAndReadyState() // Actualizar UI y estado _isBleReady.
            }
            .launchIn(viewModelScope)

        // Observar los mensajes de error/informativos emitidos por el servicio.
        bluetoothService.errorMessages
            .onEach { message ->
                Log.d(TAG_VM, "Mensaje de error/info del BluetoothService: $message")
                _lastServiceErrorMessage.value = message // Guardar el último mensaje del servicio.
                // Filtrar mensajes que ya se manejan explícitamente en `checkBleRequirementsAndReadyState`
                // (permisos, Bluetooth desactivado, ubicación) para no sobreescribir mensajes más específicos.
                // También filtrar mensajes genéricos de GATT o errores si _isBleReady ya estaba gestionando la UI.
                if (! (message.contains("permisos", ignoreCase = true) ||
                            message.contains("Bluetooth desactivado", ignoreCase = true) ||
                            message.contains("ubicación", ignoreCase = true)
                            )) {
                    // Solo actualizar _uiBluetoothMessage si no es un error de bajo nivel (GATT) y _isBleReady es true,
                    // o si no es un mensaje de error genérico.
                    if (_isBleReady.value && !message.contains("GATT", ignoreCase = true) && !message.contains("error", ignoreCase = true) ) {
                        _uiBluetoothMessage.value = message
                    }
                }
                checkBleRequirementsAndReadyState() // Reevaluar el estado general de BLE y el mensaje de UI.
            }
            .launchIn(viewModelScope)

        // Observar los datos del dispositivo BLE (SpO2, HR, etc.) emitidos por el servicio.
        bluetoothService.oximeterDeviceData
            .onEach { newData ->
                Log.d(TAG_VM, "Nuevos datos Oximeter BLE del servicio: SpO2=${newData.spo2}, HR=${newData.heartRate}, NoFinger=${newData.noFingerDetected}, Signal=${newData.signalStrength}")
                // Actualizar los StateFlows correspondientes con los nuevos datos.
                _latestBleSpo2.value = newData.spo2
                _latestBleHeartRate.value = newData.heartRate
                _latestBleSignalStrength.value = newData.signalStrength
                _latestBleNoFinger.value = newData.noFingerDetected // true si no hay dedo, false si hay dedo.
                _latestBlePleth.value = newData.plethValue
                _latestBleBarGraph.value = newData.barGraphValue

                // Recalcular `canStartTest` ya que la detección de dedo (`_latestBleNoFinger`) puede haber cambiado.
                _combinedCanStartTestConditions.value = calculateCanStartTest()
            }
            .launchIn(viewModelScope)

        // Observar cambios en el dispositivo conectado.
        bluetoothService.connectedOximeter
            .onEach { _ -> // El valor específico del dispositivo no se usa aquí, solo se reacciona al cambio.
                checkBleRequirementsAndReadyState() // Actualizar el mensaje de UI y el estado _isBleReady.
            }
            .launchIn(viewModelScope)

        // --- OBSERVACIÓN DEL ACELERÓMETRO (Wearable) ---
        bluetoothService.wearableConnectionStatus
            .onEach { status ->
                Log.d(TAG_VM, "WearableConnectionStatus del servicio cambió a: $status")

                // Lógica para limpiar _connectingWearableAddress:
                // Similar al pulsioxímetro.
                if (_connectingWearableAddress.value != null && status != BleConnectionStatus.CONNECTING) {
                    // Limpiar si el estado ya NO es CONNECTING y teníamos una dirección almacenada.
                    if (status == BleConnectionStatus.SUBSCRIBED ||
                        status == BleConnectionStatus.IDLE ||
                        status == BleConnectionStatus.DISCONNECTED_BY_USER ||
                        status == BleConnectionStatus.DISCONNECTED_ERROR ||
                        status.isErrorStatus()
                    ) {
                        Log.d(TAG_VM, "Limpiando _connectingWearableAddress. Dirección previa: ${_connectingWearableAddress.value}, Estado actual: $status")
                        _connectingWearableAddress.value = null
                    }
                }

                if (status == BleConnectionStatus.DISCONNECTED_BY_USER || status == BleConnectionStatus.DISCONNECTED_ERROR || status == BleConnectionStatus.IDLE) {
                    clearWearableBleDataStates()
                }
                checkBleRequirementsAndReadyState()
            }
            .launchIn(viewModelScope)

        bluetoothService.wearableDeviceData
            .onEach { wearableData ->
                Log.d(TAG_VM, "Nuevos datos Wearable BLE: Pasos=${wearableData.totalSteps}")
                _latestWearableSteps.value = wearableData.totalSteps
                // Si la distancia es para comprobación, podría influir en canStartTest
                _combinedCanStartTestConditions.value = calculateCanStartTest()
            }
            .launchIn(viewModelScope)

        bluetoothService.connectedWearable
            .onEach { _ ->
                checkBleRequirementsAndReadyState()
            }
            .launchIn(viewModelScope)

        // El scannedDevices y isScanning ya son generales y no necesitan duplicarse aquí
    }

    fun BleConnectionStatus.isErrorStatus(): Boolean {
        return this.name.startsWith("ERROR_")
    }

    // Nueva función para limpiar datos específicos del wearable
    private fun clearWearableBleDataStates() {
        Log.d("StateClear", "Limpiando estados de datos BLE del Wearable en ViewModel.")
        _latestWearableSteps.value = null
        // Otros estados específicos del wearable si los hubiera
    }

    /**
     * Función centralizada para verificar todos los requisitos de Bluetooth (adaptador encendido,
     * permisos concedidos, servicios de ubicación activados si es necesario) y actualizar
     * el estado `_isBleReady` y el mensaje de UI `_uiBluetoothMessage` en consecuencia.
     * Esta función es llamada desde múltiples lugares para asegurar que el estado de la UI
     * refleje correctamente la preparación de BLE.
     */
    private fun checkBleRequirementsAndReadyState() {
        val context = application.applicationContext // Obtener contexto para verificar permisos.
        val isBtAdapterEnabled = bluetoothService.isBluetoothEnabled() // Verificar si el adaptador BT está encendido.
        // Verificar si todos los permisos de BT requeridos están concedidos.
        val hasBtPermissions = getRequiredBluetoothPermissions(context).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        // Verificar si el permiso de ubicación fina está concedido.
        val hasLocationPermission = ContextCompat.checkSelfPermission(context,
            ACCESS_FINE_LOCATION_STRING) == PackageManager.PERMISSION_GRANTED
        // Verificar si los servicios de ubicación del dispositivo están activos.
        val areLocationServicesEnabled = bluetoothService.isLocationEnabled() // Verifica el servicio de ubicación del dispositivo

        var overallUiMessage: String
        var blePrerequisitesOk = false // Indica si todos los prerrequisitos están OK.

        when {
            !isBtAdapterEnabled -> overallUiMessage = "Bluetooth desactivado. Actívalo."
            !hasBtPermissions -> {
                val missingBtPerms = getRequiredBluetoothPermissions(context).filterNot {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }.joinToString { it.substringAfterLast('.') }
                overallUiMessage = "Faltan permisos de Bluetooth: $missingBtPerms."
            }
            !hasLocationPermission -> overallUiMessage = "Falta permiso de Ubicación."
            !areLocationServicesEnabled -> overallUiMessage = "Servicios de Ubicación desactivados. Actívalos."
            else -> {
                blePrerequisitesOk = true
                // Ahora, con los prerrequisitos OK, el mensaje dependerá del estado de AMBOS dispositivos
                // y del escaneo.
                val oximeterStatus = bluetoothService.oximeterConnectionStatus.value
                val wearableStatus = bluetoothService.wearableConnectionStatus.value
                val currentIsScanning = isScanning.value // isScanning del ViewModel, que viene del servicio

                val oximeterName = connectedOximeterDeviceName.value // Nombre del pulsioxímetro conectado
                val wearableName = connectedWearableName.value // Nombre del wearable conectado

                if (currentIsScanning) {
                    overallUiMessage = "Escaneando dispositivos..."
                } else {
                    // Lógica para construir el mensaje basado en los estados individuales
                    val messages = mutableListOf<String>()
                    if (oximeterStatus == BleConnectionStatus.IDLE && wearableStatus == BleConnectionStatus.IDLE) {
                        messages.add("Listo para escanear.")
                    }

                    if (messages.isEmpty()) {
                        // Si ambos están conectados y listos, o si uno está conectado y el otro no
                        // y no estamos escaneando.
                        if (oximeterStatus == BleConnectionStatus.SUBSCRIBED && wearableStatus == BleConnectionStatus.SUBSCRIBED) {
                            overallUiMessage = "Ambos dispositivos listos."
                        } else if (oximeterStatus == BleConnectionStatus.SUBSCRIBED) {
                            overallUiMessage = "Pulsioxímetro listo. Conecte el acelerómetro."
                        } else if (wearableStatus == BleConnectionStatus.SUBSCRIBED) {
                            overallUiMessage = "Acelerómetro listo. Conecte el pulsioxímetro."
                        }
                        else {
                            overallUiMessage = "Listo para conectar dispositivos."
                        }
                    } else {
                        overallUiMessage = messages.joinToString(" ")
                    }
                }
            }
        }

        if (_isBleReady.value != blePrerequisitesOk) {
            _isBleReady.value = blePrerequisitesOk
            Log.d(TAG_VM, "_isBleReady (prerrequisitos) actualizado a: $blePrerequisitesOk")
        }
        if (_uiBluetoothMessage.value != overallUiMessage) {
            _uiBluetoothMessage.value = overallUiMessage
            Log.d(TAG_VM, "Mensaje UI Bluetooth actualizado a: '$overallUiMessage'")
        }
        _combinedCanStartTestConditions.value = calculateCanStartTest()
    }

    /**
     * Inicia el proceso de verificación de requisitos BLE de forma secuencial.
     * Si falta algún requisito (Bluetooth desactivado, permisos, ubicación desactivada),
     * emite un evento para que la UI solicite la acción correspondiente al usuario.
     * Si todos los requisitos están cumplidos, inicia el escaneo de dispositivos BLE
     * si el estado actual lo permite (IDLE, DISCONNECTED, ERROR o ya SCANNING).
     */
    fun startBleProcessOrRequestPermissions() {
        Log.d(TAG, "startBleProcessOrRequestPermissions: Iniciando verificación secuencial...")
        val context = application.applicationContext

        // Paso 1: Verificar si el adaptador Bluetooth está encendido.
        if (!bluetoothService.isBluetoothEnabled()) {
            Log.w(TAG, "Bluetooth desactivado. Emitiendo _requestEnableBluetoothEvent.")
            _uiBluetoothMessage.value =
                "Bluetooth desactivado. Pulsa para activar." // Mensaje accionable para la UI.
            viewModelScope.launch { _requestEnableBluetoothEvent.emit(Unit) } // Evento para solicitar activación.
            checkBleRequirementsAndReadyState() // Actualizar estado general y mensaje.
            return // Salir, ya que este es el primer requisito.
        }
        Log.d(TAG, "Paso 1: Bluetooth está ACTIVADO.")

        // Paso 2: Verificar Permisos de Bluetooth.
        val requiredBtPerms = getRequiredBluetoothPermissions(context)
        val missingBtPerms = requiredBtPerms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingBtPerms.isNotEmpty()) {
            val missingPermsString = missingBtPerms.joinToString { it.substringAfterLast('.') }
            Log.w(
                TAG,
                "Faltan permisos de Bluetooth: [$missingPermsString]. Emitiendo _requestPermissionsEvent."
            )
            _uiBluetoothMessage.value =
                "Faltan permisos Bluetooth: $missingPermsString. Pulsa para conceder."
            viewModelScope.launch { _requestPermissionsEvent.emit(missingBtPerms.toTypedArray()) } // Evento para solicitar permisos.
            checkBleRequirementsAndReadyState()
            return // Salir, esperar resultado de permisos.
        }
        Log.d(TAG, "Paso 2: Permisos de Bluetooth CONCEDIDOS.")

        // Paso 3: Verificar Permiso de Ubicación Fina (ACCESS_FINE_LOCATION).
        // Aunque ya está incluido en `requiredBtPerms` para versiones < S,
        // una verificación explícita aquí es buena práctica y cubre casos donde `getRequiredBluetoothPermissions`
        // podría no incluirlo para S+ si se usa "neverForLocation" pero la app aún lo necesita.
        if (ContextCompat.checkSelfPermission(
                context,
                ACCESS_FINE_LOCATION_STRING
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Falta permiso ACCESS_FINE_LOCATION. Emitiendo _requestPermissionsEvent.")
            _uiBluetoothMessage.value = "Falta permiso de Ubicación. Pulsa para conceder."
            viewModelScope.launch {
                _requestPermissionsEvent.emit(
                    arrayOf(
                        ACCESS_FINE_LOCATION_STRING
                    )
                )
            }
            checkBleRequirementsAndReadyState()
            return // Salir, esperar resultado de permisos.
        }
        Log.d(TAG, "Paso 3: Permiso de Ubicación (ACCESS_FINE_LOCATION) CONCEDIDO.")

        // Paso 4: Verificar si los servicios de Ubicación del dispositivo están activos.
        if (!bluetoothService.isLocationEnabled()) {
            Log.w(
                TAG,
                "Servicios de ubicación del dispositivo desactivados. Emitiendo _requestLocationServicesEvent."
            )
            _uiBluetoothMessage.value = "Servicios de Ubicación desactivados. Pulsa para activar."
            viewModelScope.launch { _requestLocationServicesEvent.emit(Unit) } // Evento para ir a ajustes de ubicación.
            checkBleRequirementsAndReadyState()
            return // Salir, esperar que el usuario active la ubicación.
        }
        Log.d(TAG, "Paso 4: Servicios de Ubicación del dispositivo ACTIVADOS.")

        // Si todos los pasos anteriores se cumplen, BLE está listo para operar.
        Log.i(TAG, "Todos los requisitos BLE están OK. Intentando iniciar escaneo.")
        _isBleReady.value = true // Confirmar que todos los prerrequisitos están listos.

        // Obtener los estados actuales para tomar la decisión de escanear.
        val currentOximeterStatus = bluetoothService.oximeterConnectionStatus.value
        val currentWearableStatus = bluetoothService.wearableConnectionStatus.value
        val currentlyScanning = isScanning.value // Usar el StateFlow del ViewModel

        // Condición para iniciar/reiniciar un escaneo:
        // - No estamos ya conectados y suscritos a AMBOS dispositivos.
        // - O, si ya estamos escaneando, permitir "refrescar" el escaneo.
        // - O, si alguno está en un estado de error o desconectado, permitir escanear.

        val shouldStartScan: Boolean

        // Si ya está escaneando, permitimos que se "reinicie" el escaneo (limpiar lista y volver a escanear).
        if (currentlyScanning) {
            Log.d(
                TAG,
                "Ya se está escaneando. Se procederá a limpiar dispositivos y reiniciar el escaneo."
            )
            shouldStartScan = true
        } else {
            // Si no está escaneando, evaluamos los estados de los dispositivos.
            // Escanear si al menos uno no está "listo" (SUBSCRIBED)
            // o si está en un estado que justifique un nuevo escaneo (IDLE, DISCONNECTED, ERROR).
            val oximeterNeedsScan = currentOximeterStatus == BleConnectionStatus.IDLE ||
                    currentOximeterStatus.name.startsWith("DISCONNECTED_") ||
                    currentOximeterStatus.name.startsWith("ERROR_") ||
                    currentOximeterStatus != BleConnectionStatus.SUBSCRIBED // También si no está suscrito

            val wearableNeedsScan = currentWearableStatus == BleConnectionStatus.IDLE ||
                    currentWearableStatus.name.startsWith("DISCONNECTED_") ||
                    currentWearableStatus.name.startsWith("ERROR_") ||
                    currentWearableStatus != BleConnectionStatus.SUBSCRIBED // También si no está suscrito

            // Queremos escanear si CUALQUIERA de los dispositivos aún no está listo y necesita ser encontrado,
            // O si ambos están en un estado inicial (IDLE)
            // No queremos escanear si AMBOS ya están SUBSCRIBED.
            if (currentOximeterStatus == BleConnectionStatus.SUBSCRIBED && currentWearableStatus == BleConnectionStatus.SUBSCRIBED) {
                Log.d(TAG, "Ambos dispositivos ya están suscritos. No se inicia nuevo escaneo.")
                shouldStartScan = false
            } else {
                Log.d(
                    TAG,
                    "Al menos un dispositivo no está suscrito o está en estado IDLE/ERROR/DISCONNECTED. Se iniciará escaneo."
                )
                shouldStartScan = true
            }
        }

        if (shouldStartScan) {
            // Limpiar la lista de dispositivos escaneados antes de un nuevo escaneo,
            // excepto si ya se está escaneando (ya se limpió o se está refrescando).
            // Si currentlyScanning es true, startScan() debería manejar la limpieza si es necesario,
            // o podemos hacerlo explícitamente aquí.
            // La implementación de startScan en BluetoothServiceImpl ya limpia los dispositivos.
            // BluetoothServiceImpl.startScan() -> clearScannedDevicesInternal()
            Log.d(TAG, "Solicitando inicio de escaneo al servicio.")
            bluetoothService.startScan() // Iniciar el escaneo a través del servicio.
        } else {
            Log.d(
                TAG, "No se inicia escaneo. " +
                        "OxiStatus: $currentOximeterStatus, " +
                        "WearStatus: $currentWearableStatus, " +
                        "Scanning: $currentlyScanning"
            )
        }

        checkBleRequirementsAndReadyState() // Actualizar el mensaje de UI.
    }

    /**
     * Callback que se invoca cuando se recibe el resultado de una solicitud de permisos.
     * @param grantedPermissionsMap Mapa donde la clave es el nombre del permiso y el valor es un booleano
     * indicando si fue concedido.
     */
    fun onPermissionsResult(grantedPermissionsMap: Map<String, Boolean>) {
        Log.d(TAG, "Resultado de permisos recibido: $grantedPermissionsMap")
        val allGranted = grantedPermissionsMap.values.all { it } // Verificar si todos los permisos solicitados fueron concedidos.
        if (allGranted) {
            Log.d(TAG, "Todos los permisos solicitados fueron concedidos. Continuando proceso...")
            startBleProcessOrRequestPermissions() // Reintentar el proceso BLE, ya que ahora podrían cumplirse los requisitos.
        } else {
            Log.w(TAG, "Algunos permisos fueron denegados. Actualizando UI.")
            checkBleRequirementsAndReadyState() // Actualizar el mensaje de UI para reflejar los permisos faltantes.
        }
    }

    /**
     * Callback que se invoca cuando se recibe el resultado de la solicitud de activación de Bluetooth.
     * @param isEnabled `true` si el usuario activó Bluetooth, `false` en caso contrario.
     */
    fun onBluetoothEnabledResult(isEnabled: Boolean) {
        Log.d(TAG, "Resultado de activación de Bluetooth: $isEnabled")
        if (isEnabled) {
            Log.d(TAG, "Bluetooth activado por el usuario. Continuando proceso.")
            startBleProcessOrRequestPermissions() // Reintentar el proceso BLE.
        } else {
            Log.w(TAG, "Usuario no activó Bluetooth.")
            checkBleRequirementsAndReadyState() // Actualizar UI para reflejar que BT sigue desactivado.
        }
    }

    /**
     * Callback que se invoca cuando el usuario regresa de la pantalla de ajustes de servicios de ubicación.
     * No se recibe un resultado directo booleano, por lo que se reintenta el proceso.
     * @param areEnabled (No usado directamente, se asume que el usuario pudo haberlo activado).
     *                   Realmente, esta función se llama al regresar de la actividad de ajustes,
     *                   por lo que se debe volver a verificar el estado.
     */
    fun onLocationServicesEnabledResult(areEnabled: Boolean) { // El parámetro `areEnabled` no es fiable aquí.
        Log.d(TAG, "Usuario regresó de ajustes de ubicación. Re-verificando estado.")
        startBleProcessOrRequestPermissions() // Reintentar el proceso BLE, que verificará de nuevo los servicios de ubicación.
    }

    /**
     * Solicita al `BluetoothService` que detenga el escaneo de dispositivos BLE.
     */
    fun stopBleScan() {
        Log.i(TAG, "Solicitando parada de escaneo BLE al servicio.")
        bluetoothService.stopScan()
        // `checkBleRequirementsAndReadyState` se llamará indirectamente si el estado de `isScanning` cambia.
    }

    /**
     * Solicita al `BluetoothService` que se conecte a un dispositivo BLE escaneado específico.
     * Antes de intentar conectar, verifica si los prerrequisitos de BLE (`_isBleReady`) están cumplidos.
     * Si no, guía al usuario a través de los pasos faltantes llamando a `startBleProcessOrRequestPermissions()`.
     *
     * @param device El dispositivo escaneado (`ServiceUiScannedDevice`) al que se desea conectar.
     */
    fun connectToScannedDevice(device: ServiceUiScannedDevice) {
        Log.d(TAG_VM, "VM: Solicitando conexión a ${device.deviceName ?: device.address} de tipo ${device.category}.")
        // _isBleReady ahora implica que todos los requisitos (permisos, BT activado, ubicación activada) están OK.
        if (!_isBleReady.value) {
            Log.w(TAG, "Intento de conexión pero _isBleReady es false. Iniciando proceso de verificación.")
            startBleProcessOrRequestPermissions() // Esto guiará al usuario a través de los pasos faltantes (activar BT, conceder permisos, etc.).
            return // No intentar conectar hasta que _isBleReady sea true.
        }

        // Detener el escaneo antes de conectar es una buena práctica
        if (isScanning.value) {
            bluetoothService.stopScan()
        }

        // Actualizar el StateFlow correspondiente para indicar a qué dispositivo se está conectando
        // y limpiar el otro si se estaba intentando una conexión previa a un tipo diferente.
        when (device.category) {
            DeviceCategory.OXIMETER -> {
                // Solo actualiza si es diferente o si el otro está seteado (para limpiarlo)
                if (_connectingOximeterAddress.value != device.address || _connectingWearableAddress.value != null) {
                    _connectingOximeterAddress.value = device.address
                    _connectingWearableAddress.value = null // Limpiar si se estaba conectando a un wearable
                }
            }
            DeviceCategory.WEARABLE -> {
                if (_connectingWearableAddress.value != device.address || _connectingOximeterAddress.value != null) {
                    _connectingWearableAddress.value = device.address
                    _connectingOximeterAddress.value = null // Limpiar si se estaba conectando a un oxímetro
                }
            }
            DeviceCategory.UNKNOWN -> {
                Log.e(TAG_VM, "VM: No se puede conectar a un dispositivo de categoría UNKNOWN.")
                // _userMessages.tryEmit("No se puede conectar al dispositivo: Tipo desconocido.")
                return // No intentar conectar si la categoría es UNKNOWN
            }
        }

        Log.d(TAG_VM, "VM: Llamando a bluetoothService.connect con Address: ${device.address}, Category: ${device.category}")
        bluetoothService.connect(device.address, device.category)
    }

    /**
     * Solicita al `BluetoothService` que se desconecte del dispositivo BLE actualmente conectado.
     */
    fun disconnectOximeter() {
        Log.d(TAG_VM, "VM: Solicitando desconexión del pulsioxímetro.")
        bluetoothService.connectedOximeter.value?.let {
            bluetoothService.disconnect(it.address)
        }
    }

    fun disconnectWearable() {
        Log.d(TAG_VM, "VM: Solicitando desconexión del acelerómetro.")
        bluetoothService.connectedWearable.value?.let {
            bluetoothService.disconnect(it.address)
        }
    }

    // Y mantenemos una general si es necesario
    fun disconnectAllDevices() {
        Log.d(TAG_VM, "VM: Solicitando desconexión de todos los dispositivos.")
        bluetoothService.disconnectAll() // Asumiendo que BluetoothService tiene este método
    }

    /**
     * Limpia los estados internos del ViewModel que almacenan los últimos datos recibidos del dispositivo BLE.
     * Se llama típicamente al desconectar o cuando ocurre un error.
     */
    private fun clearOximeterBleDataStates() {
        Log.d("StateClear", "Limpiando estados de datos BLE del Oximeter en ViewModel.")
        _latestBleSpo2.value = null
        _latestBleHeartRate.value = null
        _latestBleSignalStrength.value = null
        _latestBleNoFinger.value = true // Restablecer a 'no dedo detectado' como estado seguro por defecto.
        _latestBlePleth.value = null
        _latestBleBarGraph.value = null
    }

    /**
     * Intenta capturar los valores actuales de SpO2 y Frecuencia Cardíaca desde el dispositivo BLE conectado
     * y los asigna a los campos de entrada manual de basales (`_spo2Input`, `_heartRateInput`).
     * Realiza validaciones previas (BLE listo, suscrito, dedo detectado).
     */
    fun captureBasalFromBle() {
        Log.d("CaptureBasal", "Intentando capturar SpO2 y FC desde BLE. SpO2: ${_latestBleSpo2.value}, HR: ${_latestBleHeartRate.value}, NoFinger: ${_latestBleNoFinger.value}")

        val currentBleReady = _isBleReady.value
        val currentStatus = oximeterConnectionStatus.value

        // 1. Verificar si BLE está completamente listo (permisos, BT activado, ubicación).
        if (!currentBleReady) {
            _uiBluetoothMessage.value = "Dispositivo no preparado. Verifica Bluetooth, permisos y ubicación."
            Log.w("CaptureBasal", "Intento de captura fallido. isBleReady (comprensivo): $currentBleReady")
            startBleProcessOrRequestPermissions() // Intentar guiar al usuario para arreglar el estado.
            return
        }

        // 2. Verificar si el dispositivo está suscrito (listo para enviar datos).
        if (currentStatus != BleConnectionStatus.SUBSCRIBED) {
            _uiBluetoothMessage.value = "Dispositivo no suscrito o conexión perdida. No se pueden capturar datos."
            Log.w("CaptureBasal", "Intento de captura fallido. Status: $currentStatus (no SUBSCRIBED)")
            return
        }

        // 3. Verificar si el sensor detecta el dedo.
        // Se asume que `_latestBleNoFinger.value == true` significa que no hay dedo.
        if (_latestBleNoFinger.value == true) { // Explicitar `== true` ya que es Booleano nullable.
            _uiBluetoothMessage.value = "No se detecta el dedo en el sensor."
            Log.w("CaptureBasal", "Intento de captura con 'No Finger' activo.")
            return
        }

        // Si todas las verificaciones pasan, intentar capturar los datos.
        val spo2ToCapture = _latestBleSpo2.value
        val hrToCapture = _latestBleHeartRate.value
        var spo2Captured = false
        var hrCaptured = false

        // Capturar SpO2 si es válido y está dentro del rango de entrada permitido.
        if (spo2ToCapture != null && spo2ToCapture in inputSpo2Min..inputSpo2Max) {
            _spo2Input.value = spo2ToCapture.toString()
            onSpo2InputChange(spo2ToCapture.toString()) // Esto también llama a validaciones.
            spo2Captured = true
        } else {
            Log.w("CaptureBasal", "SpO2 inválido o nulo desde BLE: $spo2ToCapture")
        }

        // Capturar Frecuencia Cardíaca si es válida y está dentro del rango.
        if (hrToCapture != null && hrToCapture in inputHrMin..inputHrMax) {
            _heartRateInput.value = hrToCapture.toString()
            onHeartRateInputChange(hrToCapture.toString()) // Esto también llama a validaciones.
            hrCaptured = true
        } else {
            Log.w("CaptureBasal", "HR inválido ($hrToCapture) o nulo desde BLE. Rango ($inputHrMin-$inputHrMax)")
        }

        // Actualizar el mensaje de UI según qué datos se capturaron.
        if (spo2Captured && hrCaptured) {
            _uiBluetoothMessage.value = "SpO2 y FC capturados."
        } else if (spo2Captured) {
            _uiBluetoothMessage.value = "SpO2 capturado. FC no es válido/disponible."
        } else if (hrCaptured) {
            _uiBluetoothMessage.value = "FC capturada. SpO2 no es válido/disponible."
        } else {
            _uiBluetoothMessage.value = "SpO2 y FC no válidos o no disponibles."
        }
    }

    // --- Funciones de manejo de entrada y validación para valores basales ---

    /**
     * Se llama cuando cambia el valor del campo de entrada de SpO2.
     * Actualiza el `_spo2Input` y valida el nuevo valor.
     * @param newValue El nuevo valor de SpO2 como String.
     */
    fun onSpo2InputChange(newValue: String) {
        _spo2Input.value = newValue.filter { it.isDigit() } // Filtrar para aceptar solo dígitos.
        validateSpo2() // Validar el valor individual.
        validateAllBasalInputs() // Validar el conjunto de todos los basales.
    }

    /** Valida el valor de SpO2 introducido contra los rangos definidos. */
    private fun validateSpo2() {
        val spo2 = _spo2Input.value.toIntOrNull()
        _isValidSpo2.value = spo2 != null && spo2 in inputSpo2Min..inputSpo2Max
    }

    /**
     * Se llama cuando cambia el valor del campo de entrada de Frecuencia Cardíaca.
     * @param newValue El nuevo valor de FC como String.
     */
    fun onHeartRateInputChange(newValue: String) {
        _heartRateInput.value = newValue.filter { it.isDigit() }
        validateHeartRate()
        validateAllBasalInputs()
    }

    /** Valida el valor de Frecuencia Cardíaca introducido. */
    private fun validateHeartRate() {
        val hr = _heartRateInput.value.toIntOrNull()
        _isValidHeartRate.value = hr != null && hr in inputHrMin..inputHrMax
    }

    /**
     * Se llama cuando cambia el valor del campo de entrada de Presión Arterial.
     * @param newValue El nuevo valor de PA como String (ej. "120/80").
     */
    fun onBloodPressureInputChange(newValue: String) {
        _bloodPressureInput.value = newValue // No filtrar dígitos aquí, ya que contiene "/".
        validateBloodPressure()
        validateAllBasalInputs()
    }

    /**
     * Valida el valor de Presión Arterial introducido.
     * La entrada debe tener el formato "sistólica/diastólica".
     * Ambas deben ser números enteros dentro de sus rangos y la sistólica debe ser mayor que la diastólica.
     */
    private fun validateBloodPressure() {
        val input = _bloodPressureInput.value
        if (input.isBlank()) { // Considerar vacío como inválido para la validación de *este* campo,
            // pero validateAllBasalInputs manejará si el campo es requerido o no.
            _isValidBloodPressure.value = false // Si está vacío, no es una PA válida por sí misma.
            return
        }
        val parts = input.split("/")
        if (parts.size == 2) {
            val systolic = parts[0].trim().toIntOrNull()
            val diastolic = parts[1].trim().toIntOrNull()
            // Validar que ambos sean números, estén en rango y sistólica > diastólica.
            _isValidBloodPressure.value = systolic != null && diastolic != null &&
                    systolic in inputBpSysMin..inputBpSysMax &&
                    diastolic in inputBpDiaMin..inputBpDiaMax &&
                    systolic > diastolic
        } else {
            _isValidBloodPressure.value = false // Formato incorrecto.
        }
    }

    /**
     * Se llama cuando cambia el valor del campo de entrada de Frecuencia Respiratoria.
     * @param newValue El nuevo valor de FR como String.
     */
    fun onRespiratoryRateInputChange(newValue: String) {
        _respiratoryRateInput.value = newValue.filter { it.isDigit() }
        validateRespiratoryRate()
        validateAllBasalInputs()
    }

    /** Valida el valor de Frecuencia Respiratoria introducido. */
    private fun validateRespiratoryRate() {
        val rr = _respiratoryRateInput.value.toIntOrNull()
        _isValidRespiratoryRate.value = rr != null && rr in inputRrMin..inputRrMax
    }

    /**
     * Se llama cuando cambia el valor del campo de entrada de Disnea (Borg).
     * @param newValue El nuevo valor de Disnea como String.
     */
    fun onDyspneaBorgInputChange(newValue: String) {
        _dyspneaBorgInput.value = newValue.filter { it.isDigit() }
        validateDyspneaBorg()
        validateAllBasalInputs()
    }

    /** Valida el valor de Disnea (Borg) introducido. */
    private fun validateDyspneaBorg() {
        val borg = _dyspneaBorgInput.value.toIntOrNull()
        _isValidDyspneaBorg.value = borg != null && borg in inputBorgMin..inputBorgMax
    }

    /**
     * Se llama cuando cambia el valor del campo de entrada de Dolor en Piernas (Borg).
     * @param newValue El nuevo valor de Dolor en Piernas como String.
     */
    fun onLegPainBorgInputChange(newValue: String) {
        _legPainBorgInput.value = newValue.filter { it.isDigit() }
        validateLegPainBorg()
        validateAllBasalInputs()
    }

    /** Valida el valor de Dolor en Piernas (Borg) introducido. */
    private fun validateLegPainBorg() {
        val borg = _legPainBorgInput.value.toIntOrNull()
        _isValidLegPainBorg.value = borg != null && borg in inputBorgMin..inputBorgMax
    }

    /**
     * Valida si todos los campos de entrada de valores basales están completos y son válidos.
     * Actualiza `_areBasalsValid` y `_basalValuesStatusMessage` para la UI.
     * Es crucial para determinar si se puede iniciar el test.
     */
    private fun validateAllBasalInputs() {
        // Ejecutar primero las validaciones individuales para asegurar que los estados `_isValid...` estén actualizados.
        validateSpo2()
        validateHeartRate()
        validateBloodPressure() // Esta función ahora establece _isValidBloodPressure a false si está vacía.
        validateRespiratoryRate()
        validateDyspneaBorg()
        validateLegPainBorg()

        // Comprobar que todos los campos requeridos estén llenos Y que sus validaciones individuales sean `true`.
        val allRequiredValidAndFilled =
            _spo2Input.value.isNotBlank() && _isValidSpo2.value &&
                    _heartRateInput.value.isNotBlank() && _isValidHeartRate.value &&
                    _bloodPressureInput.value.isNotBlank() && _isValidBloodPressure.value && // Aquí, si BP está vacío, isValidBP puede ser true, pero isNotBlank será false.
                    _respiratoryRateInput.value.isNotBlank() && _isValidRespiratoryRate.value &&
                    _dyspneaBorgInput.value.isNotBlank() && _isValidDyspneaBorg.value &&
                    _legPainBorgInput.value.isNotBlank() && _isValidLegPainBorg.value

        _areBasalsValid.value = allRequiredValidAndFilled

        // Construir el mensaje de estado para la UI.
        if (_areBasalsValid.value) {
            _basalValuesStatusMessage.value = "Todos los valores basales son válidos."
        } else {
            val errors = mutableListOf<String>() // Lista para campos con errores de rango.
            // Mensajes de error usan las variables miembro de los rangos para mostrar los límites esperados.
            if (_spo2Input.value.isNotBlank() && !_isValidSpo2.value) {
                errors.add("SpO2 ($inputSpo2Min-$inputSpo2Max)")
            }
            if (_heartRateInput.value.isNotBlank() && !_isValidHeartRate.value) {
                errors.add("FC ($inputHrMin-$inputHrMax)")
            }
            if (_bloodPressureInput.value.isNotBlank() && !_isValidBloodPressure.value) {
                errors.add("TA (S: $inputBpSysMin-$inputBpSysMax, D: $inputBpDiaMin-$inputBpDiaMax, S>D)")
            }
            if (_respiratoryRateInput.value.isNotBlank() && !_isValidRespiratoryRate.value) {
                errors.add("FR ($inputRrMin-$inputRrMax)")
            }
            if (_dyspneaBorgInput.value.isNotBlank() && !_isValidDyspneaBorg.value) {
                errors.add("Disnea ($inputBorgMin-$inputBorgMax)")
            }
            if (_legPainBorgInput.value.isNotBlank() && !_isValidLegPainBorg.value) {
                errors.add("Dolor Piernas ($inputBorgMin-$inputBorgMax)")
            }

            val missingFields = mutableListOf<String>() // Lista para campos vacíos.
            if (_spo2Input.value.isBlank()) missingFields.add("SpO2")
            if (_heartRateInput.value.isBlank()) missingFields.add("FC")
            if (_bloodPressureInput.value.isBlank()) missingFields.add("TA")
            if (_respiratoryRateInput.value.isBlank()) missingFields.add("FR")
            if (_dyspneaBorgInput.value.isBlank()) missingFields.add("Disnea")
            if (_legPainBorgInput.value.isBlank()) missingFields.add("Dolor Piernas")

            var message = "" // Construir el mensaje final combinando campos faltantes y errores de validación.
            if (missingFields.isNotEmpty()) {
                message = "Complete: ${missingFields.joinToString(", ")}."
                if (errors.isNotEmpty()) {
                    message += " Inválidos: ${errors.joinToString(", ")}."
                }
            } else if (errors.isNotEmpty()) {
                message = "Valores inválidos en: ${errors.joinToString(", ")}."
            } else {
                // Mensaje genérico si no hay errores específicos ni campos faltantes pero _areBasalsValid es false
                // (esto podría ocurrir si hay alguna lógica de validación cruzada no implementada aquí).
                message = "Complete todos los campos basales." // Mensaje genérico si no hay errores específicos ni faltantes pero _areBasalsValid es false
            }
            _basalValuesStatusMessage.value = message
        }
        // Recalcular `canStartTest` ya que la validez de los basales ha cambiado.
        _combinedCanStartTestConditions.value = calculateCanStartTest() // Recalcular
    }

    // --- Funciones de Gestión de Estado de UI Adicional (Datos del Paciente) ---

    /**
     * Se llama cuando cambia la selección del sexo del paciente.
     * Actualiza `_patientSex`, `_internalPatientDetails`, recalcula la distancia teórica
     * y las condiciones para iniciar el test.
     * @param newSex El nuevo sexo seleccionado ("M" o "F").
     */
    fun onPatientSexChange(newSex: String) {
        _patientSex.value = newSex
        // Actualizar la copia interna de los detalles del paciente.
        _internalPatientDetails = _internalPatientDetails?.copy(sex = newSex)
            ?: PatientDetails( // O crear uno nuevo si _internalPatientDetails era null.
                id = _patientId.value ?: "",
                fullName = _patientFullName.value,
                sex = newSex,
                age = _internalPatientDetails?.age ?: 0, // Usar valores existentes si es posible.
                heightCm = _internalPatientDetails?.heightCm ?: 0,
                weightKg = _internalPatientDetails?.weightKg ?: 0,
                usesInhalers = _usesInhalers.value,
                usesOxygen = _usesOxygen.value
            )
        calculateTheoreticalDistance() // Recalcular con el nuevo sexo.
        calculateStrideLength()
        _combinedCanStartTestConditions.value = calculateCanStartTest() // Reevaluar si se puede iniciar.
    }

    /**
     * Se llama cuando cambia el valor del campo de edad del paciente.
     * @param newAgeString La nueva edad como String.
     */
    fun onPatientAgeChange(newAgeString: String) {
        val newAgeFiltered = newAgeString.filter { it.isDigit() } // Solo dígitos.
        _patientAge.value = newAgeFiltered
        val newAgeInt = newAgeFiltered.toIntOrNull() ?: 0 // Convertir a Int, o 0 si es inválido.

        _internalPatientDetails = _internalPatientDetails?.copy(age = newAgeInt)
            ?: PatientDetails(
                id = _patientId.value ?: "",
                fullName = _patientFullName.value,
                sex = _patientSex.value,
                age = newAgeInt,
                heightCm = _internalPatientDetails?.heightCm ?: 0,
                weightKg = _internalPatientDetails?.weightKg ?: 0,
                usesInhalers = _usesInhalers.value,
                usesOxygen = _usesOxygen.value
            )
        calculateTheoreticalDistance()
        calculateStrideLength()
        _combinedCanStartTestConditions.value = calculateCanStartTest()
    }

    /**
     * Se llama cuando cambia el valor del campo de altura del paciente.
     * @param newHeightString La nueva altura en cm como String.
     */
    fun onPatientHeightChange(newHeightString: String) {
        val newHeightFiltered = newHeightString.filter { it.isDigit() }
        _patientHeightCm.value = newHeightFiltered
        val newHeightInt = newHeightFiltered.toIntOrNull() ?: 0

        _internalPatientDetails = _internalPatientDetails?.copy(heightCm = newHeightInt)
            ?: PatientDetails(
                id = _patientId.value ?: "",
                fullName = _patientFullName.value,
                sex = _patientSex.value,
                age = _internalPatientDetails?.age ?: 0,
                heightCm = newHeightInt,
                weightKg = _internalPatientDetails?.weightKg ?: 0,
                usesInhalers = _usesInhalers.value,
                usesOxygen = _usesOxygen.value
            )
        calculateTheoreticalDistance()
        calculateStrideLength()
        _combinedCanStartTestConditions.value = calculateCanStartTest()
    }

    /**
     * Se llama cuando cambia el valor del campo de peso del paciente.
     * @param newWeightString El nuevo peso en kg como String.
     */
    fun onPatientWeightChange(newWeightString: String) {
        val newWeightFiltered = newWeightString.filter { it.isDigit() }
        _patientWeightKg.value = newWeightFiltered
        val newWeightInt = newWeightFiltered.toIntOrNull() ?: 0

        _internalPatientDetails = _internalPatientDetails?.copy(weightKg = newWeightInt)
            ?: PatientDetails(
                id = _patientId.value ?: "",
                fullName = _patientFullName.value,
                sex = _patientSex.value,
                age = _internalPatientDetails?.age ?: 0,
                heightCm = _internalPatientDetails?.heightCm ?: 0,
                weightKg = newWeightInt,
                usesInhalers = _usesInhalers.value,
                usesOxygen = _usesOxygen.value
            )
        _combinedCanStartTestConditions.value = calculateCanStartTest()
        calculateTheoreticalDistance()
        calculateStrideLength()
    }

    /**
     * Se llama cuando cambia el valor del campo de peso del paciente.
     * @param newWeightString El nuevo peso en kg como String.
     */
    fun onUsesInhalersChange(newValue: Boolean) {
        _usesInhalers.value = newValue
        _internalPatientDetails = _internalPatientDetails?.copy(usesInhalers = newValue)
            ?: PatientDetails(
                id = _patientId.value ?: "",
                fullName = _patientFullName.value,
                sex = _patientSex.value,
                age = _internalPatientDetails?.age ?: 0,
                heightCm = _internalPatientDetails?.heightCm ?: 0,
                weightKg = _internalPatientDetails?.weightKg ?: 0,
                usesInhalers = newValue,
                usesOxygen = _usesOxygen.value // Mantener el valor actual de 'usesOxygen'.
            )
        // No necesita recalcular distancia teórica ni canStartTest ya que no depende de esto.
    }

    /**
     * Se llama cuando cambia el estado del toggle "Usa Oxígeno".
     * @param newValue El nuevo estado booleano.
     */
    fun onUsesOxygenChange(newValue: Boolean) {
        _usesOxygen.value = newValue
        _internalPatientDetails = _internalPatientDetails?.copy(usesOxygen = newValue)
            ?: PatientDetails(
                id = _patientId.value ?: "",
                fullName = _patientFullName.value,
                sex = _patientSex.value,
                age = _internalPatientDetails?.age ?: 0,
                heightCm = _internalPatientDetails?.heightCm ?: 0,
                weightKg = _internalPatientDetails?.weightKg ?: 0,
                usesInhalers = _usesInhalers.value, // Mantener el valor actual de 'usesInhalers'.
                usesOxygen = newValue
            )
        // No necesita recalcular distancia teórica ni canStartTest.
    }

    /**
     * Calcula la distancia teórica del test de marcha de 6 minutos basándose en los datos
     * demográficos del paciente (sexo, edad, altura, peso) usando fórmulas estándar.
     * Actualiza `_theoreticalDistance`. Si algún dato es inválido, la distancia será 0.
     */
    private fun calculateTheoreticalDistance() {
        val details = _internalPatientDetails // Usar la instancia interna actualizada.
        if (details == null) {
            Log.d("CalcDist", "_internalPatientDetails es null. Distancia a 0.")
            _theoreticalDistance.value = 0.0
            return
        }

        // Extraer los valores necesarios de `details`.
        val sex = details.sex
        val age = details.age
        val heightCm = details.heightCm
        val weightKg = details.weightKg
        Log.d("CalcDist", "Calculando con Sex: '$sex', Age: $age, Height: $heightCm, Weight: $weightKg")

        // Verificar que los datos necesarios para el cálculo sean válidos.
        if (age <= 0 || heightCm <= 0 || weightKg <= 0 || sex.isBlank()) {
            _theoreticalDistance.value = 0.0
            Log.d("CalcDist", "Uno o más valores (age, height, weight, sex) no son válidos para calcular. Distancia a 0.")
            return
        }

        // Aplicar la fórmula correspondiente según el sexo.
        // Fórmulas de Enright y Sherrill (1998)
        val distance = when (sex) { // Convertir a mayúsculas para ser insensible a 'm' vs 'M'.
            "M" -> (7.57 * heightCm) - (5.02 * age) - (1.76 * weightKg) - 309
            "F" -> (2.11 * heightCm) - (5.78 * age) - (2.29 * weightKg) + 667
            else -> {
                Log.w("CalcDist", "Sexo '$sex' no reconocido para fórmula. Distancia a 0.")
                0.0 // Si el sexo no es "M" o "F", la distancia es 0.
            }
        }
        // La distancia no puede ser negativa.
        _theoreticalDistance.value = if (distance > 0) distance else 0.0
        Log.d("CalcDist", "Distancia calculada: ${_theoreticalDistance.value}")
    }

    /**
     * Calcula la longitud de zancada estimada del paciente en metros utilizando la fórmula de Lee et al. (2025).
     * La fórmula original proporcionada es:
     * Paso (m) = (34.7 - 0.3 * edad + 0.76 * altura_cm - 0.15 * peso_kg - 3.33 * sexo_codificado) / 200
     * donde sexo_codificado es 1 para hombre y 0 para mujer.
     *
     * Actualiza `_calculatedStrideLengthMeters`.
     *
     * @return La longitud de zancada calculada en metros. Devuelve 0.0 si los datos son insuficientes o inválidos.
     */
    private fun calculateStrideLength(): Double {
        val details = _internalPatientDetails
        if (details == null || details.age <= 0 || details.heightCm <= 0 || details.weightKg <= 0 || details.sex.isBlank()) {
            _calculatedStrideLengthMeters.value = 0.0
            Log.d("StrideCalc", "Datos insuficientes para calcular longitud de zancada (edad, altura, peso, sexo).")
            return 0.0
        }

        val ageYears = details.age.toDouble()
        val heightCm = details.heightCm.toDouble()
        val weightKg = details.weightKg.toDouble()

        // Codificación del sexo: 0 para hombre ("M"), 1 para mujer ("F")
        val sexEncoded = when (details.sex.uppercase()) {
            "M" -> 0.0
            "F" -> 1.0
            else -> {
                Log.w("StrideCalc", "Sexo '${details.sex}' no reconocido para fórmula de Lee et al. Usando 0.0 (equivalente a mujer) como fallback o considerar inválido.")
                // Podrías decidir devolver 0.0 aquí si el sexo es inválido y crucial.
                // Por ahora, usamos 0.0, lo que podría no ser ideal si el sexo es desconocido.
                // Una mejor opción podría ser no calcular si el sexo no es M o F.
                _calculatedStrideLengthMeters.value = 0.0
                return 0.0 // Salir si el sexo no es M o F
            }
        }

        // Aplicar la fórmula de Lee et al. (2025)
        // Paso (m) = (34.7 - 0.3 * edad + 0.76 * altura - 0.15 *peso - 3.33 * sexo_codificado) / (100*2)
        val numerator = 34.7 - (0.3 * ageYears) + (0.76 * heightCm) - (0.15 * weightKg) - (3.33 * sexEncoded)
        val stepLengthMeters = numerator / 200.0

        // Una longitud de paso negativa no tiene sentido físico, podría ocurrir si los parámetros están muy fuera de lo común
        // o si la fórmula no se ajusta bien a un individuo particular.
        if (stepLengthMeters <= 0) {
            _calculatedStrideLengthMeters.value = 0.0
            Log.w("StrideCalc", "Longitud de zancada calculada no positiva ($stepLengthMeters m) con fórmula de Lee et al. Estableciendo a 0.0. Numerador: $numerator")
            return 0.0
        }

        _calculatedStrideLengthMeters.value = stepLengthMeters
        Log.d("StrideCalc", "Longitud de zancada calculada (Lee et al.): $stepLengthMeters m (Edad: $ageYears, Altura: $heightCm cm, Peso: $weightKg kg, SexoEnc: $sexEncoded, Numerador: $numerator)")
        return stepLengthMeters
    }

    /**
     * Se llama cuando el usuario cambia el estado del toggle "Dispositivo Colocado".
     * @param isNowPlaced `true` si el dispositivo se considera colocado, `false` en caso contrario.
     */
    fun onDevicePlacedToggle(isNowPlaced: Boolean) {
        _isDevicePlaced.value = isNowPlaced
        // Si se desmarca "Dispositivo Colocado", resetear la ubicación de colocación a NONE.
        if (!isNowPlaced) {
            _devicePlacementLocation.value = DevicePlacementLocation.NONE
        }
        _combinedCanStartTestConditions.value = calculateCanStartTest() // Reevaluar si se puede iniciar.
    }

    fun onAccelerometerPlacedToggle(isNowPlaced: Boolean) {
        _isAccelerometerPlaced.value = isNowPlaced
        if (!isNowPlaced) {
            _accelerometerPlacementLocation.value = AccelerometerPlacementLocation.NONE
        }
        // _combinedCanStartTestConditions.value = calculateCanStartTest() // Ya se hace por el listener
    }

    /**
     * Se llama cuando el usuario selecciona una ubicación para la colocación del dispositivo (ej. Dedo, Oreja).
     * @param location La `DevicePlacementLocation` seleccionada.
     */
    fun onDevicePlacementLocationSelected(location: DevicePlacementLocation) {
        _devicePlacementLocation.value = location
        // Si se selecciona una ubicación válida (no NONE), marcar `_isDevicePlaced` como true.
        _isDevicePlaced.value = location != DevicePlacementLocation.NONE
        _combinedCanStartTestConditions.value = calculateCanStartTest() // Reevaluar si se puede iniciar.
    }

    fun onAccelerometerPlacementLocationSelected(location: AccelerometerPlacementLocation) {
        _accelerometerPlacementLocation.value = location
        _isAccelerometerPlaced.value = location != AccelerometerPlacementLocation.NONE
        // _combinedCanStartTestConditions.value = calculateCanStartTest() // Ya se hace por el listener
    }

    /**
     * Confirma la acción de navegar hacia atrás. Oculta el diálogo de confirmación,
     * desconecta el Bluetooth si es necesario, y emite el evento para navegar.
     */
    fun confirmNavigateBack() {
        Log.d(TAG_VM, "confirmNavigateBack() llamado.")
        _showNavigateBackDialog.value = false
        bluetoothService.disconnectAll()
        bluetoothService.stopScan()
        viewModelScope.launch {
            _navigateBackEvent.emit(Unit)
        }
    }

    /** Cancela la acción de navegar hacia atrás y oculta el diálogo de confirmación. */
    fun cancelNavigateBack() {
        _showNavigateBackDialog.value = false
    }

    /** Muestra el diálogo de confirmación para navegar hacia atrás. */
    fun requestNavigateBack() {
        _showNavigateBackDialog.value = true
    }

    /**
     * Se llama cuando el usuario hace clic en el botón "Iniciar Test".
     * Primero, intenta asegurar que todos los requisitos de BLE estén cumplidos.
     * Luego, verifica si `canStartTest.value` es `true`.
     * Si es así, recopila todos los datos de preparación y emite un evento para navegar
     * a la pantalla de ejecución del test.
     * Si no, muestra un mensaje de error detallando los requisitos faltantes.
     */
    fun onStartTestClicked() {
        // Asegurar que los prerrequisitos BLE estén listos o solicitar al usuario.
        startBleProcessOrRequestPermissions() // Esto puede ser asíncrono y requerir interacción del usuario.

        viewModelScope.launch {
            kotlinx.coroutines.delay(100) // Pequeño delay para permitir que los estados (ej. _isBleReady) se propaguen
            // después de `startBleProcessOrRequestPermissions`.

            // Reevaluar `canStartTest` aquí, ya que el estado podría haber cambiado.
            if (!canStartTest.value) { // Usar el `canStartTest` que ya se actualiza dinámicamente.
                var errorMessage = "No se puede iniciar el test. Requisitos incompletos: "
                val missing = mutableListOf<String>()

                // Re-verificar condiciones aquí para dar feedback específico si startBleProcessOrRequestPermissions no fue suficiente
                // 1. Requisitos básicos de BLE (permisos, Bluetooth activado, ubicación).
                if (!_isBleReady.value) {
                    // Usar el mensaje ya generado por `checkBleRequirementsAndReadyState` si es más específico.
                    missing.add(_uiBluetoothMessage.value ?: "Bluetooth/Permisos no listos")
                }
                // PULSIOXÍMETRO
                // 2. Conexión y suscripción al pulsioxímetro.
                else if (bluetoothService.oximeterConnectionStatus.value != BleConnectionStatus.SUBSCRIBED) {
                    missing.add("Dispositivo no conectado y suscrito (${bluetoothService.oximeterConnectionStatus.value.name})")
                }

                // 3. Detección de dedo en el sensor (solo si está suscrito).
                if (_latestBleNoFinger.value == true && bluetoothService.oximeterConnectionStatus.value == BleConnectionStatus.SUBSCRIBED) {
                    missing.add("Sensor pulsioxímetro no detecta el dedo")
                }

                // 4. Confirmación de colocación del dispositivo.
                if (!_isDevicePlaced.value || _devicePlacementLocation.value == DevicePlacementLocation.NONE) {
                    missing.add("Colocación del dispositivo no confirmada")
                }

                // ACELERÓMETRO
                // 5. Conexión y suscripción al acelerómetro.
                if (bluetoothService.wearableConnectionStatus.value != BleConnectionStatus.SUBSCRIBED) {
                    missing.add("Acelerómetro no conectado (${bluetoothService.wearableConnectionStatus.value.name})")
                }

                // 6. Confirmación de colocación del acelerómetro.
                if (!_isAccelerometerPlaced.value || _accelerometerPlacementLocation.value == AccelerometerPlacementLocation.NONE) {
                    missing.add("Colocación del acelerómetro no confirmada")
                }

                // 7. Validez de los datos basales.
                if (!_areBasalsValid.value)  {
                    missing.add("Valores basales inválidos o incompletos (${_basalValuesStatusMessage.value})")
                }

                // 8. Validación de la información del paciente.
                val patientDetails = _internalPatientDetails // Capturar una vez para consistencia.
                if (patientDetails == null ||
                    patientDetails.id.isBlank() ||
                    patientDetails.fullName.isBlank() ||
                    patientDetails.sex.isBlank() ||
                    patientDetails.age <= 0 ||
                    patientDetails.heightCm <= 0 ||
                    patientDetails.weightKg <= 0) {
                    missing.add("Información del paciente incompleta")
                }

                // Construir y mostrar el mensaje de error.
                if (missing.isNotEmpty()) {
                    errorMessage += missing.joinToString("; ")
                    // No sobreescribir _uiBluetoothMessage si ya tiene un mensaje de `startBleProcessOrRequestPermissions`
                    // que indica un problema fundamental de BLE.
                    // Priorizar el primer error encontrado en la lista `missing` para un mensaje más conciso si BLE ya está "listo".
                    if (_isBleReady.value && missing.firstOrNull() != (_uiBluetoothMessage.value ?: "Bluetooth, permisos o ubicación no listos")) {
                        _uiBluetoothMessage.value = "No se puede iniciar: ${missing.firstOrNull()}"
                    } else if (!_isBleReady.value && _uiBluetoothMessage.value?.contains("No se puede iniciar") != true) {
                        // Si BLE no está listo, el mensaje de _uiBluetoothMessage ya es relevante.
                        // Solo se añade "No se puede iniciar:" si no está ya.
                        _uiBluetoothMessage.value = "No se puede iniciar: ${_uiBluetoothMessage.value}"
                    }
                } else if (_isBleReady.value) { // Si _isBleReady es true pero canStartTest sigue siendo false por otra razón.
                    _uiBluetoothMessage.value = "Verifique todos los campos y la conexión del dispositivo."
                }
                Log.w("StartTest", "Intento de iniciar test fallido. CanStartTest: ${canStartTest.value}. Detalles: $errorMessage. Mensaje UI actual: ${_uiBluetoothMessage.value}")
                return@launch // Salir de la coroutine.
            }

            // --- Si `canStartTest.value` es TRUE, proceder a recopilar datos y navegar ---
            Log.i("StartTest", "Todos los requisitos cumplidos. `canStartTest` es true. Preparando datos para navegación.")

            // Asegurarse de que los valores no sean nulos (ya validados por `canStartTest`).
            // Se usan los `!!` (operador de aserción no nula) porque `canStartTest` asegura que estos valores son válidos y no nulos/vacíos.
            val patientDetails = _internalPatientDetails!!
            val basalSpo2 = _spo2Input.value.toIntOrNull()!!
            val basalHeartRate = _heartRateInput.value.toIntOrNull()!!
            val bpParts = _bloodPressureInput.value.split("/")
            val basalSystolic = bpParts.getOrNull(0)?.trim()?.toIntOrNull()!!
            val basalDiastolic = bpParts.getOrNull(1)?.trim()?.toIntOrNull()!!
            val basalRespiratoryRate = _respiratoryRateInput.value.toIntOrNull()!!
            val basalDyspneaBorg = _dyspneaBorgInput.value.toIntOrNull()!!
            val basalLegPainBorg = _legPainBorgInput.value.toIntOrNull()!!
            val currentStrideLength = calculateStrideLength()

            // Crear el objeto `TestPreparationData` con todos los datos recopilados.
            val preparationData = TestPreparationData(
                patientId = patientDetails.id,
                patientFullName = patientDetails.fullName,
                patientSex = patientDetails.sex,
                patientAge = patientDetails.age,
                patientHeightCm = patientDetails.heightCm,
                patientWeightKg = patientDetails.weightKg,
                usesInhalers = patientDetails.usesInhalers,
                usesOxygen = patientDetails.usesOxygen,
                theoreticalDistance = _theoreticalDistance.value, // Tomar el valor actual del State<Double>.
                basalSpo2 = basalSpo2,
                basalHeartRate = basalHeartRate,
                basalBloodPressureSystolic = basalSystolic,
                basalBloodPressureDiastolic = basalDiastolic,
                basalRespiratoryRate = basalRespiratoryRate,
                basalDyspneaBorg = basalDyspneaBorg,
                basalLegPainBorg = basalLegPainBorg,
                devicePlacementLocation = _devicePlacementLocation.value.name, // Usar el nombre del enum.
                accelerometerPlacementLocation = _accelerometerPlacementLocation.value.name,
                isFirstTestForPatient = !_patientHasPreviousHistory.value, // Si no hay historial previo cargado
                strideLengthMeters = currentStrideLength
            )

            Log.i("StartTest", "Preparando para navegar a TestExecution con datos: $preparationData")
            _navigateToEvent.emit(PreparationNavigationEvent.ToTestExecution(preparationData)) // Emitir evento de navegación.
        }
    }

    /**
     * Se llama cuando el ViewModel está a punto de ser destruido.
     * Es el lugar para limpiar recursos, como la conexión Bluetooth si aún está activa,
     * o detener el escaneo si está en curso.
     */
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG_VM, "PreparationViewModel onCleared.")
        // Llama a un método en el servicio para desconectar todas las conexiones activas
        // y detener el escaneo si está en curso.
        bluetoothService.disconnectAll()
        bluetoothService.stopScan()
        Log.d(TAG_VM, "PreparationViewModel onCleared finalizado.")
    }
}
