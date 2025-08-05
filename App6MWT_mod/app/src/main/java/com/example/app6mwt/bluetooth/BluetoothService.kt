package com.example.app6mwt.bluetooth

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Define los posibles estados de la conexión Bluetooth Low Energy (BLE).
 * Estos estados ayudan a rastrear el ciclo de vida de la conexión y a
 * identificar problemas específicos.
 */
enum class BleConnectionStatus {
    /** Estado inicial, sin actividad BLE en curso. */
    IDLE,
    /** El servicio está actualmente escaneando dispositivos BLE cercanos. */
    SCANNING,
    /** Se está intentando establecer una conexión con un dispositivo BLE. */
    CONNECTING, // Podría ser a cualquiera de los dos
    /** Conexión establecida exitosamente con un dispositivo BLE. */
    CONNECTED, // Al menos un dispositivo conectado
    /** Suscrito exitosamente a las notificaciones de características del dispositivo conectado. */
    SUBSCRIBED, // Al menos un dispositivo suscrito
    /** El usuario ha iniciado la desconexión del dispositivo BLE. */
    DISCONNECTED_BY_USER,
    /** La conexión se ha perdido o terminado debido a un error. */
    DISCONNECTED_ERROR,
    /** Se está intentando reconectar automáticamente a un dispositivo previamente conectado. */
    RECONNECTING,
    /** Error debido a la falta de permisos Bluetooth necesarios. */
    ERROR_PERMISSIONS,
    /** Error debido a que el adaptador Bluetooth del dispositivo está desactivado. */
    ERROR_BLUETOOTH_DISABLED,
    /** Error al intentar conectar porque el dispositivo BLE no fue encontrado. */
    ERROR_DEVICE_NOT_FOUND,
    /** Error porque el servicio BLE requerido no fue encontrado en el dispositivo conectado. */
    ERROR_SERVICE_NOT_FOUND,
    /** Error porque la característica BLE requerida no fue encontrada en el servicio. */
    ERROR_CHARACTERISTIC_NOT_FOUND,
    /** Error al intentar suscribirse a las notificaciones de una característica. */
    ERROR_SUBSCRIBE_FAILED,
    /** Un error genérico de Bluetooth no especificado por otros estados. */
    ERROR_GENERIC
}

/**
 * Representa los datos de medición recibidos del pulsioxímetro vía BLE.
 *
 * @property spo2 El valor de saturación de oxígeno en sangre (%), nulo si no está disponible.
 * @property heartRate El valor de frecuencia cardíaca (latidos por minuto), nulo si no está disponible.
 * @property signalStrength La calidad de la señal del sensor (0-15), nulo si no está disponible.
 * @property noFingerDetected Indica si se detecta que no hay un dedo en el sensor.
 * @property plethValue El valor de la onda pletismográfica (0-127), nulo si no está disponible.
 * @property barGraphValue La intensidad visual del pulso (0-15), para gráficos de barras, nulo si no está disponible.
 * @property timestamp La marca de tiempo de cuándo se recibieron o generaron estos datos.
 */
