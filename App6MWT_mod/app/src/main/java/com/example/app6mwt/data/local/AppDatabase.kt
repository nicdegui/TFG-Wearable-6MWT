package com.example.app6mwt.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.app6mwt.data.model.Paciente
import kotlinx.coroutines.Dispatchers
import com.example.app6mwt.data.model.PruebaRealizada
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Clase principal de la base de datos Room.
 * Define las entidades, la versión de la base de datos y proporciona acceso a los DAOs.
 * Utiliza un patrón Singleton para asegurar una única instancia de la base de datos.
 * Incluye un callback para poblar la base de datos con datos iniciales en su creación.
 */
@Database(entities = [Paciente::class, PruebaRealizada::class], // Lista de entidades que formarán parte de la BD
    version = 11, // Versión de la base de datos, se incrementa al cambiar el esquema
    exportSchema = false // No exportar el esquema a un archivo JSON (útil para migraciones complejas, pero no necesario aquí)
)
@TypeConverters(DataConverter::class) // Especifica la clase que contiene los TypeConverters para tipos complejos
abstract class AppDatabase : RoomDatabase() {

    // Métodos abstractos para obtener los Data Access Objects (DAOs)
    abstract fun pacienteDao(): PacienteDao
    abstract fun pruebaRealizadaDao(): PruebaRealizadaDao

    companion object {
        @Volatile // Asegura que la instancia sea visible inmediatamente para todos los hilos
        private var INSTANCE: AppDatabase? = null

        // CoroutineScope dedicado para operaciones de escritura en la base de datos
        // que se realizan durante el callback de creación.
        // SupervisorJob asegura que si una tarea hija falla, otras tareas o el propio scope no se cancelen.
        // Dispatchers.IO es adecuado para operaciones de disco.
        private val databaseWriteExecutor = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Obtiene la instancia Singleton de la base de datos.
         * Si la instancia no existe, la crea de forma segura para hilos (thread-safe).
         * @param context Contexto de la aplicación.
         * @return La instancia de AppDatabase.
         */
        fun getDatabase(
            context: Context
        ): AppDatabase {
            // Si INSTANCE no es nula, la devuelve.
            // Si es nula, entra en un bloque sincronizado para crear la instancia.
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext, // Usar el contexto de la aplicación para evitar memory leaks
                    AppDatabase::class.java,
                    "6mwt_app_database" // Nombre del archivo de la base de datos
                )
                    // Añade un callback que se ejecuta en diferentes momentos del ciclo de vida de la BD (ej. creación)
                    .addCallback(AppDatabaseCallback())
                    // Define la estrategia de migración si la versión de la BD cambia y no hay una migración explícita.
                    // fallbackToDestructiveMigration borra y recrea la BD (se pierden los datos).
                    // Para producción, se suelen preferir migraciones que conserven datos.
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance // Asigna la nueva instancia creada
                instance // Devuelve la instancia
            }
        }
    }

    /**
     * Callback para eventos de la base de datos, como su creación.
     * Utilizado aquí para poblar la base de datos con datos iniciales
     * la primera vez que se crea.
     */
    private class AppDatabaseCallback() : Callback() {
        /**
         * Se llama cuando la base de datos es creada por primera vez (después de que las tablas son creadas).
         * No se llama si la base de datos ya existe.
         * @param db La base de datos recién creada.
         */
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            Log.d("AppDatabaseCallback", "onCreate CALLED - Database schema will be created.)")
            // Lanza una corutina en el executor dedicado para realizar operaciones de IO (poblar la BD)
            databaseWriteExecutor.launch {
                Log.d("AppDatabaseCallback", "Coroutine launched for populateInitialData.")
                INSTANCE?.let { database -> // Accede a la instancia de la BD (debería estar ya disponible)
                    Log.d("AppDatabaseCallback", "INSTANCE found. Populating data...")
                    populateInitialData(database.pacienteDao()) // Llama al método para insertar datos iniciales
                } ?: run {
                    Log.e("AppDatabaseCallback", "INSTANCE was NULL in coroutine. Population FAILED.")
                }
            }
        }

        /**
         * Inserta datos iniciales (pacientes de ejemplo) en la tabla de pacientes.
         * Esta función es 'suspend' porque realiza operaciones de base de datos que son asíncronas.
         * @param pacienteDao El DAO para interactuar con la tabla de pacientes.
         */
        suspend fun populateInitialData(pacienteDao: PacienteDao) {
            Log.d("AppDatabaseCallback", "populateInitialData STARTING")
            try {
                // Lista de pacientes de ejemplo para poblar la base de datos
                val pacientes = listOf(
                    Paciente(
                        "1001",
                        "Ana Pérez García",
                        ultimoAccesoTimestamp = System.currentTimeMillis() - 90000000
                    ),
                    Paciente(
                        "1002",
                        "Marta Gómez Sánchez",
                        ultimoAccesoTimestamp = System.currentTimeMillis() - 70000000
                    ),
                    Paciente(
                        "1003",
                        "Elena Torres Vazquez",
                        ultimoAccesoTimestamp = System.currentTimeMillis() - 50000000
                    )
                )
                Log.d("AppDatabaseCallback", "Número de pacientes a insertar: ${pacientes.size}")

                // Itera sobre la lista e inserta cada paciente
                pacientes.forEach { paciente ->
                    Log.d(
                        "AppDatabaseCallback",
                        "Intentando insertar paciente: ID ${paciente.id} - Nombre ${paciente.nombre}"
                    )
                    try {
                        // Usa el DAO para insertar o actualizar el paciente.
                        // OnConflictStrategy.REPLACE (definido en el DAO) se encargará si ya existe.
                        pacienteDao.insertarOActualizarPaciente(paciente)
                        Log.d(
                            "AppDatabaseCallback",
                            "ÉXITO al insertar paciente: ID ${paciente.id}"
                        )
                    } catch (e: Exception) {
                        Log.e(
                            "AppDatabaseCallback",
                            "ERROR al insertar paciente: ID ${paciente.id}",
                            e
                        )
                    }
                }
                Log.d("AppDatabaseCallback", "Todos los pacientes procesados en el bucle.")
            } catch (e: Exception) {
                Log.e("AppDatabaseCallback", "ERROR GENERAL en populateInitialData", e)
            } finally {
                Log.d("AppDatabaseCallback", "populateInitialData FINISHED (bloque finally)")
            }
        }
    }
}
