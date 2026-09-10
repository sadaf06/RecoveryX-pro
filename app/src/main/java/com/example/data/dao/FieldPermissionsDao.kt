package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.FieldPermissions
import com.example.data.model.UserRole
import kotlinx.coroutines.flow.Flow

@Dao
interface FieldPermissionsDao {
    @Query("SELECT * FROM field_permissions WHERE roleString = :roleString LIMIT 1")
    fun getPermissionsByRoleString(roleString: String): Flow<FieldPermissions?>

    @Query("SELECT * FROM field_permissions WHERE roleString = :roleString LIMIT 1")
    suspend fun getPermissionsByRoleStringSync(roleString: String): FieldPermissions?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPermissions(permissions: FieldPermissions)

    @Query("DELETE FROM field_permissions WHERE roleString = :roleString")
    suspend fun deletePermissionsByRoleString(roleString: String)
}
