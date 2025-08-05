package com.example.app6mwt.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app6mwt.data.PacienteRepository
import com.example.app6mwt.data.model.Paciente
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.example.app6mwt.data.DefaultThresholdValues
import com.example.app6mwt.data.SettingsRepository

/**
 * Clase sellada para representar eventos de navegación que este ViewModel puede emitir.
 * La UI observará estos eventos y realizará la navegación correspondiente.
 */
sealed class NavigationEvent {
    /** Navegar a la pantalla de preparación del test. */
    data class ToPreparationScreen(
        val patientId: String,
        val patientName: String,
        val patientHasHistory: Boolean
    ) : NavigationEvent()

    /** Navegar a la pantalla de historial del paciente. */
    data class ToHistoryScreen(val patientId: String) : NavigationEvent()

    /** Evento para indicar que la aplicación debe cerrarse. */
    object ExitApp : NavigationEvent()
}

/**
 * Data class para combinar un objeto `Paciente` con un booleano que indica
 * si realmente tiene un historial de pruebas (calculado, no solo el flag de la BD).
 */
data class PacienteConHistorialReal(
    val paciente: Paciente,
    val tieneHistorialReal: Boolean
)

/**
 * Data class para mantener el estado de todos los campos del diálogo de ajustes.
 * Todos los campos son Strings para facilitar la entrada del usuario en TextField,
 * y se validarán/convertirán antes de guardar.
 * @property validationError Mensaje de error a mostrar si la validación falla.
 */
data class AllSettingsDialogState(
    // Umbrales de Alarma para la ejecución del test
    val spo2Warning: String = "",
    val spo2Critical: String = "",
    val hrCriticalLow: String = "",
    val hrWarningLow: String = "",
    val hrWarningHigh: String = "",
    val hrCriticalHigh: String = "",
    // Rangos de Entrada Aceptables para registro basal/post
    val spo2InputMin: String = "",
    val spo2InputMax: String = "",
    val hrInputMin: String = "",
    val hrInputMax: String = "",
    val bpSystolicMin: String = "",
    val bpSystolicMax: String = "",
    val bpDiastolicMin: String = "",
    val bpDiastolicMax: String = "",
    val rrMin: String = "",
    val rrMax: String = "",
    val borgMin: String = "",
    val borgMax: String = "",
    // Para mostrar mensajes de error de validación en el diálogo
    val validationError: String? = null
)

/**
 * ViewModel para la pantalla de gestión de pacientes (`PatientManagementScreen`).
 * Responsable de:
 * - Mantener y exponer el estado de la UI (lista de pacientes, paciente seleccionado, estados de diálogos, etc.).
 * - Manejar la lógica de negocio (búsqueda, adición, edición, eliminación de pacientes).
 * - Interactuar con los repositorios (`PacienteRepository`, `SettingsRepository`) para obtener y guardar datos.
 * - Emitir eventos de navegación.
 *
 * @param repository Repositorio para operaciones de datos de pacientes.
 * @param settingsRepository Repositorio para operaciones de ajustes de la aplicación.
 */
