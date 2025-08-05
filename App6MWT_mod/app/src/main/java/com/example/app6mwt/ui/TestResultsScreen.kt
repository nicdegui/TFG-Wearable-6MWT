package com.example.app6mwt.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.app6mwt.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.DialogProperties
import kotlin.collections.forEachIndexed

enum class ChartTypeToShow {
    SPO2,
    HEART_RATE
}

@Composable
fun StatusColor.toComposeColor(): Color {
    return when (this) {
        StatusColor.NORMAL -> Color(0xFF4CAF50)
        StatusColor.WARNING -> Color(0xFFFFC107)
        StatusColor.CRITICAL -> MaterialTheme.colorScheme.error
        StatusColor.UNKNOWN -> Color.Gray
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestResultsScreen(
    viewModel: TestResultsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToPatientManagement: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spo2RangeHint by viewModel.spo2RangeHint
    val hrRangeHint by viewModel.hrRangeHint
    val bpRangeHint by viewModel.bpRangeHint
    val rrRangeHint by viewModel.rrRangeHint
    val borgRangeHint by viewModel.borgRangeHint

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var showEnlargedChartDialogFor by remember { mutableStateOf<ChartTypeToShow?>(null) }
    // --- Estados locales para controlar los diálogos de confirmación ---
    var showSimpleNavigateBackDialog by remember { mutableStateOf(false) }
    var showSimpleFinalizeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadInitialData()
        viewModel.observeRecoveryData()
        viewModel.observeLiveSensorDataAndBluetoothStatus()
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { messageText ->
            snackbarHostState.showSnackbar(
                message = messageText,
                duration = SnackbarDuration.Short
            )
            viewModel.clearUserMessageAfterDelay()
        }
    }

    LaunchedEffect(uiState.shouldNavigateToHome) {
        if (uiState.shouldNavigateToHome) {
            onNavigateToPatientManagement()
            viewModel.onNavigationHandled()
        }
    }

    BackHandler {
        showSimpleNavigateBackDialog = true
    }

    if (showSimpleNavigateBackDialog) {
        ConfirmationDialog(
            title = "Confirmar Salida",
            text = "¿Está seguro de que desea volver a la pantalla anterior? Los cambios no guardados se perderán.", // Mensaje genérico
            onConfirm = {
                showSimpleNavigateBackDialog = false
                onNavigateBack() // Navega hacia atrás
            },
            onDismiss = { showSimpleNavigateBackDialog = false },
            confirmButtonText = "Salir",
            dismissButtonText = "Cancelar"
        )
    }

    if (uiState.showObservationsDialog) {
        ObservationsInputDialog(
            initialText = uiState.observations,
            onConfirm = { newText ->
                viewModel.onObservationsChange(newText)
                viewModel.onShowObservationsDialog(false)
            },
            onDismiss = { viewModel.onShowObservationsDialog(false) }
        )
    }

    uiState.pdfGeneratedUri?.let { uri ->
        var fileNameToDisplay = "Informe_6MWT.pdf"

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        val nameFromUri = cursor.getString(displayNameIndex)
                        if (!nameFromUri.isNullOrBlank()) {
                            fileNameToDisplay = nameFromUri
                        }
                    } else {
                        Log.w("PdfDialog", "Columna DISPLAY_NAME no encontrada en la URI: $uri")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PdfDialog", "Error al obtener nombre de archivo de la URI: $uri", e)
        }
        PdfGeneratedDialog(
            uri = uri,
            fileName = fileNameToDisplay,
            onDismiss = { viewModel.clearPdfUri() },
            onOpen = {
                try {
                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    if (openIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(openIntent)
                    } else {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("No se puede abrir el PDF. No hay aplicación disponible.")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PdfDialog", "Error al intentar abrir el PDF: $uri", e)
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Error al abrir el PDF.")
                    }
                }
                viewModel.clearPdfUri()
            },
            onShare = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_STREAM, uri)
                    type = "application/pdf"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Compartir PDF vía..."))
                viewModel.clearPdfUri()
            }
        )
    }

    if (uiState.isGeneratingPdf) {
        LoadingDialog("Generando PDF...")
    }

    // --- NUEVO: Diálogo para la Gráfica Ampliada ---
    showEnlargedChartDialogFor?.let { chartType ->
        EnlargedChartDialog(
            chartType = chartType,
            uiState = uiState, // Pasamos el uiState para acceder a los datos
            onDismiss = { showEnlargedChartDialogFor = null }
        )
    }

    if (showSimpleFinalizeDialog) {
        ConfirmationDialog(
            title = "Confirmar Finalización",
            text = "¿Está seguro de que desea finalizar y volver a la gestión de pacientes?",
            onConfirm = {
                showSimpleFinalizeDialog = false
                viewModel.requestFinalizeTest()
            },
            onDismiss = { showSimpleFinalizeDialog = false },
            confirmButtonText = "Finalizar",
            dismissButtonText = "Cancelar"
        )
    }

    App6MWTTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                val isTestCompleteAndValid = uiState.arePostTestValuesCompleteAndValid
                val isTestSaved = uiState.isTestSaved
                val hasUnsavedChanges = uiState.hasUnsavedChanges

                val isSaveEnabled = isTestCompleteAndValid && (!isTestSaved || hasUnsavedChanges)
                val isFinalizeEnabled = isTestCompleteAndValid && isTestSaved && !hasUnsavedChanges
                val isPrintEnabled = isTestCompleteAndValid && isTestSaved && !hasUnsavedChanges

                TestResultsTopAppBar(
                    patientName = uiState.patientFullName.uppercase(),
                    patientId = uiState.patientId?.toString() ?: "---",
                    onNavigateBackClicked = { showSimpleNavigateBackDialog = true }, // Muestra su diálogo
                    onSaveClicked = { viewModel.onSaveTestClicked() },
                    isSaveEnabled = isSaveEnabled,
                    onFinalizeClicked = { showSimpleFinalizeDialog = true }, // Muestra su diálogo
                    isFinalizeEnabled = isFinalizeEnabled,
                    onPrintClicked = { viewModel.onGeneratePdfClicked() },
                    isPrintEnabled = isPrintEnabled
                )
            }
        ) { paddingValues ->
            TestResultsContent(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
                uiState = uiState,
                spo2RangeHint = spo2RangeHint,
                hrRangeHint = hrRangeHint,
                bpRangeHint = bpRangeHint,
                rrRangeHint = rrRangeHint,
                borgRangeHint = borgRangeHint,
                onPostTestValueChange = { field, value -> viewModel.onPostTestValueChange(field, value) },
                onEditObservationsClicked = { viewModel.onShowObservationsDialog(true) },
                onBluetoothIconClicked = { viewModel.onBluetoothIconClicked() },
                onChartClicked = { chartType ->
                    showEnlargedChartDialogFor = chartType
                }
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestResultsTopAppBar(
    patientName: String,
    patientId: String,
    onNavigateBackClicked: () -> Unit,
    onSaveClicked: () -> Unit,
    isSaveEnabled: Boolean,
    onFinalizeClicked: () -> Unit,
    isFinalizeEnabled: Boolean,
    onPrintClicked: () -> Unit,
    isPrintEnabled: Boolean
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "RESULTADOS PRUEBA - $patientName".uppercase(),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    color = TextOnSecondary,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 1. IconButton para Navegar Hacia Atrás (Flecha Izquierda)
                IconButton(onClick = onNavigateBackClicked) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Retroceder",
                        tint = TextOnSecondary
                    )
                }
                // 2. IconButton para FINALIZAR (Flecha Derecha a Home)
                IconButton(
                    onClick = onFinalizeClicked,
                    enabled = isFinalizeEnabled,
                    modifier = Modifier
                        .padding(start = 0.dp, end = 8.dp) // Ajusta el padding si es necesario
                        .size(40.dp) // Tamaño del icono similar al de ejecución
                        .background(
                            color = if (isFinalizeEnabled) SuccessGreenColor else Color.Gray.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward, // O Icons.Filled.Save
                        contentDescription = "Finalizar y volver a gestión",
                        tint = Color.White
                    )
                }
                // 3. IconButton para GUARDAR
                IconButton( // Guardar
                    onClick = onSaveClicked,
                    enabled = isSaveEnabled,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(40.dp)
                        .background(
                            color = if (isSaveEnabled) SuccessGreenColor else (if (!isSaveEnabled && patientId != "---") SuccessGreenColor.copy(alpha = 0.4f) else Color.Gray.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = "Guardar prueba en base de datos",
                        tint = Color.White
                    )
                }
            }
        },
        actions = {
            // Botón de Imprimir (Generar PDF)
            IconButton(
                onClick = onPrintClicked,
                enabled = isPrintEnabled
            ) {
                Icon(
                    imageVector = Icons.Filled.Print,
                    contentDescription = "Generar PDF"
                )
            }
            // ID del Paciente
            Text(
                text = "ID: $patientId",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(end = 16.dp),
                maxLines = 1
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary, titleContentColor = TextOnSecondary,
            navigationIconContentColor = TextOnSecondary, actionIconContentColor = TextOnSecondary
        )
    )
}

