package com.example.app6mwt.data.local

import androidx.room.*
import com.example.app6mwt.data.model.Paciente
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) para la entidad Paciente.
 * Define los métodos para interactuar con la tabla de pacientes en la base de datos.
 * Todas las funciones que realizan operaciones de escritura (insert, update, delete)
 * o consultas que pueden tardar son marcadas como 'suspend' para ser usadas en corutinas.
 * Las funciones que devuelven Flow permiten observar cambios en los datos de forma reactiva.
 */
@Dao
interface PacienteDao {
    /**
     * Inserta un nuevo paciente o actualiza uno existente si ya existe un paciente
     * con el mismo 'id' (clave primaria).
     * La estrategia de conflicto REPLACE asegura que la nueva entrada reemplaza a la antigua.
     * @param paciente El objeto Paciente a insertar o actualizar.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarOActualizarPaciente(paciente: Paciente)

    /**
     * Elimina un paciente de la base de datos basándose en su ID.
     * @param pacienteId El ID del paciente a eliminar.
     */
    @Query("DELETE FROM pacientes WHERE id = :pacienteId")
    suspend fun eliminarPacientePorId(pacienteId: String)

    /**
     * Obtiene un paciente específico de la base de datos por su ID.
     * Puede devolver null si no se encuentra ningún paciente con ese ID.
     * @param id El ID del paciente a buscar.
     * @return El objeto Paciente si se encuentra, o null en caso contrario.
     */
    @Query("SELECT * FROM pacientes WHERE id = :id")
    suspend fun obtenerPacientePorId(id: String): Paciente?

    /**
     * Observa todos los pacientes en la base de datos, ordenados por su último
     * timestamp de acceso en orden descendente (los más recientes primero).
     * Devuelve un Flow, lo que permite a la UI actualizarse automáticamente
     * cuando los datos de los pacientes cambian.
     * @return Un Flow que emite una lista de todos los pacientes.
     */
    @Query("SELECT * FROM pacientes ORDER BY ultimoAccesoTimestamp DESC")
    fun observarTodosLosPacientes(): Flow<List<Paciente>>

    /**
     * Obtiene el valor máximo del campo 'id' (tratado como numérico) de la tabla de pacientes.
     * Útil para generar nuevos IDs secuenciales si los IDs son numéricos.
     * Puede devolver null si la tabla está vacía.
     * @return El ID numérico máximo, o null si la tabla está vacía.
     */
    @Query("SELECT MAX(CAST(id AS INTEGER)) FROM pacientes")
    suspend fun obtenerMaxIdNumerico(): Int?

    /**
     * Actualiza el campo 'tieneHistorial' de un paciente específico.
     * @param pacienteId El ID del paciente a actualizar.
     * @param tieneHistorial El nuevo valor para el campo 'tieneHistorial'.
     */
    @Query("UPDATE pacientes SET tieneHistorial = :tieneHistorial WHERE id = :pacienteId")
    suspend fun actualizarEstadoHistorial(pacienteId: String, tieneHistorial: Boolean)

    /**
     * Actualiza el campo 'ultimoAccesoTimestamp' de un paciente específico.
     * @param pacienteId El ID del paciente a actualizar.
     * @param timestamp El nuevo valor para el timestamp de último acceso.
     */
    @Query("UPDATE pacientes SET ultimoAccesoTimestamp = :timestamp WHERE id = :pacienteId")
    suspend fun actualizarTimestampAcceso(pacienteId: String, timestamp: Long)

    /**
     * Actualiza el nombre de un paciente y, como efecto secundario implícito por la operación,
     * también actualiza su 'ultimoAccesoTimestamp' al valor proporcionado.
     * @param pacienteId El ID del paciente a actualizar.
     * @param nuevoNombre El nuevo nombre para el paciente.
     * @param timestamp El nuevo timestamp de último acceso.
     */
    @Query("UPDATE pacientes SET nombre = :nuevoNombre, ultimoAccesoTimestamp = :timestamp WHERE id = :pacienteId")
    suspend fun actualizarNombreEImplicitamenteTimestamp(pacienteId: String, nuevoNombre: String, timestamp: Long)

}
