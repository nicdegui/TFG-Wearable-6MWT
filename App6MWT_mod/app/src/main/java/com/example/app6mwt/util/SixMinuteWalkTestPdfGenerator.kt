package com.example.app6mwt.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import com.example.app6mwt.R
import com.example.app6mwt.ui.PruebaCompletaDetalles
import com.example.app6mwt.ui.TestExecutionSummaryData
import com.example.app6mwt.ui.StopRecord
import com.example.app6mwt.ui.DataPoint
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor


object SixMinuteWalkTestPdfGenerator {

    // --- Constantes de Diseño ---
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842

    private const val MARGIN_BIG = 30f
    private const val MARGIN_MEDIUM = 15f
    private const val MARGIN_SMALL = 8f
    private const val MARGIN_VERY_SMALL = 4f


    private const val HEADER_AREA_HEIGHT = 85f
    // Mantener el tamaño aumentado del icono, pero verificar la calidad de la imagen origen
    private const val ICON_MAX_HEIGHT_HEADER = 70f * 1.8f
    private const val ICON_MAX_WIDTH_HEADER = 190f * 2.2f

    private const val TEXT_SIZE_TITLE_MAIN = 16f
    private const val TEXT_SIZE_SECTION_HEADER_MAIN = 12f
    private const val TEXT_SIZE_NORMAL_MAIN = 10f
    private const val TEXT_SIZE_SMALL_MAIN = 8f
    private const val TEXT_SIZE_VERY_SMALL_MAIN = 7f

    private const val TEXT_SIZE_TABLE_HEADER_MAIN = 9f
    private const val TEXT_SIZE_TABLE_CELL_MAIN = 8.5f

    private const val TEXT_SIZE_GRAPH_TITLE = 13f


    private val COLOR_SECTION_BOX_FILL = Color.parseColor("#E0EFFF")
    private val COLOR_SECTION_BOX_BORDER = Color.parseColor("#B0C4DE")
    private val COLOR_SECTION_TEXT = Color.rgb(0, 0, 100)

    private val COLOR_TEXT_PRIMARY_PDF = Color.BLACK
    private val COLOR_TEXT_SECONDARY_PDF = Color.DKGRAY
    private val COLOR_SPO2_GRAPH = Color.rgb(0, 102, 204)
    private val COLOR_HR_GRAPH = Color.rgb(204, 0, 0)
    private val COLOR_TABLE_BORDER = Color.DKGRAY
    // MODIFICADO: La línea divisoria horizontal en Resumen será negra
    private val COLOR_HORIZONTAL_DIVIDER_SUMMARY = Color.BLACK
    private const val LINE_STROKE_WIDTH_HORIZONTAL_DIVIDER_SUMMARY = 1f


    private const val LINE_STROKE_WIDTH_TABLE = 1f
    private const val GRAPH_POINT_RADIUS = 2f

    // Altura de las gráficas en la sección Resumen
    private const val SUMMARY_GRAPH_HEIGHT = 165f

    // Altura fija para la caja de observaciones (el texto se truncará si excede)
    private const val OBSERVATIONS_BOX_HEIGHT = 90f
    private const val SIGNATURE_BOX_HEIGHT_CONTENT = 40f


    private lateinit var currentPage: PdfDocument.Page
    private lateinit var currentCanvas: Canvas
    private var currentPageNumber = 1
    private var currentY = MARGIN_BIG
    private lateinit var pdfDoc: PdfDocument
    private lateinit var dateGenString: String

    // Helper para el espacio antes de paréntesis
    private fun formatUnit(unit: String): String = " ($unit)"

