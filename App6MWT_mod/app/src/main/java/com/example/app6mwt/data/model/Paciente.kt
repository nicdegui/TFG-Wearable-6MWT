package com.example.app6mwt.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Representa la entidad 'Paciente' en la base de datos Room.
 * Cada instancia de esta clase corresponde a una fila en la tabla 'pacientes'.
 *
 * @property id Identificador único del paciente (clave primaria).
 * @property nombre Nombre completo del paciente.
 * @property tieneHistorial Indica si el paciente tiene al menos una prueba realizada registrada.
 *                         Este campo puede usarse para optimizar consultas o UI, aunque el estado
 *                         real del historial también se puede derivar contando las pruebas.
 * @property ultimoAccesoTimestamp Marca de tiempo (en milisegundos) del último acceso o
 *                                 modificación del paciente. Se utiliza para ordenar
 *                                 los pacientes por actividad reciente. Se inicializa con
 *                                 la hora actual por defecto.
 */
@Entity(tableName = "pacientes") // Define el nombre de la tabla en la base de datos
data class Paciente(
    @PrimaryKey val id: String, // Clave primaria de la tabla
    var nombre: String,
    var tieneHistorial: Boolean = false, // Valor por defecto es false
    var ultimoAccesoTimestamp: Long = System.currentTimeMillis() // Valor por defecto es el timestamp actual
)
