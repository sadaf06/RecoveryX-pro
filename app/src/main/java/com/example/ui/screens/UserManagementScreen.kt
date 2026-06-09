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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    fun syncFromCloud() {
        if (isSyncing) return
        isSyncing = true
        viewModelScope.launch {
            try {
                val admin = AuthManager.currentUser.value
                val filter = if (admin?.mobile == "admin") null else admin?.mobile
                repository.syncUsersFromFirestore(filter)
            } catch (e: Exception) {
                e.printStackTrace()
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
    val viewModel: UserManagementViewModel = viewModel(factory = UserManagementViewModel.Factory(repository))
    val allUsersList by viewModel.users.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedUserForEdit by remember { mutableStateOf<User?>(null) }
    val currentUser by AuthManager.currentUser.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var expandedAdmins by remember { mutableStateOf(setOf<String>()) }

    // Filter rules
    val displayedUsers = remember(allUsersList, currentUser, selectedTab) {
        val curr = currentUser ?: return@remember emptyList()
        val withoutSelfAndSuperAdmin = allUsersList.filter { it.mobile != "admin" && it.mobile != curr.mobile }
        
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Users & Staff") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.syncFromCloud() }) {
                        if (viewModel.isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Sync Users")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
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
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Admins", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Users & Staff", fontWeight = FontWeight.Bold) }
                    )
                }
            }

            if (currentUser?.mobile == "admin") {
                // SUPER ADMIN VIEW
                if (displayedUsers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = if (selectedTab == 0) "No other admins registered" else "No users or staff found",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Generate new accounts using the '+' action below.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (selectedTab == 0) {
                    // Admins Tab: First shows all admins. When clicked, shows their created users.
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
                    ) {
                        items(displayedUsers) { uAdmin ->
                            val isExpanded = expandedAdmins.contains(uAdmin.mobile)
                            val createdUsers = allUsersList.filter { it.creatorMobile == uAdmin.mobile }
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isExpanded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.dp,
                                    color = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Column {
                                    ListItem(
                                        headlineContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(uAdmin.name, fontWeight = FontWeight.Bold)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                SuggestionChip(
                                                    onClick = {},
                                                    label = { Text("Admin") },
                                                    modifier = Modifier.height(24.dp)
                                                )
                                            }
                                        },
                                        supportingContent = {
                                            Column(modifier = Modifier.padding(top = 4.dp)) {
                                                Text("Mobile / ID: ${uAdmin.mobile}")
                                                Text("Password: ${uAdmin.passwordHash}")
                                                val deviceStatus = if (uAdmin.registeredDeviceId.isEmpty()) "Not Registered" else "${uAdmin.registeredDeviceId.take(12)}..."
                                                Text(
                                                    "Device: $deviceStatus",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (uAdmin.registeredDeviceId.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        },
                                        trailingContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                TextButton(onClick = { selectedUserForEdit = uAdmin }) {
                                                    Text("Manage")
                                                }
                                                IconButton(onClick = {
                                                    expandedAdmins = if (isExpanded) {
                                                        expandedAdmins - uAdmin.mobile
                                                    } else {
                                                        expandedAdmins + uAdmin.mobile
                                                    }
                                                }) {
                                                    Icon(
                                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                        contentDescription = if (isExpanded) "Collapse" else "Expand"
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier.clickable {
                                            expandedAdmins = if (isExpanded) {
                                                expandedAdmins - uAdmin.mobile
                                            } else {
                                                expandedAdmins + uAdmin.mobile
                                            }
                                        }
                                    )

                                    if (isExpanded) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                        
                                        if (createdUsers.isEmpty()) {
                                            Text(
                                                text = "No users created by this admin.",
                                                modifier = Modifier.padding(16.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        } else {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), MaterialTheme.shapes.medium)
                                                    .padding(12.dp)
                                            ) {
                                                Text(
                                                    text = "Created Users (${createdUsers.size}):",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(bottom = 8.dp)
                                                )
                                                createdUsers.forEach { subUser ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { selectedUserForEdit = subUser }
                                                            .padding(vertical = 8.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Text(subUser.name, fontWeight = FontWeight.SemiBold)
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                SuggestionChip(
                                                                    onClick = {},
                                                                    label = { Text(subUser.role.name, style = MaterialTheme.typography.labelSmall) },
                                                                    modifier = Modifier.height(20.dp)
                                                                )
                                                            }
                                                            Spacer(modifier = Modifier.height(2.dp))
                                                            Text("Mobile: ${subUser.mobile} • Pwd: ${subUser.passwordHash}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                        TextButton(onClick = { selectedUserForEdit = subUser }) {
                                                            Text("Manage", style = MaterialTheme.typography.labelMedium)
                                                        }
                                                    }
                                                    if (subUser != createdUsers.last()) {
                                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Users and staff (non-admin) Tab
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(displayedUsers) { user ->
                            ListItem(
                                headlineContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(user.name, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        SuggestionChip(
                                            onClick = {},
                                            label = { Text(user.role.name) },
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                },
                                supportingContent = {
                                    Column(modifier = Modifier.padding(top = 4.dp)) {
                                        Text("Mobile / ID: ${user.mobile}")
                                        Text("Password: ${user.passwordHash}")
                                        val deviceStatus = if (user.registeredDeviceId.isEmpty()) "Not Registered" else "${user.registeredDeviceId.take(12)}..."
                                        Text(
                                            "Device Status: $deviceStatus",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (user.registeredDeviceId.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text("Created by: ${user.creatorMobile.ifEmpty { "admin" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                },
                                trailingContent = {
                                    TextButton(onClick = { selectedUserForEdit = user }) {
                                        Text("Manage")
                                    }
                                },
                                modifier = Modifier.clickable { selectedUserForEdit = user }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            } else {
                // NORMAL ADMIN VIEW: Separate Office Staff and Normal Users Sections
                val normalUsers = displayedUsers.filter { it.role == UserRole.NORMAL_USER }
                val officeStaff = displayedUsers.filter { it.role == UserRole.OFFICE_STAFF }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    // Normal Users Section
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "NORMAL USERS (${normalUsers.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    
                    if (normalUsers.isEmpty()) {
                        item {
                            Text(
                                text = "No normal users found under your admin account.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        items(normalUsers) { user ->
                            ListItem(
                                headlineContent = { Text(user.name, fontWeight = FontWeight.Bold) },
                                supportingContent = {
                                    Column(modifier = Modifier.padding(top = 4.dp)) {
                                        Text("Mobile / ID: ${user.mobile}")
                                        Text("Password: ${user.passwordHash}")
                                        val deviceStatus = if (user.registeredDeviceId.isEmpty()) "Not Registered" else "${user.registeredDeviceId.take(12)}..."
                                        Text(
                                            "Device Status: $deviceStatus",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (user.registeredDeviceId.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                },
                                trailingContent = {
                                    TextButton(onClick = { selectedUserForEdit = user }) {
                                        Text("Manage")
                                    }
                                },
                                modifier = Modifier.clickable { selectedUserForEdit = user }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }

                    // Office Staff Section
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "OFFICE STAFF (${officeStaff.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }

                    if (officeStaff.isEmpty()) {
                        item {
                            Text(
                                text = "No office staff found under your admin account.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        items(officeStaff) { user ->
                            ListItem(
                                headlineContent = { Text(user.name, fontWeight = FontWeight.Bold) },
                                supportingContent = {
                                    Column(modifier = Modifier.padding(top = 4.dp)) {
                                        Text("Mobile / ID: ${user.mobile}")
                                        Text("Password: ${user.passwordHash}")
                                        val deviceStatus = if (user.registeredDeviceId.isEmpty()) "Not Registered" else "${user.registeredDeviceId.take(12)}..."
                                        Text(
                                            "Device Status: $deviceStatus",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (user.registeredDeviceId.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                },
                                trailingContent = {
                                    TextButton(onClick = { selectedUserForEdit = user }) {
                                        Text("Manage")
                                    }
                                },
                                modifier = Modifier.clickable { selectedUserForEdit = user }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
    }
}

@Composable
fun AddUserDialog(isSuperAdmin: Boolean, onDismiss: () -> Unit, onSave: (User) -> Unit) {
    var name by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(UserRole.NORMAL_USER) }
    var status by remember { mutableStateOf(UserStatus.ACTIVE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create User Account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = mobile, onValueChange = { mobile = it }, label = { Text("Mobile Number (Login ID)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
                
                Text("Role Assignment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = role == UserRole.NORMAL_USER, onClick = { role = UserRole.NORMAL_USER })
                        Text("Normal User", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = role == UserRole.OFFICE_STAFF, onClick = { role = UserRole.OFFICE_STAFF })
                        Text("Office Staff", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (isSuperAdmin) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = role == UserRole.ADMIN, onClick = { role = UserRole.ADMIN })
                            Text("Administrator", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Text("Account Status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = status == UserStatus.ACTIVE, onClick = { status = UserStatus.ACTIVE })
                        Text("Active", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = status == UserStatus.DISABLED, onClick = { status = UserStatus.DISABLED })
                        Text("Disabled", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotEmpty() && mobile.isNotEmpty() && password.isNotEmpty()) {
                        onSave(User(name = name, mobile = mobile, passwordHash = password, role = role, status = status))
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

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
    var role by remember { mutableStateOf(user.role) }
    var status by remember { mutableStateOf(user.status) }
    var registeredDeviceId by remember { mutableStateOf(user.registeredDeviceId) }
    var isFirstTime by remember { mutableStateOf(user.isFirstTime) }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Account?") },
            text = { Text("Are you sure you want to permanently delete user account for '${user.name}'? This actions is irreversible.") },
            confirmButton = {
                Button(
                    onClick = { 
                        onDelete(user) 
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Account Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = mobile, onValueChange = { mobile = it }, label = { Text("Mobile Number") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
                
                Text("Role Assignment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = role == UserRole.NORMAL_USER, onClick = { role = UserRole.NORMAL_USER })
                        Text("Normal User", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = role == UserRole.OFFICE_STAFF, onClick = { role = UserRole.OFFICE_STAFF })
                        Text("Office Staff", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (isSuperAdmin) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = role == UserRole.ADMIN, onClick = { role = UserRole.ADMIN })
                            Text("Administrator", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Text("Account Status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = status == UserStatus.ACTIVE, onClick = { status = UserStatus.ACTIVE })
                        Text("Active", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = status == UserStatus.DISABLED, onClick = { status = UserStatus.DISABLED })
                        Text("Disabled", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))

                // Device Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Device Identity Limit", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        val deviceStr = if (registeredDeviceId.isEmpty()) "Unbound. Login OTP required." else "Bound ID: ${registeredDeviceId.take(12)}..."
                        Text(deviceStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    
                    if (registeredDeviceId.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                registeredDeviceId = ""
                                isFirstTime = true
                            }
                        ) {
                            Icon(
                                Icons.Default.Devices, 
                                contentDescription = "Deregister Device",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Ensure Super Admin status is never compromised
                if (user.mobile != "admin") {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete User", tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Spacer(modifier = Modifier.width(48.dp)) // keeps spacing standard
                }
                
                Row {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = {
                            if (name.isNotEmpty() && mobile.isNotEmpty() && password.isNotEmpty()) {
                                onSave(
                                    user.copy(
                                        name = name,
                                        mobile = mobile,
                                        passwordHash = password,
                                        role = role,
                                        status = status,
                                        registeredDeviceId = registeredDeviceId,
                                        isFirstTime = isFirstTime
                                    )
                                )
                            }
                        }
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    )
}