data class BleDeviceData(
    val spo2: Int? = null,
    val heartRate: Int? = null,
    val signalStrength: Int? = null,
    val noFingerDetected: Boolean = true, // Por defecto, asumimos que no hay dedo hasta que se confirme
    val plethValue: Int? = null,
    val barGraphValue: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Representa los datos de distancia recibidos del wearable ESP32 vía BLE.
 *
 * @property totalSteps La distancia total acumulada, en la unidad que envíe el ESP32 (ej. metros, centímetros).
 *                         Nulo si no está disponible o la lectura es inválida.
 * @property timestamp La marca de tiempo de cuándo se recibieron o generaron estos datos.
 */
data class WearableDeviceData(
    val totalSteps: Int? = null,
    val timestamp: Long = 0L
)

/**
 * Identificador para los tipos de dispositivos BLE que la aplicación maneja.
 */
enum class DeviceCategory {
    OXIMETER,
    WEARABLE, // Para el acelerómetro/wearable
    UNKNOWN
}

/**
 * Representa un dispositivo Bluetooth escaneado, adaptado para la UI.
 *
 * @property deviceName El nombre publicitado del dispositivo BLE, puede ser nulo o "Desconocido".
 * @property address La dirección MAC única del dispositivo BLE.
 * @property rssi La fuerza de la señal recibida (RSSI) en dBm, nulo si no está disponible durante el escaneo.
 * @property rawDevice La instancia original de [BluetoothDevice] obtenida del framework de Android.
 */
data class UiScannedDevice(
    val deviceName: String?,
    val address: String,
    val rssi: Int?,
    val rawDevice: BluetoothDevice, // Se mantiene para poder reconectar o acceder a más detalles si es necesario
    val category: DeviceCategory
) {
    /**
     * Compara dos instancias de [UiScannedDevice] basándose únicamente en su dirección MAC.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UiScannedDevice
        return address == other.address
    }

    /**
     * Genera un código hash basado únicamente en la dirección MAC del dispositivo.
     */
    override fun hashCode(): Int = address.hashCode()
}

/**
 * Interfaz que define las operaciones y los flujos de datos para la gestión
 * de la conectividad Bluetooth Low Energy (BLE) con múltiples tipos de dispositivos.
 */
interface BluetoothService {
    /**
     * Flujo de estado que emite la lista actual de dispositivos BLE escaneados ([UiScannedDevice]).
     * La lista se actualiza a medida que se descubren nuevos dispositivos.
     */
    val scannedDevices: StateFlow<List<UiScannedDevice>>

    /**
     * Flujo de estado que indica si el servicio está actualmente escaneando dispositivos BLE.
     * `true` si el escaneo está activo, `false` en caso contrario.
     */
    val isScanning: StateFlow<Boolean>

    // --- Estados de Conexión y Datos del Pulsioxímetro (BM1000) ---
    /**
     * Flujo de estado que emite el estado actual de la conexión BLE con el pulsioxímetro.
     * Permite a los observadores reaccionar a cambios en la conexión específica del pulsioxímetro.
     */
    val oximeterConnectionStatus: StateFlow<BleConnectionStatus>

    /**
     * Flujo de estado que emite el pulsioxímetro BLE actualmente conectado ([UiScannedDevice]).
     * Emite `null` si no hay ningún pulsioxímetro conectado.
     */
    val connectedOximeter: StateFlow<UiScannedDevice?>

    /**
     * Flujo de estado que emite los últimos datos de medición recibidos del pulsioxímetro conectado ([BleDeviceData]).
     */
    val oximeterDeviceData: StateFlow<BleDeviceData>

    /**
     * Flujo de estado que emite la dirección MAC del último pulsioxímetro al que se conectó exitosamente
     * y se suscribió a sus características.
     */
    val lastKnownOximeterAddress: StateFlow<String?>

    // --- Estados de Conexión y Datos del Wearable (ESP32) ---
    /**
     * Flujo de estado que emite el estado actual de la conexión BLE con el wearable ESP32.
     */
    val wearableConnectionStatus: StateFlow<BleConnectionStatus> // NUEVO

    /**
     * Flujo de estado que emite el wearable ESP32 BLE actualmente conectado ([UiScannedDevice]).
     * Emite `null` si no hay ningún wearable conectado.
     */
    val connectedWearable: StateFlow<UiScannedDevice?> // NUEVO

    /**
     * Flujo de estado que emite los últimos datos de distancia recibidos del wearable ESP32 conectado ([WearableDeviceData]).
     */
    val wearableDeviceData: StateFlow<WearableDeviceData> // NUEVO

    /**
     * Flujo de estado que emite la dirección MAC del último wearable al que se conectó exitosamente
     * y se suscribió a sus características.
     */
    val lastKnownWearableAddress: StateFlow<String?> // NUEVO

    // --- General ---
    /**
     * Flujo compartido que emite mensajes de error o informativos generados por el servicio.
     * Podría necesitar prefijos para indicar a qué dispositivo se refiere un mensaje.
     */
    val errorMessages: SharedFlow<String>

    /**
     * Flujo de estado que indica si el servicio Bluetooth está listo para operar en general
     * (adaptador encendido, permisos, ubicación).
     */
    val isServiceReady: StateFlow<Boolean>

    /**
     * Devuelve un array de strings que representan los permisos de Bluetooth
     * requeridos por la aplicación, variando según la versión de Android del dispositivo.
     *
     * @return Array de [Manifest.permission] strings.
     */
    fun getRequiredBluetoothPermissionsArray(): Array<String>

    /**
     * Verifica si la aplicación tiene todos los permisos Bluetooth necesarios concedidos.
     *
     * @return `true` si todos los permisos requeridos están concedidos, `false` en caso contrario.
     */
    fun hasRequiredBluetoothPermissions(): Boolean

    /**
     * Verifica si el adaptador Bluetooth del dispositivo está actualmente habilitado.
     * Utiliza un estado interno reactivo para mayor precisión.
     *
     * @return `true` si el Bluetooth está encendido, `false` en caso contrario.
     */
    fun isBluetoothEnabled(): Boolean // Cambiado para reflejar la implementación que usa el StateFlow

    /**
     * Verifica si los servicios de ubicación del dispositivo están actualmente habilitados.
     * Esto es relevante para el escaneo BLE en algunas versiones de Android.
     *
     * @return `true` si la ubicación está activada, `false` en caso contrario.
     */
    fun isLocationEnabled(): Boolean

    /**
     * Inicia el proceso de escaneo de dispositivos BLE cercanos.
     * Los dispositivos encontrados se emitirán a través del flujo [scannedDevices].
     * Se realizarán comprobaciones de permisos y estado del adaptador Bluetooth antes de escanear.
     */
    fun startScan()

    /**
     * Detiene el proceso de escaneo de dispositivos BLE si está activo.
     */
    fun stopScan()

    /**
     * Intenta establecer una conexión con un dispositivo BLE específico mediante su dirección MAC.
     * El servicio intentará determinar el tipo de dispositivo (pulsioxímetro o wearable)
     * para manejar la conexión y suscripción a características adecuadamente.
     *
     * @param deviceAddress La dirección MAC del dispositivo al que se desea conectar.
     */
    fun connect(deviceAddress: String, deviceType: DeviceCategory)

    /**
     * Desconecta de un dispositivo BLE específico mediante su dirección MAC.
     * Si el dispositivo está conectado, se cerrará su conexión GATT.
     *
     * @param deviceAddress La dirección MAC del dispositivo que se desea desconectar.
     *                      Si es null, podría intentar desconectar todos los dispositivos (a definir).
     */
    fun disconnect(deviceAddress: String)

    /**
     * Desconecta todos los dispositivos BLE actualmente conectados.
     */
    fun disconnectAll() // NUEVO: Para mayor claridad y facilidad

    /**
     * Limpia la lista de dispositivos BLE escaneados previamente y almacenados en [scannedDevices].
     */
    fun clearScannedDevices()
}
