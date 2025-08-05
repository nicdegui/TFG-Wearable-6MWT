package com.example.app6mwt.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.app6mwt.ui.theme.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.app6mwt.data.model.Paciente
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.mikephil.charting.BuildConfig

/**
 * Pantalla principal para la gestión de pacientes.
 * Muestra una lista de pacientes, permite buscar, añadir, editar, eliminar,
 * y navegar a otras pantallas relacionadas con un paciente.
 *
 * @param viewModel El [PatientManagementViewModel] que proporciona el estado y la lógica para esta pantalla.
 * @param onNavigateToPreparation Callback para navegar a la pantalla de preparación de test.
 * @param onNavigateToHistory Callback para navegar a la pantalla de historial del paciente.
 * @param onExitApp Callback para manejar la acción de salir de la aplicación.
 */
@OptIn(ExperimentalMaterial3Api::class) // Necesario para componentes de Material 3 como Scaffold, CenterAlignedTopAppBar, etc.
@Composable
fun PatientManagementScreen(
    viewModel: PatientManagementViewModel = hiltViewModel(), // Obtiene la instancia del ViewModel usando Hilt.
    onNavigateToPreparation: (patientId: String, patientName: String, patientHasHistory: Boolean) -> Unit,
    onNavigateToHistory: (patientId: String) -> Unit,
    onExitApp: () -> Unit
) {
    // Colecta los estados del ViewModel como estados de Compose.
    // `collectAsStateWithLifecycle` es la forma recomendada para observar Flows en Compose,
    // ya que maneja correctamente la suscripción/cancelación según el ciclo de vida del Composable.
    val pacientesConEstado by viewModel.pacientesConEstadoHistorial.collectAsStateWithLifecycle()
    val currentSelectedPatientInfo by viewModel.selectedPatientInfo.collectAsStateWithLifecycle()

    // `LaunchedEffect` para observar eventos de navegación del ViewModel.
    // Se ejecuta una vez cuando `viewModel` (la clave) cambia o en la composición inicial.
    // Recolecta el `SharedFlow` `navigateToEvent` del ViewModel.
    LaunchedEffect(key1 = viewModel) {
        viewModel.navigateToEvent.collect { event ->
            when (event) {
                is NavigationEvent.ToPreparationScreen -> {
                    onNavigateToPreparation(
                        event.patientId,
                        event.patientName,
                        event.patientHasHistory
                    )
                }
                is NavigationEvent.ToHistoryScreen -> {
                    onNavigateToHistory(event.patientId)
                }
                // is NavigationEvent.ToSettingsScreen -> onNavigateToSettings()
                is NavigationEvent.ExitApp -> onExitApp()
            }
        }
    }

    // Estado para mostrar el diálogo de confirmación de salida.
    val showExitAppDialog = viewModel.showExitAppConfirmationDialog

    // `BackHandler` intercepta el evento del botón "Atrás" del sistema.
    // Si está habilitado (`enabled = true`), ejecuta la lambda proporcionada en lugar de la acción por defecto.
    BackHandler(enabled = true) {
        viewModel.requestExitApp() // Solicita al ViewModel mostrar el diálogo de confirmación.
    }

    // `Scaffold` es un layout básico de Material Design que proporciona estructura
    // para elementos comunes como TopAppBar, BottomAppBar, FloatingActionButton, etc.
    Scaffold(
        topBar = {
            AppTopBar(title = "GESTIÓN DE PACIENTES") // Barra superior personalizada.
        }
    ) { innerPadding -> // `innerPadding` contiene los paddings necesarios para el contenido debajo del TopAppBar.
        Column(
            modifier = Modifier
                .padding(innerPadding) // Aplica el padding del Scaffold.
                .fillMaxSize() // Ocupa todo el espacio disponible.
                .padding(16.dp) // Padding adicional alrededor del contenido principal.
        ) {
            // Barra de búsqueda de pacientes.
            PatientSearchBar(
                query = viewModel.searchQuery,
                onQueryChanged = viewModel::onSearchQueryChanged, // Referencia a la función del ViewModel.
                onSearch = viewModel::performSearch,
                modifier = Modifier.fillMaxWidth() // Ocupa todo el ancho.
            )

            Spacer(modifier = Modifier.height(16.dp)) // Espacio vertical.

            // Layout principal dividido en dos paneles: tabla de pacientes (izquierda) y panel de acciones (derecha).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f), // Ocupa el espacio vertical restante gracias a `weight`.
                verticalAlignment = Alignment.Top // Alinea los elementos hijos al tope.
            ) {
                // Tabla que muestra todos los pacientes registrados.
                AllPatientsTable(
                    pacientes = pacientesConEstado,
                    onPatientClick = viewModel::onPatientSelectedFromList,
                    selectedPatientId = currentSelectedPatientInfo?.paciente?.id, // ID del paciente seleccionado para resaltarlo.
                    modifier = Modifier
                        .weight(0.6f) // Ocupa el 60% del ancho del Row.
                        .fillMaxHeight() // Ocupa toda la altura disponible.
                )

                Spacer(modifier = Modifier.width(16.dp)) // Espacio horizontal entre paneles.

                // Panel derecho con acciones para el paciente seleccionado e información.
                RightPanel(
                    selectedPatientInfo = currentSelectedPatientInfo,
                    onViewHistoryClick = viewModel::onViewHistoryClicked,
                    onEditPatientClick = viewModel::onStartEditPatientName,
                    onDeletePatientClick = viewModel::requestDeleteSelectedPatient,
                    modifier = Modifier
                        .weight(0.4f) // Ocupa el 40% del ancho del Row.
                        .fillMaxHeight(),
                    onPrepareTestClicked = viewModel::onPrepareTestClicked
                )
            }

            Spacer(modifier = Modifier.height(12.dp)) // Espacio vertical.

            // Fila inferior con botón de "Nuevo Paciente", caja de información y botón de ajustes.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min), // La altura se ajusta al contenido más alto de la fila.
                verticalAlignment = Alignment.CenterVertically, // Centra los elementos verticalmente.
                horizontalArrangement = Arrangement.SpaceBetween // Distribuye el espacio entre los elementos.
            ) {
                // Botón flotante extendido para añadir un nuevo paciente.
                ExtendedFloatingActionButton(
                    onClick = viewModel::onOpenAddPatientDialog,
                    containerColor = ButtonActionColor, // Color personalizado.
                    contentColor = Color.White,
                    icon = { Icon(Icons.Filled.Add, "Añadir paciente") },
                    text = { Text("Nuevo paciente", fontSize = 18.sp) },
                    modifier = Modifier.padding(end = 8.dp)
                )

                // Contenedor para la caja de información.
                Box(
                    modifier = Modifier
                        .weight(1f) // Ocupa el espacio restante en la fila.
                        .fillMaxHeight() // Se estira a la altura de la fila.
                        .padding(horizontal = 4.dp)
                ) {
                    InfoBox(
                        title = "Información:",
                        content = viewModel.infoMessage, // Mensaje dinámico del ViewModel.
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Botón de icono para abrir los ajustes.
                IconButton(
                    onClick = viewModel::onOpenSettingsDialog,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Ajustes",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary // Usa el color primario del tema.
                    )
                }
            }
        }
    }

    // --- DIÁLOGOS ---
    // La visibilidad de cada diálogo está controlada por una variable de estado en el ViewModel.

    // Diálogo para añadir un nuevo paciente.
    if (viewModel.showAddPatientDialog) {
        AddPatientDialog(
            patientName = viewModel.newPatientNameInput,
            onPatientNameChange = viewModel::onNewPatientNameChange,
            onConfirm = viewModel::onConfirmAddPatient,
            onDismiss = viewModel::onCloseAddPatientDialog
        )
    }

    // Diálogo para editar el nombre de un paciente.
    // Se muestra solo si `isEditingPatientName` es true y hay un paciente seleccionado.
    if (viewModel.isEditingPatientName && currentSelectedPatientInfo != null) {
        EditPatientNameDialog(
            currentName = viewModel.editingPatientNameValue,
            onNameChange = viewModel::onEditingPatientNameChange,
            onConfirm = viewModel::onConfirmEditPatientName,
            onDismiss = viewModel::onCancelEditPatientName, // onDismiss se llama al tocar fuera o con el botón atrás.
            onCancel = viewModel::onCancelEditPatientName // onCancel para el botón explícito "Cancelar".
        )
    }

    // Diálogo de confirmación para eliminar un paciente.
    // Se muestra si `showDeletePatientConfirmationDialog` es true y hay un paciente seleccionado.
    // El '!!' es seguro aquí porque la condición `currentSelectedPatientInfo != null` lo protege.
    if (viewModel.showDeletePatientConfirmationDialog && currentSelectedPatientInfo != null) {
        DeletePatientConfirmationDialog(
            patientName = currentSelectedPatientInfo!!.paciente.nombre,
            patientId = currentSelectedPatientInfo!!.paciente.id,
            onConfirm = viewModel::confirmDeletePatient,
            onCancel = viewModel::cancelDeletePatient,
            onDismiss = viewModel::cancelDeletePatient // Reutiliza cancel si se descarta tocando fuera.
        )
    }

    // Diálogo de confirmación para salir de la aplicación.
    if (showExitAppDialog) {
        ConfirmationDialog(
            title = "Confirmar Salida",
            text = "¿Está seguro de que desea salir de la aplicación?",
            onConfirm = viewModel::confirmExitApp,
            onDismiss = viewModel::cancelExitApp,
            confirmButtonText = "Salir",
            dismissButtonText = "Cancelar"
        )
    }

    // Diálogo para mostrar y modificar los ajustes de la aplicación.
    if (viewModel.showSettingsDialog) {
        SettingsDialog(
            currentDialogState = viewModel.alarmThresholdsDialogState,
            onDialogStateChange = viewModel::onSettingsDialogStateChanged,
            onSave = viewModel::saveSettings,
            onDismiss = viewModel::onCloseSettingsDialog,
            appVersion = BuildConfig.VERSION_NAME // Obtiene la versión de la app desde BuildConfig.
        )
    }
}

