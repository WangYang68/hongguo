package com.example.magicpinao.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 音乐实体类
 * 存储 MIDI 音乐的元数据
 */
@Entity(tableName = "music")
data class MusicEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,           // 文件名
    val title: String,              // 标题（从文件名或MIDI元数据提取）
    val artist: String? = null,     // 艺术家
    val tags: String = "[]",        // 标签（JSON数组字符串，如 ["流行","古典"]）
    val duration: Long = 0L,        // 时长（毫秒）
    val filePath: String,           // 文件绝对路径
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * 解析标签JSON为字符串列表
     */
    fun getTagList(): List<String> {
        return try {
            com.google.gson.Gson().fromJson(tags, Array<String>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        /** 从文件名推断标签 */
        fun inferTags(fileName: String): String {
            val name = fileName.lowercase()
            val inferred = mutableListOf<String>()

            if (name.contains("pop") || name.contains("流行")) inferred.add("流行")
            if (name.contains("classic") || name.contains("古典")) inferred.add("古典")
            if (name.contains("anime") || name.contains("动漫") || name.contains("anime")) inferred.add("动漫")
            if (name.contains("game") || name.contains("游戏") || name.contains("genshin") || name.contains("原神")) inferred.add("游戏")
            if (name.contains("piano") || name.contains("钢琴")) inferred.add("钢琴")
            if (name.contains("jazz") || name.contains("爵士")) inferred.add("爵士")
            if (name.contains("rock") || name.contains("摇滚")) inferred.add("摇滚")
            if (name.contains("folk") || name.contains("民谣")) inferred.add("民谣")

            return com.google.gson.Gson().toJson(if (inferred.isEmpty()) listOf("其他") else inferred)
        }
    }
}
