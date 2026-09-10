package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import com.example.data.model.User
import com.example.data.model.UserRole
import com.example.data.model.UserStatus
import com.example.logic.AuthManager
import kotlinx.coroutines.launch

class UserManagementViewModel(private val repository: DatabaseRepository) : ViewModel() {
    val users = repository.allUsers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var isSyncing by mutableStateOf(false)
    var subsMap by mutableStateOf<Map<String, com.example.data.model.Subscription?>>(emptyMap())

    fun saveUser(user: User) {
        viewModelScope.launch {
            val creator = AuthManager.currentUser.value?.mobile ?: "admin"
            val finalUser = user.copy(creatorMobile = creator)
            repository.insertUser(finalUser)
        }
    }

    fun updateUser(user: User) {
        viewModelScope.launch {
            repository.updateUser(user)
        }
    }

    fun deleteUser(user: User) {
        viewModelScope.launch {
            repository.deleteUser(user)
        }
    }

        fun loadSubscriptions(adminMobiles: List<String>) {
        viewModelScope.launch {
            try {
                val map = mutableMapOf<String, com.example.data.model.Subscription?>()
                for (m in adminMobiles) {
                    map[m] = try { repository.getSubscription(m) } catch (e: Exception) { null }
                }
                subsMap = map
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun extendSubscription(adminMobile: String, days: Int, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val existing = try { repository.getSubscription(adminMobile) } catch (e: Exception) { null }
                // Stack only onto a live paid plan; trial/expired restarts from today
                val stacking = existing != null && existing.status == "ACTIVE" &&
                        !existing.planName.startsWith("TRIAL_") && existing.expiresAt > now
                val base = if (stacking) existing!!.expiresAt else now
                val sub = com.example.data.model.Subscription(
                    adminMobile = adminMobile,
                    planName = "${days}D",
                    startsAt = if (stacking) existing!!.startsAt else now,
                    expiresAt = base + days * 86400000L,
                    status = "ACTIVE",
                    updatedAt = now,
                    updatedBy = com.example.logic.AuthManager.currentUser.value?.mobile ?: "admin"
                )
                repository.saveSubscription(sub)
                subsMap = subsMap + (adminMobile to sub)
                onResult(true, "Recharge done: +$days days.")
            } catch (e: Exception) {
                e.printStackTrace()
                onResult(false, "Recharge failed: ${e.localizedMessage}")
            }
        }
    }

    fun blockSubscription(adminMobile: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val existing = try { repository.getSubscription(adminMobile) } catch (e: Exception) { null }
                val sub = com.example.data.model.Subscription(
                    adminMobile = adminMobile,
                    planName = existing?.planName ?: "NONE",
                    startsAt = existing?.startsAt ?: now,
                    expiresAt = existing?.expiresAt ?: now,
                    status = "EXPIRED",
                    updatedAt = now,
                    updatedBy = com.example.logic.AuthManager.currentUser.value?.mobile ?: "admin"
                )
                repository.saveSubscription(sub)
                subsMap = subsMap + (adminMobile to sub)
                onResult(true, "Subscription blocked.")
            } catch (e: Exception) {
                e.printStackTrace()
                onResult(false, "Block failed: ${e.localizedMessage}")
            }
        }
    }

    fun syncFromCloud(context: android.content.Context? = null, showResult: Boolean = false) {        if (isSyncing) return
        isSyncing = true
        viewModelScope.launch {
            try {
                val admin = AuthManager.currentUser.value
                val filter = if (admin?.mobile == "admin") null else admin?.mobile
                repository.syncUsersFromFirestore(filter)
                if (showResult && context != null) {
                    android.widget.Toast.makeText(context, "Cloud users database synced successfully!", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (showResult && context != null) {
                    android.widget.Toast.makeText(context, "Users sync failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                isSyncing = false
            }
        }
    }

    init {
        syncFromCloud()
    }

    class Factory(private val repository: DatabaseRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = UserManagementViewModel(repository) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManagementScreen(repository: DatabaseRepository, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: UserManagementViewModel = viewModel(factory = UserManagementViewModel.Factory(repository))
    val allUsersList by viewModel.users.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedUserForEdit by remember { mutableStateOf<User?>(null) }
    var userToDeleteByCard by remember { mutableStateOf<User?>(null) }
    val currentUser by AuthManager.currentUser.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var expandedAdmins by remember { mutableStateOf(setOf<String>()) }

    // Filter rules
    val displayedUsers = remember(allUsersList, currentUser, selectedTab) {
        val curr = currentUser ?: return@remember emptyList()
        val uniqueUsers = allUsersList.distinctBy { it.mobile }
        val withoutSelfAndSuperAdmin = uniqueUsers.filter { it.mobile != "admin" && it.mobile != curr.mobile }
        
        if (curr.mobile == "admin") {
            // Super Admin gets tabs
            if (selectedTab == 0) {
                // Admins tab
                withoutSelfAndSuperAdmin.filter { it.role == UserRole.ADMIN }
            } else {
                // Users & Staff tab (includes NORMAL_USER & OFFICE_STAFF)
                withoutSelfAndSuperAdmin.filter { it.role != UserRole.ADMIN }
            }
        } else {
            // Normal Admin only sees their own users
            withoutSelfAndSuperAdmin.filter { it.creatorMobile == curr.mobile && it.role != UserRole.ADMIN }
        }
    }

    // Super admin: pull recharge status for admin nodes shown on screen
    // (must come AFTER displayedUsers — Kotlin needs declaration order)
    val rechargeMobiles = remember(displayedUsers, currentUser) {
        if (currentUser?.mobile == "admin") displayedUsers.filter { it.role == UserRole.ADMIN }.map { it.mobile }
        else emptyList()
    }
    LaunchedEffect(rechargeMobiles) {
        if (rechargeMobiles.isNotEmpty()) viewModel.loadSubscriptions(rechargeMobiles)
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
                            text = "USER MNGT PROTOCOLS",
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
                        IconButton(onClick = { viewModel.syncFromCloud(context, true) }) {
                            if (viewModel.isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF4F7CFF))
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Sync Users", tint = Color.White)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0x3B070A13),
                        titleContentColor = Color.White
                    )
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = Color(0xFF4F7CFF),
                    contentColor = Color.White,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add User")
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                // If logged in as Super Admin, show tabs as admins should be seen separately from normal users/staff
                if (currentUser?.mobile == "admin") {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        indicator = { tabPositions ->
                            if (selectedTab < tabPositions.size) {
                                TabRowDefaults.Indicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                    color = Color(0xFF4F7CFF)
                                )
                            }
                        }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("ADMINISTRATORS", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) },
                            selectedContentColor = Color.White,
                            unselectedContentColor = Color(0xFFA1A8B8)
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("PERSONNEL", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) },
                            selectedContentColor = Color.White,
                            unselectedContentColor = Color(0xFFA1A8B8)
                        )
                    }
                }

                if (currentUser?.mobile == "admin") {
                    // SUPER ADMIN VIEW
                    if (displayedUsers.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (selectedTab == 0) "NO ADMINISTRATORS FOUND" else "NO PERSONNEL FOUND",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFFA1A8B8),
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    } else if (selectedTab == 0) {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(displayedUsers) { uAdmin ->
                                val isExpanded = expandedAdmins.contains(uAdmin.mobile)
                                val createdUsers = allUsersList.distinctBy { it.mobile }.filter { it.creatorMobile == uAdmin.mobile }
                                
                                PremiumUserCard(
                                    user = uAdmin,
                                    isExpanded = isExpanded,
                                    onManage = { selectedUserForEdit = uAdmin },
                                    onExpandToggle = {
                                        expandedAdmins = if (isExpanded) expandedAdmins - uAdmin.mobile else expandedAdmins + uAdmin.mobile
                                    },
                                    onDeleteClick = { userToDeleteByCard = uAdmin },
                                    footer = {
                                        if (currentUser?.mobile == "admin" && uAdmin.role == UserRole.ADMIN) {
                                            RechargeControls(
                                                adminMobile = uAdmin.mobile,
                                                sub = viewModel.subsMap[uAdmin.mobile],
                                                onExtend = { d ->
                                                    viewModel.extendSubscription(uAdmin.mobile, d) { _, msg ->
                                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                onBlock = {
                                                    viewModel.blockSubscription(uAdmin.mobile) { _, msg ->
                                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            )
                                        }
                                    }
                                ) {
                                    if (isExpanded) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                                        Spacer(modifier = Modifier.height(12.dp))
                                        
                                        if (createdUsers.isEmpty()) {
                                            Text(
                                                text = "No users created by this administrator.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFFA1A8B8),
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                        } else {
                                            Text(
                                                text = "PROVISIONED ACCOUNTS (${createdUsers.size})",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF4FD1FF),
                                                fontWeight = FontWeight.Black,
                                                letterSpacing = 1.5.sp,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                            createdUsers.forEach { subUser ->
                                                PremiumSubUserRow(user = subUser, onClick = { selectedUserForEdit = subUser })
                                                if (subUser != createdUsers.last()) {
                                                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 4.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(displayedUsers) { user ->
                                PremiumUserCard(
                                    user = user,
                                    isExpanded = false,
                                    onManage = { selectedUserForEdit = user },
                                    onExpandToggle = {},
                                    onDeleteClick = { userToDeleteByCard = user }
                                )
                            }
                        }
                    }
                } else {
                    // NORMAL ADMIN VIEW: Separate Office Staff and Normal Users Sections
                    val normalUsers = displayedUsers.filter { it.role == UserRole.NORMAL_USER }
                    val officeStaff = displayedUsers.filter { it.role == UserRole.OFFICE_STAFF }

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Text(
                                text = "OFFICE STAFF (${officeStaff.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF4FD1FF),
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        if (officeStaff.isEmpty()) {
                            item { Text("No office staff found.", color = Color(0xFFA1A8B8)) }
                        } else {
                            items(officeStaff) { user ->
                                PremiumUserCard(
                                    user = user,
                                    isExpanded = false,
                                    onManage = { selectedUserForEdit = user },
                                    onExpandToggle = {},
                                    onDeleteClick = { userToDeleteByCard = user }
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(8.dp)) }

                        item {
                            Text(
                                text = "NORMAL USERS (${normalUsers.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF7B61FF),
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        if (normalUsers.isEmpty()) {
                            item { Text("No normal users found.", color = Color(0xFFA1A8B8)) }
                        } else {
                            items(normalUsers) { user ->
                                PremiumUserCard(
                                    user = user,
                                    isExpanded = false,
                                    onManage = { selectedUserForEdit = user },
                                    onExpandToggle = {},
                                    onDeleteClick = { userToDeleteByCard = user }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddUserDialog(
            isSuperAdmin = currentUser?.mobile == "admin",
            onDismiss = { showAddDialog = false },
            onSave = { user ->
                viewModel.saveUser(user)
                showAddDialog = false
            }
        )
    }

    if (selectedUserForEdit != null) {
        EditUserDialog(
            user = selectedUserForEdit!!,
            isSuperAdmin = currentUser?.mobile == "admin",
            onDismiss = { selectedUserForEdit = null },
            onSave = { updatedUser ->
                viewModel.updateUser(updatedUser)
                selectedUserForEdit = null
            },
            onDelete = { userToDelete ->
                viewModel.deleteUser(userToDelete)
                selectedUserForEdit = null
            }
        )
    }

    if (userToDeleteByCard != null) {
        val userItem = userToDeleteByCard!!
        val confirmMsg = if (userItem.role == UserRole.ADMIN) {
            "Are you sure you want to permanently delete ADMIN account '${userItem.name.uppercase()}'? This will ALSO delete all personnel accounts and vehicle records/files uploaded by this admin. This action cannot be reversed!"
        } else {
            "Are you sure you want to permanently delete the account for '${userItem.name.uppercase()}'? This action cannot be reversed."
        }
        BasicDialog(
            title = "CONFIRM DELETION",
            onDismiss = { userToDeleteByCard = null },
            onSave = {
                viewModel.deleteUser(userItem)
                userToDeleteByCard = null
            },
            saveText = "DELETE",
            isDestructive = true
        ) {
            Text(confirmMsg, color = Color.White)
        }
    }
}

@Composable
fun RechargeControls(
    adminMobile: String,
    sub: com.example.data.model.Subscription?,
    onExtend: (Int) -> Unit,
    onBlock: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var customDays by remember { mutableStateOf("") }
    val exempt = adminMobile == "admin"
    val state = com.example.logic.SubscriptionGate.state(sub)

    val (badgeText, badgeColor) = when {
        exempt -> "EXEMPT" to Color(0xFFA1A8B8)
        state == com.example.logic.SubscriptionGate.State.NONE -> "NO RECHARGE" to Color(0xFFA1A8B8)
        state == com.example.logic.SubscriptionGate.State.BLOCKED -> "BLOCKED" to Color(0xFFFF5D73)
        state == com.example.logic.SubscriptionGate.State.EXPIRING ->
            "${com.example.logic.SubscriptionGate.daysLeft(sub!!)}D LEFT" to Color(0xFFFFB020)
        else -> "${com.example.logic.SubscriptionGate.daysLeft(sub!!)}D LEFT" to Color(0xFF4FD1FF)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "RECHARGE",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFA1A8B8),
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(badgeColor.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(badgeText, style = MaterialTheme.typography.labelSmall, color = badgeColor, fontWeight = FontWeight.Black)
            }
        }
        if (sub != null && state != com.example.logic.SubscriptionGate.State.NONE) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${sub.planName} • valid till ${java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date(sub.expiresAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFA1A8B8)
            )
        }
        if (!exempt) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 90, 365).forEach { d ->
                    androidx.compose.material3.Button(
                        onClick = { onExtend(d) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1FBF6B).copy(alpha = 0.15f),
                            contentColor = Color(0xFF4FD1FF)
                        )
                    ) {
                        Text("+$d", fontWeight = FontWeight.Bold)
                    }
                }
                if (state == com.example.logic.SubscriptionGate.State.ACTIVE ||
                    state == com.example.logic.SubscriptionGate.State.EXPIRING) {
                    androidx.compose.material3.Button(
                        onClick = onBlock,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF5D73).copy(alpha = 0.15f),
                            contentColor = Color(0xFFFF5D73)
                        )
                    ) {
                        Text("BLOCK", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customDays,
                    onValueChange = { customDays = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Custom days") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White)
                )
                Spacer(modifier = Modifier.width(8.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        val days = customDays.toIntOrNull()
                        if (days == null || days < 1 || days > 3650) {
                            android.widget.Toast.makeText(context, "Enter days between 1 and 3650", android.widget.Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        onExtend(days)
                        customDays = ""
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1FBF6B).copy(alpha = 0.15f),
                        contentColor = Color(0xFF4FD1FF)
                    )
                ) {
                    Text("ADD", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun PremiumUserCard(
    user: User, 
    isExpanded: Boolean, 
    onManage: () -> Unit, 
    onExpandToggle: () -> Unit, 
    onDeleteClick: () -> Unit,
    content: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x17FFFFFF)),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isExpanded) Brush.linearGradient(colors = listOf(Color(0xFF4F7CFF), Color(0x05FFFFFF)))
            else Brush.linearGradient(colors = listOf(Color(0x26FFFFFF), Color(0x05FFFFFF)))
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = if(user.role == UserRole.ADMIN) onExpandToggle else onManage),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(user.name.uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    if(user.role == UserRole.ADMIN) Color(0xFF4F7CFF).copy(alpha = 0.2f) else Color(0x1AFFFFFF), 
                                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(user.role.name.replace("_", " "), style = MaterialTheme.typography.labelSmall, color = if(user.role == UserRole.ADMIN) Color(0xFF4FD1FF) else Color.White, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("ID: ${user.mobile}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A8B8))
                    if (user.email.isNotEmpty()) {
                        Text("EMAIL: ${user.email}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A8B8))
                    }
                    Text("PASS: ${user.passwordHash}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A8B8))
                    val deviceStatus = if (user.registeredDeviceId.isEmpty()) "UNBOUND" else "${user.registeredDeviceId.take(12)}..."
                    Text(
                        "DEVICE: $deviceStatus",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (user.registeredDeviceId.isEmpty()) Color(0xFFFF5D73) else Color(0xFF4FD1FF),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onManage, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color.White.copy(alpha = 0.85f))
                    }
                    if (user.mobile != "admin") {
                        IconButton(onClick = onDeleteClick, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5D73))
                        }
                    }
                    if (user.role == UserRole.ADMIN) {
                        IconButton(onClick = onExpandToggle, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
            content()
            footer()
        }
    }
}

@Composable
fun PremiumSubUserRow(user: User, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(user.name.uppercase(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Text("[${user.role.name.replace("_", " ")}]", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4FD1FF), fontWeight = FontWeight.Bold)
            }
            Text("ID: ${user.mobile} • PASS: ${user.passwordHash}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A8B8))
            if (user.email.isNotEmpty()) {
                Text("EMAIL: ${user.email}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A8B8))
            }
        }
    }
}

private fun isValidEmail(email: String): Boolean {
    val emailRegex = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\$"
    return email.matches(emailRegex.toRegex())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddUserDialog(isSuperAdmin: Boolean, onDismiss: () -> Unit, onSave: (User) -> Unit) {
    var name by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(UserRole.NORMAL_USER) }
    var status by remember { mutableStateOf(UserStatus.ACTIVE) }
    var emailError by remember { mutableStateOf<String?>(null) }

    BasicDialog(
        title = "CREATE NEW ACCOUNT",
        onDismiss = onDismiss,
        onSave = {
            val trimmedEmail = email.trim()
            if (name.isBlank() || mobile.isBlank() || password.isBlank()) {
                // Let basic text input validations pass
            } else if (trimmedEmail.isEmpty()) {
                emailError = "Email ID is required"
            } else if (!isValidEmail(trimmedEmail)) {
                emailError = "Invalid email format"
            } else {
                emailError = null
                onSave(User(name = name.trim(), mobile = mobile.trim(), passwordHash = password.trim(), role = role, status = status, email = trimmedEmail))
            }
        }
    ) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name", color = Color(0xFFA1A8B8)) }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0x33FFFFFF), focusedBorderColor = Color(0xFF4F7CFF)))
        OutlinedTextField(value = mobile, onValueChange = { mobile = it }, label = { Text("Mobile / ID", color = Color(0xFFA1A8B8)) }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0x33FFFFFF), focusedBorderColor = Color(0xFF4F7CFF)))
        
        OutlinedTextField(
            value = email,
            onValueChange = { 
                email = it
                if (emailError != null) {
                    emailError = if (isValidEmail(it.trim())) null else "Invalid email format"
                }
            },
            label = { Text("Email ID", color = Color(0xFFA1A8B8)) },
            isError = emailError != null,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color(0x33FFFFFF), 
                focusedBorderColor = Color(0xFF4F7CFF),
                errorBorderColor = Color(0xFFFF5D73)
            )
        )
        if (emailError != null) {
            Text(
                text = emailError!!,
                color = Color(0xFFFF5D73),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }

        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password", color = Color(0xFFA1A8B8)) }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0x33FFFFFF), focusedBorderColor = Color(0xFF4F7CFF)))
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("ROLE ASSIGNMENT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFA1A8B8), letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(4.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = role == UserRole.NORMAL_USER, onClick = { role = UserRole.NORMAL_USER }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF4F7CFF)))
            Text("NORMAL USER", style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = role == UserRole.OFFICE_STAFF, onClick = { role = UserRole.OFFICE_STAFF }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF4F7CFF)))
            Text("OFFICE STAFF", style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.Bold)
        }
        if (isSuperAdmin) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = role == UserRole.ADMIN, onClick = { role = UserRole.ADMIN }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF4F7CFF)))
                Text("ADMINISTRATOR", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4FD1FF), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditUserDialog(
    user: User, 
    isSuperAdmin: Boolean,
    onDismiss: () -> Unit, 
    onSave: (User) -> Unit,
    onDelete: (User) -> Unit
) {
    var name by remember { mutableStateOf(user.name) }
    var mobile by remember { mutableStateOf(user.mobile) }
    var password by remember { mutableStateOf(user.passwordHash) }
    var email by remember { mutableStateOf(user.email) }
    var role by remember { mutableStateOf(user.role) }
    var status by remember { mutableStateOf(user.status) }
    var registeredDeviceId by remember { mutableStateOf(user.registeredDeviceId) }
    var isFirstTime by remember { mutableStateOf(user.isFirstTime) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var emailError by remember { mutableStateOf<String?>(null) }

    if (showDeleteConfirm) {
        BasicDialog(
            title = "CONFIRM DELETION",
            onDismiss = { showDeleteConfirm = false },
            onSave = { 
                onDelete(user)
                showDeleteConfirm = false 
            },
            saveText = "DELETE",
            isDestructive = true
        ) {
            val confirmMsg = if (user.role == UserRole.ADMIN) {
                "Are you sure you want to permanently delete ADMIN account '${user.name.uppercase()}'? This will ALSO delete all personnel accounts and vehicle records/files uploaded by this admin. This action cannot be reversed!"
            } else {
                "Are you sure you want to permanently delete the account for '${user.name.uppercase()}'? This action cannot be reversed."
            }
            Text(confirmMsg, color = Color.White)
        }
        return
    }

    BasicDialog(
        title = "MANAGE IDENTITY",
        onDismiss = onDismiss,
        onSave = {
            val trimmedEmail = email.trim()
            if (name.isBlank() || mobile.isBlank() || password.isBlank()) {
                // Let basic text input validations pass
            } else if (trimmedEmail.isEmpty()) {
                emailError = "Email ID is required"
            } else if (!isValidEmail(trimmedEmail)) {
                emailError = "Invalid email format"
            } else {
                emailError = null
                onSave(user.copy(name = name.trim(), mobile = mobile.trim(), passwordHash = password.trim(), role = role, status = status, registeredDeviceId = registeredDeviceId, isFirstTime = isFirstTime, email = trimmedEmail))
            }
        },
        extraAction = {
            if (user.mobile != "admin") {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5D73))
                }
            } else Spacer(modifier = Modifier.width(48.dp))
        }
    ) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name", color = Color(0xFFA1A8B8)) }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0x33FFFFFF), focusedBorderColor = Color(0xFF4F7CFF)))
        OutlinedTextField(value = mobile, onValueChange = { mobile = it }, label = { Text("Mobile / ID", color = Color(0xFFA1A8B8)) }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0x33FFFFFF), focusedBorderColor = Color(0xFF4F7CFF)))
        
        OutlinedTextField(
            value = email,
            onValueChange = { 
                email = it
                if (emailError != null) {
                    emailError = if (isValidEmail(it.trim())) null else "Invalid email format"
                }
            },
            label = { Text("Email ID", color = Color(0xFFA1A8B8)) },
            isError = emailError != null,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color(0x33FFFFFF), 
                focusedBorderColor = Color(0xFF4F7CFF),
                errorBorderColor = Color(0xFFFF5D73)
            )
        )
        if (emailError != null) {
            Text(
                text = emailError!!,
                color = Color(0xFFFF5D73),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }

        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password", color = Color(0xFFA1A8B8)) }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0x33FFFFFF), focusedBorderColor = Color(0xFF4F7CFF)))
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("ROLE & STATUS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFA1A8B8), letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(4.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = role == UserRole.NORMAL_USER, onClick = { role = UserRole.NORMAL_USER }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF4F7CFF)))
            Text("NORMAL USER", style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = role == UserRole.OFFICE_STAFF, onClick = { role = UserRole.OFFICE_STAFF }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF4F7CFF)))
            Text("OFFICE STAFF", style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.Bold)
        }
        if (isSuperAdmin) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = role == UserRole.ADMIN, onClick = { role = UserRole.ADMIN }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF4F7CFF)))
                Text("ADMINISTRATOR", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4FD1FF), fontWeight = FontWeight.Bold)
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = status == UserStatus.ACTIVE, onClick = { status = UserStatus.ACTIVE }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF4F7CFF)))
                Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = status == UserStatus.DISABLED, onClick = { status = UserStatus.DISABLED }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF5D73)))
                Text("DISABLED", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF5D73), fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
        Spacer(modifier = Modifier.height(16.dp))

        Text("DEVICE BINDING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFA1A8B8), letterSpacing = 1.sp)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val deviceStr = if (registeredDeviceId.isEmpty()) "UNBOUND" else "ID: ${registeredDeviceId.take(12)}..."
            Text(deviceStr, style = MaterialTheme.typography.bodySmall, color = if(registeredDeviceId.isEmpty()) Color(0xFFA1A8B8) else Color.White)
            
            if (registeredDeviceId.isNotEmpty()) {
                Button(
                    onClick = {
                        registeredDeviceId = ""
                        isFirstTime = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5D73).copy(alpha = 0.2f), contentColor = Color(0xFFFF5D73)),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text("UNBIND", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun BasicDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    saveText: String = "SAVE",
    isDestructive: Boolean = false,
    extraAction: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131929)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF))
        ) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp, color = Color.White)
                Spacer(modifier = Modifier.height(24.dp))
                content()
                Spacer(modifier = Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    extraAction()
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.weight(1f)) {
                        TextButton(onClick = onDismiss) { Text("CANCEL", color = Color(0xFFA1A8B8), fontWeight = FontWeight.Bold) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onSave,
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDestructive) Color(0xFFFF5D73) else Color(0xFF4F7CFF)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        ) {
                            Text(saveText, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                        }
                    }
                }
            }
        }
    }
}
