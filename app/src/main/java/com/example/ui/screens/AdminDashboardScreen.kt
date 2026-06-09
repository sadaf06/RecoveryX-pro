package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.repository.DatabaseRepository
import com.example.logic.AuthManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import com.example.data.model.UserStatus

class AdminDashboardViewModel(private val repository: DatabaseRepository) : ViewModel() {
    val totalUsers = repository.allUsers.map { list ->
        val activeUser = AuthManager.currentUser.value
        if (activeUser?.mobile == "admin") {
            // Include everyone except Super Admin
            list.count { it.mobile != "admin" }
        } else {
            // Include only users under this admin
            list.count { it.creatorMobile == activeUser?.mobile }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalVehicles = repository.allVehicles.map { list ->
        val activeUser = AuthManager.currentUser.value
        if (activeUser?.mobile == "admin") {
            list.size
        } else {
            list.count { it.creatorMobile == activeUser?.mobile }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeUsers = repository.allUsers.map { list ->
        val activeUser = AuthManager.currentUser.value
        if (activeUser?.mobile == "admin") {
            list.count { it.status == UserStatus.ACTIVE && it.mobile != "admin" }
        } else {
            list.count { it.status == UserStatus.ACTIVE && it.creatorMobile == activeUser?.mobile }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    class Factory(private val repository: DatabaseRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AdminDashboardViewModel(repository) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    repository: DatabaseRepository,
    onNavigateToUsers: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToImport: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onLogout: () -> Unit
) {
    val viewModel: AdminDashboardViewModel = viewModel(factory = AdminDashboardViewModel.Factory(repository))
    val usersCount by viewModel.totalUsers.collectAsStateWithLifecycle()
    val vehiclesCount by viewModel.totalVehicles.collectAsStateWithLifecycle()
    val activeCount by viewModel.activeUsers.collectAsStateWithLifecycle()
    val currentUser by AuthManager.currentUser.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val screenTitle = if (currentUser?.mobile == "admin") {
        "Super Admin Dashboard"
    } else {
        "Admin Dashboard (${currentUser?.name ?: ""})"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle) },
                actions = {
                    IconButton(onClick = {
                        AuthManager.logout(context)
                        onLogout()
                    }) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Logout")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                DashboardCard("Connected Users", usersCount.toString(), Modifier.weight(1f))
                DashboardCard("Active Members", activeCount.toString(), Modifier.weight(1f))
            }
            DashboardCard("Tracked Vehicles", vehiclesCount.toString(), Modifier.fillMaxWidth())
            
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onNavigateToSearch,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Search Vehicles")
            }

            Button(
                onClick = onNavigateToUsers,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Manage Users & Admins")
            }
            
            Button(
                onClick = onNavigateToHistory,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text("Search & Use History")
            }
            
            Button(
                onClick = onNavigateToPermissions,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Text("Field Permissions (M3)")
            }

            Button(
                onClick = onNavigateToImport,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Text("Import Vehicle Data")
            }
        }
    }
}

@Composable
fun DashboardCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(115.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(value, style = MaterialTheme.typography.headlineLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
