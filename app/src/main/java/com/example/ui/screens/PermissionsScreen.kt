package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    private val adminMobile = com.example.logic.AuthManager.currentUser.value?.mobile ?: "admin"
    private val key = "NORMAL_USER_$adminMobile"

    val normalUserPermissions = repository.getPermissions(UserRole.NORMAL_USER, adminMobile)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            val exist = repository.getPermissionsSync(UserRole.NORMAL_USER, adminMobile)
            if (exist == null) {
                repository.insertPermissions(FieldPermissions(
                    roleString = key,
                    role = UserRole.NORMAL_USER
                ))
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A0B10), Color(0xFF12131A))
                )
            )
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(colors = listOf(Color(0x334F7CFF), Color.Transparent)),
                    radius = size.width * 1.0f,
                    center = Offset(x = size.width * 0.1f, y = size.height * 0.1f)
                )
                drawCircle(
                    brush = Brush.radialGradient(colors = listOf(Color(0x1F7B61FF), Color.Transparent)),
                    radius = size.width * 0.9f,
                    center = Offset(x = size.width * 0.9f, y = size.height * 0.8f)
                )
            }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { 
                        Text(
                            text = "SECURITY CLEARANCE",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp,
                            color = Color.White
                        ) 
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0x3B070A13),
                        titleContentColor = Color.White
                    )
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "NORMAL USER DATALINK VISIBILITY",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF4FD1FF),
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Customize the fields available to Normal User accounts in their client view.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA1A8B8),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                perms?.let { p ->
                    item { PermissionSwitch("CUSTOMER NAME", p.showCustomerName) { viewModel.updatePermissions(p.copy(showCustomerName = it)) } }
                    item { PermissionSwitch("VEHICLE NUMBER", p.showVehicleNumber) { viewModel.updatePermissions(p.copy(showVehicleNumber = it)) } }
                    item { PermissionSwitch("BANK NAME", p.showBankName) { viewModel.updatePermissions(p.copy(showBankName = it)) } }
                    item { PermissionSwitch("POS", p.showPos) { viewModel.updatePermissions(p.copy(showPos = it)) } }
                    item { PermissionSwitch("EMI", p.showEmi) { viewModel.updatePermissions(p.copy(showEmi = it)) } }
                    item { PermissionSwitch("ENGINE NUMBER", p.showEngineNumber) { viewModel.updatePermissions(p.copy(showEngineNumber = it)) } }
                    item { PermissionSwitch("CHASSIS NUMBER", p.showChassisNumber) { viewModel.updatePermissions(p.copy(showChassisNumber = it)) } }
                    item { PermissionSwitch("CONFIRMER NAME", p.showConfirmerName) { viewModel.updatePermissions(p.copy(showConfirmerName = it)) } }
                    item { PermissionSwitch("LOAN NO", p.showLoanNo) { viewModel.updatePermissions(p.copy(showLoanNo = it)) } }
                    item { PermissionSwitch("BUCKET", p.showBucket) { viewModel.updatePermissions(p.copy(showBucket = it)) } }
                    item { PermissionSwitch("FILE NAME", p.showFileName) { viewModel.updatePermissions(p.copy(showFileName = it)) } }
                }
            }
        }
    }
}

@Composable
fun PermissionSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x17FFFFFF)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1AFFFFFF))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label, 
                style = MaterialTheme.typography.bodyMedium, 
                fontWeight = FontWeight.ExtraBold, 
                color = if (checked) Color.White else Color(0xFFA1A8B8),
                letterSpacing = 1.sp
            )
            Switch(
                checked = checked, 
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF4F7CFF),
                    uncheckedThumbColor = Color(0xFFA1A8B8),
                    uncheckedTrackColor = Color(0x33FFFFFF)
                )
            )
        }
    }
}