@Composable
fun TestResultsContent(
    modifier: Modifier = Modifier,
    uiState: TestResultsUiState,
    spo2RangeHint: String,
    hrRangeHint: String,
    bpRangeHint: String,
    rrRangeHint: String,
    borgRangeHint: String,
    onPostTestValueChange: (PostTestField, String) -> Unit,
    onEditObservationsClicked: () -> Unit,
    onBluetoothIconClicked: () -> Unit,
    onChartClicked: (ChartTypeToShow) -> Unit
) {
    Row(
        modifier = modifier
            .padding(8.dp)
            .fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // --- COLUMNA IZQUIERDA (55%) ---
        Box(modifier = Modifier
            .weight(0.55f)
            .fillMaxHeight()) {
            LeftColumnResults(
                uiState = uiState,
                onChartClicked = onChartClicked
            )
        }

        VerticalDivider(modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant))

        // --- COLUMNA CENTRAL (15%) ---
        Box(modifier = Modifier
            .weight(0.15f)
            .fillMaxHeight()) {
            CentralColumnResults(
                uiState = uiState,
                onEditObservationsClicked = onEditObservationsClicked,
                onBluetoothIconClicked = onBluetoothIconClicked
            )
        }

        VerticalDivider(modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant))

        // --- COLUMNA DERECHA (30%) ---
        Box(modifier = Modifier
            .weight(0.30f)
            .fillMaxHeight()) {
            RightColumnResults(
                uiState = uiState,
                spo2RangeHint = spo2RangeHint,
                hrRangeHint = hrRangeHint,
                bpRangeHint = bpRangeHint,
                rrRangeHint = rrRangeHint,
                borgRangeHint = borgRangeHint,
                onPostTestValueChange = onPostTestValueChange)
        }
    }
}

