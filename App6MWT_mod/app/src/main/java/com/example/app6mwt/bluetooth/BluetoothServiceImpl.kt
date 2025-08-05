package com.example.app6mwt.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.location.LocationManager
import androidx.core.app.ActivityCompat
import com.example.app6mwt.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val applicationScope: CoroutineScope
) : BluetoothService {

    private val TAG = "BluetoothServiceImpl"

    // --- Adaptadores y Scanners Bluetooth (sin cambios directos para múltiples dispositivos) ---
    private val bluetoothManager: BluetoothManager? by lazy {
        try {
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        } catch (e: Exception) {
            Log.e(TAG, "Error al obtener BluetoothManager", e)
            null
        }
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }
    private var bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

    // --- Gestión de Múltiples Conexiones GATT ---
    /**
     * Mapa para almacenar las instancias de BluetoothGatt activas, usando la dirección MAC como clave.
     * ConcurrentHashMap es útil para acceso concurrente seguro.
     */
    private val gattConnections = ConcurrentHashMap<String, BluetoothGatt>()

    /**
     * Mapa para rastrear el tipo de dispositivo asociado a una dirección MAC una vez identificado.
     */
    private val deviceTypes = ConcurrentHashMap<String, DeviceCategory>()


    // --- StateFlows para el Pulsioxímetro ---
    private val _oximeterConnectionStatus = MutableStateFlow(BleConnectionStatus.IDLE)
    override val oximeterConnectionStatus: StateFlow<BleConnectionStatus> = _oximeterConnectionStatus.asStateFlow()

    private val _connectedOximeter = MutableStateFlow<UiScannedDevice?>(null)
    override val connectedOximeter: StateFlow<UiScannedDevice?> = _connectedOximeter.asStateFlow()

    private val _oximeterDeviceData = MutableStateFlow(BleDeviceData())
    override val oximeterDeviceData: StateFlow<BleDeviceData> = _oximeterDeviceData.asStateFlow()

    private val _lastKnownOximeterAddress = MutableStateFlow<String?>(null)
    override val lastKnownOximeterAddress: StateFlow<String?> = _lastKnownOximeterAddress.asStateFlow()

    // --- StateFlows para el Wearable (ESP32) ---
    private val _wearableConnectionStatus = MutableStateFlow(BleConnectionStatus.IDLE)
    override val wearableConnectionStatus: StateFlow<BleConnectionStatus> = _wearableConnectionStatus.asStateFlow()

    private val _connectedWearable = MutableStateFlow<UiScannedDevice?>(null)
    override val connectedWearable: StateFlow<UiScannedDevice?> = _connectedWearable.asStateFlow()

    private val _wearableDeviceData = MutableStateFlow(WearableDeviceData()) // Usar el nuevo data class
    override val wearableDeviceData: StateFlow<WearableDeviceData> = _wearableDeviceData.asStateFlow()

    private val _lastKnownWearableAddress = MutableStateFlow<String?>(null)
    override val lastKnownWearableAddress: StateFlow<String?> = _lastKnownWearableAddress.asStateFlow()

    // --- StateFlows Generales (pueden requerir adaptación o usarse con más cuidado) ---
    private val _scannedDevices = MutableStateFlow<List<UiScannedDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<UiScannedDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _errorMessages = MutableSharedFlow<String>()
    override val errorMessages: SharedFlow<String> = _errorMessages.asSharedFlow()

    private val foundDeviceAddresses = Collections.synchronizedSet(mutableSetOf<String>())

    // --- Lógica de Conexión y Reconexión (necesitará ser adaptada) ---
    // En lugar de un único currentConnectingDeviceAddress, podríamos necesitar un set o mapa
    // si permitimos intentos de conexión simultáneos a diferentes dispositivos.
    // Por simplicidad, empecemos asumiendo que solo se intenta conectar a un dispositivo a la vez,
    // o que 'connect' maneja un dispositivo a la vez.
    private val oximeterConnectingAddress = MutableStateFlow<String?>(null)
    private val wearableConnectingAddress = MutableStateFlow<String?>(null)
    private val disconnectsInitiatedByUser = Collections.synchronizedSet(mutableSetOf<String>())

    // La reconexión es más compleja con múltiples dispositivos.
    // Podríamos tener un mapa de Jobs de reconexión o una lógica más generalizada.
    // Por ahora, el mecanismo de reconexión simple que tenías se aplicaría al "último desconectado por error".
    // Esto es un área que podría necesitar más refinamiento.
    private var reconnectJob: Job? = null
    private val MAX_RECONNECT_ATTEMPTS = 1 // Podría ser por dispositivo
    private var reconnectAttemptsMap = ConcurrentHashMap<String, Int>()
    private var addressForCurrentReconnectAttempt: String? = null

    private val _isBluetoothAdapterEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val newAdapterState = when (state) {
                    BluetoothAdapter.STATE_ON -> true
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> false
                    else -> _isBluetoothAdapterEnabled.value
                }
                if (_isBluetoothAdapterEnabled.value != newAdapterState) {
                    Log.d(TAG, "Estado del adaptador Bluetooth cambiado a: ${if (newAdapterState) "ON" else "OFF"}")
                    _isBluetoothAdapterEnabled.value = newAdapterState
                    if (newAdapterState) {
                        // Si BT se enciende, resetear estados de error relacionados con BT apagado
                        // para ambos tipos de dispositivos.
                        if (_oximeterConnectionStatus.value == BleConnectionStatus.ERROR_BLUETOOTH_DISABLED) {
                            _oximeterConnectionStatus.value = if (hasRequiredBluetoothPermissions()) BleConnectionStatus.IDLE else BleConnectionStatus.ERROR_PERMISSIONS
                        }
                        if (_wearableConnectionStatus.value == BleConnectionStatus.ERROR_BLUETOOTH_DISABLED) {
                            _wearableConnectionStatus.value = if (hasRequiredBluetoothPermissions()) BleConnectionStatus.IDLE else BleConnectionStatus.ERROR_PERMISSIONS
                        }
                        if (hasRequiredBluetoothPermissions()) {
                            applicationScope.launch { _errorMessages.emit("Bluetooth operativo.") }
                        } else {
                            applicationScope.launch { _errorMessages.emit("Bluetooth activado, pero faltan permisos.") }
                        }

                    } else { // Si el Bluetooth SE APAGÓ
                        Log.w(TAG, "Bluetooth se ha desactivado. Forzando desconexión de todos los dispositivos y reseteo de estados.")
                        disconnectAllInternal(BleConnectionStatus.ERROR_BLUETOOTH_DISABLED) // Nueva función interna para manejar esto
                        applicationScope.launch { _errorMessages.emit("Bluetooth se ha desactivado.") }
                    }
                }
            }
        }
    }

    override fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // API 28+
            locationManager.isLocationEnabled
        } else {
            try {
                // Método más antiguo, puede requerir más permisos o ser menos fiable
                @Suppress("DEPRECATION")
                val mode = android.provider.Settings.Secure.getInt(
                    context.contentResolver,
                    android.provider.Settings.Secure.LOCATION_MODE
                )
                mode != android.provider.Settings.Secure.LOCATION_MODE_OFF
            } catch (e: Exception) {
                Log.e(TAG, "Error al verificar el estado de la ubicación (legacy)", e)
                false // Asumir desactivado si hay error
            }
        }
    }

    override val isServiceReady: StateFlow<Boolean> = combine(
        _isBluetoothAdapterEnabled, // Se actualiza por el BroadcastReceiver
        _oximeterConnectionStatus, // Podríamos usar un estado general o combinar estos.
        _wearableConnectionStatus  // Por ahora, solo usamos el adaptador y permisos.
    ) { isAdapterEnabled, _, _ -> // El valor de connectionStatus no se usa directamente aquí
        val hasPerms = hasRequiredBluetoothPermissions() // Chequear permisos actualizados
        val locationOn = isLocationEnabled()
        Log.v(TAG, "isServiceReady recalculado: AdapterEnabled=$isAdapterEnabled, HasPerms=$hasPerms, LocationOn=$locationOn. Resultado: ${isAdapterEnabled && hasPerms}")
        isAdapterEnabled && hasPerms && locationOn
    }.stateIn(applicationScope, SharingStarted.WhileSubscribed(5000), hasRequiredBluetoothPermissions() && (bluetoothAdapter?.isEnabled == true) && isLocationEnabled()
    )

    init {
        Log.d(TAG, "BluetoothService Initialized")
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothStateReceiver, filter)
        _isBluetoothAdapterEnabled.value = bluetoothAdapter?.isEnabled == true
        Log.d(TAG, "Estado inicial del adaptador BT: ${_isBluetoothAdapterEnabled.value}")
    }


    /**
     * Actualiza el estado de conexión y emite un mensaje de error para un tipo de dispositivo específico.
     */
    private fun updateStatusAndEmitErrorForDevice(
        deviceAddress: String, // Para logging y contexto, aunque el estado se actualiza por tipo
        deviceType: DeviceCategory,
        status: BleConnectionStatus,
        errorMessage: String,
        logError: Boolean = true
    ) {
        if (logError) Log.e(TAG, "[$deviceAddress (${deviceType.name})] $errorMessage")

        when (deviceType) {
            DeviceCategory.OXIMETER -> _oximeterConnectionStatus.value = status
            DeviceCategory.WEARABLE -> _wearableConnectionStatus.value = status
            DeviceCategory.UNKNOWN -> { // Si es UNKNOWN, podría actualizar un estado general o loguear
                Log.w(TAG, "updateStatusAndEmitErrorForDevice llamado para UNKNOWN tipo de dispositivo: $deviceAddress")
                // Quizás emitir solo el error message si no hay un status específico para UNKNOWN
            }
        }
        applicationScope.launch { _errorMessages.emit("[$deviceAddress] $errorMessage") }
    }

    override fun hasRequiredBluetoothPermissions(): Boolean {
        return getRequiredBluetoothPermissionsArray().all { // Usa la nueva función
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // --- NUEVO: Función para obtener el array de permisos ---
    override fun getRequiredBluetoothPermissionsArray(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    override fun isBluetoothEnabled(): Boolean {
        // Ahora podemos usar nuestro StateFlow interno, aunque bluetoothAdapter?.isEnabled también es válido
        // para una comprobación puntual. La ventaja de _isBluetoothAdapterEnabled.value es que es reactivo.
        return _isBluetoothAdapterEnabled.value // Más reactivo
        // return bluetoothAdapter?.isEnabled == true // Menos reactivo si el estado cambia externamente
    }

    @SuppressLint("MissingPermission")
    override fun startScan() { // La lógica de escaneo es general
        if (!hasRequiredBluetoothPermissions()) {
            applicationScope.launch { _errorMessages.emit("Faltan permisos Bluetooth para escanear.") }
            // Podríamos querer actualizar los estados de ambos a ERROR_PERMISSIONS si estaban IDLE
            if (_oximeterConnectionStatus.value == BleConnectionStatus.IDLE) _oximeterConnectionStatus.value = BleConnectionStatus.ERROR_PERMISSIONS
            if (_wearableConnectionStatus.value == BleConnectionStatus.IDLE) _wearableConnectionStatus.value = BleConnectionStatus.ERROR_PERMISSIONS
            return
        }
        if (!isBluetoothEnabled()) {
            applicationScope.launch { _errorMessages.emit("Bluetooth está desactivado.") }
            if (_oximeterConnectionStatus.value == BleConnectionStatus.IDLE) _oximeterConnectionStatus.value = BleConnectionStatus.ERROR_BLUETOOTH_DISABLED
            if (_wearableConnectionStatus.value == BleConnectionStatus.IDLE) _wearableConnectionStatus.value = BleConnectionStatus.ERROR_BLUETOOTH_DISABLED
            return
        }
        if (_isScanning.value) {
            Log.d(TAG, "El escaneo ya está en progreso.")
            return
        }

        Log.d(TAG, "Iniciando escaneo BLE...")
        clearScannedDevicesInternal()
        _isScanning.value = true
        // Actualizar estados si estaban en IDLE para reflejar el escaneo
        if (_oximeterConnectionStatus.value == BleConnectionStatus.IDLE || _oximeterConnectionStatus.value == BleConnectionStatus.DISCONNECTED_ERROR) _oximeterConnectionStatus.value = BleConnectionStatus.SCANNING
        if (_wearableConnectionStatus.value == BleConnectionStatus.IDLE || _wearableConnectionStatus.value == BleConnectionStatus.DISCONNECTED_ERROR) _wearableConnectionStatus.value = BleConnectionStatus.SCANNING


        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                applicationScope.launch { _errorMessages.emit("No se pudo obtener el BLE Scanner.") }
                _isScanning.value = false
                if (_oximeterConnectionStatus.value == BleConnectionStatus.SCANNING) _oximeterConnectionStatus.value = BleConnectionStatus.IDLE
                if (_wearableConnectionStatus.value == BleConnectionStatus.SCANNING) _wearableConnectionStatus.value = BleConnectionStatus.IDLE
                return
            }
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bluetoothLeScanner?.startScan(null, scanSettings, leScanCallback)
        } catch (e: Exception) {
            applicationScope.launch { _errorMessages.emit("Excepción al iniciar escaneo: ${e.localizedMessage}") }
            _isScanning.value = false
            if (_oximeterConnectionStatus.value == BleConnectionStatus.SCANNING) _oximeterConnectionStatus.value = BleConnectionStatus.IDLE
            if (_wearableConnectionStatus.value == BleConnectionStatus.SCANNING) _wearableConnectionStatus.value = BleConnectionStatus.IDLE
            return
        }

        applicationScope.launch {
            delay(20000) // Duración del escaneo
            if (_isScanning.value) {
                Log.d(TAG, "Escaneo detenido automáticamente por tiempo.")
                stopScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() { // La lógica de detener escaneo es general
        if (!_isScanning.value) {
            return
        }
        Log.d(TAG, "Deteniendo escaneo BLE...")
        if (hasRequiredBluetoothPermissions() && isBluetoothEnabled()) {
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Excepción al detener escaneo formalmente: ${e.localizedMessage}")
            }
        } else {
            Log.w(TAG, "No se puede detener el escaneo formalmente (sin permisos/BT apagado), pero se actualizan flags.")
        }

        _isScanning.value = false
        // Si estábamos en SCANNING, volvemos a IDLE para ambos tipos de dispositivos
        if (_oximeterConnectionStatus.value == BleConnectionStatus.SCANNING) {
            _oximeterConnectionStatus.value = BleConnectionStatus.IDLE
        }
        if (_wearableConnectionStatus.value == BleConnectionStatus.SCANNING) {
            _wearableConnectionStatus.value = BleConnectionStatus.IDLE
        }
        Log.d(TAG, "Escaneo detenido, estados relevantes cambiados a IDLE si estaban en SCANNING.")
    }


    private fun clearScannedDevicesInternal() {
        _scannedDevices.value = emptyList()
        foundDeviceAddresses.clear()
    }

    override fun clearScannedDevices() {
        clearScannedDevicesInternal()
    }


    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val deviceAddress = device.address
                if (deviceAddress.isNullOrEmpty() || !BluetoothAdapter.checkBluetoothAddress(deviceAddress)) {
                    Log.w(TAG, "Dispositivo con dirección inválida encontrado y omitido.")
                    return
                }


                // Determinar categoría del dispositivo
                var category = DeviceCategory.UNKNOWN
                val scanRecord = result.scanRecord

                scanRecord?.serviceUuids?.forEach { parcelUuid ->
                    val uuid = parcelUuid.uuid
                    when (uuid) {
                        BluetoothConstants.BM1000_SERVICE_UUID -> {
                            category = DeviceCategory.OXIMETER
                            // Podríamos añadir 'return@forEach' si estamos seguros
                            // que un dispositivo no anunciará ambos servicios principales.
                        }
                        BluetoothConstants.WEARABLE_SERVICE_UUID -> {
                            category = DeviceCategory.WEARABLE
                            // 'return@forEach'
                        }
                    }
                }
                // Log.d(TAG, "Scan for $deviceAddress, category by UUID: $category")

                var deviceName: String? = "Desconocido"
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && hasRequiredBluetoothPermissions())) {
                    try { deviceName = device.name ?: "Desconocido" } catch (e: SecurityException) { deviceName = "N/A (Sin Permiso)" }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    deviceName = "N/A (Sin Permiso Connect)"
                }

                // Fallback por nombre si la categoría sigue siendo UNKNOWN y tenemos un nombre
                if (category == DeviceCategory.UNKNOWN && deviceName != "Desconocido" && deviceName != "N/A (Sin Permiso)" && deviceName != "N/A (Sin Permiso Connect)") {
                    if (deviceName?.contains("BM1000", ignoreCase = true) == true || deviceName?.contains("BerryMed", ignoreCase = true) == true) {
                        category = DeviceCategory.OXIMETER
                    } else if (deviceName?.contains("ESP32", ignoreCase = true) == true || deviceName?.contains("Wearable", ignoreCase = true) == true || deviceName?.contains("6MWT", ignoreCase = true) == true) {
                        category = DeviceCategory.WEARABLE
                    }
                    // Log.d(TAG, "Scan for $deviceAddress, category after name check: $category")
                }


                val uiDevice = UiScannedDevice(
                    deviceName = deviceName,
                    address = deviceAddress,
                    rssi = result.rssi,
                    rawDevice = device,
                    category = category // ¡Aquí asignamos la categoría!
                )

                if (!foundDeviceAddresses.contains(deviceAddress)) {
                    foundDeviceAddresses.add(deviceAddress)
                    _scannedDevices.update { currentList ->
                        (currentList + uiDevice).distinctBy { it.address }
                    }
                    Log.d(TAG, "Dispositivo encontrado: ${uiDevice.deviceName} (${uiDevice.category}) - ${uiDevice.address} RSSI: ${uiDevice.rssi}")
                } else {
                    _scannedDevices.update { list ->
                        list.map {
                            if (it.address == deviceAddress) {
                                // Actualiza RSSI y también la categoría si se determinó más tarde o cambió
                                it.copy(rssi = result.rssi, category = if (uiDevice.category != DeviceCategory.UNKNOWN) uiDevice.category else it.category)
                            } else {
                                it
                            }
                        }
                    }
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMsg = "Escaneo BLE fallido: código $errorCode"
            Log.e(TAG, errorMsg)
            applicationScope.launch { _errorMessages.emit(errorMsg) }
            _isScanning.value = false
            // Si el escaneo falla, ambos estados vuelven a IDLE si estaban en SCANNING
            if (_oximeterConnectionStatus.value == BleConnectionStatus.SCANNING) _oximeterConnectionStatus.value = BleConnectionStatus.IDLE
            if (_wearableConnectionStatus.value == BleConnectionStatus.SCANNING) _wearableConnectionStatus.value = BleConnectionStatus.IDLE
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(deviceAddress: String, category: DeviceCategory) {
        Log.d(TAG, "connect() llamado para: $deviceAddress, Categoría: $category")

        if (!hasRequiredBluetoothPermissions()) {
            applicationScope.launch { _errorMessages.emit("[$deviceAddress ($category)] Faltan permisos para conectar.") }
            updateConnectionState(deviceAddress, category, BleConnectionStatus.ERROR_PERMISSIONS)
            return
        }
        if (!isBluetoothEnabled()) {
            applicationScope.launch { _errorMessages.emit("[$deviceAddress ($category)] Bluetooth está desactivado.") }
            updateConnectionState(deviceAddress, category, BleConnectionStatus.ERROR_BLUETOOTH_DISABLED)
            return
        }

        if (category == DeviceCategory.UNKNOWN) {
            Log.w(TAG, "[$deviceAddress] Intento de conexión a categoría UNKNOWN. Abortando.")
            applicationScope.launch { _errorMessages.emit("[$deviceAddress] No se puede conectar a tipo desconocido.")}
            // Opcionalmente, actualizar ambos estados a IDLE si estaban en algún estado de error previo.
            // _oximeterConnectionStatus.value = BleConnectionStatus.IDLE
            // _wearableConnectionStatus.value = BleConnectionStatus.IDLE
            return
        }

        // Verificar si ya se está conectando o conectado a ESTA CATEGORÍA
        val (currentStatusFlow, currentConnectingAddressFlow, currentGatt) = when (category) {
            DeviceCategory.OXIMETER -> Triple(_oximeterConnectionStatus, oximeterConnectingAddress, gattConnections[deviceAddress]) // O mejor, un gattOximeter dedicado
            DeviceCategory.WEARABLE -> Triple(_wearableConnectionStatus, wearableConnectingAddress, gattConnections[deviceAddress]) // O mejor, un gattWearable dedicado
            else -> return // Ya manejado
        }

        if (currentConnectingAddressFlow.value == deviceAddress && currentStatusFlow.value == BleConnectionStatus.CONNECTING) {
            Log.d(TAG, "[$deviceAddress ($category)] Ya se está conectando.")
            return
        }
        if ((currentStatusFlow.value == BleConnectionStatus.CONNECTED || currentStatusFlow.value == BleConnectionStatus.SUBSCRIBED) &&
            currentGatt?.device?.address == deviceAddress) {
            Log.d(TAG, "[$deviceAddress ($category)] Ya conectado.")
            return
        }

// Si hay otro dispositivo de la MISMA categoría conectado, desconectarlo primero.
        // Esto es una decisión de diseño. Alternativamente, podrías rechazar la nueva conexión.
        val existingDeviceAddressForCategory = when(category) {
            DeviceCategory.OXIMETER -> connectedOximeter.value?.address
            DeviceCategory.WEARABLE -> connectedWearable.value?.address
            else -> null
        }
        if (existingDeviceAddressForCategory != null && existingDeviceAddressForCategory != deviceAddress) {
            Log.d(TAG, "Desconectando dispositivo anterior de categoría $category: $existingDeviceAddressForCategory para conectar a $deviceAddress")
            disconnect(existingDeviceAddressForCategory) // Tu función disconnect actual debería manejar esto
        }


        stopScan() // Detener escaneo antes de conectar es una buena práctica

        val deviceToConnect: BluetoothDevice? = try {
            bluetoothAdapter?.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "[$deviceAddress ($category)] Dirección MAC inválida.", e)
            applicationScope.launch { _errorMessages.emit("[$deviceAddress ($category)] Dirección MAC inválida.") }
            updateConnectionState(deviceAddress, category, BleConnectionStatus.ERROR_DEVICE_NOT_FOUND) // O un error más genérico
            null
        }

        if (deviceToConnect == null) {
            applicationScope.launch { _errorMessages.emit("[$deviceAddress ($category)] No se pudo obtener BluetoothDevice.") }
            updateConnectionState(deviceAddress, category, BleConnectionStatus.ERROR_DEVICE_NOT_FOUND)
            return
        }

        // Marcar que estamos intentando conectar a este dispositivo para esta categoría
        when (category) {
            DeviceCategory.OXIMETER -> oximeterConnectingAddress.value = deviceAddress
            DeviceCategory.WEARABLE -> wearableConnectingAddress.value = deviceAddress
            else -> {}
        }
        disconnectsInitiatedByUser.remove(deviceAddress)
        reconnectAttemptsMap.remove(deviceAddress) // Resetear intentos de reconexión

        deviceTypes[deviceAddress] = category // *IMPORTANTE: Registrar el tipo AHORA*

        Log.d(TAG, "[$deviceAddress ($category)] Intentando conectar a GATT...")
        updateConnectionState(deviceAddress, category, BleConnectionStatus.CONNECTING)

        applicationScope.launch(ioDispatcher) {
            // El gattCallback seguirá siendo el mismo por ahora,
            // pero su comportamiento interno cambiará basándose en deviceTypes[deviceAddress]
            val newGatt = deviceToConnect.connectGatt(
                context,
                false, // autoConnect = false
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            if (newGatt == null) {
                withContext(Dispatchers.Main) { // O usa applicationScope
                    Log.e(TAG, "[$deviceAddress ($category)] connectGatt devolvió null.")
                    applicationScope.launch { _errorMessages.emit("[$deviceAddress ($category)] Fallo al iniciar conexión (gatt null).") }
                    when (category) {
                        DeviceCategory.OXIMETER -> oximeterConnectingAddress.value = null
                        DeviceCategory.WEARABLE -> wearableConnectingAddress.value = null
                        else -> {}
                    }
                    // No limpiar gattConnections aquí si newGatt es null, se limpia en error de callback o si no se llama
                    updateConnectionState(deviceAddress, category, BleConnectionStatus.DISCONNECTED_ERROR) // o ERROR_GENERIC
                }
            } else {
                // No almacenar en gattConnections aquí. Se hará en onConnectionStateChange cuando STATE_CONNECTED.
                Log.d(TAG, "[$deviceAddress ($category)] connectGatt llamado. Esperando callback...")
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun disconnect(deviceAddress: String) {
        Log.d(TAG, "[$deviceAddress] disconnect() llamado.")
        disconnectsInitiatedByUser.add(deviceAddress)
        reconnectJob = null // Cancelar reconexión si estaba para este dispositivo (necesita refinar)
        reconnectAttemptsMap.remove(deviceAddress)
        addressForCurrentReconnectAttempt = null


        val gatt = gattConnections[deviceAddress]
        if (gatt != null) {
            if (hasRequiredBluetoothPermissions() && isBluetoothEnabled()) {
                Log.d(TAG, "[$deviceAddress] Desconectando GATT...")
                try {
                    gatt.disconnect() // Esto debería disparar onConnectionStateChange
                    // La limpieza final (close, remove from map) se hará en onConnectionStateChange
                } catch (e: Exception) {
                    Log.e(TAG, "[$deviceAddress] Excepción durante gatt.disconnect(): ${e.localizedMessage}")
                    // Forzar limpieza si disconnect() falla
                    cleanupGattConnection(gatt, DeviceCategory.UNKNOWN, BleConnectionStatus.DISCONNECTED_BY_USER, true)
                }
            } else {
                Log.w(TAG, "[$deviceAddress] Faltan permisos o BT apagado para desconectar GATT formalmente. Solo cerrando.")
                cleanupGattConnection(gatt, DeviceCategory.UNKNOWN, BleConnectionStatus.DISCONNECTED_BY_USER, true)
            }
        } else {
            Log.d(TAG, "[$deviceAddress] disconnect() llamado pero no se encontró GATT activo.")
            // Asegurar que el estado refleje la desconexión si no lo hace (si el tipo era conocido)
            val deviceType = deviceTypes[deviceAddress] ?: DeviceCategory.UNKNOWN
            updateConnectionState(deviceAddress, deviceType, BleConnectionStatus.DISCONNECTED_BY_USER)
            if (deviceType == DeviceCategory.OXIMETER) _connectedOximeter.value = null
            if (deviceType == DeviceCategory.WEARABLE) _connectedWearable.value = null
            deviceTypes.remove(deviceAddress) // Limpiar tipo si ya no hay gatt
        }
    }

    /**
     *  Función interna para manejar la desconexión de todos los dispositivos.
     *  @param finalStatus El estado al que se deben poner los dispositivos tras la desconexión.
     */
    @SuppressLint("MissingPermission")
    private fun disconnectAllInternal(finalStatus: BleConnectionStatus) {
        Log.i(TAG, "disconnectAllInternal() llamado con estado final: $finalStatus")

        oximeterConnectingAddress.value = null
        wearableConnectingAddress.value = null

        val addressesToMarkAsUserDisconnect = Collections.list(gattConnections.keys())
        disconnectsInitiatedByUser.addAll(addressesToMarkAsUserDisconnect)

        gattConnections.values.forEach { gatt ->
            val address = gatt.device.address
            try {
                if (hasRequiredBluetoothPermissions() && isBluetoothEnabled()) {
                    Log.d(TAG, "[$address] Desconectando GATT (parte de disconnectAll)...")
                    gatt.disconnect()
                } else {
                    // Si no se puede llamar a disconnect, se cierra directamente
                    throw IllegalStateException("BT/Permisos no disponibles, cerrando directamente.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$address] Excepción durante disconnectAll en gatt.disconnect() o cierre directo: ${e.localizedMessage}")
                // Si disconnect falla o no se puede llamar, cerramos y limpiamos manualmente.
                // El tipo podría no ser conocido aquí si la conexión falló muy temprano.
                cleanupGattConnection(gatt, deviceTypes[address] ?: DeviceCategory.UNKNOWN, finalStatus, true)
            }
        }
        // Los callbacks de onConnectionStateChange deberían manejar la limpieza final.
        // Pero si BT está apagado, esos callbacks podrían no llegar, por eso la limpieza forzada arriba si hay error.
        // Y aquí actualizamos los estados de alto nivel.
        _oximeterConnectionStatus.value = finalStatus
        _connectedOximeter.value = null
        _oximeterDeviceData.value = BleDeviceData()
        _lastKnownOximeterAddress.value = if (finalStatus == BleConnectionStatus.ERROR_BLUETOOTH_DISABLED) _lastKnownOximeterAddress.value else null


        _wearableConnectionStatus.value = finalStatus
        _connectedWearable.value = null
        _wearableDeviceData.value = WearableDeviceData()
        _lastKnownWearableAddress.value = if (finalStatus == BleConnectionStatus.ERROR_BLUETOOTH_DISABLED) _lastKnownWearableAddress.value else null

        if (finalStatus == BleConnectionStatus.ERROR_BLUETOOTH_DISABLED) {
            _isScanning.value = false // Detener escaneo también
            reconnectJob?.cancel()
            reconnectAttemptsMap.clear()
            addressForCurrentReconnectAttempt = null
        }
    }

    override fun disconnectAll() {
        Log.d(TAG, "disconnectAll() llamado por el usuario.")
        disconnectAllInternal(BleConnectionStatus.DISCONNECTED_BY_USER)
    }

    /**
     * Limpia los recursos de una conexión GATT y actualiza los estados.
     * @param gatt El BluetoothGatt a limpiar.
     * @param deviceType El tipo de dispositivo conocido.
     * @param finalStatus El estado de conexión final para este dispositivo.
     * @param wasInitiatedByUser True si la desconexión/limpieza fue iniciada por el usuario.
     */
    @SuppressLint("MissingPermission")
    private fun cleanupGattConnection(gatt: BluetoothGatt?, identifiedDeviceType: DeviceCategory, finalStatus: BleConnectionStatus, wasInitiatedByUser: Boolean) {
        val address = gatt?.device?.address
        if (address == null) {
            Log.w(TAG, "cleanupGattConnection llamado con gatt nulo o sin dirección.")
            return
        }

        Log.d(TAG, "[$address] cleanupGattConnection. Tipo: $identifiedDeviceType, Estado Final: $finalStatus, Iniciado por Usuario: $wasInitiatedByUser")

        try { gatt.close()
            Log.d(TAG,"[$address] GATT cerrado.")
        } catch (e: Exception) {
            Log.e(TAG, "[$address] Excepción al cerrar GATT en cleanup: $e")
        }

        gattConnections.remove(address)
        if (oximeterConnectingAddress.value == address) oximeterConnectingAddress.value = null
        if (wearableConnectingAddress.value == address) wearableConnectingAddress.value = null
        if (!wasInitiatedByUser) { // Solo quitar de disconnectsInitiatedByUser si NO fue por usuario, para permitir reconexión
            disconnectsInitiatedByUser.remove(address)
        }


        val actualDeviceType = deviceTypes.remove(address) ?: identifiedDeviceType

        updateConnectionState(address, actualDeviceType, finalStatus)

        if (actualDeviceType == DeviceCategory.OXIMETER) {
            _connectedOximeter.value = null
            _oximeterDeviceData.value = BleDeviceData()
            if (finalStatus != BleConnectionStatus.RECONNECTING && finalStatus != BleConnectionStatus.CONNECTING) { // No limpiar si está reconectando o conectando
                // _lastKnownOximeterAddress se mantiene a menos que sea un error crítico o desconexión por usuario sin error
                if (finalStatus == BleConnectionStatus.DISCONNECTED_BY_USER || finalStatus == BleConnectionStatus.ERROR_BLUETOOTH_DISABLED) {
                    // _lastKnownOximeterAddress.value = null; // Decidir si se limpia o no en desconexión manual
                }
            }
        } else if (actualDeviceType == DeviceCategory.WEARABLE) {
            _connectedWearable.value = null
            _wearableDeviceData.value = WearableDeviceData()
            if (finalStatus != BleConnectionStatus.RECONNECTING && finalStatus != BleConnectionStatus.CONNECTING) {
                // _lastKnownWearableAddress.value = null;
            }
        }

        // Si la reconexión estaba en curso para este dispositivo y falló o se canceló
        if (addressForCurrentReconnectAttempt == address && finalStatus != BleConnectionStatus.RECONNECTING) {
            reconnectJob?.cancel()
            reconnectJob = null
            addressForCurrentReconnectAttempt = null
            reconnectAttemptsMap.remove(address)
        }
    }


    /**
     * Actualiza el StateFlow de conexión correcto basado en el tipo de dispositivo.
     */
    private fun updateConnectionState(deviceAddress: String, deviceType: DeviceCategory, newStatus: BleConnectionStatus) {
        Log.d(TAG, "[$deviceAddress] Actualizando estado para tipo $deviceType a $newStatus")
        when (deviceType) {
            DeviceCategory.OXIMETER -> {
                if (_oximeterConnectionStatus.value != newStatus) _oximeterConnectionStatus.value = newStatus
                if (newStatus != BleConnectionStatus.CONNECTED && newStatus != BleConnectionStatus.SUBSCRIBED && newStatus != BleConnectionStatus.CONNECTING && newStatus != BleConnectionStatus.RECONNECTING) {
                    if (_connectedOximeter.value?.address == deviceAddress) _connectedOximeter.value = null
                }
            }
            DeviceCategory.WEARABLE -> {
                if (_wearableConnectionStatus.value != newStatus) _wearableConnectionStatus.value = newStatus
                if (newStatus != BleConnectionStatus.CONNECTED && newStatus != BleConnectionStatus.SUBSCRIBED && newStatus != BleConnectionStatus.CONNECTING && newStatus != BleConnectionStatus.RECONNECTING) {
                    if (_connectedWearable.value?.address == deviceAddress) _connectedWearable.value = null
                }
            }
            DeviceCategory.UNKNOWN -> {
                // Si es UNKNOWN, puede que ambos estados estuvieran en CONNECTING.
                // Si la conexión falla antes de identificar, ambos deberían ir a un estado de error/idle.
                Log.w(TAG, "[$deviceAddress] updateConnectionState para tipo UNKNOWN a $newStatus. Afectando ambos temporalmente si estaban conectando.")
                if (_oximeterConnectionStatus.value == BleConnectionStatus.CONNECTING && newStatus != BleConnectionStatus.CONNECTED && newStatus != BleConnectionStatus.SUBSCRIBED) {
                    _oximeterConnectionStatus.value = newStatus
                }
                if (_wearableConnectionStatus.value == BleConnectionStatus.CONNECTING && newStatus != BleConnectionStatus.CONNECTED && newStatus != BleConnectionStatus.SUBSCRIBED) {
                    _wearableConnectionStatus.value = newStatus
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptReconnect(deviceAddress: String) {
        val typeToReconnect = deviceTypes[deviceAddress] // Obtener el tipo guardado

        if (typeToReconnect == null || typeToReconnect == DeviceCategory.UNKNOWN) {
            Log.e(TAG, "[$deviceAddress] No se puede reconectar, tipo desconocido.")
            // No se puede llamar a updateStatusAndEmitErrorForDevice sin un tipo,
            // pero podemos limpiar el estado de reconexión.
            reconnectAttemptsMap.remove(deviceAddress)
            if (addressForCurrentReconnectAttempt == deviceAddress) {
                reconnectJob?.cancel()
                addressForCurrentReconnectAttempt = null
            }
            // También podría ser útil resetear los estados de conexión a IDLE o ERROR.
            // Si _oximeterConnectionStatus estaba en RECONNECTING, ponerlo en DISCONNECTED_ERROR
            if(_oximeterConnectionStatus.value == BleConnectionStatus.RECONNECTING) _oximeterConnectionStatus.value = BleConnectionStatus.DISCONNECTED_ERROR
            if(_wearableConnectionStatus.value == BleConnectionStatus.RECONNECTING) _wearableConnectionStatus.value = BleConnectionStatus.DISCONNECTED_ERROR
            return
        }

        if (disconnectsInitiatedByUser.contains(deviceAddress)) {
            Log.d(TAG, "[$deviceAddress ($typeToReconnect)] Reconexión abortada, desconexión iniciada por el usuario.")
            updateConnectionState(deviceAddress, typeToReconnect, BleConnectionStatus.DISCONNECTED_BY_USER)
            return
        }

        if (!isBluetoothEnabled()) {
            val type = deviceTypes[deviceAddress] ?: DeviceCategory.UNKNOWN
            updateStatusAndEmitErrorForDevice(deviceAddress, type, BleConnectionStatus.ERROR_BLUETOOTH_DISABLED, "No se puede reconectar, Bluetooth desactivado.")
            return
        }
        if (!hasRequiredBluetoothPermissions()) {
            val type = deviceTypes[deviceAddress] ?: DeviceCategory.UNKNOWN
            updateStatusAndEmitErrorForDevice(deviceAddress, type, BleConnectionStatus.ERROR_PERMISSIONS, "No se puede reconectar, faltan permisos.")
            return
        }

        val attempts = reconnectAttemptsMap[deviceAddress] ?: 0
        if (attempts >= MAX_RECONNECT_ATTEMPTS) {
            val type = deviceTypes[deviceAddress] ?: DeviceCategory.UNKNOWN
            updateStatusAndEmitErrorForDevice(deviceAddress, type, BleConnectionStatus.DISCONNECTED_ERROR, "Fallaron todos los intentos de reconexión a $deviceAddress.")
            // Aquí NO se hace el mini-rescan de tu código original, ya que el escaneo es general.
            // Si la UI necesita refrescar, el usuario puede iniciar un nuevo escaneo.
            // O podrías decidir iniciar un escaneo general aquí si fallan todos los reintentos para un dispositivo.
            reconnectAttemptsMap.remove(deviceAddress)
            addressForCurrentReconnectAttempt = null
            return
        }

        reconnectAttemptsMap[deviceAddress] = attempts + 1
        addressForCurrentReconnectAttempt = deviceAddress // Marcar para qué dispositivo es este job de reconexión
        val type = deviceTypes[deviceAddress] ?: DeviceCategory.UNKNOWN // Tipo puede ser desconocido si falló antes de identificar
        updateConnectionState(deviceAddress, type, BleConnectionStatus.RECONNECTING)
        applicationScope.launch { _errorMessages.emit("[$deviceAddress] Intentando reconectar (${attempts + 1}/$MAX_RECONNECT_ATTEMPTS)...") }

        Log.d(TAG, "[$deviceAddress] Programando intento de reconexión #${attempts + 1}...")
        reconnectJob?.cancel() // Cancelar job anterior si existía para otro dispositivo (o el mismo)
        reconnectJob = applicationScope.launch {
            delay(3000) // Esperar antes de reconectar
            if (addressForCurrentReconnectAttempt == deviceAddress) { // Asegurar que el job es para el dispositivo correcto
                Log.d(TAG, "[$deviceAddress ($typeToReconnect)] Ejecutando reconexión ahora...")
                connect(deviceAddress, typeToReconnect) // Volver a llamar a connect
            } else {
                Log.d(TAG, "[$deviceAddress ($typeToReconnect)] Job de reconexión obsoleto, no se ejecuta.")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            val deviceName = try { gatt.device.name ?: deviceAddress } catch (e: SecurityException) { deviceAddress }

            // *Obtener el tipo del dispositivo que ESPERÁBAMOS conectar*
            val expectedDeviceType = deviceTypes[deviceAddress] // Esto debería estar puesto por connect()

            Log.d(TAG, "[$deviceName ($expectedDeviceType)] onConnectionStateChange. Status: $status, NewState: $newState.")

            // Limpiar la dirección de conexión pendiente para la categoría correspondiente
            // independientemente del resultado, ya que el intento de conexión ha terminado.
            if (expectedDeviceType != null) {
                when (expectedDeviceType) {
                    DeviceCategory.OXIMETER -> oximeterConnectingAddress.value = null
                    DeviceCategory.WEARABLE -> wearableConnectingAddress.value = null
                    DeviceCategory.UNKNOWN -> { // Poco probable si connect() lo filtró
                        oximeterConnectingAddress.value = null
                        wearableConnectingAddress.value = null
                    }
                }
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "[$deviceName ($expectedDeviceType)] CONECTADO a GATT server.")
                    gattConnections[deviceAddress] = gatt
                    reconnectAttemptsMap.remove(deviceAddress)
                    if (addressForCurrentReconnectAttempt == deviceAddress) {
                        reconnectJob?.cancel()
                        addressForCurrentReconnectAttempt = null
                    }
                    disconnectsInitiatedByUser.remove(deviceAddress)

                    if (expectedDeviceType == null || expectedDeviceType == DeviceCategory.UNKNOWN) {
                        Log.e(TAG, "[$deviceName] CONECTADO, pero el tipo de dispositivo es desconocido. Esto no debería ocurrir si connect() estableció el tipo. Limpiando.")
                        cleanupGattConnection(gatt, DeviceCategory.UNKNOWN, BleConnectionStatus.DISCONNECTED_ERROR, false)
                        return
                    }

                    // Actualizar estado del dispositivo conectado y el estado general de conexión
                    val connectedUiDevice = _scannedDevices.value.find { it.address == deviceAddress && it.category == expectedDeviceType }
                        ?: UiScannedDevice(deviceName, deviceAddress, null, gatt.device, expectedDeviceType)

                    when (expectedDeviceType) {
                        DeviceCategory.OXIMETER -> _connectedOximeter.value = connectedUiDevice
                        DeviceCategory.WEARABLE -> _connectedWearable.value = connectedUiDevice
                        else -> {}
                    }
                    updateConnectionState(deviceAddress, expectedDeviceType, BleConnectionStatus.CONNECTED)

                    applicationScope.launch(ioDispatcher) {
                        delay(600)
                        if (gattConnections[deviceAddress] == gatt) {
                            Log.d(TAG, "[$deviceName ($expectedDeviceType)] Iniciando descubrimiento de servicios...")
                            val discoveryInitiated = gatt.discoverServices()
                            if (!discoveryInitiated) {
                                Log.e(TAG, "[$deviceName ($expectedDeviceType)] discoverServices() falló al iniciar.")
                                applicationScope.launch { _errorMessages.emit("[$deviceName] Fallo al iniciar descubrimiento de servicios.")}
                                cleanupGattConnection(gatt, expectedDeviceType, BleConnectionStatus.ERROR_SERVICE_NOT_FOUND, false)
                            }
                        } else {
                            Log.w(TAG, "[$deviceName ($expectedDeviceType)] GATT cambió durante onConnectionStateChange (CONNECTED) antes de discoverServices. Cerrando este gatt.")
                            try { gatt.close() } catch (e: Exception) { Log.e(TAG, "Error cerrando gatt obsoleto: $e")}
                        }
                    }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "[$deviceName ($expectedDeviceType)] DESCONECTADO de GATT server. Iniciado por usuario: ${disconnectsInitiatedByUser.contains(deviceAddress)}")
                    val wasByUser = disconnectsInitiatedByUser.contains(deviceAddress)
                    val finalStatus = if (wasByUser) BleConnectionStatus.DISCONNECTED_BY_USER else BleConnectionStatus.DISCONNECTED_ERROR

                    val typeToCleanup = expectedDeviceType ?: deviceTypes[deviceAddress] ?: DeviceCategory.UNKNOWN
                    cleanupGattConnection(gatt, typeToCleanup, finalStatus, wasByUser)

                    if (!wasByUser && bluetoothAdapter?.isEnabled == false) {
                        updateConnectionState(deviceAddress, typeToCleanup, BleConnectionStatus.ERROR_BLUETOOTH_DISABLED)
                    }
                }
            } else { // status != BluetoothGatt.GATT_SUCCESS (Error de conexión)
                Log.e(TAG, "[$deviceName ($expectedDeviceType)] Error en onConnectionStateChange. Status: $status, NewState: $newState.")
                val wasByUser = disconnectsInitiatedByUser.contains(deviceAddress)
                val finalStatus = if (wasByUser) BleConnectionStatus.DISCONNECTED_BY_USER else BleConnectionStatus.DISCONNECTED_ERROR

                val typeToCleanup = expectedDeviceType ?: deviceTypes[deviceAddress] ?: DeviceCategory.UNKNOWN
                cleanupGattConnection(gatt, typeToCleanup, finalStatus, wasByUser)

                if (!wasByUser && bluetoothAdapter?.isEnabled == false) {
                    updateConnectionState(deviceAddress, typeToCleanup, BleConnectionStatus.ERROR_BLUETOOTH_DISABLED)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val deviceAddress = gatt.device.address
            val deviceName = try { gatt.device.name ?: deviceAddress } catch (e: SecurityException) { deviceAddress }

            // *Obtener el tipo que ESPERÁBAMOS*
            val expectedDeviceType = deviceTypes[deviceAddress]

            Log.d(TAG, "[$deviceName ($expectedDeviceType)] onServicesDiscovered. Status: $status.")

            if (gattConnections[deviceAddress] != gatt) {
                Log.w(TAG, "[$deviceName] Callback de onServicesDiscovered para un GATT diferente al actual. Ignorando.")
                return // Callback para un GATT obsoleto
            }

            if (expectedDeviceType == null || expectedDeviceType == DeviceCategory.UNKNOWN) {
                Log.e(TAG, "[$deviceName] Servicios descubiertos, pero el tipo de dispositivo es desconocido. Limpiando.")
                cleanupGattConnection(gatt, DeviceCategory.UNKNOWN, BleConnectionStatus.ERROR_SERVICE_NOT_FOUND, false)
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                var serviceFound = false
                var characteristicToEnable: BluetoothGattCharacteristic? = null

                when (expectedDeviceType) {
                    DeviceCategory.OXIMETER -> {
                        val oximeterService = gatt.getService(BluetoothConstants.BM1000_SERVICE_UUID)
                        if (oximeterService != null) {
                            characteristicToEnable = oximeterService.getCharacteristic(BluetoothConstants.BM1000_MEASUREMENT_CHARACTERISTIC_UUID)
                            if (characteristicToEnable != null) {
                                serviceFound = true
                                Log.i(TAG, "[$deviceName] Servicios de OXIMETER verificados.")
                            } else {
                                Log.e(TAG, "[$deviceName (OXIMETER)] Característica de medición NO encontrada.")
                            }
                        } else {
                            Log.e(TAG, "[$deviceName (OXIMETER)] Servicio de oximetría NO encontrado.")
                        }
                    }
                    DeviceCategory.WEARABLE -> {
                        val wearableService = gatt.getService(BluetoothConstants.WEARABLE_SERVICE_UUID)
                        if (wearableService != null) {
                            characteristicToEnable = wearableService.getCharacteristic(BluetoothConstants.DISTANCE_CHARACTERISTIC_UUID)
                            if (characteristicToEnable != null) {
                                serviceFound = true
                                Log.i(TAG, "[$deviceName] Servicios de WEARABLE verificados.")
                            } else {
                                Log.e(TAG, "[$deviceName (WEARABLE)] Característica de distancia NO encontrada.")
                            }
                        } else {
                            Log.e(TAG, "[$deviceName (WEARABLE)] Servicio de wearable NO encontrado.")
                        }
                    }
                    DeviceCategory.UNKNOWN -> { /* Ya manejado arriba */ }
                }

                if (serviceFound && characteristicToEnable != null) {
                    // Actualizar _connectedOximeter/_connectedWearable si no se hizo en onConnectionStateChange o para asegurar que el tipo es correcto
                    val uiDev = _scannedDevices.value.find { it.address == deviceAddress && it.category == expectedDeviceType }
                        ?: UiScannedDevice(deviceName, deviceAddress, null, gatt.device, expectedDeviceType)
                    when (expectedDeviceType) {
                        DeviceCategory.OXIMETER -> _connectedOximeter.value = uiDev
                        DeviceCategory.WEARABLE -> _connectedWearable.value = uiDev
                        else -> {}
                    }
                    // El estado CONNECTED ya debería estar puesto por onConnectionStateChange para el tipo correcto.
                    // Ahora intentamos suscribirnos.
                    if (!enableNotifications(gatt, characteristicToEnable, expectedDeviceType)) {
                        // enableNotifications ya actualiza el estado y limpia si falla inmediatamente
                        // cleanupGattConnection(gatt, expectedDeviceType, BleConnectionStatus.ERROR_SUBSCRIBE_FAILED, false)
                    }
                } else {
                    Log.e(TAG, "[$deviceName ($expectedDeviceType)] Servicios esperados NO encontrados.")
                    updateStatusAndEmitErrorForDevice(deviceAddress, expectedDeviceType, BleConnectionStatus.ERROR_SERVICE_NOT_FOUND, "Servicio/Característica BLE requerida no encontrada.")
                    cleanupGattConnection(gatt, expectedDeviceType, BleConnectionStatus.ERROR_SERVICE_NOT_FOUND, false)
                }

            } else {
                Log.e(TAG, "[$deviceName ($expectedDeviceType)] onServicesDiscovered falló con status: $status")
                cleanupGattConnection(gatt, expectedDeviceType, BleConnectionStatus.ERROR_SERVICE_NOT_FOUND, false)
            }
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, type: DeviceCategory): Boolean {
            val deviceAddress = gatt.device.address
            val deviceName = try { gatt.device.name ?: deviceAddress } catch (e: SecurityException) { deviceAddress }

            val notificationSet = gatt.setCharacteristicNotification(characteristic, true)
            if (!notificationSet) {
                Log.e(TAG, "[$deviceName ($type)] Fallo al habilitar notificación (setCharacteristicNotification).")
                updateStatusAndEmitErrorForDevice(deviceAddress, type, BleConnectionStatus.ERROR_SUBSCRIBE_FAILED, "Fallo al suscribirse (setCharacteristicNotification).")
                return false
            }

            val cccDescriptor = characteristic.getDescriptor(BluetoothConstants.CCCD_UUID)
            if (cccDescriptor == null) {
                Log.e(TAG, "[$deviceName ($type)] Descriptor CCCD no encontrado.")
                updateStatusAndEmitErrorForDevice(deviceAddress, type, BleConnectionStatus.ERROR_SUBSCRIBE_FAILED, "Fallo al obtener descriptor CCCD.")
                return false
            }

            cccDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            // Importante: La escritura del descriptor es asíncrona, el resultado llega a onDescriptorWrite
            val descriptorWritten = gatt.writeDescriptor(cccDescriptor)
            if (!descriptorWritten) {
                Log.e(TAG, "[$deviceName ($type)] Fallo al iniciar escritura en el descriptor CCCD.")
                updateStatusAndEmitErrorForDevice(deviceAddress, type, BleConnectionStatus.ERROR_SUBSCRIBE_FAILED, "Fallo al suscribirse (writeDescriptor inicio).")
                return false
            }
            Log.d(TAG, "[$deviceName ($type)] Escritura de descriptor CCCD iniciada...")
            return true // La confirmación final vendrá en onDescriptorWrite
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            val deviceAddress = gatt.device.address
            val deviceName = try { gatt.device.name ?: deviceAddress } catch (e: SecurityException) { deviceAddress }
            val characteristicUUID = descriptor.characteristic.uuid
            val currentDeviceType = deviceTypes[deviceAddress] ?: DeviceCategory.UNKNOWN

            Log.d(TAG, "[$deviceName ($currentDeviceType)] onDescriptorWrite. Char: $characteristicUUID, Status: $status")

            if (gattConnections[deviceAddress] != gatt) {
                Log.w(TAG, "[$deviceName] Callback de onDescriptorWrite para un GATT diferente. Ignorando.")
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptor.uuid == BluetoothConstants.CCCD_UUID) {
                    var message = "Suscripción exitosa."
                    if (currentDeviceType == DeviceCategory.OXIMETER && characteristicUUID == BluetoothConstants.BM1000_MEASUREMENT_CHARACTERISTIC_UUID) {
                        _oximeterConnectionStatus.value = BleConnectionStatus.SUBSCRIBED
                        _lastKnownOximeterAddress.value = deviceAddress
                        message = "Pulsioxímetro listo y recibiendo datos."
                        Log.i(TAG, "[$deviceName] Suscrito a datos del Pulsioxímetro.")
                    } else if (currentDeviceType == DeviceCategory.WEARABLE && characteristicUUID == BluetoothConstants.DISTANCE_CHARACTERISTIC_UUID) {
                        _wearableConnectionStatus.value = BleConnectionStatus.SUBSCRIBED
                        _lastKnownWearableAddress.value = deviceAddress
                        message = "Wearable ESP32 listo y recibiendo datos."
                        Log.i(TAG, "[$deviceName] Suscrito a datos de distancia del Wearable.")
                    } else {
                        Log.w(TAG, "[$deviceName] Descriptor escrito para característica desconocida o tipo no coincidente: $characteristicUUID, Tipo: $currentDeviceType")
                        // No debería ocurrir si el flujo es correcto
                        updateStatusAndEmitErrorForDevice(deviceAddress, currentDeviceType, BleConnectionStatus.ERROR_SUBSCRIBE_FAILED, "Error de lógica en suscripción.")
                        return
                    }
                    applicationScope.launch { _errorMessages.emit("[$deviceName] $message") }
                }
            } else {
                Log.e(TAG, "[$deviceName ($currentDeviceType)] Fallo en onDescriptorWrite. Status: $status")
                updateStatusAndEmitErrorForDevice(deviceAddress, currentDeviceType, BleConnectionStatus.ERROR_SUBSCRIBE_FAILED, "Fallo al escribir descriptor (status: $status).")
                cleanupGattConnection(gatt, currentDeviceType, BleConnectionStatus.ERROR_SUBSCRIBE_FAILED, false)
            }
        }

        // Ya no se usa la sobrecarga obsoleta de onCharacteristicChanged, usar la que incluye 'value'
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Esta sobrecarga es para API < 33. Si tu minSdk es 33+, esta no se llamará.
            // Si necesitas compatibilidad con API < 33, puedes replicar la lógica de la otra
            // onCharacteristicChanged aquí, obteniendo el valor de characteristic.getValue().
            // Pero es mejor usar la versión con el parámetro 'value'.
            // Log.d(TAG, "[LEGACY CB] onCharacteristicChanged for ${characteristic.uuid}")
            // val value = characteristic.value
            // if (value != null) {
            //    processCharacteristicChange(gatt, characteristic, value)
            // }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray // Este es el valor de la característica
        ) {
            // Esta es la callback preferida para API 33+ y también funciona en APIs anteriores
            // si el dispositivo la usa (la mayoría lo hace para notificaciones).
            // super.onCharacteristicChanged(gatt, characteristic, value) // No es necesario llamarlo si sobreescribes completamente

            processCharacteristicChange(gatt, characteristic, value)
        }
        @SuppressLint("MissingPermission")
        private fun processCharacteristicChange(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val deviceAddress = gatt.device.address
            val deviceName = try { gatt.device.name ?: deviceAddress } catch (e: SecurityException) { deviceAddress }
            val currentDeviceType = deviceTypes[deviceAddress]

            // Log.d(TAG, "[$deviceName ($currentDeviceType)] onCharacteristicChanged. UUID: ${characteristic.uuid}, Value: ${value.toHexString()}")

            if (gattConnections[deviceAddress] != gatt) {
                Log.w(TAG, "[$deviceName] Callback de onCharacteristicChanged para un GATT diferente. Ignorando.")
                return
            }

            when (currentDeviceType) {
                DeviceCategory.OXIMETER -> {
                    if (characteristic.uuid == BluetoothConstants.BM1000_MEASUREMENT_CHARACTERISTIC_UUID) {
                        parseOximeterData(value, deviceName)
                    } else {
                        Log.d(TAG, "[$deviceName (OXIMETER)] Característica cambiada no esperada: ${characteristic.uuid}")
                    }
                }
                DeviceCategory.WEARABLE -> {
                    if (characteristic.uuid == BluetoothConstants.DISTANCE_CHARACTERISTIC_UUID) {
                        parseWearableData(value, deviceName)
                    } else {
                        Log.d(TAG, "[$deviceName (WEARABLE)] Característica cambiada no esperada: ${characteristic.uuid}")
                    }
                }
                else -> { // DeviceType.UNKNOWN o null
                    Log.w(TAG, "[$deviceName] Característica cambiada para dispositivo de tipo desconocido o no rastreado.")
                    // Podrías intentar identificarlo aquí si aún no se hizo, aunque es menos probable
                }
            }
        }
    } // Fin de gattCallback

    private fun parseOximeterData(value: ByteArray, deviceNameForLog: String) {
        if (value.isNotEmpty() && value.size % 5 == 0) {
            val numberOfPackets = value.size / 5
            // Log.d(TAG, "[$deviceNameForLog] Oximeter Data Received: ${value.toHexString()} ($numberOfPackets packets)")

            for (i in 0 until numberOfPackets) {
                val packet = value.copyOfRange(i * 5, (i * 5) + 5)
                // Log.d(TAG, "[$deviceNameForLog] Processing oximeter packet ${i + 1}: ${packet.toHexString()}")

                if ((packet[0].toInt() and 0x80) == 0) {
                    Log.w(TAG, "[$deviceNameForLog] Oximeter Desync? Packet byte0 MSB is 0. Skipping.")
                    continue
                }

                val byte0 = packet[0].toInt()
                val byte1 = packet[1].toInt() and 0x7F
                val byte2 = packet[2].toInt()
                val byte3 = packet[3].toInt() and 0x7F
                val byte4 = packet[4].toInt() and 0x7F

                val signalStrengthS = byte0 and 0x0F
                val plethL = byte1
                val pulseRateBitP = (byte2 shr 6) and 0x01
                val pulseDetectionFlagR = (byte2 shr 5) and 0x01
                val fingerDetectionFlagC = (byte2 shr 4) and 0x01
                val visualPulseIntensityG = byte2 and 0x0F
                val pulseRateLsbsp = byte3
                val spo2X = byte4

                val finalSpo2 = if (spo2X == 127) null else spo2X.coerceIn(0, 100)
                val finalHRRaw = (pulseRateBitP shl 7) or pulseRateLsbsp
                val finalHR = if (finalHRRaw == 255) null else finalHRRaw

                var noFingerDetected = false
                if (fingerDetectionFlagC == 1) noFingerDetected = true
                else if (signalStrengthS == 15) noFingerDetected = true
                else if (spo2X == 127 || finalHRRaw == 255) noFingerDetected = true
                // else if (pulseDetectionFlagR == 0 && fingerDetectionFlagC == 0) noFingerDetected = false (calibrando)

                _oximeterDeviceData.value = BleDeviceData(
                    spo2 = if (noFingerDetected) null else finalSpo2,
                    heartRate = if (noFingerDetected) null else finalHR,
                    signalStrength = signalStrengthS,
                    noFingerDetected = noFingerDetected,
                    barGraphValue = visualPulseIntensityG,
                    plethValue = plethL,
                    timestamp = System.currentTimeMillis()
                )
                // Log.v(TAG, "[$deviceNameForLog] Parsed Oximeter: SpO2=${_oximeterDeviceData.value.spo2}, HR=${_oximeterDeviceData.value.heartRate}, NoFinger=${_oximeterDeviceData.value.noFingerDetected}")
            }
        } else if (value.isNotEmpty()) {
            Log.w(TAG, "[$deviceNameForLog] Paquete de oximetro con longitud inesperada: ${value.size}. Contenido: ${value.toHexString()}.")
        }
    }

    private fun parseWearableData(value: ByteArray, deviceNameForLog: String) {
        if (value.size == 4) { // Esperamos exactamente 4 bytes para un Int
            try {
                // Crear un ByteBuffer, establecer el orden a Little Endian (como en el ESP32)
                // y obtener el entero.
                val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
                val steps = buffer.int

                Log.d(TAG, "[$deviceNameForLog] Wearable Data Received (Int): Steps = $steps. Raw bytes: ${value.toHexString()}")
                _wearableDeviceData.value = WearableDeviceData(
                    totalSteps = steps,
                    timestamp = System.currentTimeMillis()
                )

            } catch (e: Exception) {
                Log.e(TAG, "[$deviceNameForLog] Excepción al parsear datos del wearable (esperando Int de 4 bytes): ${value.toHexString()}", e)
                _wearableDeviceData.value = _wearableDeviceData.value.copy(
                    totalSteps = null, // O mantener el valor anterior si se prefiere en caso de error
                    timestamp = System.currentTimeMillis()
                )
            }
        } else if (value.isNotEmpty()) {
            Log.w(TAG, "[$deviceNameForLog] Paquete del wearable con longitud inesperada: ${value.size}. Esperando 4 bytes para Int. Contenido: ${value.toHexString()}.")
            _wearableDeviceData.value = _wearableDeviceData.value.copy(
                totalSteps = null, // O mantener el valor anterior
                timestamp = System.currentTimeMillis()
            )
        } else {
            Log.w(TAG, "[$deviceNameForLog] Paquete del wearable vacío recibido.")
            // Opcional: actualizar _wearableDeviceData con valor nulo
            _wearableDeviceData.value = _wearableDeviceData.value.copy(
                totalSteps = null,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    // Helper para logs de ByteArrays (opcional)
    private fun ByteArray.toHexString(): String = joinToString(separator = " ") { eachByte -> "%02x".format(eachByte) }
    // private fun Byte.toHexString(): String = "%02x".format(this) // No la necesitas si la anterior es una extension function

    protected fun finalize() {
        Log.d(TAG, "BluetoothService finalized. Desregistrando receiver y cerrando conexiones.")
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Excepción al desregistrar bluetoothStateReceiver: ${e.localizedMessage}")
        }
        // Asegurarse de que todas las conexiones GATT se cierran y limpian
        disconnectAllInternal(BleConnectionStatus.DISCONNECTED_ERROR) // O un estado más apropiado para finalización
        reconnectJob?.cancel()
        Log.d(TAG, "Recursos limpiados en finalize.")
    }

} // Fin de la clase BluetoothServiceImpl
