package com.example.app6mwt.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.example.app6mwt.ui.PruebaCompletaDetalles

/**
 * Contiene conversores de tipo para Room, permitiendo almacenar objetos complejos
 * que Room no puede manejar nativamente (como `PruebaCompletaDetalles`).
 * En este caso, utiliza Gson para convertir el objeto a una cadena JSON y viceversa.
 */
class DataConverter {
    // Instancia de Gson para realizar las conversiones JSON.
    // Es buena práctica reutilizar la instancia de Gson.
    private val gson = Gson()

    /**
     * Convierte un objeto `PruebaCompletaDetalles` a su representación en cadena JSON.
     * Room usará este método para almacenar el objeto en una columna de tipo TEXT.
     * @param value El objeto `PruebaCompletaDetalles` a convertir. Puede ser nulo.
     * @return Una cadena JSON representando el objeto, o null si el objeto de entrada es nulo.
     */
    @TypeConverter
    fun fromPruebaCompletaDetalles(value: PruebaCompletaDetalles?): String? {
        // Si el valor es nulo, devuelve nulo directamente.
        // Si no es nulo, usa gson.toJson() para convertir el objeto a una cadena JSON.
        return value?.let { gson.toJson(it) }
    }

    /**
     * Convierte una cadena JSON de vuelta a un objeto `PruebaCompletaDetalles`.
     * Room usará este método cuando lea desde la base de datos.
     * @param value La cadena JSON a convertir. Puede ser nula.
     * @return Un objeto `PruebaCompletaDetalles`, o null si la cadena de entrada es nula o inválida.
     */
    @TypeConverter
    fun toPruebaCompletaDetalles(value: String?): PruebaCompletaDetalles? {
        // Si la cadena es nula, devuelve nulo directamente.
        // Si no es nula, usa gson.fromJson() para convertir la cadena JSON al objeto.
        return value?.let { gson.fromJson(it, PruebaCompletaDetalles::class.java) }
    }


    // --- NOTA IMPORTANTE ---
    // Si dentro de PruebaCompletaDetalles o sus componentes internos (como TestExecutionSummaryData)
    // existieran tipos genéricos complejos (ej. List<ObjetoNoEstándar>) que Gson no pueda inferir
    // directamente durante la deserialización, se necesitaría usar TypeToken.
    // Por ejemplo:
    /*
    @TypeConverter
    fun fromMiListaDeObjetos(value: List<MiObjetoPersonalizado>?): String? {
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toMiListaDeObjetos(value: String?): List<MiObjetoPersonalizado>? {
        return value?.let {
            // Se define el tipo exacto de la lista genérica para que Gson pueda deserializarla correctamente.
            val listType = object : TypeToken<List<MiObjetoPersonalizado>>() {}.type
            gson.fromJson(it, listType)
        }
    }
    */
    // Sin embargo, para estructuras de datos compuestas por data classes y listas/mapas de tipos estándar
    // o de esas mismas data classes (como parece ser el caso de PruebaCompletaDetalles),
    // Gson generalmente maneja la serialización/deserialización recursiva de manera adecuada
    // sin necesidad de TypeTokens explícitos en el TypeConverter del objeto raíz.
    // El TypeConverter actual se centra en convertir PruebaCompletaDetalles <-> String,
    // y Gson se encarga de la estructura interna de PruebaCompletaDetalles.
}