/**
 * Barra superior personalizada para la aplicación.
 * @param title El título a mostrar en la barra.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String) {
    CenterAlignedTopAppBar( // TopAppBar con el título centrado.
        title = {
            Text(
                text = title,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth() // Asegura que el texto (y por tanto el centrado) use todo el ancho.
            )
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor =  MaterialTheme.colorScheme.primary, // Color de fondo de la barra.
            titleContentColor = TextOnSecondary // Color del texto del título.
        )
    )
}

/**
 * Barra de búsqueda para pacientes.
 * @param query El texto actual de la búsqueda.
 * @param onQueryChanged Callback cuando el texto de búsqueda cambia.
 * @param onSearch Callback cuando se inicia una búsqueda (ej. al pulsar enter o el icono de búsqueda).
 * @param modifier Modificador para este Composable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientSearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current // Para controlar el foco (ej. quitar foco después de buscar).

    Row(
        modifier = modifier
            .height(56.dp) // Altura estándar para campos de texto.
            .background(ElementBackgroundColor, RoundedCornerShape(8.dp)) // Fondo y bordes redondeados.
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            placeholder = { Text("Buscar por ID de paciente...") }, // Texto de ayuda.
            singleLine = true, // El campo de texto no se expandirá a múltiples líneas.
            modifier = Modifier.weight(1f), // Ocupa el espacio disponible en la fila.
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors( // Personalización de colores del campo.
                focusedBorderColor = DarkerBlueHighlight,
                unfocusedBorderColor = LightBluePrimary.copy(alpha = 0.7f),
                cursorColor = DarkerBlueHighlight,
                // Fondos transparentes para que se vea el fondo del Row.
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            ),
            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search), // Botón "Search" en el teclado.
            keyboardActions = KeyboardActions(
                onSearch = {  // Acción al pulsar el botón "Search" del teclado.
                    onSearch()
                    focusManager.clearFocus() // Quita el foco del campo de texto.
                }
            )
        )
        IconButton(onClick = { // Botón de icono para iniciar la búsqueda.
            onSearch()
            focusManager.clearFocus()
        }) {
            Icon(Icons.Filled.Search, contentDescription = "Buscar", tint = DarkerBlueHighlight)
        }
    }
}

/**
 * Muestra una lista/tabla de todos los pacientes registrados.
 * @param pacientes Lista de pacientes con su estado de historial.
 * @param onPatientClick Callback cuando se hace clic en un paciente.
 * @param selectedPatientId ID del paciente actualmente seleccionado (para resaltarlo).
 * @param modifier Modificador para este Composable.
 */
