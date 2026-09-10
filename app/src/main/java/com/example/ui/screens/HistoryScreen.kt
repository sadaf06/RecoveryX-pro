package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.SearchHistory
import com.example.data.repository.DatabaseRepository
import com.example.logic.AuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Accompanying model for grouping
data class UserHistoryGroup(
    val mobile: String,
    val name: String,
    val logs: List<SearchHistory>
)

class HistoryViewModel(private val repository: DatabaseRepository) : ViewModel() {
    private val _historyLogs = MutableStateFlow<List<SearchHistory>>(emptyList())
    val historyLogs: StateFlow<List<SearchHistory>> = _historyLogs.asStateFlow()

    fun loadHistory() {
        val user = AuthManager.currentUser.value ?: return
        viewModelScope.launch {
            if (user.mobile == "admin") {
                repository.getAllHistory().collect { logs ->
                    _historyLogs.value = logs
                }
            } else {
                repository.getHistoryForAdmin(user.mobile).collect { logs ->
                    _historyLogs.value = logs
                }
            }
        }
    }

    fun deleteHistoryItem(id: Int, firestoreId: String) {
        viewModelScope.launch {
            repository.deleteHistoryItem(id, firestoreId)
            loadHistory()
        }
    }

    fun deleteHistoryByUser(userMobile: String) {
        viewModelScope.launch {
            repository.deleteHistoryByUser(userMobile)
            loadHistory()
        }
    }

    fun clearAllRelevantHistory() {
        val user = AuthManager.currentUser.value ?: return
        viewModelScope.launch {
            if (user.mobile == "admin") {
                repository.clearAllHistory()
            } else {
                repository.clearHistoryForAdmin(user.mobile)
            }
            loadHistory()
        }
    }

    class Factory(private val repository: DatabaseRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(repository) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    repository: DatabaseRepository,
    onBack: () -> Unit
) {
    val viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory(repository))
    val logs by viewModel.historyLogs.collectAsStateWithLifecycle()
    val currentUser by AuthManager.currentUser.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var isSyncing by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current

