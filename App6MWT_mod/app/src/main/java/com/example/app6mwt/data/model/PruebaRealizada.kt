package com.example.app6mwt.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.example.app6mwt.data.local.DataConverter
import com.example.app6mwt.ui.PruebaCompletaDetalles

/**
 * Representa la entidad 'PruebaRealizada' en la base de datos Room.
 * Cada instancia corresponde a una prueba de marcha de 6 minutos realizada por un paciente.
 *
 * Está vinculada a la entidad `Paciente` mediante una clave foránea.
 *
 * @property pruebaId Identificador único de la prueba (clave primaria, autogenerada).
 * @property pacienteId ID del paciente que realizó la prueba (clave foránea).
 * @property fechaTimestamp Marca de tiempo (en milisegundos) de cuándo se realizó la prueba.
 * @property numeroPruebaPaciente Contador del número de prueba para ese paciente específico (ej. 1ª prueba, 2ª prueba).
 * @property distanciaRecorrida Distancia total recorrida por el paciente en metros.
 * @property porcentajeTeorico Porcentaje de la distancia recorrida respecto a un valor teórico esperado.
 * @property spo2min El valor mínimo de SpO2 registrado durante la prueba.
 * @property stops Número de veces que el paciente se detuvo durante la prueba.
 * @property datosCompletos Objeto que contiene todos los detalles y datos recogidos durante la
 *                          ejecución de la prueba. Se almacena como JSON usando un `TypeConverter`.
 */
@Entity(
    tableName = "pruebas_realizadas", // Nombre de la tabla en la base de datos
    foreignKeys = [ForeignKey( // Define una clave foránea
        entity = Paciente::class, // La entidad padre
        parentColumns = ["id"], // La columna en la entidad padre (tabla 'pacientes')
        childColumns = ["pacienteId"], // La columna en esta entidad (tabla 'pruebas_realizadas')
        onDelete = ForeignKey.CASCADE // Acción a tomar si el Paciente referenciado es eliminado: eliminar en cascada todas las pruebas asociadas a ese paciente.
    )],
    indices = [Index(value = ["pacienteId"])] // Crea un índice en la columna 'pacienteId' para optimizar las consultas que filtran por este campo.
)

@TypeConverters(DataConverter::class) // Especifica que se usará DataConverter para el campo 'datosCompletos'
data class PruebaRealizada(
    @PrimaryKey(autoGenerate = true) // Clave primaria, Room la generará automáticamente
    val pruebaId: Int = 0, // Valor por defecto 0 para nuevas inserciones
    val pacienteId: String,
    val fechaTimestamp: Long,
    val numeroPruebaPaciente: Int,
    val distanciaRecorrida: Float,
    val porcentajeTeorico: Float,
    val spo2min: Int,
    val stops: Int,
    val datosCompletos: PruebaCompletaDetalles? // Puede ser nulo si no se guardan todos los detalles
)
