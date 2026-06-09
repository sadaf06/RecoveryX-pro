package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.repository.DatabaseRepository
import com.example.data.model.FieldPermissions
import com.example.data.model.UserRole
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class PermissionsViewModel(private val repository: DatabaseRepository) : ViewModel() {
    val normalUserPermissions = repository.getPermissions(UserRole.NORMAL_USER)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            val exist = repository.getPermissionsSync(UserRole.NORMAL_USER)
            if (exist == null) {
                repository.insertPermissions(FieldPermissions(role = UserRole.NORMAL_USER))
            }
        }
    }

    fun updatePermissions(permissions: FieldPermissions) {
        viewModelScope.launch {
            repository.insertPermissions(permissions)
        }
    }

    class Factory(private val repository: DatabaseRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PermissionsViewModel(repository) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(repository: DatabaseRepository, onBack: () -> Unit) {
    val viewModel: PermissionsViewModel = viewModel(factory = PermissionsViewModel.Factory(repository))
    val perms by viewModel.normalUserPermissions.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Normal User Permissions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp).fillMaxSize()) {
            Text("Select which fields a Normal User can view:", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))

            perms?.let { p ->
                PermissionSwitch("Customer Name", p.showCustomerName) { viewModel.updatePermissions(p.copy(showCustomerName = it)) }
                PermissionSwitch("Vehicle Number", p.showVehicleNumber) { viewModel.updatePermissions(p.copy(showVehicleNumber = it)) }
                PermissionSwitch("Bank Name", p.showBankName) { viewModel.updatePermissions(p.copy(showBankName = it)) }
                PermissionSwitch("POS", p.showPos) { viewModel.updatePermissions(p.copy(showPos = it)) }
                PermissionSwitch("EMI", p.showEmi) { viewModel.updatePermissions(p.copy(showEmi = it)) }
                PermissionSwitch("Engine Number", p.showEngineNumber) { viewModel.updatePermissions(p.copy(showEngineNumber = it)) }
                PermissionSwitch("Chassis Number", p.showChassisNumber) { viewModel.updatePermissions(p.copy(showChassisNumber = it)) }
                PermissionSwitch("Confirmer Name", p.showConfirmerName) { viewModel.updatePermissions(p.copy(showConfirmerName = it)) }
            }
        }
    }
}

@Composable
fun PermissionSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
