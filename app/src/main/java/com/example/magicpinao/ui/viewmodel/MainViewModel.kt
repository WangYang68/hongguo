package com.example.magicpinao.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.magicpinao.core.accessibility.SupportedApps
import com.example.magicpinao.core.auth.AuthManager
import com.example.magicpinao.core.auth.VipValidity
import com.example.magicpinao.core.coordinate.CoordinateManager
import com.example.magicpinao.core.data.CoordinateRepository
import com.example.magicpinao.core.data.MusicEntity
import com.example.magicpinao.core.data.MusicRepository
import com.example.magicpinao.core.midi.MidiParser
import com.example.magicpinao.core.midi.MidiTestUtils
import com.example.magicpinao.core.midi.NoteSequence
import com.example.magicpinao.core.network.*
import com.example.magicpinao.core.performance.PerformanceScheduler
import com.example.magicpinao.core.utils.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val musicRepository = MusicRepository(application)
    private val coordinateRepository = CoordinateRepository(application)
    private val permissionManager = PermissionManager(application)
    private val midiParser = MidiParser()
    private val coordinateManager = CoordinateManager()
    private val performanceScheduler = PerformanceScheduler.getOrCreate()

    private val apiRepository = ApiRepository()

    // ===== UI 状态 =====
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // ===== 本地音乐 =====
    private val _musicList = MutableStateFlow<List<MusicEntity>>(emptyList())
    val musicList: StateFlow<List<MusicEntity>> = _musicList.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTag = MutableStateFlow("全部")
    val selectedTag: StateFlow<String> = _selectedTag.asStateFlow()

    // ===== 云音乐 =====
    private val _cloudMusicList = MutableStateFlow<List<MusicItem>>(emptyList())
    val cloudMusicList: StateFlow<List<MusicItem>> = _cloudMusicList.asStateFlow()

    private val _cloudSearchQuery = MutableStateFlow("")
    val cloudSearchQuery: StateFlow<String> = _cloudSearchQuery.asStateFlow()

    private val _isCloudLoading = MutableStateFlow(false)
    val isCloudLoading: StateFlow<Boolean> = _isCloudLoading.asStateFlow()

    private var cloudCurrentPage = 1
    private var cloudHasMore = true

    // ===== VIP激活记录 =====
    private val _vipRecords = MutableStateFlow<List<VipRecord>>(emptyList())
    val vipRecords: StateFlow<List<VipRecord>> = _vipRecords.asStateFlow()

    // ===== 分页（本地） =====
    private var currentPage = 1
    private var hasMore = true
    private val pageSize = 20

    init {
        // 注入 CoordinateManager
        performanceScheduler.setCoordinateManager(coordinateManager)

        // 初始化 AuthManager
        AuthManager.init(application)

        // 恢复上次选择的目标游戏，并刷新坐标状态
        val prefs = application.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)
        val savedPkg = prefs.getString(KEY_SELECTED_APP, null)?.let { SupportedApps.normalizePackage(it) }
        if (!savedPkg.isNullOrBlank()) {
            if (savedPkg != prefs.getString(KEY_SELECTED_APP, null)) {
                prefs.edit().putString(KEY_SELECTED_APP, savedPkg).apply()
            }
            _uiState.value = _uiState.value.copy(selectedAppPackage = savedPkg)
            refreshCoordinateStatus(savedPkg)
        }

        // 悬浮播放窗关闭时：停止并清除当前曲目（音乐不得在无悬浮窗时继续播）
        com.example.magicpinao.core.accessibility.FloatingPlayerService.onPlayerClosed = {
            performanceScheduler.stop()
            _uiState.value = _uiState.value.copy(
                isPlaying = false,
                currentMusicId = null,
                currentMusicTitle = null
            )
        }

        // 同步悬浮播放器 / 调度器状态到主界面
        performanceScheduler.addStateListener(object : PerformanceScheduler.PlaybackStateListener {
            override fun onPlaybackStarted() {
                _uiState.value = _uiState.value.copy(isPlaying = true)
            }
            override fun onPlaybackPaused() {
                _uiState.value = _uiState.value.copy(isPlaying = false)
            }
            override fun onPlaybackResumed() {
                _uiState.value = _uiState.value.copy(isPlaying = true)
            }
            override fun onPlaybackStopped() {
                // 不清除曲目信息：切歌时会 stop 再 start，避免底部栏闪烁消失
                _uiState.value = _uiState.value.copy(isPlaying = false)
            }
            override fun onPlaybackFinished() {
                _uiState.value = _uiState.value.copy(isPlaying = false)
            }
        })

        // 扫描本地 MIDI
        scanLocalMidiFiles()
        loadMusicList()
    }

    companion object {
        private const val PREFS_NAME = "magic_piano_prefs"
        private const val KEY_SELECTED_APP = "selected_app_package"
    }

    fun refreshCoordinateStatus(packageName: String? = _uiState.value.selectedAppPackage) {
        if (packageName.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(hasCoordinates = false, coordinateSchemeName = null)
            return
        }
        viewModelScope.launch {
            val pkg = SupportedApps.normalizePackage(packageName)
            val entity = coordinateRepository.getCoordinateByPackage(pkg)
            val valid = entity != null && coordinateRepository.isCoordinateEntityValid(entity)
            val appName = SupportedApps.findByPackage(pkg)?.appName
            val scheme = when {
                !valid -> null
                !entity?.schemeName.isNullOrBlank() -> entity!!.schemeName
                appName != null -> "$appName-${entity?.keyCount ?: 21}键"
                else -> "坐标已就绪"
            }
            _uiState.value = _uiState.value.copy(
                hasCoordinates = valid,
                coordinateSchemeName = scheme
            )
        }
    }

    /** 重置当前游戏的音符坐标并重新进入标定 */
    fun resetCoordinatesAndCalibrate() {
        val pkg = _uiState.value.selectedAppPackage ?: return
        viewModelScope.launch {
            val entity = coordinateRepository.getCoordinateByPackage(pkg)
            if (entity != null) {
                coordinateRepository.deleteCoordinate(entity)
            }
            // 同时清掉旧原神包名下的坐标
            if (pkg == "com.PA.GenshinPiano") {
                coordinateRepository.getCoordinateByPackage("com.miHoYo.GenshinImpact")?.let {
                    coordinateRepository.deleteCoordinate(it)
                }
            }
            _uiState.value = _uiState.value.copy(
                hasCoordinates = false,
                coordinateSchemeName = null
            )
            startCalibration(pkg)
        }
    }

    // ========== 本地音乐 ==========

    fun scanLocalMidiFiles() {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                copyMidiFilesFromAssets(context)
                val midiDir = "${context.filesDir.absolutePath}/midi_music"
                Log.d("MainViewModel", "Scanning MIDI files from: $midiDir")
                val scannedMusic = musicRepository.scanLocalMidiFiles(midiDir)
                Log.d("MainViewModel", "Found ${scannedMusic.size} MIDI files")
                syncMusicDatabase(midiDir)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error scanning MIDI files", e)
            }
        }
    }

    private suspend fun syncMusicDatabase(midiDir: String) {
        try {
            musicRepository.getAllMusic().collect { musicList ->
                val dir = java.io.File(midiDir)
                val existingFiles = dir.listFiles()?.map { it.absolutePath } ?: emptyList()
                val toDelete = musicList.filter { music ->
                    !existingFiles.contains(music.filePath)
                }
                if (toDelete.isNotEmpty()) {
                    Log.d("MainViewModel", "Removing ${toDelete.size} outdated music records")
                    toDelete.forEach { music -> musicRepository.deleteMusic(music) }
                }
                return@collect
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error syncing music database", e)
        }
    }

    private suspend fun copyMidiFilesFromAssets(context: Application) {
        try {
            val assetManager = context.assets
            val midiDir = java.io.File("${context.filesDir.absolutePath}/midi_music")
            if (!midiDir.exists()) midiDir.mkdirs()
            val assetFiles = assetManager.list("midi_music") ?: emptyArray()
            var copiedCount = 0
            for (fileName in assetFiles) {
                if (fileName.endsWith(".mid") || fileName.endsWith(".midi")) {
                    val targetFile = java.io.File(midiDir, fileName)
                    if (targetFile.exists()) continue
                    assetManager.open("midi_music/$fileName").use { inputStream ->
                        targetFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    copiedCount++
                }
            }
            if (copiedCount > 0) Log.d("MainViewModel", "Copied $copiedCount MIDI files")
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error copying MIDI files from assets", e)
        }
    }

    private fun loadMusicList() {
        viewModelScope.launch {
            musicRepository.getAllMusic().collect { music ->
                _musicList.value = music
            }
        }
    }

    fun searchMusic(query: String) {
        _searchQuery.value = query
        currentPage = 1
        viewModelScope.launch {
            if (query.isBlank() && _selectedTag.value == "全部") {
                loadMusicList()
            } else if (query.isBlank()) {
                musicRepository.searchByTag(_selectedTag.value).collect { _musicList.value = it }
            } else {
                musicRepository.searchByTitleOrTag(query).collect { _musicList.value = it }
            }
        }
    }

    fun selectTag(tag: String) {
        _selectedTag.value = tag
        currentPage = 1
        viewModelScope.launch {
            if (tag == "全部") {
                if (_searchQuery.value.isBlank()) loadMusicList()
                else musicRepository.searchByTitle(_searchQuery.value).collect { _musicList.value = it }
            } else {
                musicRepository.searchByTag(tag).collect { _musicList.value = it }
            }
        }
    }

    fun loadMore() {
        if (!hasMore) return
        currentPage++
        viewModelScope.launch {
            val more = musicRepository.getMusicPaged(currentPage, pageSize)
            if (more.size < pageSize) hasMore = false
            _musicList.value = _musicList.value + more
        }
    }

    fun refresh() {
        currentPage = 1
        hasMore = true
        scanLocalMidiFiles()
    }

    // ========== 云音乐 ==========

    fun loadCloudMusic(refresh: Boolean = false) {
        if (refresh) {
            cloudCurrentPage = 1
            cloudHasMore = true
        }
        viewModelScope.launch {
            _isCloudLoading.value = true
            val token = AuthManager.token ?: ""
            val result = apiRepository.getMusicList(
                token = token,
                page = cloudCurrentPage,
                size = CloudMusicCache.pageSize,
                search = _cloudSearchQuery.value.ifBlank { null }
            )
            result.fold(
                onSuccess = { data ->
                    val search = _cloudSearchQuery.value.ifBlank { null }
                    if (refresh || cloudCurrentPage == 1) {
                        _cloudMusicList.value = data.data
                        CloudMusicCache.set(data.data, data.total, search)
                    } else {
                        _cloudMusicList.value = _cloudMusicList.value + data.data
                        CloudMusicCache.addMore(data.data, data.total)
                    }
                    cloudHasMore = if (data.total > 0) {
                        _cloudMusicList.value.size < data.total
                    } else {
                        data.data.size >= CloudMusicCache.pageSize
                    }
                },
                onFailure = { e ->
                    Log.e("MainViewModel", "Load cloud music error", e)
                }
            )
            _isCloudLoading.value = false
        }
    }

    fun loadMoreCloudMusic() {
        if (!cloudHasMore || _isCloudLoading.value || CloudMusicCache.loading) return
        cloudCurrentPage = CloudMusicCache.nextPage
        loadCloudMusic()
    }

    fun searchCloudMusic(query: String) {
        _cloudSearchQuery.value = query
        cloudCurrentPage = 1
        cloudHasMore = true
        loadCloudMusic(refresh = true)
    }

    // ========== VIP检查与播放 ==========

    /**
     * 调用接口检查 VIP / 试用是否有效。
     * @return true 可播放；false 已弹过期或错误提示
     */
    private suspend fun ensureVipNotExpired(): Boolean {
        val token = AuthManager.token ?: ""
        if (token.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "请先登录后再播放")
            return false
        }
        val vipResult = apiRepository.checkVipExpire(token)
        return vipResult.fold(
            onSuccess = { vipValid ->
                if (VipValidity.isAllowed(
                        serverVipValid = vipValid,
                        createdAt = AuthManager.createdAt,
                        cachedStatus = AuthManager.vipStatus
                    )
                ) {
                    if (vipValid) AuthManager.updateVipStatus("2")
                    true
                } else {
                    _uiState.value = _uiState.value.copy(showVipExpired = true)
                    false
                }
            },
            onFailure = { e ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = "会员状态检查失败: ${e.message ?: "网络错误"}"
                )
                false
            }
        )
    }

    fun dismissVipExpired() {
        _uiState.value = _uiState.value.copy(showVipExpired = false)
    }

    /**
     * 检查VIP状态并播放云音乐（HomeScreen 云列表入口）
     */
    fun checkVipAndPlay(music: MusicItem, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            if (!ensureVipNotExpired()) {
                callback(false, if (_uiState.value.showVipExpired) "VIP已过期" else (_uiState.value.errorMessage ?: "无法播放"))
                return@launch
            }
            val token = AuthManager.token ?: ""
            apiRepository.addRecentPlay(token, music.id)
            callback(true, "")
        }
    }

    fun playCloudMusic(music: MusicItem) {
        viewModelScope.launch {
            try {
                val packageName = _uiState.value.selectedAppPackage?.let { SupportedApps.normalizePackage(it) }

                if (packageName.isNullOrBlank()) {
                    _uiState.value = _uiState.value.copy(
                        showAppSelector = true,
                        pendingMusicId = "cloud_${music.id}",
                        pendingCloudMusicJson = music.id.toString()
                    )
                    return@launch
                }

                // 1) 接口检测是否过期
                if (!ensureVipNotExpired()) {
                    _uiState.value = _uiState.value.copy(
                        pendingMusicId = "cloud_${music.id}",
                        pendingCloudMusicJson = music.id.toString()
                    )
                    return@launch
                }

                // 2) 权限
                if (!ensurePlayPermissions()) return@launch

                // 3) 坐标是否已设置
                val coordEntity = coordinateRepository.getCoordinateByPackage(packageName)
                if (coordEntity == null || !coordinateRepository.isCoordinateEntityValid(coordEntity)) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "请先为「${SupportedApps.findByPackage(packageName)?.appName ?: packageName}」设置音符坐标后再播放",
                        showCalibrationPrompt = true,
                        calibratingAppPackage = packageName,
                        pendingMusicId = "cloud_${music.id}"
                    )
                    return@launch
                }

                val token = AuthManager.token ?: ""
                apiRepository.addRecentPlay(token, music.id)

                val app = getApplication<Application>()
                _uiState.value = _uiState.value.copy(
                    currentMusicId = "cloud_${music.id}",
                    currentMusicTitle = music.name,
                    errorMessage = null,
                    hasCoordinates = true,
                    coordinateSchemeName = coordEntity.schemeName.ifBlank {
                        val name = SupportedApps.findByPackage(packageName)?.appName ?: "坐标"
                        "$name-${coordEntity.keyCount}键"
                    },
                    selectedAppPackage = SupportedApps.normalizePackage(packageName)
                )

                // 必须弹出悬浮窗
                com.example.magicpinao.core.accessibility.FloatingPlayerService.start(
                    app, SupportedApps.normalizePackage(packageName), music.name, "cloud_${music.id}", autoPlay = true
                )
                Log.d("MainViewModel", "Delegated cloud playback to FloatingPlayer: ${music.name}")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Cloud play error", e)
                _uiState.value = _uiState.value.copy(errorMessage = "播放失败: ${e.message}", isPlaying = false)
            }
        }
    }

    // ========== 认证相关 ==========

    fun login(account: String, password: String, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = apiRepository.login(account, password)
            result.fold(
                onSuccess = { user ->
                    AuthManager.saveLogin(user)
                    callback(true, "登录成功")
                },
                onFailure = { e ->
                    callback(false, e.message ?: "登录失败")
                }
            )
        }
    }

    fun register(
        account: String,
        nickname: String,
        password: String,
        inviteCode: String?,
        callback: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val result = apiRepository.register(account, nickname, password, inviteCode)
            result.fold(
                onSuccess = { msg -> callback(true, msg) },
                onFailure = { e -> callback(false, e.message ?: "注册失败") }
            )
        }
    }

    fun logout(callback: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val token = AuthManager.token ?: ""
            val result = apiRepository.logout(token)
            // 无论服务端是否成功，都清除本地登录状态
            AuthManager.clearLogin()
            stopPlayback()
            result.fold(
                onSuccess = { msg -> callback(true, msg) },
                onFailure = { callback(false, "") }
            )
        }
    }

    fun uploadAvatar(file: java.io.File, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val token = AuthManager.token ?: ""
            val result = apiRepository.uploadAvatar(token, file)
            result.fold(
                onSuccess = { url -> callback(true, url) },
                onFailure = { e -> callback(false, e.message ?: "上传失败") }
            )
        }
    }

    fun activateVipCode(code: String, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val token = AuthManager.token ?: ""
            val result = apiRepository.activateVip(token, code)
            result.fold(
                onSuccess = { msg ->
                    AuthManager.updateVipStatus("2")
                    loadVipRecords()
                    callback(true, msg)
                },
                onFailure = { e -> callback(false, e.message ?: "激活失败") }
            )
        }
    }

    fun resetPassword(account: String, newPassword: String, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = apiRepository.resetPassword(account, newPassword)
            result.fold(
                onSuccess = { msg -> callback(true, msg) },
                onFailure = { e -> callback(false, e.message ?: "重置失败") }
            )
        }
    }

    // ========== VIP记录 ==========

    fun loadVipRecords() {
        viewModelScope.launch {
            val token = AuthManager.token ?: ""
            val result = apiRepository.getVipRecords(token, 1, 20)
            result.fold(
                onSuccess = { data -> _vipRecords.value = data.data },
                onFailure = { Log.e("MainViewModel", "Load VIP records error", it) }
            )
        }
    }

    // ========== 播放控制 ==========

    fun selectTargetApp(packageName: String) {
        val pkg = SupportedApps.normalizePackage(packageName)
        getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_APP, pkg)
            .apply()
        _uiState.value = _uiState.value.copy(
            selectedAppPackage = pkg,
            showAppSelector = false,
            showCalibrationPrompt = false,
            calibratingAppPackage = pkg
        )
        viewModelScope.launch {
            refreshCoordinateStatus(pkg)
            val entity = coordinateRepository.getCoordinateByPackage(pkg)
            val valid = entity != null && coordinateRepository.isCoordinateEntityValid(entity)
            if (!valid) {
                _uiState.value = _uiState.value.copy(
                    showCalibrationPrompt = true,
                    calibratingAppPackage = pkg
                )
            }
        }
    }

    fun toggleAppSelector() {
        _uiState.value = _uiState.value.copy(
            showAppSelector = !_uiState.value.showAppSelector
        )
    }

    fun dismissCalibrationPrompt() {
        _uiState.value = _uiState.value.copy(showCalibrationPrompt = false)
    }

    fun dismissTargetAppPrompt() {
        _uiState.value = _uiState.value.copy(
            showTargetAppPrompt = false,
            targetAppPromptPackage = null
        )
    }

    /** 兼容旧 UI：直接进入标定，不再要求打开某款游戏 */
    fun confirmOpenTargetAppAndCalibrate() {
        val pkg = SupportedApps.normalizePackage(
            _uiState.value.targetAppPromptPackage
                ?: _uiState.value.calibratingAppPackage
                ?: _uiState.value.selectedAppPackage
                ?: return
        )
        dismissTargetAppPrompt()
        startCalibrationService(pkg)
    }

    fun dismissPermissionDialog() {
        _uiState.value = _uiState.value.copy(
            showPermissionDialog = false,
            permissionType = PermissionType.NONE
        )
    }

    fun checkAccessibilityPermission(): Boolean = permissionManager.isAccessibilityServiceEnabled()

    /** 无障碍已开启且服务进程已连接 */
    fun isAccessibilityServiceReady(): Boolean {
        return permissionManager.isAccessibilityServiceEnabled() &&
            com.example.magicpinao.core.accessibility.PianoAccessibilityService.instance != null
    }

    fun openAccessibilitySettings() = permissionManager.openAccessibilitySettings()
    fun openOverlaySettings() = permissionManager.openOverlaySettings()
    fun checkOverlayPermission(): Boolean = permissionManager.hasOverlayPermission()

    /**
     * 检查演奏/标定所需权限。失败时写入 uiState.showPermissionDialog 供界面弹出。
     */
    fun ensurePlayPermissions(): Boolean {
        if (!permissionManager.hasOverlayPermission()) {
            _uiState.value = _uiState.value.copy(
                showPermissionDialog = true,
                permissionType = PermissionType.OVERLAY
            )
            return false
        }
        if (!permissionManager.isAccessibilityServiceEnabled()) {
            _uiState.value = _uiState.value.copy(
                showPermissionDialog = true,
                permissionType = PermissionType.ACCESSIBILITY
            )
            return false
        }
        if (com.example.magicpinao.core.accessibility.PianoAccessibilityService.instance == null) {
            _uiState.value = _uiState.value.copy(
                showPermissionDialog = true,
                permissionType = PermissionType.ACCESSIBILITY,
                errorMessage = "无障碍服务未连接，请在系统设置中关闭再重新开启「Magic Piano」"
            )
            return false
        }
        return true
    }

    fun playMusic(musicId: String) {
        val packageName = _uiState.value.selectedAppPackage?.let { SupportedApps.normalizePackage(it) }
        if (packageName.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(showAppSelector = true, pendingMusicId = musicId)
            return
        }
        if (packageName != _uiState.value.selectedAppPackage) {
            _uiState.value = _uiState.value.copy(selectedAppPackage = packageName)
        }
        playMusic(musicId, packageName)
    }

    fun checkAppAndCoordinates(callback: (AppCheckResult) -> Unit) {
        val packageName = _uiState.value.selectedAppPackage
        if (packageName.isNullOrBlank()) {
            callback(AppCheckResult.NO_APP_SELECTED)
            return
        }
        viewModelScope.launch {
            val hasCoordinates = coordinateRepository.hasCoordinateForPackage(packageName)
            if (!hasCoordinates) {
                callback(AppCheckResult.NO_COORDINATES(packageName))
            } else {
                callback(AppCheckResult.OK)
            }
        }
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            getApplication<Application>().packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun isPackageForeground(pkg: String): Boolean {
        val acc = com.example.magicpinao.core.accessibility.PianoAccessibilityService.instance
            ?: return false
        return try {
            val root = acc.rootInActiveWindow ?: return false
            val current = root.packageName?.toString()
            root.recycle()
            current == pkg
        } catch (_: Exception) {
            false
        }
    }

    private fun launchTargetApp(pkg: String): Boolean {
        return try {
            val intent = getApplication<Application>().packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(intent)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    fun startCalibration(packageName: String = _uiState.value.selectedAppPackage ?: "") {
        val raw = packageName.ifBlank {
            _uiState.value.selectedAppPackage ?: return
        }
        val pkg = SupportedApps.normalizePackage(raw)
        if (!ensurePlayPermissions()) {
            _uiState.value = _uiState.value.copy(
                calibratingAppPackage = pkg,
                isCalibrating = false
            )
            return
        }
        startCalibrationService(pkg)
    }

    private fun startCalibrationService(pkg: String) {
        val normalized = SupportedApps.normalizePackage(pkg)
        val appInfo = SupportedApps.findByPackage(normalized) ?: SupportedApps.getDefault()
        _uiState.value = _uiState.value.copy(
            isCalibrating = true,
            calibratingAppPackage = normalized,
            showCalibrationPrompt = false,
            showTargetAppPrompt = false,
            selectedAppPackage = normalized
        )
        getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_APP, normalized).apply()
        com.example.magicpinao.core.accessibility.FloatingPlayerService.stop(getApplication())
        performanceScheduler.stop()
        com.example.magicpinao.core.accessibility.FloatingCalibrationService.start(
            getApplication(),
            normalized,
            appInfo.keyCount,
            appInfo.layoutType,
            openTargetApp = false
        )
    }

    fun finishCalibration() {
        _uiState.value = _uiState.value.copy(isCalibrating = false, calibratingAppPackage = null)
        refreshCoordinateStatus()
        loadMusicList()
    }

    private fun playMusic(musicId: String, packageName: String) {
        viewModelScope.launch {
            try {
                // 1) 接口检测会员是否过期
                if (!ensureVipNotExpired()) {
                    _uiState.value = _uiState.value.copy(pendingMusicId = musicId)
                    return@launch
                }

                // 2) 权限
                if (!ensurePlayPermissions()) {
                    _uiState.value = _uiState.value.copy(pendingMusicId = musicId)
                    return@launch
                }

                val music = musicRepository.getMusicById(musicId) ?: run {
                    _uiState.value = _uiState.value.copy(errorMessage = "音乐未找到")
                    return@launch
                }

                // 3) 坐标是否已设置好
                val coordEntity = coordinateRepository.getCoordinateByPackage(packageName)
                if (coordEntity == null || !coordinateRepository.isCoordinateEntityValid(coordEntity)) {
                    _uiState.value = _uiState.value.copy(
                        pendingMusicId = musicId,
                        calibratingAppPackage = packageName,
                        showCalibrationPrompt = true,
                        errorMessage = "请先为「${SupportedApps.findByPackage(packageName)?.appName ?: packageName}」设置音符坐标后再播放"
                    )
                    return@launch
                }

                if (_uiState.value.currentMusicId == musicId && performanceScheduler.isPlaying) {
                    pausePlayback()
                    return@launch
                }
                if (_uiState.value.currentMusicId == musicId && performanceScheduler.isPaused) {
                    resumePlayback()
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    currentMusicId = musicId,
                    currentMusicTitle = music.title,
                    errorMessage = null,
                    hasCoordinates = true,
                    coordinateSchemeName = coordEntity.schemeName.ifBlank {
                        val app = SupportedApps.findByPackage(packageName)?.appName ?: "坐标"
                        "$app-${coordEntity.keyCount}键"
                    },
                    pendingMusicId = null
                )

                // 必须弹出悬浮窗；关闭悬浮窗会停播
                com.example.magicpinao.core.accessibility.FloatingPlayerService.start(
                    getApplication(), packageName, music.title, musicId, autoPlay = true
                )
                Log.d("MainViewModel", "Delegated local playback to FloatingPlayer: ${music.title}")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Play error", e)
                _uiState.value = _uiState.value.copy(errorMessage = "播放失败: ${e.message}", isPlaying = false)
            }
        }
    }

    fun pausePlayback() {
        // 播放控制以悬浮窗为准；主界面仅转发
        com.example.magicpinao.core.accessibility.FloatingPlayerService.instance?.let {
            performanceScheduler.pause()
            _uiState.value = _uiState.value.copy(isPlaying = false)
        }
    }

    fun resumePlayback() {
        val pkg = _uiState.value.selectedAppPackage ?: return
        val title = _uiState.value.currentMusicTitle ?: ""
        val id = _uiState.value.currentMusicId
        // 悬浮窗必须打开才能继续播放
        if (com.example.magicpinao.core.accessibility.FloatingPlayerService.instance == null) {
            com.example.magicpinao.core.accessibility.FloatingPlayerService.start(
                getApplication(), pkg, title, id, autoPlay = true
            )
        } else {
            performanceScheduler.resume()
            _uiState.value = _uiState.value.copy(isPlaying = true)
        }
    }

    fun stopPlayback() {
        performanceScheduler.stop()
        com.example.magicpinao.core.accessibility.FloatingPlayerService.stop(getApplication())
        _uiState.value = _uiState.value.copy(isPlaying = false, currentMusicId = null, currentMusicTitle = null)
    }

    fun testTap(x: Int, y: Int, onResult: (Boolean, String) -> Unit) {
        val service = com.example.magicpinao.core.accessibility.PianoAccessibilityService.instance
        if (service == null) {
            onResult(false, "无障碍服务未连接")
            return
        }
        viewModelScope.launch {
            val (ok, msg) = service.testTap(x, y)
            onResult(ok, msg)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}

/**
 * UI 状态
 */
data class MainUiState(
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val currentMusicId: String? = null,
    val currentMusicTitle: String? = null,
    val errorMessage: String? = null,
    val showPermissionDialog: Boolean = false,
    val permissionType: PermissionType = PermissionType.NONE,
    val selectedAppPackage: String? = null,
    val showAppSelector: Boolean = false,
    val pendingMusicId: String? = null,
    val pendingCloudMusicJson: String? = null,
    val isCalibrating: Boolean = false,
    val calibratingAppPackage: String? = null,
    val hasCoordinates: Boolean = false,
    val coordinateSchemeName: String? = null,
    val showCalibrationPrompt: Boolean = false,
    /** 目标钢琴 App 未打开时的引导弹窗 */
    val showTargetAppPrompt: Boolean = false,
    val targetAppPromptPackage: String? = null,
    /** VIP 过期引导（本地/云端播放共用） */
    val showVipExpired: Boolean = false
)

sealed class AppCheckResult {
    object OK : AppCheckResult()
    object NO_APP_SELECTED : AppCheckResult()
    data class NO_COORDINATES(val packageName: String) : AppCheckResult()
}

enum class PermissionType {
    NONE, ACCESSIBILITY, OVERLAY
}