@HiltViewModel
class PatientManagementViewModel @Inject constructor(
    private val repository: PacienteRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    /** Texto actual de la consulta de búsqueda introducido por el usuario. */
    var searchQuery by mutableStateOf("")
        private set // Solo modificable dentro del ViewModel

    /**
     * `StateFlow` que emite la información del paciente actualmente seleccionado (o null si no hay ninguno).
     * Incluye el objeto `Paciente` y si tiene historial real.
     */
    private val _selectedPatientInfo = MutableStateFlow<PacienteConHistorialReal?>(null)
    val selectedPatientInfo: StateFlow<PacienteConHistorialReal?> = _selectedPatientInfo.asStateFlow() // Exposición pública inmutable

    /** Mensaje informativo para mostrar al usuario en la UI. */
    var infoMessage by mutableStateOf("Seleccione un paciente o realice una búsqueda.")
        private set

    /** Controla la visibilidad del diálogo de ajustes generales. */
    var showSettingsDialog by mutableStateOf(false)
        private set

    /** Mantiene el estado actual de los campos en el diálogo de ajustes. */
    var alarmThresholdsDialogState by mutableStateOf(AllSettingsDialogState())
        private set

    /**
     * `StateFlow` que emite la lista de pacientes con su estado de historial real.
     * Se obtiene combinando datos del `PacienteRepository`.
     * El operador `onEach` se usa para logging y para actualizar `_selectedPatientInfo`
     * si el paciente seleccionado cambia en la lista (ej. se elimina o su estado de historial cambia).
     * `stateIn` convierte un Flow frío en un StateFlow caliente, compartiendo la emisión entre colectores.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // Necesario para stateIn
    val pacientesConEstadoHistorial: StateFlow<List<PacienteConHistorialReal>> =
        repository.getPacientesConEstadoHistorialCombinado()
            .onEach { updatedList -> // Se ejecuta cada vez que el Flow emite una nueva lista
                Log.d("PatientMgmtVM", "[pacientesConEstadoHistorial] Nueva lista recibida. Elementos: ${updatedList.size}")
                val currentSelected = _selectedPatientInfo.value
                if (currentSelected != null) {
                    // Busca el paciente seleccionado en la lista actualizada.
                    val updatedSelectedPatientInList = updatedList.find { it.paciente.id == currentSelected.paciente.id }
                    if (updatedSelectedPatientInList != null) {
                        // Si el paciente seleccionado ha cambiado (ej. su `tieneHistorialReal`), actualiza `_selectedPatientInfo`.
                        if (updatedSelectedPatientInList != currentSelected) {
                            Log.d("PatientMgmtVM", "[pacientesConEstadoHistorial] Actualizando _selectedPatientInfo para ${updatedSelectedPatientInList.paciente.id}. " +
                                    "Nuevo tieneHistorialReal: ${updatedSelectedPatientInList.tieneHistorialReal}.")
                            _selectedPatientInfo.value = updatedSelectedPatientInList
                        }
                    } else {
                        // Si el paciente seleccionado ya no está en la lista (ej. fue eliminado), deselecciónalo.
                        Log.w("PatientMgmtVM", "[pacientesConEstadoHistorial] Paciente seleccionado (${currentSelected.paciente.id}) ya no está en la lista. Deseleccionando.")
                        _selectedPatientInfo.value = null
                    }
                }
            }
            .stateIn( // Convierte el Flow en un StateFlow.
                scope = viewModelScope, // El StateFlow vivirá mientras el ViewModel esté activo.
                started = SharingStarted.WhileSubscribed(5000L), // El flujo se inicia cuando hay suscriptores y se detiene 5s después de que el último se vaya.
                initialValue = emptyList() // Valor inicial mientras se carga la primera lista.
            )

    // --- Estados para controlar la visibilidad y contenido de varios diálogos ---
    /** Controla la visibilidad del diálogo "Añadir Paciente". */
    var showAddPatientDialog by mutableStateOf(false)
        private set
    /** Nombre introducido para un nuevo paciente. */
    var newPatientNameInput by mutableStateOf("")
        private set
    /** Indica si se está editando el nombre de un paciente existente. */
    var isEditingPatientName by mutableStateOf(false)
        private set
    /** Valor actual del nombre durante la edición. */
    var editingPatientNameValue by mutableStateOf("")
        private set
    /** Controla la visibilidad del diálogo de confirmación para eliminar un paciente. */
    var showDeletePatientConfirmationDialog by mutableStateOf(false)
        private set

    /**
     * Contador para generar IDs numéricos para nuevos pacientes.
     * Se inicializa obteniendo el siguiente ID disponible del repositorio.
     * Aunque los IDs son Strings, se gestiona un componente numérico para la generación.
     */
    private var nextPatientIdCounter by mutableIntStateOf(1001) // Valor inicial por defecto
    init {
        // Bloque de inicialización del ViewModel.
        viewModelScope.launch {
            // Carga el siguiente ID numérico disponible desde el repositorio al iniciar el ViewModel.
            nextPatientIdCounter = repository.obtenerSiguienteIdNumerico()
            Log.d("PatientMgmtVM", "ViewModel INIT. Hash: ${this.hashCode()}")
        }
    }

    /**
     * `MutableSharedFlow` para emitir eventos de navegación.
     * `SharedFlow` es adecuado para eventos que deben ser consumidos una vez (eventos "one-shot").
     */
    private val _navigateToEvent = MutableSharedFlow<NavigationEvent>()
    val navigateToEvent = _navigateToEvent.asSharedFlow() // Exposición pública inmutable.

    /**
     * Actualiza `searchQuery` cuando el texto de búsqueda cambia en la UI.
     * Restaura el mensaje informativo si la búsqueda se borra y no hay paciente seleccionado.
     * @param query El nuevo texto de búsqueda.
     */
    fun onSearchQueryChanged(query: String) {
        searchQuery = query
        if (query.isBlank() && selectedPatientInfo.value == null) {
            infoMessage = "Seleccione un paciente o realice una búsqueda."
        }
    }

    /**
     * Realiza una búsqueda de paciente por ID.
     * Si se encuentra, lo selecciona y actualiza su timestamp de último acceso.
     * Si no, muestra un mensaje de error.
     */
    fun performSearch() {
        if (searchQuery.isBlank()) {
            infoMessage = "Ingrese un ID para buscar."
            return
        }
        viewModelScope.launch {
            // Busca en la lista actual de pacientes (obtenida de `pacientesConEstadoHistorial`).
            val foundInList = pacientesConEstadoHistorial.value.find { it.paciente.id == searchQuery }
            if (foundInList != null) {
                _selectedPatientInfo.value = foundInList
                infoMessage = "Paciente: ${foundInList.paciente.nombre} (ID: ${foundInList.paciente.id})"
                repository.actualizarAccesoPaciente(foundInList.paciente.id) // Actualiza timestamp
            } else {
                _selectedPatientInfo.value = null
                infoMessage = "Búsqueda errónea, el paciente ID '$searchQuery' no está registrado o la lista no está actualizada."
            }
        }
    }

    /**
     * Selecciona un paciente de la lista.
     * Actualiza `_selectedPatientInfo` y el mensaje informativo.
     * Actualiza el timestamp de último acceso del paciente.
     * @param pacienteConInfo El paciente seleccionado de la lista.
     */
    fun onPatientSelectedFromList(pacienteConInfo: PacienteConHistorialReal) {
        _selectedPatientInfo.value = pacienteConInfo
        infoMessage = "Paciente: ${pacienteConInfo.paciente.nombre} (ID: ${pacienteConInfo.paciente.id})"
        viewModelScope.launch {
            repository.actualizarAccesoPaciente(pacienteConInfo.paciente.id)
        }
    }

    // --- Gestión del diálogo "Añadir Paciente" ---

    /** Abre el diálogo para añadir un nuevo paciente, reseteando el campo de nombre. */
    fun onOpenAddPatientDialog() {
        newPatientNameInput = "" // Limpia el campo de entrada
        showAddPatientDialog = true
        infoMessage = "Registrando un nuevo paciente."
    }

    /** Cierra el diálogo de añadir paciente y restaura el mensaje informativo. */
    fun onCloseAddPatientDialog() {
        showAddPatientDialog = false
        restoreInfoMessageAfterDialog() // Restaura el mensaje según el estado actual
    }

    /** Actualiza `newPatientNameInput` cuando el usuario escribe en el campo de nombre. */
    fun onNewPatientNameChange(name: String) {
        newPatientNameInput = name
    }

    /**
     * Confirma la adición de un nuevo paciente.
     * Valida el nombre.
     * Genera un nuevo ID, crea el objeto `Paciente` y lo inserta a través del repositorio.
     * Selecciona al nuevo paciente y actualiza el `nextPatientIdCounter`.
     */
    fun onConfirmAddPatient() {
        val trimmedName = newPatientNameInput.trim() // Elimina espacios al inicio y final
        // Validaciones básicas del nombre
        if (trimmedName.isBlank()) {
            infoMessage = "Error: El nombre del nuevo paciente no puede estar vacío."
            return
        }
        if (trimmedName.any { it.isDigit() }) { // Comprueba si el nombre contiene números
            infoMessage = "Error: El nombre del nuevo paciente no debe contener números."
            return
        }

        viewModelScope.launch {
            val actualSiguienteIdNumerico = repository.obtenerSiguienteIdNumerico() // Obtiene el ID numérico fresco del repo
            val newId = actualSiguienteIdNumerico.toString() // Convierte a String para el ID del Paciente

            Log.d("PatientMgmtVM", "onConfirmAddPatient: Usando ID $newId (obtenido de repo) para el nuevo paciente.")
            val newPatient = Paciente(
                id = newId,
                nombre = trimmedName,
                tieneHistorial = false, // Un nuevo paciente no tiene historial inicialmente
                ultimoAccesoTimestamp = System.currentTimeMillis() // Establece el timestamp de creación/acceso
            )
            repository.insertarPaciente(newPatient) // Inserta en la base de datos

            // Crea la representación enriquecida para la UI
            val nuevoPacienteConInfo = PacienteConHistorialReal(
                paciente = newPatient,
                tieneHistorialReal = false
            )
            _selectedPatientInfo.value = nuevoPacienteConInfo // Selecciona al nuevo paciente
            // Actualiza el mensaje informativo principal
            // (Este mensaje se sobrescribirá por el de abajo, considerar cuál es el deseado)
            infoMessage = "Paciente: ${nuevoPacienteConInfo.paciente.nombre} (ID: ${nuevoPacienteConInfo.paciente.id})"

            // Actualiza el contador interno para el *próximo* ID (aunque no se use directamente para el siguiente paciente si siempre se consulta el repo)
            val proximoIdDespuesDeInsertar = repository.obtenerSiguienteIdNumerico()
            nextPatientIdCounter = proximoIdDespuesDeInsertar
            Log.d("PatientMgmtVM", "onConfirmAddPatient: Nuevo paciente registrado. nextPatientIdCounter (estado) actualizado a: $proximoIdDespuesDeInsertar")

            // Mensaje de éxito más específico para la adición
            infoMessage = "Nuevo paciente '${newPatient.nombre}' (ID: ${newPatient.id}) registrado."
        }
        onCloseAddPatientDialog() // Cierra el diálogo
    }

    // --- Gestión del diálogo "Editar Nombre Paciente" ---

    /**
     * Inicia la edición del nombre del paciente seleccionado.
     * Si hay un paciente seleccionado, muestra el diálogo de edición con su nombre actual.
     */
    fun onStartEditPatientName() {
        _selectedPatientInfo.value?.let { // Solo si hay un paciente seleccionado
            editingPatientNameValue = it.paciente.nombre // Carga el nombre actual para edición
            isEditingPatientName = true // Muestra el diálogo/estado de edición
            infoMessage = "Editando nombre para paciente ID: ${it.paciente.id}"
        } ?: run { // Si no hay paciente seleccionado
            infoMessage = "Seleccione un paciente para poder editar su nombre."
        }
    }

    /** Cancela la edición del nombre y restaura el mensaje informativo. */
    fun onCancelEditPatientName() {
        isEditingPatientName = false
        restoreInfoMessageAfterDialog()
    }

    /** Actualiza `editingPatientNameValue` mientras el usuario edita el nombre. */
    fun onEditingPatientNameChange(updatedName: String) {
        editingPatientNameValue = updatedName
    }

    /**
     * Confirma la edición del nombre del paciente.
     * Valida el nuevo nombre.
     * Actualiza el nombre en el repositorio.
     */
    fun onConfirmEditPatientName() {
        val currentPatientInfo = _selectedPatientInfo.value
        if (currentPatientInfo == null) { // Chequeo de seguridad
            infoMessage = "Error interno: No hay paciente seleccionado para editar."
            isEditingPatientName = false
            return
        }

        val proposedName = editingPatientNameValue.trim()
        // Validaciones del nombre
        if (proposedName.isBlank()) {
            infoMessage = "Error: El nombre del paciente no puede estar vacío."
            return
        }
        if (proposedName.any { it.isDigit() }) {
            infoMessage = "Error: El nombre del paciente no debe contener números."
            return
        }

        viewModelScope.launch {
            repository.actualizarNombrePaciente(currentPatientInfo.paciente.id, proposedName)
            // La actualización de `_selectedPatientInfo` con el nuevo nombre
            // ocurrirá reactivamente a través del flow `pacientesConEstadoHistorial`.
            infoMessage = "Nombre del paciente ID '${currentPatientInfo.paciente.id}' actualizado a '$proposedName'."
        }
        isEditingPatientName = false // Cierra el estado/diálogo de edición
    }

    // --- Navegación ---

    /**
     * Emite un evento para navegar a la pantalla de preparación del test
     * para el paciente seleccionado.
     */
    fun onPrepareTestClicked() {
        _selectedPatientInfo.value?.let { info ->
            viewModelScope.launch {
                _navigateToEvent.emit(
                    NavigationEvent.ToPreparationScreen(
                        info.paciente.id,
                        info.paciente.nombre,
                        info.tieneHistorialReal
                    )
                )
            }
        } ?: run {
            infoMessage = "Por favor, seleccione un paciente para preparar la prueba."
        }
    }

    /**
     * Emite un evento para navegar a la pantalla de historial del paciente seleccionado,
     * solo si el paciente tiene historial.
     */
    fun onViewHistoryClicked() {
        _selectedPatientInfo.value?.let { info ->
            if (info.tieneHistorialReal) { // Solo navega si hay historial real
                viewModelScope.launch {
                    _navigateToEvent.emit(NavigationEvent.ToHistoryScreen(info.paciente.id))
                }
            } else {
                infoMessage = "El paciente ID ${info.paciente.id} (${info.paciente.nombre}) no tiene historial de pruebas."
            }
        } ?: run {
            infoMessage = "Por favor, seleccione un paciente para ver su historial."
        }
    }

    // --- Gestión del diálogo "Eliminar Paciente" ---

    /**
     * Solicita la eliminación del paciente seleccionado.
     * Muestra el diálogo de confirmación si hay un paciente seleccionado y no se está editando su nombre.
     */
    fun requestDeleteSelectedPatient() {
        if (_selectedPatientInfo.value != null && !isEditingPatientName) {
            showDeletePatientConfirmationDialog = true // Muestra el diálogo de confirmación
        } else if (_selectedPatientInfo.value == null) {
            infoMessage = "Seleccione un paciente para eliminarlo."
        } else if (isEditingPatientName) { // No permitir eliminar si se está editando el nombre
            infoMessage = "Termine la edición del nombre antes de eliminar al paciente."
        }
    }


    /**
     * Confirma la eliminación del paciente seleccionado.
     * Llama al repositorio para eliminarlo y actualiza el mensaje informativo.
     * El paciente se deseleccionará reactivamente a través de `pacientesConEstadoHistorial`.
     */
    fun confirmDeletePatient() {
        _selectedPatientInfo.value?.paciente?.let { patientToDelete -> // Asegura que hay un paciente para eliminar
            viewModelScope.launch {
                repository.eliminarPaciente(patientToDelete.id)
                // El _selectedPatientInfo.value se volverá null reactivamente si el paciente es eliminado de la lista.
                infoMessage = "Paciente ${patientToDelete.nombre} (ID: ${patientToDelete.id}) eliminado."
            }
        }
        showDeletePatientConfirmationDialog = false // Cierra el diálogo
    }

    /** Cancela la eliminación del paciente y cierra el diálogo. */
    fun cancelDeletePatient() {
        showDeletePatientConfirmationDialog = false
        restoreInfoMessageAfterDialog() // Restaura mensaje informativo
    }

    /**
     * Restaura el mensaje informativo principal después de cerrar un diálogo.
     * El mensaje depende de si hay un paciente seleccionado o de la búsqueda actual.
     */
    private fun restoreInfoMessageAfterDialog() {
        _selectedPatientInfo.value?.let { info -> // Si hay un paciente seleccionado
            infoMessage = "Paciente: ${info.paciente.nombre} (ID: ${info.paciente.id})"
        } ?: run { // Si no hay paciente seleccionado
            if (searchQuery.isBlank()) { // Y la búsqueda está vacía
                infoMessage = "Seleccione un paciente o realice una búsqueda."
            }else if (!infoMessage.startsWith("Búsqueda errónea")) { // Y no hay un error de búsqueda activo
                // Si había una query pero no resultó en un paciente seleccionado (o se deseleccionó),
                // este mensaje podría ser un poco ambiguo. Se podría refinar.
                infoMessage = "Realice una nueva búsqueda o seleccione un paciente."
            }
            // Si infoMessage ya es "Búsqueda errónea...", se mantiene.
        }
    }

    /** Controla la visibilidad del diálogo de confirmación para salir de la aplicación. */
    var showExitAppConfirmationDialog by mutableStateOf(false)
        private set

    /** Muestra el diálogo de confirmación para salir de la aplicación. */
    fun requestExitApp() {
        showExitAppConfirmationDialog = true
    }

    /** Confirma la salida de la aplicación emitiendo el evento de navegación correspondiente. */
    fun confirmExitApp() {
        showExitAppConfirmationDialog = false
        viewModelScope.launch { _navigateToEvent.emit(NavigationEvent.ExitApp) }
    }

    /** Cancela la salida de la aplicación y cierra el diálogo. */
    fun cancelExitApp() {
        showExitAppConfirmationDialog = false
    }

    // --- Funciones para el Diálogo de Ajustes Generales ---

    /**
     * Abre el diálogo de ajustes.
     * Carga los valores actuales de los ajustes desde `SettingsRepository`
     * y los establece en `alarmThresholdsDialogState`.
     */
    fun onOpenSettingsDialog() {
        viewModelScope.launch {
            // Carga todos los ajustes y los convierte a String para los TextFields del diálogo.
            // .first() se usa para obtener el valor actual de los Flows de DataStore.
            alarmThresholdsDialogState = AllSettingsDialogState(
                spo2Warning = settingsRepository.spo2WarningThresholdFlow.first().toString(),
                spo2Critical = settingsRepository.spo2CriticalThresholdFlow.first().toString(),
                hrCriticalLow = settingsRepository.hrCriticalLowThresholdFlow.first().toString(),
                hrWarningLow = settingsRepository.hrWarningLowThresholdFlow.first().toString(),
                hrWarningHigh = settingsRepository.hrWarningHighThresholdFlow.first().toString(),
                hrCriticalHigh = settingsRepository.hrCriticalHighThresholdFlow.first().toString(),

                spo2InputMin = settingsRepository.spo2InputMinFlow.first().toString(),
                spo2InputMax = settingsRepository.spo2InputMaxFlow.first().toString(),
                hrInputMin = settingsRepository.hrInputMinFlow.first().toString(),
                hrInputMax = settingsRepository.hrInputMaxFlow.first().toString(),
                bpSystolicMin = settingsRepository.bpSystolicInputMinFlow.first().toString(),
                bpSystolicMax = settingsRepository.bpSystolicInputMaxFlow.first().toString(),
                bpDiastolicMin = settingsRepository.bpDiastolicInputMinFlow.first().toString(),
                bpDiastolicMax = settingsRepository.bpDiastolicInputMaxFlow.first().toString(),
                rrMin = settingsRepository.rrInputMinFlow.first().toString(),
                rrMax = settingsRepository.rrInputMaxFlow.first().toString(),
                borgMin = settingsRepository.borgInputMinFlow.first().toString(),
                borgMax = settingsRepository.borgInputMaxFlow.first().toString()
            )
            showSettingsDialog = true // Muestra el diálogo
        }
    }

    /** Cierra el diálogo de ajustes y limpia cualquier error de validación previo. */
    fun onCloseSettingsDialog() {
        showSettingsDialog = false
        // Limpia el mensaje de error de validación al cerrar.
        alarmThresholdsDialogState = alarmThresholdsDialogState.copy(validationError = null)
    }

    /**
     * Actualiza `alarmThresholdsDialogState` cuando cambian los valores en el diálogo.
     * Limpia el error de validación al cambiar cualquier valor, para que el usuario pueda corregir.
     * @param newDialogState El nuevo estado del diálogo con los valores actualizados.
     */
    fun onSettingsDialogStateChanged(newDialogState: AllSettingsDialogState) {
        // Actualiza el estado y limpia el error de validación para permitir la corrección.
        alarmThresholdsDialogState = newDialogState.copy(validationError = null)
    }

    /**
     * Guarda los ajustes introducidos en el diálogo.
     * Primero, convierte los valores String a Int y realiza una validación exhaustiva.
     * Si hay errores, los muestra en el diálogo.
     * Si todo es válido, guarda los ajustes a través de `SettingsRepository` y cierra el diálogo.
     */
    fun saveSettings() {
        val currentState = alarmThresholdsDialogState // Estado actual de los campos del diálogo

        // --- Conversión de String a Int? (nullable Int) para validación ---
        // toIntOrNull() devuelve null si la conversión falla, lo que facilita la validación.
        val spo2Warning = currentState.spo2Warning.toIntOrNull()
        val spo2Critical = currentState.spo2Critical.toIntOrNull()
        val hrCriticalLow = currentState.hrCriticalLow.toIntOrNull()
        val hrWarningLow = currentState.hrWarningLow.toIntOrNull()
        val hrWarningHigh = currentState.hrWarningHigh.toIntOrNull()
        val hrCriticalHigh = currentState.hrCriticalHigh.toIntOrNull()

        val spo2Min = currentState.spo2InputMin.toIntOrNull()
        val spo2Max = currentState.spo2InputMax.toIntOrNull()
        val hrMin = currentState.hrInputMin.toIntOrNull()
        val hrMax = currentState.hrInputMax.toIntOrNull()
        val bpSysMin = currentState.bpSystolicMin.toIntOrNull()
        val bpSysMax = currentState.bpSystolicMax.toIntOrNull()
        val bpDiaMin = currentState.bpDiastolicMin.toIntOrNull()
        val bpDiaMax = currentState.bpDiastolicMax.toIntOrNull()
        val rrMin = currentState.rrMin.toIntOrNull()
        val rrMax = currentState.rrMax.toIntOrNull()
        val borgMin = currentState.borgMin.toIntOrNull()
        val borgMax = currentState.borgMax.toIntOrNull()

        // --- Validación Lógica Detallada de los valores convertidos ---
        val errors = mutableListOf<String>() // Lista para acumular mensajes de error

        // Validación Umbrales de Alarma
        if (spo2Warning == null || spo2Warning !in 0..100) errors.add("SpO2 Alerta (alarma) inválido (0-100).")
        if (spo2Critical == null || spo2Critical !in 0..100) errors.add("SpO2 Crítico (alarma) inválido (0-100).")
        if (spo2Warning != null && spo2Critical != null && spo2Critical >= spo2Warning) {
            errors.add("SpO2 Crítico (alarma) debe ser menor que SpO2 Alerta (alarma).")
        }

        if (hrCriticalLow == null || hrCriticalLow <= 0) errors.add("FC Crítica Baja (alarma) inválida (>0).")
        if (hrWarningLow == null || hrWarningLow <= 0) errors.add("FC Alerta Baja (alarma) inválida (>0).")
        if (hrWarningHigh == null || hrWarningHigh <= 0) errors.add("FC Alerta Alta (alarma) inválida (>0).")
        if (hrCriticalHigh == null || hrCriticalHigh <= 0) errors.add("FC Crítica Alta (alarma) inválida (>0).")

        // Validaciones de orden entre umbrales de FC
        if (hrCriticalLow != null && hrWarningLow != null && hrCriticalLow >= hrWarningLow) {
            errors.add("FC Crítica Baja (alarma) debe ser < FC Alerta Baja (alarma).")
        }
        if (hrWarningLow != null && hrWarningHigh != null && hrWarningLow >= hrWarningHigh) {
            errors.add("FC Alerta Baja (alarma) debe ser < FC Alerta Alta (alarma).")
        }
        if (hrWarningHigh != null && hrCriticalHigh != null && hrWarningHigh >= hrCriticalHigh) {
            errors.add("FC Alerta Alta (alarma) debe ser < FC Crítica Alta (alarma).")
        }

        // Validación de Rangos de Entrada (similar a los umbrales)
        if (spo2Min == null || spo2Min !in 0..100) errors.add("SpO2 Mín (entrada) inválido (0-100).")
        if (spo2Max == null || spo2Max !in 0..100) errors.add("SpO2 Máx (entrada) inválido (0-100).")
        if (spo2Min != null && spo2Max != null && spo2Min > spo2Max) errors.add("SpO2 Mín (entrada) > SpO2 Máx (entrada).")

        if (hrMin == null || hrMin <= 0) errors.add("FC Mín (entrada) inválida (>0).")
        if (hrMax == null || hrMax <= 0) errors.add("FC Máx (entrada) inválida (>0).")
        if (hrMin != null && hrMax != null && hrMin > hrMax) errors.add("FC Mín (entrada) > FC Máx (entrada).")

        // Validación cruzada entre TAS y TAD
        // Esta validación se omite si los valores son los por defecto, para permitir que la app inicie sin error
        // si los valores por defecto para TAS min y TAD min son iguales (o TAS max y TAD max).
        // Sería más robusto si los valores por defecto siempre cumplieran esta condición.
        if (bpSysMin == null || bpSysMin <= 0) errors.add("TAS Mín (entrada) inválida (>0).")
        if (bpSysMax == null || bpSysMax <= 0) errors.add("TAS Máx (entrada) inválida (>0).")
        if (bpSysMin != null && bpSysMax != null && bpSysMin > bpSysMax) errors.add("TAS Mín (entrada) > TAS Máx (entrada).")

        if (bpDiaMin == null || bpDiaMin <= 0) errors.add("TAD Mín (entrada) inválida (>0).")
        if (bpDiaMax == null || bpDiaMax <= 0) errors.add("TAD Máx (entrada) inválida (>0).")
        if (bpDiaMin != null && bpDiaMax != null && bpDiaMin > bpDiaMax) errors.add("TAD Mín (entrada) > TAD Máx (entrada).")

        if (bpSysMin != null && bpDiaMin != null && bpSysMin <= bpDiaMin && bpSysMin != DefaultThresholdValues.BP_SYSTOLIC_INPUT_MIN_DEFAULT && bpDiaMin != DefaultThresholdValues.BP_DIASTOLIC_INPUT_MIN_DEFAULT) {
            // Solo validar si no son los valores por defecto, para permitir inicio sin error si son iguales
            errors.add("TAS Mín (entrada) debe ser > TAD Mín (entrada).")
        }
        if (bpSysMax != null && bpDiaMax != null && bpSysMax <= bpDiaMax && bpSysMax != DefaultThresholdValues.BP_SYSTOLIC_INPUT_MAX_DEFAULT && bpDiaMax != DefaultThresholdValues.BP_DIASTOLIC_INPUT_MAX_DEFAULT) {
            errors.add("TAS Máx (entrada) debe ser > TAD Máx (entrada).")
        }


        if (rrMin == null || rrMin <= 0) errors.add("FR Mín (entrada) inválida (>0).")
        if (rrMax == null || rrMax <= 0) errors.add("FR Máx (entrada) inválida (>0).")
        if (rrMin != null && rrMax != null && rrMin > rrMax) errors.add("FR Mín (entrada) > FR Máx (entrada).")

        if (borgMin == null || borgMin < 0) errors.add("Borg Mín (entrada) inválido (>=0).")
        if (borgMax == null || borgMax < 0) errors.add("Borg Máx (entrada) inválido (>=0).") // Borg puede ser 0
        if (borgMin != null && borgMax != null && borgMin > borgMax) errors.add("Borg Mín (entrada) > Borg Máx (entrada).")

        // --- Si hay errores de validación ---
        if (errors.isNotEmpty()) {
            // Concatena todos los mensajes de error en una sola cadena, separados por saltos de línea.
            val fullErrorMessage = errors.joinToString("\n")
            Log.e("PatientMgmtVM", "Error de validación al guardar ajustes:\n$fullErrorMessage")
            // Actualiza el estado del diálogo para mostrar el mensaje de error.
            alarmThresholdsDialogState = currentState.copy(validationError = fullErrorMessage)
            return // Detiene la ejecución de saveSettings si hay errores.
        }

        // --- Si todas las validaciones pasan ---
        // En este punto, sabemos que todas las conversiones a Int? fueron exitosas y
        // los valores son lógicamente válidos, por lo que los operadores '!!' (not-null assertion) son seguros.
        viewModelScope.launch { // Ejecuta el guardado en una corutina.
            settingsRepository.saveAllSettings(
                spo2Warning = spo2Warning!!, spo2Critical = spo2Critical!!,
                hrCriticalLow = hrCriticalLow!!, hrWarningLow = hrWarningLow!!,
                hrWarningHigh = hrWarningHigh!!, hrCriticalHigh = hrCriticalHigh!!,
                spo2InputMin = spo2Min!!, spo2InputMax = spo2Max!!,
                hrInputMin = hrMin!!, hrInputMax = hrMax!!,
                bpSystolicMin = bpSysMin!!, bpSystolicMax = bpSysMax!!,
                bpDiastolicMin = bpDiaMin!!, bpDiastolicMax = bpDiaMax!!,
                rrMin = rrMin!!, rrMax = rrMax!!,
                borgMin = borgMin!!, borgMax = borgMax!!
            )
            Log.d("PatientMgmtVM", "Todos los ajustes guardados correctamente en SettingsRepository.")
            onCloseSettingsDialog() // Cierra el diálogo de ajustes si todo se guardó correctamente.
        }
    }
}
