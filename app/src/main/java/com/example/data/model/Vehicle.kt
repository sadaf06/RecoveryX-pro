package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "vehicles",
    indices = [
        androidx.room.Index(value = ["vehicleNumber"]),
        androidx.room.Index(value = ["customerName"]),
        androidx.room.Index(value = ["model"]),
        androidx.room.Index(value = ["status"])
    ]
)
data class Vehicle(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val firestoreId: String = "", // Used to map to Firestore document
    val customerName: String, // Owner
    val vehicleNumber: String, // Registration number
    val model: String = "",
    val status: String = "Active",
    val bankName: String,
    val pos: String,
    val emi: String,
    val engineNumber: String,
    val chassisNumber: String,
    val confirmerName: String,
    val loanNo: String = "",
    val creatorMobile: String = "admin",
    val fileName: String = "" // Tracker for which file this belongs to
)
