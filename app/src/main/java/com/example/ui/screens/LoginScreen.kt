package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.repository.DatabaseRepository
import com.example.logic.AuthManager
import kotlinx.coroutines.launch
import com.example.data.model.User
import com.example.data.model.UserRole
import androidx.lifecycle.ViewModelProvider

class LoginViewModel(private val repository: DatabaseRepository) : ViewModel() {
    var error by mutableStateOf<String?>(null)
    var isLoggingIn by mutableStateOf(false)
    
    // OTP States
    var otpRequired by mutableStateOf(false)
    var generatedOtp by mutableStateOf("")
    var lastAuthenticatedUser by mutableStateOf<User?>(null)
    var isVerifying by mutableStateOf(false)

    fun checkAutoLogin(context: android.content.Context, onSuccess: (UserRole) -> Unit) {
        val prefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
        val mobile = prefs.getString("logged_in_mobile", null)
        val loginTime = prefs.getLong("login_time", 0)

        if (mobile != null) {
            val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000
            if (System.currentTimeMillis() - loginTime > thirtyDaysMillis) {
                prefs.edit().clear().apply()
                return // Token expired
            }

            viewModelScope.launch {
                isLoggingIn = true
                var user = repository.getUserByMobile(mobile) // Try offline first to be quick
                if (user == null) {
                    try {
                        user = repository.getUserFromFirestore(mobile)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                if (user != null) {
                    if (user.status == com.example.data.model.UserStatus.DISABLED) {
                        error = "Account is deactivated. Contact Admin."
                    } else {
                        val currentDeviceId = android.provider.Settings.Secure.getString(
                            context.contentResolver,
                            android.provider.Settings.Secure.ANDROID_ID
                        ) ?: "device_id_unknown"

                        // For Auto login, we skip password since token is valid, but we still verify device if not admin
                        if (user.role == UserRole.ADMIN || user.mobile == "admin") {
                            AuthManager.login(context, user)
                            onSuccess(user.role)
                        } else if (user.registeredDeviceId == currentDeviceId) {
                            AuthManager.login(context, user)
                            onSuccess(user.role)
                        } else {
                            // If device ID changed somehow, they need to logout/re-verify
                            prefs.edit().clear().apply()
                        }
                    }
                }
                isLoggingIn = false
            }
        }
    }

    fun login(context: android.content.Context, mobile: String, pass: String, onSuccess: (UserRole) -> Unit) {
        if (mobile.isBlank() || pass.isBlank()) {
            error = "Fields cannot be empty"
            return
        }
        isLoggingIn = true
        error = null
        viewModelScope.launch {
            var user: User? = null
            try {
                // 1. Authenticate / Fetch first from firebase database online
                val onlineUser = repository.getUserFromFirestore(mobile)
                if (onlineUser != null) {
                    user = onlineUser
                    // Synced update to local cache
                    repository.updateUser(onlineUser)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (user == null) {
                // 2. Fallback to local cache for offline mode login
                user = repository.getUserByMobile(mobile)
            }

            isLoggingIn = false

            if (user != null && user.passwordHash == pass) {
                if (user.status == com.example.data.model.UserStatus.DISABLED) {
                    error = "Account is deactivated. Contact Admin."
                    return@launch
                }

                // Super Admin role bypasses device restriction lock to ease administration
                if (user.role == UserRole.ADMIN || user.mobile == "admin") {
                    error = null
                    AuthManager.login(context, user)
                    onSuccess(user.role)
                    return@launch
                }

                // Normal / Office staff locking check
                val currentDeviceId = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ) ?: "device_id_unknown"

                if (user.registeredDeviceId.isNotEmpty()) {
                    if (user.registeredDeviceId != currentDeviceId) {
                        error = "This account is registered on another device. Please ask Admin to deregister your device from Admin console."
                    } else {
                        error = null
                        AuthManager.login(context, user)
                        onSuccess(user.role)
                    }
                } else {
                    // First time login or device deregistered -> Require OTP & register device
                    lastAuthenticatedUser = user
                    generatedOtp = (100000..999999).random().toString()
                    otpRequired = true
                }
            } else {
                error = "Invalid mobile number or password"
            }
        }
    }

    fun verifyOtpAndRegisterDevice(context: android.content.Context, enteredOtp: String, onSuccess: (UserRole) -> Unit) {
        val user = lastAuthenticatedUser ?: return
        if (enteredOtp == generatedOtp) {
            isVerifying = true
            error = null
            viewModelScope.launch {
                try {
                    val currentDeviceId = android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    ) ?: "device_id_unknown"

                    val updatedUser = user.copy(
                        registeredDeviceId = currentDeviceId,
                        isFirstTime = false
                    )
                    
                    // Upload/update to both local Room DB & cloud Firestore
                    repository.updateUser(updatedUser)
                    
                    error = null
                    otpRequired = false
                    AuthManager.login(context, updatedUser)
                    onSuccess(updatedUser.role)
                } catch (e: Exception) {
                    error = "Failed to register Device ID: ${e.localizedMessage}"
                } finally {
                    isVerifying = false
                }
            }
        } else {
            error = "Invalid OTP code. Please verify the code displayed in simulated SMS below."
        }
    }
    
    fun cancelOtp() {
        otpRequired = false
        generatedOtp = ""
        lastAuthenticatedUser = null
        error = null
    }

    class Factory(private val repository: DatabaseRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LoginViewModel(repository) as T
    }
}

@Composable
fun LoginScreen(
    repository: DatabaseRepository,
    onNavigateToAdmin: () -> Unit,
    onNavigateToSearch: () -> Unit
) {
    val viewModel: LoginViewModel = viewModel(factory = LoginViewModel.Factory(repository))
    val context = LocalContext.current
    var mobile by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var enteredOtp by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize()) {
        LaunchedEffect(Unit) {
            viewModel.checkAutoLogin(context) { role ->
                if (role == UserRole.ADMIN) {
                    onNavigateToAdmin()
                } else {
                    onNavigateToSearch()
                }
            }
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("RecoveryX Pro", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            
            Spacer(modifier = Modifier.height(32.dp))

            if (!viewModel.otpRequired) {
                // Standard Credentials form
                OutlinedTextField(
                    value = mobile,
                    onValueChange = { mobile = it },
                    label = { Text("Mobile Number") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (viewModel.error != null) {
                    Text(viewModel.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        viewModel.login(context, mobile, password) { role ->
                            if (role == UserRole.ADMIN) {
                                onNavigateToAdmin()
                            } else {
                                onNavigateToSearch()
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !viewModel.isLoggingIn
                ) {
                    if (viewModel.isLoggingIn) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Login", modifier = Modifier.padding(4.dp))
                    }
                }
            } else {
                // OTP View & Device Register Form
                Card(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("First Time Login Detected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "This device needs to be verified & registered. A simulated OTP SMS code has been generated & shown below:",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            viewModel.generatedOtp, 
                            style = MaterialTheme.typography.headlineLarge, 
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 4.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = enteredOtp,
                    onValueChange = { enteredOtp = it },
                    label = { Text("Enter OTP Code") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (viewModel.error != null) {
                    Text(viewModel.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        viewModel.verifyOtpAndRegisterDevice(context, enteredOtp) { role ->
                            if (role == UserRole.ADMIN) {
                                onNavigateToAdmin()
                            } else {
                                onNavigateToSearch()
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !viewModel.isVerifying
                ) {
                    if (viewModel.isVerifying) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Verify & Register Device")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = { viewModel.cancelOtp() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Go Back")
                }
            }
        }
    }
}
