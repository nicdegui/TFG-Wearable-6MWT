package com.example.app6mwt.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.bluetooth.BluetoothAdapter
import android.provider.Settings
import androidx.hilt.navigation.compose.hiltViewModel
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.app6mwt.bluetooth.BleConnectionStatus
import com.example.app6mwt.ui.theme.*
import kotlinx.coroutines.flow.collectLatest
import com.example.app6mwt.bluetooth.UiScannedDevice as ServiceUiScannedDevice

/**
 * Define una opción de sexo para el formulario, con un valor interno y un nombre para mostrar.
 * @param value Valor interno (ej. "M", "F").
 * @param displayName Texto a mostrar en la UI (ej. "M", "F").
 */
data class SexOption(val value: String, val displayName: String)

/**
 * Pantalla principal para la preparación de la prueba de 6 minutos marcha (6MWT).
 * Gestiona la entrada de datos del paciente, la conexión Bluetooth con el pulsioxímetro,
 * la introducción de valores basales y la navegación a la ejecución de la prueba.
 *
 * @param patientIdFromNav ID del paciente recibido de la navegación.
 * @param patientNameFromNav Nombre del paciente recibido de la navegación.
 * @param patientHasHistoryFromNav Booleano que indica si el paciente tiene historial, recibido de la navegación.
 * @param onNavigateBack Lambda para ejecutar cuando se debe navegar hacia atrás.
 * @param onNavigateToTestExecution Lambda para navegar a la pantalla de ejecución de la prueba, pasando los datos de preparación.
 * @param viewModel Instancia de [PreparationViewModel] inyectada por Hilt, que maneja la lógica de esta pantalla.
 */