@Composable
fun LeftColumnResults(
    uiState: TestResultsUiState,
    modifier: Modifier = Modifier,
    onChartClicked: (ChartTypeToShow) -> Unit
) {
    val chartTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val chartGridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f).toArgb()
    val chartLineColorSpo2 = MaterialTheme.colorScheme.tertiary.toArgb()
    val chartLineColorHr = MaterialTheme.colorScheme.secondary.toArgb()

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Mitad Superior: Gráficas y Tabla de Registro por Minuto
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Gráficas (aprox 60% del ancho de esta Row)
            Column(modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight()) {
                SectionCard(
                    title = "SpO2 (%) - Resumen",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable { onChartClicked(ChartTypeToShow.SPO2) },
                    titleHorizontalArrangement = Arrangement.Center, // Para centrar la Row del título
                    titleTextAlign = TextAlign.Center,              // Para centrar el Text interno
                    titlePadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), // Reduce padding vertical del título
                    contentPadding = PaddingValues(all = 3.dp),      // Padding para la gráfica
                    isActionsEmpty = true,                            // No hay acciones
                    actions = {}                                      // Slot de acciones vacío
                ) {
                    val spo2DataPoints = uiState.summaryData?.spo2DataPoints ?: emptyList()

                    if (spo2DataPoints.isNotEmpty()) {
                        LineChartComposable(
                            modifier = Modifier.fillMaxSize(),
                            dataPoints = spo2DataPoints,
                            yAxisMin = 85f,
                            yAxisMax = 100f,
                            yAxisLabelCount = 5,
                            xAxisLabelCount = 7,
                            lineColor = chartLineColorSpo2,
                            textColor = chartTextColor,
                            gridColor = chartGridColor
                        )
                    } else {
                        ChartPlaceholder(modifier = Modifier, "No hay datos de SpO2 para la gráfica.")
                    }
                }
                Spacer(Modifier.height(8.dp))
                SectionCard(
                    title = "FC (lpm) - Resumen",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable { onChartClicked(ChartTypeToShow.HEART_RATE) },
                    titleHorizontalArrangement = Arrangement.Center,
                    titleTextAlign = TextAlign.Center,
                    titlePadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    contentPadding = PaddingValues(all = 3.dp),
                    isActionsEmpty = true,
                    actions = {}
                ) {
                    val hrDataPoints = uiState.summaryData?.heartRateDataPoints ?: emptyList()

                    if (hrDataPoints.isNotEmpty()) {
                        LineChartComposable(
                            modifier = Modifier.fillMaxSize(),
                            dataPoints = hrDataPoints,
                            yAxisMin = 60f,
                            yAxisMax = 160f,
                            yAxisLabelCount = 8,
                            xAxisLabelCount = 7,
                            lineColor = chartLineColorHr,
                            textColor = chartTextColor,
                            gridColor = chartGridColor
                        )
                    } else {
                        ChartPlaceholder(modifier = Modifier, "No hay datos de FC para la gráfica.")
                    }
                }
            }

            // Tabla de Registro por Minuto (aprox 40% del ancho de esta Row)
            SectionCard(
                title = "Registro por minuto",
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight(),
                titleHorizontalArrangement = Arrangement.Center,
                titleTextAlign = TextAlign.Center,
                isActionsEmpty = true
            ) {
                MinuteReadingsTable(
                    minuteSnapshots = uiState.minuteSnapshotsForTable,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Línea Horizontal Divisoria
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // Mitad Inferior: Distancia, Paradas, Críticos
        Column(
            modifier = Modifier
                .weight(1f) // Ocupa la otra mitad de la altura
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp) // Espacio entre fila de distancias y fila de tablas
        ) {
            // **FILA SUPERIOR: DISTANCIAS HORIZONTALES**
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp), // Padding para esta fila de distancias
                horizontalArrangement = Arrangement.SpaceAround, // O SpaceBetween, o usa weights en los items
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDistanceItem(
                    label = "Dist. Total",
                    value = "${String.format("%.2f", uiState.totalDistanceMeters)} m",
                    // modifier = Modifier.weight(1f) // Opcional: para distribuir espacio si usas SpaceAround y quieres control
                )
                HorizontalDistanceItem(
                    label = "Dist. Teórica",
                    value = "${String.format("%.2f", uiState.theoreticalDistanceMeters)} m",
                    // modifier = Modifier.weight(1f)
                )
                HorizontalDistanceItem(
                    label = "% Teórico",
                    value = "${String.format("%.2f", uiState.percentageOfTheoretical)}%",
                    // modifier = Modifier.weight(1f)
                )
            }

            // **FILA INFERIOR: TABLAS (PARADAS Y CRÍTICOS)**
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f), // Para que esta fila ocupe el resto del espacio vertical disponible en la Column
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Tabla de Paradas (Mitad Izquierda)
                SectionCard(
                    title = "Registro de paradas",
                    modifier = Modifier
                        .weight(0.5f) // Ocupa la mitad del ancho de esta Row
                        .fillMaxHeight(), // Ocupa toda la altura disponible en esta Row
                    titleHorizontalArrangement = Arrangement.Center, // Centra el Row del título
                    titleTextAlign = TextAlign.Center,             // Centra el texto del título
                    isActionsEmpty = true,                         // Asumiendo que no tienes 'actions' aquí
                    showTitleDivider = true                        // O false si no quieres el divisor bajo el título
                ) {
                    StopsTable( // Ya tienes este Composable
                        stops = uiState.stopRecordsForTable,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        enableInternalScroll = true
                    )
                }

                // Tabla de Parámetros Críticos (Mitad Derecha)
                SectionCard(
                    title = "Parámetros críticos",
                    modifier = Modifier
                        .weight(0.5f) // Ocupa la otra mitad del ancho
                        .fillMaxHeight(), // Ocupa toda la altura disponible
                    titleHorizontalArrangement = Arrangement.Center, // Centra el Row del título
                    titleTextAlign = TextAlign.Center,             // Centra el texto del título
                    isActionsEmpty = true,                         // Asumiendo que no tienes 'actions' aquí
                    showTitleDivider = true                        // O false si no quieres el divisor bajo el título
                ) {
                    CriticalValuesTable( // Ya tienes este Composable
                        summaryData = uiState.summaryData,
                        modifier = Modifier
                            .fillMaxSize() // La tabla llena el SectionCard
                            .padding(horizontal = 8.dp, vertical = 4.dp) // Ajusta padding si es necesario
                    )
                }
            }
        }
    }
}

@Composable
fun CentralColumnResults(
    uiState: TestResultsUiState,
    onEditObservationsClicked: () -> Unit,
    onBluetoothIconClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(50.dp)
    ) {
        // Cuadro de Observaciones (ocupa la mitad superior)
        SectionCard(
            title = "Observaciones",
            modifier = Modifier
                .height(305.dp)
                .fillMaxWidth(),
            titleHorizontalArrangement = Arrangement.Center,
            titleTextAlign = TextAlign.Center,
            actions = {
                TextButton(onClick = onEditObservationsClicked) { Text("Editar") }
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize() // El Box llena el SectionCard
                    .padding(12.dp)
                    .clickable(
                        enabled = true, // Siempre clickable para abrir el editor
                        onClick = onEditObservationsClicked
                    ),
                // contentAlignment del Box centrará el Text si es más pequeño (como el placeholder)
                contentAlignment = if (uiState.observations.isBlank()) Alignment.Center else Alignment.TopStart
            ) {
                val observationsText = uiState.observations
                val isPlaceholder = observationsText.isBlank()

                Text(
                    text = if (isPlaceholder) "Sin observaciones." else observationsText,
                    style = MaterialTheme.typography.bodyMedium,
                    // El placeholder se centra, el texto real se alinea al inicio
                    textAlign = if (isPlaceholder) TextAlign.Center else TextAlign.Start,
                    // Permitir múltiples líneas para observaciones reales, una para el placeholder
                    maxLines = if (isPlaceholder) 1 else 15, // Ajusta maxLines para texto real
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        // El scroll solo se aplica si NO es el placeholder y hay texto real
                        .then(if (!isPlaceholder) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        // Para que el TextAlign.Center del placeholder funcione bien si el Box es ancho:
                        .then(if (isPlaceholder) Modifier.fillMaxWidth() else Modifier)
                        .then(if (!isPlaceholder) Modifier.fillMaxSize() else Modifier)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(0.50f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VitalSignDisplay(
                label = "SpO2 Actual",
                value = uiState.currentSpo2?.toString() ?: "--",
                unit = "%",
                trend = uiState.spo2Trend,
                alarmStatus = uiState.spo2AlarmStatus,
                modifier = Modifier.fillMaxWidth(0.95f)
            )

            VitalSignDisplay(
                label = "FC Actual",
                value = uiState.currentHeartRate?.toString() ?: "--",
                unit = "lpm",
                trend = uiState.heartRateTrend,
                alarmStatus = uiState.heartRateAlarmStatus,
                modifier = Modifier.fillMaxWidth(0.95f)
            )

            BluetoothStatusIndicatorButton(
                status = uiState.bluetoothVisualStatus,
                messageForText = uiState.bluetoothStatusMessage,
                onClick = onBluetoothIconClicked,
            )
        }
    }
}

@Composable
fun RightColumnResults(
    uiState: TestResultsUiState,
    spo2RangeHint: String,
    hrRangeHint: String,
    bpRangeHint: String,
    rrRangeHint: String,
    borgRangeHint: String,
    onPostTestValueChange: (PostTestField, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        SectionCard(
            title = "Registro Completo de la Prueba",
            modifier = Modifier
                .height(485.dp)
                .fillMaxWidth(),
            titleHorizontalArrangement = Arrangement.Center, // Centra el contenido del título horizontalmente
            titleTextAlign = TextAlign.Center,             // Asegura que el texto del título esté centrado
            titlePadding = PaddingValues(vertical = 6.dp, horizontal = 16.dp), // Ajusta el padding del título según necesites
            isActionsEmpty = true,                         // Indicar que no hay 'actions' en esta sección
            contentPadding = PaddingValues(0.dp) // El padding lo maneja la tabla o sus contenedores internos
        ) {
            Column(
                modifier = Modifier.fillMaxSize() // La tabla usa el espacio que le da SectionCard

            ) {
                FullTestRecordTable(
                    uiState = uiState,
                    spo2RangeHint = spo2RangeHint,
                    hrRangeHint = hrRangeHint,
                    bpRangeHint = bpRangeHint,
                    rrRangeHint = rrRangeHint,
                    borgRangeHint = borgRangeHint,
                    onPostTestValueChange = onPostTestValueChange,
                    modifier = Modifier.fillMaxWidth() // La tabla ocupa el ancho disponible
                )
            }
        }

        Text(
            text = uiState.validationMessage ?: "",
            color = if (uiState.arePostTestValuesCompleteAndValid) SuccessGreenColor else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 17.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun HorizontalDistanceItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), // Estilo para la etiqueta
    valueStyle: TextStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp) // Estilo para el valor
) {
    Row(
        modifier = modifier.padding(horizontal = 4.dp), // Espacio entre los items de distancia
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = labelStyle,
            modifier = Modifier.padding(end = 4.dp) // Espacio entre etiqueta y valor
        )
        Box(
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 10.dp, vertical = 4.dp), // Padding dentro del recuadro del valor
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = value,
                style = valueStyle,
                maxLines = 1
            )
        }
    }
}

