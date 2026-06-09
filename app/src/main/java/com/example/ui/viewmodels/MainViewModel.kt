package com.example.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.User
import com.example.data.repository.DatabaseRepository
import kotlinx.coroutines.launch
import com.example.data.model.UserRole
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.data.model.UserStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

class MainViewModel(private val repository: DatabaseRepository) : ViewModel() {

    init {
        // Create default admin user if none exists
        viewModelScope.launch {
            val admin = repository.getUserByMobile("admin")
            if (admin == null) {
                repository.insertUser(
                    User(
                        name = "Super Admin",
                        mobile = "admin",
                        passwordHash = "admin123", // mocked password
                        role = UserRole.ADMIN,
                        status = UserStatus.ACTIVE
                    )
                )
            }
            
            // Sync vehicles
            repository.syncVehiclesFromFirestore()
        }
    }

    class Factory(private val repository: DatabaseRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            return MainViewModel(repository) as T
        }
    }
}
