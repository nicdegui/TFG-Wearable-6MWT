package com.example.app6mwt.ui

import android.net.Uri // Necesario para codificar/decodificar JSON
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.gson.Gson

/**
 * Objeto que define todas las rutas y nombres de argumentos para la navegación en la aplicación.
 * Centralizar estas constantes ayuda a evitar errores tipográficos y facilita el mantenimiento.
 */
object AppDestinations {
    // Ruta principal para navegar a la pantalla de gestión de pacientes
    const val PATIENT_MANAGEMENT_ROUTE = "patientManagement"

    // Nombres de los argumentos comunes que se pasan entre pantallas
    const val PATIENT_ID_ARG = "patientId" // ID del paciente
    const val PATIENT_NAME_ARG = "patientName" // Nombre del paciente (requiere codificación URI)
    const val PREPARATION_DATA_ARG = "preparationData" // Datos de preparación del test (serializados como JSON)
    const val TEST_FINAL_DATA_ARG = "testFinalData" // Datos resumidos de la ejecución del test (serializados como JSON)
    const val PATIENT_HAS_HISTORY_ARG = "patientHasHistory" // Booleano para indicar si el paciente tiene historial

    // --- Rutas y argumentos para PreparationScreen ---
    const val PREPARATION_SCREEN_BASE_ROUTE = "preparationScreen"
    // Ruta completa con argumentos obligatorios: patientId, patientName, patientHasHistory
    const val PREPARATION_SCREEN_ROUTE = "$PREPARATION_SCREEN_BASE_ROUTE/{$PATIENT_ID_ARG}/{$PATIENT_NAME_ARG}/{$PATIENT_HAS_HISTORY_ARG}"

    // --- Rutas y argumentos para TestExecutionScreen ---
    const val TEST_EXECUTION_BASE_ROUTE = "testExecution"
    // Ruta completa con argumento obligatorio: preparationData (JSON)
    const val TEST_EXECUTION_ROUTE = "$TEST_EXECUTION_BASE_ROUTE/{$PREPARATION_DATA_ARG}"

    // --- Rutas y argumentos para TestResultsScreen ---
    const val TEST_RESULTS_BASE_ROUTE = "testResults"
    // Ruta completa con argumento obligatorio (patientId) y un argumento opcional (testFinalData).
    // Los argumentos opcionales se definen con '?' y luego 'nombreArg={nombreArg}'.
    const val TEST_RESULTS_ROUTE = "$TEST_RESULTS_BASE_ROUTE/{$PATIENT_ID_ARG}?$TEST_FINAL_DATA_ARG={$TEST_FINAL_DATA_ARG}"

    // --- Rutas y argumentos para HistoryScreen ---
    const val HISTORY_SCREEN_BASE_ROUTE = "testHistoryScreen"
    // Ruta completa con argumento obligatorio: patientId
    const val HISTORY_SCREEN_ROUTE = "$HISTORY_SCREEN_BASE_ROUTE/{$PATIENT_ID_ARG}"
}

