package com.example.app6mwt.bluetooth

import java.util.UUID

/**
 * Contiene constantes relacionadas con la comunicación Bluetooth,
 * incluyendo UUIDs para servicios y características de dispositivos BLE.
 */
object BluetoothConstants {
    // --- Pulsioxímetro BM1000 ---
    /**
     * UUID del servicio principal del dispositivo BM1000B.
     * Este servicio agrupa las características relevantes para la medición.
     */
    val BM1000_SERVICE_UUID: UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")

    /**
     * UUID de la característica BLE utilizada para recibir las mediciones
     * (SpO2, frecuencia cardíaca, etc.) desde el dispositivo BM1000B.
     * Esta característica soporta notificaciones.
     */
    val BM1000_MEASUREMENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")

    // --- ESP32 Wearable ---
    /**
     * UUID del servicio principal del wearable ESP32 para la medición de distancia.
     */
    val WEARABLE_SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b") // Tu UUID

    /**
     * UUID de la característica BLE utilizada para recibir los datos de distancia
     * desde el wearable ESP32.
     * Esta característica soporta notificaciones.
     */
    val DISTANCE_CHARACTERISTIC_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8") // Tu UUID

    // --- Común ---
    /**
     * UUID del Descriptor de Configuración de Característica del Cliente (CCCD).
     * Este descriptor se utiliza para habilitar o deshabilitar notificaciones o indicaciones
     * para una característica específica.
     */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
}
