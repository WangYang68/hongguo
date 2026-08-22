package com.example.magicpinao.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/** 用户播放列表项（本地/云端音乐通过 + 添加） */
@Entity(tableName = "playlist")
data class PlaylistItemEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    /** local 音乐 id 或 cloud_{id} */
    val musicKey: String,
    val source: String,
    val title: String,
    val filePath: String? = null,
    val cloudMusicId: Int? = null,
    val sortOrder: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)