@Composable
fun FullTestRecordTable(
    uiState: TestResultsUiState,
    spo2RangeHint: String,
    hrRangeHint: String,
    bpRangeHint: String,
    rrRangeHint: String,
    borgRangeHint: String,
    onPostTestValueChange: (PostTestField, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val tablePadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
    // Aumentar el padding vertical de las celdas para que los TextFields no se vean tan apretados
    val cellPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    // Padding específico para la cabecera, con menos padding vertical
    val headerCellPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    val headerStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    val rowLabelStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
    val valueStyle = MaterialTheme.typography.bodyMedium // Para el texto del valor
    // Estilo para el placeholder DENTRO del TextField, más sutil
    val placeholderStyleInTextField = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))


    Column(modifier = modifier.padding(tablePadding)) {
        // --- Cabecera de la Tabla (AHORA CON 3 COLUMNAS) ---
        Row(
            Modifier
                .fillMaxWidth()
                .background(ElementBackgroundColor)
                .padding(headerCellPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 2. Ajustar alineación y pesos de los títulos de columna
            // Columna 1 Título: Parámetros
            Text(
                text = "Parámetros",
                style = headerStyle,
                textAlign = TextAlign.Start, // Alineado al inicio
                modifier = Modifier.weight(0.3f) // Coincide con la columna de datos
            )
            // Columna 2 Título: Basal
            Text(
                text = "Basal",
                style = headerStyle,
                textAlign = TextAlign.Center, // Centrado
                modifier = Modifier.weight(0.25f) // Coincide con la columna de datos
            )
            // Columna 3 Título: Post-Prueba
            Text(
                text = "Postprueba",
                style = headerStyle,
                textAlign = TextAlign.Center, // Centrado (o End si se prefiere para que esté al final del área)
                modifier = Modifier.weight(0.45f) // Coincide con la columna de datos
            )
        }
        FormDividerSmall(Modifier.padding(horizontal = 4.dp)) // Divisor

        // --- Fila SpO2 ---
        FullTestRecordTableRow(
            parameterLabel = "SpO2 (%)",
            basalValue = uiState.basalSpo2?.toString() ?: "--",
            postTestValueDisplay = uiState.recoverySpo2?.toString(), // No es input, es display
            isPostTestEditable = false,
            postTestPlaceholder = if (uiState.recoverySpo2 != null) "" else spo2RangeHint, // Placeholder para el Text (si es null)
            rowLabelStyle = rowLabelStyle,
            valueStyle = valueStyle,
            placeholderStyle = placeholderStyleInTextField, // Aunque no es TextField, se usa para consistencia
            cellPadding = cellPadding
        )
        FormDividerSmall(Modifier.padding(horizontal = 4.dp))

        // --- Fila FC ---
        FullTestRecordTableRow(
            parameterLabel = "FC (lpm)",
            basalValue = uiState.basalHeartRate?.toString() ?: "--",
            postTestValueDisplay = uiState.recoveryHeartRate?.toString(),
            isPostTestEditable = false,
            postTestPlaceholder = if (uiState.recoveryHeartRate != null) "" else hrRangeHint,
            rowLabelStyle = rowLabelStyle,
            valueStyle = valueStyle,
            placeholderStyle = placeholderStyleInTextField,
            cellPadding = cellPadding
        )
        FormDividerSmall(Modifier.padding(horizontal = 4.dp))

        // --- Fila TA ---
        FullTestRecordTableRow(
            parameterLabel = "TA (mmHg)",
            basalValue = formatBloodPressure(uiState.basalBloodPressureSystolic, uiState.basalBloodPressureDiastolic),
            postTestValueInput = uiState.postTestBloodPressureInput,
            onPostTestValueInputChange = { onPostTestValueChange(PostTestField.BLOOD_PRESSURE, it) },
            isPostTestEditable = true,
            isPostTestValid = uiState.isPostTestBloodPressureValid,
            postTestKeyboardType = KeyboardType.Text,
            postTestImeAction = ImeAction.Done,
            postTestPlaceholder = bpRangeHint, // Placeholder DENTRO del TextField
            onDone = { focusManager.clearFocus() },
            rowLabelStyle = rowLabelStyle,
            valueStyle = valueStyle,
            placeholderStyle = placeholderStyleInTextField,
            cellPadding = cellPadding
            // infoText YA NO SE PASA
        )
        FormDividerSmall(Modifier.padding(horizontal = 4.dp))

        // --- Fila FR ---
        FullTestRecordTableRow(
            parameterLabel = "FR (rpm)",
            basalValue = uiState.basalRespiratoryRate?.toString() ?: "--",
            postTestValueInput = uiState.postTestRespiratoryRateInput,
            onPostTestValueInputChange = { onPostTestValueChange(PostTestField.RESPIRATORY_RATE, it) },
            isPostTestEditable = true,
            isPostTestValid = uiState.isPostTestRespiratoryRateValid,
            postTestKeyboardType = KeyboardType.Number,
            postTestImeAction = ImeAction.Done,
            postTestPlaceholder = rrRangeHint, // Placeholder DENTRO del TextField
            onDone = { focusManager.clearFocus() },
            rowLabelStyle = rowLabelStyle,
            valueStyle = valueStyle,
            placeholderStyle = placeholderStyleInTextField,
            cellPadding = cellPadding
        )
        FormDividerSmall(Modifier.padding(horizontal = 4.dp))

        // --- Fila Disnea ---
        FullTestRecordTableRow(
            parameterLabel = "Disnea (Borg)",
            basalValue = uiState.basalDyspneaBorg?.toString() ?: "--",
            postTestValueInput = uiState.postTestDyspneaBorgInput,
            onPostTestValueInputChange = { onPostTestValueChange(PostTestField.DYSPNEA_BORG, it) },
            isPostTestEditable = true,
            isPostTestValid = uiState.isPostTestDyspneaBorgValid,
            postTestKeyboardType = KeyboardType.Number,
            postTestImeAction = ImeAction.Done,
            postTestPlaceholder = borgRangeHint, // Placeholder DENTRO del TextField
            onDone = { focusManager.clearFocus() },
            rowLabelStyle = rowLabelStyle,
            valueStyle = valueStyle,
            placeholderStyle = placeholderStyleInTextField,
            cellPadding = cellPadding
        )
        FormDividerSmall(Modifier.padding(horizontal = 4.dp))

        // --- Fila Dolor MII ---
        FullTestRecordTableRow(
            parameterLabel = "Dolor MII (Borg)",
            basalValue = uiState.basalLegPainBorg?.toString() ?: "--",
            postTestValueInput = uiState.postTestLegPainBorgInput,
            onPostTestValueInputChange = { onPostTestValueChange(PostTestField.LEG_PAIN_BORG, it) },
            isPostTestEditable = true,
            isPostTestValid = uiState.isPostTestLegPainBorgValid,
            postTestKeyboardType = KeyboardType.Number,
            postTestImeAction = ImeAction.Done,
            postTestPlaceholder = borgRangeHint, // Placeholder DENTRO del TextField
            onDone = { focusManager.clearFocus() },
            rowLabelStyle = rowLabelStyle,
            valueStyle = valueStyle,
            placeholderStyle = placeholderStyleInTextField,
            cellPadding = cellPadding
        )
    }
}

