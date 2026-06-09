package com.example.data.repository

import com.example.data.dao.FieldPermissionsDao
import com.example.data.dao.UserDao
import com.example.data.dao.VehicleDao
import com.example.data.dao.SearchHistoryDao
import com.example.data.model.FieldPermissions
import com.example.data.model.User
import com.example.data.model.UserRole
import com.example.data.model.Vehicle
import com.example.data.model.SearchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class DatabaseRepository(
    private val userDao: UserDao,
    private val vehicleDao: VehicleDao,
    private val fieldPermissionsDao: FieldPermissionsDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val firestoreSyncManager: FirestoreSyncManager? = null
) {
    // User
    val allUsers: Flow<List<User>> = userDao.getAllUsers()
    suspend fun getUserByMobile(mobile: String) = userDao.getUserByMobile(mobile)
    fun getUserById(id: Int) = userDao.getUserById(id)
    
    suspend fun insertUser(user: User) {
        userDao.insertUser(user)
        firestoreSyncManager?.let { sync ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sync.uploadUser(user)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    suspend fun updateUser(user: User) {
        userDao.updateUser(user)
        firestoreSyncManager?.let { sync ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sync.uploadUser(user)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    suspend fun deleteUser(user: User) {
        userDao.deleteUserById(user.id)
        firestoreSyncManager?.let { sync ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sync.deleteUser(user.mobile)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    suspend fun getUserFromFirestore(mobile: String): User? {
        return firestoreSyncManager?.getUserFromFirestore(mobile)
    }

    suspend fun syncUsersFromFirestore(creatorFilter: String? = null) {
        firestoreSyncManager?.let { sync ->
            try {
                val users = sync.getAllUsersFromFirestore()
                for (u in users) {
                    // Filter matching admin's users if creatorFilter is provided
                    if (creatorFilter != null && u.creatorMobile != creatorFilter) {
                        continue
                    }
                    val existing = userDao.getUserByMobile(u.mobile)
                    if (existing == null) {
                        userDao.insertUser(u)
                    } else {
                        userDao.updateUser(u.copy(id = existing.id))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Vehicle
    val allVehicles: Flow<List<Vehicle>> = vehicleDao.getAllVehicles()
    fun searchVehicles(query: String, criteria: com.example.data.model.SearchCriteria = com.example.data.model.SearchCriteria.GENERAL): Flow<List<Vehicle>> {
        return when (criteria) {
            com.example.data.model.SearchCriteria.GENERAL -> vehicleDao.searchVehiclesGeneral(query)
            com.example.data.model.SearchCriteria.ENGINE_LAST -> vehicleDao.searchVehiclesByEngineLast(query)
            com.example.data.model.SearchCriteria.CHASSIS_LAST -> vehicleDao.searchVehiclesByChassisLast(query)
            com.example.data.model.SearchCriteria.LOAN_START -> vehicleDao.searchVehiclesByLoanStart(query)
            com.example.data.model.SearchCriteria.VEHICLE_LAST -> vehicleDao.searchVehiclesByVehicleLast(query)
        }
    }

    suspend fun searchVehiclesOnline(query: String, criteria: com.example.data.model.SearchCriteria = com.example.data.model.SearchCriteria.GENERAL, creatorFilter: String?): List<Vehicle> {
        return firestoreSyncManager?.searchVehiclesOnline(query, criteria, creatorFilter) ?: emptyList()
    }
    fun getVehicleById(id: Int) = vehicleDao.getVehicleById(id)
    
    suspend fun insertVehicle(vehicle: Vehicle) {
        // Handle duplicate matching
        val existing = vehicleDao.getVehicleByNumber(vehicle.vehicleNumber)
        val finalVehicle = if (existing != null) {
            val updated = vehicle.copy(id = existing.id, firestoreId = existing.firestoreId)
            vehicleDao.updateVehicle(updated)
            updated
        } else {
            vehicleDao.insertVehicle(vehicle)
            vehicle
        }
        
        // Push to Firestore in background
        firestoreSyncManager?.let { syncReq ->
             CoroutineScope(Dispatchers.IO).launch {
                 try {
                     val fId = syncReq.uploadVehicle(finalVehicle)
                     if (fId != finalVehicle.firestoreId) {
                         vehicleDao.updateVehicle(finalVehicle.copy(firestoreId = fId))
                     }
                 } catch (e: Exception) {
                     e.printStackTrace()
                 }
             }
        }
    }
    suspend fun deleteVehicle(id: Int) = vehicleDao.deleteVehicleById(id)

    suspend fun getFirestoreUploadedFiles(adminMobile: String?): List<UploadedFileMeta> {
        return firestoreSyncManager?.getUploadedFiles(adminMobile) ?: emptyList()
    }

    suspend fun isFirestoreFileUploaded(adminMobile: String, fileName: String): Boolean {
        return firestoreSyncManager?.isFileUploaded(adminMobile, fileName) ?: false
    }

    suspend fun countVehiclesByFile(creatorMobile: String, fileName: String): Int {
        return vehicleDao.countVehiclesByFile(creatorMobile, fileName)
    }

    suspend fun getLocalUploadedFiles(creatorMobile: String, context: android.content.Context): List<UploadedFileMeta> {
        val summaries = vehicleDao.getLocalFilesSummary(creatorMobile)
        val prefs = context.getSharedPreferences("recoveryx_prefs", android.content.Context.MODE_PRIVATE)
        return summaries.map { s ->
            val time = prefs.getLong("file_time_${creatorMobile}_${s.fileName}", System.currentTimeMillis())
            UploadedFileMeta(
                fileName = s.fileName,
                adminMobile = creatorMobile,
                uploadedAt = time,
                recordCount = s.recordCount
            )
        }.sortedByDescending { it.uploadedAt }
    }

    suspend fun uploadFileMetadataAndVehicles(adminMobile: String, fileName: String, vehicles: List<Vehicle>) {
        firestoreSyncManager?.let { sync ->
            sync.saveFileMetadata(adminMobile, fileName, vehicles.size)
            sync.uploadVehiclesBatch(vehicles)
        }
    }

    suspend fun deleteLocalFileAndItsData(adminMobile: String, fileName: String) {
        vehicleDao.deleteVehiclesByFile(adminMobile, fileName)
    }

    suspend fun deleteCloudFileAndItsData(adminMobile: String, fileName: String) {
        firestoreSyncManager?.deleteFileAndVehicles(adminMobile, fileName)
    }

    suspend fun deleteFileAndItsData(adminMobile: String, fileName: String) {
        vehicleDao.deleteVehiclesByFile(adminMobile, fileName)
        firestoreSyncManager?.deleteFileAndVehicles(adminMobile, fileName)
    }
    
    suspend fun syncVehiclesFromFirestore(creatorFilter: String? = null) {
        firestoreSyncManager?.let { syncReq ->
            try {
                val vehicles = syncReq.getVehicles()
                for (v in vehicles) {
                    // Filter matching admin's vehicles if creatorFilter is provided
                    if (creatorFilter != null && v.creatorMobile != creatorFilter) {
                        continue
                    }
                    val existing = vehicleDao.getVehicleByNumber(v.vehicleNumber)
                    if (existing == null) {
                        vehicleDao.insertVehicle(v)
                    } else {
                        vehicleDao.updateVehicle(v.copy(id = existing.id))
                    }
                }
            } catch (e: Exception) {
                 e.printStackTrace()
            }
        }
    }

    // Permissions
    fun getPermissions(role: UserRole) = fieldPermissionsDao.getPermissionsForRole(role)
    suspend fun getPermissionsSync(role: UserRole) = fieldPermissionsDao.getPermissionsForRoleSync(role)
    suspend fun insertPermissions(permissions: FieldPermissions) = fieldPermissionsDao.insertPermissions(permissions)

    // Search History Logs
    fun getAllHistory(): Flow<List<SearchHistory>> = searchHistoryDao.getAllHistory()
    fun getHistoryForAdmin(adminMobile: String): Flow<List<SearchHistory>> = searchHistoryDao.getHistoryForAdmin(adminMobile)
    
    suspend fun insertHistory(history: SearchHistory) {
        searchHistoryDao.insertHistory(history)
        firestoreSyncManager?.let { sync ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sync.uploadHistory(history)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    suspend fun syncHistoriesFromFirestore(creatorFilter: String? = null) {
        firestoreSyncManager?.let { sync ->
            try {
                val histories = sync.getAllHistories()
                for (h in histories) {
                    if (creatorFilter != null && h.creatorMobile != creatorFilter) {
                        continue
                    }
                    val existing = searchHistoryDao.getHistoryByFirestoreId(h.firestoreId)
                    if (existing == null) {
                        searchHistoryDao.insertHistory(h)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
