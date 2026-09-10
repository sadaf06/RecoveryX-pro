package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawBehind
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
        val userJson = prefs.getString("logged_in_user", null)
        val mobile = prefs.getString("logged_in_mobile", null)
        val loginTime = prefs.getLong("login_time", 0)

        val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000
        if (System.currentTimeMillis() - loginTime > thirtyDaysMillis) {
            prefs.edit().clear().apply()
            return // Token expired
        }

        if (userJson != null) {
            try {
                val user = kotlinx.serialization.json.Json.decodeFromString<com.example.data.model.User>(userJson)
                AuthManager.login(context, user)
                onSuccess(user.role)
            } catch (e: Exception) {
                e.printStackTrace()
                prefs.edit().clear().apply()
            }
        } else if (mobile != null) {
            viewModelScope.launch {
                isLoggingIn = true
                val user = repository.getUserByMobile(mobile)
                if (user != null) {
                    AuthManager.login(context, user)
                    onSuccess(user.role)
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
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

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
                            androidx.compose.ui.graphics.Color(0x3D4F7CFF), 
                            androidx.compose.ui.graphics.Color.Transparent
                        )
                    ),
                    radius = size.width * 0.9f,
                    center = androidx.compose.ui.geometry.Offset(x = size.width * 0.1f, y = size.height * 0.2f)
                )
                // Blur ambient lighting spot 2
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            androidx.compose.ui.graphics.Color(0x267B61FF), 
                            androidx.compose.ui.graphics.Color.Transparent
                        )
                    ),
                    radius = size.width * 0.8f,
                    center = androidx.compose.ui.geometry.Offset(x = size.width * 0.9f, y = size.height * 0.8f)
                )
            }
    ) {
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
                .padding(24.dp)
                .systemBarsPadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Logo & Branding with Gradient Accent
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(
                                    androidx.compose.ui.graphics.Color(0xFF4F7CFF),
                                    androidx.compose.ui.graphics.Color(0xFF7B61FF)
                                )
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "RECOVERYX PRO",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = androidx.compose.ui.graphics.Color.White,
                    letterSpacing = 1.5.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Premium Vehicle Recovery Database",
                style = MaterialTheme.typography.bodyMedium,
                color = androidx.compose.ui.graphics.Color(0xFFA1A8B8),
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Visually Frosted Liquid Glass Card containing form
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(
                    containerColor = androidx.compose.ui.graphics.Color(0x1F1A2234) // Soft translucent base
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            androidx.compose.ui.graphics.Color(0x3DFFFFFF), // Reflective top white border
                            androidx.compose.ui.graphics.Color(0x0AFFFFFF)  // Very dark bottom edge
                        )
                    )
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!viewModel.otpRequired) {
                        Text(
                            text = "Access Console",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Styled Input fields
                        OutlinedTextField(
                            value = mobile,
                            onValueChange = { mobile = it },
                            label = { Text("Mobile Number") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Devices,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color(0xFFA1A8B8)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color(0x0F0D111A),
                                focusedContainerColor = androidx.compose.ui.graphics.Color(0x1F0D111A),
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color(0x1AFFFFFF),
                                focusedBorderColor = androidx.compose.ui.graphics.Color(0xFF4F7CFF),
                                unfocusedTextColor = androidx.compose.ui.graphics.Color.White,
                                focusedTextColor = androidx.compose.ui.graphics.Color.White,
                                unfocusedLabelColor = androidx.compose.ui.graphics.Color(0xFFA1A8B8),
                                focusedLabelColor = androidx.compose.ui.graphics.Color(0xFF4F7CFF)
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.LockOpen,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color(0xFFA1A8B8)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color(0x0F0D111A),
                                focusedContainerColor = androidx.compose.ui.graphics.Color(0x1F0D111A),
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color(0x1AFFFFFF),
                                focusedBorderColor = androidx.compose.ui.graphics.Color(0xFF4F7CFF),
                                unfocusedTextColor = androidx.compose.ui.graphics.Color.White,
                                focusedTextColor = androidx.compose.ui.graphics.Color.White,
                                unfocusedLabelColor = androidx.compose.ui.graphics.Color(0xFFA1A8B8),
                                focusedLabelColor = androidx.compose.ui.graphics.Color(0xFF4F7CFF)
                            )
                        )

                        if (viewModel.error != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = viewModel.error!!,
                                color = androidx.compose.ui.graphics.Color(0xFFFF5D73),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        // High fidelity gradient button
                        Button(
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                viewModel.login(context, mobile, password) { role ->
                                    if (role == UserRole.ADMIN) {
                                        onNavigateToAdmin()
                                    } else {
                                        onNavigateToSearch()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            contentPadding = PaddingValues(),
                            enabled = !viewModel.isLoggingIn
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                            colors = listOf(
                                                androidx.compose.ui.graphics.Color(0xFF4F7CFF),
                                                androidx.compose.ui.graphics.Color(0xFF7B61FF)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (viewModel.isLoggingIn) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = androidx.compose.ui.graphics.Color.White
                                    )
                                } else {
                                    Text(
                                        text = "Login to Account",
                                        fontWeight = FontWeight.ExtraBold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = androidx.compose.ui.graphics.Color.White
                                    )
                                }
                            }
                        }

                    } else {
                        // OTP View & Device Register Form
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color(0xFF4F7CFF),
                            modifier = Modifier.size(54.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = "First Time Login", 
                            style = MaterialTheme.typography.titleMedium, 
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "Your device needs to be paired. Use the verification PIN generated below:",
                            style = MaterialTheme.typography.bodySmall,
                            color = androidx.compose.ui.graphics.Color(0xFFA1A8B8),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(20.dp))

                        // Ambient Glowing OTP display card
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = androidx.compose.ui.graphics.Color(0x334F7CFF)
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0x664F7CFF)),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = viewModel.generatedOtp, 
                                style = MaterialTheme.typography.headlineLarge, 
                                fontWeight = FontWeight.Black,
                                color = androidx.compose.ui.graphics.Color.White,
                                letterSpacing = 6.sp,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedTextField(
                            value = enteredOtp,
                            onValueChange = { enteredOtp = it },
                            label = { Text("6-Digit OTP Code") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color(0x0F0D111A),
                                focusedContainerColor = androidx.compose.ui.graphics.Color(0x1F0D111A),
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color(0x1AFFFFFF),
                                focusedBorderColor = androidx.compose.ui.graphics.Color(0xFF4F7CFF),
                                unfocusedTextColor = androidx.compose.ui.graphics.Color.White,
                                focusedTextColor = androidx.compose.ui.graphics.Color.White,
                                unfocusedLabelColor = androidx.compose.ui.graphics.Color(0xFFA1A8B8),
                                focusedLabelColor = androidx.compose.ui.graphics.Color(0xFF4F7CFF)
                            )
                        )

                        if (viewModel.error != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = viewModel.error!!, 
                                color = androidx.compose.ui.graphics.Color(0xFFFF5D73), 
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                viewModel.verifyOtpAndRegisterDevice(context, enteredOtp) { role ->
                                    if (role == UserRole.ADMIN) {
                                        onNavigateToAdmin()
                                    } else {
                                        onNavigateToSearch()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            contentPadding = PaddingValues(),
                            enabled = !viewModel.isVerifying
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                            colors = listOf(
                                                androidx.compose.ui.graphics.Color(0xFF4F7CFF),
                                                androidx.compose.ui.graphics.Color(0xFF7B61FF)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (viewModel.isVerifying) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp), 
                                        color = androidx.compose.ui.graphics.Color.White
                                    )
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.LockOpen, 
                                            contentDescription = null, 
                                            tint = androidx.compose.ui.graphics.Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Verify & Pair Device", 
                                            fontWeight = FontWeight.ExtraBold,
                                            color = androidx.compose.ui.graphics.Color.White
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        TextButton(
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                viewModel.cancelOtp()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Go Back", 
                                color = androidx.compose.ui.graphics.Color(0xFFA1A8B8), 
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Frosted Full-screen Loader Overlay
        if (viewModel.isLoggingIn || viewModel.isVerifying) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
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
                            text = if (viewModel.isVerifying) "Verifying..." else "Authorizing...",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                    }
                }
            }
        }
    }
}
