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
    try {
        com.google.firebase.FirebaseApp.initializeApp(this)
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Firebase initialization failed", e)
    }
    System.setProperty("org.apache.poi.util.POILogger", "org.apache.poi.util.NullLogger")
    System.setProperty("log4j2.formatMsgNoLookups", "true")
    System.setProperty("log4j2.disable.jmx", "true")
    enableEdgeToEdge()
    // Restore in-memory session after process death so Search/Admin don't show Hello User
    try { com.example.logic.AuthManager.restoreFromPrefs(applicationContext) } catch (e: Exception) { e.printStackTrace() }

    val db = Room.databaseBuilder(
        applicationContext,
        AppDatabase::class.java, "vehicle-database"
    ).addMigrations(com.example.data.MIGRATION_7_8).fallbackToDestructiveMigration().build()
    
    val firestoreSyncManager = com.example.data.repository.FirestoreSyncManager()
    val repository = DatabaseRepository(db.userDao(), db.vehicleDao(), db.fieldPermissionsDao(), db.searchHistoryDao(), firestoreSyncManager)

    setContent {
      MyApplicationTheme {
        // Initialize default admin
        androidx.lifecycle.viewmodel.compose.viewModel<com.example.ui.viewmodels.MainViewModel>(
            factory = com.example.ui.viewmodels.MainViewModel.Factory(repository)
        )

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val navController = rememberNavController()
            val context = androidx.compose.ui.platform.LocalContext.current

            // Resume guard: next-day / background->foreground pe in-memory session blank ho to restore karo.
            // Warna nav restored Search pe atak kar Hello User + dead search dikhata hai.
            androidx.compose.runtime.DisposableEffect(Unit) {
                val lifecycle = (context as androidx.lifecycle.LifecycleOwner).lifecycle
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        try {
                            if (com.example.logic.AuthManager.currentUser.value == null) {
                                com.example.logic.AuthManager.restoreFromPrefs(context)
                            }
                            val restored = com.example.logic.AuthManager.currentUser.value
                            val route = navController.currentDestination?.route
                            val isLoginDest = route?.contains("Login", ignoreCase = true) == true
                            if (restored == null && !isLoginDest) {
                                try {
                                    navController.navigate(com.example.ui.navigation.LoginRoute) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                            // X days expired -> clear and force login (online only check already in LoginScreen)
                            if (restored != null && com.example.logic.AuthManager.isSessionExpired(context)) {
                                com.example.logic.AuthManager.logout(context)
                                navController.navigate(com.example.ui.navigation.LoginRoute) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            // Auto-logout mid-session: recharge beech me expire ho to turant login pe bhejo
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(5 * 60 * 1000L)
                    try {
                        val u = com.example.logic.AuthManager.currentUser.value
                        if (u != null) {
                            val ok = try {
                                com.example.logic.SubscriptionGate.check(repository, u).ok
                            } catch (e: Exception) {
                                e.printStackTrace()
                                true
                            }
                            if (!ok) {
                                com.example.logic.AuthManager.logout(context)
                                navController.navigate(com.example.ui.navigation.LoginRoute) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

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
                        onNavigateToDetails = { number -> navController.navigate(VehicleDetailsRoute(number)) },
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
                        vehicleNumber = route.vehicleNumber,
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
