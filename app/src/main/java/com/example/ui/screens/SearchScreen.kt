package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.repository.DatabaseRepository
import com.example.logic.AuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import com.example.data.model.Vehicle
import kotlinx.coroutines.launch

class SearchViewModel(private val repository: DatabaseRepository) : ViewModel() {
    private val _searchResults = MutableStateFlow<List<Vehicle>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _searchCriteria = MutableStateFlow(com.example.data.model.SearchCriteria.GENERAL)
    val searchCriteria = _searchCriteria.asStateFlow()

    private val _isOnlineMode = MutableStateFlow(false)
    val isOnlineMode = _isOnlineMode.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow("")
    val searchError = _searchError.asStateFlow()

    private var currentSearchJob: kotlinx.coroutines.Job? = null
    private var lastQuery = ""
    private var lastCreatorFilter: String? = null

    fun selectCriteria(criteria: com.example.data.model.SearchCriteria, creatorFilter: String? = lastCreatorFilter) {
        _searchCriteria.value = criteria
        search(lastQuery, criteria, creatorFilter)
    }

    fun toggleSearchMode(online: Boolean, creatorFilter: String? = lastCreatorFilter) {
        _isOnlineMode.value = false
        _searchError.value = ""
        search(lastQuery, _searchCriteria.value, creatorFilter)
    }

    fun search(query: String, criteria: com.example.data.model.SearchCriteria = _searchCriteria.value, creatorFilter: String? = lastCreatorFilter) {
        lastQuery = query
        lastCreatorFilter = creatorFilter
        currentSearchJob?.cancel()
        _searchError.value = ""
        
        // Suffix / prefix matches can start with 1 digit
        val minLength = if (criteria == com.example.data.model.SearchCriteria.GENERAL) 2 else 1
        
        if (query.length >= minLength) {
             currentSearchJob = viewModelScope.launch {
                 _isSearching.value = true
                 repository.searchVehicles(query, criteria, creatorFilter).collect { results ->
                     _searchResults.value = results.distinctBy { it.vehicleNumber.uppercase().replace("\\s+".toRegex(), "") }
                     _isSearching.value = false
                 }
             }
        } else {
             _searchResults.value = emptyList()
             _isSearching.value = false
         }
    }