@OptIn(ExperimentalMaterial3Api::class) // Habilita el uso de APIs experimentales de Material 3.
@Composable
fun PreparationScreen(
    patientIdFromNav: String,
    patientNameFromNav: String,
    patientHasHistoryFromNav: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToTestExecution: (preparationData: TestPreparationData) -> Unit,
    viewModel: PreparationViewModel = hiltViewModel() // Inyección del ViewModel.
) {
    // ---- ESTADOS OBSERVADOS DEL VIEWMODEL ----
    // Controla la visibilidad del diálogo de confirmación para salir.
    val showDialog by viewModel.showNavigateBackDialog.collectAsStateWithLifecycle()
    // Nombre completo del paciente, actualizado por el ViewModel.
    val patientFullName by viewModel.patientFullName.collectAsStateWithLifecycle()
    // ID del paciente para mostrar, actualizado por el ViewModel.
    val patientIdForDisplay by viewModel.patientId.collectAsStateWithLifecycle()

    // Controlador del teclado virtual para ocultarlo programáticamente.
    val keyboardController = LocalSoftwareKeyboardController.current

    // ---- ACTIVITY RESULT LAUNCHERS ----
    // Launcher para solicitar permisos de Android (Bluetooth y ubicación).
    // Procesa el resultado de la solicitud de permisos y lo envía al ViewModel.
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsResult ->
        Log.d("PreparationScreen", "Resultado de permisos de Android: $permissionsResult")
        viewModel.onPermissionsResult(permissionsResult)
    }

    // Launcher para solicitar la activación del Bluetooth.
    // Procesa el resultado (activado o no) y lo envía al ViewModel.
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("PreparationScreen", "Resultado de activación de Bluetooth: ${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onBluetoothEnabledResult(true)
        } else {
            viewModel.onBluetoothEnabledResult(false)
        }
    }

    // Launcher para solicitar la activación de los servicios de ubicación.
    // Notifica al ViewModel para que re-verifique el estado, ya que no hay un resultCode estándar.
    val enableLocationServicesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        Log.d("PreparationScreen", "Resultado de activación de servicios de ubicación (se re-verificará el estado).")
        viewModel.onLocationServicesEnabledResult(viewModel.bluetoothService.isLocationEnabled()) // Re-chequear el estado
    }

    // ---- EFECTOS LANZADOS (LAUNCHEDEFFECTS) ----
    // Se ejecuta cuando cambian los parámetros de navegación del paciente.
    // Inicializa el ViewModel con los datos del paciente.
    LaunchedEffect(patientIdFromNav, patientNameFromNav, patientHasHistoryFromNav) {
        Log.d("PreparationScreen", "LaunchedEffect para inicializar ViewModel con datos del paciente.")
        viewModel.initialize(patientIdFromNav, patientNameFromNav, patientHasHistoryFromNav)
    }

    // Observa eventos de navegación emitidos por el ViewModel.
    // Si el evento es para ir a la ejecución de la prueba, oculta el teclado y navega.
    LaunchedEffect(viewModel.navigateToEvent) {
        viewModel.navigateToEvent.collectLatest { event ->
            when (event) {
                is PreparationNavigationEvent.ToTestExecution -> {
                    keyboardController?.hide()
                    onNavigateToTestExecution(event.preparationData)
                }
            }
        }
    }

    // Observa el evento para navegar hacia atrás emitido por el ViewModel.
    LaunchedEffect(key1 = viewModel) {
        viewModel.navigateBackEvent.collectLatest {
            Log.d("ScreenPrep", "Evento navigateBackEvent recibido desde ViewModel. Ejecutando onNavigateBack().")
            onNavigateBack()
        }
    }

    // Observa el evento para solicitar permisos de Android.
    // Si hay permisos para solicitar, los lanza usando `permissionsLauncher`.
    LaunchedEffect(viewModel.requestPermissionsEvent) {
        viewModel.requestPermissionsEvent.collectLatest { permissionsToRequestArray ->
            Log.d("PreparationScreen", "Evento requestPermissionsEvent recibido. Solicitando: ${permissionsToRequestArray.joinToString()}")
            if (permissionsToRequestArray.isNotEmpty()) {
                permissionsLauncher.launch(permissionsToRequestArray)
            }
        }
    }

    // Observa el evento para solicitar la activación de Bluetooth.
    // Lanza el intent correspondiente usando `enableBluetoothLauncher`.
    LaunchedEffect(viewModel.requestEnableBluetoothEvent) {
        viewModel.requestEnableBluetoothEvent.collectLatest {
            Log.d("PreparationScreen", "Evento requestEnableBluetoothEvent recibido. Lanzando intent para activar Bluetooth.")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        }
    }

    // Observa el evento para solicitar la activación de los servicios de ubicación.
    // Lanza el intent a la configuración de ubicación usando `enableLocationServicesLauncher`.
    LaunchedEffect(viewModel.requestLocationServicesEvent) {
        viewModel.requestLocationServicesEvent.collectLatest {
            Log.d("PreparationScreen", "Evento requestLocationServicesEvent recibido. Lanzando intent para configuración de ubicación.")
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            enableLocationServicesLauncher.launch(intent)
        }
    }

    // ---- MANEJO DEL BOTÓN DE RETROCESO DEL SISTEMA ----
    // `rememberUpdatedState` asegura que `currentViewModel` siempre tenga la instancia más reciente.
    val currentViewModel by rememberUpdatedState(viewModel)
    // Intercepta el evento del botón de retroceso del sistema.
    BackHandler(enabled = true) {
        Log.d("ScreenPrep", "Botón de atrás del sistema presionado. Llamando a currentViewModel.requestNavigateBack().")
        currentViewModel.requestNavigateBack() // Delega la lógica al ViewModel.
    }

    // ---- ESTRUCTURA DE LA PANTALLA (SCAFFOLD) ----
    Scaffold(
        topBar = {
            // Define la barra superior de la aplicación para esta pantalla.
            PreparationTopAppBar(
                // Usa el nombre del ViewModel si está disponible, sino el de navegación.
                patientName = patientFullName.takeIf { it.isNotBlank() } ?: patientNameFromNav,
                // Usa el ID del ViewModel si está disponible, sino el de navegación.
                patientId = patientIdForDisplay ?: patientIdFromNav,
                onNavigateBackClicked = {
                    Log.d("ScreenPrep", "Flecha de TopAppBar presionada. Llamando a viewModel.requestNavigateBack().")
                    keyboardController?.hide() // Oculta el teclado.
                    viewModel.requestNavigateBack() // Solicita navegar hacia atrás.
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background // Color de fondo del Scaffold.
    ) { innerPadding ->
        // Contenido principal de la pantalla.
        PreparationScreenContent(
            modifier = Modifier
                .padding(innerPadding) // Aplica el padding proporcionado por el Scaffold.
                .fillMaxSize(),
            viewModel = viewModel,
            // Lambda que se pasa al contenido para iniciar el proceso BLE o solicitar permisos.
            onRequestPermissionsOrEnableFeatures = {
                Log.d("PreparationScreen", "onRequestPermissions llamado desde la UI. Llamando a viewModel.startBleScan() que gestionará permisos si es necesario.")
                viewModel.startBleProcessOrRequestPermissions()
            }
        )
    }

    // Muestra el diálogo de confirmación si `showDialog` es verdadero.
    if (showDialog) {
        ConfirmationDialog(
            title = "Confirmar Salida",
            text = "¿Está seguro de que quiere volver? Se perderán los datos no guardados de esta preparación.",
            onConfirm = { viewModel.confirmNavigateBack() }, // Acción al confirmar.
            onDismiss = { viewModel.cancelNavigateBack() }, // Acción al descartar.
            confirmButtonText = "Salir",
            dismissButtonText = "Cancelar"
        )
    }
}

/**
 * Composable para la barra de aplicación superior (TopAppBar) de la pantalla de preparación.
 * Muestra el título de la pantalla, el nombre e ID del paciente, y un botón de navegación hacia atrás.
 *
 * @param patientName Nombre del paciente a mostrar.
 * @param patientId ID del paciente a mostrar.
 * @param onNavigateBackClicked Lambda a ejecutar cuando se presiona el botón de retroceso.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreparationTopAppBar(
    patientName: String,
    patientId: String,
    onNavigateBackClicked: () -> Unit
) {
    TopAppBar(
        title = {
            // Contenedor para centrar el título.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "PREPARACIÓN PRUEBA 6MWT - ${patientName.uppercase()}", // Título con nombre del paciente.
                    fontSize = 25.sp, fontWeight = FontWeight.Bold, color = TextOnSecondary,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, // Evita que el texto largo desborde.
                    modifier = Modifier.padding(horizontal = 58.dp) // Padding para evitar solapamiento con íconos.
                )
            }
        },
        navigationIcon = {
            // Botón de retroceso.
            IconButton(onClick = onNavigateBackClicked) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Regresar", tint = TextOnSecondary)
            }
        },
        actions = {
            // Muestra el ID del paciente en la sección de acciones.
            Text(
                text = "ID: $patientId", fontSize = 25.sp, fontWeight = FontWeight.Bold,
                color = TextOnSecondary, textAlign = TextAlign.End,
                modifier = Modifier
                    .padding(end = 16.dp)
                    .align(Alignment.CenterVertically) // Alinea verticalmente el texto.
            )
        },
        // Colores personalizados para la TopAppBar.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary, titleContentColor = TextOnSecondary,
            navigationIconContentColor = TextOnSecondary, actionIconContentColor = TextOnSecondary
        )
    )
}

/**
 * Composable que define el diseño del contenido principal de la pantalla de preparación.
 * Organiza las diferentes secciones (datos del paciente, valores basales, conexión Bluetooth, etc.) en columnas y filas.
 *
 * @param modifier Modificador para aplicar al contenedor principal.
 * @param viewModel Instancia del [PreparationViewModel].
 * @param onRequestPermissionsOrEnableFeatures Lambda para solicitar permisos o activar funcionalidades necesarias para Bluetooth.
 */
@Composable
fun PreparationScreenContent(
    modifier: Modifier = Modifier,
    viewModel: PreparationViewModel,
    onRequestPermissionsOrEnableFeatures: () -> Unit
) {
    // Fila principal que divide la pantalla en columnas.
    Row(modifier = modifier
        .padding(16.dp) // Padding general.
        .fillMaxSize()
    ) {
        // Columna izquierda: Datos del paciente y ubicación del dispositivo.
        Column(modifier = Modifier
            .weight(0.38f) // Proporción del ancho.
            .padding(end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionContainer { PatientDataSection(viewModel) } // Contenedor para la sección de datos del paciente.
            FormDivider() // Divisor visual.
            SectionContainer { CombinedDevicePlacementSection(viewModel) } // Contenedor para la sección de ubicación del dispositivo.
            Spacer(modifier = Modifier.weight(1f)) // Espaciador para empujar contenido hacia arriba.
        }
        // Divisor vertical.
        VerticalDivider(modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        // Columna central: Valores basales.
        Column(modifier = Modifier
            .weight(0.28f)
            .padding(horizontal = 8.dp)) {
            SectionContainer { BasalValuesSection(viewModel) } // Contenedor para la sección de valores basales.
            Spacer(modifier = Modifier.weight(1f)) // Espaciador.
        }
        // Divisor vertical.
        VerticalDivider(modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        // Columna derecha: Acciones y conexión Bluetooth.
        Column(modifier = Modifier
            .weight(0.34f) // Proporción del ancho.
            .padding(start = 5.dp), verticalArrangement = Arrangement.SpaceBetween) { // SpaceBetween para empujar el Spacer al medio.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionContainer { ActionsTopSection(viewModel) } // Contenedor para las acciones principales.
                SectionContainer { BluetoothConnectionSection(viewModel = viewModel, onRequestPermissionsOrEnableFeatures = onRequestPermissionsOrEnableFeatures) } // Contenedor para la conexión Bluetooth.
            }
            Spacer(modifier = Modifier.weight(1f)) // Espaciador.
        }
    }
}

/**
 * Un contenedor genérico para las secciones de la UI.
 * Aplica un fondo, bordes redondeados y padding al contenido.
 *
 * @param modifier Modificador para el `Column` contenedor.
 * @param content El contenido Composable que se mostrará dentro de la sección.
 */
@Composable
fun SectionContainer(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier
        .fillMaxWidth() // Ocupa todo el ancho disponible.
        .background(ElementBackgroundColor, RoundedCornerShape(12.dp)) // Fondo y bordes redondeados.
        .padding(horizontal = 6.dp, vertical = 4.dp), // Padding interno.
        content = content // Contenido de la sección.
    )

}

/**
 * Sección para la entrada de datos demográficos y clínicos del paciente.
 * Incluye campos para sexo, edad, altura, peso, y checkboxes para medicación.
 * Calcula y muestra la distancia teórica del 6MWT.
 *
 * @param viewModel Instancia del [PreparationViewModel] para acceder y modificar los datos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientDataSection(viewModel: PreparationViewModel) {
    // Estados observados del ViewModel para los datos del paciente.
    val patientSex by viewModel.patientSex.collectAsStateWithLifecycle()
    val patientAge by viewModel.patientAge.collectAsStateWithLifecycle()
    val patientHeightCm by viewModel.patientHeightCm.collectAsStateWithLifecycle()
    val patientWeightKg by viewModel.patientWeightKg.collectAsStateWithLifecycle()
    val theoreticalDistance by viewModel.theoreticalDistance
    val calculatedStrideLengthMeters by viewModel.calculatedStrideLengthMeters
    val usesInhalers by viewModel.usesInhalers.collectAsStateWithLifecycle()
    val usesOxygen by viewModel.usesOxygen.collectAsStateWithLifecycle()

    // Opciones para el desplegable de sexo.
    val sexOptions = remember { listOf(SexOption("M", "M"), SexOption("F", "F")) }
    // Estado para controlar si el menú desplegable de sexo está expandido.
    var sexExpanded by remember { mutableStateOf(false) }
    // Texto a mostrar en el campo de sexo (el valor actual del ViewModel).
    val selectedSexDisplay = patientSex

    Column { // Contenedor principal de la sección.
        Text("Datos del paciente", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier
            .padding(bottom = 6.dp)
            .align(Alignment.CenterHorizontally)) // Título de la sección.
        // Fila para Sexo y Edad.
        DataRow("Sexo y edad:") {
            // Contenedor para el menú desplegable de sexo.
            Box(
                modifier = Modifier
                    .weight(1f) // Ocupa el espacio disponible.
                    .padding(end = 4.dp)
            ) {
                // Componente ExposedDropdownMenuBox para el menú de sexo.
                ExposedDropdownMenuBox(
                    expanded = sexExpanded,
                    onExpandedChange = { sexExpanded = !sexExpanded }, // Cambia el estado de expansión.
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Campo de texto que actúa como ancla para el menú.
                    OutlinedTextField(
                        value = selectedSexDisplay, // Muestra el sexo seleccionado.
                        onValueChange = { /* No editable directamente por teclado */ },
                        readOnly = true, // Solo se puede cambiar mediante el menú.
                        label = { Text("Sexo", fontSize = 12.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sexExpanded) }, // Icono del desplegable.
                        colors = OutlinedTextFieldDefaults.colors( // Estilos de color.
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                            focusedBorderColor = DarkerBlueHighlight,
                            unfocusedBorderColor = LightBluePrimary.copy(alpha = 0.6f),
                            disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            cursorColor = DarkerBlueHighlight
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .menuAnchor() // Define este campo como el ancla del menú.
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 32.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, textAlign = TextAlign.Center)
                    )
                    // Contenido del menú desplegable.
                    ExposedDropdownMenu(
                        expanded = sexExpanded,
                        onDismissRequest = { sexExpanded = false } // Cierra el menú si se toca fuera.
                    ) {
                        // Opción para limpiar la selección si ya hay una.
                        if (patientSex.isNotBlank()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "-- Limpiar selección --",
                                        fontSize = 15.sp,
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                    )
                                },
                                onClick = {
                                    viewModel.onPatientSexChange("") // Llama al ViewModel con valor vacío
                                    sexExpanded = false // Cierra el menú.
                                }
                            )
                            HorizontalDivider() // Separador
                        }
                        // Itera sobre las opciones de sexo.
                        sexOptions.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = {
                                    // Centrar el texto "M" o "F" en el DropdownMenuItem
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text(selectionOption.displayName, fontSize = 17.sp) // Muestra el nombre de la opción.
                                    }
                                },
                                onClick = {
                                    viewModel.onPatientSexChange(selectionOption.value) // Envía el valor seleccionado al ViewModel.
                                    sexExpanded = false // Cierra el menú.
                                }
                            )
                        }
                    }
                }
            }
            // Campo de texto personalizado para la edad.
            CustomTextField(patientAge, viewModel::onPatientAgeChange, "Edad", Modifier
                .weight(1f)
                .padding(start = 4.dp), KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        FormDivider() // Divisor visual.
        // Fila para Altura y Peso.
        DataRow("Altura y peso:") {
            CustomTextField(patientHeightCm, viewModel::onPatientHeightChange, "Altura (cm)", Modifier
                .weight(1f)
                .padding(end = 4.dp), KeyboardOptions(keyboardType = KeyboardType.Number))
            CustomTextField(patientWeightKg, viewModel::onPatientWeightChange, "Peso (kg)", Modifier
                .weight(1f)
                .padding(start = 4.dp), KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        FormDivider()
        // Muestra la distancia teórica calculada.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("Distancia teórica:", fontWeight = FontWeight.Medium, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(if (theoreticalDistance > 0.0) "%.2f metros".format(theoreticalDistance) else "0 metros", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = TextOnPrimary)
        }
        // NUEVA LÍNEA PARA LONGITUD DE PASO
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp) // Padding similar al de arriba
        ) {
            Text("Longitud de paso:", fontWeight = FontWeight.Medium, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(
                if (calculatedStrideLengthMeters > 0.0) "%.2f metros".format(calculatedStrideLengthMeters) else "0 metros", // Formato con 2 decimales
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = TextOnPrimary
            )
        }
        FormDivider()
        // Sección de medicación adicional.
        Text("Medicación adicional:", fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        CheckboxRow("Usa inhaladores", usesInhalers, viewModel::onUsesInhalersChange) // Checkbox para inhaladores.
        CheckboxRow("Usa oxígeno domiciliario", usesOxygen, viewModel::onUsesOxygenChange) // Checkbox para oxígeno.
    }
}

