package com.example.magicpinao.core.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 坐标配置数据访问对象
 */
@Dao
interface CoordinateDao {
    
    /**
     * 插入或更新坐标配置
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCoordinate(coordinate: CoordinateEntity): Long
    
    /**
     * 根据设备ID获取坐标配置
     */
    @Query("SELECT * FROM coordinates WHERE deviceId = :deviceId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getCoordinateByDeviceId(deviceId: String): CoordinateEntity?
    
    /**
     * 根据包名获取坐标配置
     */
    @Query("SELECT * FROM coordinates WHERE packageName = :packageName ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getCoordinateByPackage(packageName: String): CoordinateEntity?

    @Query("SELECT * FROM coordinates WHERE layoutType = :layoutType ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getCoordinateByLayoutType(layoutType: String): CoordinateEntity?

    @Query("SELECT * FROM coordinates WHERE keyCount = :keyCount ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getCoordinateByKeyCount(keyCount: Int): CoordinateEntity?
    
    /**
     * 获取所有坐标配置
     */
    @Query("SELECT * FROM coordinates ORDER BY updatedAt DESC")
    fun getAllCoordinates(): Flow<List<CoordinateEntity>>
    
    /**
     * 删除坐标配置
     */
    @Delete
    suspend fun deleteCoordinate(coordinate: CoordinateEntity): Int
    
    /**
     * 根据ID删除坐标配置
     */
    @Query("DELETE FROM coordinates WHERE id = :id")
    suspend fun deleteCoordinateById(id: String): Int
    
    /**
     * 检查是否存在指定设备的坐标配置
     */
    @Query("SELECT COUNT(*) FROM coordinates WHERE deviceId = :deviceId")
    suspend fun hasCoordinateForDevice(deviceId: String): Boolean
    
    /**
     * 检查是否存在指定包名的坐标配置
     */
    @Query("SELECT COUNT(*) FROM coordinates WHERE packageName = :packageName")
    suspend fun hasCoordinateForPackage(packageName: String): Boolean

    /**
     * 获取指定包名的所有标定方案
     */
    @Query("SELECT * FROM coordinates WHERE packageName = :packageName ORDER BY updatedAt DESC")
    suspend fun getSchemesForPackage(packageName: String): List<CoordinateEntity>

    /**
     * 获取所有标定方案
     */
    @Query("SELECT * FROM coordinates ORDER BY updatedAt DESC")
    fun getAllSchemes(): Flow<List<CoordinateEntity>>
}
