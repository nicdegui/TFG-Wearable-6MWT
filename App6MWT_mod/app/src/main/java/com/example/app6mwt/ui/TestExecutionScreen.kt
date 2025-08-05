package com.example.app6mwt.ui

import android.app.Activity
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.app6mwt.ui.theme.*
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.util.*
import java.util.concurrent.TimeUnit

// FUNCIONES DE EXTENSIÓN DE COLOR
/**
 * Convierte un [BluetoothIconStatus] a su representación de [Color] en Compose.
 * Utilizado para mostrar el color adecuado del icono de estado de Bluetooth.
 */
@Composable
fun BluetoothIconStatus.toActualComposeColor(): Color {
    return when (this) {
        BluetoothIconStatus.GREEN -> Color(0xFF4CAF50) // Verde estándar para conectado/OK
        BluetoothIconStatus.YELLOW -> Color(0xFFFFC107) // Amarillo para advertencias o estados intermedios
        BluetoothIconStatus.RED -> MaterialTheme.colorScheme.error // Rojo del tema para errores
        BluetoothIconStatus.CONNECTING -> Color(0xFF2196F3) // Azul para indicar proceso de conexión
        BluetoothIconStatus.GRAY -> Color.Gray
    }
}

/**
 * Convierte un [StatusColor] (enum genérico para estados) a su [Color] de Compose.
 * Usado para los indicadores de estado de SpO2 y FC.
 */
@Composable
fun StatusColor.toActualColor(): Color {
    return when (this) {
        StatusColor.NORMAL -> Color(0xFF4CAF50) // Verde para normal
        StatusColor.WARNING -> Color(0xFFFFC107) // Amarillo para advertencia
        StatusColor.CRITICAL -> MaterialTheme.colorScheme.error // Rojo del tema para crítico
        StatusColor.UNKNOWN -> Color.Gray // Gris para desconocido o no disponible
    }
}

/**
 * Composable principal para la pantalla de ejecución de la prueba 6MWT.
 *
 * @param preparationData Datos iniciales de la preparación de la prueba (paciente, configuración basal).
 * @param onNavigateBackFromScreen Callback para navegar hacia atrás desde esta pantalla.
 * @param onNavigateToResults Callback para navegar a la pantalla de resultados, pasando los datos del resumen.
 * @param viewModel Instancia de [TestExecutionViewModel] manejada por Hilt.
 */
