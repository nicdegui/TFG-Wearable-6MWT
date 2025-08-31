# Sistema integral para la automatización de la 6MWT

**Diseño y desarrollo de un dispositivo vestible para automatizar la prueba de la marcha de seis minutos en pacientes con patología respiratoria**

Trabajo de Fin de Grado (TFG) de Nicolás Gabriel Díez Guillán para el Grado en Ingeniería en Electrónica Industrial y Automática de la Universidade de Vigo.

---

## 📜 Descripción del proyecto

Este repositorio contiene el código fuente de un sistema integral diseñado para automatizar por completo la **Prueba de la marcha de seis minutos (6MWT)**. La solución se compone de un **dispositivo vestible a medida** (hardware + firmware) y una **aplicación Android** modificada que funciona como unidad de control.

El objetivo principal es eliminar la última tarea manual del procedimiento clínico: el conteo de la distancia. Al automatizar la adquisición de este dato, el sistema reduce la carga cognitiva del personal sanitario y aumenta la precisión y granularidad de los datos recogidos.

### ✨ Funcionalidades del sistema

El sistema se divide en dos componentes software principales:

#### Dispositivo vestible (firmware)
*   **Lectura de sensores:** inicialización y lectura continua de los datos del sensor inercial (IMU LSM9DS1).
*   **Detección de pasos:** implementación de un algoritmo para procesar la señal del acelerómetro y contar los pasos en tiempo real.
*   **Comunicación BLE:** creación de un servicio **Bluetooth Low Energy (BLE)** con una característica personalizada para transmitir el número de pasos total a la aplicación Android.

#### Aplicación Android (modificada)
*   **Gestión de doble conexión BLE:** refactorización del módulo de comunicación para conectar y gestionar datos de **dos dispositivos simultáneamente**: el pulsioxímetro y el nuevo dispositivo vestible.
*   **Integración automática de pasos y cálculo de distancia:** recepción e integración de los datos de pasos en la pantalla de ejecución, junto con datos antropométricos del paciente de la pantalla de preparación para calcular la distancia recorrida, reemplazando la entrada manual de vueltas.
*   **Monitorización completa:** mantiene todas las funcionalidades de la versión original (gestión de pacientes, SpO₂, FC, PDF, etc.), pero ahora con datos de distancia automáticos y de mayor resolución.
*   **Visualización de datos:** gráficas en tiempo real que combinan los parámetros fisiológicos (SpO₂, FC) con el esfuerzo realizado (distancia).

---

## 🛠️ Tecnologías utilizadas

Este proyecto combina el desarrollo de software embebido y el desarrollo de aplicaciones móviles.

#### Firmware & hardware
*   **Lenguaje:** C++ (Framework Arduino)
*   **Entorno de Desarrollo:** PlatformIO en VSCode
*   **Hardware Principal:** microcontrolador Seeed Studio XIAO ESP32-S3 y sensor IMU LSM9DS1.
*   **Comunicación:** Bluetooth Low Energy (BLE)

#### Aplicación Android
*   **Lenguaje:** [Kotlin](https://kotlinlang.org/)
*   **UI Toolkit:** [Jetpack Compose](https://developer.android.com/jetpack/compose)
*   **Arquitectura:** MVVM (Model-View-ViewModel)
*   **Asincronía:** [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) & Flow
*   **Inyección de dependencias:** [Hilt](https://developer.android.com/training/dependency-injection/hilt-android)
*   **Persistencia:** [Room](https://developer.android.com/training/data-storage/room) y [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
*   **Comunicación:** API nativa de Android para [Bluetooth Low Energy (BLE)](https://developer.android.com/guide/topics/connectivity/bluetooth/ble)
*   **Gráficas:** [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart)
*   **PDF:** API nativa de Android (`PdfDocument` y `Canvas`)

---
---

## 👤 Autor

*   **Nicolás Gabriel Díez Guillán**
