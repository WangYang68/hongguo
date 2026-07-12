package com.example.magicpinao.core.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.io.File

class MusicRepository(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val musicDao = database.musicDao()

    fun getAllMusic(): Flow<List<MusicEntity>> {
        return musicDao.getAllMusic()
    }

    fun searchByTitle(keyword: String): Flow<List<MusicEntity>> {
        return musicDao.searchByTitle("%$keyword%")
    }

    fun searchByTag(tag: String): Flow<List<MusicEntity>> {
        return musicDao.searchByTag("%\"$tag\"%")
    }

    fun searchByTitleOrTag(keyword: String): Flow<List<MusicEntity>> {
        return musicDao.searchByTitleOrTag("%$keyword%", "%\"$keyword\"%")
    }

    suspend fun getMusicPaged(page: Int, pageSize: Int = 20): List<MusicEntity> {
        return musicDao.getMusicPaged(pageSize, (page - 1) * pageSize)
    }

    suspend fun getMusicById(id: String): MusicEntity? {
        return musicDao.getMusicById(id)
    }

    suspend fun insertMusic(music: MusicEntity): Long {
        return musicDao.insertMusic(music)
    }

    suspend fun insertMusicList(musicList: List<MusicEntity>): List<Long> {
        return musicDao.insertMusicList(musicList)
    }

    suspend fun deleteMusic(music: MusicEntity): Int {
        val result = musicDao.deleteMusic(music)
        try {
            val file = File(music.filePath)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    suspend fun scanLocalMidiFiles(directory: String): List<MusicEntity> {
        val dir = File(directory)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val midiFiles = dir.listFiles { file ->
            file.extension.lowercase() in listOf("mid", "midi")
        } ?: return emptyList()

        val musicList = midiFiles.map { file ->
            val uniqueId = file.absolutePath.hashCode().toString()
            MusicEntity(
                id = uniqueId,
                fileName = file.name,
                title = file.nameWithoutExtension,
                tags = MusicEntity.inferTags(file.name),
                filePath = file.absolutePath,
                duration = 0L
            )
        }

        // 先清空旧记录再插入，彻底解决 ID 不匹配导致的重复问题
        musicDao.deleteAllMusic()
        if (musicList.isNotEmpty()) {
            insertMusicList(musicList)
            Log.d("MusicRepository", "Scanned and inserted ${musicList.size} MIDI files")
        }

        return musicList
    }
}
