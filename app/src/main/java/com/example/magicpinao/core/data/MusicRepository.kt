package com.example.magicpinao.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.io.File

class MusicRepository(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val musicDao = database.musicDao()

    fun midiStorageDir(): File {
        val dir = File(context.filesDir, "midi_music")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

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

    fun searchByTitleAndTag(keyword: String, tag: String): Flow<List<MusicEntity>> {
        return musicDao.searchByTitleAndTag("%$keyword%", "%\"$tag\"%")
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
            Log.e(TAG, "Delete file failed: ${music.filePath}", e)
        }
        return result
    }

    /**
     * 扫描目录中的 MIDI，按路径 upsert，不删掉用户已导入的记录。
     * 仅清理文件已丢失的数据库行。
     */
    suspend fun scanLocalMidiFiles(directory: String): List<MusicEntity> {
        val dir = File(directory)
        if (!dir.exists() || !dir.isDirectory) {
            pruneMissingFiles()
            return emptyList()
        }

        val midiFiles = dir.listFiles { file ->
            file.isFile && file.extension.lowercase() in MIDI_EXTS
        } ?: emptyArray()

        var inserted = 0
        for (file in midiFiles) {
            if (musicDao.getMusicByFilePath(file.absolutePath) == null) {
                musicDao.insertMusic(entityFromFile(file))
                inserted++
            }
        }
        pruneMissingFiles()
        Log.d(TAG, "Scan ${midiFiles.size} files, inserted $inserted")
        return musicDao.getAllMusicList()
    }

    /**
     * 从系统文件选择器导入 MIDI，复制到应用私有目录并写入 SQLite。
     */
    suspend fun importMidiFromUri(uri: Uri): Result<MusicEntity> {
        return try {
            val rawName = queryDisplayName(uri) ?: "import_${System.currentTimeMillis()}.mid"
            val safeName = sanitizeFileName(rawName)
            val ext = File(safeName).extension.lowercase().ifBlank { "mid" }
            if (ext !in MIDI_EXTS) {
                return Result.failure(IllegalArgumentException("仅支持 .mid / .midi 文件"))
            }
            val dir = midiStorageDir()
            val target = uniqueFile(dir, safeName.ifBlank { "import.$ext" })
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return Result.failure(IllegalStateException("无法读取所选文件"))

            if (!isMidiHeader(target)) {
                target.delete()
                return Result.failure(IllegalArgumentException("不是有效的 MIDI 文件"))
            }

            val existing = musicDao.getMusicByFilePath(target.absolutePath)
            if (existing != null) return Result.success(existing)

            val entity = entityFromFile(target)
            musicDao.insertMusic(entity)
            Result.success(entity)
        } catch (e: Exception) {
            Log.e(TAG, "importMidiFromUri failed", e)
            Result.failure(e)
        }
    }

    private suspend fun pruneMissingFiles() {
        musicDao.getAllMusicList().forEach { music ->
            if (!File(music.filePath).exists()) {
                musicDao.deleteMusic(music)
            }
        }
    }

    private fun entityFromFile(file: File): MusicEntity {
        return MusicEntity(
            id = file.absolutePath.hashCode().toString(),
            fileName = file.name,
            title = file.nameWithoutExtension,
            tags = MusicEntity.inferTags(file.name),
            filePath = file.absolutePath,
            duration = 0L
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.substringAfterLast('/').substringAfterLast('\\').trim()
        return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "import.mid" }
    }

    private fun uniqueFile(dir: File, preferred: String): File {
        val base = File(preferred).nameWithoutExtension.ifBlank { "import" }
        val ext = File(preferred).extension.ifBlank { "mid" }
        var candidate = File(dir, "$base.$ext")
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$i.$ext")
            i++
        }
        return candidate
    }

    private fun isMidiHeader(file: File): Boolean {
        return try {
            file.inputStream().use { ins ->
                val h = ByteArray(4)
                ins.read(h) == 4 && h[0] == 'M'.code.toByte() && h[1] == 'T'.code.toByte() &&
                    h[2] == 'h'.code.toByte() && h[3] == 'd'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "MusicRepository"
        private val MIDI_EXTS = setOf("mid", "midi")
    }
}