@Composable
fun AllPatientsTable(
    pacientes: List<PacienteConHistorialReal>,
    onPatientClick: (PacienteConHistorialReal) -> Unit,
    selectedPatientId: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(ElementBackgroundColor, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = "Pacientes registrados",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 10.dp),
            color = MaterialTheme.colorScheme.onSurface
        )
        if (pacientes.isEmpty()) {
            // Mensaje a mostrar si no hay pacientes.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hay pacientes registrados en el sistema.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // `LazyColumn` para mostrar una lista scrollable de forma eficiente.
            // Solo compone y dibuja los elementos que son visibles en pantalla.
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp) // Espacio entre cada ítem.
            ) {
                // `items` define cómo se renderiza cada elemento de la lista.
                // `key` ayuda a Compose a identificar de forma única cada ítem para optimizar recomposiciones.
                items(pacientes, key = { pacienteInfoItem -> pacienteInfoItem.paciente.id }) { pacienteInfo ->
                    PatientRow(
                        paciente = pacienteInfo.paciente,
                        isSelected = pacienteInfo.paciente.id == selectedPatientId, // Determina si esta fila debe resaltarse.
                        onPatientClick = { onPatientClick(pacienteInfo) } // Pasa el objeto completo `PacienteConHistorialReal`.
                    )
                }
            }
        }
    }
}

