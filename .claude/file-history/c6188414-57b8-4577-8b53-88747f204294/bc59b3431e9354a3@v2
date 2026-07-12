package com.example.magicpinao.core.network

/**
 * 云端音乐列表缓存（供 MainViewModel 和 FloatingPlayerService 共享）
 */
object CloudMusicCache {
    private val _cache = mutableListOf<MusicItem>()

    @Synchronized
    fun set(items: List<MusicItem>) {
        _cache.clear()
        _cache.addAll(items)
    }

    @Synchronized
    fun addMore(items: List<MusicItem>) {
        _cache.addAll(items)
    }

    @Synchronized
    fun getAll(): List<MusicItem> = _cache.toList()

    @Synchronized
    fun search(query: String): List<MusicItem> {
        if (query.isBlank()) return _cache.toList()
        val q = query.lowercase()
        return _cache.filter {
            it.name.lowercase().contains(q) ||
            it.nameEn.lowercase().contains(q) ||
            it.singer.lowercase().contains(q) ||
            it.category.lowercase().contains(q)
        }
    }

    @Synchronized
    fun clear() {
        _cache.clear()
    }

    val size: Int
        @Synchronized get() = _cache.size
}
