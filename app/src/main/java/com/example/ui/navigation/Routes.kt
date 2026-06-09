package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object LoginRoute

@Serializable
object AdminDashboardRoute

@Serializable
object UserManagementRoute

@Serializable
object ManagePermissionsRoute

@Serializable
object SearchRoute

@Serializable
data class VehicleDetailsRoute(val vehicleId: Int)

@Serializable
object ImportDataRoute

@Serializable
object HistoryRoute