/**
 * Representa una fila individual en la tabla de pacientes.
 * @param paciente El objeto Paciente a mostrar.
 * @param isSelected Booleano que indica si esta fila está seleccionada.
 * @param onPatientClick Callback cuando se hace clic en la fila.
 */
@Composable
fun PatientRow(
    paciente: Paciente,
    isSelected: Boolean,
    onPatientClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPatientClick() } // Hace toda la fila clickable.
            .background( // Cambia el color de fondo si la fila está seleccionada.
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                RoundedCornerShape(8.dp)
            )
            .padding(vertical = 10.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween // Nombre a la izquierda, ID a la derecha.
    ) {
        Text(
            paciente.nombre,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f) // Permite que el nombre ocupe el espacio disponible y se ajuste.
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "ID: ${paciente.id}",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) // ID con menor énfasis.
        )
    }
}

/**
 * Panel derecho que muestra acciones e información sobre el paciente seleccionado.
 * @param selectedPatientInfo Información del paciente seleccionado (puede ser null).
 * @param onViewHistoryClick Callback para ver el historial.
 * @param onEditPatientClick Callback para editar el nombre del paciente.
 * @param onDeletePatientClick Callback para eliminar el paciente.
 * @param modifier Modificador para este Composable.
 * @param onPrepareTestClicked Callback para preparar una nueva prueba.
 */