@OptIn(ExperimentalMaterial3Api::class) // Necesario para algunos componentes de Material 3
@Composable
fun TestExecutionScreen(
    preparationData: TestPreparationData,
    onNavigateBackFromScreen: () -> Unit,
    onNavigateToResults: (testExecutionSummaryData: TestExecutionSummaryData) -> Unit,
    viewModel: TestExecutionViewModel = hiltViewModel() // Inyección del ViewModel
) {
    // Observa el estado de la UI desde el ViewModel como un State de Compose.
    val uiState by viewModel.uiState.collectAsState()
    // Obtiene el contexto actual, útil para Toasts, etc.
    val context = LocalContext.current
    // Accede a la ventana de la Activity actual para controlar flags (ej. mantener pantalla encendida).
    val window = (LocalView.current.context as? Activity)?.window

    // SideEffect para ejecutar código que interactúa con el sistema fuera de la composición.
    // En este caso, para mantener la pantalla encendida durante la prueba.
    SideEffect {
        if (uiState.isTestRunning) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Maneja la pulsación del botón de retroceso del dispositivo.
    // `enabled = true` significa que siempre interceptará el evento.
    BackHandler(enabled = true) {
        Log.d("TestExecutionScreen", "BackHandler presionado. Solicitando confirmación de salida.")
        // Solicita confirmación al ViewModel antes de salir.
        viewModel.requestExitConfirmation()
    }

    // LaunchedEffect se ejecuta cuando la `key1` (preparationData) cambia.
    // Útil para inicializar el ViewModel con los datos de preparación cuando la pantalla se carga
    // o cuando estos datos cambian (aunque en este flujo, suelen ser estáticos una vez que se llega aquí).
    LaunchedEffect(key1 = preparationData) {
        Log.d("TestExecutionScreen", "LaunchedEffect: Initializing ViewModel with preparationData: ID ${preparationData.patientId}")
        viewModel.initializeTest(preparationData)
    }

    // LaunchedEffect para manejar la navegación automática a la pantalla de resultados.
    // Se dispara si cambian `testSummaryDataForNavigation`, `isTestFinished`, `testFinishedInfoMessage`, o `showNavigateToResultsConfirmationDialog`.
    // La navegación ocurre solo si hay un resumen, la prueba ha terminado, no hay mensajes informativos pendientes
    // y no se está mostrando el diálogo de confirmación para ir a resultados (evita doble navegación).
    LaunchedEffect(uiState.testSummaryDataForNavigation, uiState.isTestFinished, uiState.testFinishedInfoMessage, uiState.showNavigateToResultsConfirmationDialog) {
        val summary = uiState.testSummaryDataForNavigation
        if (summary != null && uiState.isTestFinished && uiState.testFinishedInfoMessage == null && !uiState.showNavigateToResultsConfirmationDialog){
            onNavigateToResults(summary) // Ejecuta la navegación
            viewModel.onNavigationToResultsCompleted() // Notifica al ViewModel que la navegación se completó para limpiar el trigger.
        }
    }

    // LaunchedEffect para mostrar mensajes al usuario (Toasts) cuando `userMessage` en el UiState cambia.
    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { // Si hay un mensaje
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show() // Lo muestra
            viewModel.clearUserMessage() // Limpia el mensaje en el ViewModel para no mostrarlo de nuevo.
        }
    }
    // Aplica el tema de la aplicación.
    App6MWTTheme {
        // Scaffold es un layout básico de Material Design que provee estructura para TopAppBar, BottomAppBar, FAB, etc.
        Scaffold(
            topBar = {
                // CenterAlignedTopAppBar es una TopAppBar donde el título está centrado.
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = "EJECUCIÓN PRUEBA 6MWT - ${uiState.patientFullName.uppercase()}",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 25.sp, // Tamaño de fuente personalizado
                                fontWeight = FontWeight.Bold // Fuente en negrita
                            ),
                            maxLines = 1, // Limita el título a una línea
                            overflow = TextOverflow.Ellipsis // Añade "..." si el texto es muy largo
                        )
                    },
                    navigationIcon = {
                        // Un Row para agrupar iconos de navegación si hay más de uno.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Icono 1: Flecha de retroceso (siempre visible)
                            IconButton(onClick = {
                                Log.d("TestExecutionScreen", "TopAppBar Back presionado. Solicitando confirmación de salida.")
                                viewModel.requestExitConfirmation() // Misma acción que el BackHandler del dispositivo.
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Regresar", tint = TextOnSecondary)
                            }
                            // Icono 2: "Continuar a Resultados" (visible solo cuando `canNavigateToResults` es true).
                            if (uiState.canNavigateToResults) {
                                IconButton(
                                    onClick = { viewModel.onContinueToResultsClicked() },
                                    modifier = Modifier
                                        .padding(start = 8.dp) // Espacio a la izquierda del icono anterior
                                        .size(40.dp) // Tamaño del área del botón
                                        .background(
                                            SuccessGreenColor, // Color de fondo personalizado
                                            shape = RoundedCornerShape(8.dp) // Esquinas redondeadas
                                        )
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Continuar a resultados",
                                        tint = Color.White, // Color del icono
                                        modifier = Modifier.size(24.dp), // Tamaño del icono en sí

                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Acciones en el lado derecho de la TopAppBar.
                        // Muestra el ID del paciente.
                        Text(
                            text = "ID: ${uiState.patientId.uppercase()}",
                            modifier = Modifier
                                .padding(end = 16.dp) // Espacio al final
                                .align(Alignment.CenterVertically), // Centrado verticalmente con el título
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            fontSize = 25.sp,  // Mismo tamaño que el título
                            color = TextOnSecondary, // Color del texto
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        // Define los colores de la TopAppBar.
                        containerColor = MaterialTheme.colorScheme.primary, // Color de fondo
                        titleContentColor = TextOnSecondary, // Color del título
                        actionIconContentColor = TextOnSecondary // Color de los iconos de acción (y texto en `actions`)
                    )
                )
            },
            containerColor = BackgroundColor // Color de fondo para el contenido del Scaffold.
        ) { paddingValues -> // `paddingValues` contiene los paddings aplicados por la TopAppBar.
            // Contenido principal de la pantalla.
            // Se muestra un CircularProgressIndicator mientras `preparationDataLoaded` es false.
            if (uiState.preparationDataLoaded) {
                Row(
                    // Layout principal en forma de Fila (Row) para dividir la pantalla en secciones.
                    modifier = Modifier
                        .fillMaxSize() // Ocupa todo el espacio disponible.
                        .padding(paddingValues) // Aplica los paddings de la TopAppBar.
                        .padding(horizontal = 16.dp, vertical = 12.dp) // Padding adicional para el contenido.
                ) {
                    // Sección Izquierda (gráficos).
                    LeftSection(
                        modifier = Modifier
                            .weight(0.50f) // Ocupa el 50% del ancho disponible.
                            .fillMaxHeight(), // Ocupa toda la altura.
                        uiState = uiState // Pasa el estado de la UI.
                    )
                    Spacer(modifier = Modifier.width(16.dp)) // Espaciador horizontal.
                    // Sección Central (controles principales, tiempo, vueltas).
                    CentralSection(
                        modifier = Modifier
                            .weight(0.22f) // Ocupa el 22% del ancho.
                            .fillMaxHeight(),
                        uiState = uiState,
                        viewModel = viewModel // Pasa el ViewModel para acciones.
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    // Sección Derecha (paradas, valores min/max, estado Bluetooth).
                    RightSection(
                        modifier = Modifier
                            .weight(0.28f) // Ocupa el 28% del ancho.
                            .fillMaxHeight(),
                        uiState = uiState,
                        viewModel = viewModel
                    )
                }
            } else {
                // Muestra un indicador de progreso si los datos de preparación aún no están cargados.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues), contentAlignment = Alignment.Center // Centra el indicador.
                ) {
                    CircularProgressIndicator()
                }
            }
        } // Fin del Scaffold

        // --- DIÁLOGOS ---
        // La lógica de los diálogos se coloca fuera del `content` del androidx . compose . material3 . Scaffold
        // para que se superpongan a toda la pantalla.

        // Diálogo de confirmación para RESTART (durante la prueba) o REINITIALIZE (después de la prueba).
        // Se muestra si `showMainActionConfirmationDialog` es true.
        if (uiState.showMainActionConfirmationDialog) {
            val title: String
            val confirmText: String
            val onConfirmAction: () -> Unit

            // Determina el título, texto del botón y acción según el `mainButtonAction` actual.
            when (uiState.mainButtonAction) {
                MainButtonAction.RESTART_DURING_TEST -> {
                    title = "Confirmar reinicio de prueba"
                    confirmText = "Reiniciar prueba"
                    onConfirmAction = { viewModel.confirmRestartTestAndReturnToConfig() }
                }
                MainButtonAction.REINITIALIZE_AFTER_TEST -> {
                    title = "Confirmar reconfiguración"
                    confirmText = "Reconfigurar"
                    onConfirmAction = { viewModel.confirmReinitializeTestToConfig() }
                }
                else -> { // MainButtonAction.START o un estado inesperado.
                    // Este diálogo no debería mostrarse para START, así que esto es un fallback.
                    title = "Confirmar acción"
                    confirmText = "Confirmar"
                    onConfirmAction = {}
                }
            }

            // Utiliza el Composable reutilizable `ConfirmationDialog`.
            ConfirmationDialog(
                title = title,
                text = uiState.mainActionConfirmationMessage, // El mensaje viene del ViewModel
                onConfirm = {
                    onConfirmAction()
                    // El ViewModel es responsable de poner showMainActionConfirmationDialog a false
                },
                onDismiss = { viewModel.dismissMainActionConfirmationDialog() }, // Acción al descartar.
                confirmButtonText = confirmText,
                dismissButtonText = "Cancelar" // Texto del botón de descarte.
            )
        }

        // Diálogo de confirmación para salir de la pantalla.
        if (uiState.showExitConfirmationDialog) {
            ConfirmationDialog(
                title = "Confirmar salida",
                text = "Si sale, la prueba actual se cancelará y los datos no guardados se perderán. ¿Está seguro?",
                onConfirm = {
                    viewModel.confirmExitTest() // Notifica al ViewModel que se confirma la salida.
                    onNavigateBackFromScreen() // Ejecuta la navegación hacia atrás.
                },
                onDismiss = { viewModel.dismissExitConfirmation() },
                confirmButtonText = "Salir",
                dismissButtonText = "Cancelar"
            )
        }

        // Diálogo para el mensaje informativo de fin de prueba (completada o detenida).
        // Se muestra si `testFinishedInfoMessage` tiene un valor.
        uiState.testFinishedInfoMessage?.let { message ->
            val isCompletedNormally = uiState.currentTimeMillis >= TEST_DURATION_MILLIS
            val titleText = if (isCompletedNormally) "Prueba finalizada" else "Prueba detenida"
            // Utiliza el Composable reutilizable `InfoDialog`.
            InfoDialog(
                title = titleText,
                text = message,
                onDismiss = {
                    viewModel.dismissTestFinishedInfoDialog() // Descarta el diálogo.
                },
                buttonText = "Entendido" // Texto del único botón.
            )
        }

        // Diálogo de cuenta atrás para detener la prueba.
        if (uiState.showStopConfirmationDialog) {
            // Utiliza el Composable reutilizable `CountdownDialog`.
            CountdownDialog(
                title = "Deteniendo prueba",
                countdownValue = uiState.stopCountdownSeconds, // Valor actual de la cuenta atrás.
                onCancel = { viewModel.cancelStopTest() } // Acción al cancelar la detención.
            )
        }

        // Diálogo de confirmación para navegar a la pantalla de resultados.
        if (uiState.showNavigateToResultsConfirmationDialog) {
            ConfirmationDialog(
                title = "Ver resultados",
                text = NAVIGATE_TO_RESULTS_CONFIRMATION_MESSAGE, // Constante con el mensaje.
                onConfirm = { viewModel.confirmNavigateToResults() }, // Confirma y el ViewModel gestionará la navegación.
                onDismiss = { viewModel.dismissNavigateToResultsConfirmation() },
                confirmButtonText = "Continuar",
                dismissButtonText = "Cancelar"
            )
        }

        // Diálogo de confirmación para eliminar la última parada registrada.
        if (uiState.showDeleteLastStopConfirmationDialog) {
            ConfirmationDialog(
                title = "Eliminar última parada",
                text = "¿Está seguro de que desea eliminar la última parada registrada?",
                onConfirm = { viewModel.confirmDeleteLastStop() },
                onDismiss = { viewModel.dismissDeleteLastStopConfirmation() },
                confirmButtonText = "Eliminar",
                dismissButtonText = "Cancelar"
            )
        }

        // Lógica para mostrar un Toast de alerta si se detecta un valor crítico durante la prueba.
        // `isCriticalAlarm` es true si SpO2 o FC están en estado crítico.
        val isCriticalAlarm = uiState.spo2StatusColor == StatusColor.CRITICAL || uiState.heartRateStatusColor == StatusColor.CRITICAL
        if (isCriticalAlarm && uiState.isTestRunning) {
            // LaunchedEffect se usa para mostrar el Toast cuando cambian los estados relevantes
            // y las condiciones se cumplen.
            LaunchedEffect(
                uiState.spo2StatusColor,
                uiState.heartRateStatusColor,
                uiState.isTestRunning // Claves que, si cambian, re-evalúan el efecto.
            ) {
                // Comprobación adicional dentro del LaunchedEffect para asegurar que las condiciones
                // siguen siendo verdaderas en el momento de la ejecución
                if (uiState.isTestRunning && (uiState.spo2StatusColor == StatusColor.CRITICAL || uiState.heartRateStatusColor == StatusColor.CRITICAL)) {
                    Toast.makeText(context, "¡ALERTA! Valor crítico detectado.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

// SECCIONES PRINCIPALES (Left, Central, Right)
/**
 * Composable para la sección izquierda de la pantalla, que muestra los gráficos de SpO2 y FC.
 *
 * @param modifier Modificador para este Composable.
 * @param uiState Estado actual de la UI de ejecución de la prueba.
 */
@Composable
fun LeftSection(modifier: Modifier = Modifier, uiState: TestExecutionUiState) {
    // Colores para los gráficos, obtenidos del tema de Material Design.
    // `.toArgb()` convierte el Color de Compose a un entero ARGB, que es lo que espera MPAndroidChart.
    val chartTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val chartGridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f).toArgb() // Rejilla semitransparente
    val chartLineColorSpo2 = MaterialTheme.colorScheme.tertiary.toArgb() // Color terciario para SpO2
    val chartLineColorHr = MaterialTheme.colorScheme.secondary.toArgb() // Color secundario para FC

    // Columna principal para organizar los dos gráficos verticalmente.
    Column(
        modifier = modifier.fillMaxHeight(), // Ocupa toda la altura disponible.
        verticalArrangement = Arrangement.spacedBy(12.dp) // Espacio entre los gráficos.
    ) {
        // --- Sección Gráfico SpO2 ---
        ExecutionSectionCard(
            modifier = Modifier.weight(1f), // Cada gráfico ocupa el mismo espacio vertical.
            title = "SpO2 (%)",
            titleStyle = MaterialTheme.typography.titleLarge.copy(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        ) {
            // Muestra el gráfico si no está en la fase de configuración y hay puntos de datos.
            if (!uiState.isConfigPhase && uiState.spo2DataPoints.isNotEmpty()) {
                LineChartComposable(
                    modifier = Modifier.fillMaxSize(), // El gráfico ocupa todo el espacio de la Card.
                    dataPoints = uiState.spo2DataPoints,
                    yAxisMin = 85f, // Límite inferior del eje Y para SpO2.
                    yAxisMax = 100f, // Límite superior del eje Y.
                    yAxisLabelCount = 5, // Número de etiquetas en el eje Y.
                    xAxisLabelCount = 7, // Número de etiquetas en el eje X (tiempo).
                    lineColor = chartLineColorSpo2,
                    textColor = chartTextColor,
                    gridColor = chartGridColor
                )
            } else {
                // Muestra un placeholder si no hay datos o se está en fase de configuración.
                ChartPlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    text = if (uiState.isConfigPhase) "Esperando inicio de prueba..." else "No hay datos de SpO2"
                )
            }
        }

        // --- Sección Gráfico FC (Frecuencia Cardíaca) ---
        ExecutionSectionCard(
            modifier = Modifier.weight(1f),
            title = "FC (lpm)",
            titleStyle = MaterialTheme.typography.titleLarge.copy(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        ) {
            // Similar al gráfico de SpO2, muestra el gráfico de FC o un placeholder.
            if (!uiState.isConfigPhase && uiState.heartRateDataPoints.isNotEmpty()) {
                LineChartComposable(
                    modifier = Modifier.fillMaxSize(),
                    dataPoints = uiState.heartRateDataPoints,
                    yAxisMin = 60f, // Límite inferior del eje Y para FC.
                    yAxisMax = 160f, // Límite superior del eje Y.
                    yAxisLabelCount = 8,
                    xAxisLabelCount = 7,
                    lineColor = chartLineColorHr,
                    textColor = chartTextColor,
                    gridColor = chartGridColor
                )
            } else {
                ChartPlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    text = if (uiState.isConfigPhase) "Esperando inicio de prueba..." else "No hay datos de FC"
                )
            }
        }
    }
}

/**
 * Composable que muestra un texto centrado como placeholder para los gráficos
 * cuando no hay datos disponibles o la prueba no ha comenzado.
 *
 * @param modifier Modificador para este Composable.
 * @param text Texto a mostrar en el placeholder.
 */
@Composable
fun ChartPlaceholder(modifier: Modifier, text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize() // Ocupa todo el espacio del contenedor padre.
            .padding(4.dp), // Pequeño padding interno.
        contentAlignment = Alignment.Center // Centra el texto.
    ) {
        Text(
            text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, // Color de texto sutil.
            fontSize = 17.sp
        )
    }
}

/**
 * Composable para la sección central de la pantalla.
 * Contiene el cronómetro, botones de control (START/RESTART/REINICIAR, STOP),
 * y los campos para la longitud de pista, vueltas, distancia, y valores en vivo de SpO2/FC.
 *
 * @param modifier Modificador para este Composable.
 * @param uiState Estado actual de la UI.
 * @param viewModel ViewModel para manejar las acciones del usuario.
 */
@Composable
fun CentralSection(
    modifier: Modifier = Modifier,
    uiState: TestExecutionUiState,
    viewModel: TestExecutionViewModel
) {
    val focusManager = LocalFocusManager.current // Para controlar el foco (ej. ocultar teclado).

    // Variables locales para simplificar el acceso a los flags de estado de la prueba.
    val isDuringTest = uiState.isTestRunning // La prueba está activamente en curso.

    // Columna principal para la sección central.
    Column(
        modifier = modifier.padding(horizontal = 4.dp), // Padding horizontal.
        horizontalAlignment = Alignment.CenterHorizontally, // Centra los elementos horizontalmente.
        verticalArrangement = Arrangement.spacedBy(12.dp) // Espacio vertical entre elementos.
    ) {
        // --- TÍTULO Y DISPLAY DEL TIEMPO ---
        Text(
            "TIEMPO",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp)
                .background(ElementBackgroundColor, RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = uiState.currentTimeFormatted, // Tiempo formateado desde el ViewModel.
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // --- BOTÓN PRINCIPAL (START / RESTART / REINICIAR) ---
        Button(
            onClick = {
                viewModel.onMainButtonClicked() // Delega la lógica al ViewModel.
                focusManager.clearFocus() // Oculta el teclado si estuviera abierto.
            },
            enabled = true, // El ViewModel gestiona internamente si la acción es permitida.
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ButtonActionColor, // Color de acción definido en el tema.
                contentColor = Color.White // Texto e icono en blanco.
            )
        ) {
            // El icono y texto del botón cambian según `uiState.mainButtonAction`.
            val icon = when (uiState.mainButtonAction) {
                MainButtonAction.START -> Icons.Filled.PlayArrow
                MainButtonAction.RESTART_DURING_TEST -> Icons.Filled.Replay // O un icono específico de "restart"
                MainButtonAction.REINITIALIZE_AFTER_TEST -> Icons.Filled.Replay
            }
            val text = when (uiState.mainButtonAction) {
                MainButtonAction.START -> "START"
                MainButtonAction.RESTART_DURING_TEST -> "RESTART"
                MainButtonAction.REINITIALIZE_AFTER_TEST -> "REINICIAR"
            }

            Icon(
                imageVector = icon,
                contentDescription = text, // Descripción para accesibilidad.
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(8.dp)) // Espacio entre icono y texto.
            Text(
                text = text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // --- BOTÓN STOP ---
        Button(
            onClick = {
                viewModel.onStopTestInitiated() // Inicia el proceso de detención (puede incluir cuenta atrás).
                focusManager.clearFocus()
            },
            enabled = isDuringTest, // Solo habilitado si la prueba está en curso.
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error, // Color rojo para acción destructiva/stop.
                contentColor = MaterialTheme.colorScheme.onError, // Color del contenido sobre el color de error.
                disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f), // Atenuado cuando está deshabilitado.
                disabledContentColor = MaterialTheme.colorScheme.onError.copy(alpha = 0.6f)
            )
        ) {
            Icon(Icons.Filled.Stop, "Detener Prueba", modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Text("STOP", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        // --- DISPLAY DISTANCIA ---
        LabeledDisplay(
            label = "DISTANCIA",
            borderColor = MaterialTheme.colorScheme.outline,
            backgroundColor = ElementBackgroundColor.copy(alpha = 0.7f) // Fondo ligeramente diferente para indicar solo display.
        ) {
            Text(
                // Formatea la distancia a metros sin decimales.
                text = String.format(Locale.US, "%.2f metros", uiState.distanceMeters),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // --- DISPLAY VALORES EN VIVO (SpO2 y FC) ---
        // Utiliza el Composable `LiveValueWithIndicator`.
        LiveValueWithIndicator(
            label = "SpO2",
            value = "${uiState.currentSpo2?.takeIf { it > 0 } ?: "--"} %", // Muestra "--" si el valor no es válido.
            trend = uiState.spo2Trend, // Tendencia (arriba, abajo, estable).
            statusColor = uiState.spo2StatusColor.toActualColor(), // Color del indicador de estado.
            valueFontSize = 20.sp,
            borderColor = MaterialTheme.colorScheme.outline,
            backgroundColor = ElementBackgroundColor.copy(alpha = 0.7f)
        )

        LiveValueWithIndicator(
            label = "FC",
            value = "${uiState.currentHeartRate?.takeIf { it > 0 } ?: "--"} lpm",
            trend = uiState.heartRateTrend,
            statusColor = uiState.heartRateStatusColor.toActualColor(),
            valueFontSize = 20.sp,
            borderColor = MaterialTheme.colorScheme.outline,
            backgroundColor = ElementBackgroundColor.copy(alpha = 0.7f)
        )
        // --- INDICADOR DE ESTADO DEL ACELERÓMETRO ---
        BluetoothStatusIndicatorButton(
            status = uiState.accelerometerBluetoothIconStatus,
            message = uiState.accelerometerBluetoothStatusMessage,
            isAttemptingReconnect = uiState.isAttemptingAccelerometerForceReconnect,
            onClick = { viewModel.onAccelerometerIconClicked() },
            modifier = Modifier
                .fillMaxWidth(0.95f) // Ocupa el 95% del ancho para un ligero margen.
                .align(Alignment.CenterHorizontally) // Centrado.
        )
        Spacer(modifier = Modifier.height(4.dp)) // Pequeño espacio al final si es necesario
    }
}

/**
 * Composable para la sección derecha de la pantalla.
 * Muestra el contador de paradas, botones para añadir/eliminar paradas,
 * la tabla de registro de paradas, valores min/max de SpO2/FC, y el indicador de estado de Bluetooth.
 *
 * @param modifier Modificador para este Composable.
 * @param uiState Estado actual de la UI.
 * @param viewModel ViewModel para manejar acciones.
 */
@Composable
fun RightSection(
    modifier: Modifier = Modifier,
    uiState: TestExecutionUiState,
    viewModel: TestExecutionViewModel
) {
    // Lógica para habilitar/deshabilitar controles basados en el estado de la prueba.
    val isDuringTest = uiState.isTestRunning && !uiState.isTestFinished
    val isAfterTest = uiState.isTestFinished && !uiState.isTestRunning
    val canAddStop = isDuringTest // Solo se pueden añadir paradas durante la prueba.
    // Se pueden eliminar paradas si hay alguna, durante o después de la prueba (para correcciones).
    val canDeleteStop = uiState.stopsCount > 0 && (isDuringTest || isAfterTest)

    Column(modifier = modifier) {
        // Columna interna para la sección de paradas, que permite que la tabla de paradas
        // no ocupe toda la altura si el resto del contenido es pequeño.
        Column(modifier = Modifier.weight(1f, fill = false)) { // `fill = false` es importante aquí.
            // --- SECCIÓN DE PARADAS (CONTADOR Y BOTONES) ---
            ExecutionSectionCard(
                titleStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start),
                backgroundColor = ElementBackgroundColor,
                borderColor = MaterialTheme.colorScheme.outline
            ) {
                Column { // Contenido dentro de la Card.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(), // Padding por defecto de la Card o se puede ajustar.
                        horizontalArrangement = Arrangement.SpaceBetween, // Espacia el texto y los botones.
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PARADAS: ${uiState.stopsCount}", // Contador de paradas.
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) { // Botones de añadir/eliminar.
                            IconButton(
                                onClick = { viewModel.onAddStop() },
                                enabled = canAddStop,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Add, "Añadir Parada",
                                    // Color cambia según si está habilitado o no.
                                    tint = if (canAddStop) ButtonActionColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { viewModel.requestDeleteLastStopConfirmation() }, // Pide confirmación.
                                enabled = canDeleteStop,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Delete, "Eliminar Última Parada",
                                    tint = if (canDeleteStop) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // --- TABLA DE REGISTRO DE PARADAS ---
            Text(
                "Registro de Paradas:",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterHorizontally) // Centra el título de la tabla.
            )
            StopsTable(
                stops = uiState.stopRecords, // Lista de paradas desde el UiState.
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp), // Altura máxima para la tabla (scroll si hay muchos items).
                showPlaceholder = uiState.stopRecords.isEmpty(), // Muestra placeholder si la lista está vacía.
                placeholderText = if (uiState.isConfigPhase) "Las paradas se registrarán al iniciar la prueba."
                else if (uiState.stopRecords.isEmpty()) "No hay paradas registradas."
                else "" // No debería mostrarse si showPlaceholder es true y stops no está vacío.
            )
        }

        Spacer(modifier = Modifier.height(16.dp)) // Espacio antes de los valores min/max.

        // --- DISPLAYS DE VALORES MÍNIMOS Y MÁXIMOS ---
        LabeledDisplay(label = "SpO2 MÍNIMO", borderColor = MaterialTheme.colorScheme.outline, backgroundColor = ElementBackgroundColor.copy(alpha = 0.7f)) {
            Text(
                // Muestra el valor o "---" si no hay registro.
                text = uiState.minSpo2Record?.value?.let { "$it %" } ?: "---",
                fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        LabeledDisplay(label = "FC MÍNIMA", borderColor = MaterialTheme.colorScheme.outline, backgroundColor = ElementBackgroundColor.copy(alpha = 0.7f)) {
            Text(
                text = uiState.minHeartRateRecord?.value?.let { "$it lpm" } ?: "---",
                fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        LabeledDisplay(label = "FC MÁXIMA", borderColor = MaterialTheme.colorScheme.outline, backgroundColor = ElementBackgroundColor.copy(alpha = 0.7f)) {
            Text(
                text = uiState.maxHeartRateRecord?.value?.let { "$it lpm" } ?: "---",
                fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(10.dp)) // Espacio antes del indicador de Bluetooth.

        // --- INDICADOR/BOTÓN DE ESTADO DE BLUETOOTH ---
        BluetoothStatusIndicatorButton(
            status = uiState.oximeterBluetoothIconStatus,
            message = uiState.oximeterBluetoothStatusMessage,
            isAttemptingReconnect = uiState.isAttemptingOximeterForceReconnect, // Para animaciones de reconexión.
            onClick = { viewModel.onOximeterIconClicked() }, // Acción al hacer clic.
            modifier = Modifier
                .fillMaxWidth(0.95f) // Ocupa el 95% del ancho para un ligero margen.
                .align(Alignment.CenterHorizontally) // Centrado.
        )
    }
}


// COMPONENTES REUTILIZABLES
/**
 * Un Composable de Card reutilizable para las secciones de la pantalla de ejecución.
 * Proporciona un marco con título opcional, color de fondo y borde.
 *
 * @param modifier Modificador para la Card.
 * @param title Título opcional a mostrar en la parte superior de la Card.
 * @param titleStyle Estilo para el texto del título.
 * @param backgroundColor Color de fondo de la Card.
 * @param borderColor Color del borde de la Card.
 * @param content El contenido Composable a mostrar dentro de la Card.
 */
@Composable
fun ExecutionSectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    backgroundColor: Color = ElementBackgroundColor, // Color de fondo por defecto.
    borderColor: Color = MaterialTheme.colorScheme.outline, // Color de borde por defecto.
    content: @Composable ColumnScope.() -> Unit // Contenido flexible mediante lambda con receptor.
) {
    Card(
        modifier = modifier.fillMaxWidth(), // La Card ocupa todo el ancho por defecto.
        shape = RoundedCornerShape(12.dp), // Esquinas redondeadas.
        colors = CardDefaults.cardColors(containerColor = backgroundColor),  // Define el color de fondo.
        border = BorderStroke(1.dp, borderColor) // Define el borde.
    ) {
        Column { // Contenido interno organizado verticalmente.
            if (title != null) { // Si se proporciona un título...
                Text(
                    title,
                    style = titleStyle,
                    textAlign = TextAlign.Center, // Título centrado.
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor.copy(alpha = 0.3f)) // Fondo ligeramente diferente para el área del título.
                        .padding(vertical = 5.dp, horizontal = 8.dp), // Padding para el título.
                    color = MaterialTheme.colorScheme.onSurface // Color del texto del título.
                )
                HorizontalDivider(color = borderColor) // Divisor debajo del título.
            }
            // Contenedor para el contenido principal de la Card.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp) // Padding horizontal para el contenido.
                    .padding(bottom = 4.dp), // Padding inferior para el contenido.
            ){
                content() // Invoca el Composable de contenido pasado como parámetro.
            }
        }
    }
}

/**
 * Composable que envuelve la librería MPAndroidChart para mostrar un gráfico de líneas.
 *
 * @param modifier Modificador para el gráfico.
 * @param dataPoints Lista de [DataPoint] a graficar.
 * @param yAxisMin Valor mínimo para el eje Y.
 * @param yAxisMax Valor máximo para el eje Y.
 * @param yAxisLabelCount Número de etiquetas en el eje Y.
 * @param xAxisLabelCount Número de etiquetas en el eje X.
 * @param lineColor Color de la línea del gráfico (formato ARGB).
 * @param textColor Color del texto de los ejes (formato ARGB).
 * @param gridColor Color de las líneas de la rejilla (formato ARGB).
 */
@Composable
fun LineChartComposable(
    modifier: Modifier,
    dataPoints: List<DataPoint>, // DataPoint es una clase de datos definida en el ViewModel/modelo.
    yAxisMin: Float,
    yAxisMax: Float,
    yAxisLabelCount: Int,
    xAxisLabelCount: Int = 7, // Valor por defecto para las etiquetas del eje X.
    lineColor: Int, // Colores como Int ARGB para MPAndroidChart.
    textColor: Int,
    gridColor: Int
) {
    // AndroidView permite integrar Views de Android tradicionales en un layout de Compose.
    AndroidView(
        factory = { // `factory` se llama una vez para crear la View.
            LineChart(it).apply {
                // --- Configuración General del Gráfico ---
                description.isEnabled = false // Sin descripción.
                setDrawGridBackground(false) // Sin fondo de rejilla.
                setTouchEnabled(true) // Habilita interacción táctil (zoom, scroll).
                isDragEnabled = true // Habilita arrastrar/scroll.
                setScaleEnabled(true) // Habilita escalar/zoom con dos dedos.
                setPinchZoom(true) // Habilita zoom de pellizco.
                isAutoScaleMinMaxEnabled = false // No auto-escalar min/max, se definen manualmente.

                // --- Configuración del Eje X (Tiempo) ---
                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM // Eje X en la parte inferior.
                    setDrawGridLines(true) // Mostrar líneas de rejilla verticales.
                    this.textColor = textColor // Color del texto del eje.
                    this.gridColor = gridColor // Color de las líneas de rejilla.
                    axisMinimum = 0f // Inicia en 0 segundos.
                    axisMaximum = TEST_DURATION_MILLIS.toFloat() // Duración máxima de la prueba.
                    setLabelCount(xAxisLabelCount, true) // Forzar el número de etiquetas.
                    valueFormatter = object : ValueFormatter() { // Formateador para las etiquetas del eje X.
                        override fun getAxisLabel(value: Float, axis: com.github.mikephil.charting.components.AxisBase?): String {
                            // Convierte milisegundos a formato "mm:ss".
                            val minutes = TimeUnit.MILLISECONDS.toMinutes(value.toLong())
                            val seconds = TimeUnit.MILLISECONDS.toSeconds(value.toLong()) % 60
                            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                        }
                    }
                }

                // --- Configuración del Eje Y Izquierdo (Valor) ---
                axisLeft.apply {
                    setDrawGridLines(true) // Mostrar líneas de rejilla horizontales.
                    this.textColor = textColor
                    this.gridColor = gridColor
                    axisMinimum = yAxisMin // Mínimo definido por parámetro.
                    axisMaximum = yAxisMax // Máximo definido por parámetro.
                    setLabelCount(yAxisLabelCount, true) // Forzar número de etiquetas.
                }
                axisRight.isEnabled = false // Deshabilitar eje Y derecho.
                legend.isEnabled = false // Deshabilitar leyenda.
            }
        },
        update = { chart -> // `update` se llama cuando `dataPoints` (u otros parámetros clave) cambian.
            // Convierte `DataPoint` a `Entry` de MPAndroidChart.
            val entries = dataPoints.map { Entry(it.timeMillis.toFloat(), it.value) }
            // Crea el conjunto de datos para la línea.
            val dataSet = LineDataSet(entries, "Data").apply { // "Data" es la etiqueta del conjunto de datos.
                this.color = lineColor // Color de la línea.
                setCircleColor(lineColor) // Color de los puntos.
                circleRadius = 2.0f // Radio de los puntos.
                setDrawCircleHole(false) // Puntos sólidos.
                lineWidth = 1.8f // Ancho de la línea.
                setDrawValues(false) // No dibujar los valores numéricos sobre los puntos.
                mode = LineDataSet.Mode.LINEAR // Modo de dibujo de la línea (puede ser CUBIC_BEZIER, etc.).
            }
            chart.data = LineData(dataSet) // Asigna los datos al gráfico.
            chart.setVisibleXRangeMaximum(TEST_DURATION_MILLIS.toFloat())
            chart.notifyDataSetChanged() // Notifica al gráfico que los datos cambiaron.
            chart.invalidate() // Redibuja el gráfico.
        },
        modifier = Modifier // Aplica el modificador pasado.
            .fillMaxSize() // Por defecto, intenta llenar el tamaño máximo.
            .heightIn(min = 180.dp) // Altura mínima para asegurar visibilidad.
    )
}

/**
 * Un Composable reutilizable que muestra una etiqueta encima de un contenido enmarcado.
 *
 * @param label Texto de la etiqueta (se muestra en mayúsculas).
 * @param labelStyle Estilo para el texto de la etiqueta.
 * @param borderColor Color del borde del contenedor del contenido.
 * @param backgroundColor Color de fondo del contenedor del contenido.
 * @param contentPadding Padding interno para el contenedor del contenido.
 * @param content El Composable del contenido a mostrar debajo de la etiqueta.
 */
@Composable
fun LabeledDisplay(
    label: String,
    labelStyle: TextStyle = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal),
    borderColor: Color = MaterialTheme.colorScheme.outline,
    backgroundColor: Color = ElementBackgroundColor,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp), // Padding por defecto.
    content: @Composable () -> Unit // Contenido flexible.
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, // Centra la etiqueta y el contenido.
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label.uppercase(), // Etiqueta en mayúsculas.
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp, // Tamaño de fuente para la etiqueta.
            modifier = Modifier.padding(bottom = 4.dp) // Espacio debajo de la etiqueta.
        )
        Box( // Contenedor para el contenido.
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor, RoundedCornerShape(10.dp)) // Fondo y esquinas redondeadas.
                .border(1.dp, borderColor, RoundedCornerShape(10.dp)) // Borde.
                .padding(contentPadding), // Padding interno para el contenido.
            contentAlignment = Alignment.Center // Centra el contenido dentro del Box.
        ) {
            content() // Renderiza el contenido proporcionado.
        }
    }
}

/**
 * Composable para mostrar un valor en vivo (como SpO2 o FC) con una etiqueta,
 * una flecha de tendencia y un indicador de estado (luz de color).
 *
 * @param label Texto de la etiqueta.
 * @param value Valor actual a mostrar.
 * @param trend [Trend] del valor (ARRIBA, ABAJO, ESTABLE).
 * @param statusColor [Color] del indicador de estado.
 * @param valueFontSize Tamaño de la fuente para el valor.
 * @param iconSize Tamaño para los iconos de tendencia y estado.
 * @param borderColor Color del borde del contenedor.
 * @param backgroundColor Color de fondo del contenedor.
 */
@Composable
fun LiveValueWithIndicator(
    label: String,
    value: String,
    trend: Trend,
    statusColor: Color, // Ya es Color, no StatusColor
    valueFontSize: TextUnit = 22.sp,
    iconSize: Dp = 28.dp,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    backgroundColor: Color = ElementBackgroundColor // Usar directamente ElementBackgroundColor si es el deseado
) {
    LabeledDisplay( // Reutilizamos LabeledDisplay para la estructura base del título
        label = label,
        borderColor = borderColor,
        backgroundColor = backgroundColor, // El fondo del LabeledDisplay actuará como fondo general
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround // O SpaceBetween si se prefiere
        ) {
            Text(
                value,
                fontSize = valueFontSize,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface, // Color del texto del valor
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f) // Darle peso para que ocupe el espacio disponible
            )
            TrendArrow(trend, iconSize) // El tamaño del icono se pasa aquí
            StatusLight(statusColor, iconSize) // El tamaño del icono se pasa aquí
        }
    }
}

/**
 * Muestra un icono de flecha (o un guion) representando la tendencia de un valor.
 *
 * @param trend La [Trend] actual.
 * @param iconSize Tamaño base para el área del icono. El icono en sí será ligeramente más pequeño.
 */
@Composable
fun TrendArrow(trend: Trend, iconSize: Dp = 28.dp) {
    val icon = when (trend) {
        Trend.UP -> Icons.Filled.ArrowUpward
        Trend.DOWN -> Icons.Filled.ArrowDownward
        else -> null // Para Trend.STABLE o UNKNOWN
    }
    Box(
        modifier = Modifier.size(iconSize), // El tamaño del Box se ajusta al iconSize
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = "Tendencia ${trend.name.lowercase(Locale.getDefault())}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, // Color del icono de tendencia
                modifier = Modifier.size(iconSize * 0.70f) // El icono en sí un poco más pequeño que el Box
            )
        } else {
            // Mostrar un guion si la tendencia es estable o desconocida
            Text(
                "—", // Guion largo o corto
                fontSize = (iconSize.value * 0.60f).sp, // Tamaño del texto relativo al iconSize
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Muestra un círculo de color simple, usado como indicador de estado (luz).
 *
 * @param color El [Color] para la luz de estado.
 * @param size El tamaño del círculo.
 */
@Composable
fun StatusLight(color: Color, size: Dp = 28.dp) { // color ya es Color
    Box(
        modifier = Modifier
            .size(size) // Tamaño total del área.
            .padding(size * 0.2f) // Padding interno para hacer el círculo visible un poco más pequeño.
            .clip(CircleShape) // Recorta a forma de círculo.
            .background(color) // Aplica el color de fondo.
    )
}

/**
 * Composable que muestra una tabla de los registros de parada.
 *
 * @param stops Lista de [StopRecord] a mostrar.
 * @param modifier Modificador para el Composable.
 * @param showPlaceholder Indica si se debe mostrar un texto placeholder si no hay paradas.
 * @param placeholderText Texto a mostrar como placeholder.
 */
@Composable
fun StopsTable(
    stops: List<StopRecord>,
    modifier: Modifier = Modifier,
    showPlaceholder: Boolean = false,
    placeholderText: String = "No hay paradas registradas"
) {
    // Estilos para el encabezado y las celdas de la tabla.
    val tableHeaderStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    val tableCellStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val cellPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp) // Padding para las celdas.

    // Columna principal para la tabla con borde y fondo.
    Column(
        modifier = modifier
            .background(ElementBackgroundColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp)) // Fondo semitransparente.
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(6.dp) // Padding interno general.
    ) {
        // Encabezado de la tabla
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    ElementBackgroundColor, // Fondo sólido para el encabezado
                    RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp) // Solo esquinas superiores redondeadas.
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Celdas del encabezado con pesos para distribuir el ancho.
            Text("Nº", modifier = Modifier
                .weight(0.12f) // Ajustar pesos
                .padding(cellPadding), style = tableHeaderStyle, fontSize = 17.sp, textAlign = TextAlign.Center)
            Text("Tiempo", modifier = Modifier
                .weight(0.30f) // Ajustar pesos
                .padding(cellPadding), style = tableHeaderStyle, fontSize = 17.sp, textAlign = TextAlign.Center)
            Text("SpO2", modifier = Modifier
                .weight(0.20f) // Ajustar pesos
                .padding(cellPadding), style = tableHeaderStyle, fontSize = 17.sp, textAlign = TextAlign.Center)
            Text("FC", modifier = Modifier
                .weight(0.25f) // Ajustar pesos
                .padding(cellPadding), style = tableHeaderStyle, fontSize = 17.sp, textAlign = TextAlign.Center)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)) // Divisor debajo del encabezado.

        // Muestra el placeholder o la lista de paradas.
        if (showPlaceholder || stops.isEmpty()) {
            Box( // Contenedor para el texto placeholder.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp), // Padding vertical amplio.
                contentAlignment = Alignment.Center
            ) {
                Text(
                    // Usar placeholderText
                    text = if (showPlaceholder) placeholderText else "No hay paradas registradas", // Usa el placeholderText proporcionado.
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        } else {
            // LazyColumn para mostrar las filas de la tabla de forma eficiente.
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                // `itemsIndexed` proporciona el índice y el item.
                itemsIndexed(stops) { index, stop ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (index % 2 == 0) Color.Transparent else ElementBackgroundColor.copy(
                                    alpha = 0.2f
                                )
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Celdas de datos, usando los mismos pesos que el encabezado.
                        Text((index + 1).toString(), modifier = Modifier
                            .weight(0.12f)
                            .padding(cellPadding), style = tableCellStyle, fontSize = 17.sp, textAlign = TextAlign.Center)
                        Text(stop.stopTimeFormatted, modifier = Modifier
                            .weight(0.30f)
                            .padding(cellPadding), style = tableCellStyle, fontSize = 17.sp, textAlign = TextAlign.Center)
                        Text("${stop.spo2AtStopTime}", modifier = Modifier
                            .weight(0.20f)
                            .padding(cellPadding), style = tableCellStyle, fontSize = 17.sp, textAlign = TextAlign.Center)
                        Text("${stop.heartRateAtStopTime}", modifier = Modifier
                            .weight(0.25f)
                            .padding(cellPadding), style = tableCellStyle, fontSize = 17.sp, textAlign = TextAlign.Center)
                    }
                    if (index < stops.size - 1) { // Añade un divisor entre filas, excepto después de la última.
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}


// DIÁLOGOS (Reutilizables)
/**
 * Diálogo de confirmación genérico.
 *
 * @param title Título del diálogo.
 * @param text Texto del cuerpo del diálogo.
 * @param onConfirm Acción a ejecutar al pulsar el botón de confirmar.
 * @param onDismiss Acción a ejecutar al descartar el diálogo (botón o fuera del diálogo).
 * @param confirmButtonText Texto para el botón de confirmación.
 * @param dismissButtonText Texto para el botón de descarte/cancelación.
 */
@Composable
fun ConfirmationDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmButtonText: String = "Salir",
    dismissButtonText: String = "Cancelar"
) {
    AlertDialog(
        onDismissRequest = onDismiss, // Se llama si se pulsa fuera o el botón de retroceso.
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp)) },
        text = { Text(text = text, style = MaterialTheme.typography.bodyMedium, fontSize = 17.sp) },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(containerColor = ButtonActionColor) // Usar color de acción.
            ) {
                Text(confirmButtonText, color = Color.White, fontSize = 17.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ButtonActionColor),
                border = BorderStroke(1.dp, ButtonActionColor)
            ) {
                Text(dismissButtonText, fontSize = 17.sp)
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = BackgroundColor, // Color de fondo del diálogo.
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * Diálogo informativo con un solo botón.
 *
 * @param title Título del diálogo.
 * @param text Texto del cuerpo del diálogo.
 * @param onDismiss Acción al descartar/cerrar el diálogo.
 * @param buttonText Texto del botón de cierre.
 */
@Composable
fun InfoDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    buttonText: String = "Entendido"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp)) },
        text = { Text(text = text, style = MaterialTheme.typography.bodyMedium, fontSize = 17.sp) },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ButtonActionColor)
            ) {
                Text(buttonText, color = Color.White, fontSize = 17.sp)
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = BackgroundColor,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * Diálogo que muestra una cuenta atrás con un botón de cancelación.
 *
 * @param title Título del diálogo.
 * @param countdownValue Valor actual de la cuenta atrás (en segundos).
 * @param onCancel Acción a ejecutar si se cancela la cuenta atrás.
 */
@Composable
fun CountdownDialog(
    title: String,
    countdownValue: Int,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = { /* No se puede descartar pulsando fuera */ }) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = BackgroundColor), // Color de fondo.
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp) // Espacio entre elementos.
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$countdownValue",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "La prueba se detendrá automáticamente...",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Text("CANCELAR DETENCIÓN")
                }
            }
        }
    }
}

