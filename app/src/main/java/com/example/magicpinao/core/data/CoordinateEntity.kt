package com.example.magicpinao.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 琴键坐标实体类
 * 存储目标应用中每个琴键的屏幕坐标
 */
@Entity(tableName = "coordinates")
data class CoordinateEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val packageName: String,
    val appName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val keyCount: Int = 21,
    val keyCoordinates: String,
    val calibrationMode: String,
    val schemeName: String = "",       // 标定方案名称（用户自定义）
    val layoutType: String = "LAYOUT_21",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 单个琴键坐标
 */
data class PianoKeyCoordinate(
    val keyIndex: Int,              // 琴键索引（0-20）
    val noteNumber: Int,            // MIDI音符编号
    val centerX: Int,               // 中心X坐标
    val centerY: Int,               // 中心Y坐标
    val width: Int,                 // 宽度
    val height: Int                 // 高度
)
