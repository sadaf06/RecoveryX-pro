package com.example.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.data.dao.FieldPermissionsDao
import com.example.data.dao.UserDao
import com.example.data.dao.VehicleDao
import com.example.data.dao.SearchHistoryDao
import com.example.data.model.FieldPermissions
import com.example.data.model.User
import com.example.data.model.UserRole
import com.example.data.model.UserStatus
import com.example.data.model.Vehicle
import com.example.data.model.SearchHistory

class Converters {
    @TypeConverter
    fun toUserRole(value: String): UserRole = enumValueOf(value)
    
    @TypeConverter
    fun fromUserRole(value: UserRole): String = value.name

    @TypeConverter
    fun toUserStatus(value: String): UserStatus = enumValueOf(value)
    
    @TypeConverter
    fun fromUserStatus(value: UserStatus): String = value.name
}

@Database(entities = [User::class, Vehicle::class, FieldPermissions::class, SearchHistory::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun vehicleDao(): VehicleDao
    abstract fun fieldPermissionsDao(): FieldPermissionsDao
    abstract fun searchHistoryDao(): SearchHistoryDao
}