    class Factory(private val repository: DatabaseRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(repository) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: DatabaseRepository,
    onNavigateToDetails: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    onLogout: () -> Unit
) {
    val viewModel: SearchViewModel = viewModel(factory = SearchViewModel.Factory(repository))
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val activeCriteria by viewModel.searchCriteria.collectAsStateWithLifecycle()
    val isOnlineMode by viewModel.isOnlineMode.collectAsStateWithLifecycle()
    val isSearchingDetail by viewModel.isSearching.collectAsStateWithLifecycle()
    val searchError by viewModel.searchError.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    val currentUser by AuthManager.currentUser.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var isSyncing by remember { mutableStateOf(false) }
    var hasNewDataPending by remember { mutableStateOf(false) }
    var isLoggingOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    val adminFilter = remember(currentUser) {
        when {
            currentUser?.mobile == "admin" -> null
            currentUser?.role == com.example.data.model.UserRole.ADMIN -> currentUser?.mobile
            else -> currentUser?.creatorMobile?.ifEmpty { "admin" } ?: "admin"
        }
    }

    val adminMobileForCount = remember(currentUser) {
        val cr = currentUser?.creatorMobile ?: "admin"
        if (cr.isEmpty()) "admin" else cr
    }

    val adminCaseCountFlow = remember(adminMobileForCount) {
        repository.countAllVehiclesByAdmin(adminMobileForCount)
    }
    val adminCaseCount by adminCaseCountFlow.collectAsStateWithLifecycle(initialValue = 0)

    LaunchedEffect(currentUser, isSyncing) {
        if (isSyncing) return@LaunchedEffect
        val prefs = context.getSharedPreferences("recoveryx_prefs", android.content.Context.MODE_PRIVATE)
        val lastDownloadTime = prefs.getLong("last_download_time", 0L)
        try {
            val files = repository.getFirestoreUploadedFiles(adminFilter)
            val maxUploadedAt = files.maxOfOrNull { it.uploadedAt } ?: 0L
            hasNewDataPending = maxUploadedAt > lastDownloadTime
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        androidx.compose.ui.graphics.Color(0xFF0A0B10), // Deep charcoal background
                        androidx.compose.ui.graphics.Color(0xFF12131A)  // Dark space charcoal
                    )
                )
            )
            .drawBehind {
                // Blur ambient lighting spot 1
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            androidx.compose.ui.graphics.Color(0x334F7CFF), 
                            androidx.compose.ui.graphics.Color.Transparent
                        )
                    ),
                    radius = size.width * 1.0f,
                    center = androidx.compose.ui.geometry.Offset(x = size.width * 0.1f, y = size.height * 0.1f)
                )
                // Blur ambient lighting spot 2
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            androidx.compose.ui.graphics.Color(0x1F7B61FF), 
                            androidx.compose.ui.graphics.Color.Transparent
                        )
                    ),
                    radius = size.width * 0.9f,
                    center = androidx.compose.ui.geometry.Offset(x = size.width * 0.9f, y = size.height * 0.8f)
                )
            }
    ) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent, // Clear background for Scaffold
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (!isSyncing) {
                            isSyncing = true
                            scope.launch {
                                try {
                                    val filter = when {
                                        currentUser?.mobile == "admin" -> null
                                        currentUser?.role == com.example.data.model.UserRole.ADMIN -> currentUser?.mobile
                                        else -> currentUser?.creatorMobile?.ifEmpty { "admin" } ?: "admin"
                                    }
                                    repository.forceSyncFromNetwork(filter)
                                    val prefs = context.getSharedPreferences("recoveryx_prefs", android.content.Context.MODE_PRIVATE)
                                    prefs.edit().putLong("last_download_time", System.currentTimeMillis()).apply()
                                    hasNewDataPending = false
                                    android.widget.Toast.makeText(context, "Local search database synchronized!", android.widget.Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Sync failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                } finally {
                                    isSyncing = false
                                }
                            }
                        }
                    },
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .height(52.dp)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = if (hasNewDataPending) {
                                    listOf(androidx.compose.ui.graphics.Color(0xFFFF5D73), androidx.compose.ui.graphics.Color(0xFFFFB547))
                                } else {
                                    listOf(androidx.compose.ui.graphics.Color(0xFF4F7CFF), androidx.compose.ui.graphics.Color(0xFF7B61FF))
                                }
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)
                        ),
                    icon = {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = androidx.compose.ui.graphics.Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Sync Database",
                                tint = androidx.compose.ui.graphics.Color.White
                            )
                        }
                    },
                    text = {
                        Text(
                            text = if (hasNewDataPending) "UPDATE READY" else "SYNC DATA",
                            fontWeight = FontWeight.ExtraBold,
                            color = androidx.compose.ui.graphics.Color.White,
                            letterSpacing = 0.5.sp
                        )
                    }
                )
            },
            topBar = {
                @OptIn(ExperimentalMaterial3Api::class)
                TopAppBar(
                    title = {
                        Text(
                            text = "SEARCH REGISTRY",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                    },
                    navigationIcon = {
                        if (onBack != null && currentUser?.role == com.example.data.model.UserRole.ADMIN) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = androidx.compose.ui.graphics.Color.White)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color(0x3B070A13), // Frosted glass translucent navy bar
                        titleContentColor = androidx.compose.ui.graphics.Color.White
                    ),
                    actions = {
                        IconButton(
                            onClick = {
                                if (!isSyncing) {
                                    isSyncing = true
                                    scope.launch {
                                        try {
                                            val filter = when {
                                                currentUser?.mobile == "admin" -> null
                                                currentUser?.role == com.example.data.model.UserRole.ADMIN -> currentUser?.mobile
                                                else -> currentUser?.creatorMobile?.ifEmpty { "admin" } ?: "admin"
                                            }
                                            repository.forceSyncFromNetwork(filter)
                                            val prefs = context.getSharedPreferences("recoveryx_prefs", android.content.Context.MODE_PRIVATE)
                                            prefs.edit().putLong("last_download_time", System.currentTimeMillis()).apply()
                                            hasNewDataPending = false
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        } finally {
                                            isSyncing = false
                                        }
                                    }
                                }
                            }
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = androidx.compose.ui.graphics.Color(0xFF4F7CFF)
                                )
                            } else {
                                Box(modifier = Modifier.size(24.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh, 
                                        contentDescription = "Sync from Cloud",
                                        modifier = Modifier.align(Alignment.Center),
                                        tint = if (hasNewDataPending) androidx.compose.ui.graphics.Color(0xFF4F7CFF) else androidx.compose.ui.graphics.Color.White
                                    )
                                    if (hasNewDataPending) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .align(Alignment.TopEnd)
                                                .background(androidx.compose.ui.graphics.Color(0xFFFF5D73), shape = CircleShape)
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
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
                            Icon(Icons.Default.ExitToApp, contentDescription = "Logout", tint = androidx.compose.ui.graphics.Color(0xFFFF5D73))
                        }
                    }
                )
            }
        ) { innerPadding ->
            if (isLoggingOut) {
                androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFF131929)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0x33FFFFFF)),
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
                                color = androidx.compose.ui.graphics.Color(0xFF4F7CFF),
                                strokeWidth = 4.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Clearing Cache...",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = androidx.compose.ui.graphics.Color.White
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {

                // Floating HUD Welcome Header
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = androidx.compose.ui.graphics.Color(0x17FFFFFF) // Transparent glass overlay
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(
                                androidx.compose.ui.graphics.Color(0x26FFFFFF),
                                androidx.compose.ui.graphics.Color(0x05FFFFFF)
                            )
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = listOf(
                                            androidx.compose.ui.graphics.Color(0xFF4F7CFF),
                                            androidx.compose.ui.graphics.Color(0xFF7B61FF)
                                        )
                                    ),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (currentUser?.name?.take(1) ?: "U").uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = androidx.compose.ui.graphics.Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Hello, ${currentUser?.name ?: "User"}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (currentUser?.role == com.example.data.model.UserRole.NORMAL_USER || 
                                    currentUser?.role == com.example.data.model.UserRole.OFFICE_STAFF) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = androidx.compose.ui.graphics.Color(0xFF4F7CFF).copy(alpha = 0.2f),
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                            )
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "Total Cases: ${adminCaseCount * 10}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = androidx.compose.ui.graphics.Color(0xFF4FD1FF)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val roleLabel = when (currentUser?.role) {
                                    com.example.data.model.UserRole.ADMIN -> "Administrator"
                                    com.example.data.model.UserRole.OFFICE_STAFF -> "Office Staff"
                                    com.example.data.model.UserRole.NORMAL_USER -> "Normal Agent"
                                    null -> "Guest"
                                }
                                Text(
                                    text = roleLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = androidx.compose.ui.graphics.Color(0xFF4FD1FF)
                                )
                                if (currentUser?.role != com.example.data.model.UserRole.ADMIN) {
                                    Text(
                                        text = "• Admin: ${currentUser?.creatorMobile?.ifEmpty { "admin" } ?: "admin"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = androidx.compose.ui.graphics.Color(0xFFA1A8B8)
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        viewModel.search(it, creatorFilter = adminFilter)
                    },
                    placeholder = {
                        val placeholderText = when (activeCriteria) {
                            com.example.data.model.SearchCriteria.GENERAL -> "Search Registration, Owner etc..."
                            com.example.data.model.SearchCriteria.ENGINE_LAST -> "Search matching last digit of Engine"
                            com.example.data.model.SearchCriteria.CHASSIS_LAST -> "Search matching last digit of Chassis"
                            com.example.data.model.SearchCriteria.LOAN_START -> "Search starting of Loan No"
                            com.example.data.model.SearchCriteria.VEHICLE_LAST -> "Search matching last digit of Vehicle No"
                        }
                        Text(placeholderText, color = androidx.compose.ui.graphics.Color(0x99A1A8B8))
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFFA1A8B8)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color(0x14FFFFFF),
                        focusedContainerColor = androidx.compose.ui.graphics.Color(0x1FFFFFFF),
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color(0x1AFFFFFF),
                        focusedBorderColor = androidx.compose.ui.graphics.Color(0xFF4F7CFF),
                        unfocusedTextColor = androidx.compose.ui.graphics.Color.White,
                        focusedTextColor = androidx.compose.ui.graphics.Color.White
                    )
                )

                val criteriaFilters = listOf(
                    com.example.data.model.SearchCriteria.GENERAL to "General",
                    com.example.data.model.SearchCriteria.ENGINE_LAST to "Engine Last Digit",
                    com.example.data.model.SearchCriteria.CHASSIS_LAST to "Chassis Last Digit",
                    com.example.data.model.SearchCriteria.LOAN_START to "Loan Start",
                    com.example.data.model.SearchCriteria.VEHICLE_LAST to "Vehicle Last Digit"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    criteriaFilters.forEach { (crit, label) ->
                        val isSelected = activeCriteria == crit
                        Box(
                            modifier = Modifier
                                .background(
                                    brush = if (isSelected) {
                                        androidx.compose.ui.graphics.Brush.linearGradient(
                                            colors = listOf(androidx.compose.ui.graphics.Color(0xFF4F7CFF), androidx.compose.ui.graphics.Color(0xFF7B61FF))
                                        )
                                    } else {
                                        androidx.compose.ui.graphics.Brush.linearGradient(
                                            colors = listOf(androidx.compose.ui.graphics.Color(0x14FFFFFF), androidx.compose.ui.graphics.Color(0x0AFFFFFF))
                                        )
                                    },
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                )
                                .clickable { viewModel.selectCriteria(crit, adminFilter) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                                color = if (isSelected) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFFA1A8B8)
                            )
                        }
                    }
                }

                if (isSearchingDetail) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        color = androidx.compose.ui.graphics.Color(0xFF4F7CFF)
                    )
                }

                if (searchError.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0x33FF5D73)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0x66FF5D73))
                    ) {
                        Text(
                            text = searchError,
                            color = androidx.compose.ui.graphics.Color(0xFFFF5D73),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results) { vehicle ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = androidx.compose.ui.graphics.Color(0x17FFFFFF)
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    colors = listOf(
                                        androidx.compose.ui.graphics.Color(0x26FFFFFF),
                                        androidx.compose.ui.graphics.Color(0x05FFFFFF)
                                    )
                                )
                            ),
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                scope.launch {
                                    try {
                                        val u = currentUser
                                        if (u != null) {
                                            val formatter = java.text.SimpleDateFormat(
                                                "dd-MMM-yyyy HH:mm:ss",
                                                java.util.Locale.getDefault()
                                            )
                                            val timeStr = formatter.format(java.util.Date())

                                            val historyLog = com.example.data.model.SearchHistory(
                                                userMobile = u.mobile,
                                                userName = u.name,
                                                vehicleNumber = vehicle.vehicleNumber,
                                                model = vehicle.model,
                                                timestamp = timeStr,
                                                creatorMobile = if (u.role == com.example.data.model.UserRole.ADMIN) u.mobile else u.creatorMobile.ifEmpty { "admin" }
                                            )
                                            repository.insertHistory(historyLog)
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                                onNavigateToDetails(vehicle.vehicleNumber)
                            }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                if (currentUser?.role != com.example.data.model.UserRole.NORMAL_USER) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                                    colors = listOf(
                                                        androidx.compose.ui.graphics.Color(0x264F7CFF),
                                                        androidx.compose.ui.graphics.Color(0x0D4F7CFF)
                                                    )
                                                ),
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = vehicle.bankName,
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = androidx.compose.ui.graphics.Color(0xFF4FD1FF),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                }

                                Text(
                                    text = vehicle.vehicleNumber,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = androidx.compose.ui.graphics.Color.White,
                                    maxLines = 1
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = vehicle.customerName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.ui.graphics.Color(0xFFA1A8B8),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )

                                if (vehicle.status.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .background(
                                                    color = androidx.compose.ui.graphics.Color(0xFF00C896),
                                                    shape = CircleShape
                                                )
                                        )
                                        Text(
                                            text = vehicle.status,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = androidx.compose.ui.graphics.Color(0xFF00C896),
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                    val minLength = if (activeCriteria == com.example.data.model.SearchCriteria.GENERAL) 2 else 1
                    if (results.isEmpty() && searchQuery.length >= minLength) {
                        item(span = { GridItemSpan(2) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No matching vehicles found.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = androidx.compose.ui.graphics.Color(0xFFA1A8B8)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
