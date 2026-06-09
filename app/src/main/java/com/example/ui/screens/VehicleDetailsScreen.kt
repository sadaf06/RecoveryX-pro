package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.repository.DatabaseRepository
import com.example.data.model.UserRole
import com.example.data.model.Vehicle
import com.example.data.model.FieldPermissions
import com.example.logic.AuthManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VehicleDetailsViewModel(
    private val vehicleId: Int,
    private val repository: DatabaseRepository
) : ViewModel() {

    val vehicle = repository.getVehicleById(vehicleId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _permissions = MutableStateFlow<FieldPermissions?>(null)
    val permissions = _permissions.asStateFlow()

    init {
        val user = AuthManager.currentUser.value
        if (user?.role == UserRole.NORMAL_USER) {
            viewModelScope.launch {
                repository.getPermissions(UserRole.NORMAL_USER).collect {
                    _permissions.value = it
                }
            }
        }
    }

    class Factory(private val vehicleId: Int, private val repository: DatabaseRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = VehicleDetailsViewModel(vehicleId, repository) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleDetailsScreen(vehicleId: Int, repository: DatabaseRepository, onBack: () -> Unit) {
    val viewModel: VehicleDetailsViewModel = viewModel(factory = VehicleDetailsViewModel.Factory(vehicleId, repository))
    val vehicle by viewModel.vehicle.collectAsStateWithLifecycle()
    val perms by viewModel.permissions.collectAsStateWithLifecycle()
    val user = AuthManager.currentUser.value
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vehicle Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            vehicle?.let { v ->
                val isNormal = user?.role == UserRole.NORMAL_USER
                val p = perms
                
                // Section 1: Vehicle Details
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Veh details:",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text("Status: ${v.status}") }
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                if (!isNormal || p?.showVehicleNumber == true) {
                    DetailRow("Veh no.", v.vehicleNumber)
                }
                DetailRow("Make model", v.model.ifEmpty { "N/A" })
                
                if (!isNormal || p?.showEngineNumber == true) {
                    DetailRow("Engine no.", v.engineNumber)
                }
                if (!isNormal || p?.showChassisNumber == true) {
                    DetailRow("Chasis number.", v.chassisNumber)
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                // Section 2: Other Details
                Text(
                    text = "Other details:",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                DetailRow("Loan no.", v.loanNo.ifEmpty { "N/A" })
                
                if (!isNormal || p?.showCustomerName == true) {
                    DetailRow("Customer name.", v.customerName)
                }
                if (!isNormal || p?.showPos == true) {
                    DetailRow("Pos", v.pos)
                }
                if (!isNormal || p?.showEmi == true) {
                    DetailRow("Emi", v.emi)
                }
                if (!isNormal || p?.showBankName == true) {
                    DetailRow("Bank name.", v.bankName)
                }
                if (!isNormal || p?.showConfirmerName == true) {
                    DetailRow("Confirmer name.", v.confirmerName)
                }

                Spacer(modifier = Modifier.height(32.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp), 
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                ) {
                    Button(onClick = { Toast.makeText(context, "Exporting to PDF...", Toast.LENGTH_SHORT).show() }, modifier = Modifier.weight(1f)) {
                        Text("Export PDF")
                    }
                    Button(onClick = { Toast.makeText(context, "Exporting to Excel...", Toast.LENGTH_SHORT).show() }, modifier = Modifier.weight(1f)) {
                        Text("Export Excel")
                    }
                }

            } ?: run {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
