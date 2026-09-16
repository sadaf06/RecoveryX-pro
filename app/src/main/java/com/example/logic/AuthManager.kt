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
        try {
            com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val prefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    /** Restore in-memory session from SharedPreferences after process death / cold resume.
     * Returns restored user or null. Does NOT clear prefs — caller decides. */
    fun restoreFromPrefs(context: android.content.Context): User? {
        if (_currentUser.value != null) return _currentUser.value
        return try {
            val prefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
            val userJson = prefs.getString("logged_in_user", null) ?: return null
            val loginTime = prefs.getLong("login_time", 0L)
            val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000
            if (loginTime != 0L && System.currentTimeMillis() - loginTime > thirtyDaysMillis) {
                return null
            }
            if (userJson.isBlank()) return null
            val user = Json.decodeFromString<User>(userJson)
            _currentUser.value = user
            user
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun isSessionExpired(context: android.content.Context): Boolean {
        val prefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
        val loginTime = prefs.getLong("login_time", 0L)
        if (loginTime == 0L) return true
        val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000
        return System.currentTimeMillis() - loginTime > thirtyDaysMillis
    }
}
