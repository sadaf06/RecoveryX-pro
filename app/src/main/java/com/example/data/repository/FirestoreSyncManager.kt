package com.example.data.repository

import com.example.data.model.User
import com.example.data.model.UserRole
import com.example.data.model.UserStatus
import com.example.data.model.Vehicle
import com.example.data.model.SearchHistory
import com.example.data.model.SearchCriteria
import com.example.data.model.FieldPermissions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class FirestoreSyncManager {
    
    private val db: FirebaseFirestore by lazy {
        val firestore = FirebaseFirestore.getInstance()
        val settings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            .build()
        firestore.firestoreSettings = settings
        firestore
    }
        
    private val vehiclesCollection get() = db.collection("vehicles")
    private val usersCollection get() = db.collection("users")
    private val historiesCollection get() = db.collection("search_histories")

    // Users Sync
    suspend fun uploadUser(user: User) {
        val mappedData = hashMapOf(
            "name" to user.name,
            "mobile" to user.mobile,
            "password" to user.passwordHash,
            "role" to user.role.name,
            "status" to user.status.name,
            "registered_device_id" to user.registeredDeviceId,
            "is_first_time" to user.isFirstTime,
            "creator_mobile" to user.creatorMobile,
            "email" to user.email
        )
        usersCollection.document(user.mobile).set(mappedData).await()
    }

    suspend fun deleteUser(mobile: String) {
        usersCollection.document(mobile).delete().await()
    }

    suspend fun deleteUsersByCreator(creatorMobile: String) {
        val usersQuery = usersCollection
            .whereEqualTo("creator_mobile", creatorMobile)
            .get().await()
        val docChunks = usersQuery.documents.chunked(500)
        for (chunk in docChunks) {
            val batch = db.batch()
            for (doc in chunk) {
                batch.delete(doc.reference)
            }
            batch.commit().await()
        }
    }

    suspend fun deleteDataByCreator(creatorMobile: String) {
        // 1. Delete all file metadata uploaded by this admin
        val filesQuery = filesCollection
            .whereEqualTo("admin_mobile", creatorMobile)
            .get().await()
        for (doc in filesQuery.documents) {
            filesCollection.document(doc.id).delete().await()
        }

        // 2. Delete all vehicles created by this admin in batches
        val vehicleQuery = vehiclesCollection
            .whereEqualTo("creator_mobile", creatorMobile)
            .get().await()
        val docChunks = vehicleQuery.documents.chunked(500)
        for (chunk in docChunks) {
            val batch = db.batch()
            for (doc in chunk) {
                batch.delete(doc.reference)
            }
            batch.commit().await()
        }
    }

    suspend fun getUserFromFirestore(mobile: String): User? {
        val doc = usersCollection.document(mobile).get().await()
        if (!doc.exists()) return null
        
        val name = doc.getString("name") ?: ""
        val pass = doc.getString("password") ?: ""
        val roleStr = doc.getString("role") ?: UserRole.NORMAL_USER.name
        val statusStr = doc.getString("status") ?: UserStatus.ACTIVE.name
        val reqDeviceId = doc.getString("registered_device_id") ?: ""
        val isFirstTime = doc.getBoolean("is_first_time") ?: true
        val creatorMobile = doc.getString("creator_mobile") ?: "admin"
        val email = doc.getString("email") ?: ""
        
        return User(
            name = name,
            mobile = mobile,
            passwordHash = pass,
            role = enumValueOf(roleStr),
            status = enumValueOf(statusStr),
            registeredDeviceId = reqDeviceId,
            isFirstTime = isFirstTime,
            creatorMobile = creatorMobile,
            email = email
        )
    }

    suspend fun getAllUsersFromFirestore(): List<User> {
        val result = usersCollection.get().await()
        return result.documents.map { doc ->
            val num = doc.id
            val name = doc.getString("name") ?: ""
            val pass = doc.getString("password") ?: ""
            val roleStr = doc.getString("role") ?: UserRole.NORMAL_USER.name
            val statusStr = doc.getString("status") ?: UserStatus.ACTIVE.name
            val reqDeviceId = doc.getString("registered_device_id") ?: ""
            val isFirstTime = doc.getBoolean("is_first_time") ?: true
            val creatorMobile = doc.getString("creator_mobile") ?: "admin"
            val email = doc.getString("email") ?: ""
            
            User(
                name = name,
                mobile = num,
                passwordHash = pass,
                role = enumValueOf(roleStr),
                status = enumValueOf(statusStr),
                registeredDeviceId = reqDeviceId,
                isFirstTime = isFirstTime,
                creatorMobile = creatorMobile,
                email = email
            )
        }
    }

    // Vehicles Sync
    suspend fun uploadVehicle(vehicle: Vehicle): String {
        val mappedData = hashMapOf(
            "registration_number" to vehicle.vehicleNumber,
            "owner" to vehicle.customerName,
            "model" to vehicle.model,
            "status" to vehicle.status,
            "bank_name" to vehicle.bankName,
            "pos" to vehicle.pos,
            "emi" to vehicle.emi,
            "engine_number" to vehicle.engineNumber,
            "chassis_number" to vehicle.chassisNumber,
            "confirmer_name" to vehicle.confirmerName,
            "loan_no" to vehicle.loanNo,
            "creator_mobile" to vehicle.creatorMobile,
            "file_name" to vehicle.fileName,
            "bucket" to vehicle.bucket
        )
        
        return if (vehicle.firestoreId.isNotEmpty()) {
            vehiclesCollection.document(vehicle.firestoreId).set(mappedData).await()
            vehicle.firestoreId
        } else {
            val docRef = vehiclesCollection.add(mappedData).await()
            docRef.id
        }
    }

    suspend fun uploadVehiclesBatch(vehicles: List<Vehicle>) {
        if (vehicles.isEmpty()) return
        val chunks = vehicles.chunked(500)
        for (chunk in chunks) {
            val batch = db.batch()
            for (v in chunk) {
                val docRef = vehiclesCollection.document()
                val mappedData = hashMapOf(
                    "registration_number" to v.vehicleNumber,
                    "owner" to v.customerName,
                    "model" to v.model,
                    "status" to v.status,
                    "bank_name" to v.bankName,
                    "pos" to v.pos,
                    "emi" to v.emi,
                    "engine_number" to v.engineNumber,
                    "chassis_number" to v.chassisNumber,
                    "confirmer_name" to v.confirmerName,
                    "loan_no" to v.loanNo,
                    "creator_mobile" to v.creatorMobile,
                    "file_name" to v.fileName,
                    "bucket" to v.bucket
                )
                batch.set(docRef, mappedData)
            }
            batch.commit().await()
        }
    }

    suspend fun forceSyncVehiclesFromNetwork(): List<Vehicle> {
        val result = vehiclesCollection.get(com.google.firebase.firestore.Source.SERVER).await()
        return result.documents.mapNotNull { doc ->
            val num = doc.getString("registration_number") ?: return@mapNotNull null
            val owner = doc.getString("owner") ?: ""
            Vehicle(
                firestoreId = doc.id,
                vehicleNumber = num,
                customerName = owner,
                model = doc.getString("model") ?: "",
                status = doc.getString("status") ?: "Active",
                bankName = doc.getString("bank_name") ?: "",
                pos = doc.getString("pos") ?: "",
                emi = doc.getString("emi") ?: "",
                engineNumber = doc.getString("engine_number") ?: "",
                chassisNumber = doc.getString("chassis_number") ?: "",
                confirmerName = doc.getString("confirmer_name") ?: "",
                loanNo = doc.getString("loan_no") ?: "",
                creatorMobile = doc.getString("creator_mobile") ?: "admin",
                fileName = doc.getString("file_name") ?: "",
                bucket = doc.getString("bucket") ?: ""
            )
        }
    }

    // Excel/CSV Files Metadata Sync
    private val filesCollection get() = db.collection("uploaded_files")

    suspend fun isFileUploaded(adminMobile: String, fileName: String): Boolean {
        val docId = "${adminMobile}_${fileName}"
        val doc = filesCollection.document(docId).get().await()
        return doc.exists()
    }

    suspend fun saveFileMetadata(adminMobile: String, fileName: String, recordCount: Int) {
        val docId = "${adminMobile}_${fileName}"
        val data = hashMapOf(
            "file_name" to fileName,
            "admin_mobile" to adminMobile,
            "uploaded_at" to System.currentTimeMillis(),
            "record_count" to recordCount
        )
        filesCollection.document(docId).set(data).await()
    }

    suspend fun getUploadedFiles(adminMobile: String?): List<UploadedFileMeta> {
        val query = if (adminMobile != null) {
            filesCollection.whereEqualTo("admin_mobile", adminMobile).get().await()
        } else {
            filesCollection.get().await()
        }
        return query.documents.mapNotNull { doc ->
            val fileName = doc.getString("file_name") ?: return@mapNotNull null
            val mobile = doc.getString("admin_mobile") ?: ""
            val uploadedAt = doc.getLong("uploaded_at") ?: 0L
            val recordCount = doc.getLong("record_count")?.toInt() ?: 0
            UploadedFileMeta(fileName, mobile, uploadedAt, recordCount)
        }.sortedByDescending { it.uploadedAt }
    }

    suspend fun deleteFileAndVehicles(adminMobile: String, fileName: String) {
        // 1. Delete meta document
        val docId = "${adminMobile}_${fileName}"
        filesCollection.document(docId).delete().await()

        // 2. Query matching vehicles and delete in batches of 500
        val vehicleQuery = vehiclesCollection
            .whereEqualTo("creator_mobile", adminMobile)
            .whereEqualTo("file_name", fileName)
            .get().await()
        
        val docChunks = vehicleQuery.documents.chunked(500)
        for (chunk in docChunks) {
            val batch = db.batch()
            for (doc in chunk) {
                batch.delete(doc.reference)
            }
            batch.commit().await()
        }
    }

    suspend fun searchVehiclesOnline(query: String, criteria: SearchCriteria, creatorMobile: String?): List<Vehicle> {
        val queryLower = query.lowercase().trim()
        val result = if (creatorMobile != null) {
            vehiclesCollection.whereEqualTo("creator_mobile", creatorMobile).get().await()
        } else {
            vehiclesCollection.get().await()
        }
        val vehicles = result.documents.mapNotNull { doc ->
            val num = doc.getString("registration_number") ?: return@mapNotNull null
            val owner = doc.getString("owner") ?: ""
            Vehicle(
                firestoreId = doc.id,
                vehicleNumber = num,
                customerName = owner,
                model = doc.getString("model") ?: "",
                status = doc.getString("status") ?: "Active",
                bankName = doc.getString("bank_name") ?: "",
                pos = doc.getString("pos") ?: "",
                emi = doc.getString("emi") ?: "",
                engineNumber = doc.getString("engine_number") ?: "",
                chassisNumber = doc.getString("chassis_number") ?: "",
                confirmerName = doc.getString("confirmer_name") ?: "",
                loanNo = doc.getString("loan_no") ?: "",
                creatorMobile = doc.getString("creator_mobile") ?: "admin",
                fileName = doc.getString("file_name") ?: "",
                bucket = doc.getString("bucket") ?: ""
            )
        }
        
        return vehicles.filter { v ->
            when (criteria) {
                SearchCriteria.GENERAL -> {
                    v.vehicleNumber.lowercase().contains(queryLower) ||
                    v.customerName.lowercase().contains(queryLower) ||
                    v.bankName.lowercase().contains(queryLower) ||
                    v.model.lowercase().contains(queryLower) ||
                    v.pos.lowercase().contains(queryLower)
                }
                SearchCriteria.ENGINE_LAST -> {
                    v.engineNumber.endsWith(queryLower)
                }
                SearchCriteria.CHASSIS_LAST -> {
                    v.chassisNumber.endsWith(queryLower)
                }
                SearchCriteria.LOAN_START -> {
                    v.loanNo.lowercase().startsWith(queryLower)
                }
                SearchCriteria.VEHICLE_LAST -> {
                    v.vehicleNumber.endsWith(queryLower)
                }
            }
        }
    }

    // Histories Sync
    suspend fun uploadHistory(history: SearchHistory): String {
        val mappedData = hashMapOf(
            "user_mobile" to history.userMobile,
            "user_name" to history.userName,
            "vehicle_number" to history.vehicleNumber,
            "model" to history.model,
            "timestamp" to history.timestamp,
            "creator_mobile" to history.creatorMobile
        )
        return if (history.firestoreId.isNotEmpty()) {
            historiesCollection.document(history.firestoreId).set(mappedData).await()
            history.firestoreId
        } else {
            val docRef = historiesCollection.add(mappedData).await()
            docRef.id
        }
    }

    suspend fun getAllHistories(): List<SearchHistory> {
        val result = historiesCollection.get().await()
        return result.documents.mapNotNull { doc ->
            val userMobile = doc.getString("user_mobile") ?: return@mapNotNull null
            SearchHistory(
                firestoreId = doc.id,
                userMobile = userMobile,
                userName = doc.getString("user_name") ?: "",
                vehicleNumber = doc.getString("vehicle_number") ?: "",
                model = doc.getString("model") ?: "",
                timestamp = doc.getString("timestamp") ?: "",
                creatorMobile = doc.getString("creator_mobile") ?: "admin"
            )
        }
    }

    suspend fun deleteHistoryItem(firestoreId: String) {
        if (firestoreId.isNotEmpty()) {
            historiesCollection.document(firestoreId).delete().await()
        }
    }

    suspend fun deleteHistoryByUser(userMobile: String) {
        val query = historiesCollection.whereEqualTo("user_mobile", userMobile).get().await()
        for (doc in query.documents) {
            historiesCollection.document(doc.id).delete().await()
        }
    }

    suspend fun deleteHistoryByCreator(creatorMobile: String) {
        val query = historiesCollection.whereEqualTo("creator_mobile", creatorMobile).get().await()
        val docChunks = query.documents.chunked(500)
        for (chunk in docChunks) {
            val batch = db.batch()
            for (doc in chunk) {
                batch.delete(doc.reference)
            }
            batch.commit().await()
        }
    }

    suspend fun clearAllHistories() {
        val query = historiesCollection.get().await()
        val docChunks = query.documents.chunked(500)
        for (chunk in docChunks) {
            val batch = db.batch()
            for (doc in chunk) {
                batch.delete(doc.reference)
            }
            batch.commit().await()
        }
    }

    // Permissions Sync
    private val permissionsCollection get() = db.collection("field_permissions")

    suspend fun uploadPermissions(roleString: String, permissions: FieldPermissions) {
        val mappedData = hashMapOf(
            "role_string" to permissions.roleString,
            "role" to permissions.role.name,
            "show_customer_name" to permissions.showCustomerName,
            "show_vehicle_number" to permissions.showVehicleNumber,
            "show_bank_name" to permissions.showBankName,
            "show_pos" to permissions.showPos,
            "show_emi" to permissions.showEmi,
            "show_engine_number" to permissions.showEngineNumber,
            "show_chassis_number" to permissions.showChassisNumber,
            "show_confirmer_name" to permissions.showConfirmerName,
            "show_loan_no" to permissions.showLoanNo,
            "show_bucket" to permissions.showBucket,
            "show_file_name" to permissions.showFileName
        )
        permissionsCollection.document(roleString).set(mappedData).await()
    }

    suspend fun getPermissions(adminMobile: String): FieldPermissions? {
        val roleString = "NORMAL_USER_$adminMobile"
        val doc = permissionsCollection.document(roleString).get().await()
        if (!doc.exists()) return null
        
        return FieldPermissions(
            roleString = roleString,
            role = UserRole.NORMAL_USER,
            showCustomerName = doc.getBoolean("show_customer_name") ?: true,
            showVehicleNumber = doc.getBoolean("show_vehicle_number") ?: true,
            showBankName = doc.getBoolean("show_bank_name") ?: true,
            showPos = doc.getBoolean("show_pos") ?: false,
            showEmi = doc.getBoolean("show_emi") ?: false,
            showEngineNumber = doc.getBoolean("show_engine_number") ?: false,
            showChassisNumber = doc.getBoolean("show_chassis_number") ?: false,
            showConfirmerName = doc.getBoolean("show_confirmer_name") ?: false,
            showLoanNo = doc.getBoolean("show_loan_no") ?: false,
            showBucket = doc.getBoolean("show_bucket") ?: false,
            showFileName = doc.getBoolean("show_file_name") ?: false
        )
    }

    suspend fun deletePermissions(roleString: String) {
        if (roleString.isNotEmpty()) {
            try {
                permissionsCollection.document(roleString).delete().await()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun listenToPermissions(onPermissionsChanged: (List<FieldPermissions>) -> Unit): com.google.firebase.firestore.ListenerRegistration {
        return permissionsCollection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                error.printStackTrace()
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val list = mutableListOf<FieldPermissions>()
                for (doc in snapshot.documents) {
                    val roleString = doc.getString("role_string") ?: doc.id
                    val roleStr = doc.getString("role") ?: "NORMAL_USER"
                    val role = try {
                        enumValueOf<UserRole>(roleStr)
                    } catch (e: Exception) {
                        UserRole.NORMAL_USER
                    }
                    val perms = FieldPermissions(
                        roleString = roleString,
                        role = role,
                        showCustomerName = doc.getBoolean("show_customer_name") ?: true,
                        showVehicleNumber = doc.getBoolean("show_vehicle_number") ?: true,
                        showBankName = doc.getBoolean("show_bank_name") ?: true,
                        showPos = doc.getBoolean("show_pos") ?: false,
                        showEmi = doc.getBoolean("show_emi") ?: false,
                        showEngineNumber = doc.getBoolean("show_engine_number") ?: false,
                        showChassisNumber = doc.getBoolean("show_chassis_number") ?: false,
                        showConfirmerName = doc.getBoolean("show_confirmer_name") ?: false,
                        showLoanNo = doc.getBoolean("show_loan_no") ?: false,
                        showBucket = doc.getBoolean("show_bucket") ?: false,
                        showFileName = doc.getBoolean("show_file_name") ?: false
                    )
                    list.add(perms)
                }
                onPermissionsChanged(list)
            }
        }
    }
}

data class UploadedFileMeta(
    val fileName: String,
    val adminMobile: String,
    val uploadedAt: Long,
    val recordCount: Int
)