    private fun formatDurationMillis(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
    private fun formatDurationMillis(millis: Long, shortFormat: Boolean): String {
        if (shortFormat) {
            val totalSeconds = millis / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%d'%02d\"", minutes, seconds)
        }
        return formatDurationMillis(millis)
    }
    private fun formatTimeAxisLabel(timeMillis: Long): String {
        val totalSeconds = timeMillis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }


    private fun getEstimatedTextHeight(paint: TextPaint): Float {
        return paint.fontMetrics.descent - paint.fontMetrics.ascent
    }

    fun generatePdf(
        context: Context,
        detallesPrueba: PruebaCompletaDetalles,
        numeroPrueba: Int,
        pruebaId: Int?,
        iconoHospitalResId: Int = R.drawable.img // Asegúrate que esta imagen sea de alta calidad
    ): File? {
        pdfDoc = PdfDocument()
        dateGenString = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

        val pTitleMain = TextPaint().apply { color = COLOR_TEXT_PRIMARY_PDF; textSize = TEXT_SIZE_TITLE_MAIN; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.LEFT }
        val pSectionHeader = TextPaint().apply { color = COLOR_SECTION_TEXT; textSize = TEXT_SIZE_SECTION_HEADER_MAIN; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        val pNormal = TextPaint().apply { color = COLOR_TEXT_PRIMARY_PDF; textSize = TEXT_SIZE_NORMAL_MAIN; isAntiAlias = true }
        val pNormalBold = TextPaint(pNormal).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val pSmall = TextPaint().apply { color = COLOR_TEXT_PRIMARY_PDF; textSize = TEXT_SIZE_SMALL_MAIN; isAntiAlias = true }

        // --- DIBUJAR PÁGINA 1 ---
        currentPageNumber = 1
        startNewPage()

        drawMainHeaderPdf(context, iconoHospitalResId, pTitleMain)
        currentY += MARGIN_MEDIUM

        val sectionTitleBoxHeightEstimate = getEstimatedTextHeight(pSectionHeader) + MARGIN_VERY_SMALL * 2f

        drawSectionTitlePdf("Datos del paciente y preparación de la prueba", currentY, pSectionHeader)
        currentY += sectionTitleBoxHeightEstimate + MARGIN_SMALL * 1.5f
        currentY = drawPatientAndPreparationDataPdf(detallesPrueba.summaryData, numeroPrueba, pruebaId, currentY, pNormal, pNormalBold)
        currentY += MARGIN_MEDIUM

        drawSectionTitlePdf("Resumen de la prueba", currentY, pSectionHeader)
        currentY += sectionTitleBoxHeightEstimate + MARGIN_SMALL * 1.5f
        currentY = drawTestSummaryRevisedLayoutPdf(detallesPrueba.summaryData, currentY, pNormal, pNormalBold, pNormalBold, pSmall) // MODIFICADO: Nueva función de layout

        // Potencial salto de página aquí si el resumen es muy alto
        if (currentY > PAGE_HEIGHT - (getEstimatedTextHeight(pSmall) + MARGIN_MEDIUM + MARGIN_SMALL + 50f)) { // 50f para espacio del footer
            drawPageFooterPdf(pSmall.apply { color = COLOR_TEXT_SECONDARY_PDF })
            pdfDoc.finishPage(currentPage)
            currentPageNumber++
            startNewPage()
        }

        // --- DIBUJAR PÁGINA 2 (o continuación) ---
        // Si no hubo salto de página, y no es la primera página (implica que ya se dibujó algo), añadir un pequeño margen
        // O si es la primera página pero currentY ya está avanzado.
        // Esto es para asegurar que las secciones no se peguen si el resumen fue corto.
        val isNewPageForPage2Content = currentPageNumber > 1 && currentY == MARGIN_BIG // Si es una página nueva recién iniciada
        if (!isNewPageForPage2Content) {
            currentY += MARGIN_MEDIUM // Añadir un margen si continuamos en la misma página
        }


        drawSectionTitlePdf("Datos minuto a minuto y parámetros críticos totales", currentY, pSectionHeader)
        currentY += sectionTitleBoxHeightEstimate + MARGIN_SMALL * 1.5f
        currentY = drawMinuteAndCriticalDataPdf(detallesPrueba.summaryData, currentY, pNormal, pNormalBold)
        currentY += MARGIN_MEDIUM


        if (currentY > PAGE_HEIGHT - (OBSERVATIONS_BOX_HEIGHT + SIGNATURE_BOX_HEIGHT_CONTENT + getEstimatedTextHeight(pSmall)*2 + MARGIN_MEDIUM*3)) {
            drawPageFooterPdf(pSmall.apply { color = COLOR_TEXT_SECONDARY_PDF })
            pdfDoc.finishPage(currentPage)
            currentPageNumber++
            startNewPage()
        }

        drawSectionTitlePdf("Registro completo de parámetros basales y post-prueba", currentY, pSectionHeader)
        currentY += sectionTitleBoxHeightEstimate + MARGIN_SMALL * 1.5f
        currentY = drawBasalAndPostTestDataPdf(detallesPrueba, currentY, pSectionHeader, pNormal)
        currentY += MARGIN_MEDIUM

        if (currentY > PAGE_HEIGHT - ( (getEstimatedTextHeight(pNormal)*3) + SIGNATURE_BOX_HEIGHT_CONTENT + getEstimatedTextHeight(pSmall)*2 + MARGIN_MEDIUM*3)) { // Estimación altura tabla paradas
            drawPageFooterPdf(pSmall.apply { color = COLOR_TEXT_SECONDARY_PDF })
            pdfDoc.finishPage(currentPage)
            currentPageNumber++
            startNewPage()
        }

        val stopRecords = detallesPrueba.summaryData?.stopRecords
        // Solo dibujar la sección de paradas si hay paradas
        if (stopRecords?.isNotEmpty() == true) {
            drawSectionTitlePdf("Registro de paradas", currentY, pSectionHeader)
            currentY += sectionTitleBoxHeightEstimate + MARGIN_SMALL * 1.5f
            currentY = drawStopsTablePdf(stopRecords, currentY, pSectionHeader, pNormal)
            currentY += MARGIN_MEDIUM
        }


        if (currentY > PAGE_HEIGHT - (OBSERVATIONS_BOX_HEIGHT + SIGNATURE_BOX_HEIGHT_CONTENT + getEstimatedTextHeight(pSmall)*2 + MARGIN_MEDIUM*2)) {
            drawPageFooterPdf(pSmall.apply { color = COLOR_TEXT_SECONDARY_PDF })
            pdfDoc.finishPage(currentPage)
            currentPageNumber++
            startNewPage()
        }

        drawSectionTitlePdf("Observaciones", currentY, pSectionHeader)
        currentY += sectionTitleBoxHeightEstimate + MARGIN_SMALL * 1.5f
        currentY = drawObservationsPdf(detallesPrueba.observations, currentY, pNormal)
        // currentY += MARGIN_MEDIUM // El margen se gestionará antes de la firma


        val signatureTextHeight = getEstimatedTextHeight(pNormal)
        val signatureBlockTotalHeight = SIGNATURE_BOX_HEIGHT_CONTENT + signatureTextHeight + MARGIN_VERY_SMALL * 2
        val footerHeight = getEstimatedTextHeight(pSmall) + MARGIN_SMALL + MARGIN_MEDIUM
        val extraMarginAboveSignature = MARGIN_MEDIUM // Un poco más de margen sobre la firma

        // Asegurar que la firma no se superponga con el footer y tenga espacio
        var signatureStartY = PAGE_HEIGHT - footerHeight - signatureBlockTotalHeight - extraMarginAboveSignature
        if (currentY + MARGIN_MEDIUM > signatureStartY) { // Si el contenido llega muy abajo
            // Forzar nueva página para la firma si no cabe bien
            drawPageFooterPdf(pSmall.apply { color = COLOR_TEXT_SECONDARY_PDF })
            pdfDoc.finishPage(currentPage)
            currentPageNumber++
            startNewPage()
            signatureStartY = PAGE_HEIGHT - footerHeight - signatureBlockTotalHeight - extraMarginAboveSignature
            currentY = signatureStartY // Colocar la firma en la posición calculada en la nueva página
        } else {
            currentY = max(currentY + extraMarginAboveSignature, signatureStartY)
        }


        drawSignatureAreaPdf(currentY, pNormal)
        drawPageFooterPdf(pSmall.apply { color = COLOR_TEXT_SECONDARY_PDF })
        pdfDoc.finishPage(currentPage)


        val pacienteIdParaNombre = detallesPrueba.summaryData?.patientId?.replace(Regex("[^a-zA-Z0-9_]"), "_") ?: "IDDesconocido"
        val pruebaIdParaNombre = pruebaId?.toString() ?: "IDPruebaDesconocido"
        val fileName = "Informe6MWT_${pacienteIdParaNombre}_${pruebaIdParaNombre}.pdf"
        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val reportsDir = File(documentsDir, "Informes6MWT")

        if (!reportsDir.exists() && !reportsDir.mkdirs()) {
            Log.e("PdfGenerator", "No se pudo crear el directorio: ${reportsDir.absolutePath}")
            pdfDoc.close()
            return null
        }
        val file = File(reportsDir, fileName)

        try {
            FileOutputStream(file).use { outputStream -> pdfDoc.writeTo(outputStream) }
            Log.i("PdfGenerator", "PDF generado: ${file.absolutePath}")
            return file
        } catch (e: IOException) {
            Log.e("PdfGenerator", "Error al escribir PDF", e)
            return null
        } finally {
            pdfDoc.close()
        }
    }

    private fun startNewPage() {
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, currentPageNumber).create()
        currentPage = pdfDoc.startPage(pageInfo)
        currentCanvas = currentPage.canvas
        currentY = MARGIN_BIG // Reset Y para la nueva página
    }

    @SuppressLint("NewApi")
    private fun drawMainHeaderPdf(context: Context, iconoResId: Int, titlePaint: TextPaint) {
        val actualHeaderHeight = HEADER_AREA_HEIGHT
        val title = "Informe de Prueba de Marcha de 6 Minutos (6MWT)"
        titlePaint.textAlign = Paint.Align.LEFT
        val titleYPos = currentY + actualHeaderHeight / 2f - (titlePaint.descent() + titlePaint.ascent()) / 2f
        currentCanvas.drawText(title, MARGIN_BIG, titleYPos, titlePaint)

        try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                // inScaled = false; // Opcional si está en nodpi
            }
            val originalBitmap = BitmapFactory.decodeResource(context.resources, iconoResId, options)

            if (originalBitmap != null) {
                Log.d("PdfGeneratorIcon", "Alternativa - Original Bitmap: ${originalBitmap.width}x${originalBitmap.height}")

                val originalWidth = originalBitmap.width.toFloat()
                val originalHeight = originalBitmap.height.toFloat()

                // Tus dimensiones máximas deseadas en el PDF
                val targetMaxWidth = ICON_MAX_WIDTH_HEADER // ~418f
                val targetMaxHeight = ICON_MAX_HEIGHT_HEADER // ~126f

                // Calcular las dimensiones finales manteniendo la relación de aspecto
                var finalDisplayWidth = targetMaxWidth
                var finalDisplayHeight = (originalHeight * (finalDisplayWidth / originalWidth))

                if (finalDisplayHeight > targetMaxHeight) {
                    finalDisplayHeight = targetMaxHeight
                    finalDisplayWidth = (originalWidth * (finalDisplayHeight / originalHeight))
                }
                if (finalDisplayWidth > targetMaxWidth) { // Re-check
                    finalDisplayWidth = targetMaxWidth
                    finalDisplayHeight = (originalHeight * (finalDisplayWidth / originalWidth))
                }

                // Asegurar que no sean dimensiones inválidas
                finalDisplayWidth = max(1f, finalDisplayWidth)
                finalDisplayHeight = max(1f, finalDisplayHeight)

                Log.d("PdfGeneratorIcon", "Alternativa - Dibujando a: ${finalDisplayWidth}x${finalDisplayHeight}")


                val iconX = PAGE_WIDTH - MARGIN_BIG - finalDisplayWidth
                val iconY = currentY + (actualHeaderHeight - finalDisplayHeight) / 2f

                // Define el rectángulo fuente (toda la imagen original)
                val srcRect = Rect(0, 0, originalBitmap.width, originalBitmap.height)

                // Define el rectángulo destino en el canvas con las dimensiones calculadas
                val dstRect = RectF(iconX, iconY, iconX + finalDisplayWidth, iconY + finalDisplayHeight)

                val paint = Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true // ¡MUY IMPORTANTE para el escalado en drawBitmap!
                    isDither = true
                }

                // Dejar que Canvas.drawBitmap haga el escalado directamente
                currentCanvas.drawBitmap(originalBitmap, srcRect, dstRect, paint)

                if (!originalBitmap.isRecycled) {
                    originalBitmap.recycle()
                }
                Log.d("PdfGeneratorIcon", "Alternativa - Bitmap dibujado y reciclado.")

            } else {
                Log.w("PdfGenerator", "No se pudo decodificar el icono del hospital (ID: $iconoResId).")
            }
        } catch (e: Exception) {
            Log.e("PdfGenerator", "Error al cargar o dibujar icono hospital", e)
        }
        currentY += actualHeaderHeight
    }

    private fun drawSectionTitlePdf(title: String, yPos: Float, textPaint: TextPaint) {
        val originalAlignment = textPaint.textAlign
        textPaint.textAlign = Paint.Align.CENTER

        val titleHeight = getEstimatedTextHeight(textPaint)
        val paddingVertical = MARGIN_VERY_SMALL
        val boxHeight = titleHeight + paddingVertical * 2

        val pBoxFill = Paint().apply { color = COLOR_SECTION_BOX_FILL; style = Paint.Style.FILL }
        val pBoxBorder = Paint().apply { color = COLOR_SECTION_BOX_BORDER; style = Paint.Style.STROKE; strokeWidth = 0.75f }

        currentCanvas.drawRect(MARGIN_BIG, yPos, PAGE_WIDTH - MARGIN_BIG, yPos + boxHeight, pBoxFill)
        currentCanvas.drawRect(MARGIN_BIG, yPos, PAGE_WIDTH - MARGIN_BIG, yPos + boxHeight, pBoxBorder)

        val textY = yPos + paddingVertical + titleHeight / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        currentCanvas.drawText(title, PAGE_WIDTH / 2f, textY, textPaint)

        textPaint.textAlign = originalAlignment
    }

    private fun drawTwoColumnLine(canvas: Canvas, label: String, value: String?, xLeft: Float, xRightLabelEnd: Float, y: Float, pLabel: TextPaint, pValue: TextPaint) {
        val originalLabelAlign = pLabel.textAlign
        val originalValueAlign = pValue.textAlign
        pLabel.textAlign = Paint.Align.LEFT
        pValue.textAlign = Paint.Align.LEFT

        val labelY = y // Centrado verticalmente
        val valueY = y

        canvas.drawText(label, xLeft, labelY, pLabel)
        canvas.drawText(value ?: "-", xRightLabelEnd + MARGIN_SMALL, valueY, pValue) // Aumentado el espacio entre etiqueta y valor

        pLabel.textAlign = originalLabelAlign
        pValue.textAlign = originalValueAlign
    }
    // Nueva función para dibujar un solo dato centrado en una celda de una "tabla" imaginaria
    private fun drawDataCell(canvas: Canvas, label: String, value: String, xCellStart: Float, cellWidth: Float, yTextTop: Float, pLabel: TextPaint, pValue: TextPaint) {
        val labelHeight = getEstimatedTextHeight(pLabel)
        val valueHeight = getEstimatedTextHeight(pValue)
        val totalTextHeightInCell = labelHeight + valueHeight + MARGIN_VERY_SMALL // Espacio entre label y valor
        val cellCenterY = yTextTop + totalTextHeightInCell / 2f // Asumiendo que yTextTop es el inicio del bloque para este dato

        val labelY = cellCenterY - valueHeight / 2f - MARGIN_VERY_SMALL / 2f
        val valueY = cellCenterY + labelHeight / 2f + MARGIN_VERY_SMALL / 2f


        val originalLabelAlign = pLabel.textAlign
        val originalValueAlign = pValue.textAlign
        pLabel.textAlign = Paint.Align.CENTER
        pValue.textAlign = Paint.Align.CENTER

        val centerX = xCellStart + cellWidth / 2f
        canvas.drawText(label, centerX, labelY, pLabel)
        canvas.drawText(value, centerX, valueY, pValue)

        pLabel.textAlign = originalLabelAlign
        pValue.textAlign = originalValueAlign
    }


    private fun drawPatientAndPreparationDataPdf(
        summary: TestExecutionSummaryData?,
        nPrueba: Int,
        idPruebaParaPdf: Int?,
        yPos: Float,
        pNormal: TextPaint,
        pBold: TextPaint
    ): Float {
        var localY = yPos
        val lineHeight = getEstimatedTextHeight(pNormal) * 1.8f // Un poco más de espacio entre líneas
        val contentWidth = PAGE_WIDTH - 2 * MARGIN_BIG
        val colSpacing = MARGIN_MEDIUM

        val blockWidth = (contentWidth - colSpacing) / 2f
        val labelWidthRatio = 0.45f // Un poco más de espacio para la etiqueta

        val col1LabelX = MARGIN_BIG
        val col1ValueXEst = col1LabelX + blockWidth * labelWidthRatio // Usado para pasar a drawTwoColumnLine

        val col2LabelX = MARGIN_BIG + blockWidth + colSpacing
        val col2ValueXEst = col2LabelX + blockWidth * labelWidthRatio

        if (summary == null) {
            currentCanvas.drawText("Datos del paciente no disponibles.", col1LabelX, localY, pNormal.apply{textAlign = Paint.Align.LEFT})
            return localY + lineHeight * 1.5f // Ajustar si es solo una línea
        }

        val fechaPrueba = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(summary.testActualStartTimeMillis))
        val horaPrueba = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(summary.testActualStartTimeMillis))

        fun drawLinePair(label1: String, value1: String?, label2: String?, value2: String?, yCurrent: Float) {
            val textY = yCurrent + getEstimatedTextHeight(pBold) / 2f // Centrar texto en la línea
            drawTwoColumnLine(currentCanvas, label1, value1, col1LabelX, col1ValueXEst, textY, pBold, pNormal)
            if (label2 != null) {
                drawTwoColumnLine(currentCanvas, label2, value2, col2LabelX, col2ValueXEst, textY, pBold, pNormal)
            }
        }

        drawLinePair("Nombre:", summary.patientFullName, "ID Prueba:", idPruebaParaPdf?.toString() ?: "N/A", localY)
        localY += lineHeight
        drawLinePair("ID Paciente:", summary.patientId, "Nº Prueba:", if (nPrueba > 0) nPrueba.toString() else "N/A", localY)
        localY += lineHeight
        drawLinePair("Edad:", "${summary.patientAge} años", "Fecha:", fechaPrueba, localY)
        localY += lineHeight
        drawLinePair("Sexo:", summary.patientSex, "Hora:", horaPrueba, localY)
        localY += lineHeight
        drawLinePair("Altura:", "${summary.patientHeightCm} cm", "Longitud paso:", "${summary.strideLengthUsedForTestMeters} m", localY)
        localY += lineHeight
        drawLinePair("Peso:", "${summary.patientWeightKg} kg", "Pulsioxómetro en:", summary.oximeterDevicePlacementLocation, localY)
        localY += lineHeight
        drawLinePair("Usa Inhaladores:", if (summary.usesInhalers) "Sí" else "No", "Acelerómetro en:", summary.accelerometerPlacementLocation, localY)
        localY += lineHeight

        val singleLineY2 = localY + getEstimatedTextHeight(pBold) / 2f
        drawTwoColumnLine(currentCanvas, "Usa oxígeno dom.:", if (summary.usesOxygen) "Sí" else "No", col1LabelX, col1ValueXEst, singleLineY2, pBold, pNormal)
        localY += lineHeight

        return localY
    }


    // MODIFICADO: Nueva función para el layout de "Resumen de la prueba"
    private fun drawTestSummaryRevisedLayoutPdf(
        summary: TestExecutionSummaryData?,
        yPos: Float,
        pNormal: TextPaint,
        pBold: TextPaint,
        pNormalBold: TextPaint,
        pSmall: TextPaint
    ): Float {
        var localY = yPos
        val graphHeight = SUMMARY_GRAPH_HEIGHT
        val availableWidth = PAGE_WIDTH - 2 * MARGIN_BIG

        val textGraphPaint = TextPaint(pSmall).apply { color = COLOR_TEXT_SECONDARY_PDF; isAntiAlias = true; textSize = TEXT_SIZE_VERY_SMALL_MAIN }
        val graphLinePaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f; style = Paint.Style.STROKE }
        val dataGraphPaint = Paint().apply { strokeWidth = 1.2f; isAntiAlias = true; style = Paint.Style.STROKE }

        // --- Gráfica SpO2 (Ancho completo) ---
        val spo2DataPoints = summary?.spo2DataPoints?.filterNotNull()
        val graphRectSpo2 = RectF(MARGIN_BIG, localY, MARGIN_BIG + availableWidth, localY + graphHeight)
        if (!spo2DataPoints.isNullOrEmpty()) {
            drawDataGraphPdf(
                canvas = currentCanvas, dataPoints = spo2DataPoints, graphArea = graphRectSpo2, title = "SpO2" + formatUnit("%"),
                dataColor = COLOR_SPO2_GRAPH, baseLinePaint = graphLinePaint, baseTextPaint = textGraphPaint,
                graphDataPaint = dataGraphPaint.apply { color = COLOR_SPO2_GRAPH },
                yMinFixed = 85f, yMaxFixed = 100f,
                numYLabels = 3
            )
        } else {
            currentCanvas.drawText("No hay datos de SpO2", graphRectSpo2.centerX(), graphRectSpo2.centerY(), pNormal.apply { textAlign = Paint.Align.CENTER; color = Color.GRAY })
            currentCanvas.drawRect(graphRectSpo2, graphLinePaint)
        }
        localY += graphHeight + MARGIN_MEDIUM

        // --- Gráfica FC (Ancho completo) ---
        val hrDataPoints = summary?.heartRateDataPoints?.filterNotNull()
        val graphRectHr = RectF(MARGIN_BIG, localY, MARGIN_BIG + availableWidth, localY + graphHeight)
        if (!hrDataPoints.isNullOrEmpty()) {
            drawDataGraphPdf(
                canvas = currentCanvas, dataPoints = hrDataPoints, graphArea = graphRectHr, title = "FC" + formatUnit("lpm"),
                dataColor = COLOR_HR_GRAPH, baseLinePaint = graphLinePaint, baseTextPaint = textGraphPaint,
                graphDataPaint = dataGraphPaint.apply { color = COLOR_HR_GRAPH },
                yMinFixed = 60f, yMaxFixed = 160f,
                numYLabels = 5
            )
        } else {
            currentCanvas.drawText("No hay datos de FC", graphRectHr.centerX(), graphRectHr.centerY(), pNormal.apply { textAlign = Paint.Align.CENTER; color = Color.GRAY })
            currentCanvas.drawRect(graphRectHr, graphLinePaint)
        }
        localY += graphHeight + MARGIN_SMALL // Menos margen antes de la línea

        // --- Línea Horizontal Divisoria ---
        val dividerPaint = Paint().apply { color = COLOR_HORIZONTAL_DIVIDER_SUMMARY; strokeWidth = LINE_STROKE_WIDTH_HORIZONTAL_DIVIDER_SUMMARY }
        currentCanvas.drawLine(MARGIN_BIG, localY, PAGE_WIDTH - MARGIN_BIG, localY, dividerPaint)
        localY += MARGIN_MEDIUM // Espacio después de la línea

        // --- Datos Clave (Distribuidos) ---
        if (summary != null) {
            val pDataLabel = TextPaint(pSmall).apply { color = COLOR_TEXT_SECONDARY_PDF; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)}
            val pDataValue = TextPaint(pNormalBold).apply { textAlign = Paint.Align.CENTER }

            val dataItemsRow1 = listOf(
                Pair("Duración", formatDurationMillis(summary.actualTestDurationMillis)),
                Pair("Paradas", summary.stopRecords.size.toString()),
                Pair("Distancia Real", String.format(Locale.US, "%.2f m", summary.distanceMetersFinal)),
                Pair("% Distancia", String.format(Locale.US, "%.2f %%", if (summary.theoreticalDistance > 0) (summary.distanceMetersFinal / summary.theoreticalDistance.toFloat()) * 100 else 0f))
            )
            val dataItemsRow2 = listOf(
                Pair("SpO2 Mínimo"+formatUnit("%"), summary.minSpo2Record?.value?.toString() ?: "-"),
                Pair("FC Máxima"+formatUnit("lpm"), summary.maxHeartRateRecord?.value?.toString() ?: "-"),
                Pair("FC Mínima"+formatUnit("lpm"), summary.minHeartRateRecord?.value?.toString() ?: "-")
            )

            val dataRowHeight = getEstimatedTextHeight(pDataLabel) + getEstimatedTextHeight(pDataValue) + MARGIN_SMALL * 2f
            var currentDataY = localY

            // --- Dibujar Fila 1 de Datos ---
            val numberOfColumnsRow1 = dataItemsRow1.size // Debería ser 4
            val cellWidthRow1 = availableWidth / numberOfColumnsRow1
            var currentXRow1 = MARGIN_BIG

            // Guardar las posiciones X donde terminan las celdas de la Fila 1
            // Estas serán los puntos de centrado para los items de la Fila 2
            val columnEndPositionsX = FloatArray(numberOfColumnsRow1)

            for (i in dataItemsRow1.indices) {
                val item = dataItemsRow1[i]
                drawDataCell(currentCanvas, item.first, item.second, currentXRow1, cellWidthRow1, currentDataY, pDataLabel, pDataValue)
                columnEndPositionsX[i] = currentXRow1 + cellWidthRow1 // Guardar el final de esta celda
                currentXRow1 += cellWidthRow1
            }
            currentDataY += dataRowHeight

            // --- Dibujar Fila 2 de Datos (Centrados entre las columnas de la Fila 1) ---
            // Esta lógica es específica para cuando la Fila 1 tiene 4 items y la Fila 2 tiene 3.
            if (dataItemsRow2.size == 3 && numberOfColumnsRow1 == 4) {
                val centerPointsX = floatArrayOf(
                    columnEndPositionsX[0],
                    columnEndPositionsX[1],
                    columnEndPositionsX[2]
                )

                val cellWidthRow2 = cellWidthRow1

                for (i in dataItemsRow2.indices) {
                    val item = dataItemsRow2[i]
                    val targetCenterX = centerPointsX[i]

                    val xCellStartForRow2Item = targetCenterX - (cellWidthRow2 / 2f)

                    drawDataCell(currentCanvas, item.first, item.second, xCellStartForRow2Item, cellWidthRow2, currentDataY, pDataLabel, pDataValue)
                }
            }
            currentDataY += dataRowHeight
            localY = currentDataY

        } else {
            currentCanvas.drawText("No hay datos de resumen", PAGE_WIDTH / 2f, localY + MARGIN_MEDIUM, pNormal.apply { textAlign = Paint.Align.CENTER; color = Color.GRAY })
            localY += MARGIN_MEDIUM + getEstimatedTextHeight(pNormal)
        }

        return localY
    }

    private fun drawCellsAndBorders(
        canvas: Canvas,
        xStart: Float,
        yStart: Float,
        colWidths: FloatArray,
        rowHeight: Float,
        values: List<String>,
        defaultCellPaint: TextPaint,
        borderPaint: Paint,
        isHeader: Boolean = false,
        headerPaintOverride: TextPaint? = null,
        cellPaintsOverride: List<TextPaint>? = null
    ) {
        var currentCellX = xStart
        val finalHeaderPaint = headerPaintOverride ?: defaultCellPaint

        canvas.drawLine(xStart, yStart, xStart + colWidths.sum(), yStart, borderPaint) // Top border of row

        values.forEachIndexed { index, text ->
            val cellWidth = colWidths[index]
            val cellPaintToUse = if (isHeader) finalHeaderPaint else (cellPaintsOverride?.getOrNull(index) ?: defaultCellPaint)
            val textY = yStart + rowHeight / 2 - (cellPaintToUse.descent() + cellPaintToUse.ascent()) / 2f
            val textX = when (cellPaintToUse.textAlign) {
                Paint.Align.LEFT -> currentCellX + MARGIN_VERY_SMALL * 2
                Paint.Align.RIGHT -> currentCellX + cellWidth - MARGIN_VERY_SMALL * 2
                else -> currentCellX + cellWidth / 2f // CENTER
            }
            // Truncate text if it's too wide for the cell (simple truncation)
            var textToDraw = text
            val availableTextWidth = cellWidth - MARGIN_VERY_SMALL * 4 // Padding
            var measuredTextWidth = cellPaintToUse.measureText(textToDraw)

            if (measuredTextWidth > availableTextWidth) {
                // Basic truncation with ellipsis
                while (measuredTextWidth > availableTextWidth && textToDraw.length > 1) {
                    textToDraw = textToDraw.substring(0, textToDraw.length - 2) + "…"
                    measuredTextWidth = cellPaintToUse.measureText(textToDraw)
                }
                if (textToDraw.length <=1 && measuredTextWidth > availableTextWidth) textToDraw = "…" // If even ellipsis is too long
            }

            canvas.drawText(textToDraw, textX, textY, cellPaintToUse)
            canvas.drawLine(currentCellX + cellWidth, yStart, currentCellX + cellWidth, yStart + rowHeight, borderPaint) // Right border of cell
            currentCellX += cellWidth
        }
        canvas.drawLine(xStart, yStart + rowHeight, xStart + colWidths.sum(), yStart + rowHeight, borderPaint) // Bottom border of row
        canvas.drawLine(xStart, yStart, xStart, yStart + rowHeight, borderPaint) // Left border of first cell
    }


    private fun drawMinuteAndCriticalDataPdf(summary: TestExecutionSummaryData?, yPos: Float, pNormal: TextPaint, pBold: TextPaint): Float {
        var localY = yPos
        val tableLineHeight = getEstimatedTextHeight(pNormal) * 1.9f
        val pCellDefault = TextPaint(pNormal).apply { textSize = TEXT_SIZE_TABLE_CELL_MAIN; textAlign = Paint.Align.CENTER }
        val pCellLeftAlign = TextPaint(pCellDefault).apply { textAlign = Paint.Align.LEFT }
        val pCellGray = TextPaint(pCellDefault).apply { color = Color.GRAY }
        val pTableHeader = TextPaint(pBold).apply { textSize = TEXT_SIZE_TABLE_HEADER_MAIN; textAlign = Paint.Align.CENTER; color = COLOR_SECTION_TEXT }
        val pTableBorder = Paint().apply { color = COLOR_TABLE_BORDER; strokeWidth = LINE_STROKE_WIDTH_TABLE; style = Paint.Style.STROKE }

        val contentWidthAvailable = PAGE_WIDTH - MARGIN_BIG * 2
        val tableSpacing = MARGIN_MEDIUM
        val tableWidth = (contentWidthAvailable - tableSpacing) / 2f

        // --- Tabla Datos Minuto a Minuto (Izquierda) ---
        val minuteTableXStart = MARGIN_BIG
        // MODIFICADO: Espacio en unidades
        val minuteHeaders = listOf("Minuto", "SpO2" + formatUnit("%"), "FC" + formatUnit("lpm"), "Dist." + formatUnit("m"))
        val minuteColWidths = floatArrayOf(tableWidth * 0.25f, tableWidth * 0.25f, tableWidth * 0.25f, tableWidth * 0.25f)
        var currentYTable1 = localY

        drawCellsAndBorders(currentCanvas, minuteTableXStart, currentYTable1, minuteColWidths, tableLineHeight, minuteHeaders, pCellDefault, pTableBorder, true, pTableHeader)
        currentYTable1 += tableLineHeight

        val totalMinutesToShow = 6 // Mostrar siempre 6 filas para los minutos
        if (summary?.minuteReadings.isNullOrEmpty()) {
            for (i in 0 until totalMinutesToShow) {
                val placeholderRow = listOf(formatDurationMillis((i + 1) * 60000L), "-", "-", "-")
                drawCellsAndBorders(currentCanvas, minuteTableXStart, currentYTable1, minuteColWidths, tableLineHeight, placeholderRow, pCellGray, pTableBorder, false, null, listOf(pCellDefault,pCellGray,pCellGray,pCellGray))
                currentYTable1 += tableLineHeight
            }
        } else {
            val readingsMap = summary.minuteReadings.associateBy { it.minuteMark }
            for (minuteMarkValue in 1..totalMinutesToShow) {
                val data = readingsMap[minuteMarkValue]
                val cellValues = if (data != null) {
                    listOf(
                        formatDurationMillis(data.minuteMark * 60000L),
                        data.minSpo2Overall?.toString() ?: "-",
                        data.maxHrOverall?.toString() ?: "-",
                        data.distanceAtMinuteEnd?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                    )
                } else {
                    listOf(formatDurationMillis(minuteMarkValue * 60000L), "-", "-", "-")
                }
                val cellPaints = if (data != null) listOf(pCellDefault, pCellDefault, pCellDefault, pCellDefault) else listOf(pCellDefault, pCellGray, pCellGray, pCellGray)
                drawCellsAndBorders(currentCanvas, minuteTableXStart, currentYTable1, minuteColWidths, tableLineHeight, cellValues, pCellDefault, pTableBorder, false, null, cellPaints)
                currentYTable1 += tableLineHeight
            }
        }


        // --- Tabla Parámetros Críticos (Derecha) ---
        val criticalTableXStart = MARGIN_BIG + tableWidth + tableSpacing
        // MODIFICADO: Espacio en unidades
        val criticalHeaders = listOf("Parámetro", "Valor", "Tiempo", "Dist." + formatUnit("m"))
        val criticalColWidths = floatArrayOf(tableWidth * 0.35f, tableWidth * 0.18f, tableWidth * 0.22f, tableWidth * 0.25f)
        var currentYTable2 = localY

        drawCellsAndBorders(currentCanvas, criticalTableXStart, currentYTable2, criticalColWidths, tableLineHeight, criticalHeaders, pCellDefault, pTableBorder, true, pTableHeader)
        currentYTable2 += tableLineHeight

        val criticalRecordsData = mutableListOf<List<String>>()
        summary?.minSpo2Record?.let {
            criticalRecordsData.add(listOf("SpO2 mín" + formatUnit("%"), "${it.value}%", formatDurationMillis(it.timeMillis, true), String.format(Locale.US, "%.2f", it.distanceAtTime)))
        }
        summary?.maxHeartRateRecord?.let {
            criticalRecordsData.add(listOf("FC máx" + formatUnit("lpm"), "${it.value}", formatDurationMillis(it.timeMillis, true), String.format(Locale.US, "%.2f", it.distanceAtTime)))
        }
        summary?.minHeartRateRecord?.let {
            criticalRecordsData.add(listOf("FC mín" + formatUnit("lpm"), "${it.value}", formatDurationMillis(it.timeMillis, true), String.format(Locale.US, "%.2f", it.distanceAtTime)))
        }

        val cellPaintsCritical = listOf(pCellLeftAlign, pCellDefault, pCellDefault, pCellDefault)
        val numCriticalRowsToShow = 3 // Siempre mostrar 3 filas

        if (criticalRecordsData.isEmpty()) {
            for (i in 0 until numCriticalRowsToShow) {
                val placeholderRow = if (i == 0) listOf("No hay datos críticos", "-", "-", "-") else listOf("-", "-", "-", "-")
                val paintsToUse = if (i == 0) listOf(pCellLeftAlign.apply{color=Color.GRAY}, pCellGray, pCellGray, pCellGray) else listOf(pCellLeftAlign.apply{color=Color.GRAY}, pCellGray, pCellGray, pCellGray) // Aplicar gris a todas las celdas de placeholder
                drawCellsAndBorders(currentCanvas, criticalTableXStart, currentYTable2, criticalColWidths, tableLineHeight, placeholderRow, pCellDefault, pTableBorder, false, null, paintsToUse)
                currentYTable2 += tableLineHeight
            }
        } else {
            criticalRecordsData.forEach { values ->
                drawCellsAndBorders(currentCanvas, criticalTableXStart, currentYTable2, criticalColWidths, tableLineHeight, values, pCellDefault, pTableBorder, false, null, cellPaintsCritical)
                currentYTable2 += tableLineHeight
            }
            // Rellenar con filas vacías si hay menos de numCriticalRowsToShow registros
            for (i in criticalRecordsData.size until numCriticalRowsToShow) {
                drawCellsAndBorders(currentCanvas, criticalTableXStart, currentYTable2, criticalColWidths, tableLineHeight, listOf("-", "-", "-", "-"), pCellGray, pTableBorder, false, null, listOf(pCellLeftAlign.apply{color=Color.GRAY}, pCellGray, pCellGray, pCellGray))
                currentYTable2 += tableLineHeight
            }
        }
        return max(currentYTable1, currentYTable2)
    }


    private fun drawBasalAndPostTestDataPdf(detalles: PruebaCompletaDetalles, yPos: Float, pHeaderBase: TextPaint, pCellBase: TextPaint): Float {
        var localY = yPos
        val tableLineHeight = getEstimatedTextHeight(pCellBase) * 1.9f
        val tableWidth = PAGE_WIDTH - 2 * MARGIN_BIG

        val pCellLeft = TextPaint(pCellBase).apply { textAlign = Paint.Align.LEFT; textSize = TEXT_SIZE_TABLE_CELL_MAIN }
        val pCellCenter = TextPaint(pCellBase).apply { textAlign = Paint.Align.CENTER; textSize = TEXT_SIZE_TABLE_CELL_MAIN }
        val pHeaderCenter = TextPaint(pHeaderBase).apply { textAlign = Paint.Align.CENTER; textSize = TEXT_SIZE_TABLE_HEADER_MAIN; color = COLOR_SECTION_TEXT }
        val pTableBorder = Paint().apply { color = COLOR_TABLE_BORDER; strokeWidth = LINE_STROKE_WIDTH_TABLE; style = Paint.Style.STROKE }

        // MODIFICADO: Espacio en unidades
        val headers = listOf("Parámetros", "SpO2" + formatUnit("%"), "FC" + formatUnit("lpm"), "TA" + formatUnit("mmHg"), "FR" + formatUnit("rpm"), "Disnea" + formatUnit("Borg"), "Dolor MII" + formatUnit("Borg"))
        val colWidths = floatArrayOf(tableWidth * 0.20f, tableWidth * 0.11f, tableWidth * 0.11f, tableWidth * 0.13f, tableWidth * 0.11f, tableWidth * 0.17f, tableWidth * 0.17f) // Ajustar anchos

        drawCellsAndBorders(currentCanvas, MARGIN_BIG, localY, colWidths, tableLineHeight, headers, pCellCenter, pTableBorder, true, pHeaderCenter)
        localY += tableLineHeight

        val summary = detalles.summaryData
        val basalValues = listOf(
            "Basales",
            summary?.basalSpo2?.takeIf { it > 0 }?.toString() ?: "-",
            summary?.basalHeartRate?.takeIf { it > 0 }?.toString() ?: "-",
            if (summary?.basalBloodPressureSystolic ?: 0 > 0 && summary?.basalBloodPressureDiastolic ?: 0 > 0) "${summary?.basalBloodPressureSystolic}/${summary?.basalBloodPressureDiastolic}" else "-",
            summary?.basalRespiratoryRate?.takeIf { it > 0 }?.toString() ?: "-",
            summary?.basalDyspneaBorg?.takeIf { it >= 0 }?.toString() ?: "-",
            summary?.basalLegPainBorg?.takeIf { it >= 0 }?.toString() ?: "-"
        )
        val basalPaints = mutableListOf<TextPaint>().apply {
            add(pCellLeft)
            repeat(basalValues.size - 1) { add(pCellCenter) }
        }
        drawCellsAndBorders(currentCanvas, MARGIN_BIG, localY, colWidths, tableLineHeight, basalValues, pCellCenter, pTableBorder, false, null, basalPaints)
        localY += tableLineHeight

        val postTestValues = listOf(
            "Postprueba",
            detalles.postTestSpo2?.takeIf { it > 0 }?.toString() ?: "-",
            detalles.postTestHeartRate?.takeIf { it > 0 }?.toString() ?: "-",
            if (detalles.postTestSystolicBP?.takeIf { it > 0 } != null && detalles.postTestDiastolicBP?.takeIf { it > 0 } != null) "${detalles.postTestSystolicBP}/${detalles.postTestDiastolicBP}" else "-",
            detalles.postTestRespiratoryRate?.takeIf { it > 0 }?.toString() ?: "-",
            detalles.postTestDyspneaBorg?.takeIf { it >= 0 }?.toString() ?: "-",
            detalles.postTestLegPainBorg?.takeIf { it >= 0 }?.toString() ?: "-"
        )
        val postTestPaints = mutableListOf<TextPaint>().apply {
            add(pCellLeft)
            repeat(postTestValues.size - 1) { add(pCellCenter) }
        }
        drawCellsAndBorders(currentCanvas, MARGIN_BIG, localY, colWidths, tableLineHeight, postTestValues, pCellCenter, pTableBorder, false, null, postTestPaints)
        localY += tableLineHeight

        return localY
    }

    private fun drawStopsTablePdf(stopRecords: List<StopRecord>?, yPos: Float, pHeaderBase: TextPaint, pCellBase: TextPaint): Float {
        var localY = yPos
        val tableLineHeight = getEstimatedTextHeight(pCellBase) * 1.9f
        val tableWidth = PAGE_WIDTH - 2 * MARGIN_BIG

        val pCellCenter = TextPaint(pCellBase).apply { textAlign = Paint.Align.CENTER; textSize = TEXT_SIZE_TABLE_CELL_MAIN }
        val pCellLeftGray = TextPaint(pCellBase).apply{ textAlign = Paint.Align.LEFT; color = Color.GRAY; textSize = TEXT_SIZE_TABLE_CELL_MAIN }
        val pHeaderCenter = TextPaint(pHeaderBase).apply { textAlign = Paint.Align.CENTER; textSize = TEXT_SIZE_TABLE_HEADER_MAIN; color = COLOR_SECTION_TEXT }
        val pTableBorder = Paint().apply { color = COLOR_TABLE_BORDER; strokeWidth = LINE_STROKE_WIDTH_TABLE; style = Paint.Style.STROKE }

        // MODIFICADO: Espacio en unidades
        val headers = listOf("Nº", "Tiempo", "SpO2" + formatUnit("%"), "FC" + formatUnit("lpm"), "Distancia" + formatUnit("m"))
        val colWidths = floatArrayOf(tableWidth * 0.10f, tableWidth * 0.20f, tableWidth * 0.20f, tableWidth * 0.20f, tableWidth * 0.30f)

        drawCellsAndBorders(currentCanvas, MARGIN_BIG, localY, colWidths, tableLineHeight, headers, pCellCenter, pTableBorder, true, pHeaderCenter)
        localY += tableLineHeight

        val minRowsForStops = 2 // Mostrar al menos espacio para 2 paradas o el placeholder

        if (stopRecords.isNullOrEmpty()) {
            val placeholder = "No se registraron paradas."
            val placeholderValues = listOf(placeholder, "", "", "", "") // Ocupa toda la primera celda
            val placeholderPaints = listOf(pCellLeftGray, pCellCenter, pCellCenter, pCellCenter, pCellCenter) //pCellCenter para celdas vacias
            // Hacer que el placeholder ocupe toda la fila visualmente (fusionando celdas conceptualmente)
            val placeholderColWidths = floatArrayOf(tableWidth) // Una sola celda para el texto
            // Dibujar solo la primera celda con el texto, y luego bordes para la fila
            drawCellsAndBorders(currentCanvas, MARGIN_BIG, localY, colWidths, tableLineHeight, placeholderValues, pCellCenter,pTableBorder, false, null, placeholderPaints.map { it.apply { if(placeholderPaints.indexOf(it) == 0) textAlign = Paint.Align.LEFT else textAlign = Paint.Align.CENTER} })

            localY += tableLineHeight
            // Añadir una fila vacía adicional si solo se mostró el placeholder para mantener el tamaño mínimo
            if (minRowsForStops > 1) {
                val emptyValues = listOf("", "", "", "", "")
                drawCellsAndBorders(currentCanvas, MARGIN_BIG, localY, colWidths, tableLineHeight, emptyValues, pCellCenter.apply { color = Color.TRANSPARENT } , pTableBorder) // Transparente para que no se vea el "-"
                localY += tableLineHeight
            }

        } else {
            stopRecords.forEachIndexed { index, stop ->
                val cellValues = listOf(
                    (index + 1).toString(),
                    stop.stopTimeFormatted,
                    stop.spo2AtStopTime?.takeIf { it > 0 }?.toString() ?: "-",
                    stop.heartRateAtStopTime?.takeIf { it > 0 }?.toString() ?: "-",
                    String.format(Locale.US, "%.2f", stop.distanceAtStopTime)
                )
                drawCellsAndBorders(currentCanvas, MARGIN_BIG, localY, colWidths, tableLineHeight, cellValues, pCellCenter, pTableBorder)
                localY += tableLineHeight
            }
            // Rellenar con filas vacías si hay menos paradas que minRowsForStops
            for (i in stopRecords.size until minRowsForStops) {
                val emptyValues = listOf("", "", "", "", "") // No "-", sino realmente vacío
                drawCellsAndBorders(currentCanvas, MARGIN_BIG, localY, colWidths, tableLineHeight, emptyValues, pCellCenter.apply { color = Color.TRANSPARENT } , pTableBorder)
                localY += tableLineHeight
            }
        }
        return localY
    }

    private fun drawObservationsPdf(observations: String?, yPos: Float, pNormal: TextPaint): Float {
        var localY = yPos
        val textWidth = PAGE_WIDTH - 2 * MARGIN_BIG
        val observationBoxHeight = OBSERVATIONS_BOX_HEIGHT // Altura fija
        val pTableBorder = Paint().apply { color = COLOR_TABLE_BORDER; strokeWidth = LINE_STROKE_WIDTH_TABLE; style = Paint.Style.STROKE }
        val pPlaceholder = TextPaint(pNormal).apply{textAlign=Paint.Align.CENTER; color=Color.GRAY}

        currentCanvas.drawRect(MARGIN_BIG, localY, PAGE_WIDTH - MARGIN_BIG, localY + observationBoxHeight, pTableBorder)

        if (!observations.isNullOrBlank()) {
            val textPaint = TextPaint(pNormal)
            // Permitir que StaticLayout use múltiples líneas hasta llenar observationBoxHeight
            val staticLayout = StaticLayout.Builder.obtain(
                observations, 0, observations.length, textPaint,
                (textWidth - MARGIN_SMALL * 2).toInt() // Ancho interno de la caja
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.1f) // Espaciado de línea
                .setIncludePad(true)
                .setEllipsize(TextUtils.TruncateAt.END) // Truncar si excede el número de líneas
                .setMaxLines( (observationBoxHeight / (getEstimatedTextHeight(textPaint) * 1.15f ) ).toInt().coerceAtLeast(1) ) // Calcular maxLines basado en la altura
                .build()

            currentCanvas.save()
            currentCanvas.translate(MARGIN_BIG + MARGIN_SMALL, localY + MARGIN_SMALL) // Padding interno
            staticLayout.draw(currentCanvas)
            currentCanvas.restore()
        } else {
            val placeholder = "No se hicieron observaciones." // MODIFICADO
            val textY = localY + observationBoxHeight / 2 - (pPlaceholder.descent() + pPlaceholder.ascent()) / 2
            currentCanvas.drawText(placeholder, MARGIN_BIG + textWidth / 2f, textY, pPlaceholder)
        }
        localY += observationBoxHeight
        return localY
    }


    private fun drawSignatureAreaPdf(yPos: Float, pNormal: TextPaint) {
        var localY = yPos
        val signatureLineWidth = 180f
        val signatureContentBoxHeight = SIGNATURE_BOX_HEIGHT_CONTENT
        val signatureLineXStart = PAGE_WIDTH - MARGIN_BIG - signatureLineWidth
        val pTableBorder = Paint().apply { color = COLOR_TABLE_BORDER; strokeWidth = LINE_STROKE_WIDTH_TABLE; style = Paint.Style.STROKE }
        val pNormalCenter = TextPaint(pNormal).apply{textAlign = Paint.Align.CENTER}
        val textHeight = getEstimatedTextHeight(pNormalCenter)

        // Ajustar Y para que el texto "Firma del profesional" esté sobre la caja y no se superponga
        val textY = yPos - MARGIN_VERY_SMALL // yPos será el inicio de la caja, el texto va encima
        currentCanvas.drawText("Firma del profesional", signatureLineXStart + signatureLineWidth / 2f, textY, pNormalCenter)
        // La caja se dibuja desde yPos
        currentCanvas.drawRect(signatureLineXStart, yPos , signatureLineXStart + signatureLineWidth, yPos + signatureContentBoxHeight, pTableBorder)
    }


    private fun drawPageFooterPdf(footerPaint: TextPaint) {
        val footerY = PAGE_HEIGHT - MARGIN_MEDIUM
        val lineY = footerY - MARGIN_SMALL
        val linePaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }
        currentCanvas.drawLine(MARGIN_BIG, lineY, PAGE_WIDTH - MARGIN_BIG, lineY, linePaint)

        val originalAlign = footerPaint.textAlign
        footerPaint.textAlign = Paint.Align.LEFT
        currentCanvas.drawText("Generado: $dateGenString", MARGIN_BIG, footerY, footerPaint)

        footerPaint.textAlign = Paint.Align.RIGHT
        val pageNumText = "Página $currentPageNumber"
        currentCanvas.drawText(pageNumText, PAGE_WIDTH - MARGIN_BIG, footerY, footerPaint)
        footerPaint.textAlign = originalAlign
    }

    private fun drawDataGraphPdf(
        canvas: Canvas,
        dataPoints: List<DataPoint>,
        graphArea: RectF, // Esta es el área total disponible para la gráfica, incluyendo título y etiquetas
        title: String,
        dataColor: Int,
        baseLinePaint: Paint,
        baseTextPaint: TextPaint, // Este es el pSmall (TEXT_SIZE_VERY_SMALL_MAIN)
        graphDataPaint: Paint,
        yMinFixed: Float?,
        yMaxFixed: Float?,
        // yLabelSuffix se elimina, se incluye en el título
        numYLabels: Int = 5 // numXLabels se define internamente
    ) {
        if (dataPoints.isEmpty()) {
            val placeholderPaint = TextPaint(baseTextPaint).apply { textAlign = Paint.Align.CENTER; color = Color.GRAY; textSize = TEXT_SIZE_NORMAL_MAIN}
            // MODIFICADO: Título de la gráfica con "(Sin datos)"
            val fullTitlePlaceholder = "$title (Sin datos)"
            canvas.drawText(fullTitlePlaceholder, graphArea.centerX(), graphArea.centerY(), placeholderPaint)
            val emptyGraphBorder = Paint(baseLinePaint).apply { style = Paint.Style.STROKE }
            canvas.drawRect(graphArea, emptyGraphBorder)
            return
        }

        val graphTitlePaint = TextPaint(baseTextPaint).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = TEXT_SIZE_GRAPH_TITLE
            color = COLOR_SECTION_TEXT
        }
        // Calcular la altura real del título para descontarla del área de dibujo de la gráfica
        val titleTextHeight = getEstimatedTextHeight(graphTitlePaint)
        val titleYPos = graphArea.top + titleTextHeight - graphTitlePaint.descent() - graphTitlePaint.ascent()/2f // Centrar título en su espacio
        canvas.drawText(title, graphArea.centerX(), titleYPos, graphTitlePaint)

        // Definir el área real para dibujar la malla y los datos, descontando espacio para título y etiquetas de ejes
        val yAxisLabelTextPaint = TextPaint(baseTextPaint)
        val yAxisLabelTestText = (yMaxFixed?.let { String.format(Locale.US, "%.0f", it) } ?: "100") // No suffix, es parte del título
        val yAxisLabelWidth = yAxisLabelTextPaint.measureText(yAxisLabelTestText) + MARGIN_SMALL
        val xAxisLabelHeight = getEstimatedTextHeight(yAxisLabelTextPaint) + MARGIN_SMALL

        // El drawableGraphArea es donde se dibuja la línea de datos y las líneas de la cuadrícula
        val drawableGraphArea = RectF(
            graphArea.left + yAxisLabelWidth,
            graphArea.top + titleTextHeight + MARGIN_SMALL, // Espacio después del título
            graphArea.right - MARGIN_VERY_SMALL, // Pequeño margen derecho para que no se peguen los puntos
            graphArea.bottom - xAxisLabelHeight
        )
        // Dibujar borde exterior del área de datos
        canvas.drawRect(drawableGraphArea, baseLinePaint.apply{style=Paint.Style.STROKE; color=Color.GRAY})


        val minTime = 0L
        val maxTimeOverall = dataPoints.maxOfOrNull { it.timeMillis }?.takeIf { it > 0 } ?: (6 * 60000L)
        val maxTime = max(maxTimeOverall, 60000L) // Mínimo 1 minuto en eje X
        val timeRange = maxTime - minTime

        val actualMinVal = dataPoints.minOfOrNull { it.value } ?: 0f
        val actualMaxVal = dataPoints.maxOfOrNull { it.value } ?: (yMaxFixed ?: 100f)

        var yMin = yMinFixed ?: floor(actualMinVal / 10f) * 10f
        var yMax = yMaxFixed ?: ceil(actualMaxVal / 10f) * 10f

        if (yMin == yMax) {
            yMin = floor((actualMinVal - 5f).coerceAtLeast(0f) / 10f) * 10f
            yMax = ceil((actualMaxVal + 5f) / 10f) * 10f
            if (yMin == yMax) yMax += 10f
        }
        if (yMinFixed == null && yMin < 0f && !title.contains("lpm")) yMin = 0f // No SpO2 negativo
        if (yMax - yMin < 10f && yMaxFixed == null) {
            val mid = (yMax + yMin) / 2f
            yMin = (mid - 5f).coerceAtLeast(0f)
            yMax = mid + 5f
            if (yMin < 0f && !title.contains("lpm") && yMinFixed == null) yMin = 0f
        }
        val yRange = if (yMax > yMin) yMax - yMin else 10f

        yAxisLabelTextPaint.textAlign = Paint.Align.RIGHT
        val gridLinePaint = Paint(baseLinePaint).apply {
            pathEffect = DashPathEffect(floatArrayOf(2f, 2f), 0f)
            strokeWidth = 0.4f
            alpha = 100 // Más sutiles
            color = Color.LTGRAY
        }

        // Etiquetas Eje Y y líneas de grid horizontales
        if (numYLabels > 0 && yRange > 0) {
            for (i in 0..numYLabels) {
                val value = yMin + (yRange / numYLabels) * i
                val yPixel = drawableGraphArea.bottom - ((value - yMin) / yRange) * drawableGraphArea.height()
                if (yPixel >= drawableGraphArea.top - MARGIN_VERY_SMALL && yPixel <= drawableGraphArea.bottom + MARGIN_VERY_SMALL) {
                    canvas.drawText(
                        String.format(Locale.US, "%.0f", value),
                        drawableGraphArea.left - MARGIN_VERY_SMALL * 1.5f,
                        yPixel + yAxisLabelTextPaint.textSize / 3f,
                        yAxisLabelTextPaint
                    )
                    if (i > 0 && i < numYLabels) { // Solo líneas de grid internas
                        if(yPixel > drawableGraphArea.top && yPixel < drawableGraphArea.bottom) {
                            canvas.drawLine(drawableGraphArea.left, yPixel, drawableGraphArea.right, yPixel, gridLinePaint)
                        }
                    }
                }
            }
        }

        val xAxisLabelTextPaint = TextPaint(baseTextPaint)
        xAxisLabelTextPaint.textAlign = Paint.Align.CENTER

        // Etiquetas Eje X y líneas de grid verticales
        // Mostrar etiquetas cada minuto: 00:00, 01:00, ..., 06:00
        val maxMinutesInGraph = ceil(maxTime.toDouble() / 60000.0).toInt().coerceAtLeast(1) // Al menos 1 minuto
        val numXLabelsToDraw = min(maxMinutesInGraph, 6) // Hasta 6 minutos (7 etiquetas: 0 a 6)

        if (timeRange >= 0) { // Permitir timeRange == 0 si solo hay un punto o es el inicio
            for (minuteMark in 0..numXLabelsToDraw) {
                val timeAtLabel = minuteMark * 60000L
                // No dibujar etiquetas mucho más allá del maxTime real de los datos, excepto la última etiqueta calculada si está cerca
                if (timeAtLabel > maxTime + 58000L && minuteMark > 0 && minuteMark < numXLabelsToDraw) continue


                val xPixel = drawableGraphArea.left + (if (timeRange > 0) ((timeAtLabel - minTime).toFloat() / timeRange.toFloat()) else 0.5f) * drawableGraphArea.width()


                // Asegurar que la etiqueta está dentro de los límites visuales del área de la gráfica
                if (xPixel >= drawableGraphArea.left - MARGIN_SMALL && xPixel <= drawableGraphArea.right + MARGIN_SMALL) {
                    canvas.drawText(
                        formatTimeAxisLabel(timeAtLabel),
                        xPixel,
                        drawableGraphArea.bottom + xAxisLabelHeight - xAxisLabelTextPaint.descent() - MARGIN_VERY_SMALL/2, // Ajustar bien el Y
                        xAxisLabelTextPaint
                    )
                    // Líneas de grid verticales (excepto en el borde izquierdo y derecho ya dibujados)
                    if (minuteMark > 0 && timeAtLabel < maxTime + 30000L ) { // No dibujar la última línea de grid si coincide con el borde
                        if(xPixel > drawableGraphArea.left && xPixel < drawableGraphArea.right -1f) { // -1f para no sobrepintar borde
                            canvas.drawLine(xPixel, drawableGraphArea.top, xPixel, drawableGraphArea.bottom, gridLinePaint)
                        }
                    }
                }
            }
        }


        // Dibujar datos (línea y puntos)
        graphDataPaint.color = dataColor
        graphDataPaint.style = Paint.Style.STROKE
        graphDataPaint.strokeWidth = 1.5f // Un poco más gruesa la línea de datos
        val pointPaint = Paint(graphDataPaint).apply {
            style = Paint.Style.FILL
            color = dataColor
        }
        val path = Path()
        var firstPointProcessed = false

        if (timeRange >= 0 && yRange > 0 && yRange > 0) { // Permitir timeRange == 0 si solo hay un dato para dibujar el punto
            dataPoints.sortedBy { it.timeMillis }.forEach { dataPoint ->
                val x = drawableGraphArea.left + (if (timeRange > 0) ((dataPoint.timeMillis - minTime).toFloat() / timeRange.toFloat()) else 0.5f) * drawableGraphArea.width()
                val yValue = dataPoint.value
                val y = drawableGraphArea.bottom - ((yValue - yMin) / yRange) * drawableGraphArea.height()

                // Coercer los puntos de la línea para que no se salgan dramáticamente del área, pero permitir que los puntos de datos se dibujen ligeramente fuera.
                val lineX = x.coerceIn(drawableGraphArea.left, drawableGraphArea.right)
                val lineY = y.coerceIn(drawableGraphArea.top, drawableGraphArea.bottom)

                if (!firstPointProcessed) {
                    path.moveTo(lineX, lineY)
                    firstPointProcessed = true
                } else {
                    path.lineTo(lineX, lineY)
                }
                // Dibujar puntos solo si están (casi) dentro del área de datos visible, pueden estar un poco fuera.
                if (x >= drawableGraphArea.left - GRAPH_POINT_RADIUS*2 && x <= drawableGraphArea.right + GRAPH_POINT_RADIUS*2 &&
                    y >= drawableGraphArea.top - GRAPH_POINT_RADIUS*2 && y <= drawableGraphArea.bottom + GRAPH_POINT_RADIUS*2) {
                    canvas.drawCircle(x, y, GRAPH_POINT_RADIUS, pointPaint)
                }
            }
        }
        canvas.drawPath(path, graphDataPaint)
    }
}
