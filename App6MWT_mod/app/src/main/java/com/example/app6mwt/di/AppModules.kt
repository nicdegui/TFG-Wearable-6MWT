package com.example.app6mwt.di

import android.content.Context
import com.example.app6mwt.data.PacienteRepository
import com.example.app6mwt.data.local.AppDatabase
import com.example.app6mwt.data.local.PacienteDao
import com.example.app6mwt.data.local.PruebaRealizadaDao
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Calificador personalizado para identificar un CoroutineDispatcher destinado a operaciones de IO.
 * Esto permite inyectar específicamente el Dispatchers.IO donde sea necesario,
 * diferenciándolo de otros dispatchers (como Dispatchers.Main o Dispatchers.Default).
 */
@Retention(AnnotationRetention.BINARY) // El calificador solo se necesita en tiempo de compilación
@Qualifier
annotation class IoDispatcher

/**
 * Módulo principal de Hilt para la aplicación.
 * Define cómo se proveen las dependencias con alcance de Singleton (a nivel de aplicación).
 * Estas dependencias estarán disponibles para ser inyectadas en cualquier parte de la app
 * donde Hilt esté configurado.
 */
@Module
@InstallIn(SingletonComponent::class) // Instala las dependencias de este módulo en el SingletonComponent
object AppModule {

    // --- SECCIÓN: DISPATCHERS DE COROUTINES ---
    /**
     * Provee una instancia de CoroutineDispatcher para operaciones de entrada/salida (IO).
     * Se marca como Singleton para que se reutilice la misma instancia.
     * Utiliza el calificador @IoDispatcher para que pueda ser inyectado específicamente.
     * @return Dispatchers.IO
     */
    @Provides
    @Singleton
    @IoDispatcher // Califica esta provisión
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    // --- SECCIÓN: SCOPES DE COROUTINES ---
    /**
     * Provee un CoroutineScope a nivel de aplicación.
     * Este scope utiliza un SupervisorJob (para que los fallos en corutinas hijas no cancelen todo el scope)
     * y el Dispatcher.IO (inyectado mediante @IoDispatcher).
     * Útil para lanzar corutinas que deben vivir mientras la aplicación exista y que realizan trabajo de fondo.
     * @param ioDispatcher El dispatcher de IO inyectado.
     * @return Un CoroutineScope configurado con SupervisorJob y Dispatchers.IO.
     */
    @Singleton
    @Provides
    fun providesApplicationCoroutineScope(
        @IoDispatcher ioDispatcher: CoroutineDispatcher // Hilt inyectará el dispatcher provisto por providesIoDispatcher()
    ): CoroutineScope {
        return CoroutineScope(SupervisorJob() + ioDispatcher)
    }

    // --- SECCIÓN: ROOM DATABASE Y DATA ACCESS OBJECTS (DAOs) ---
    /**
     * Provee la instancia Singleton de la base de datos Room (AppDatabase).
     * @param context Contexto de la aplicación, inyectado por Hilt.
     * @return La instancia de AppDatabase.
     */
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context // Hilt provee el contexto de la aplicación
    ): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    /**
     * Provee una instancia de PacienteDao.
     * Depende de AppDatabase, que Hilt sabrá cómo proveer.
     * No necesita ser @Singleton explícitamente si AppDatabase lo es y el DAO
     * no tiene estado propio (lo cual es típico para los DAOs de Room).
     * @param appDatabase La instancia de la base de datos.
     * @return Una instancia de PacienteDao.
     */
    @Provides
    fun providePacienteDao(appDatabase: AppDatabase): PacienteDao {
        return appDatabase.pacienteDao()
    }

    /**
     * Provee una instancia de PruebaRealizadaDao.
     * Depende de AppDatabase.
     * @param appDatabase La instancia de la base de datos.
     * @return Una instancia de PruebaRealizadaDao.
     */
    @Provides
    fun providePruebaRealizadaDao(appDatabase: AppDatabase): PruebaRealizadaDao {
        return appDatabase.pruebaRealizadaDao()
    }

    // --- SECCIÓN: REPOSITORIOS ---
    /**
     * Provee una instancia Singleton de PacienteRepository.
     * Depende de PacienteDao y PruebaRealizadaDao, que Hilt proveerá.
     * @param pacienteDao DAO para pacientes.
     * @param pruebaRealizadaDao DAO para pruebas realizadas.
     * @return Una instancia de PacienteRepository.
     */
    @Provides
    @Singleton
    fun providePacienteRepository(pacienteDao: PacienteDao, pruebaRealizadaDao: PruebaRealizadaDao): PacienteRepository {
        return PacienteRepository(pacienteDao, pruebaRealizadaDao)
    }

    // --- SECCIÓN: SERIALIZACIÓN (EJ. GSON) ---
    /**
     * Provee una instancia Singleton de Gson para serialización/deserialización JSON.
     * Útil para TypeConverters en Room o para comunicación con APIs web.
     * @return Una instancia de Gson.
     */
    @Provides
    @Singleton // Gson es thread-safe y se recomienda reutilizar la misma instancia.
    fun provideGson(): Gson {
        return Gson()
    }
}
