package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "field_permissions")
data class FieldPermissions(
    @PrimaryKey val role: UserRole, // e.g. NORMAL_USER
    val showCustomerName: Boolean = true,
    val showVehicleNumber: Boolean = true,
    val showBankName: Boolean = true,
    val showPos: Boolean = false,
    val showEmi: Boolean = false,
    val showEngineNumber: Boolean = false,
    val showChassisNumber: Boolean = false,
    val showConfirmerName: Boolean = false
)
