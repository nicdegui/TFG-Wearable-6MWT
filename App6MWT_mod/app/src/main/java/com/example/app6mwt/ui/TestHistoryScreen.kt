package com.example.app6mwt.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.app6mwt.data.model.PruebaRealizada
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import java.util.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.app6mwt.ui.theme.*
import com.example.app6mwt.util.DateTimeFormatterUtil.formatMillisToDateTimeUserFriendly
import kotlinx.coroutines.flow.collectLatest
import kotlin.text.format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestHistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: TestHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearUserMessage()
        }
    }
    LaunchedEffect(uiState.pdfGenerationError) {
        uiState.pdfGenerationError?.let {
            Toast.makeText(context, "Error PDF: $it", Toast.LENGTH_LONG).show()
            viewModel.clearUserMessage()
        }
    }

    LaunchedEffect(viewModel.navigateBackEvent) {
        viewModel.navigateBackEvent.collectLatest {
            onNavigateBack()
        }
    }

    val currentOnRequestNavigateBack = rememberUpdatedState(viewModel::requestNavigateBack)
    BackHandler(enabled = true) {
        currentOnRequestNavigateBack.value()
    }

    Scaffold(
        topBar = {
            TestHistoryTopAppBar(
                patientName = uiState.nombrePaciente.takeIf { it.isNotBlank() && it != "Paciente no encontrado" }
                    ?: "Historial",
                patientId = uiState.pacienteId ?: "---",
                onNavigateBackClicked = { viewModel.requestNavigateBack() }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            when {
                uiState.isLoadingPaciente || uiState.isLoadingHistorial -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                uiState.errorMensaje != null -> {
                    Text(
                        text = uiState.errorMensaje!!,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                }

                uiState.historialDePruebas.isEmpty() && !uiState.isLoadingHistorial && !uiState.isLoadingPaciente && uiState.errorMensaje == null -> {
                    Text(
                        "Este paciente no tiene pruebas registradas.",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                else -> {
                    TestHistoryListContent(
                        historialDePruebas = uiState.historialDePruebas,
                        onVerDetallesClicked = { item ->
                            viewModel.onVerDetallesCompletosClicked(item)
                        },
                        onConsultarDatosPreparacionClicked = { item ->
                            viewModel.onConsultarDatosPreparacionClicked(item)
                        },
                        onImprimirResultadosClicked = { item ->
                            viewModel.onImprimirResultadosClicked(item)
                        },
                        onEliminarPruebaClicked = { item -> viewModel.onEliminarPruebaClicked(item) },
                        isGeneratingPdf = uiState.isGeneratingPdf
                    )
                }
            }
        }
    }

    // --- Diálogos ---
    if (uiState.showNavigateBackDialog) {
        ConfirmationDialog(
            title = "Confirmar Salida",
            text = "¿Está seguro de que quiere volver a la pantalla de gestión de pacientes?",
            onConfirm = { viewModel.confirmNavigateBack() },
            onDismiss = { viewModel.cancelNavigateBack() },
            confirmButtonText = "Confirmar",
            dismissButtonText = "Cancelar"
        )
    }

    if (uiState.mostrarDialogoDetalleCompleto && uiState.pruebaSeleccionadaParaDetalleCompleto != null) {
        HistorialResultadosDialog(
            pruebaRealizada = uiState.pruebaSeleccionadaParaDetalleCompleto!!,
            onDismiss = { viewModel.onDismissDialogoDetalleCompleto() }
        )
    }

    if (uiState.mostrarDialogoDatosPreparacion && uiState.datosPreparacionSeleccionados != null) {
        DatosPreparacionDialog(
            datos = uiState.datosPreparacionSeleccionados!!,
            onDismiss = { viewModel.onDismissDialogoDatosPreparacion() }
        )
    }

    if (uiState.mostrarDialogoConfirmacionEliminar && uiState.pruebaParaEliminar != null) {
        ConfirmacionEliminarDialog(
            historialItemUi = uiState.pruebaParaEliminar!!,
            onConfirm = { viewModel.onConfirmarEliminacion() },
            onDismiss = { viewModel.onDismissDialogoEliminacion() }
        )
    }

    // Diálogo para PDF generado (opcional, o podrías usar un Snackbar con acción)
    uiState.pdfGeneratedUri?.let { uri ->
        PdfGeneratedDialogHist(
            uri = uri,
            fileName = uiState.pruebaParaPdfFileName ?: "document.pdf",
            onDismiss = { viewModel.clearPdfDialogState() },
            onShare = {
                sharePdf(context, uri)
                viewModel.clearPdfDialogState()
            },
            onOpen = {
                openPdf(context, uri)
                viewModel.clearPdfDialogState()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestHistoryTopAppBar(
    patientName: String,
    patientId: String,
    onNavigateBackClicked: () -> Unit
) {
    TopAppBar(
        title = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "HISTORIAL - ${patientName.uppercase()}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextOnSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 56.dp)
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBackClicked) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    tint = TextOnSecondary
                )
            }
        },
        actions = {
            if (patientId.isNotBlank() && patientId != "---") {
                Text(
                    text = "ID: $patientId",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextOnSecondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .align(Alignment.CenterVertically)
                )
            } else {
                Spacer(modifier = Modifier.width(56.dp))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = TextOnSecondary,
            navigationIconContentColor = TextOnSecondary,
            actionIconContentColor = TextOnSecondary
        )
    )
}

@Composable
fun TestHistoryListContent(
    historialDePruebas: List<HistorialItemUi>,
    onVerDetallesClicked: (HistorialItemUi) -> Unit,
    onConsultarDatosPreparacionClicked: (HistorialItemUi) -> Unit,
    onImprimirResultadosClicked: (HistorialItemUi) -> Unit,
    onEliminarPruebaClicked: (HistorialItemUi) -> Unit,
    isGeneratingPdf: Boolean
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (historialDePruebas.isEmpty() && !isGeneratingPdf) { // Condición mejorada para el mensaje de vacío
            Box(modifier = Modifier
                .fillMaxSize()
                .weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Este paciente no tiene pruebas registradas.",
                    textAlign = TextAlign.Center,
                    fontSize = 17.sp,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(historialDePruebas, key = { it.pruebaIdOriginal }) { historialItem ->
                    HistorialItemCard(
                        item = historialItem,
                        onResultadosClicked = { onVerDetallesClicked(historialItem) },
                        onDatosPreparacionClicked = { onConsultarDatosPreparacionClicked(historialItem) },
                        onPdfClicked = { onImprimirResultadosClicked(historialItem) },
                        onDeleteClicked = { onEliminarPruebaClicked(historialItem) }
                    )
                }
            }

            if (isGeneratingPdf) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generando PDF...")
                }
            }
        }
    }
}

@Composable
fun HistorialItemCard(
    item: HistorialItemUi,
    onResultadosClicked: () -> Unit,
    onDatosPreparacionClicked: () -> Unit,
    onPdfClicked: () -> Unit,
    onDeleteClicked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ElementBackgroundColor)
    ) {
        Row( // Fila principal que divide la tarjeta en dos columnas
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .fillMaxWidth()
                .defaultMinSize(minHeight = 100.dp),
            verticalAlignment = Alignment.CenterVertically // O CenterVertically si prefieres centrar las columnas entre sí
        ) {
            // Columna Izquierda (60% del ancho)
            Column(
                modifier = Modifier.weight(0.35f), // Ocupa el 60% del espacio horizontal disponible en la Row
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Prueba Nº ${item.numeroPruebaEnLista}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Fecha: ${item.fechaFormateada}  Hora: ${item.horaFormateada}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                InfoTextLine(label = "Distancia total recorrida:", value = "${String.format("%.2f", item.distanciaMostrada)} metros")
                InfoTextLine(label = "Porcentaje teórico recorrido:", value = "${String.format("%.2f", item.porcentajeTeoricoMostrado)} %")
                InfoTextLine(label = "SpO₂ mínimo (%):", value = item.spo2MinimaMostrada?.toString() ?: "N/A")
                InfoTextLine(label = "Número de paradas:", value = item.numeroParadasMostrado?.toString() ?: "N/A")
            }

            Column(
                modifier = Modifier.weight(0.65f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Fila para los iconos de acción
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    HistoryActionIcon(
                        icon = Icons.Filled.Assessment,
                        text = "Resultados",
                        onClick = onResultadosClicked
                    )
                    HistoryActionIcon(
                        icon = Icons.Filled.MedicalInformation,
                        text = "Preparación",
                        onClick = onDatosPreparacionClicked
                    )
                    HistoryActionIcon(
                        icon = Icons.Filled.PictureAsPdf,
                        text = "PDF",
                        onClick = onPdfClicked
                    )
                    HistoryActionIcon(
                        icon = Icons.Filled.Delete,
                        text = "Eliminar",
                        onClick = onDeleteClicked,
                        isDestructive = true
                    )
                }
            }
        }
    }
}

