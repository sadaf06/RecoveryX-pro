package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    private val vehicleNumber: String,
    private val repository: DatabaseRepository
) : ViewModel() {

    val vehicles = repository.getVehiclesByNumber(vehicleNumber)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _permissions = MutableStateFlow<FieldPermissions?>(null)
    val permissions = _permissions.asStateFlow()

    init {
        val user = AuthManager.currentUser.value
        if (user?.role == UserRole.NORMAL_USER) {
            val adminMobile = user.creatorMobile.ifEmpty { "admin" }
            viewModelScope.launch {
                try {
                    repository.syncPermissionsFromFirestore(adminMobile)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                repository.getPermissions(UserRole.NORMAL_USER, adminMobile).collect {
                    _permissions.value = it
                }
            }
        }
    }

    class Factory(private val vehicleNumber: String, private val repository: DatabaseRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = VehicleDetailsViewModel(vehicleNumber, repository) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleDetailsScreen(vehicleNumber: String, repository: DatabaseRepository, onBack: () -> Unit) {
    val viewModel: VehicleDetailsViewModel = viewModel(factory = VehicleDetailsViewModel.Factory(vehicleNumber, repository))
    val vehicles by viewModel.vehicles.collectAsStateWithLifecycle()
    var selectedVehicleIndex by remember { mutableStateOf(0) }
    val perms by viewModel.permissions.collectAsStateWithLifecycle()
    val user = AuthManager.currentUser.value
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0B10), // Deep charcoal background
                        Color(0xFF12131A)  // Dark space charcoal
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
                        Text(
                            text = "VEHICLE DETAILS",
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
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                if (vehicles.isNotEmpty()) {
                    val v = vehicles.getOrNull(selectedVehicleIndex) ?: vehicles.first()
                    // If multiple files have this vehicle, show a picker
                    if (vehicles.size > 1) {
                        Text(
                            text = "AVAILABLE IN MULTIPLE FILES:",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFA1A8B8),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            items(vehicles.size) { index ->
                                val doc = vehicles[index]
                                val isSelected = index == selectedVehicleIndex
                                Surface(
                                    modifier = Modifier.clickable { selectedVehicleIndex = index },
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                    color = if (isSelected) Color(0xFF4F7CFF) else Color(0x17FFFFFF),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) Color(0xFF4F7CFF) else Color(0x33FFFFFF))
                                ) {
                                    Text(
                                        text = doc.fileName.ifEmpty { "FILE ${index + 1}" },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSelected) Color.White else Color(0xFFA1A8B8),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }

                    val isNormal = user?.role == UserRole.NORMAL_USER
                    val p = perms
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    // Vehicle Profile Header
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x17FFFFFF)),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Brush.linearGradient(colors = listOf(Color(0x26FFFFFF), Color(0x05FFFFFF)))
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        brush = Brush.linearGradient(
                                            colors = listOf(Color(0x264F7CFF), Color(0x0D4F7CFF))
                                        ),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "STATUS: ${v.status.uppercase()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4F7CFF),
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.5.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (!isNormal || p?.showVehicleNumber == true) v.vehicleNumber else "HIDDEN",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                letterSpacing = 2.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = v.model.ifEmpty { "Model N/A" }.uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF4FD1FF),
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = "CORE SPECIFICATIONS",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFA1A8B8),
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x17FFFFFF)),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Brush.linearGradient(colors = listOf(Color(0x26FFFFFF), Color(0x05FFFFFF)))
                        )
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            if (!isNormal || p?.showEngineNumber == true) {
                                DetailRow("Engine No.", v.engineNumber, true)
                            }
                            if (!isNormal || p?.showChassisNumber == true) {
                                DetailRow("Chassis No.", v.chassisNumber, false)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = "LOAN & OWNERSHIP",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFA1A8B8),
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x17FFFFFF)),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Brush.linearGradient(colors = listOf(Color(0x26FFFFFF), Color(0x05FFFFFF)))
                        )
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            if (!isNormal || p?.showLoanNo == true) {
                                DetailRow("Loan No.", v.loanNo.ifEmpty { "N/A" }, true)
                            }
                            if (!isNormal || p?.showCustomerName == true) {
                                DetailRow("Customer", v.customerName, true)
                            }
                            if (!isNormal || p?.showPos == true) {
                                DetailRow("POS", v.pos, true)
                            }
                            if (!isNormal || p?.showEmi == true) {
                                DetailRow("EMI", v.emi, true)
                            }
                            if (!isNormal || p?.showBankName == true) {
                                DetailRow("Bank Name", v.bankName, true)
                            }
                            if (!isNormal || p?.showConfirmerName == true) {
                                DetailRow("Confirmer", v.confirmerName, !isNormal || p?.showBucket == true || p?.showFileName == true)
                            }
                            if (!isNormal || p?.showBucket == true) {
                                DetailRow("Bucket", v.bucket.ifEmpty { "Default Bucket" }, !isNormal || p?.showFileName == true)
                            }
                            if (!isNormal || p?.showFileName == true) {
                                DetailRow("File Name", v.fileName.ifEmpty { "N/A" }, false)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp), 
                        modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
                    ) {
                        Button(
                            onClick = { Toast.makeText(context, "Exporting to PDF...", Toast.LENGTH_SHORT).show() }, 
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f))
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("PDF", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Button(
                            onClick = { Toast.makeText(context, "Exporting to Excel...", Toast.LENGTH_SHORT).show() }, 
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent
                            ),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        brush = Brush.linearGradient(
                                            colors = listOf(Color(0xFF4F7CFF), Color(0xFF7B61FF))
                                        ),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.TableChart, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("EXCEL", fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 1.sp)
                                }
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(top = 100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF4F7CFF))
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, showDivider: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(), 
                style = MaterialTheme.typography.labelSmall, 
                color = Color(0xFFA1A8B8),
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = value.ifEmpty { "-" }, 
                style = MaterialTheme.typography.bodyMedium, 
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        }
        if (showDivider) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
        }
    }
}