@Composable
fun FullTestRecordTableRow(
    parameterLabel: String,
    basalValue: String,
    postTestValueDisplay: String? = null, // Para SpO2, FC post (no editable)
    postTestValueInput: String? = null, // Para campos editables
    onPostTestValueInputChange: ((String) -> Unit)? = null,
    isPostTestEditable: Boolean,
    isPostTestValid: Boolean = true,
    postTestKeyboardType: KeyboardType = KeyboardType.Text,
    postTestImeAction: ImeAction = ImeAction.Default,
    postTestPlaceholder: String, // Este se usará DENTRO del TextField
    onDone: (() -> Unit)? = null,
    rowLabelStyle: TextStyle,
    valueStyle: TextStyle,
    placeholderStyle: TextStyle, // Estilo para el placeholder DENTRO del TextField
    cellPadding: PaddingValues // Padding para cada celda
) {
    val focusManager = LocalFocusManager.current

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp), // Espacio entre filas
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Columna 1: Parámetro (Peso ajustado)
        Box(modifier = Modifier
            .weight(0.3f)
            .padding(cellPadding)) { // Más peso a la etiqueta
            Text(text = parameterLabel, style = rowLabelStyle, textAlign = TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        // Columna 2: Valor Basal (Peso ajustado)
        Box(modifier = Modifier
            .weight(0.25f)
            .padding(cellPadding), contentAlignment = Alignment.Center) { // Un poco más de peso
            Text(text = basalValue, style = valueStyle, textAlign = TextAlign.Center, maxLines = 1)
        }

        // Columna 3: Valor/Input Post-Prueba (Peso ajustado)
        Box(modifier = Modifier
            .weight(0.45f)
            .padding(cellPadding), contentAlignment = Alignment.Center) { // Más peso para el input
            if (isPostTestEditable) {
                OutlinedTextField(
                    value = postTestValueInput ?: "",
                    onValueChange = { onPostTestValueInputChange?.invoke(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 50.dp),
                    singleLine = !(parameterLabel == "TA (mmHg)"),
                    maxLines = if (parameterLabel == "TA (mmHg)") 2 else 1,

                    isError = !isPostTestValid,
                    textStyle = valueStyle.copy(textAlign = TextAlign.Center), // Texto del input centrado
                    placeholder = {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = postTestPlaceholder,
                                style = placeholderStyle.copy(textAlign = TextAlign.Center),
                                maxLines = if (parameterLabel == "TA (mmHg)") 2 else 1
                            )
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (!isPostTestValid) MaterialTheme.colorScheme.error else DarkerBlueHighlight,
                        unfocusedBorderColor = if (!isPostTestValid) MaterialTheme.colorScheme.error.copy(alpha = 0.7f) else LightBluePrimary.copy(alpha = 0.7f),
                        cursorColor = DarkerBlueHighlight,
                        errorCursorColor = MaterialTheme.colorScheme.error,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        errorContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f),
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = postTestKeyboardType, imeAction = postTestImeAction),
                    keyboardActions = KeyboardActions(onDone = { onDone?.invoke() ?: focusManager.clearFocus() })
                )
            } else {
                // Para SpO2 y FC que no son editables
                Text(
                    text = postTestValueDisplay ?: postTestPlaceholder, // Mostrar "Esperando..." si es null
                    style = valueStyle.copy(
                        color = if (postTestValueDisplay == null) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else valueStyle.color,
                        textAlign = TextAlign.Center // Texto centrado
                    )
                )
            }
        }
    }
}

// Función helper para formatear la TA basal (puedes ponerla donde quieras, ej. al final del archivo)
fun formatBloodPressure(systolic: Int?, diastolic: Int?): String {
    return if (systolic != null && diastolic != null) {
        "$systolic/$diastolic"
    } else if (systolic != null) {
        "$systolic/--"
    } else if (diastolic != null) {
        "--/$diastolic"
    } else {
        "--/--"
    }
}

