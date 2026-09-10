package com.example.data.model

import kotlinx.serialization.Serializable

// Admin-level recharge record. Whole team (admin + staff + agents) is gated
// by the admin's document. Mirrors the web app's Subscription type.
@Serializable
data class Subscription(
    val adminMobile: String = "",
    val planName: String = "",
    val startsAt: Long = 0L,
    val expiresAt: Long = 0L,
    val status: String = "EXPIRED",
    val updatedAt: Long = 0L,
    val updatedBy: String = ""
)
