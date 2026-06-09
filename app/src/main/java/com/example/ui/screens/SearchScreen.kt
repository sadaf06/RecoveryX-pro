package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp
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
        _isOnlineMode.value = online
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
                 if (_isOnlineMode.value) {
                     _isSearching.value = true
                     try {
                         val results = repository.searchVehiclesOnline(query, criteria, creatorFilter)
                         _searchResults.value = results
                     } catch (e: Exception) {
                         _searchError.value = "Online search failed: No internet or Firestore issue."
                         _searchResults.value = emptyList()
                     } finally {
                         _isSearching.value = false
                     }
                 } else {
                     _isSearching.value = true
                     repository.searchVehicles(query, criteria).collect { results ->
                         _searchResults.value = results
                         _isSearching.value = false
                     }
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
    onNavigateToDetails: (Int) -> Unit,
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
    val context = LocalContext.current

    val adminFilter = remember(currentUser) {
        when {
            currentUser?.mobile == "admin" -> null
            currentUser?.role == com.example.data.model.UserRole.ADMIN -> currentUser?.mobile
            else -> currentUser?.creatorMobile?.ifEmpty { "admin" } ?: "admin"
        }
    }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search Vehicles") },
                navigationIcon = {
                    if (onBack != null && currentUser?.role == com.example.data.model.UserRole.ADMIN) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
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
                                        repository.syncVehiclesFromFirestore(filter)
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
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Box(modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Refresh, 
                                    contentDescription = "Sync from Cloud",
                                    modifier = Modifier.align(Alignment.Center),
                                    tint = if (hasNewDataPending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (hasNewDataPending) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .align(Alignment.TopEnd)
                                            .background(MaterialTheme.colorScheme.error, shape = CircleShape)
                                    )
                                }
                            }
                        }
                    }
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
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            
            // Search Mode Toggle Selection (Online and Offline both modes)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Offline Local Search Choice
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { viewModel.toggleSearchMode(false, adminFilter) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (!isOnlineMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    shape = MaterialTheme.shapes.medium,
                    border = if (!isOnlineMode) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Offline Search",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (!isOnlineMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Downloaded (Fast Search)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!isOnlineMode) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }

                // Online Cloud Search Choice
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            if (!com.example.logic.NetworkUtils.isNetworkAvailable(context)) {
                                android.widget.Toast.makeText(context, "No connection. Please connect to internet to use Online Search.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            viewModel.toggleSearchMode(true, adminFilter)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isOnlineMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    shape = MaterialTheme.shapes.medium,
                    border = if (isOnlineMode) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Online Search",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isOnlineMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Live Cloud Database",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOnlineMode) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // High-visibility download/refresh banner for offline data integration
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (hasNewDataPending) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (hasNewDataPending) "🚨 Vehicle Updates Ready!" else "Offline Search Database",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (hasNewDataPending) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (hasNewDataPending)
                                "Imported files have updates. Download latest database for fast search."
                            else if (isSyncing) "Updating local records..." else "All data is saved locally. Refresh to sync live.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasNewDataPending) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
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
                                        repository.syncVehiclesFromFirestore(filter)
                                        val prefs = context.getSharedPreferences("recoveryx_prefs", android.content.Context.MODE_PRIVATE)
                                        prefs.edit().putLong("last_download_time", System.currentTimeMillis()).apply()
                                        hasNewDataPending = false
                                        android.widget.Toast.makeText(context, "Database updated successfully for offline searching!", android.widget.Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "Error updating database: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                    } finally {
                                        isSyncing = false
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasNewDataPending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download Data",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (hasNewDataPending) "DOWNLOAD" else "REFRESH",
                                style = MaterialTheme.typography.labelLarge
                            )
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
                        com.example.data.model.SearchCriteria.GENERAL -> "Search by Registration No, Owner etc"
                        com.example.data.model.SearchCriteria.ENGINE_LAST -> "Last digit of Engine No (e.g. 5)"
                        com.example.data.model.SearchCriteria.CHASSIS_LAST -> "Last digit of Chassis No (e.g. 9)"
                        com.example.data.model.SearchCriteria.LOAN_START -> "Starting of Loan No (e.g. LN)"
                        com.example.data.model.SearchCriteria.VEHICLE_LAST -> "Last digit of Vehicle No (e.g. 2)"
                    }
                    Text(placeholderText, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
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
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                criteriaFilters.forEach { (crit, label) ->
                    FilterChip(
                        selected = activeCriteria == crit,
                        onClick = { viewModel.selectCriteria(crit, adminFilter) },
                        label = { Text(label) }
                    )
                }
            }

            if (isSearchingDetail) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (searchError.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = searchError,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(results) { vehicle ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        onClick = {
                            scope.launch {
                                try {
                                    val u = currentUser
                                    if (u != null) {
                                        val formatter = java.text.SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", java.util.Locale.getDefault())
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
                            onNavigateToDetails(vehicle.id)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            // Pseudo icon box
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Text(
                                    vehicle.customerName.take(1).uppercase(),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(vehicle.vehicleNumber, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text("${vehicle.bankName} • ${vehicle.customerName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                val minLength = if (activeCriteria == com.example.data.model.SearchCriteria.GENERAL) 2 else 1
                if (results.isEmpty() && searchQuery.length >= minLength) {
                    item {
                        Text("No matching vehicles found.", modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}
