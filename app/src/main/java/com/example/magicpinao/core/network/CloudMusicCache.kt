package com.example.magicpinao.core.network

/**
 * 云端音乐列表缓存（供 MainViewModel 和 FloatingPlayerService 共享）
 */
object CloudMusicCache {
    private val _cache = mutableListOf<MusicItem>()

    var serverTotal: Int = 0
        private set
    var nextPage: Int = 1
        private set
    var pageSize: Int = 50
    @Volatile
    var loading: Boolean = false
    var lastSearch: String? = null
        private set

    val hasMore: Boolean
        get() = if (serverTotal > 0) size < serverTotal else true

    @Synchronized
    fun set(items: List<MusicItem>, total: Int = items.size, search: String? = null) {
        _cache.clear()
        _cache.addAll(items)
        serverTotal = total
        lastSearch = search
        nextPage = 2
    }

    @Synchronized
    fun addMore(items: List<MusicItem>, total: Int = serverTotal) {
        val exist = _cache.map { it.id }.toHashSet()
        _cache.addAll(items.filter { it.id !in exist })
        if (total > 0) serverTotal = total
        nextPage += 1
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
    fun markNoMore() {
        serverTotal = size
    }

    @Synchronized
    fun clear() {
        _cache.clear()
        serverTotal = 0
        nextPage = 1
        lastSearch = null
    }

    val size: Int
        @Synchronized get() = _cache.size
}
