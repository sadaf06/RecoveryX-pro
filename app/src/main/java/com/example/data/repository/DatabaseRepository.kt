package com.example.data.repository

import com.example.data.dao.FieldPermissionsDao
import com.example.data.dao.UserDao
import com.example.data.dao.VehicleDao
import com.example.data.dao.SearchHistoryDao
import com.example.data.model.FieldPermissions
import com.example.data.model.User
import com.example.data.model.UserRole
import com.example.data.model.Subscription
import com.example.data.model.Vehicle
import com.example.data.model.SearchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DatabaseRepository(
    private val userDao: UserDao,
    private val vehicleDao: VehicleDao,
    private val fieldPermissionsDao: FieldPermissionsDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val firestoreSyncManager: FirestoreSyncManager? = null
) {
    init {
        firestoreSyncManager?.listenToPermissions { permissionsList ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    for (permissions in permissionsList) {
                        fieldPermissionsDao.insertPermissions(permissions)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // User
    val allUsers: Flow<List<User>> = userDao.getAllUsers()
    suspend fun getUserByMobile(mobile: String) = userDao.getUserByMobile(mobile)
    fun getUserById(id: Int) = userDao.getUserById(id)
    
    suspend fun insertUser(user: User) {
        userDao.insertUser(user)
        if (user.role == com.example.data.model.UserRole.ADMIN) {
            val key = "NORMAL_USER_${user.mobile}"
            val adminPerms = FieldPermissions(
                roleString = key,
                role = com.example.data.model.UserRole.NORMAL_USER,
                showCustomerName = true,
                showVehicleNumber = true,
                showBankName = true,
                showPos = true,
                showEmi = true,
                showEngineNumber = true,
                showChassisNumber = true,
                showConfirmerName = true,
                showLoanNo = true,
                showBucket = true,
                showFileName = true
            )
            fieldPermissionsDao.insertPermissions(adminPerms)
            firestoreSyncManager?.let { sync ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        sync.uploadPermissions(key, adminPerms)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
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
        if (user.authUid.isNotEmpty()) {
            try {
                firestoreSyncManager?.deleteUserSecret(user.authUid)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (user.role == com.example.data.model.UserRole.ADMIN) {
            userDao.deleteUsersByCreator(user.mobile)
            vehicleDao.deleteVehiclesByCreator(user.mobile)
            searchHistoryDao.deleteHistoryByCreator(user.mobile)
            fieldPermissionsDao.deletePermissionsByRoleString("NORMAL_USER_${user.mobile}")
        } else {
            searchHistoryDao.deleteHistoryByUser(user.mobile)
        }
        firestoreSyncManager?.let { sync ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sync.deleteUser(user.authUid.ifEmpty { user.mobile })
                    if (user.role == com.example.data.model.UserRole.ADMIN) {
                        sync.deleteUsersByCreator(user.mobile)
                        sync.deleteDataByCreator(user.mobile)
                        sync.deleteHistoryByCreator(user.mobile)
                        sync.deletePermissions("NORMAL_USER_${user.mobile}")
                    } else {
                        sync.deleteHistoryByUser(user.mobile)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    suspend fun getUserFromFirestore(mobile: String): User? {
        return firestoreSyncManager?.getUserFromFirestore(mobile)
    }

    // Secure mode: fetch UID-keyed profile + mirror into Room (matched by mobile)
    suspend fun getUserByUid(uid: String): User? {
        val remote = firestoreSyncManager?.getUserByUid(uid) ?: return null
        try {
            val existing = userDao.getUserByMobile(remote.mobile)
            if (existing == null) {
                userDao.insertUser(remote.copy(id = 0))
            } else {
                // Preserve locally cached password when the doc carries none (vault era)
                val pw = remote.passwordHash.ifEmpty { existing.passwordHash }
                userDao.updateUser(remote.copy(id = existing.id, passwordHash = pw))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return remote
    }

    // Cache the typed password after a successful online Auth login
    // (docs stay blank; this keeps offline login working)
    suspend fun cacheLocalPassword(mobile: String, password: String) {
        try {
            val existing = userDao.getUserByMobile(mobile) ?: return
            userDao.updateUser(existing.copy(passwordHash = password))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun getUserSecret(uid: String): String? {
        return firestoreSyncManager?.getUserSecret(uid)
    }

    suspend fun saveUserSecret(uid: String, password: String, adminMobile: String, mobile: String) {
        firestoreSyncManager?.saveUserSecret(uid, password, adminMobile, mobile)
    }

    suspend fun deleteUserSecret(uid: String) {
        firestoreSyncManager?.deleteUserSecret(uid)
    }

    suspend fun syncUsersFromFirestore(creatorFilter: String? = null) {
        firestoreSyncManager?.let { sync ->
            try {
                val users = sync.getAllUsersFromFirestore()
                val fetchedMobiles = mutableSetOf<String>()
                for (u in users) {
                    // Filter matching admin's users if creatorFilter is provided
                    if (creatorFilter != null && u.creatorMobile != creatorFilter) {
                        continue
                    }
                    fetchedMobiles.add(u.mobile)
                    val existing = userDao.getUserByMobile(u.mobile)
                    if (existing == null) {
                        userDao.insertUser(u)
                    } else {
                        // Preserve locally cached password when the doc carries none (vault era)
                        val pw = u.passwordHash.ifEmpty { existing.passwordHash }
                        userDao.updateUser(u.copy(id = existing.id, passwordHash = pw))
                    }
                }
                // Prune ghosts: server-deleted accounts lingering in Room.
                // Offline-created rows (empty authUid) are never touched.
                try {
                    for (local in userDao.getAllUsersSync()) {
                        if (local.mobile !in fetchedMobiles && local.authUid.isNotEmpty() &&
                            (creatorFilter == null || local.creatorMobile == creatorFilter || local.mobile == creatorFilter)
                        ) {
                            userDao.deleteUserById(local.id)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
    }

    // Vehicle
    val allVehicles: Flow<List<Vehicle>> = vehicleDao.getAllVehicles()
    fun searchVehicles(query: String, criteria: com.example.data.model.SearchCriteria = com.example.data.model.SearchCriteria.GENERAL, creatorMobile: String?): Flow<List<Vehicle>> {
        val flow = when (criteria) {
            com.example.data.model.SearchCriteria.GENERAL -> vehicleDao.searchVehiclesGeneral(query)
            com.example.data.model.SearchCriteria.ENGINE_LAST -> vehicleDao.searchVehiclesByEngineLast(query)
            com.example.data.model.SearchCriteria.CHASSIS_LAST -> vehicleDao.searchVehiclesByChassisLast(query)
            com.example.data.model.SearchCriteria.LOAN_START -> vehicleDao.searchVehiclesByLoanStart(query)
            com.example.data.model.SearchCriteria.VEHICLE_LAST -> vehicleDao.searchVehiclesByVehicleLast(query)
        }
        return flow.map { list ->
            if (creatorMobile == null) {
                list
            } else {
                list.filter { it.creatorMobile == creatorMobile }
            }
        }
    }

    suspend fun searchVehiclesOnline(query: String, criteria: com.example.data.model.SearchCriteria = com.example.data.model.SearchCriteria.GENERAL, creatorFilter: String?): List<Vehicle> {
        return firestoreSyncManager?.searchVehiclesOnline(query, criteria, creatorFilter) ?: emptyList()
    }

    // Server-side prefix search: downloads only matches (quota-safe for 40K+ datasets).
    // Falls back to empty list offline — caller uses local Room search instead.
    suspend fun searchVehiclesServer(query: String, criteria: com.example.data.model.SearchCriteria, creatorFilter: String?): List<Vehicle> {
        return firestoreSyncManager?.searchServer(query, criteria, creatorFilter) ?: emptyList()
    }
    fun getVehicleById(id: Int) = vehicleDao.getVehicleById(id)
    fun getVehiclesByNumber(number: String) = vehicleDao.getVehiclesByNumber(number)
    fun countAllVehiclesByAdmin(creatorMobile: String) = vehicleDao.countAllVehiclesByAdmin(creatorMobile)
    
    suspend fun insertVehicle(vehicle: Vehicle) {
        // Handle duplicate matching
        val existing = vehicleDao.getVehicleByNumberInFile(vehicle.vehicleNumber, vehicle.fileName, vehicle.creatorMobile)
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

    suspend fun clearAllDownloadedVehicles() {
        vehicleDao.deleteAllVehicles()
    }

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

    // Super admin: all local files across every admin (offline fallback)
    suspend fun getAllLocalUploadedFiles(context: android.content.Context): List<UploadedFileMeta> {
        val summaries = vehicleDao.getAllLocalFilesSummary()
        val prefs = context.getSharedPreferences("recoveryx_prefs", android.content.Context.MODE_PRIVATE)
        return summaries.map { s ->
            val time = prefs.getLong("file_time_${s.creatorMobile}_${s.fileName}", System.currentTimeMillis())
            UploadedFileMeta(
                fileName = s.fileName,
                adminMobile = s.creatorMobile,
                uploadedAt = time,
                recordCount = s.recordCount
            )
        }.sortedByDescending { it.uploadedAt }
    }

    suspend fun uploadFileMetadataAndVehicles(
        adminMobile: String,
        fileName: String,
        vehicles: List<Vehicle>,
        onChunk: ((done: Int, total: Int) -> Unit)? = null
    ) {
        firestoreSyncManager?.let { sync ->
            sync.saveFileMetadata(adminMobile, fileName, vehicles.size)
            sync.uploadVehiclesBatch(vehicles, onChunk)
        }
    }

    // Local-only insert (no Firestore push): avoids double-upload during import,
    // since the batch upload below writes everything once.
    suspend fun insertVehicleLocal(vehicle: Vehicle) {
        val existing = vehicleDao.getVehicleByNumberInFile(vehicle.vehicleNumber, vehicle.fileName, vehicle.creatorMobile)
        if (existing == null) {
            vehicleDao.insertVehicle(vehicle.copy(id = 0))
        } else {
            vehicleDao.updateVehicle(vehicle.copy(id = existing.id, firestoreId = existing.firestoreId))
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
    
    suspend fun forceSyncFromNetwork(creatorFilter: String? = null) {
        firestoreSyncManager?.let { syncReq ->
            try {
                val vehicles = syncReq.forceSyncVehiclesFromNetwork()
                for (v in vehicles) {
                    val existing = vehicleDao.getVehicleByNumberInFile(v.vehicleNumber, v.fileName, v.creatorMobile)
                    if (existing == null) {
                        vehicleDao.insertVehicle(v)
                    } else {
                        vehicleDao.updateVehicle(v.copy(id = existing.id))
                    }
                }
            } catch (e: Exception) {
                 e.printStackTrace()
                 throw e
            }
        }
    }
    


    // Permissions
    fun getPermissions(role: UserRole, adminMobile: String = "admin"): Flow<FieldPermissions?> {
        val key = "NORMAL_USER_$adminMobile"
        return fieldPermissionsDao.getPermissionsByRoleString(key)
    }
    suspend fun getPermissionsSync(role: UserRole, adminMobile: String = "admin"): FieldPermissions? {
        val key = "NORMAL_USER_$adminMobile"
        return fieldPermissionsDao.getPermissionsByRoleStringSync(key)
    }
    suspend fun insertPermissions(permissions: FieldPermissions) {
        fieldPermissionsDao.insertPermissions(permissions)
        firestoreSyncManager?.let { sync ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sync.uploadPermissions(permissions.roleString, permissions)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    suspend fun syncPermissionsFromFirestore(adminMobile: String) {
        firestoreSyncManager?.let { sync ->
            try {
                val perms = sync.getPermissions(adminMobile)
                if (perms != null) {
                    fieldPermissionsDao.insertPermissions(perms)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Server-side era: SYNC no longer bulk-downloads (quota-safe).
    // Refreshes small collections only; vehicles arrive per-search and get cached.
    // Subscriptions (Firestore-only, no local Room cache — checked online, fail-open offline)
    suspend fun getSubscription(adminMobile: String): Subscription? {
        return firestoreSyncManager?.getSubscription(adminMobile)
    }

    suspend fun countVehicles(creatorMobile: String?): Long? {
        return firestoreSyncManager?.countVehicles(creatorMobile)
    }

    // Region sync + local cache (no Firestore push back — no write burn)
    suspend fun syncRegionAndCache(creatorFilter: String?): Int {
        val sync = firestoreSyncManager ?: return 0
        val region = sync.syncRegionVehicles(creatorFilter)
        cacheServerVehicles(region)
        return region.size
    }

    suspend fun syncSearchMetadata(creatorFilter: String?, permAdminMobile: String?) {
        try {
            syncUsersFromFirestore(creatorFilter)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (permAdminMobile != null) {
            try {
                syncPermissionsFromFirestore(permAdminMobile)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        try {
            syncHistoriesFromFirestore(creatorFilter)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Cache server results locally WITHOUT pushing back to Firestore
    // (pushing would burn write quota on every search).
    suspend fun cacheServerVehicles(vehicles: List<Vehicle>) {
        for (v in vehicles) {
            val existing = vehicleDao.getVehicleByNumberInFile(v.vehicleNumber, v.fileName, v.creatorMobile)
            if (existing == null) {
                vehicleDao.insertVehicle(v.copy(id = 0))
            } else {
                vehicleDao.updateVehicle(
                    v.copy(id = existing.id, firestoreId = existing.firestoreId.ifEmpty { v.firestoreId })
                )
            }
        }
    }

    suspend fun saveSubscription(sub: Subscription) {
        firestoreSyncManager?.saveSubscription(sub)
    }

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

    suspend fun deleteHistoryItem(id: Int, firestoreId: String) {
        searchHistoryDao.deleteHistoryById(id)
        firestoreSyncManager?.let { sync ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (firestoreId.isNotEmpty()) {
                        sync.deleteHistoryItem(firestoreId)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    suspend fun deleteHistoryByUser(userMobile: String) {
        searchHistoryDao.deleteHistoryByUser(userMobile)
        firestoreSyncManager?.let { sync ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sync.deleteHistoryByUser(userMobile)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    suspend fun clearHistoryForAdmin(adminMobile: String) {
        searchHistoryDao.deleteHistoryByCreator(adminMobile)
        firestoreSyncManager?.let { sync ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sync.deleteHistoryByCreator(adminMobile)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    suspend fun clearAllHistory() {
        searchHistoryDao.clearAllHistory()
        firestoreSyncManager?.let { sync ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sync.clearAllHistories()
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
                throw e
            }
        }
    }
}