@Composable
fun MinuteReadingsTable(
    minuteSnapshots: List<MinuteDataSnapshot>,
    modifier: Modifier = Modifier
) {
    val headerStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
    val cellStyle = MaterialTheme.typography.bodyMedium
    val cellPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)

    val timeColWeight = 0.30f
    val spo2ColWeight = 0.20f
    val hrColWeight = 0.25f
    val metersColWeight = 0.25f

    val snapshotsMap = minuteSnapshots.associateBy { it.minuteMark }
    val displayMinutes = (1..6).toList()
    val hasAnyDataForDisplayMinutes = displayMinutes.any { snapshotsMap.containsKey(it) }

    if (!hasAnyDataForDisplayMinutes && minuteSnapshots.all { it.minuteMark !in 1..6 }) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No hay datos de registro por minuto.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    Column(modifier = modifier) {
        // --- CABECERA ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(timeColWeight), contentAlignment = Alignment.Center) {
                TableCellSimple(text = "Tiempo", style = headerStyle, padding = cellPadding, textAlign = TextAlign.Center)
            }
            Box(modifier = Modifier.weight(spo2ColWeight), contentAlignment = Alignment.Center) {
                TableCellSimple(text = "SpO2", style = headerStyle, padding = cellPadding, textAlign = TextAlign.Center)
            }
            Box(modifier = Modifier.weight(hrColWeight), contentAlignment = Alignment.Center) {
                TableCellSimple(text = "FC", style = headerStyle, padding = cellPadding, textAlign = TextAlign.Center)
            }
            Box(modifier = Modifier.weight(metersColWeight), contentAlignment = Alignment.Center) {
                TableCellSimple(text = "Metros", style = headerStyle, padding = cellPadding, textAlign = TextAlign.Center)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        Column {
            displayMinutes.forEachIndexed { index, minute ->
                val snapshot = snapshotsMap[minute]
                val minuteFormatted = String.format(Locale.getDefault(), "%02d:00", minute)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(timeColWeight), contentAlignment = Alignment.Center) {
                        TableCellSimple(text = minuteFormatted, style = cellStyle, padding = cellPadding, textAlign = TextAlign.Center)
                    }
                    Box(modifier = Modifier.weight(spo2ColWeight), contentAlignment = Alignment.Center) {
                        TableCellSimple(text = snapshot?.minSpo2Overall?.toString() ?: "--", style = cellStyle, padding = cellPadding, textAlign = TextAlign.Center)
                    }
                    Box(modifier = Modifier.weight(hrColWeight), contentAlignment = Alignment.Center) {
                        TableCellSimple(text = snapshot?.maxHrOverall?.toString() ?: "--", style = cellStyle, padding = cellPadding, textAlign = TextAlign.Center)
                    }
                    Box(modifier = Modifier.weight(metersColWeight), contentAlignment = Alignment.Center) {
                        TableCellSimple(text = snapshot?.distanceAtMinuteEnd?.let { String.format(Locale.US, "%.2f", it) } ?: "--", style = cellStyle, padding = cellPadding, textAlign = TextAlign.Center)
                    }
                }
                if (index < displayMinutes.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), thickness = 0.8.dp)
                }
            }
        }
    }
}