/**
 * Composable reutilizable para crear una fila de datos con una etiqueta y contenido.
 *
 * @param label Texto de la etiqueta.
 * @param content El contenido Composable que se mostrará a la derecha de la etiqueta.
 */
@Composable
fun DataRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier
            .weight(0.35f) // Proporción del ancho para la etiqueta.
            .padding(end = 8.dp))
        Row(modifier = Modifier.weight(0.65f), content = content, verticalAlignment = Alignment.CenterVertically) // Contenido.
    }
}

/**
 * Sección para la gestión de la conexión Bluetooth con el pulsioxímetro.
 * Muestra el estado de la conexión, permite escanear y conectar dispositivos,
 * y muestra los datos en tiempo real (SpO2, FC) una vez conectado.
 *
 * @param viewModel Instancia del [PreparationViewModel].
 * @param onRequestPermissionsOrEnableFeatures Lambda para iniciar el proceso de permisos o activación de Bluetooth/ubicación.
 */
@SuppressLint("MissingPermission") // Se gestionan los permisos a través del ViewModel y los Launchers.
@Composable
fun BluetoothConnectionSection(
    viewModel: PreparationViewModel,
    onRequestPermissionsOrEnableFeatures: () -> Unit
) {
    // Estados del ViewModel para AMBOS dispositivos
    val isBleReady by viewModel.isBleReady.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scannedDevicesResult by viewModel.scannedDevices.collectAsStateWithLifecycle() // Lista única de dispositivos
    val currentBluetoothMessageForUi by viewModel.uiBluetoothMessage.collectAsStateWithLifecycle() // Mensaje general

    val oximeterDisplayStatus by viewModel.oximeterDisplayStatus.collectAsStateWithLifecycle() // Ej: "Conectado", "Error"
    val wearableDisplayStatus by viewModel.wearableDisplayStatus.collectAsStateWithLifecycle() // Ej: "Desconectado"

    // Pulsioxímetro
    val oximeterConnectionInternalStatus by viewModel.oximeterConnectionStatus.collectAsStateWithLifecycle()
    val connectedOximeterName by viewModel.connectedOximeterDeviceName.collectAsStateWithLifecycle()
    val latestBleSpo2 by viewModel.latestBleSpo2.collectAsStateWithLifecycle()
    val latestBleHeartRate by viewModel.latestBleHeartRate.collectAsStateWithLifecycle()
    val latestBleNoFinger by viewModel.latestBleNoFinger.collectAsStateWithLifecycle()
    val latestBleSignalStrength by viewModel.latestBleSignalStrength.collectAsStateWithLifecycle() // Asumiendo que es solo para pulsioxímetro

    // Acelerómetro (Wearable)
    val wearableConnectionInternalStatus by viewModel.wearableConnectionStatus.collectAsStateWithLifecycle()
    val connectedWearableName by viewModel.connectedWearableName.collectAsStateWithLifecycle()
    val latestWearableTotalSteps by viewModel.latestWearableSteps.collectAsStateWithLifecycle()

    // --- Lógica de Conexión Específica ---
    val isOximeterConnecting = oximeterConnectionInternalStatus == BleConnectionStatus.CONNECTING || oximeterConnectionInternalStatus == BleConnectionStatus.RECONNECTING
    val isWearableConnecting = wearableConnectionInternalStatus == BleConnectionStatus.CONNECTING || wearableConnectionInternalStatus == BleConnectionStatus.RECONNECTING
    val isAnyDeviceActuallyConnecting = isOximeterConnecting || isWearableConnecting // Usar esto para el progreso general

    // Lógica para determinar si se muestra la sección de lecturas
    val oximeterIsSubscribed = oximeterConnectionInternalStatus == BleConnectionStatus.SUBSCRIBED
    val wearableIsSubscribed = wearableConnectionInternalStatus == BleConnectionStatus.SUBSCRIBED
    val areBothDevicesSubscribed = oximeterIsSubscribed && wearableIsSubscribed

    // Lógica para la visibilidad de la lista de dispositivos
    // La lista se muestra si los prerrequisitos están listos Y (estamos escaneando O hay dispositivos) Y NO AMBOS están suscritos
    val showDeviceList = isBleReady &&
            (scannedDevicesResult.isNotEmpty() || isScanning) &&
            !areBothDevicesSubscribed

    // Mensaje global de UI (podría venir del ViewModel para errores de permisos, etc.)
    val globalUiMessage by viewModel.uiBluetoothMessage.collectAsStateWithLifecycle()

    Column {
        Text(
            "Conexión dispositivos",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .align(Alignment.CenterHorizontally)
        )

        // ---- Botón Principal (Escanear/Detener Escaneo/Activar BT/Permisos) ----
        val mainButtonEnabled: Boolean
        val mainButtonText: String
        val mainButtonIconImageVector: androidx.compose.ui.graphics.vector.ImageVector
        val mainButtonAction: () -> Unit
        var showProgressInMainButton = isAnyDeviceActuallyConnecting

        when {
            !isBleReady -> {
                mainButtonEnabled = true
                mainButtonIconImageVector = Icons.Filled.BluetoothDisabled
                mainButtonAction = onRequestPermissionsOrEnableFeatures
                mainButtonText = when {
                    globalUiMessage?.contains("Activar Bluetooth", ignoreCase = true) == true  -> "Activar Bluetooth"
                    globalUiMessage?.contains("permisos de Bluetooth", ignoreCase = true) == true  -> "Conceder Permisos BT"
                    globalUiMessage?.contains("ubicación", ignoreCase = true) == true -> "Activar Ubicación"
                    else -> "Revisar Config. BT"
                }
            }
            isAnyDeviceActuallyConnecting -> { // Prioridad si algo se está conectando
                mainButtonEnabled = false // Deshabilitado mientras conecta
                mainButtonText = "Conectando..." // Este mensaje es genérico, los estados individuales mostrarán detalles
                mainButtonIconImageVector = Icons.AutoMirrored.Filled.BluetoothSearching // O un icono de progreso
                mainButtonAction = { /* No action */ }
            }
            isScanning -> {
                mainButtonEnabled = true
                mainButtonText = "Detener Escaneo"
                mainButtonIconImageVector = Icons.Filled.SearchOff
                mainButtonAction = { viewModel.stopBleScan() }
            }
            areBothDevicesSubscribed -> {
                mainButtonEnabled = true
                mainButtonText = "Desconectar Todo"
                mainButtonIconImageVector = Icons.Filled.BluetoothConnected // O un icono de desconectar
                mainButtonAction = {
                    viewModel.disconnectOximeter()
                    viewModel.disconnectWearable()
                }
            }
            else -> { // No se está escaneando, no se está conectando, no ambos conectados => Listo para escanear
                mainButtonEnabled = true
                mainButtonText = "Escanear Dispositivos"
                mainButtonIconImageVector = Icons.Filled.Bluetooth
                mainButtonAction = onRequestPermissionsOrEnableFeatures
            }
        }

        Button(
            onClick = mainButtonAction,
            enabled = mainButtonEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .defaultMinSize(minHeight = 48.dp)
        ) {
            if (showProgressInMainButton) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(mainButtonIconImageVector, contentDescription = null, Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(mainButtonText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Indicador de progreso lineal solo si se está escaneando Y los prerrequisitos están listos
        if (isScanning && isBleReady && !isAnyDeviceActuallyConnecting) { // No mostrar si ya se está conectando a algo
            LinearProgressIndicator(Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp))
        }

        // ---- Estado General de Dos Líneas ----
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text("Estado general:", style = MaterialTheme.typography.labelMedium, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))

            // Usar los displayStatus que vienen del ViewModel, asumiendo que el ViewModel los actualiza correctamente
            StatusLine("Pulsioxímetro:", oximeterDisplayStatus, getStatusColor(oximeterDisplayStatus, isOximeterConnecting))
            StatusLine("Acelerómetro:", wearableDisplayStatus, getStatusColor(wearableDisplayStatus, isWearableConnecting))

            // Mensaje de error global si existe y no es sobre el estado de un dispositivo específico
            if (globalUiMessage != null &&
                !globalUiMessage!!.lowercase().contains("pulsiox") && // Evitar duplicar mensajes que ya están en StatusLine
                !globalUiMessage!!.lowercase().contains("acelerom") &&
                !globalUiMessage!!.lowercase().contains(BleConnectionStatus.CONNECTING.name.lowercase()) && // no si el vm solo dice conectando
                !globalUiMessage!!.lowercase().contains(BleConnectionStatus.SUBSCRIBED.name.lowercase()) &&
                !globalUiMessage!!.lowercase().contains(BleConnectionStatus.CONNECTED.name.lowercase()) &&
                !globalUiMessage!!.lowercase().contains("desconectado") &&
                !globalUiMessage!!.lowercase().contains("error") &&
                !globalUiMessage!!.lowercase().contains("pérdida"))
            {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    globalUiMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface, // Asumir que estos mensajes son informativos de problemas
                    fontSize = 15.sp
                )
            }
        }
        FormDivider(modifier = Modifier.padding(vertical = 4.dp))


        // ---- Sección de Lectura de Datos (Dinámica) ----
        val showDeviceReadingsSection = oximeterIsSubscribed || wearableIsSubscribed
        AnimatedVisibility(visible = showDeviceReadingsSection) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    "Lectura de dispositivos:",
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                if (oximeterIsSubscribed) {
                    val oximeterNameDisplay = connectedOximeterName ?: "Pulsioxímetro"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically, // Alinea verticalmente el texto y el botón
                        horizontalArrangement = Arrangement.SpaceBetween // Empuja el botón al final
                    ) {
                        Text(
                            text = "$oximeterNameDisplay:",
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f) // Permite que el texto tome el espacio necesario
                        )
                        Button(
                            onClick = { viewModel.disconnectOximeter() },
                            modifier = Modifier
                                .height(36.dp), // Quita el padding(top = 4.dp) y align(Alignment.End) de aquí
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("OFF", fontSize = 12.sp, color = TextOnSecondary) }
                    }

                    val noFingerDetected = latestBleNoFinger == true
                    val signalLowOrNull = (latestBleSignalStrength == null || latestBleSignalStrength == 0)

                    if (noFingerDetected) {
                        Text("Sensor: NO DEDO", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
                    } else if (signalLowOrNull) {
                        Text("Sensor: SEÑAL BAJA/NULA", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
                    }
                    RealTimeDataRow("  SpO₂:", latestBleSpo2?.toString() ?: "---", "%")
                    RealTimeDataRow("  FC:", latestBleHeartRate?.toString() ?: "---", "lpm")

                    if (wearableIsSubscribed) { // Espacio si ambos están conectados
                        Spacer(modifier = Modifier.height(10.dp))
                        FormDivider()
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }

                if (wearableIsSubscribed) {
                    val wearableNameDisplay = connectedWearableName ?: "Acelerómetro"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically, // Alinea verticalmente el texto y el botón
                        horizontalArrangement = Arrangement.SpaceBetween // Empuja el botón al final
                    ) {
                        Text(
                            text = "$wearableNameDisplay:",
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f) // Permite que el texto tome el espacio necesario
                        )
                        Button(
                            onClick = { viewModel.disconnectWearable() },
                            modifier = Modifier
                                .height(36.dp), // Quita el padding(top = 4.dp) y align(Alignment.End) de aquí
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("OFF", fontSize = 12.sp, color = TextOnSecondary) }
                    }
                    val stepsDisplay = latestWearableTotalSteps?.let { if (it >= 0) "$it" else "---" } ?: "---"
                    RealTimeDataRow("  Pasos:", stepsDisplay, "")
                }
            }
        }

        // ---- Lista de Dispositivos Escaneados ----
        // Se muestra si los prerrequisitos están OK Y (escaneando O hay dispositivos) Y NO AMBOS YA CONECTADOS
        AnimatedVisibility(visible = showDeviceList) {
            Column(Modifier.padding(top = 8.dp)) {
                Text(
                    text = when {
                        isScanning && !isAnyDeviceActuallyConnecting-> "Buscando dispositivos..."
                        isAnyDeviceActuallyConnecting -> "Conectando a un dispositivo..."
                        !oximeterIsSubscribed && !wearableIsSubscribed -> "Seleccione un dispositivo:"
                        !oximeterIsSubscribed -> "Conectar Pulsioxímetro..."
                        !wearableIsSubscribed -> "Conectar Acelerómetro..."
                        else -> "Dispositivos encontrados:" // No debería llegar aquí si showDeviceList es false
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                // Contenedor con altura MÁXIMA y scroll INTERNO
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 230.dp) // Ajustar altura según necesidad
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            RoundedCornerShape(8.dp)
                        )
                        .verticalScroll(rememberScrollState()) // Scroll solo para la lista
                ) {
                    Column {
                        if (scannedDevicesResult.isEmpty() && !isScanning && !isAnyDeviceActuallyConnecting) {
                            Text(
                                "No se encontraron dispositivos cercanos.",
                                modifier = Modifier
                                    .padding(16.dp)
                                    .align(Alignment.CenterHorizontally),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                        scannedDevicesResult.forEach { device ->
                            // Filtrar para no mostrar dispositivos ya conectados y suscritos (opcional, pero puede ser útil)
                            val isThisOximeterConnectingOrSubscribed = (isOximeterConnecting && viewModel.connectingOximeterAddress.value == device.address) || (oximeterIsSubscribed && viewModel.connectedOximeterDeviceAddress.value == device.address)
                            val isThisWearableConnectingOrSubscribed = (isWearableConnecting && viewModel.connectingWearableAddress.value == device.address) || (wearableIsSubscribed && viewModel.connectedWearableAddress.value == device.address)


                            // No mostrar si este dispositivo específico ya está suscrito o activamente conectándose
                            if (!isThisOximeterConnectingOrSubscribed && !isThisWearableConnectingOrSubscribed) {
                                DeviceRowItem(
                                    uiDevice = device,
                                    // Deshabilitar clic si CUALQUIER dispositivo ya se está conectando (evita conexiones paralelas accidentales)
                                    isEnabled = !isAnyDeviceActuallyConnecting,
                                    onDeviceClick = {
                                        // Doble check aquí, aunque el isEnabled debería cubrirlo
                                        if (!isAnyDeviceActuallyConnecting) {
                                            viewModel.connectToScannedDevice(device)
                                        }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusLine(label: String, status: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            modifier = Modifier.width(130.dp) // Ancho fijo para alineación
        )
        Text(
            text = status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }, // Capitalizar
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Helper para obtener el color basado en el string de estado simplificado
@Composable
fun getStatusColor(status: String, isConnecting: Boolean): androidx.compose.ui.graphics.Color {
    return when {
        isConnecting -> MaterialTheme.colorScheme.primary // Siempre primario si está específicamente conectándose
        status.lowercase() == "conectado" -> SuccessGreenColor
        status.lowercase().contains("error") || status.lowercase().contains("pérdida") -> MaterialTheme.colorScheme.error
        // otros estados como "desconectado", "inactivo"
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

// Helper para convertir BleConnectionStatus a un mensaje legible y color
fun BleConnectionStatus.toUserFriendlyMessage(deviceName: String?, deviceTypePlaceholder: String = "Dispositivo"): String {
    val name = deviceName ?: deviceTypePlaceholder
    return when (this) {
        BleConnectionStatus.IDLE -> "Inactivo. Listo para escanear."
        BleConnectionStatus.SCANNING -> "Escaneando $deviceTypePlaceholder..."
        BleConnectionStatus.CONNECTING -> "Conectando a $name..."
        BleConnectionStatus.CONNECTED -> "Conectado a $name. Esperando datos..."
        BleConnectionStatus.SUBSCRIBED -> "Recibiendo datos de $name."
        BleConnectionStatus.DISCONNECTED_BY_USER -> "Desconectado por el usuario ($name)."
        BleConnectionStatus.DISCONNECTED_ERROR -> "Conexión perdida con $name."
        BleConnectionStatus.RECONNECTING -> "Reconectando a $name..."
        BleConnectionStatus.ERROR_PERMISSIONS -> "Error: Permisos de Bluetooth denegados."
        BleConnectionStatus.ERROR_BLUETOOTH_DISABLED -> "Error: Bluetooth desactivado."
        BleConnectionStatus.ERROR_DEVICE_NOT_FOUND -> "Error: No se encontró $name."
        BleConnectionStatus.ERROR_SERVICE_NOT_FOUND -> "Error: Servicio no encontrado en $name."
        BleConnectionStatus.ERROR_CHARACTERISTIC_NOT_FOUND -> "Error: Característica no encontrada en $name."
        BleConnectionStatus.ERROR_SUBSCRIBE_FAILED -> "Error: Fallo al suscribir a $name."
        BleConnectionStatus.ERROR_GENERIC -> "Error de Bluetooth con $name."
    }
}

@Composable
fun BleConnectionStatus.toColor(): androidx.compose.ui.graphics.Color {
    return when (this) {
        BleConnectionStatus.SUBSCRIBED, BleConnectionStatus.CONNECTED -> SuccessGreenColor
        BleConnectionStatus.ERROR_PERMISSIONS,
        BleConnectionStatus.ERROR_BLUETOOTH_DISABLED,
        BleConnectionStatus.ERROR_DEVICE_NOT_FOUND,
        BleConnectionStatus.ERROR_SERVICE_NOT_FOUND,
        BleConnectionStatus.ERROR_CHARACTERISTIC_NOT_FOUND,
        BleConnectionStatus.ERROR_SUBSCRIBE_FAILED,
        BleConnectionStatus.ERROR_GENERIC,
        BleConnectionStatus.DISCONNECTED_ERROR -> MaterialTheme.colorScheme.error
        BleConnectionStatus.CONNECTING, BleConnectionStatus.SCANNING, BleConnectionStatus.RECONNECTING -> MaterialTheme.colorScheme.primary
        BleConnectionStatus.IDLE, BleConnectionStatus.DISCONNECTED_BY_USER -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Composable para mostrar un ítem individual en la lista de dispositivos Bluetooth escaneados.
 * Muestra el nombre del dispositivo, la dirección MAC y, si está disponible, el RSSI.
 * Permite hacer clic en el ítem para iniciar la conexión.
 *
 * @param uiDevice Objeto [ServiceUiScannedDevice] con la información del dispositivo.
 * @param onDeviceClick Lambda a ejecutar cuando se hace clic en el dispositivo.
 */
@SuppressLint("MissingPermission") // Los permisos se verifican antes del escaneo.
@Composable
private fun DeviceRowItem(
    uiDevice: ServiceUiScannedDevice,
    isEnabled: Boolean,
    onDeviceClick: () -> Unit
) {
    val textColor = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val iconColor = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDeviceClick) // Permite hacer clic.
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween // Distribuye el espacio.
    ) {
        Column(Modifier
            .weight(1f) // Ocupa el espacio principal.
            .padding(end = 6.dp)) {
            Text(
                uiDevice.deviceName ?: "Dispositivo desconocido",
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = textColor
            )
            Text(uiDevice.address,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 13.sp,
                color = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        }
        // Muestra el RSSI si está disponible.
        if (uiDevice.rssi != null) {
            Text(
                "RSSI: ${uiDevice.rssi}",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 13.sp,
                color = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Icon(Icons.Filled.Bluetooth, "Conectar", tint = iconColor)
    }
}

/**
 * Composable para mostrar una fila de datos en tiempo real (etiqueta, valor y unidad).
 *
 * @param label Texto de la etiqueta.
 * @param value Valor del dato.
 * @param unit Unidad del dato.
 */
@Composable
private fun RealTimeDataRow(label: String, value: String, unit: String) {
    Row(Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.6f), fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$value $unit", Modifier.weight(0.4f), fontWeight = FontWeight.Medium, fontSize = 18.sp, color = TextOnPrimary, textAlign = TextAlign.End)
    }
}

/**
 * Sección para la entrada de los valores basales del paciente.
 * Incluye campos para SpO2, FC, TA, FR y escalas de Borg.
 * Permite capturar SpO2 y FC directamente desde el dispositivo BLE conectado.
 *
 * @param viewModel Instancia del [PreparationViewModel].
 */
@Composable
fun BasalValuesSection(viewModel: PreparationViewModel) {
    // Estados observados del ViewModel para los valores basales y su validación.
    val spo2Input by viewModel.spo2Input.collectAsStateWithLifecycle()
    val isValidSpo2 by viewModel.isValidSpo2.collectAsStateWithLifecycle()
    val spo2Hint by viewModel.spo2RangeHint

    val heartRateInput by viewModel.heartRateInput.collectAsStateWithLifecycle()
    val isValidHeartRate by viewModel.isValidHeartRate.collectAsStateWithLifecycle()
    val hrHint by viewModel.hrRangeHint

    val bloodPressureInput by viewModel.bloodPressureInput.collectAsStateWithLifecycle()
    val isValidBloodPressure by viewModel.isValidBloodPressure.collectAsStateWithLifecycle()
    val bpHint by viewModel.bpRangeHint

    val respiratoryRateInput by viewModel.respiratoryRateInput.collectAsStateWithLifecycle()
    val isValidRespiratoryRate by viewModel.isValidRespiratoryRate.collectAsStateWithLifecycle()
    val rrHint by viewModel.rrRangeHint

    val dyspneaBorgInput by viewModel.dyspneaBorgInput.collectAsStateWithLifecycle()
    val isValidDyspneaBorg by viewModel.isValidDyspneaBorg.collectAsStateWithLifecycle()
    val legPainBorgInput by viewModel.legPainBorgInput.collectAsStateWithLifecycle()
    val isValidLegPainBorg by viewModel.isValidLegPainBorg.collectAsStateWithLifecycle()
    val borgHint by viewModel.borgRangeHint

    // Mensaje de estado para los valores basales.
    val basalValuesStatusMessage by viewModel.basalValuesStatusMessage.collectAsStateWithLifecycle()
    // Indica si todos los valores basales son válidos.
    val areBasalsValid by viewModel.areBasalsValid.collectAsStateWithLifecycle()
    // Estado de la conexión Bluetooth.
    val connectionStatus by viewModel.oximeterConnectionStatus.collectAsStateWithLifecycle()

    // Datos BLE para habilitar el botón de captura.
    val latestBleSpo2 by viewModel.latestBleSpo2.collectAsStateWithLifecycle()
    val latestBleHeartRate by viewModel.latestBleHeartRate.collectAsStateWithLifecycle()
    val latestBleNoFinger by viewModel.latestBleNoFinger.collectAsStateWithLifecycle()

    Column { // Contenedor principal de la sección.
        Text("Valores basales", style = MaterialTheme.typography.titleMedium,fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier
            .padding(bottom = 10.dp)
            .align(Alignment.CenterHorizontally)) // Título de la sección.
        // Botón para capturar SpO2 y FC desde el dispositivo BLE.
        Button(
            onClick = { viewModel.captureBasalFromBle() },
            // Habilitado si está suscrito, hay datos válidos de SpO2 y FC, y no hay error de "no dedo".
            enabled = connectionStatus == BleConnectionStatus.SUBSCRIBED &&
                    latestBleSpo2 != null && latestBleSpo2!! > 0 &&
                    latestBleHeartRate != null && latestBleHeartRate!! > 0 &&
                    latestBleNoFinger != true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 5.dp),
            colors = ButtonDefaults.buttonColors(containerColor =  MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Filled.BluetoothConnected, "Capturar desde BLE", modifier = Modifier.padding(end = 5.dp))
            Text("Capturar SpO₂ y FC")
        }
        FormDivider()
        // Filas para cada valor basal.
        BasalValueRow("SpO₂ (%):", spo2Input, isValidSpo2, viewModel::onSpo2InputChange, spo2Hint, keyboardType = KeyboardType.Number)
        FormDivider()
        BasalValueRow("FC (lpm):", heartRateInput, isValidHeartRate, viewModel::onHeartRateInputChange, hrHint, keyboardType = KeyboardType.Number)
        FormDivider()
        BasalValueRow("TA (mmHg):", bloodPressureInput, isValidBloodPressure, viewModel::onBloodPressureInputChange, bpHint, KeyboardType.Text) // Permite texto para el formato "XXX/YYY".
        FormDivider()
        BasalValueRow("FR (rpm):", respiratoryRateInput, isValidRespiratoryRate, viewModel::onRespiratoryRateInputChange, rrHint, keyboardType = KeyboardType.Number)
        FormDivider()
        BasalValueRow("Disnea Borg:", dyspneaBorgInput, isValidDyspneaBorg, viewModel::onDyspneaBorgInputChange, borgHint, keyboardType = KeyboardType.Number)
        FormDivider()
        BasalValueRow("Dolor MMII Borg:", legPainBorgInput, isValidLegPainBorg, viewModel::onLegPainBorgInputChange, borgHint, keyboardType = KeyboardType.Number)

        Spacer(Modifier.height(8.dp)) // Espaciador.

        // Muestra el mensaje de estado de los valores basales.
        if (basalValuesStatusMessage.isNotBlank()) {
            Text(
                basalValuesStatusMessage,
                // Color verde si son válidos y el mensaje indica éxito, rojo en caso contrario.
                color = if (areBasalsValid && (basalValuesStatusMessage.contains("válidos", ignoreCase = true) || basalValuesStatusMessage.contains("completos", ignoreCase = true) || basalValuesStatusMessage.contains("correctos", ignoreCase = true))) SuccessGreenColor else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium, fontSize = 17.sp, textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * Composable reutilizable para una fila de entrada de valor basal.
 * Consiste en una etiqueta y un campo de texto delineado (`OutlinedTextField`).
 * Muestra errores de validación y un placeholder.
 *
 * @param label Texto de la etiqueta.
 * @param value Valor actual del campo.
 * @param isValid Booleano que indica si el valor es válido.
 * @param onValueChange Lambda que se ejecuta cuando el valor cambia.
 * @param placeholder Texto de ayuda (hint) para el campo.
 * @param keyboardType Tipo de teclado a mostrar.
 */
@Composable
fun BasalValueRow(
    label: String,
    value: String,
    isValid: Boolean,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Number
) {
    // Indica si se debe mostrar un error (inválido y no vacío).
    val showError = !isValid && value.isNotBlank()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(vertical = 3.dp)
            .fillMaxWidth()
    ) {
        Text(label,
            Modifier
                .weight(0.45f) // Proporción del ancho para la etiqueta.
                .padding(end = 4.dp),
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal
        )
        // Campo de texto delineado.
        OutlinedTextField(
            value,
            onValueChange,
            Modifier.weight(0.55f), // Proporción del ancho para el campo.
            singleLine = true,
            isError = showError, // Muestra el estado de error.
            placeholder = placeholder?.let { hintText ->
                { // Contenedor para centrar el texto del placeholder.
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = hintText,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            },
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors( // Colores personalizados para el campo.
                focusedBorderColor = if (showError) MaterialTheme.colorScheme.error else DarkerBlueHighlight,
                unfocusedBorderColor = if (showError) MaterialTheme.colorScheme.error.copy(alpha = 0.7f) else LightBluePrimary.copy(alpha = 0.7f),
                cursorColor = DarkerBlueHighlight,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                errorBorderColor = MaterialTheme.colorScheme.error
            ),
            textStyle = LocalTextStyle.current.copy(
                fontSize = 15.sp,
                textAlign = TextAlign.Center // Centra el texto introducido.
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
        )
    }
}

/**
 * Sección que contiene el botón principal para comenzar la prueba 6MWT.
 *
 * @param viewModel Instancia del [PreparationViewModel].
 */
@Composable
fun ActionsTopSection(viewModel: PreparationViewModel) {
    // Estado que indica si se puede iniciar la prueba (todos los datos necesarios son válidos).
    val canStartTestEnabled by viewModel.canStartTest.collectAsStateWithLifecycle()

    Column(horizontalAlignment = Alignment.CenterHorizontally) { // Contenedor de la sección.
        Text("Acciones de prueba", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(bottom = 6.dp))
        // Botón para comenzar la prueba.
        Button(
            onClick = { viewModel.onStartTestClicked() }, // Llama al ViewModel al hacer clic.
            enabled = canStartTestEnabled, // Habilitado según el estado del ViewModel.
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 80.dp) // Tamaño mínimo del botón.
                .padding(vertical = 4.dp),
            colors = ButtonDefaults.buttonColors( // Colores personalizados.
                containerColor = ButtonActionColor, contentColor = TextOnSecondary,
                disabledContainerColor = ButtonActionColor.copy(alpha = 0.4f),
                disabledContentColor = TextOnSecondary.copy(alpha = 0.6f)
            ),
            shape = RoundedCornerShape(12.dp) // Bordes redondeados.
        ) {
            Text("Comenzar prueba 6MWT", textAlign = TextAlign.Center, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ---- NUEVA SECCIÓN COMBINADA DE COLOCACIÓN DE DISPOSITIVOS ----
@Composable
fun CombinedDevicePlacementSection(viewModel: PreparationViewModel) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
            "Colocación de dispositivos",
            style = MaterialTheme.typography.titleMedium,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(bottom = 6.dp) // Reducido
                .align(Alignment.CenterHorizontally)
        )

        // Sub-sección Pulsioxímetro
        DevicePlacementSubSection(
            title = "Pulsioxímetro",
            isPlaced = viewModel.isDevicePlaced.collectAsState().value,
            onPlacedToggle = { viewModel.onDevicePlacedToggle(it) },
            placementOptions = listOf(
                PlacementOption("Dedo", DevicePlacementLocation.FINGER),
                PlacementOption("Oreja", DevicePlacementLocation.EAR)
            ),
            currentPlacement = viewModel.devicePlacementLocation.collectAsState().value,
            onPlacementSelected = { location ->
                // Asumimos que el ViewModel espera el enum DevicePlacementLocation
                if (location is DevicePlacementLocation) {
                    viewModel.onDevicePlacementLocationSelected(location)
                }
            }
        )

        FormDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Sub-sección Acelerómetro
        DevicePlacementSubSection(
            title = "Acelerómetro",
            isPlaced = viewModel.isAccelerometerPlaced.collectAsState().value,
            onPlacedToggle = { viewModel.onAccelerometerPlacedToggle(it) },
            placementOptions = listOf(
                PlacementOption("Bolsillo", AccelerometerPlacementLocation.POCKET),
                PlacementOption("Calcetín", AccelerometerPlacementLocation.SOCK)
                // Añade más opciones si es necesario, asegúrate que AccelerometerPlacementLocation las soporta
            ),
            currentPlacement = viewModel.accelerometerPlacementLocation.collectAsState().value,
            onPlacementSelected = { location ->
                // Asumimos que el ViewModel espera el enum AccelerometerPlacementLocation
                if (location is AccelerometerPlacementLocation) {
                    viewModel.onAccelerometerPlacementLocationSelected(location)
                }
            }
        )
    }
}

// Data class para opciones de colocación genéricas
data class PlacementOption<T>(val text: String, val locationValue: T)

@Composable
fun <T> DevicePlacementSubSection(
    title: String,
    isPlaced: Boolean,
    onPlacedToggle: (Boolean) -> Unit,
    placementOptions: List<PlacementOption<T>>,
    currentPlacement: T,
    onPlacementSelected: (T) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) { // Reducido padding
        Text(
            title,
            style = MaterialTheme.typography.titleSmall, // Un poco más pequeño que el título principal de sección
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp) // Reducido
        )
        DevicePlacedSwitch(isPlaced, onPlacedToggle, titlePrefix = "") // Quitamos el prefijo para usar el título de la subsección

        AnimatedVisibility(visible = isPlaced) {
            Column {
                Text(
                    "Ubicación:",
                    fontSize = 16.sp, // Reducido
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp) // Reducido
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp) // Reducido
                ) {
                    placementOptions.forEach { option ->
                        PlacementCheckbox(
                            text = option.text,
                            isSelected = currentPlacement == option.locationValue,
                            onSelected = { onPlacementSelected(option.locationValue) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}


/**
 * Composable para un Switch que indica si el dispositivo está colocado.
 *
 * @param isPlaced Estado actual del switch (colocado o no).
 * @param onCheckedChange Lambda que se ejecuta cuando cambia el estado del switch.
 * @param modifier Modificador para el `Row` contenedor.
 */
@Composable
fun DevicePlacedSwitch(
    isPlaced: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    titlePrefix: String = ""
) {
    Row(modifier
        .fillMaxWidth()
        .padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = if (titlePrefix.isNotBlank()) "¿$titlePrefix colocado?" else "¿Colocado?",
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp))
        Switch(
            checked = isPlaced, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                // Colores personalizados para el switch.
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            // Icono dentro del thumb del switch cuando está activado.
            thumbContent = if (isPlaced) { { Icon(Icons.Filled.Check, "Colocado", Modifier.size(SwitchDefaults.IconSize), tint = MaterialTheme.colorScheme.onPrimary) } } else null
        )
    }
}

/**
 * Composable para un Checkbox utilizado en la selección de la ubicación del dispositivo.
 *
 * @param text Texto a mostrar junto al checkbox.
 * @param isSelected Booleano que indica si el checkbox está seleccionado.
 * @param onSelected Lambda que se ejecuta cuando se selecciona el checkbox.
 * @param modifier Modificador para el `Row` contenedor.
 */
@Composable
fun PlacementCheckbox(text: String, isSelected: Boolean, onSelected: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier
        .clickable(onClick = onSelected) // Permite hacer clic en toda la fila.
        .padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = isSelected, onCheckedChange = { onSelected() },
            colors = CheckboxDefaults.colors( // Colores personalizados para el checkbox.
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary
            )
        )
        Text(text, fontSize = 17.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * Un campo de texto delineado (`OutlinedTextField`) personalizado y reutilizable.
 * Usado para la entrada de datos del paciente (edad, altura, peso).
 *
 * @param value Valor actual del campo.
 * @param onValueChange Lambda que se ejecuta cuando el valor cambia.
 * @param label Etiqueta del campo.
 * @param modifier Modificador para el `OutlinedTextField`.
 * @param keyboardOptions Opciones de teclado.
 * @param readOnly Si el campo es de solo lectura.
 * @param singleLine Si el campo debe ser de una sola línea.
 * @param isError Si el campo está en estado de error.
 */
@Composable
fun CustomTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) }, // Etiqueta más pequeña.
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp), // Tamaño mínimo.
        keyboardOptions = keyboardOptions,
        readOnly = readOnly,
        singleLine = singleLine,
        isError = isError,
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            // Colores personalizados.
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
            focusedBorderColor = if (isError) MaterialTheme.colorScheme.error else DarkerBlueHighlight,
            unfocusedBorderColor = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.7f) else LightBluePrimary.copy(
                alpha = 0.6f
            ),
            disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            cursorColor = DarkerBlueHighlight, errorBorderColor = MaterialTheme.colorScheme.error,
        ),
        textStyle = LocalTextStyle.current.copy(
            fontSize = 17.sp, // Tamaño del texto de entrada.
            textAlign = TextAlign.Center // Centra el texto.
        )

    )
}

/**
 * Composable reutilizable para una fila con un Checkbox y texto.
 * Utilizado para las opciones de medicación ("Usa inhaladores", "Usa oxígeno").
 *
 * @param text Texto a mostrar junto al checkbox.
 * @param checked Estado actual del checkbox.
 * @param onCheckedChange Lambda que se ejecuta cuando cambia el estado del checkbox.
 * @param modifier Modificador para el `Row` contenedor.
 */
@Composable
fun CheckboxRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier
        .fillMaxWidth()
        .clickable { onCheckedChange(!checked) } // Permite hacer clic en toda la fila para cambiar el estado.
        .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors( // Colores personalizados.
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary
            )
        )
        Text(text, fontSize = 17.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * Un divisor horizontal simple y reutilizable para separar secciones o elementos en formularios.
 *
 * @param modifier Modificador para el `HorizontalDivider`.
 */
@Composable
fun FormDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(vertical = 2.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}