    // Confirmation dialog states
    var itemToDelete by remember { mutableStateOf<SearchHistory?>(null) }
    var userGroupToDelete by remember { mutableStateOf<UserHistoryGroup?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadHistory()
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("CLEAR ALL HISTORIES", color = Color.White, fontWeight = FontWeight.ExtraBold) },
            text = { Text("Are you sure you want to permanently clear search history? This will delete all search history records locally and on the Cloud, and cannot be undone.", color = Color.LightGray) },
            containerColor = Color(0xFF131929),
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllRelevantHistory()
                        showClearAllConfirm = false
                        android.widget.Toast.makeText(context, "Search history cleared completely!", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5D73))
                ) {
                    Text("CLEAR ALL", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text("CANCEL", color = Color(0xFFA1A8B8))
                }
            }
        )
    }

    if (userGroupToDelete != null) {
        val group = userGroupToDelete!!
        AlertDialog(
            onDismissRequest = { userGroupToDelete = null },
            title = { Text("DELETE USER HISTORY", color = Color.White, fontWeight = FontWeight.ExtraBold) },
            text = { Text("All search history logged for user ID '${group.mobile}' (${group.name}) will be permanently deleted. Proceed?", color = Color.LightGray) },
            containerColor = Color(0xFF131929),
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteHistoryByUser(group.mobile)
                        userGroupToDelete = null
                        android.widget.Toast.makeText(context, "User history deleted!", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5D73))
                ) {
                    Text("DELETE", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { userGroupToDelete = null }) {
                    Text("CANCEL", color = Color(0xFFA1A8B8))
                }
            }
        )
    }

    if (itemToDelete != null) {
        val item = itemToDelete!!
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("DELETE SINGLE RECORD", color = Color.White, fontWeight = FontWeight.ExtraBold) },
            text = { Text("Are you sure you want to delete this specific search record for vehicle '${item.vehicleNumber}'?", color = Color.LightGray) },
            containerColor = Color(0xFF131929),
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteHistoryItem(item.id, item.firestoreId)
                        itemToDelete = null
                        android.widget.Toast.makeText(context, "Record deleted!", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5D73))
                ) {
                    Text("DELETE", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("CANCEL", color = Color(0xFFA1A8B8))
                }
            }
        )
    }

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
                            text = "COMMAND HISTORY",
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
                    actions = {
                        IconButton(
                            onClick = { showClearAllConfirm = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear History",
                                tint = Color(0xFFFF5D73)
                            )
                        }
                        IconButton(
                            onClick = {
                                if (!isSyncing) {
                                    isSyncing = true
                                    scope.launch {
                                        try {
                                            val filter = if (currentUser?.mobile == "admin") null else currentUser?.mobile
                                            repository.syncHistoriesFromFirestore(filter)
                                            viewModel.loadHistory()
                                            android.widget.Toast.makeText(context, "History synced successfully with Cloud!", android.widget.Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            android.widget.Toast.makeText(context, "History sync failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
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
                                    color = Color(0xFF4F7CFF)
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Sync Cloud", tint = Color.White)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0x3B070A13),
                        titleContentColor = Color.White
                    )
                )
            }
        ) { innerPadding ->
            var expandedUserMobiles by remember { mutableStateOf(setOf<String>()) }
            val logsByUser = remember(logs) {
                logs.groupBy { it.userMobile }
            }

            val uniqueUsersList = remember(logsByUser) {
                logsByUser.map { (mobile, userLogs) ->
                    val firstName = userLogs.firstOrNull()?.userName ?: "Unknown"
                    UserHistoryGroup(
                        mobile = mobile,
                        name = firstName,
                        logs = userLogs
                    )
                }.sortedBy { it.name }
            }

            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                if (uniqueUsersList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "NO TELEMETRY FOUND",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFA1A8B8),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(uniqueUsersList) { userGroup ->
                            val isExpanded = expandedUserMobiles.contains(userGroup.mobile)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0x17FFFFFF)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.dp,
                                    brush = if (isExpanded) Brush.linearGradient(colors = listOf(Color(0xFF4F7CFF), Color(0x05FFFFFF))) 
                                            else Brush.linearGradient(colors = listOf(Color(0x26FFFFFF), Color(0x05FFFFFF)))
                                )
                            ) {
                                Column(modifier = Modifier.padding(bottom = if(isExpanded) 16.dp else 0.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                expandedUserMobiles = if (isExpanded) {
                                                    expandedUserMobiles - userGroup.mobile
                                                } else {
                                                    expandedUserMobiles + userGroup.mobile
                                                }
                                            }
                                            .padding(20.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = userGroup.name.uppercase(), 
                                                style = MaterialTheme.typography.titleMedium, 
                                                fontWeight = FontWeight.ExtraBold, 
                                                color = Color.White,
                                                letterSpacing = 1.2.sp
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("ID: ${userGroup.mobile}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A8B8))
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFF4FD1FF).copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = "${userGroup.logs.size} REQS", 
                                                    style = MaterialTheme.typography.labelSmall, 
                                                    color = Color(0xFF4FD1FF), 
                                                    fontWeight = FontWeight.Black
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            IconButton(
                                                onClick = { userGroupToDelete = userGroup },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete User History",
                                                    tint = Color(0xFFFF5D73).copy(alpha = 0.85f),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                                tint = Color.White
                                            )
                                        }
                                    }

                                    if (isExpanded) {
                                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 20.dp))
                                        Column(
                                            modifier = Modifier
                                                .padding(top = 16.dp, start = 20.dp, end = 20.dp)
                                                .fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            userGroup.logs.forEach { log ->
                                                Card(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                                    colors = CardDefaults.cardColors(containerColor = Color(0x0AFFFFFF)),
                                                    border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0x1AFFFFFF))
                                                ) {
                                                    Column(modifier = Modifier.padding(16.dp)) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = log.vehicleNumber,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                color = Color(0xFF4FD1FF),
                                                                letterSpacing = 1.sp
                                                            )
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Text(
                                                                    text = log.timestamp,
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = Color(0xFFA1A8B8)
                                                                )
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                IconButton(
                                                                    onClick = { itemToDelete = log },
                                                                    modifier = Modifier.size(28.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.Delete,
                                                                        contentDescription = "Delete record",
                                                                        tint = Color(0xFFFF5D73).copy(alpha = 0.7f),
                                                                        modifier = Modifier.size(16.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                        if (log.model.isNotEmpty()) {
                                                            Spacer(modifier = Modifier.height(6.dp))
                                                            Text(
                                                                text = "MODEL: ${log.model.uppercase()}",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = Color.White
                                                            )
                                                        }
                                                        if (currentUser?.mobile == "admin") {
                                                            Spacer(modifier = Modifier.height(6.dp))
                                                            Text(
                                                                text = "PROVIDER: ${log.creatorMobile.ifEmpty { "ADMIN" }}",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = Color(0xFF7B61FF),
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
