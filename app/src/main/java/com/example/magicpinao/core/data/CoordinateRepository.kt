package com.example.magicpinao.core.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow

/**
 * 坐标配置仓库
 * 统一管理坐标配置的增删改查
 */
class CoordinateRepository(private val context: Context) {
    
    private val database = AppDatabase.getDatabase(context)
    private val coordinateDao = database.coordinateDao()
    private val gson = Gson()
    
    /**
     * 保存坐标配置
     */
    suspend fun saveCoordinate(coordinate: CoordinateEntity): Long {
        return coordinateDao.insertCoordinate(coordinate)
    }
    
    /**
     * 根据设备ID获取坐标配置
     */
    suspend fun getCoordinateByDeviceId(deviceId: String): CoordinateEntity? {
        return coordinateDao.getCoordinateByDeviceId(deviceId)
    }
    
    /**
     * 按键位方案取坐标：先 layoutType，再虚拟包名，最后兼容旧游戏包名。
     */
    suspend fun getCoordinateByPackage(packageName: String): CoordinateEntity? {
        val layoutType = when {
            packageName.startsWith("layout.") -> packageName.removePrefix("layout.")
            else -> com.example.magicpinao.core.accessibility.SupportedApps
                .findByPackage(packageName)?.layoutType
        }
        if (!layoutType.isNullOrBlank()) {
            val byLayout = coordinateDao.getCoordinateByLayoutType(layoutType)
            if (byLayout != null) return byLayout
            val byLayoutPkg = coordinateDao.getCoordinateByPackage("layout.$layoutType")
            if (byLayoutPkg != null) return byLayoutPkg
        }
        val primary = coordinateDao.getCoordinateByPackage(packageName)
        if (primary != null) return primary
        val aliases = when (layoutType) {
            "LAYOUT_21" -> listOf("com.PA.GenshinPiano", "com.miHoYo.GenshinImpact", "com.any.piano")
            "LAYOUT_15" -> listOf("com.sky.xlzq", "com.tgc.sky.android", "com.netease.sky")
            else -> emptyList()
        }
        for (alias in aliases) {
            coordinateDao.getCoordinateByPackage(alias)?.let { return it }
        }
        val keyCount = when (layoutType) {
            "LAYOUT_15" -> 15
            "LAYOUT_22" -> 22
            "LAYOUT_36", "LAYOUT_36_PIANO", "LAYOUT_36_SHAPED" -> 36
            "LAYOUT_37", "LAYOUT_37_SHAPED" -> 37
            "LAYOUT_61" -> 61
            "LAYOUT_88" -> 88
            "LAYOUT_21", "LAYOUT_21_GUITAR" -> 21
            else -> null
        }
        // 仅 15 键允许按键数兜底；21/22 吉他与 36/37 布局不能混用
        if (keyCount == 15) {
            return coordinateDao.getCoordinateByKeyCount(keyCount)
        }
        return null
    }
    
    /**
     * 获取所有坐标配置
     */
    fun getAllCoordinates(): Flow<List<CoordinateEntity>> {
        return coordinateDao.getAllCoordinates()
    }
    
    /**
     * 删除坐标配置
     */
    suspend fun deleteCoordinate(coordinate: CoordinateEntity): Int {
        return coordinateDao.deleteCoordinate(coordinate)
    }
    
    /**
     * 检查是否存在指定设备的坐标配置
     */
    suspend fun hasCoordinateForDevice(deviceId: String): Boolean {
        return coordinateDao.hasCoordinateForDevice(deviceId)
    }
    
    /**
     * 检查坐标是否可用于实际点击（排除归一化 0~1 误存为像素等坏数据）
     */
    suspend fun hasValidCoordinateForPackage(packageName: String): Boolean {
        val entity = getCoordinateByPackage(packageName) ?: return false
        return isCoordinateEntityValid(entity)
    }

    fun isCoordinateEntityValid(entity: CoordinateEntity): Boolean {
        return try {
            val coords = jsonToKeyCoordinates(entity.keyCoordinates)
            if (coords.isEmpty()) return false
            val xs = coords.map { it.centerX }
            val ys = coords.map { it.centerY }
            val spanX = (xs.maxOrNull() ?: 0) - (xs.minOrNull() ?: 0)
            val spanY = (ys.maxOrNull() ?: 0) - (ys.minOrNull() ?: 0)
            // 有效标定跨度至少几十像素；归一化误存通常全是 0/1
            spanX >= 80 && spanY >= 40 && coords.any { it.centerX > 10 && it.centerY > 10 }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查是否存在指定包名的坐标配置
     */
    suspend fun hasCoordinateForPackage(packageName: String): Boolean {
        return hasValidCoordinateForPackage(packageName)
    }

    suspend fun getSchemesForPackage(packageName: String): List<CoordinateEntity> {
        return coordinateDao.getSchemesForPackage(packageName)
    }

    suspend fun deleteSchemeById(id: String) {
        coordinateDao.deleteCoordinateById(id)
    }

    /**
     * 将琴键坐标列表转换为 JSON 字符串
     */
    fun keyCoordinatesToJson(coordinates: List<PianoKeyCoordinate>): String {
        return gson.toJson(coordinates)
    }
    
    /**
     * 从 JSON 字符串解析琴键坐标列表
     */
    fun jsonToKeyCoordinates(json: String): List<PianoKeyCoordinate> {
        val type = object : TypeToken<List<PianoKeyCoordinate>>() {}.type
        return gson.fromJson(json, type)
    }
    
    /**
     * 生成设备ID
     */
    fun generateDeviceId(packageName: String, screenWidth: Int, screenHeight: Int): String {
        return "${packageName}_${screenWidth}x${screenHeight}"
    }
}