@Composable
fun RightPanel(
    selectedPatientInfo: PacienteConHistorialReal?,
    onViewHistoryClick: () -> Unit,
    onEditPatientClick: () -> Unit,
    onDeletePatientClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPrepareTestClicked: () -> Unit
) {
    // Determina si hay un paciente seleccionado y si puede ver historial para habilitar/deshabilitar botones.
    val isPatientActuallySelected = selectedPatientInfo != null
    val canViewHistory = selectedPatientInfo?.tieneHistorialReal == true

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(start = 8.dp, end = 8.dp, top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Botones de acción. Se habilitan/deshabilitan según si hay un paciente seleccionado.
        ActionButton(
            text = "Preparar prueba",
            onClick = onPrepareTestClicked,
            enabled = isPatientActuallySelected
        )
        Spacer(Modifier.height(5.dp)) // Espacio entre botones.
        ActionButton(
            text = "Ver historial",
            onClick = onViewHistoryClick,
            enabled = canViewHistory // Solo habilitado si el paciente tiene historial real.
        )
        Spacer(Modifier.height(5.dp))
        ActionButton(
            text = "Editar nombre",
            onClick = onEditPatientClick, // Abre el diálogo de edición
            enabled = isPatientActuallySelected
        )
        Spacer(Modifier.height(5.dp))
        ActionButton(
            text = "Eliminar paciente",
            onClick = onDeletePatientClick,
            enabled = isPatientActuallySelected,
        )
        Spacer(modifier = Modifier.weight(1f)) // Empuja los InfoBox hacia la parte inferior del panel.

        // Cajas de información sobre el paciente seleccionado.
        InfoBox(
            title = "Historial registrado:",
            content = when { // Lógica para mostrar el estado del historial.
                !isPatientActuallySelected -> "---" // No hay paciente seleccionado.
                selectedPatientInfo.tieneHistorialReal == true -> "Sí" // Tiene historial. (el !! es seguro por la condición previa)
                else -> "No" // No tiene historial.
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(5.dp))

        InfoBox(
            title = "ID paciente seleccionado:",
            content = selectedPatientInfo?.paciente?.id ?: "---", // Muestra el ID o "---".
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Diálogo para editar el nombre de un paciente.
 * @param currentName Nombre actual del paciente.
 * @param onNameChange Callback cuando el nombre cambia en el TextField.
 * @param onConfirm Callback cuando se confirma la edición.
 * @param onDismiss Callback cuando el diálogo se cierra (ej. tocando fuera o botón "Atrás").
 * @param onCancel Callback para el botón explícito de "Cancelar".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPatientNameDialog(
    currentName: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() } // Para solicitar foco en el TextField.

    Dialog(onDismissRequest = onDismiss) { // El diálogo se cierra si se toca fuera.
        Card( // Usa Card para el estilo del diálogo.
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .width(IntrinsicSize.Max), // El ancho se ajusta al contenido, evitando ser demasiado ancho.
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Editar nombre del paciente",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = currentName,
                    onValueChange = onNameChange,
                    label = { Text("Nuevo nombre del paciente", fontSize = 16.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester), // Asocia el FocusRequester.
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors( // Estilo del campo.
                        focusedBorderColor = DarkerBlueHighlight,
                        unfocusedBorderColor = LightBluePrimary.copy(alpha = 0.7f),
                        cursorColor = DarkerBlueHighlight,
                        focusedContainerColor = Color.Transparent, // Fondo del campo de texto
                        unfocusedContainerColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions.Default.copy(
                        capitalization = KeyboardCapitalization.Words, // Capitaliza cada palabra.
                        imeAction = ImeAction.Done // Acción "Done" en el teclado.
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() } // Al pulsar "Done", solo limpia el foco. La confirmación es por botón.
                    )
                )
                // Solicita el foco para el TextField cuando el diálogo aparece.
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }

                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End // Botones alineados a la derecha.
                ) {
                    TextButton(
                        onClick = onCancel, // Usa el callback `onCancel`.
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) // Color para "Cancelar".
                    ) {
                        Text("Cancelar", fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        enabled = currentName.isNotBlank(), // El botón "Confirmar" se habilita si el nombre no está vacío.
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ButtonActionColor,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Confirmar Edición", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Confirmar", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

/**
 * Diálogo para añadir un nuevo paciente.
 * Similar al EditPatientNameDialog pero para crear un nuevo paciente.
 * @param patientName Nombre actual introducido para el nuevo paciente.
 * @param onPatientNameChange Callback cuando el nombre cambia.
 * @param onConfirm Callback para confirmar la adición.
 * @param onDismiss Callback para cerrar el diálogo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPatientDialog(
    patientName: String,
    onPatientNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Registrar nuevo paciente",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = patientName,
                    onValueChange = onPatientNameChange,
                    label = { Text("Nombre completo del paciente", fontSize = 16.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DarkerBlueHighlight,
                        unfocusedBorderColor = LightBluePrimary.copy(alpha = 0.7f),
                        cursorColor = DarkerBlueHighlight,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions.Default.copy(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() } // Solo limpia foco, confirmación por botón.
                    )
                )
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }

                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss, // `onDismiss` se usa para la acción de "Cancelar"/cerrar.
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Cancelar", fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        enabled = patientName.isNotBlank(), // Habilitado si el nombre no está vacío.
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ButtonActionColor,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Registrar paciente", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Registrar", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

/**
 * Diálogo de confirmación estándar para eliminar un paciente.
 * @param patientName Nombre del paciente a eliminar.
 * @param patientId ID del paciente a eliminar.
 * @param onConfirm Callback si se confirma la eliminación.
 * @param onCancel Callback para el botón "Cancelar".
 * @param onDismiss Callback si el diálogo se cierra tocando fuera o con botón "Atrás".
 */
@Composable
fun DeletePatientConfirmationDialog(
    patientName: String,
    patientId: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog( // Composable estándar de Material para diálogos de alerta.
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Confirmar eliminación",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Text(
                text = "¿Está seguro de que quiere eliminar al paciente '$patientName' (ID: $patientId)? Esta acción no se puede deshacer.",
                fontSize = 16.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error, // Color rojo para indicar acción destructiva.
                    contentColor = MaterialTheme.colorScheme.onError // Color de texto sobre el color de error.
                )
            ) {
                Text("Eliminar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel // Botón para cancelar la acción.
            ) {
                Text("Cancelar")
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, // Color de fondo del diálogo.
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Botón de acción genérico utilizado en el `RightPanel`.
 * @param modifier Modificador para este Composable.
 * @param text Texto del botón.
 * @param onClick Callback cuando se hace clic en el botón.
 * @param enabled Si el botón está habilitado o no.
 * @param colors Colores del botón (permite personalización, con valores por defecto).
 */
@Composable
fun ActionButton(
    modifier: Modifier = Modifier,
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors( // Colores por defecto si no se especifican.
        containerColor = ButtonActionColor,
        contentColor = Color.White,
        disabledContainerColor = ButtonActionColor.copy(alpha = 0.5f), // Color cuando está deshabilitado.
        disabledContentColor = Color.White.copy(alpha = 0.7f) // Color del texto cuando está deshabilitado.
    )
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp), // Altura mínima, pero puede crecer si el texto es largo.
        shape = RoundedCornerShape(12.dp) // Bordes redondeados.
    ) {
        Text(text, fontSize = 18.sp, textAlign = TextAlign.Center) // Texto del botón.
    }
}

/**
 * Caja de información genérica con un título y contenido.
 * @param title Título de la caja de información.
 * @param content Contenido de la caja de información.
 * @param modifier Modificador para este Composable.
 */
@Composable
fun InfoBox(title: String, content: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ElementBackgroundColor, RoundedCornerShape(12.dp)) // Fondo y bordes redondeados.
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.Start // Alinea el texto a la izquierda.
    ) {
        Text(
            title,
            fontWeight = FontWeight.Bold, // Título en negrita.
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp)) // Espacio entre título y contenido.
        Text(
            content,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 1, // Asegura que al menos se muestre una línea.
            lineHeight = 20.sp // Altura de línea para mejorar legibilidad si el contenido es multilínea.
        )
    }
}

/**
 * Diálogo para mostrar y modificar los ajustes de la aplicación.
 * @param currentDialogState Estado actual de todos los campos del diálogo.
 * @param onDialogStateChange Callback cuando cambia algún valor en el diálogo.
 * @param onSave Callback para guardar los ajustes.
 * @param onDismiss Callback para cerrar el diálogo.
 * @param appVersion Versión actual de la aplicación (para mostrar).
 * @param appDescription Descripción de la aplicación (para mostrar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    currentDialogState: AllSettingsDialogState, // Estado que contiene todos los valores y errores del diálogo.
    onDialogStateChange: (AllSettingsDialogState) -> Unit, // Para actualizar el estado en el ViewModel.
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    appVersion: String = "1.0.3", // Valor por defecto, se puede sobrescribir.
    appDescription: String = "Aplicación para la gestión de pacientes y el registro de la Prueba de Marcha de Seis Minutos (6MWT)."
) {
    // Controlador para el teclado virtual.
    val keyboardController = LocalSoftwareKeyboardController.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            // Limita la altura máxima del diálogo al 90% de la altura de la pantalla.
            modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9).dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    "Ajustes e Información de la aplicación",
                    style = MaterialTheme.typography.headlineSmall, // Estilo de título grande.
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .align(Alignment.CenterHorizontally) // Centra el título.
                )

                // Contenido principal del diálogo, scrollable si es necesario.
                Column(modifier = Modifier
                    .weight(1f) // Ocupa el espacio vertical disponible antes de los botones.
                    .verticalScroll(rememberScrollState())) { // Permite scroll si el contenido es muy largo.

                    // --- Sección: Umbrales de Alarma ---
                    Text(
                        "Umbrales de Alarma (Durante la prueba)",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp, top = 8.dp)
                    )

                    SettingSectionTitle("SpO2 (%) - Alarma")
                    SettingsInputField(
                        label = "SpO2 Crítica (X ≤ SpO2 Crítica)",
                        value = currentDialogState.spo2Critical,
                        onValueChange = { newValue ->
                            // Validación básica: longitud máxima 3 y solo dígitos.
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(spo2Critical = newValue))
                            }
                        },
                    )
                    SettingsInputField(
                        label = "SpO2 Alerta (SpO2 Crítica < X < SpO2 Alerta)",
                        value = currentDialogState.spo2Warning,
                        onValueChange = { newValue ->
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(spo2Warning = newValue))
                            }
                        },
                    )
                    SettingHelpText("SpO2 Crítica < SpO2 Alerta < SpO2 Normal") // Texto de ayuda para la lógica.


                    SettingSectionTitle("Frecuencia Cardíaca (lpm) - Alarma", Modifier.padding(top = 16.dp))
                    SettingsInputField(
                        label = "FC Crítica Baja ( X < FC Crítica Baja)",
                        value = currentDialogState.hrCriticalLow,
                        onValueChange = { newValue ->
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(hrCriticalLow = newValue))
                            }
                        },
                    )
                    SettingsInputField(
                        label = "FC Alerta Baja (Crítica Baja ≤ X < Normal)",
                        value = currentDialogState.hrWarningLow,
                        onValueChange = { newValue ->
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(hrWarningLow = newValue))
                            }
                        },
                    )
                    SettingsInputField(
                        label = "FC Alerta Alta (Normal < X ≤ Crítica Alta)",
                        value = currentDialogState.hrWarningHigh,
                        onValueChange = { newValue ->
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(hrWarningHigh = newValue))
                            }
                        },
                    )
                    SettingsInputField(
                        label = "FC Crítica Alta ( X > Crítica Alta)",
                        value = currentDialogState.hrCriticalHigh,
                        onValueChange = { newValue ->
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(hrCriticalHigh = newValue))
                            }
                        },
                    )
                    SettingHelpText("Crítica Baja < Alerta Baja < Normal < Alerta Alta < Crítica Alta")

                    Divider(modifier = Modifier.padding(vertical = 20.dp)) // Separador visual.

                    // --- Sección: Rangos de Entrada Válidos ---
                    Text(
                        "Rangos de Entrada Válidos (Basal/Post-Prueba)",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    SettingSectionTitle("SpO2 (%) - Input")
                    SettingsRangeInputFields( // Composable reutilizable para campos Min/Max.
                        minLabel = "Mín. SpO2", minValue = currentDialogState.spo2InputMin,
                        onMinValueChange = { newValue ->
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(spo2InputMin = newValue))
                            }
                        },
                        maxLabel = "Máx. SpO2", maxValue = currentDialogState.spo2InputMax,
                        onMaxValueChange = { newValue ->
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(spo2InputMax = newValue))
                            }
                        },
                    )

                    SettingSectionTitle("Frecuencia Cardíaca (lpm) - Input", Modifier.padding(top = 16.dp))
                    SettingsRangeInputFields(
                        minLabel = "Mín. FC", minValue = currentDialogState.hrInputMin,
                        onMinValueChange = { newValue ->
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(hrInputMin = newValue))
                            }
                        },
                        maxLabel = "Máx. FC", maxValue = currentDialogState.hrInputMax,
                        onMaxValueChange = { newValue ->
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(hrInputMax = newValue))
                            }
                        },
                    )

                    SettingSectionTitle("Presión Arterial (mmHg) - Input", Modifier.padding(top = 16.dp))
                    SettingsRangeInputFields(
                        minLabel = "Mín. Sistólica", minValue = currentDialogState.bpSystolicMin,
                        onMinValueChange = { newValue ->
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(bpSystolicMin = newValue))
                            }
                        },
                        maxLabel = "Máx. Sistólica", maxValue = currentDialogState.bpSystolicMax,
                        onMaxValueChange = { newValue ->
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(bpSystolicMax = newValue))
                            }
                        },
                    )
                    Spacer(Modifier.height(4.dp)) // Pequeño espacio entre campos de PA.
                    SettingsRangeInputFields(
                        minLabel = "Mín. Diastólica", minValue = currentDialogState.bpDiastolicMin,
                        onMinValueChange = { newValue ->
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(bpDiastolicMin = newValue))
                            }
                        },
                        maxLabel = "Máx. Diastólica", maxValue = currentDialogState.bpDiastolicMax,
                        onMaxValueChange = { newValue ->
                            if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(bpDiastolicMax = newValue))
                            }
                        },
                    )

                    SettingSectionTitle("Frecuencia Respiratoria (rpm) - Input", Modifier.padding(top = 16.dp))
                    SettingsRangeInputFields(
                        minLabel = "Mín. FR", minValue = currentDialogState.rrMin,
                        onMinValueChange = { newValue ->
                            // FR usualmente tiene 2 dígitos.
                            if (newValue.length <= 2 && newValue.all { it.isDigit() }) { // FR usualmente 2 dígitos
                                onDialogStateChange(currentDialogState.copy(rrMin = newValue))
                            }
                        },
                        maxLabel = "Máx. FR", maxValue = currentDialogState.rrMax,
                        onMaxValueChange = { newValue ->
                            if (newValue.length <= 2 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(rrMax = newValue))
                            }
                        },
                    )

                    SettingSectionTitle("Escala de Borg (0-10) - Input", Modifier.padding(top = 16.dp))
                    SettingsRangeInputFields(
                        minLabel = "Mín. Borg", minValue = currentDialogState.borgMin,
                        onMinValueChange = { newValue ->
                            // Borg es máximo 10 (1 o 2 dígitos).
                            if (newValue.length <= 2 && newValue.all { it.isDigit() }) { // Borg max 10
                                onDialogStateChange(currentDialogState.copy(borgMin = newValue))
                            }
                        },
                        maxLabel = "Máx. Borg", maxValue = currentDialogState.borgMax,
                        onMaxValueChange = { newValue ->
                            if (newValue.length <= 2 && newValue.all { it.isDigit() }) {
                                onDialogStateChange(currentDialogState.copy(borgMax = newValue))
                            }
                        },
                    )

                    // Muestra el mensaje de error de validación si existe.
                    currentDialogState.validationError?.let { errorMsg ->
                        Text(
                            errorMsg,
                            color = MaterialTheme.colorScheme.error, // Color de error del tema.
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }

                    Divider(modifier = Modifier.padding(vertical = 20.dp))

                    // --- Sección: Acerca de ---
                    Text(
                        "Acerca de esta Aplicación",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(appDescription, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                    Text("Versión: $appVersion", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp))
                } // Fin del Column scrollable

                // Botones de acción del diálogo (Cancelar, Guardar).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp), // Espacio sobre los botones.
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        keyboardController?.hide() // Ocultar teclado al cancelar
                        onDismiss()
                    }) { Text("Cancelar") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        keyboardController?.hide() // Ocultar teclado al guardar
                        onSave()
                    } ) { Text("Guardar") }
                }
            }
        }
    }
}

/**
 * Composable auxiliar para mostrar un título de sección dentro del SettingsDialog.
 * @param title El texto del título.
 * @param modifier Modificador para este Composable.
 */
@Composable
fun SettingSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium, // Estilo de título mediano.
        modifier = modifier.padding(bottom = 8.dp) // Espacio debajo del título.
    )
}

/**
 * Composable auxiliar para mostrar un texto de ayuda/aclaración dentro del SettingsDialog.
 * @param text El texto de ayuda.
 * @param modifier Modificador para este Composable.
 */
@Composable
fun SettingHelpText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall, // Estilo de cuerpo de texto pequeño.
        color = MaterialTheme.colorScheme.onSurfaceVariant, // Color con menor énfasis.
        modifier = modifier.padding(bottom = 12.dp, start = 4.dp) // Padding.
    )
}

