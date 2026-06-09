package com.example.logic

import com.example.data.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AuthManager {
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    fun login(context: android.content.Context, user: User) {
        _currentUser.value = user
        val prefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("logged_in_mobile", user.mobile)
            .putLong("login_time", System.currentTimeMillis())
            .apply()
    }

    fun logout(context: android.content.Context) {
        _currentUser.value = null
        val prefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
