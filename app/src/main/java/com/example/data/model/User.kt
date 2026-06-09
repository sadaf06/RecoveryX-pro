package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class UserRole {
    ADMIN,
    OFFICE_STAFF,
    NORMAL_USER
}

enum class UserStatus {
    ACTIVE,
    DISABLED
}

@Serializable
@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val mobile: String, // Also the login username
    val passwordHash: String, // In a real app we'd hash, here mock it
    val role: UserRole,
    val status: UserStatus = UserStatus.ACTIVE,
    val registeredDeviceId: String = "",
    val isFirstTime: Boolean = true,
    val creatorMobile: String = "admin"
)
