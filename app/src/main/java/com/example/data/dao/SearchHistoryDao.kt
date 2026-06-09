package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.SearchHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_histories ORDER BY id DESC")
    fun getAllHistory(): Flow<List<SearchHistory>>

    @Query("SELECT * FROM search_histories WHERE creatorMobile = :adminMobile ORDER BY id DESC")
    fun getHistoryForAdmin(adminMobile: String): Flow<List<SearchHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: SearchHistory)

    @Query("SELECT * FROM search_histories WHERE firestoreId = :firestoreId LIMIT 1")
    suspend fun getHistoryByFirestoreId(firestoreId: String): SearchHistory?

    @Query("DELETE FROM search_histories")
    suspend fun clearAllHistory()
}