@Composable
fun StopsTable(
    stops: List<StopRecord>,
    modifier: Modifier = Modifier,
    enableInternalScroll: Boolean = true
) {
    val headerStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
    val cellStyle = MaterialTheme.typography.bodyLarge

    // Padding para las celdas
    val cellPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp) // Ajusta según sea necesario

    // Pesos para las columnas (asegúrate de que sumen 1.0f)
    val indexColWeight = 0.12f
    val timeColWeight = 0.25f
    val spo2ColWeight = 0.20f
    val hrColWeight = 0.20f
    val metersColWeight = 0.23f

    if (stops.isEmpty()) {
        Box(modifier
            .fillMaxSize()
            .padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                "No se registraron paradas durante la prueba.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    Column(modifier = modifier) {
        // --- CABECERA ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(indexColWeight),
                contentAlignment = Alignment.Center
            ) {
                TableCellSimple(text = "Nº", style = headerStyle, padding = cellPadding, textAlign = TextAlign.Center)
            }
            Box(
                modifier = Modifier.weight(timeColWeight),
                contentAlignment = Alignment.Center
            ) {
                TableCellSimple(text = "Tiempo", style = headerStyle, padding = cellPadding, textAlign = TextAlign.Center)
            }
            Box(
                modifier = Modifier.weight(spo2ColWeight),
                contentAlignment = Alignment.Center
            ) {
                TableCellSimple(text = "SpO2", style = headerStyle, padding = cellPadding, textAlign = TextAlign.Center)
            }
            Box(
                modifier = Modifier.weight(hrColWeight),
                contentAlignment = Alignment.Center
            ) {
                TableCellSimple(text = "FC", style = headerStyle, padding = cellPadding, textAlign = TextAlign.Center)
            }
            Box(
                modifier = Modifier.weight(metersColWeight),
                contentAlignment = Alignment.Center
            ) {
                TableCellSimple(text = "Metros", style = headerStyle, padding = cellPadding, textAlign = TextAlign.Center)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        val contentModifier = if (enableInternalScroll) {
            Modifier.verticalScroll(rememberScrollState()) // Scroll interno si está habilitado
        } else {
            Modifier // Sin scroll interno
        }

        Column(modifier = contentModifier) {
            stops.forEachIndexed { index, record ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.weight(indexColWeight),
                        contentAlignment = Alignment.Center
                    ) {
                        TableCellSimple(text = "${index + 1}", style = cellStyle, padding = cellPadding, textAlign = TextAlign.Center)
                    }
                    Box(
                        modifier = Modifier.weight(timeColWeight),
                        contentAlignment = Alignment.Center
                    ) {
                        TableCellSimple(text = record.stopTimeFormatted, style = cellStyle, padding = cellPadding, textAlign = TextAlign.Center)
                    }
                    Box(
                        modifier = Modifier.weight(spo2ColWeight),
                        contentAlignment = Alignment.Center
                    ) {
                        TableCellSimple(text = "${record.spo2AtStopTime}", style = cellStyle, padding = cellPadding, textAlign = TextAlign.Center)
                    }
                    Box(
                        modifier = Modifier.weight(hrColWeight),
                        contentAlignment = Alignment.Center
                    ) {
                        TableCellSimple(text = "${record.heartRateAtStopTime}", style = cellStyle, padding = cellPadding, textAlign = TextAlign.Center)
                    }
                    Box(
                        modifier = Modifier.weight(metersColWeight),
                        contentAlignment = Alignment.Center
                    ) {
                        TableCellSimple(text = String.format(Locale.US, "%.2f", record.distanceAtStopTime), style = cellStyle, padding = cellPadding, textAlign = TextAlign.Center)
                    }
                }
                if (index < stops.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }
}

@Composable
fun CriticalValuesTable(
    summaryData: TestExecutionSummaryData?,
    modifier: Modifier = Modifier
) {
    if (summaryData == null) {
        Box(modifier
            .fillMaxSize()
            .padding(16.dp), contentAlignment = Alignment.Center) {
            Text("Datos de resumen no disponibles.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        return
    }

    val headerStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
    val valueStyle = MaterialTheme.typography.bodyLarge
    val universalTextAlign = TextAlign.Center

    // Definimos los pesos UNA VEZ y los reutilizamos
    val parameterColWeight = 0.33f
    val valueColWeight = 0.17f
    val timeColWeight = 0.25f
    val metersColWeight = 0.25f

    val cellPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)

    Column(modifier = modifier) { // Padding general de la tabla
        // --- CABECERA ---
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Box para la cabecera "Parámetro"
            Box(modifier = Modifier
                .weight(parameterColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) { // Contenido del Box centrado
                Text(text = "Parámetro", style = headerStyle, textAlign = universalTextAlign) // Texto centrado
            }
            // Box para la cabecera "Valor"
            Box(modifier = Modifier
                .weight(valueColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = "Valor", style = headerStyle, textAlign = universalTextAlign)
            }
            // Box para la cabecera "Tiempo"
            Box(modifier = Modifier
                .weight(timeColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = "Tiempo", style = headerStyle, textAlign = universalTextAlign)
            }
            // Box para la cabecera "Metros"
            Box(modifier = Modifier
                .weight(metersColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = "Metros", style = headerStyle, textAlign = universalTextAlign)
            }
        }
        FormDividerSmall()

        // --- FILA SpO2 Mínima ---
        Row(Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier
                .weight(parameterColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = "SpO2 mín", style = valueStyle, textAlign = universalTextAlign)
            }
            Box(modifier = Modifier
                .weight(valueColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = summaryData.minSpo2Record?.value?.toString() ?: "--", style = valueStyle, textAlign = universalTextAlign)
            }
            Box(modifier = Modifier
                .weight(timeColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = summaryData.minSpo2Record?.timeMillis?.let { formatDurationMillis(it) } ?: "--", style = valueStyle, textAlign = universalTextAlign)
            }
            Box(modifier = Modifier
                .weight(metersColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = summaryData.minSpo2Record?.distanceAtTime?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "--", style = valueStyle, textAlign = universalTextAlign)
            }
        }
        FormDividerSmall()

        // --- FILA FC Máxima ---
        Row(Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier
                .weight(parameterColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = "FC máx", style = valueStyle, textAlign = universalTextAlign)
            }
            Box(modifier = Modifier
                .weight(valueColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = summaryData.maxHeartRateRecord?.value?.toString() ?: "--", style = valueStyle, textAlign = universalTextAlign)
            }
            Box(modifier = Modifier
                .weight(timeColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = summaryData.maxHeartRateRecord?.timeMillis?.let { formatDurationMillis(it) } ?: "--", style = valueStyle, textAlign = universalTextAlign)
            }
            Box(modifier = Modifier
                .weight(metersColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = summaryData.maxHeartRateRecord?.distanceAtTime?.let { String.format(Locale.US, "%.2f", it) } ?: "--", style = valueStyle, textAlign = universalTextAlign)
            }
        }
        FormDividerSmall()

        // --- FILA FC Mínima ---
        Row(Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier
                .weight(parameterColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = "FC mín", style = valueStyle, textAlign = universalTextAlign)
            }
            Box(modifier = Modifier
                .weight(valueColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = summaryData.minHeartRateRecord?.value?.toString() ?: "--", style = valueStyle, textAlign = universalTextAlign)
            }
            Box(modifier = Modifier
                .weight(timeColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = summaryData.minHeartRateRecord?.timeMillis?.let { formatDurationMillis(it) } ?: "--", style = valueStyle, textAlign = universalTextAlign)
            }
            Box(modifier = Modifier
                .weight(metersColWeight)
                .padding(cellPadding), contentAlignment = Alignment.Center) {
                Text(text = summaryData.minHeartRateRecord?.distanceAtTime?.let { String.format(Locale.US, "%.2f", it) } ?: "--", style = valueStyle, textAlign = universalTextAlign)
            }
        }
    }
}

@Composable
fun FormDividerSmall(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 2.dp),
        thickness = 0.8.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    )
}

// --- Componentes de Ayuda Genéricos (Reutilizados) ---
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium.copy(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold
    ),
    titleHorizontalArrangement: Arrangement.Horizontal = Arrangement.SpaceBetween,
    titleTextAlign: TextAlign = TextAlign.Start,
    titlePadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    showTitleDivider: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    isActionsEmpty: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ElementBackgroundColor)
    ) {
        Column {
            title?.let {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.08f))
                        .padding(titlePadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = titleHorizontalArrangement
                ) {
                    val titleTextModifier = if (isActionsEmpty && titleHorizontalArrangement == Arrangement.Center) {
                        Modifier
                    } else {
                        Modifier.weight(1f, fill = false)
                    }

                    Text(
                        text = it,
                        style = titleStyle,
                        textAlign = titleTextAlign,
                        modifier = titleTextModifier
                    )

                    if (!isActionsEmpty) {
                        Row(
                            content = actions,
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        )
                    }
                }
                if (showTitleDivider) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content
            )
        }
    }
}

