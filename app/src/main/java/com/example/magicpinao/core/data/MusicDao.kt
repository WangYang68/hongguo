package com.example.magicpinao.core.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 音乐数据访问对象
 */
@Dao
interface MusicDao {
    
    /**
     * 插入音乐
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMusic(music: MusicEntity): Long
    
    /**
     * 批量插入音乐
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMusicList(musicList: List<MusicEntity>): List<Long>
    
    /**
     * 删除音乐
     */
    @Delete
    suspend fun deleteMusic(music: MusicEntity): Int
    
    /**
     * 根据ID删除音乐
     */
    @Query("DELETE FROM music WHERE id = :id")
    suspend fun deleteMusicById(id: String): Int
    
    /**
     * 获取所有音乐（按创建时间倒序）
     */
    @Query("SELECT * FROM music ORDER BY createdAt DESC")
    fun getAllMusic(): Flow<List<MusicEntity>>
    
    /**
     * 根据标题搜索音乐
     */
    @Query("SELECT * FROM music WHERE title LIKE :keyword ORDER BY createdAt DESC")
    fun searchByTitle(keyword: String): Flow<List<MusicEntity>>
    
    /**
     * 根据标签搜索音乐
     */
    @Query("SELECT * FROM music WHERE tags LIKE :tag ORDER BY createdAt DESC")
    fun searchByTag(tag: String): Flow<List<MusicEntity>>
    
    /**
     * 根据ID获取音乐
     */
    @Query("SELECT * FROM music WHERE id = :id")
    suspend fun getMusicById(id: String): MusicEntity?

    @Query("SELECT * FROM music WHERE filePath = :filePath LIMIT 1")
    suspend fun getMusicByFilePath(filePath: String): MusicEntity?

    @Query("SELECT * FROM music ORDER BY createdAt DESC")
    suspend fun getAllMusicList(): List<MusicEntity>
    
    /**
     * 获取所有标签（去重）
     */
    @Query("SELECT DISTINCT tags FROM music")
    suspend fun getAllTags(): List<String>

    /**
     * 按标题或标签搜索
     */
    @Query("SELECT * FROM music WHERE title LIKE :keyword OR tags LIKE :tagKeyword ORDER BY createdAt DESC")
    fun searchByTitleOrTag(keyword: String, tagKeyword: String): Flow<List<MusicEntity>>

    @Query("SELECT * FROM music WHERE (title LIKE :keyword OR tags LIKE :keyword) AND tags LIKE :tag ORDER BY createdAt DESC")
    fun searchByTitleAndTag(keyword: String, tag: String): Flow<List<MusicEntity>>
    
    /**
     * 分页获取音乐（单次查询）
     */
    @Query("SELECT * FROM music ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getMusicPaged(limit: Int, offset: Int): List<MusicEntity>

    /**
     * 统计音乐数量
     */
    @Query("SELECT COUNT(*) FROM music")
    suspend fun getMusicCount(): Int

    /**
     * 清空所有音乐记录
     */
    @Query("DELETE FROM music")
    suspend fun deleteAllMusic()
}
