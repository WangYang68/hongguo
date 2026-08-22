package com.example.magicpinao.core.data

import android.content.Context
import com.example.magicpinao.core.network.MusicItem
import kotlinx.coroutines.flow.Flow

class PlaylistRepository(context: Context) {

    private val dao = AppDatabase.getDatabase(context).playlistDao()

    fun observeAll(): Flow<List<PlaylistItemEntity>> = dao.getAll()

    suspend fun addLocalMusic(music: MusicEntity): Result<PlaylistItemEntity> {
        if (dao.findByMusicKey(music.id) != null) {
            return Result.failure(IllegalStateException("已在播放列表中"))
        }
        val order = dao.maxSortOrder() + 1
        val item = PlaylistItemEntity(
            musicKey = music.id,
            source = "local",
            title = music.title,
            filePath = music.filePath,
            sortOrder = order
        )
        dao.insert(item)
        return Result.success(item)
    }

    suspend fun addCloudMusic(music: MusicItem): Result<PlaylistItemEntity> {
        val key = "cloud_${music.id}"
        if (dao.findByMusicKey(key) != null) {
            return Result.failure(IllegalStateException("已在播放列表中"))
        }
        val order = dao.maxSortOrder() + 1
        val item = PlaylistItemEntity(
            musicKey = key,
            source = "cloud",
            title = music.name,
            cloudMusicId = music.id,
            sortOrder = order
        )
        dao.insert(item)
        return Result.success(item)
    }

    suspend fun remove(item: PlaylistItemEntity) {
        dao.delete(item)
    }
}
