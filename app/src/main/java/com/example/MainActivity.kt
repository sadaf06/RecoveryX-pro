package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.room.Room
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.AppDatabase
import com.example.data.repository.DatabaseRepository
import com.example.ui.navigation.*
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val db = Room.databaseBuilder(
        applicationContext,
        AppDatabase::class.java, "vehicle-database"
    ).fallbackToDestructiveMigration().build()
    
    val firestoreSyncManager = try { com.example.data.repository.FirestoreSyncManager() } catch (e: Exception) { null }
    val repository = DatabaseRepository(db.userDao(), db.vehicleDao(), db.fieldPermissionsDao(), db.searchHistoryDao(), firestoreSyncManager)

    setContent {
      MyApplicationTheme {
        // Initialize default admin
        androidx.lifecycle.viewmodel.compose.viewModel<com.example.ui.viewmodels.MainViewModel>(
            factory = com.example.ui.viewmodels.MainViewModel.Factory(repository)
        )

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val navController = rememberNavController()

            NavHost(navController = navController, startDestination = LoginRoute) {
                composable<LoginRoute> {
                    LoginScreen(
                        repository = repository,
                        onNavigateToAdmin = {
                            navController.navigate(AdminDashboardRoute) {
                                popUpTo(LoginRoute) { inclusive = true }
                            }
                        },
                        onNavigateToSearch = {
                            navController.navigate(SearchRoute) {
                                popUpTo(LoginRoute) { inclusive = true }
                            }
                        }
                    )
                }

                composable<AdminDashboardRoute> {
                    AdminDashboardScreen(
                        repository = repository,
                        onNavigateToUsers = { navController.navigate(UserManagementRoute) },
                        onNavigateToPermissions = { navController.navigate(ManagePermissionsRoute) },
                        onNavigateToImport = { navController.navigate(ImportDataRoute) },
                        onNavigateToSearch = { navController.navigate(SearchRoute) },
                        onNavigateToHistory = { navController.navigate(HistoryRoute) },
                        onLogout = {
                            navController.navigate(LoginRoute) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    )
                }

                composable<UserManagementRoute> {
                    UserManagementScreen(repository = repository, onBack = { navController.popBackStack() })
                }

                composable<ManagePermissionsRoute> {
                    PermissionsScreen(repository = repository, onBack = { navController.popBackStack() })
                }

                composable<ImportDataRoute> {
                    ImportDataScreen(repository = repository, onBack = { navController.popBackStack() })
                }

                composable<HistoryRoute> {
                    HistoryScreen(repository = repository, onBack = { navController.popBackStack() })
                }

                composable<SearchRoute> {
                    SearchScreen(
                        repository = repository,
                        onNavigateToDetails = { id -> navController.navigate(VehicleDetailsRoute(id)) },
                        onBack = { navController.popBackStack() },
                        onLogout = {
                            navController.navigate(LoginRoute) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    )
                }

                composable<VehicleDetailsRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<VehicleDetailsRoute>()
                    VehicleDetailsScreen(
                        vehicleId = route.vehicleId,
                        repository = repository,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
      }
    }
  }
}
