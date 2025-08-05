package com.example.app6mwt.util

import android.annotation.SuppressLint
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateTimeFormatterUtil {

    // Formato para mostrar al usuario, ej: "15/07/2023 14:30:55"
    @SuppressLint("NewApi")
    private val userFriendlyDateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    // --- NUEVO: Formato solo para fecha ---
    @SuppressLint("NewApi")
    private val userFriendlyDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault())

    // --- NUEVO: Formato solo para hora ---
    @SuppressLint("NewApi")
    private val userFriendlyTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())

    // Formato para nombres de archivo, ej: "20230715_143055"
    @SuppressLint("NewApi")
    private val fileFriendlyFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.getDefault())

    @SuppressLint("NewApi")
    fun formatMillisToDateTimeUserFriendly(timestampMillis: Long): String {
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMillis), ZoneId.systemDefault())
        return dateTime.format(userFriendlyDateTimeFormatter) // Cambiado el nombre del formateador para claridad
    }

    // --- NUEVA FUNCIÓN ---
    @SuppressLint("NewApi")
    fun formatMillisToDateUserFriendly(timestampMillis: Long): String {
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMillis), ZoneId.systemDefault())
        return dateTime.format(userFriendlyDateFormatter)
    }

    // --- NUEVA FUNCIÓN ---
    @SuppressLint("NewApi")
    fun formatMillisToTimeUserFriendly(timestampMillis: Long): String {
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMillis), ZoneId.systemDefault())
        return dateTime.format(userFriendlyTimeFormatter)
    }

    @SuppressLint("NewApi")
    fun formatMillisToDateTimeForFile(timestampMillis: Long): String {
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMillis), ZoneId.systemDefault())
        return dateTime.format(fileFriendlyFormatter)
    }
}
