package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "search_histories")
data class SearchHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val firestoreId: String = "",
    val userMobile: String,
    val userName: String,
    val vehicleNumber: String,
    val model: String = "",
    val timestamp: String,
    val creatorMobile: String = ""
)