@Composable
fun TableCellSimple(
    text: String,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign = TextAlign.Center,
    padding: PaddingValues = PaddingValues(vertical = 2.dp, horizontal = 2.dp)
) {
    Text(
        text = text,
        modifier = Modifier.padding(padding),
        textAlign = textAlign,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

// --- Diálogos (Reutilizar los que ya tienes o adaptarlos) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObservationsInputDialog(
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(initialText) { mutableStateOf(initialText) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DialogBackgroundColor),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Observaciones Adicionales",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp, max = 250.dp), // Permitir que crezca
                    label = { Text("Escribe tus observaciones aquí...") },
                    keyboardOptions = KeyboardOptions.Default.copy(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedContainerColor = ElementBackgroundColor.copy(alpha = 0.5f),
                        unfocusedContainerColor = ElementBackgroundColor.copy(alpha = 0.5f),
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("CANCELAR", fontWeight = FontWeight.Medium) }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { onConfirm(text) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreenColor)
                    ) { Text("GUARDAR", fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

@Composable
fun PdfGeneratedDialog(
    uri: android.net.Uri,
    fileName: String,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogBackgroundColor,
        icon = { Icon(Icons.Filled.CheckCircle, "PDF Generado", tint = SuccessGreenColor) },
        title = { Text("PDF Generado Exitosamente", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface) },
        text = { Text("El archivo '$fileName' ha sido guardado. ¿Qué desea hacer?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { // Usamos confirm para los botones de acción principal
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, shape = RoundedCornerShape(8.dp)) { Text("Abrir") }
                Button(onClick = onShare, shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Filled.Share, contentDescription = "Compartir", modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Compartir")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) { Text("Cerrar") }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun LoadingDialog(text: String = "Cargando...") {
    Dialog(onDismissRequest = { /* No se puede descartar */ }) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(strokeWidth = 3.dp)
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun VitalSignDisplay(
    label: String,
    value: String,
    unit: String,
    trend: Trend,
    alarmStatus: StatusColor,
    modifier: Modifier = Modifier
) {
    val trendIcon = when (trend) {
        Trend.UP -> Icons.Filled.ArrowUpward
        Trend.DOWN -> Icons.Filled.ArrowDownward
        Trend.STABLE -> Icons.Filled.Remove
    }
    val trendContentDescription = when (trend) {
        Trend.UP -> "Tendencia ascendente"
        Trend.DOWN -> "Tendencia descendente"
        Trend.STABLE -> "Tendencia estable"
    }

    val alarmColor = alarmStatus.toComposeColor() // El color del círculo de alarma Y del texto del valor

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = ElementBackgroundColor
        ),
        border = BorderStroke(0.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 2.dp, horizontal = 2.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center // Centra el contenido de la Row
            ) {
                // Valor y Unidad
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = alarmColor, // El valor numérico toma el color de la alarma
                    modifier = Modifier.alignByBaseline()
                )
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.bodyMedium,
                    color = alarmColor, // La unidad también
                    modifier = Modifier
                        .alignByBaseline()
                        .padding(start = 2.dp)
                )

                Spacer(modifier = Modifier.width(10.dp)) // Espacio entre valor y tendencia

                // Icono de Tendencia
                Icon(
                    imageVector = trendIcon,
                    contentDescription = trendContentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), // Color neutro
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(3.dp)) // Espacio entre tendencia y círculo de alarma

                // Círculo de Alarma
                Box(
                    modifier = Modifier
                        .size(18.dp) // Tamaño del círculo de alarma
                        .background(alarmColor, CircleShape) // Color del círculo basado en la alarma
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), // Borde sutil para el círculo
                            CircleShape
                        )
                )
            }
        }
    }
}

@Composable
fun BluetoothStatusIndicatorButton(
    status: BluetoothIconStatus2,
    messageForText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick, enabled = status.isClickable)
            .padding(8.dp)
    ) {
        Icon(
            imageVector = status.icon,
            contentDescription = "Estado Bluetooth",
            tint = status.color,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = messageForText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = status.color.copy(alpha = 0.8f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// --- NUEVO: Composable para el Diálogo de Gráfica Ampliada ---
@Composable
fun EnlargedChartDialog(
    chartType: ChartTypeToShow,
    uiState: TestResultsUiState, // Para acceder a summaryData
    onDismiss: () -> Unit
) {
    val chartTitle = when (chartType) {
        ChartTypeToShow.SPO2 -> "SpO2 (%) - Detalle"
        ChartTypeToShow.HEART_RATE -> "FC (lpm) - Detalle"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false // MUY IMPORTANTE para controlar el tamaño
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f) // 90% del ancho de la pantalla
                .fillMaxHeight(0.85f), // 85% del alto de la pantalla
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DialogBackgroundColor) // O el color que prefieras
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize() // El Column llena la Card
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chartTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Cerrar diálogo")
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Contenedor de la gráfica ampliada
                Box(
                    modifier = Modifier
                        .weight(1f) // Ocupa el espacio restante
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp)) // Opcional: para redondear esquinas si la gráfica no lo hace
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)), // Un fondo ligero
                    contentAlignment = Alignment.Center
                ) {
                    when (chartType) {
                        ChartTypeToShow.SPO2 -> EnlargedSpo2ChartComposable(
                            dataPoints = uiState.summaryData?.spo2DataPoints ?: emptyList(),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp) // Padding dentro del Box para la gráfica
                        )
                        ChartTypeToShow.HEART_RATE -> EnlargedHrChartComposable(
                            dataPoints = uiState.summaryData?.heartRateDataPoints ?: emptyList(),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

// --- NUEVO: Composables para las Gráficas Ampliadas (puedes personalizarlos mucho más) ---
@Composable
fun EnlargedSpo2ChartComposable(
    dataPoints: List<DataPoint>, // Usando tu DataPoint existente
    modifier: Modifier = Modifier
) {
    val chartTextColor = MaterialTheme.colorScheme.onSurface.toArgb() // Color de texto más visible
    val chartGridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f).toArgb() // Rejilla más visible
    val chartLineColorSpo2 = MaterialTheme.colorScheme.tertiary.toArgb()

    if (dataPoints.isNotEmpty()) {
        LineChartComposable( // Reutilizamos tu LineChartComposable
            modifier = modifier,
            dataPoints = dataPoints,
            yAxisMin = 80f, // Quizás un rango diferente o más granular
            yAxisMax = 100f,
            yAxisLabelCount = 10, // Más etiquetas para detalle
            xAxisLabelCount = dataPoints.size.coerceAtMost(12), // Más etiquetas en X o dinámico
            lineColor = chartLineColorSpo2,
            textColor = chartTextColor,
            gridColor = chartGridColor
        )
    } else {
        ChartPlaceholder(modifier = modifier, "No hay datos de SpO2 para mostrar.")
    }
}

@Composable
fun EnlargedHrChartComposable(
    dataPoints: List<DataPoint>, // Usando tu DataPoint existente
    modifier: Modifier = Modifier
) {
    val chartTextColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val chartGridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f).toArgb()
    val chartLineColorHr = MaterialTheme.colorScheme.secondary.toArgb()

    if (dataPoints.isNotEmpty()) {
        LineChartComposable( // Reutilizamos tu LineChartComposable
            modifier = modifier,
            dataPoints = dataPoints,
            yAxisMin = 50f,  // Ajusta según necesidad
            yAxisMax = 180f, // Ajusta según necesidad
            yAxisLabelCount = 13, // Más etiquetas
            xAxisLabelCount = dataPoints.size.coerceAtMost(12),
            lineColor = chartLineColorHr,
            textColor = chartTextColor,
            gridColor = chartGridColor
        )
    } else {
        ChartPlaceholder(modifier = modifier, "No hay datos de FC para mostrar.")
    }
}