/**
 * Composable principal que configura el NavHost y define el grafo de navegación de la aplicación.
 *
 * @param modifier Modificador para este NavHost.
 * @param navController Controlador de navegación. Se crea y recuerda uno por defecto si no se provee.
 * @param startDestination La ruta del destino inicial cuando la app se lanza.
 * @param onExitApp Callback para manejar la acción de salir de la aplicación.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(), // Crea o reutiliza el NavController
    startDestination: String = AppDestinations.PATIENT_MANAGEMENT_ROUTE, // Destino inicial por defecto
    onExitApp: () -> Unit // Lambda para salir de la app, pasada desde MainActivity
) {
    // Instancia de Gson para serializar/deserializar objetos pasados como argumentos de navegación.
    // Se podría inyectar con Hilt si se usa en muchos lugares, pero aquí es local al NavHost.
    val gson = Gson()

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // --- Destino: PatientManagementScreen ---
        // Define la pantalla de gestión de pacientes.
        composable(route = AppDestinations.PATIENT_MANAGEMENT_ROUTE) {
            PatientManagementScreen(
                // Callback para navegar a la pantalla de preparación de un nuevo test.
                onNavigateToPreparation = { patientId, patientName, patientHasHistory ->
                    val encodedPatientName = Uri.encode(patientName) // Codifica el nombre para seguridad en URL.
                    // Construye la ruta reemplazando los placeholders con los valores reales.
                    val route = AppDestinations.PREPARATION_SCREEN_ROUTE
                        .replace("{${AppDestinations.PATIENT_ID_ARG}}", patientId)
                        .replace("{${AppDestinations.PATIENT_NAME_ARG}}", encodedPatientName)
                        .replace("{${AppDestinations.PATIENT_HAS_HISTORY_ARG}}", patientHasHistory.toString())
                    navController.navigate(route)
                },
                // Callback para navegar a la pantalla de historial de un paciente.
                onNavigateToHistory = { patientId ->
                    val route = AppDestinations.HISTORY_SCREEN_ROUTE
                        .replace("{${AppDestinations.PATIENT_ID_ARG}}", patientId)
                    navController.navigate(route)
                },
                onExitApp = onExitApp // Pasa el callback para salir de la app.
            )
        }

        // --- Destino: PreparationScreen ---
        // Define la pantalla de preparación del test.
        composable(
            route = AppDestinations.PREPARATION_SCREEN_ROUTE, // Ruta definida en AppDestinations
            arguments = listOf( // Define los argumentos que esta ruta espera y sus tipos.
                navArgument(AppDestinations.PATIENT_ID_ARG) { type = NavType.StringType },
                navArgument(AppDestinations.PATIENT_NAME_ARG) { type = NavType.StringType }, // El nombre se recibe codificado
                navArgument(AppDestinations.PATIENT_HAS_HISTORY_ARG) { type = NavType.BoolType }
            )
        ) { backStackEntry -> // backStackEntry permite acceder a los argumentos pasados.
            // Extrae los argumentos de la navegación.
            val patientId = backStackEntry.arguments?.getString(AppDestinations.PATIENT_ID_ARG)
            // Decodifica el nombre del paciente.
            val patientFullNameFromNav = backStackEntry.arguments?.getString(AppDestinations.PATIENT_NAME_ARG)?.let { Uri.decode(it) }
            val patientHasHistory = backStackEntry.arguments?.getBoolean(AppDestinations.PATIENT_HAS_HISTORY_ARG) ?: false

            // Comprueba que los argumentos obligatorios no sean nulos.
            if (patientId != null && patientFullNameFromNav != null) {
                PreparationScreen(
                    patientIdFromNav = patientId,
                    patientNameFromNav = patientFullNameFromNav,
                    patientHasHistoryFromNav = patientHasHistory,
                    onNavigateBack = { navController.popBackStack() }, // Navega hacia atrás.
                    // Callback para navegar a la pantalla de ejecución del test.
                    onNavigateToTestExecution = { preparationData ->
                        val preparationDataJson = gson.toJson(preparationData) // Serializa el objeto a JSON.
                        val encodedJson = Uri.encode(preparationDataJson) // Codifica el JSON para la URL.
                        val route = AppDestinations.TEST_EXECUTION_ROUTE
                            .replace("{${AppDestinations.PREPARATION_DATA_ARG}}", encodedJson)
                        navController.navigate(route)
                    }
                )
            } else {
                // Si faltan argumentos, muestra un error y navega hacia atrás.
                Text("Error: Faltan datos del paciente para la preparación.")
                LaunchedEffect(Unit) { // Ejecuta popBackStack después de la composición inicial.
                    navController.popBackStack()
                }
            }
        }

        // --- Destino: TestExecutionScreen ---
        // Define la pantalla de ejecución del test.
        composable(
            route = AppDestinations.TEST_EXECUTION_ROUTE,
            arguments = listOf(
                navArgument(AppDestinations.PREPARATION_DATA_ARG) { type = NavType.StringType } // Espera el JSON de preparationData
            )
        ) { backStackEntry ->
            val preparationDataJsonEncoded = backStackEntry.arguments?.getString(AppDestinations.PREPARATION_DATA_ARG)
            if (preparationDataJsonEncoded != null) {
                val preparationDataJson = Uri.decode(preparationDataJsonEncoded) // Decodifica el JSON.
                // Deserializa el JSON de vuelta al objeto TestPreparationData.
                // Es importante que TestPreparationData sea una clase conocida por Gson y serializable.
                val preparationData = gson.fromJson(preparationDataJson, TestPreparationData::class.java)
                TestExecutionScreen(
                    preparationData = preparationData,
                    onNavigateBackFromScreen = { navController.popBackStack() },
                    // Callback para navegar a la pantalla de resultados del test.
                    onNavigateToResults = { testExecutionSummaryData ->
                        val summaryDataJson = gson.toJson(testExecutionSummaryData) // Serializa a JSON.
                        val encodedSummaryDataJson = Uri.encode(summaryDataJson) // Codifica el JSON.
                        val route = AppDestinations.TEST_RESULTS_ROUTE
                            .replace("{${AppDestinations.PATIENT_ID_ARG}}", testExecutionSummaryData.patientId)
                            .replace("{${AppDestinations.TEST_FINAL_DATA_ARG}}", encodedSummaryDataJson) // El argumento opcional se reemplaza igual
                        navController.navigate(route)
                    }
                )
            } else {
                Text("Error: Faltan datos de preparación para la prueba.")
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }

        // --- Destino: TestResultsScreen ---
        // Define la pantalla de resultados del test.
        composable(
            route = AppDestinations.TEST_RESULTS_ROUTE,
            arguments = listOf(
                navArgument(AppDestinations.PATIENT_ID_ARG) { type = NavType.StringType }, // Argumento obligatorio
                navArgument(AppDestinations.TEST_FINAL_DATA_ARG) { // Argumento opcional
                    type = NavType.StringType
                    nullable = true // Indica que este argumento puede ser nulo (no estar presente en la ruta)
                    // defaultValue = null // Opcionalmente, se puede definir un valor por defecto.
                }
            )
        ) { backStackEntry ->
            // El patientId es obligatorio y siempre debería estar presente si se llega a esta ruta.
            val patientIdFromRoute = backStackEntry.arguments?.getString(AppDestinations.PATIENT_ID_ARG)
            // TEST_FINAL_DATA_ARG es opcional, por lo que su valor (o su ausencia)
            // será manejado por el ViewModel a través del SavedStateHandle.

            if (patientIdFromRoute != null) {
                // Obtiene el ViewModel usando Hilt. Hilt se encarga de proveer las dependencias
                // al ViewModel, incluyendo el SavedStateHandle que contendrá los argumentos de navegación.
                val resultsViewModel: TestResultsViewModel = hiltViewModel()
                TestResultsScreen(
                    viewModel = resultsViewModel, // Pasa el ViewModel a la pantalla.
                    onNavigateBack = { navController.popBackStack() },
                    // Callback para navegar de vuelta a la gestión de pacientes, limpiando el backstack.
                    onNavigateToPatientManagement = {
                        navController.navigate(AppDestinations.PATIENT_MANAGEMENT_ROUTE) {
                            // popUpTo borra destinos del backstack hasta PATIENT_MANAGEMENT_ROUTE.
                            // inclusive = true también borra PATIENT_MANAGEMENT_ROUTE antes de añadir la nueva instancia.
                            popUpTo(AppDestinations.PATIENT_MANAGEMENT_ROUTE) { inclusive = true }
                            launchSingleTop = true // Evita múltiples copias de la misma pantalla en el tope del stack.
                        }
                    }
                )
            } else {
                Text("Error: Falta ID del paciente para mostrar resultados.")
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }

        // --- Destino: TestHistoryScreen ---
        // Define la pantalla de historial de pruebas de un paciente.
        composable(
            route = AppDestinations.HISTORY_SCREEN_ROUTE,
            arguments = listOf(
                navArgument(AppDestinations.PATIENT_ID_ARG) { type = NavType.StringType }
            )
        ) { /* backStackEntry no se usa directamente aquí si el ViewModel maneja los argumentos */
            // El patientId (y cualquier otro argumento de la ruta) se pasa automáticamente
            // al SavedStateHandle del TestHistoryViewModel gracias a hiltViewModel().
            // El ViewModel es responsable de obtener el patientId del SavedStateHandle.
            TestHistoryScreen(
                // El TestHistoryViewModel se inyectará automáticamente dentro de TestHistoryScreen
                // usando hiltViewModel(), y este ViewModel obtendrá el patientId.
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