/**
 * Composable reutilizable para un campo de entrada (OutlinedTextField) en el SettingsDialog.
 * @param label Etiqueta del campo.
 * @param value Valor actual del campo.
 * @param onValueChange Callback cuando el valor cambia.
 * @param modifier Modificador para este Composable.
 * @param keyboardType Tipo de teclado (por defecto numérico).
 * @param singleLine Si el campo es de una sola línea (por defecto true).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Number, // Teclado numérico por defecto.
    singleLine: Boolean = true
) {
    // Se obtiene el controlador del teclado localmente.
    // Aunque `keyboardController` también se define en `SettingsDialog`,
    // `LocalSoftwareKeyboardController.current` es la forma idiomática de acceder a él
    // directamente donde se necesita, asegurando que se obtiene el correcto para el contexto actual.
    val localKeyboardController = LocalSoftwareKeyboardController.current // OBTENEMOS UNO LOCALMENTE
    Log.d("SettingsInputField", "[$label] Composable RECOMPPOSED. LocalKeyboardController: $localKeyboardController")

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 15.sp) }, // Tamaño de fuente de la etiqueta.
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Done // Acción "Done" en el teclado.
        ),
        keyboardActions = KeyboardActions(
            onDone = { // Cuando se pulsa "Done" en el teclado.
                Log.d("SettingsInputField", "[$label] onDone ACTION. Attempting to hide with LocalController: $localKeyboardController")
                localKeyboardController?.hide() // Oculta el teclado.
                Log.d("SettingsInputField", "[$label] localKeyboardController.hide() CALLED.")
            }
        ),
        singleLine = singleLine,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp), // Espacio debajo del campo.
        shape = RoundedCornerShape(8.dp), // Bordes redondeados.
        colors = OutlinedTextFieldDefaults.colors( // Colores del campo.
            // Colores estándar para estos campos
            focusedBorderColor = DarkerBlueHighlight,
            unfocusedBorderColor = LightBluePrimary.copy(alpha = 0.7f),
            cursorColor = DarkerBlueHighlight,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        )
    )
}

/**
 * Composable reutilizable para un par de campos de entrada (Min/Max) en el SettingsDialog.
 * Utiliza `SettingsInputField` internamente.
 * @param minLabel Etiqueta para el campo mínimo.
 * @param minValue Valor actual del campo mínimo.
 * @param onMinValueChange Callback cuando el valor mínimo cambia.
 * @param maxLabel Etiqueta para el campo máximo.
 * @param maxValue Valor actual del campo máximo.
 * @param onMaxValueChange Callback cuando el valor máximo cambia.
 * @param modifier Modificador para este Composable.
 */
@Composable
fun SettingsRangeInputFields(
    minLabel: String,
    minValue: String,
    onMinValueChange: (String) -> Unit,
    maxLabel: String,
    maxValue: String,
    onMaxValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp) // Espacio entre los dos campos
    ) {
        SettingsInputField(
            label = minLabel,
            value = minValue,
            onValueChange = onMinValueChange,
            modifier = Modifier.weight(1f), // Cada campo ocupa la mitad del espacio.
        )
        SettingsInputField(
            label = maxLabel,
            value = maxValue,
            onValueChange = onMaxValueChange,
            modifier = Modifier.weight(1f),
        )
    }
}
