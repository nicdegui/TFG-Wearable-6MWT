package com.example.app6mwt.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.app6mwt.data.model.PruebaRealizada
import kotlinx.coroutines.flow.Flow

/**
 * Data class para representar el resultado de una consulta que cuenta
 * el número de pruebas por paciente.
 */
data class ConteoPorPaciente(
    val pacienteId: String, // ID del paciente
    val conteoPruebas: Int // Número total de pruebas para ese paciente
)

/**
 * Data Access Object (DAO) para la entidad PruebaRealizada.
 * Proporciona métodos para interactuar con la tabla 'pruebas_realizadas' en la base de datos.
 * Incluye operaciones CRUD (Crear, Leer, Actualizar, Borrar) y consultas específicas.
 */
@Dao
interface PruebaRealizadaDao {

    /**
     * Inserta una nueva prueba realizada o actualiza una existente si ya existe
     * una prueba con el mismo 'pruebaId' (clave primaria).
     * La estrategia de conflicto REPLACE asegura que la nueva entrada reemplaza a la antigua.
     * @param prueba La objeto PruebaRealizada a insertar o actualizar.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarPrueba(prueba: PruebaRealizada)

    /**
     * Actualiza una prueba realizada existente en la base de datos.
     * @param prueba El objeto PruebaRealizada con los datos actualizados.
     */
    @Update
    suspend fun actualizarPrueba(prueba: PruebaRealizada)

    /**
     * Observa todas las pruebas realizadas para un paciente específico, ordenadas
     * por fecha (timestamp) en orden descendente (las más recientes primero).
     * @param pacienteId El ID del paciente cuyas pruebas se quieren observar.
     * @return Un Flow que emite una lista de pruebas realizadas para el paciente.
     */
    @Query("SELECT * FROM pruebas_realizadas WHERE pacienteId = :pacienteId ORDER BY fechaTimestamp DESC")
    fun observarPruebasDePaciente(pacienteId: String): Flow<List<PruebaRealizada>>

    /**
     * Obtiene el número total de pruebas realizadas para un paciente específico.
     * Es una función síncrona (dentro de una corutina) que devuelve el conteo directamente.
     * @param pacienteId El ID del paciente.
     * @return El número total de pruebas para ese paciente.
     */
    @Query("SELECT COUNT(pruebaId) FROM pruebas_realizadas WHERE pacienteId = :pacienteId")
    suspend fun getConteoPruebasDePacienteSync(pacienteId: String): Int


    /**
     * Elimina una prueba específica de la base de datos por su ID de prueba.
     * @param idDeLaPrueba El ID de la prueba a eliminar.
     */
    @Query("DELETE FROM pruebas_realizadas WHERE pruebaId = :idDeLaPrueba")
    suspend fun eliminarPruebaPorSuId(idDeLaPrueba: Int)

    /**
     * Elimina todas las pruebas realizadas asociadas a un paciente específico.
     * @param pacienteId El ID del paciente cuyas pruebas serán eliminadas.
     */
    @Query("DELETE FROM pruebas_realizadas WHERE pacienteId = :pacienteId")
    suspend fun eliminarTodasLasPruebasDePaciente(pacienteId: String)

    /**
     * Observa el conteo de pruebas para cada paciente.
     * Agrupa las pruebas por 'pacienteId' y cuenta cuántas hay en cada grupo.
     * @return Un Flow que emite una lista de objetos ConteoPorPaciente.
     */
    @Query("SELECT pacienteId, COUNT(pruebaId) as conteoPruebas FROM pruebas_realizadas GROUP BY pacienteId")
    fun observarTodosLosConteosDePruebas(): Flow<List<ConteoPorPaciente>>

    /**
     * Obtiene una lista de todas las pruebas realizadas para un paciente específico.
     * No está ordenada explícitamente aquí, el orden dependerá de la implementación de la BD (generalmente por inserción).
     * @param pacienteId El ID del paciente.
     * @return Una lista de objetos PruebaRealizada.
     */
    @Query("SELECT * FROM pruebas_realizadas WHERE pacienteId = :pacienteId")
    suspend fun getPruebasPorPacienteIdDirecto(pacienteId: String): List<PruebaRealizada>

    /**
     * Obtiene la prueba más reciente realizada por un paciente específico, basándose en 'fechaTimestamp'.
     * Puede devolver null si el paciente no tiene pruebas.
     * @param pacienteId El ID del paciente.
     * @return La PruebaRealizada más reciente, o null si no hay ninguna.
     */
    @Query("SELECT * FROM pruebas_realizadas WHERE pacienteId = :pacienteId ORDER BY fechaTimestamp DESC LIMIT 1")
    suspend fun getPruebaMasRecienteParaPaciente(pacienteId: String): PruebaRealizada?

    /**
     * Obtiene el campo 'numeroPruebaPaciente' para una prueba específica, identificada por su 'pruebaId'.
     * 'numeroPruebaPaciente' podría ser un contador de cuántas pruebas ha realizado ese paciente.
     * Puede devolver null si la prueba no se encuentra.
     * @param idDeLaPrueba El ID de la prueba.
     * @return El número de prueba del paciente, o null si no se encuentra.
     */
    @Query("SELECT numeroPruebaPaciente FROM pruebas_realizadas WHERE pruebaId = :idDeLaPrueba")
    suspend fun getNumeroPruebaById(idDeLaPrueba: Int): Int?

}