// COMPONENTE ESTADO BLUETOOTH
/**
 * Botón/Indicador que muestra el estado de la conexión Bluetooth y permite interacciones.
 *
 * @param status [BluetoothIconStatus] actual para determinar el icono y color.
 * @param message Mensaje de estado a mostrar junto al icono.
 * @param isAttemptingReconnect Indica si se está intentando una reconexión (para animaciones).
 * @param onClick Acción a ejecutar cuando se hace clic en el componente.
 * @param modifier Modificador para el componente.
 */
@Composable
fun BluetoothStatusIndicatorButton(
    status: BluetoothIconStatus,
    message: String,
    isAttemptingReconnect: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Color del icono, animado para transiciones suaves.
    val iconColor = status.toActualComposeColor()
    val infiniteTransition = rememberInfiniteTransition(label = "bt_reconnect_glow")

    val isSystemBluetoothActuallyOff = message.contains("active bluetooth", ignoreCase = true)
    val isRedAndCanReconnect = status == BluetoothIconStatus.RED && message.contains("pérdida de conexión")
    val isActuallyClickable = !isSystemBluetoothActuallyOff && (isRedAndCanReconnect || isAttemptingReconnect || status == BluetoothIconStatus.CONNECTING)

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = if (isAttemptingReconnect || status == BluetoothIconStatus.CONNECTING || isRedAndCanReconnect) 0.3f else 0f,
        targetValue = if (isAttemptingReconnect || status == BluetoothIconStatus.CONNECTING || isRedAndCanReconnect) 0.7f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "bt_glow_alpha"
    )

    // Animación de rotación para el estado CONNECTING o cuando isAttemptingReconnect es true
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isAttemptingReconnect || status == BluetoothIconStatus.CONNECTING) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "bt_rotation_angle"
    )

    val animatedIconColor by animateColorAsState(
        targetValue = iconColor,
        animationSpec = tween(300), label = "bt_icon_color_animation"
    )

    // Modificador para la apariencia de "botón" cuando es rojo y reconectable
    val containerModifier = if (isRedAndCanReconnect && !isAttemptingReconnect) {
        modifier
            .clip(RoundedCornerShape(12.dp)) // Bordes más redondeados para el área del botón
            .background(
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f), // Fondo sutil
                RoundedCornerShape(12.dp)
            )
            .border(
                BorderStroke(1.5.dp, MaterialTheme.colorScheme.error), // Borde más pronunciado
                RoundedCornerShape(12.dp)
            )
    } else {
        modifier
    }

    Column(
        modifier = containerModifier
            .clickable(
                enabled = isActuallyClickable,
                onClick = onClick,
                indication = androidx.compose.material.ripple.rememberRipple(bounded = true),
                interactionSource = remember { MutableInteractionSource() }
            )
            .padding(vertical = 8.dp, horizontal = 12.dp), // Padding alrededor del conjunto icono + texto
        horizontalAlignment = Alignment.CenterHorizontally // Centra el icono y el texto dentro de la columna
    ) {
        Box(
            modifier = Modifier
                .size(42.dp) // Tamaño del área del icono
                .clip(CircleShape)
                .background(
                    animatedIconColor.copy(alpha = if (isAttemptingReconnect || status == BluetoothIconStatus.CONNECTING || isRedAndCanReconnect) glowAlpha else if (isSystemBluetoothActuallyOff) 0.5f else 1f)
                ) // Si BT está apagado, atenuar un poco el icono también
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    CircleShape
                ) // Borde sutil
                .padding(6.dp), // Padding interno para el icono en sí
            contentAlignment = Alignment.Center
        ) {
            val iconToShow = when {
                isAttemptingReconnect || status == BluetoothIconStatus.CONNECTING -> Icons.Filled.Autorenew // O Sync
                isRedAndCanReconnect -> Icons.Filled.Refresh // Para indicar "pulsar para reconectar"
                isSystemBluetoothActuallyOff -> Icons.Filled.BluetoothDisabled
                status == BluetoothIconStatus.RED -> Icons.Filled.ErrorOutline // Otro tipo de error
                status == BluetoothIconStatus.YELLOW -> Icons.Filled.WarningAmber
                status == BluetoothIconStatus.GREEN -> Icons.Filled.BluetoothConnected
                else -> Icons.Filled.Bluetooth // Default o GRAY
            }
            Icon(
                imageVector = iconToShow,
                contentDescription = "Estado Bluetooth: $message",
                tint = Color.White,
                modifier = Modifier
                    .fillMaxSize(0.85f)
                    .rotate(if (status == BluetoothIconStatus.CONNECTING || isAttemptingReconnect) rotationAngle else 0f) // Aplica rotación si es necesario.
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isRedAndCanReconnect && !isAttemptingReconnect) MaterialTheme.colorScheme.error
                    else if (isSystemBluetoothActuallyOff) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, // Permite hasta dos líneas para el mensaje.
            overflow = TextOverflow.Ellipsis, // Añade "..." si el texto es muy largo.
            textAlign = TextAlign.Center,
            fontSize = 15.sp
        )
    }
}
