package com.example.app6mwt.di

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class que representa el estado de los datos de recuperación post-prueba.
 * Utilizada para comunicar información como SpO2, HR, si el periodo de recuperación ha terminado
 * y si se capturaron datos durante dicho periodo.
 *
 * @property spo2 Valor de SpO2 medido durante la recuperación (nullable si no hay dato o timeout).
 * @property hr Valor de Frecuencia Cardíaca medida durante la recuperación (nullable).
 * @property isRecoveryPeriodOver Indica si el tiempo de recuperación ha finalizado.
 * @property wasDataCapturedDuringPeriod Indica si se recibieron datos válidos (SpO2/HR) durante el periodo.
 */
data class RecoveryData(
    val spo2: Int? = null,
    val hr: Int? = null,
    val isRecoveryPeriodOver: Boolean,
    val wasDataCapturedDuringPeriod: Boolean
)

/**
 * Clase Singleton (gestionada por Hilt) diseñada para mantener y emitir el estado
 * de los datos de recuperación de una prueba (`RecoveryData`).
 * Utiliza un `MutableSharedFlow` con `replay = 1` para que el último estado emitido
 * esté disponible para nuevos observadores (colectores) que se suscriban tarde.
 * Esto es útil para que, por ejemplo, un ViewModel pueda obtener el último estado de recuperación
 * incluso si se crea después de que el estado haya sido emitido.
 *
 * El propósito principal es desacoplar la producción de los datos de recuperación
 * (posiblemente desde `TestExecutionViewModel`) de su consumo (posiblemente en `TestResultsViewModel`).
 */
@Singleton
class TestStateHolder @Inject constructor() { // Hilt se encargará de crear e inyectar esta clase como Singleton.
    // _recoveryDataFlow: Es un MutableSharedFlow privado.
    // 'replay = 1' significa que el último valor emitido se guarda y se entrega a cualquier nuevo colector.
    // Es útil para estados donde los consumidores pueden llegar tarde.
    // Si solo se necesita el valor más reciente y siempre debe haber un valor inicial,
    // MutableStateFlow podría ser una alternativa. SharedFlow es más flexible para eventos o
    // cuando no necesariamente hay un valor inicial "activo" hasta la primera emisión real.
    private val _recoveryDataFlow = MutableSharedFlow<RecoveryData>(replay = 1)

    /**
     * Flujo público e inmutable (`SharedFlow`) que expone los datos de recuperación.
     * Los ViewModels u otros componentes pueden colectar (observar) este flujo para reaccionar a los cambios.
     */
    val recoveryDataFlow = _recoveryDataFlow.asSharedFlow()

    /**
     * Publica (emite) un nuevo estado de `RecoveryData` al `_recoveryDataFlow`.
     * Utiliza `tryEmit` que es una función no suspendible y segura para llamar desde corutinas
     * cuando se usa con `MutableSharedFlow(replay=1)`.
     * Si la emisión inmediata no es posible (buffer lleno, aunque raro con replay=1 y un único emisor principal),
     * se registra una advertencia. Se podría considerar `emit` (suspendible) si la entrega garantizada
     * bajo presión es crítica.
     *
     * @param data El objeto `RecoveryData` a emitir.
     */
    suspend fun postRecoveryData(data: RecoveryData) {
        val emitted = _recoveryDataFlow.tryEmit(data)
        if (!emitted) {
            // Esta situación es poco común con replay=1 si el flujo de datos no es extremadamente rápido
            // y los colectores no están bloqueados por mucho tiempo.
            println("WARN: TestStateHolder - No se pudo emitir RecoveryData inmediatamente.")
            // Considerar _recoveryDataFlow.emit(data) si la emisión es crítica y se puede suspender.
        }
    }

    /**
     * Restablece el estado de recuperación a un valor inicial o "limpio".
     * Útil para evitar que datos de una prueba anterior se muestren si un ViewModel
     * observa el flujo antes de que se emitan nuevos datos para la prueba actual.
     * Emite un `RecoveryData` que representa un estado "pendiente" o "inicial".
     */
    fun resetRecoveryState() {
        _recoveryDataFlow.tryEmit(
            RecoveryData(
                spo2 = null, // Sin datos
                hr = null, // Sin datos
                isRecoveryPeriodOver = false, // El periodo no ha terminado (o no ha empezado)
                wasDataCapturedDuringPeriod = false // No se han capturado datos aún
            )
        )
    }
}
