package com.example.logic

import com.example.data.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

object AuthManager {
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    fun login(context: android.content.Context, user: User) {
        _currentUser.value = user
        val prefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
        val userJson = try {
            Json.encodeToString(user)
        } catch (e: Exception) {
            ""
        }
        prefs.edit()
            .putString("logged_in_mobile", user.mobile)
            .putString("logged_in_user", userJson)
            .putLong("login_time", System.currentTimeMillis())
            .apply()
    }

    fun logout(context: android.content.Context) {
        _currentUser.value = null
        val prefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