// Composable para las líneas de información dentro del Card (label + value)
@Composable
fun InfoTextLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
            modifier = Modifier.padding(5.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// Composable para los botones de acción con icono y texto pequeño debajo
@Composable
fun HistoryActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    val tintColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(vertical = 2.dp) // Pequeño padding vertical para cada acción
            .width(IntrinsicSize.Min) // Para que el texto no se expanda demasiado
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(60.dp) // Tamaño del área clickeable del IconButton
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = tintColor,
                modifier = Modifier.size(42.dp) // Tamaño del icono en sí
            )
        }
        Text(
            text = text,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            color = if (isDestructive) tintColor else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ReadOnlyFullTestRecordTable(
    pruebaRealizada: PruebaRealizada,
    modifier: Modifier = Modifier
) {
    val datosCompletos = pruebaRealizada.datosCompletos
    val tablePadding = PaddingValues(vertical = 2.dp)
    val cellPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    val headerCellPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)

    val headerStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    val rowLabelStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
    val valueStyle = MaterialTheme.typography.bodyMedium
    val universalTextAlign = TextAlign.Center

    val parameterColWeight = 0.33f
    val basalColWeight = 0.33f
    val postTestColWeight = 0.33f

    Column(modifier = modifier.padding(tablePadding)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(ElementBackgroundColor)
                .padding(headerCellPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Parámetros (etiqueta a la izquierda, pero el Box ocupa su espacio de weight)
            Box(modifier = Modifier.weight(parameterColWeight), contentAlignment = Alignment.Center) {
                Text("Parámetros", style = headerStyle, textAlign = universalTextAlign)
            }
            // Basal (centrado)
            Box(modifier = Modifier.weight(basalColWeight), contentAlignment = Alignment.Center) {
                Text("Basal", style = headerStyle, textAlign = universalTextAlign)
            }
            // Post-Prueba (centrado)
            Box(modifier = Modifier.weight(postTestColWeight), contentAlignment = Alignment.Center) {
                Text("Postprueba", style = headerStyle, textAlign = universalTextAlign)
            }
        }
        FormDividerSmall(Modifier.padding(horizontal = 4.dp)) // Asumo que FormDividerSmall es accesible

        ReadOnlyFullTestRecordTableRow(
            parameterLabel = "SpO2 (%)",
            basalValue = datosCompletos?.summaryData?.basalSpo2?.toString() ?: "--",
            postTestValue = datosCompletos?.postTestSpo2?.toString() ?: "--",
            rowLabelStyle = rowLabelStyle, valueStyle = valueStyle, cellPadding = cellPadding,
            parameterWeight = parameterColWeight, basalValueWeight = basalColWeight, postTestValueWeight = postTestColWeight,
            cellTextAlign = universalTextAlign
        )
        FormDividerSmall(Modifier.padding(horizontal = 4.dp))
        ReadOnlyFullTestRecordTableRow(
            parameterLabel = "FC (lpm)",
            basalValue = datosCompletos?.summaryData?.basalHeartRate?.toString() ?: "--",
            postTestValue = datosCompletos?.postTestHeartRate?.toString() ?: "--",
            rowLabelStyle = rowLabelStyle, valueStyle = valueStyle, cellPadding = cellPadding,
            parameterWeight = parameterColWeight, basalValueWeight = basalColWeight, postTestValueWeight = postTestColWeight,
            cellTextAlign = universalTextAlign
        )
        FormDividerSmall(Modifier.padding(horizontal = 4.dp))
        ReadOnlyFullTestRecordTableRow(
            parameterLabel = "TA (mmHg)",
            basalValue = formatBloodPressure(
                datosCompletos?.summaryData?.basalBloodPressureSystolic,
                datosCompletos?.summaryData?.basalBloodPressureDiastolic
            ),
            postTestValue = formatBloodPressure(
                datosCompletos?.postTestSystolicBP,
                datosCompletos?.postTestDiastolicBP
            ),
            rowLabelStyle = rowLabelStyle, valueStyle = valueStyle, cellPadding = cellPadding,
            parameterWeight = parameterColWeight, basalValueWeight = basalColWeight, postTestValueWeight = postTestColWeight,
            cellTextAlign = universalTextAlign
        )
        FormDividerSmall(Modifier.padding(horizontal = 4.dp))
        ReadOnlyFullTestRecordTableRow(
            parameterLabel = "FR (rpm)",
            basalValue = datosCompletos?.summaryData?.basalRespiratoryRate?.toString() ?: "--",
            postTestValue = datosCompletos?.postTestRespiratoryRate?.toString() ?: "--",
            rowLabelStyle = rowLabelStyle, valueStyle = valueStyle, cellPadding = cellPadding,
            parameterWeight = parameterColWeight, basalValueWeight = basalColWeight, postTestValueWeight = postTestColWeight,
            cellTextAlign = universalTextAlign

        )
        FormDividerSmall(Modifier.padding(horizontal = 4.dp))
        ReadOnlyFullTestRecordTableRow(
            parameterLabel = "Disnea (Borg)",
            basalValue = datosCompletos?.summaryData?.basalDyspneaBorg?.toString() ?: "--",
            postTestValue = datosCompletos?.postTestDyspneaBorg?.toString() ?: "--",
            rowLabelStyle = rowLabelStyle, valueStyle = valueStyle, cellPadding = cellPadding,
            parameterWeight = parameterColWeight, basalValueWeight = basalColWeight, postTestValueWeight = postTestColWeight,
            cellTextAlign = universalTextAlign
        )
        FormDividerSmall(Modifier.padding(horizontal = 4.dp))
        ReadOnlyFullTestRecordTableRow(
            parameterLabel = "Dolor MII (Borg)",
            basalValue = datosCompletos?.summaryData?.basalLegPainBorg?.toString() ?: "--",
            postTestValue = datosCompletos?.postTestLegPainBorg?.toString() ?: "--",
            rowLabelStyle = rowLabelStyle, valueStyle = valueStyle, cellPadding = cellPadding,
            parameterWeight = parameterColWeight, basalValueWeight = basalColWeight, postTestValueWeight = postTestColWeight,
            cellTextAlign = universalTextAlign
        )
    }
}

