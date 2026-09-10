package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()

    val isSuperAdmin = currentUser?.mobile == "admin"
    val screenTitle = if (isSuperAdmin) {
        "SUPER ADMIN CONSOLE"
    } else {
        "ADMIN CONSOLE"
    }
    
    var isLoggingOut by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0B10), 
                        Color(0xFF12131A)  
                    )
                )
            )
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x334F7CFF), 
                            Color.Transparent
                        )
                    ),
                    radius = size.width * 1.0f,
                    center = Offset(x = size.width * 0.1f, y = size.height * 0.1f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x1F7B61FF), 
                            Color.Transparent
                        )
                    ),
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
                        Column {
                            Text(
                                text = screenTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.5.sp,
                                color = Color.White
                            )
                            if (!isSuperAdmin) {
                                Text(
                                    text = "Operator: ${currentUser?.name ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFA1A8B8)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0x3B070A13),
                        titleContentColor = Color.White
                    ),
                    actions = {
                        IconButton(onClick = {
                            isLoggingOut = true
                            scope.launch {
                                try {
                                    repository.clearAllDownloadedVehicles()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                kotlinx.coroutines.delay(1000)
                                AuthManager.logout(context)
                                onLogout()
                            }
                        }) {
                            Icon(Icons.Default.ExitToApp, contentDescription = "Logout", tint = Color(0xFFFF5D73))
                        }
                    }
                )
            }
        ) { innerPadding ->
            if (isLoggingOut) {
                androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131929)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        modifier = Modifier.width(180.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = Color(0xFF4F7CFF),
                                strokeWidth = 4.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Clearing Cache...",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    DashboardMetricCard(
                        title = "Network Users",
                        value = usersCount.toString(),
                        subtitle = "Total provisioned",
                        icon = Icons.Default.People,
                        modifier = Modifier.weight(1f)
                    )
                    DashboardMetricCard(
                        title = "Active Sessions",
                        value = activeCount.toString(),
                        subtitle = "Currently active",
                        icon = Icons.Default.AdminPanelSettings,
                        modifier = Modifier.weight(1f),
                        accentColor = Color(0xFF4FD1FF)
                    )
                }
                DashboardMetricCard(
                    title = "Tracked Vehicles Database",
                    value = vehiclesCount.toString(),
                    subtitle = "Synched from Cloud Firestore",
                    icon = Icons.Default.DirectionsCar,
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = Color(0xFF7B61FF)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "CONSOLE ACTIONS",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFA1A8B8),
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                DashboardActionCard(
                    title = "Global Search Registry",
                    description = "Search and track database vehicles",
                    icon = Icons.Default.Search,
                    gradient = listOf(Color(0xFF4F7CFF), Color(0xFF7B61FF)),
                    onClick = onNavigateToSearch
                )

                DashboardActionCard(
                    title = "User & Admin Management",
                    description = "Provision access and update roles",
                    icon = Icons.Default.People,
                    gradient = listOf(Color(0xFFFFB547), Color(0xFFFF5D73)),
                    onClick = onNavigateToUsers
                )
                
                DashboardActionCard(
                    title = "System Queries History",
                    description = "Audit logs and search behavior",
                    icon = Icons.Default.History,
                    gradient = listOf(Color(0xFF29B6F6), Color(0xFF0288D1)),
                    onClick = onNavigateToHistory
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    DashboardActionCardSmall(
                        title = "Data Import",
                        icon = Icons.Default.UploadFile,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToImport
                    )
                    DashboardActionCardSmall(
                        title = "Permissions",
                        icon = Icons.Default.Security,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToPermissions
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun DashboardMetricCard(
    title: String, 
    value: String, 
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF4F7CFF)
) {
    Card(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x17FFFFFF)),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.linearGradient(colors = listOf(Color(0x26FFFFFF), Color(0x05FFFFFF)))
        )
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(accentColor.copy(alpha = 0.2f), shape = androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                }
                Text(
                    text = value, 
                    style = MaterialTheme.typography.headlineMedium, 
                    fontWeight = FontWeight.Black, 
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Color(0xFFA1A8B8))
        }
    }
}

@Composable
fun DashboardActionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    gradient: List<Color>,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(colors = gradient))
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.White.copy(alpha = 0.2f), shape = androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun DashboardActionCardSmall(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.height(90.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x17FFFFFF)),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.linearGradient(colors = listOf(Color(0x26FFFFFF), Color(0x05FFFFFF)))
        ),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFFA1A8B8), modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}
