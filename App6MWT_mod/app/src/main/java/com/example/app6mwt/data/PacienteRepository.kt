package com.example.app6mwt.data

import android.util.Log
import com.example.app6mwt.data.local.ConteoPorPaciente
import com.example.app6mwt.data.local.PacienteDao
import com.example.app6mwt.data.local.PruebaRealizadaDao
import com.example.app6mwt.data.model.Paciente
import com.example.app6mwt.data.model.PruebaRealizada
import com.example.app6mwt.ui.PacienteConHistorialReal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repositorio para gestionar los datos de Pacientes y PruebasRealizadas.
 * Actúa como una capa de abstracción entre los ViewModels (o casos de uso) y las fuentes de datos (DAOs).
 * Proporciona una API limpia para acceder y modificar los datos.
 * Es un Singleton, gestionado por Hilt para la inyección de dependencias.
 *
 * @property pacienteDao DAO para operaciones relacionadas con la entidad Paciente.
 * @property pruebaRealizadaDao DAO para operaciones relacionadas con la entidad PruebaRealizada.
 */
@Singleton
class PacienteRepository @Inject constructor(
    private val pacienteDao: PacienteDao,
    private val pruebaRealizadaDao: PruebaRealizadaDao
) {

    /**
     * Un Flow que emite la lista de todos los pacientes ordenados por su último acceso (más reciente primero).
     * Se obtiene directamente del DAO y se expone para ser observado por los ViewModels.
     */
    val todosLosPacientesOrdenados: Flow<List<Paciente>> = pacienteDao.observarTodosLosPacientes()

    /**
     * Inserta un nuevo paciente en la base de datos.
     * Actualiza el `ultimoAccesoTimestamp` del paciente al momento actual antes de insertarlo.
     * @param paciente El objeto Paciente a insertar.
     */
    suspend fun insertarPaciente(paciente: Paciente) {
        // Crea una copia del paciente con el timestamp de último acceso actualizado
        val pacienteConTimestamp = paciente.copy(ultimoAccesoTimestamp = System.currentTimeMillis())
        pacienteDao.insertarOActualizarPaciente(pacienteConTimestamp) // Usa el DAO para la inserción/actualización
    }

    /**
     * Elimina un paciente y todas sus pruebas asociadas de la base de datos.
     * Primero elimina las pruebas (debido a la dependencia de clave foránea con onDelete = CASCADE,
     * esto podría ser redundante si la BD lo maneja, pero es más explícito y seguro hacerlo así).
     * Luego elimina el paciente.
     * @param pacienteId El ID del paciente a eliminar.
     */
    suspend fun eliminarPaciente(pacienteId: String) {
        pruebaRealizadaDao.eliminarTodasLasPruebasDePaciente(pacienteId) // Elimina pruebas asociadas
        pacienteDao.eliminarPacientePorId(pacienteId) // Elimina el paciente
    }

    /**
     * Obtiene un paciente por su ID.
     * Si el paciente es encontrado, actualiza su `ultimoAccesoTimestamp`.
     * @param pacienteId El ID del paciente a obtener.
     * @return El objeto Paciente si se encuentra, o null en caso contrario.
     */
    suspend fun obtenerPacientePorId(pacienteId: String): Paciente? {
        val paciente = pacienteDao.obtenerPacientePorId(pacienteId)
        paciente?.let { // Si el paciente no es nulo
            actualizarAccesoPaciente(it.id) // Actualiza su timestamp de último acceso
        }
        return paciente
    }

    /**
     * Calcula el siguiente ID numérico disponible para un nuevo paciente.
     * Obtiene el ID máximo actual (tratado como numérico) y le suma 1.
     * Si no hay pacientes, comienza desde 1001 (o el valor base deseado).
     * @return El siguiente ID numérico.
     */
    suspend fun obtenerSiguienteIdNumerico(): Int {
        val maxId = pacienteDao.obtenerMaxIdNumerico() ?: 1000 // Si no hay IDs, empieza en 1000
        return maxId + 1
    }

    /**
     * Actualiza el `ultimoAccesoTimestamp` de un paciente específico al momento actual.
     * @param pacienteId El ID del paciente cuyo timestamp se va a actualizar.
     */
    suspend fun actualizarAccesoPaciente(pacienteId: String) {
        pacienteDao.actualizarTimestampAcceso(pacienteId, System.currentTimeMillis())
    }

    /**
     * Actualiza el nombre de un paciente y su `ultimoAccesoTimestamp` al momento actual.
     * @param pacienteId El ID del paciente a actualizar.
     * @param nuevoNombre El nuevo nombre para el paciente.
     */
    suspend fun actualizarNombrePaciente(pacienteId: String, nuevoNombre: String) {
        pacienteDao.actualizarNombreEImplicitamenteTimestamp(pacienteId, nuevoNombre, System.currentTimeMillis())
    }

    /**
     * Guarda una nueva prueba realizada en la base de datos.
     * Después de insertarla, intenta recuperar la prueba para obtener su `pruebaId` autogenerado.
     * Actualiza el estado `tieneHistorial` del paciente a `true`.
     * @param prueba La PruebaRealizada a guardar.
     * @return La PruebaRealizada guardada con su `pruebaId` asignado, o null si hubo un error al recuperarla.
     */
    suspend fun guardarPruebaRealizada(prueba: PruebaRealizada): PruebaRealizada? {
        Log.d("PacienteRepo", "Inicio de guardarPruebaRealizada para paciente ${prueba.pacienteId}, numeroPrueba ${prueba.numeroPruebaPaciente}")
        // El pruebaId será autogenerado por Room si `prueba.pruebaId` es 0 (valor por defecto).
        pruebaRealizadaDao.insertarPrueba(prueba)
        Log.i("PacienteRepo", "Llamada a pruebaRealizadaDao.insertarPrueba completada para paciente ${prueba.pacienteId}")

        // Después de insertar, obtenemos la prueba más reciente para tener su ID autogenerado
        val pruebaGuardadaConId = pruebaRealizadaDao.getPruebaMasRecienteParaPaciente(prueba.pacienteId)

        if (pruebaGuardadaConId != null && pruebaGuardadaConId.numeroPruebaPaciente == prueba.numeroPruebaPaciente) {
            // Comprobación adicional para asegurar que es la prueba correcta (comparando numeroPruebaPaciente).
            Log.i("PacienteRepo", "Prueba guardada y recuperada con ID: ${pruebaGuardadaConId.pruebaId} para paciente ${prueba.pacienteId}")
            actualizarEstadoHistorialPaciente(prueba.pacienteId, true) // Marca que el paciente ahora tiene historial
            return pruebaGuardadaConId
        } else {
            Log.e("PacienteRepo", "Error: No se pudo recuperar la prueba recién guardada o el numeroPruebaPaciente no coincide.")
            // Aunque falle la recuperación, se asume que algo se insertó, por lo que se actualiza el historial.
            actualizarEstadoHistorialPaciente(prueba.pacienteId, true)
            return null
        }
    }

    /**
     * Actualiza una prueba realizada existente en la base de datos.
     * @param prueba La PruebaRealizada con los datos actualizados.
     */
    suspend fun actualizarPruebaRealizada(prueba: PruebaRealizada) {
        Log.d("PacienteRepo", "Actualizando prueba ID ${prueba.pruebaId} para paciente ${prueba.pacienteId}")
        pruebaRealizadaDao.actualizarPrueba(prueba)
        Log.i("PacienteRepo", "Prueba ID ${prueba.pruebaId} actualizada.")
        // No es necesario actualizar `tieneHistorial` aquí, ya que la prueba ya existía,
        // por lo que el paciente ya debería tener `tieneHistorial` en true.
    }

    /**
     * Obtiene el `numeroPruebaPaciente` de una prueba específica por su ID.
     * @param idDeLaPrueba El ID de la prueba.
     * @return El número de prueba del paciente, o null si no se encuentra.
     */
    suspend fun getNumeroPruebaById(idDeLaPrueba: Int): Int? {
        return pruebaRealizadaDao.getNumeroPruebaById(idDeLaPrueba)
    }

    /**
     * Calcula el próximo número de prueba para un paciente.
     * Se basa en el conteo actual de pruebas que tiene ese paciente.
     * @param pacienteId El ID del paciente.
     * @return El siguiente número de prueba (conteo actual + 1).
     */
    suspend fun getProximoNumeroPruebaParaPaciente(pacienteId: String): Int {
        val numeroDePruebasExistentes =
            pruebaRealizadaDao.getConteoPruebasDePacienteSync(pacienteId)
        return numeroDePruebasExistentes + 1
    }

    /**
     * Actualiza el campo `tieneHistorial` de un paciente en la base de datos.
     * @param pacienteId El ID del paciente.
     * @param tieneHistorial El nuevo valor para `tieneHistorial`.
     */
    suspend fun actualizarEstadoHistorialPaciente(pacienteId: String, tieneHistorial: Boolean) {
        Log.d("PacienteRepo", "Actualizando estado historial para paciente ID: $pacienteId a: $tieneHistorial")
        try {
            pacienteDao.actualizarEstadoHistorial(pacienteId, tieneHistorial)
            Log.d("PacienteRepo", "Estado historial actualizado en DAO para paciente ID: $pacienteId")
        } catch (e: Exception) {
            Log.e("PacienteRepo", "Error al actualizar estado historial para ID: $pacienteId", e)
        }
    }

    /**
     * Combina el flujo de todos los pacientes con el flujo de conteos de pruebas por paciente.
     * El objetivo es obtener una lista de `PacienteConHistorialReal`, donde `tieneHistorialReal`
     * se calcula basándose en si el conteo de pruebas es > 0.
     * Esto permite tener una visión actualizada del estado del historial, incluso si el campo
     * `paciente.tieneHistorial` no estuviera perfectamente sincronizado.
     * Incluye logging para depurar discrepancias.
     * @return Un Flow que emite una lista de `PacienteConHistorialReal`.
     */
    fun getPacientesConEstadoHistorialCombinado(): Flow<List<PacienteConHistorialReal>> {
        return todosLosPacientesOrdenados // Flujo de pacientes
            .combine( // Combina con otro flujo
                pruebaRealizadaDao.observarTodosLosConteosDePruebas() // Flujo de conteos de pruebas
                    .onStart { emit(emptyList<ConteoPorPaciente>()) } // Emite lista vacía al inicio para que combine funcione si un flujo emite primero
                    .distinctUntilChanged() // Emite solo si la lista de conteos ha cambiado
            ) { pacientes, conteos -> // Lambda que se ejecuta cuando cualquiera de los flujos emite un nuevo valor
                Log.d("RepoCombine", "Combinando ${pacientes.size} pacientes con ${conteos.size} registros de conteo.")
                // Convierte la lista de conteos a un mapa para búsqueda eficiente por pacienteId
                val conteosMap = conteos.associateBy({ it.pacienteId }, { it.conteoPruebas })

                pacientes.map { paciente -> // Mapea cada paciente a PacienteConHistorialReal
                    val conteoActual = conteosMap[paciente.id] ?: 0 // Obtiene el conteo, 0 si no existe
                    val tieneHistorialRealCalculado = conteoActual > 0 // Calcula si realmente tiene historial

                    // Log para detectar si el flag 'tieneHistorial' en la BD difiere del estado real calculado
                    if (paciente.tieneHistorial != tieneHistorialRealCalculado) {
                        Log.w("RepoCombineMap", "Discrepancia para Paciente ID: ${paciente.id}. " +
                                "BD.tieneHistorial: ${paciente.tieneHistorial}, " +
                                "Conteo ($conteoActual) implica: $tieneHistorialRealCalculado")
                        // Aquí se podría añadir lógica para sincronizar paciente.tieneHistorial si se deseara
                    }

                    PacienteConHistorialReal(
                        paciente = paciente,
                        tieneHistorialReal = tieneHistorialRealCalculado
                    )
                }
            }
            .onStart { Log.d("RepoCombine", "getPacientesConEstadoHistorialCombinado Flow iniciado.") } // Log al iniciar el flujo combinado
            .catch { e -> // Manejo de errores en el flujo combinado
                Log.e("RepoCombine", "Error en getPacientesConEstadoHistorialCombinado Flow", e)
                emit(emptyList<PacienteConHistorialReal>()) // Emite lista vacía en caso de error para que el colector no falle
            }
    }

    /**
     * Obtiene la prueba más reciente realizada por un paciente específico.
     * @param pacienteId El ID del paciente.
     * @return La PruebaRealizada más reciente, o null si el paciente no tiene pruebas.
     */
    suspend fun getPruebaMasRecienteParaPaciente(pacienteId: String): PruebaRealizada? {
        return pruebaRealizadaDao.getPruebaMasRecienteParaPaciente(pacienteId)
    }
}
