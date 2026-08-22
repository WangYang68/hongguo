package com.example.magicpinao.core.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PlaylistItemEntity): Long

    @Delete
    suspend fun delete(item: PlaylistItemEntity): Int

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM playlist WHERE musicKey = :musicKey LIMIT 1")
    suspend fun findByMusicKey(musicKey: String): PlaylistItemEntity?

    @Query("SELECT * FROM playlist ORDER BY sortOrder ASC, addedAt ASC")
    fun getAll(): Flow<List<PlaylistItemEntity>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM playlist")
    suspend fun maxSortOrder(): Int
}