@Composable
fun ReadOnlyFullTestRecordTableRow(
    parameterLabel: String,
    basalValue: String,
    postTestValue: String,
    rowLabelStyle: TextStyle,
    valueStyle: TextStyle,
    cellPadding: PaddingValues,
    parameterWeight: Float,
    basalValueWeight: Float,
    postTestValueWeight: Float,
    cellTextAlign: TextAlign
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Columna de Etiqueta de Parámetro
        Box(
            modifier = Modifier
                .weight(parameterWeight)
                .padding(cellPadding),
            contentAlignment = Alignment.Center // Contenido del Box centrado
        ) {
            Text(text = parameterLabel, style = rowLabelStyle, textAlign = cellTextAlign, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // Columna de Valor Basal
        Box(
            modifier = Modifier
                .weight(basalValueWeight)
                .padding(cellPadding),
            contentAlignment = Alignment.Center // Contenido del Box centrado
        ) {
            Text(text = basalValue, style = valueStyle, textAlign = cellTextAlign, maxLines = 1)
        }
        // Columna de Valor Post-Prueba
        Box(
            modifier = Modifier
                .weight(postTestValueWeight)
                .padding(cellPadding),
            contentAlignment = Alignment.Center // Contenido del Box centrado
        ) {
            Text(text = postTestValue, style = valueStyle, textAlign = cellTextAlign, maxLines = 1)
        }
    }
}

@Composable
fun HistorialResultadosDialog(
    pruebaRealizada: PruebaRealizada,
    onDismiss: () -> Unit
) {
    val datosCompletos = pruebaRealizada.datosCompletos
    val summaryData = datosCompletos?.summaryData

    val chartTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val chartGridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f).toArgb()
    val chartLineColorSpo2 = MaterialTheme.colorScheme.tertiary.toArgb()
    val chartLineColorHr = MaterialTheme.colorScheme.secondary.toArgb()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f) // Ancho del diálogo
                .fillMaxHeight(0.9f), // Alto del diálogo
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Título del Diálogo y Botón Cerrar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val patientName = summaryData?.patientFullName ?: "N/P"
                    val patientId = summaryData?.patientId ?: "---"
                    val testNumber = pruebaRealizada.numeroPruebaPaciente

                    Text(
                        text = "Resultados - P. Nº $testNumber | $patientName | ID: $patientId - ${formatMillisToDateTimeUserFriendly(pruebaRealizada.fechaTimestamp)}",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close, contentDescription = "Cerrar diálogo", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                // Contenido principal scrollable en una sola columna
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Gráfica de SpO2
                    SectionCard(
                        title = "SpO2 (%) durante la prueba",
                        titleHorizontalArrangement = Arrangement.Center,
                        titleTextAlign = TextAlign.Center,
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        val spo2DataPoints = summaryData?.spo2DataPoints ?: emptyList()
                        if (spo2DataPoints.isNotEmpty()) {
                            LineChartComposable(
                                modifier = Modifier.fillMaxWidth().height(380.dp),
                                dataPoints = spo2DataPoints,
                                yAxisMin = 85f,
                                yAxisMax = 100f,
                                yAxisLabelCount = 8,
                                xAxisLabelCount = 7,
                                lineColor = chartLineColorSpo2,
                                textColor = chartTextColor,
                                gridColor = chartGridColor
                            )
                        }
                    }

                    // 2. Gráfica de FC
                    SectionCard(
                        title = "Frecuencia cardíaca (lpm) durante la prueba",
                        titleHorizontalArrangement = Arrangement.Center,
                        titleTextAlign = TextAlign.Center,
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        val hrDataPoints = summaryData?.heartRateDataPoints ?: emptyList()
                        if (hrDataPoints.isNotEmpty()) {
                            LineChartComposable(
                                modifier = Modifier.fillMaxWidth().height(380.dp),
                                dataPoints = hrDataPoints,
                                yAxisMin = 60f,
                                yAxisMax = 160f,
                                yAxisLabelCount = 8,
                                xAxisLabelCount = 7,
                                lineColor = chartLineColorHr,
                                textColor = chartTextColor,
                                gridColor = chartGridColor
                            )
                        }
                    }
                    FormDividerSmall(Modifier.padding(vertical = 4.dp))

                    // 3. Tabla de registro de parámetros a cada minuto
                    SectionCard(
                        title = "Registro por minuto",
                        titleHorizontalArrangement = Arrangement.Center,
                        titleTextAlign = TextAlign.Center,
                        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)
                    ) {
                        val minuteSnapshotsList: List<MinuteDataSnapshot> =
                            summaryData?.minuteReadings ?: emptyList()

                        if (minuteSnapshotsList.isNotEmpty()) {
                            MinuteReadingsTable(
                                minuteSnapshots = minuteSnapshotsList,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No hay registros por minuto disponibles.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    FormDividerSmall(Modifier.padding(vertical = 4.dp))

                    // 4. Fila de valores de distancias
                    SectionCard(
                        title = "Resumen de distancias",
                        titleHorizontalArrangement = Arrangement.Center,
                        titleTextAlign = TextAlign.Center,
                        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDistanceItem(
                                label = "Total:",
                                value = "${String.format(Locale.US, "%.2f", pruebaRealizada.distanciaRecorrida)} m",
                                labelStyle = MaterialTheme.typography.titleMedium,
                                valueStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            HorizontalDistanceItem(
                                label = "Teórica:",
                                value = "${String.format(Locale.US, "%.2f", summaryData?.theoreticalDistance ?: 0.0)} m",
                                labelStyle = MaterialTheme.typography.titleMedium,
                                valueStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            HorizontalDistanceItem(
                                label = "% Teórico:",
                                value = "${String.format(Locale.US, "%.2f", pruebaRealizada.porcentajeTeorico)}%",
                                labelStyle = MaterialTheme.typography.titleMedium,
                                valueStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                    FormDividerSmall(Modifier.padding(vertical = 4.dp))

                    // 5. Tabla registro de paradas | Tabla registro parámetros críticos
                    SectionCard(
                        title = "Registro de paradas",
                        titleHorizontalArrangement = Arrangement.Center,
                        titleTextAlign = TextAlign.Center,
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        val stops = summaryData?.stopRecords ?: emptyList()
                        if (stops.isNotEmpty()) {
                            StopsTable(stops = stops, modifier = Modifier.fillMaxWidth(),enableInternalScroll = false)
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No hay paradas registradas.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    FormDividerSmall(Modifier.padding(vertical = 4.dp))

                    // 6. Tabla registro parámetros críticos
                    SectionCard(
                        title = "Valores críticos alcanzados",
                        titleHorizontalArrangement = Arrangement.Center,
                        titleTextAlign = TextAlign.Center,
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        CriticalValuesTable(summaryData = summaryData, modifier = Modifier.fillMaxWidth())
                    }
                    FormDividerSmall(Modifier.padding(vertical = 4.dp))

                    // 7. Tabla de registro completo de parámetros basales y post-prueba
                    SectionCard(
                        title = "Parámetros basales y postprueba",
                        titleHorizontalArrangement = Arrangement.Center,
                        titleTextAlign = TextAlign.Center,
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        ReadOnlyFullTestRecordTable(
                            pruebaRealizada = pruebaRealizada,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    FormDividerSmall(Modifier.padding(vertical = 4.dp))

                    // 8. Observaciones
                    SectionCard(
                        title = "Observaciones adicionales",
                        titleHorizontalArrangement = Arrangement.Center,
                        titleTextAlign = TextAlign.Center,
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        val observationsText = datosCompletos?.observations
                        if (observationsText.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Sin observaciones registradas.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Text(
                                text = observationsText,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DatosPreparacionDialog(datos: DatosPreparacionUi, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Datos de Preparación de la Prueba") },
        text = {
            Column {
                Text("Sexo: ${datos.sexoDelPacienteAlMomentoDeLaPrueba ?: "N/D"}", fontSize = 17.sp)
                Text("Edad: ${datos.edadDelPacienteAlMomentoDeLaPrueba?.toString() ?: "N/D"}", fontSize = 17.sp)
                Text("Altura (cm): ${datos.alturaDelPacienteAlMomentoDeLaPrueba?.toString() ?: "N/D"}", fontSize = 17.sp)
                Text("Peso (kg): ${datos.pesoDelPacienteAlMomentoDeLaPrueba?.toString() ?: "N/D"}", fontSize = 17.sp)
                Text("Distancia Teórica: ${datos.distanciaTeoricaCalculada?.let { String.format("%.2f m", it) } ?: "N/D"}", fontSize = 17.sp)
                Text("Longitud de paso: ${datos.longitudPaso?.let { String.format("%.2f m", it) } ?: "N/D"}", fontSize = 17.sp)
                Text("Usa Inhaladores: ${if (datos.usaInhaladores == true) "Sí" else "No"}", fontSize = 17.sp)
                Text("Oxígeno Domiciliario: ${if (datos.tieneOxigenoDomiciliario == true) "Sí" else "No"}", fontSize = 17.sp)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            )
            { Text("Cerrar", color = Color.White, fontSize = 17.sp) }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun ConfirmacionEliminarDialog(
    historialItemUi: HistorialItemUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = "Advertencia", tint = MaterialTheme.colorScheme.error) },
        title = { Text("Confirmar Eliminación") },
        text = { Text("¿Seguro que quieres eliminar la Prueba N° ${historialItemUi.numeroPruebaEnLista} del paciente con ID ${historialItemUi.pacienteIdOriginal}, realizada el ${historialItemUi.fechaFormateada}?", fontSize = 17.sp) },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = SuccessGreenColor)) { Text("Confirmar", color = Color.White, fontSize = 17.sp) }
        },
        dismissButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Cancelar", color = Color.White, fontSize = 17.sp) }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun PdfGeneratedDialogHist(
    uri: Uri,
    fileName: String,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss, // Esta es la acción cuando se hace clic fuera o se presiona escape
        icon = { Icon(Icons.Filled.CheckCircle, contentDescription = "PDF Generado", tint = SuccessGreenColor) },
        title = { Text("PDF Generado") }, // Tamaño de título opcional
        text = { Text("Se ha generado el archivo: $fileName", fontSize = 17.sp) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onOpen()
                        onDismiss() // Asegurar que el diálogo se cierre después de la acción
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SuccessGreenColor
                    )
                ) {
                    Text("Abrir", color = Color.White, fontSize = 17.sp)
                }
                Button(
                    onClick = {
                        onShare()
                        onDismiss() // Asegurar que el diálogo se cierre después de la acción
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Compartir Icono",
                        tint = Color.White,
                        modifier = Modifier.size(ButtonDefaults.IconSize) // Tamaño estándar de icono de botón
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing)) // Espacio estándar entre icono y texto
                    Text("Compartir", color = Color.White, fontSize = 17.sp)
                }
            }
        },
        dismissButton = { // Este es el botón explícito de "Cerrar"
            Button(
                onClick = onDismiss, // El onDismiss se llamará aquí
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Cerrar", color = Color.White, fontSize = 17.sp)
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

// --- Funciones de Ayuda para Intents ---

fun sharePdf(context: Context, uri: Uri) {
    val shareIntent: Intent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_STREAM, uri)
        type = "application/pdf"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(shareIntent, "Compartir PDF con..."))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No hay aplicación para compartir PDF.", Toast.LENGTH_SHORT).show()
    }
}

fun openPdf(context: Context, uri: Uri) {
    val openIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY) // Opcional: no mantener en el stack de actividad
    }
    try {
        context.startActivity(openIntent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No hay aplicación para abrir PDF.", Toast.LENGTH_SHORT).show()
    }
}
