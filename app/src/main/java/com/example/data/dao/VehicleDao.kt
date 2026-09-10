package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Vehicle
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles")
    fun getAllVehicles(): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE vehicleNumber LIKE '%' || :query || '%' OR customerName LIKE '%' || :query || '%' OR model LIKE '%' || :query || '%' OR status LIKE '%' || :query || '%'")
    fun searchVehiclesGeneral(query: String): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE engineNumber LIKE '%' || :query")
    fun searchVehiclesByEngineLast(query: String): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE chassisNumber LIKE '%' || :query")
    fun searchVehiclesByChassisLast(query: String): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE loanNo LIKE :query || '%'")
    fun searchVehiclesByLoanStart(query: String): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE vehicleNumber LIKE '%' || :query")
    fun searchVehiclesByVehicleLast(query: String): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE vehicleNumber = :vehicleNumber AND fileName = :fileName AND creatorMobile = :creatorMobile LIMIT 1")
    suspend fun getVehicleByNumberInFile(vehicleNumber: String, fileName: String, creatorMobile: String): Vehicle?

    @Query("SELECT * FROM vehicles WHERE vehicleNumber = :vehicleNumber")
    fun getVehiclesByNumber(vehicleNumber: String): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE id = :id LIMIT 1")
    fun getVehicleById(id: Int): Flow<Vehicle?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVehicle(vehicle: Vehicle)

    @Update
    suspend fun updateVehicle(vehicle: Vehicle)

    @Query("DELETE FROM vehicles WHERE id = :id")
    suspend fun deleteVehicleById(id: Int)

    @Query("DELETE FROM vehicles WHERE creatorMobile = :creatorMobile AND fileName = :fileName")
    suspend fun deleteVehiclesByFile(creatorMobile: String, fileName: String)

    @Query("DELETE FROM vehicles WHERE creatorMobile = :creatorMobile")
    suspend fun deleteVehiclesByCreator(creatorMobile: String)

    @Query("SELECT fileName, COUNT(*) as recordCount FROM vehicles WHERE creatorMobile = :creatorMobile AND fileName != '' GROUP BY fileName")
    suspend fun getLocalFilesSummary(creatorMobile: String): List<LocalFileSummary>

    @Query("SELECT COUNT(*) FROM vehicles WHERE creatorMobile = :creatorMobile AND fileName = :fileName")
    suspend fun countVehiclesByFile(creatorMobile: String, fileName: String): Int

    @Query("SELECT COUNT(*) FROM vehicles WHERE creatorMobile = :creatorMobile")
    fun countAllVehiclesByAdmin(creatorMobile: String): Flow<Int>

    @Query("DELETE FROM vehicles")
    suspend fun deleteAllVehicles()
}

data class LocalFileSummary(
    val fileName: String,
    val recordCount: Int
)
