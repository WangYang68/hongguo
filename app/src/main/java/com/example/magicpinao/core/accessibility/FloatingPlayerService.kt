package com.example.magicpinao.core.accessibility

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.magicpinao.R
import com.example.magicpinao.core.coordinate.CoordinateManager
import com.example.magicpinao.core.data.AppDatabase
import com.example.magicpinao.core.data.CoordinateEntity
import com.example.magicpinao.core.data.CoordinateRepository
import com.example.magicpinao.core.data.MusicEntity
import com.example.magicpinao.core.data.MusicRepository
import com.example.magicpinao.core.data.PianoKeyCoordinate
import com.example.magicpinao.core.calibration.PianoLayoutType
import com.example.magicpinao.core.calibration.PianoLayouts
import com.example.magicpinao.core.midi.MidiParser
import com.example.magicpinao.core.midi.NoteSequence
import com.example.magicpinao.core.auth.AuthManager
import com.example.magicpinao.core.network.ApiRepository
import com.example.magicpinao.core.network.CloudMusicCache
import com.example.magicpinao.core.network.MusicItem
import com.example.magicpinao.core.performance.PerformanceScheduler
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 悬浮播放器服务
 * 在目标钢琴应用上层显示，支持：
 * - 音乐选择（上一首/下一首/列表）
 * - 播放/暂停/继续/停止
 * - 可拖拽移动
 * - 播放动画
 */
class FloatingPlayerService : Service(), PerformanceScheduler.PlaybackStateListener {

    companion object {
        private const val TAG = "FloatingPlayer"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "player_channel"

        const val ACTION_START = "ACTION_START_PLAYER"
        const val ACTION_STOP = "ACTION_STOP_PLAYER"
        const val EXTRA_MUSIC_TITLE = "EXTRA_MUSIC_TITLE"
        const val EXTRA_TARGET_PACKAGE = "EXTRA_TARGET_PACKAGE"
        const val EXTRA_MUSIC_ID = "EXTRA_MUSIC_ID"
        const val EXTRA_AUTO_PLAY = "EXTRA_AUTO_PLAY"

        var instance: FloatingPlayerService? = null
            private set

        /** 目标应用包名（静态，跨实例保持） */
        var targetPackage: String? = null

        /** 悬浮窗被用户关闭时回调（主界面清除播放状态） */
        var onPlayerClosed: (() -> Unit)? = null

        /** VIP 过期时通知主界面弹出激活提示 */
        var onVipExpired: (() -> Unit)? = null

        fun start(
            context: Context,
            packageName: String,
            musicTitle: String = "",
            musicId: String? = null,
            autoPlay: Boolean = false
        ) {
            targetPackage = packageName
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MUSIC_TITLE, musicTitle)
                putExtra(EXTRA_TARGET_PACKAGE, packageName)
                putExtra(EXTRA_AUTO_PLAY, autoPlay)
                if (musicId != null) putExtra(EXTRA_MUSIC_ID, musicId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    // 核心组件
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val midiParser = MidiParser()
    private val coordinateManager = CoordinateManager()
    private val gson = Gson()
    private lateinit var musicRepository: MusicRepository
    private lateinit var coordinateRepository: CoordinateRepository
    private val scheduler: PerformanceScheduler
        get() = PerformanceScheduler.getOrCreate()

    // 悬浮窗
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // 统一音乐项（本地+云端）
    data class UnifiedMusic(
        val id: String,           // "local_xxx" 或 "cloud_xxx"
        val title: String,
        val source: String,       // "local" 或 "cloud"
        val localMusic: MusicEntity? = null,
        val cloudMusic: MusicItem? = null,
        val filePath: String? = null
    )

    // 列表过滤模式
    private enum class FilterMode(val label: String) { ALL("全部"), LOCAL("本地"), CLOUD("云端") }
    private enum class PlayMode { SINGLE, SEQUENCE }
    private var filterMode = FilterMode.ALL
    private var playMode = PlayMode.SINGLE
    private var selectedTrackIndex: Int? = null
    private var currentSequence: NoteSequence? = null
    private val speedOptions = (5..20).map { it / 10f }

    // 音乐列表
    private var fullMusicList: List<MusicEntity> = emptyList()
    private var cloudMusicItems: List<MusicItem> = emptyList()
    private var unifiedList: List<UnifiedMusic> = emptyList()
    private var filteredUnifiedList: List<UnifiedMusic> = emptyList()
    private var currentMusicIndex: Int = -1
    private var currentUnifiedIndex: Int = -1
    private var musicListAdapter: ArrayAdapter<String>? = null
    private var searchQuery = ""
    private var cloudSearchJob: Job? = null
    private val apiRepository = ApiRepository()

    // 过滤模式按钮引用
    private var btnFilterAll: TextView? = null
    private var btnFilterLocal: TextView? = null
    private var btnFilterCloud: TextView? = null

    // 播放状态
    private var isPlaying = false
    private var currentMusicTitle = ""
    private var currentMusicId: String? = null
    private var currentPackageName = ""

    // 动画
    private val handler = Handler(Looper.getMainLooper())
    private var animatorRunnable: Runnable? = null
    private var animationPhase = 0

    // 拖动
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartTouchX = 0f
    private var dragStartTouchY = 0f

    // UI 引用
    private var tvMusicTitle: TextView? = null
    private var tvStatus: TextView? = null
    private var tvCoordScheme: TextView? = null
    private var tvPlayingIcon: TextView? = null
    private var btnPauseResumeIcon: TextView? = null
    private var musicListPanel: LinearLayout? = null
    private var musicListView: ListView? = null
    private var tvEmptyMusic: TextView? = null
    private var etSearch: android.widget.EditText? = null
    private var tvResultCount: TextView? = null
    private var progressBar: android.widget.SeekBar? = null
    private var tvCurrentTime: TextView? = null
    private var tvTotalTime: TextView? = null
    private var btnSpeedSelect: TextView? = null
    private var btnPlayMode: TextView? = null
    private var btnPressMode: TextView? = null
    private var btnTrackSelect: TextView? = null

    // 进度更新定时器
    private var progressRunnable: Runnable? = null
    private var isUserSeeking = false  // 用户正在拖拽进度条

    // 收缩 / 缩放
    private var isCollapsed = false
    private var panelWidthPx = 0
    private var pendingAutoPlay = false
    private var resizeStartWidth = 0
    private var resizeStartX = 0f

    override fun onCreate() {
        super.onCreate()
        instance = this
        musicRepository = MusicRepository(this)
        coordinateRepository = CoordinateRepository(this)
        scheduler.addStateListener(this)
        Log.d(TAG, "FloatingPlayerService created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentMusicTitle = intent.getStringExtra(EXTRA_MUSIC_TITLE) ?: ""
                currentPackageName = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: targetPackage ?: ""
                currentMusicId = intent.getStringExtra(EXTRA_MUSIC_ID)
                pendingAutoPlay = intent.getBooleanExtra(EXTRA_AUTO_PLAY, false)
                if (currentPackageName.isNotEmpty()) {
                    targetPackage = currentPackageName
                }
                // 重新启动时先停掉旧演奏，避免双重调度
                if (scheduler.isPlaying || scheduler.isPaused) {
                    scheduler.stop()
                    isPlaying = false
                }
                startForegroundNotification()
                createFloatingPlayer()
                loadMusicList()
            }
            ACTION_STOP -> stopService()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scheduler.removeStateListener(this)
        stopAnimation()
        stopProgressUpdater()
        // 关闭悬浮窗必须停止演奏
        if (scheduler.isPlaying || scheduler.isPaused) {
            scheduler.stop()
        }
        removeFloatingView()
        instance = null
        scope.cancel()
        try {
            onPlayerClosed?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "onPlayerClosed error", e)
        }
        Log.d(TAG, "FloatingPlayerService destroyed")
    }

    // ==================== PlaybackStateListener ====================

    override fun onPlaybackStarted() {
        isPlaying = true
        updatePlayPauseIcon()
        startAnimation()
        startProgressUpdater()
        tvPlayingIcon?.text = "🎶"
        updateStatus("正在演奏: $currentMusicTitle")
        updateTimeDisplay(0L, scheduler.getDuration())
    }

    override fun onPlaybackPaused() {
        isPlaying = false
        updatePlayPauseIcon()
        stopAnimation()
        tvPlayingIcon?.text = "🎵"
        updateStatus("已暂停 — $currentMusicTitle")
        val pos = scheduler.getCurrentPosition()
        val dur = scheduler.getDuration()
        updateTimeDisplay(pos, dur)
    }

    override fun onPlaybackResumed() {
        isPlaying = true
        updatePlayPauseIcon()
        startAnimation()
        startProgressUpdater()
        tvPlayingIcon?.text = "🎶"
        updateStatus("正在演奏: $currentMusicTitle")
    }

    override fun onPlaybackStopped() {
        isPlaying = false
        updatePlayPauseIcon()
        stopAnimation()
        stopProgressUpdater()
        tvPlayingIcon?.text = "🎵"
        updateStatus("已停止")
        updateTimeDisplay(0L, 0L)
    }

    override fun onPlaybackFinished() {
        isPlaying = false
        updatePlayPauseIcon()
        stopAnimation()
        stopProgressUpdater()
        tvPlayingIcon?.text = "🎵"
        updateTimeDisplay(0L, 0L)
        if (playMode != PlayMode.SEQUENCE) {
            updateStatus("播放完成")
            return
        }
        handler.postDelayed({ autoPlayNext() }, 800)
    }

    /** 自动播放下一首 */
    private fun autoPlayNext() {
        if (unifiedList.isEmpty()) return
        val nextIndex = if (currentUnifiedIndex >= unifiedList.size - 1) 0 else currentUnifiedIndex + 1
        currentUnifiedIndex = nextIndex
        val music = unifiedList[currentUnifiedIndex]
        currentMusicId = music.id
        currentMusicTitle = music.title
        selectedTrackIndex = null
        currentSequence = null
        updateTrackButton()
        tvMusicTitle?.text = music.title
        updateStatus("自动播放: ${music.title}")
        Log.d(TAG, "Auto next: ${music.title} (${music.source})")
        startPlayback()
    }

    // ==================== 通知 ====================

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "演奏控制", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Magic Piano - 演奏控制")
                .setContentText("正在控制 ${SupportedApps.findByPackage(currentPackageName)?.appName ?: "钢琴应用"}")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()

            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ==================== 悬浮窗 ====================

    private fun createFloatingPlayer() {
        removeFloatingView()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val dm = resources.displayMetrics
        panelWidthPx = (300 * dm.density).toInt()
        layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            format = PixelFormat.TRANSLUCENT
            width = panelWidthPx
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - panelWidthPx) / 2
            y = dm.heightPixels / 4
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_player_view, null)

        // 缓存 UI 引用
        tvMusicTitle = floatingView?.findViewById(R.id.tvMusicTitle)
        tvStatus = floatingView?.findViewById(R.id.tvStatus)
        tvCoordScheme = floatingView?.findViewById(R.id.tvCoordScheme)
        tvPlayingIcon = floatingView?.findViewById(R.id.tvPlayingIcon)
        btnPauseResumeIcon = floatingView?.findViewById(R.id.btnPauseResumeIcon)
        musicListPanel = floatingView?.findViewById<LinearLayout>(R.id.musicListPanel)
        musicListView = floatingView?.findViewById(R.id.musicListView)
        tvEmptyMusic = floatingView?.findViewById(R.id.tvEmptyMusic)
        etSearch = floatingView?.findViewById(R.id.etSearch)
        tvResultCount = floatingView?.findViewById(R.id.tvResultCount)
        progressBar = floatingView?.findViewById(R.id.progressBar)
        tvCurrentTime = floatingView?.findViewById(R.id.tvCurrentTime)
        tvTotalTime = floatingView?.findViewById(R.id.tvTotalTime)
        btnSpeedSelect = floatingView?.findViewById(R.id.btnSpeedSelect)
        btnPlayMode = floatingView?.findViewById(R.id.btnPlayMode)
        btnPressMode = floatingView?.findViewById(R.id.btnPressMode)
        btnTrackSelect = floatingView?.findViewById(R.id.btnTrackSelect)

        // 显示当前音符坐标方案（原神 / 光遇 …）
        refreshCoordinateLabel()

        // 拖动
        floatingView?.findViewById<LinearLayout>(R.id.dragHandle)?.setOnTouchListener { _, event ->
            handleDrag(event)
        }

        // 右下角缩放
        ensureResizeHandle()

        // 收缩/展开
        floatingView?.findViewById<TextView>(R.id.btnCollapse)?.setOnClickListener {
            toggleCollapse()
        }

        // 关闭 — 必须停播并销毁服务
        floatingView?.findViewById<TextView>(R.id.btnClose)?.setOnClickListener {
            stopPlayback()
            removeFloatingView()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        // 标定入口（在列表按钮旁动态加入）
        ensureCalibrateButton()

        // 上一首
        floatingView?.findViewById<TextView>(R.id.btnPrev)?.setOnClickListener {
            selectPreviousMusic()
        }

        // 下一首
        floatingView?.findViewById<TextView>(R.id.btnNext)?.setOnClickListener {
            selectNextMusic()
        }

        // 音乐列表按钮
        floatingView?.findViewById<TextView>(R.id.btnMusicList)?.setOnClickListener {
            toggleMusicListPanel()
        }

        // 播放/暂停
        floatingView?.findViewById<LinearLayout>(R.id.btnPauseResume)?.setOnClickListener {
            if (isPlaying) {
                pausePlayback()
            } else if (scheduler.isPaused) {
                resumePlayback()
            } else {
                startPlayback()
            }
        }

        // 停止
        floatingView?.findViewById<LinearLayout>(R.id.btnStop)?.setOnClickListener {
            stopPlayback()
        }

        // 搜索框：临时获取焦点
        etSearch?.setOnTouchListener { v, _ ->
            requestOverlayFocus(true)
            v.requestFocus()
            false
        }
        etSearch?.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString() ?: ""
                applySearchFilter()
                if (filterMode != FilterMode.LOCAL) {
                    cloudSearchJob?.cancel()
                    cloudSearchJob = scope.launch {
                        delay(400)
                        fetchCloudList(replace = true, fromUserSearch = true)
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 音乐列表点击
        musicListView?.setOnItemClickListener { _, _, position, _ ->
            selectFilteredMusic(position)
            hideMusicListPanel()
            requestOverlayFocus(false)
            startPlayback()
        }
        musicListView?.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {}
            override fun onScroll(
                view: android.widget.AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                if (totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 8) {
                    loadMoreCloudIfNeeded()
                }
            }
        })

        btnSpeedSelect?.setOnClickListener { showSpeedPicker() }
        btnPlayMode?.setOnClickListener { togglePlayMode() }
        btnPressMode?.setOnClickListener { togglePressMode() }
        btnTrackSelect?.setOnClickListener { showTrackPicker() }

        updateSpeedLabel(1.0f)
        updatePlayModeButton()
        updatePressModeButton()
        updateTrackButton()
        scheduler.shortPressMode = false

        // 进度条拖拽 — 拖动时动态 seek
        progressBar?.max = 1000
        progressBar?.setOnTouchListener { _, _ ->
            requestOverlayFocus(true)
            false
        }
        progressBar?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val totalMs = scheduler.getDuration()
                    if (totalMs > 0) {
                        val seekMs = (progress.toLong() * totalMs / 1000)
                        tvCurrentTime?.text = formatTime(seekMs)
                        scheduler.seekTo(seekMs)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isUserSeeking = true
                requestOverlayFocus(true)
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isUserSeeking = false
                requestOverlayFocus(false)
                val totalMs = scheduler.getDuration()
                if (totalMs > 0 && seekBar != null) {
                    val seekMs = (seekBar.progress.toLong() * totalMs / 1000)
                    scheduler.seekTo(seekMs)
                    if (scheduler.isPaused) {
                        isPlaying = false
                        updatePlayPauseIcon()
                        stopAnimation()
                        tvPlayingIcon?.text = "🎵"
                    } else if (scheduler.isPlaying) {
                        isPlaying = true
                        updatePlayPauseIcon()
                        startAnimation()
                        startProgressUpdater()
                        tvPlayingIcon?.text = "🎶"
                    }
                    updateStatus("跳转至 ${formatTime(seekMs)} — $currentMusicTitle")
                }
            }
        })

        // 初始化界面
        tvMusicTitle?.text = currentMusicTitle.ifBlank { "选择音乐" }
        updatePlayPauseIcon()
        updateStatus("准备就绪")
        updateSpeedLabel(1.0f)
        updateTimeDisplay(0L, 0L)

        try {
            windowManager?.addView(floatingView, layoutParams)
            floatingView?.post {
                val h = floatingView?.height ?: return@post
                layoutParams?.apply {
                    x = (dm.widthPixels - panelWidthPx) / 2
                    y = (dm.heightPixels - h) / 2
                }
                floatingView?.let { windowManager?.updateViewLayout(it, layoutParams) }
            }
            Log.d(TAG, "Floating player shown via APPLICATION_OVERLAY at (${layoutParams?.x}, ${layoutParams?.y})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating player", e)
            Toast.makeText(this, "无法显示悬浮窗: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== 音乐列表 ====================

    private fun loadMusicList() {
        scope.launch {
            try {
                val allMusic = withContext(Dispatchers.IO) {
                    musicRepository.getAllMusic().first()
                }
                fullMusicList = allMusic
                Log.d(TAG, "Loaded ${fullMusicList.size} local music items")

                if (fullMusicList.isEmpty()) {
                    updateStatus("正在扫描MIDI文件...")
                    val midiDir = java.io.File("${filesDir.absolutePath}/midi_music")
                    if (!midiDir.exists()) {
                        midiDir.mkdirs()
                        try {
                            val assetManager = assets
                            val assetFiles = assetManager.list("midi_music") ?: emptyArray()
                            for (fileName in assetFiles) {
                                if (fileName.endsWith(".mid") || fileName.endsWith(".midi")) {
                                    val targetFile = java.io.File(midiDir, fileName)
                                    if (!targetFile.exists()) {
                                        assetManager.open("midi_music/$fileName").use { input ->
                                            targetFile.outputStream().use { output -> input.copyTo(output) }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error copying assets", e)
                        }
                    }
                    withContext(Dispatchers.IO) {
                        musicRepository.scanLocalMidiFiles(midiDir.absolutePath)
                        fullMusicList = musicRepository.getAllMusic().first()
                    }
                    Log.d(TAG, "After scan: ${fullMusicList.size} music items")
                }

                // 悬浮窗自己拉云端列表，不依赖主界面「云音乐」页是否已打开
                if (CloudMusicCache.size == 0) {
                    fetchCloudList(replace = true, fromUserSearch = false)
                } else {
                    cloudMusicItems = CloudMusicCache.getAll()
                }

                // 找到当前音乐索引
                if (currentMusicId != null) {
                    currentMusicIndex = fullMusicList.indexOfFirst { it.id == currentMusicId }
                    // 统一列表构建后再定位
                } else if (currentMusicTitle.isNotBlank()) {
                    currentMusicIndex = fullMusicList.indexOfFirst { it.title == currentMusicTitle }
                }
                if (currentMusicIndex < 0 && fullMusicList.isNotEmpty()) {
                    currentMusicIndex = 0
                }

                // 应用搜索过滤（初始显示全部）
                applySearchFilter()

                // 根据 musicId / title 定位统一列表
                if (!currentMusicId.isNullOrBlank()) {
                    currentUnifiedIndex = unifiedList.indexOfFirst { it.id == currentMusicId || it.id == "local_$currentMusicId" || it.id.removePrefix("local_") == currentMusicId }
                }
                if (currentUnifiedIndex < 0 && currentMusicTitle.isNotBlank()) {
                    currentUnifiedIndex = unifiedList.indexOfFirst { it.title == currentMusicTitle }
                }
                if (currentUnifiedIndex < 0 && unifiedList.isNotEmpty() && pendingAutoPlay) {
                    currentUnifiedIndex = 0
                }
                if (currentUnifiedIndex >= 0 && currentUnifiedIndex < unifiedList.size) {
                    currentMusicTitle = unifiedList[currentUnifiedIndex].title
                    currentMusicId = unifiedList[currentUnifiedIndex].id
                    tvMusicTitle?.text = currentMusicTitle
                }

                if (pendingAutoPlay) {
                    pendingAutoPlay = false
                    handler.postDelayed({ startPlayback() }, 400)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading music list", e)
                tvMusicTitle?.text = "加载失败"
                tvEmptyMusic?.visibility = View.VISIBLE
                updateStatus("加载失败: ${e.message}")
            }
        }
    }

    /** 构建统一音乐列表（本地+云端） */
    private fun buildUnifiedList(): List<UnifiedMusic> {
        val list = mutableListOf<UnifiedMusic>()
        // 本地音乐
        fullMusicList.forEach { music ->
            list.add(UnifiedMusic(
                id = "local_${music.id}",
                title = music.title,
                source = "local",
                localMusic = music,
                filePath = music.filePath
            ))
        }
        // 云端音乐
        cloudMusicItems.forEach { item ->
            list.add(UnifiedMusic(
                id = "cloud_${item.id}",
                title = item.name,
                source = "cloud",
                cloudMusic = item,
                filePath = null  // 需要下载
            ))
        }
        return list
    }

    private fun relocateCurrentUnified() {
        if (currentUnifiedIndex in unifiedList.indices) {
            val cur = unifiedList[currentUnifiedIndex]
            if (currentMusicId.isNullOrBlank() || cur.id == currentMusicId || cur.title == currentMusicTitle) return
        }
        if (!currentMusicId.isNullOrBlank()) {
            currentUnifiedIndex = unifiedList.indexOfFirst {
                it.id == currentMusicId || it.id == "local_$currentMusicId" ||
                    it.id.removePrefix("local_") == currentMusicId ||
                    it.id.removePrefix("cloud_") == currentMusicId
            }
        }
        if (currentUnifiedIndex < 0 && currentMusicTitle.isNotBlank()) {
            currentUnifiedIndex = unifiedList.indexOfFirst { it.title == currentMusicTitle }
        }
    }

    private fun applySearchFilter() {
        // 构建统一列表
        unifiedList = buildUnifiedList()

        // 先按来源过滤
        val sourceFiltered = when (filterMode) {
            FilterMode.LOCAL -> unifiedList.filter { it.source == "local" }
            FilterMode.CLOUD -> unifiedList.filter { it.source == "cloud" }
            FilterMode.ALL -> unifiedList
        }

        // 再按搜索词过滤
        filteredUnifiedList = if (searchQuery.isBlank()) {
            sourceFiltered
        } else {
            val q = searchQuery.lowercase()
            sourceFiltered.filter { u ->
                u.title.lowercase().contains(q) ||
                (u.localMusic?.fileName?.lowercase()?.contains(q) == true) ||
                (u.localMusic?.getTagList()?.any { it.lowercase().contains(q) } == true) ||
                (u.cloudMusic?.singer?.lowercase()?.contains(q) == true) ||
                (u.cloudMusic?.category?.lowercase()?.contains(q) == true)
            }
        }

        // 更新列表
        val titles = filteredUnifiedList.mapIndexed { _, u ->
            val prefix = when (u.source) {
                "cloud" -> "☁️ "
                else -> ""
            }
            val check = if (currentUnifiedIndex >= 0 && currentUnifiedIndex < unifiedList.size &&
                unifiedList[currentUnifiedIndex].id == u.id) "✓ " else ""
            "$check$prefix${u.title}"
        }
        musicListAdapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, titles
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(
                    if (position < filteredUnifiedList.size && currentUnifiedIndex >= 0 &&
                        filteredUnifiedList[position].id == unifiedList.getOrNull(currentUnifiedIndex)?.id)
                        0xFFA29BFE.toInt() else 0xFFFFFFFF.toInt()
                )
                view.textSize = 13f
                view.setPadding(12, 10, 12, 10)
                return view
            }
        }
        musicListView?.adapter = musicListAdapter

        relocateCurrentUnified()

        tvResultCount?.text = run {
            val totalHint = when (filterMode) {
                FilterMode.CLOUD -> {
                    val t = CloudMusicCache.serverTotal
                    if (t > 0) t else CloudMusicCache.size
                }
                FilterMode.LOCAL -> fullMusicList.size
                FilterMode.ALL -> fullMusicList.size + maxOf(CloudMusicCache.serverTotal, CloudMusicCache.size)
            }
            "${filteredUnifiedList.size}/$totalHint"
        }
        tvEmptyMusic?.visibility = if (filteredUnifiedList.isEmpty()) View.VISIBLE else View.GONE

        // 更新当前标题：已选曲目不覆盖成「选择音乐 (N首)」
        if (currentUnifiedIndex >= 0 && currentUnifiedIndex < unifiedList.size) {
            currentMusicTitle = unifiedList[currentUnifiedIndex].title
            tvMusicTitle?.text = currentMusicTitle
        } else if (currentMusicTitle.isNotBlank() && currentMusicTitle != "选择音乐") {
            tvMusicTitle?.text = currentMusicTitle
        } else if (unifiedList.isNotEmpty()) {
            tvMusicTitle?.text = "选择音乐 (${unifiedList.size}首)"
        } else {
            tvMusicTitle?.text = "无音乐文件"
        }

        updateStatus(if (cloudMusicItems.isEmpty() && filterMode != FilterMode.LOCAL && CloudMusicCache.loading)
            "正在从云端加载音乐…"
            else if (unifiedList.isEmpty()) "请先加载音乐"
            else if (filterMode == FilterMode.CLOUD && CloudMusicCache.hasMore)
                "云端 ${CloudMusicCache.size}/${maxOf(CloudMusicCache.serverTotal, CloudMusicCache.size)}，下滑加载更多"
            else if (filteredUnifiedList.size != unifiedList.size) "搜索: ${filteredUnifiedList.size}首"
            else "就绪 (本地${fullMusicList.size}+云端${cloudMusicItems.size})")
        if (filteredUnifiedList.isEmpty() && filterMode != FilterMode.LOCAL && CloudMusicCache.loading) {
            tvEmptyMusic?.text = "正在从云端加载音乐…"
        } else if (filteredUnifiedList.isEmpty()) {
            tvEmptyMusic?.text = "暂无音乐"
        }
    }

    private fun sameCloudSearch(): Boolean {
        val q = searchQuery.ifBlank { null }
        return q == CloudMusicCache.lastSearch || (q.isNullOrBlank() && CloudMusicCache.lastSearch.isNullOrBlank())
    }

    /**
     * 悬浮窗独立请求 /api/musics。缓存为空、搜索词变化时替换首页；否则由下滑加载更多。
     */
    private fun fetchCloudList(replace: Boolean, fromUserSearch: Boolean) {
        if (filterMode == FilterMode.LOCAL) return
        if (CloudMusicCache.loading) return
        val search = searchQuery.ifBlank { null }
        if (!replace) {
            if (!CloudMusicCache.hasMore) return
        } else {
            if (!fromUserSearch && CloudMusicCache.size > 0 && sameCloudSearch()) {
                cloudMusicItems = CloudMusicCache.getAll()
                applySearchFilter()
                return
            }
            if (fromUserSearch && CloudMusicCache.size > 0 && sameCloudSearch()) return
        }
        CloudMusicCache.loading = true
        if (replace && CloudMusicCache.size == 0) {
            updateStatus("正在从云端加载音乐…")
            tvEmptyMusic?.text = "正在从云端加载音乐…"
            tvEmptyMusic?.visibility = View.VISIBLE
        }
        scope.launch {
            try {
                val token = AuthManager.token ?: ""
                val page = if (replace) 1 else CloudMusicCache.nextPage
                val result = apiRepository.getMusicList(
                    token = token,
                    page = page,
                    size = CloudMusicCache.pageSize,
                    search = search
                )
                result.fold(
                    onSuccess = { data ->
                        if (replace) {
                            CloudMusicCache.set(data.data, data.total, search)
                        } else if (data.data.isEmpty()) {
                            CloudMusicCache.markNoMore()
                        } else {
                            CloudMusicCache.addMore(data.data, data.total)
                        }
                        cloudMusicItems = CloudMusicCache.getAll()
                        Log.d(TAG, "Cloud list: ${cloudMusicItems.size}/${CloudMusicCache.serverTotal} page=$page")
                        applySearchFilter()
                    },
                    onFailure = { e ->
                        Log.e(TAG, "Fetch cloud music failed", e)
                        cloudMusicItems = CloudMusicCache.getAll()
                        applySearchFilter()
                        if (CloudMusicCache.size == 0) {
                            updateStatus("云端加载失败: ${e.message}")
                        }
                    }
                )
            } finally {
                CloudMusicCache.loading = false
            }
        }
    }

    private fun loadMoreCloudIfNeeded() {
        if (filterMode == FilterMode.LOCAL) return
        fetchCloudList(replace = false, fromUserSearch = false)
    }

    private fun findCurrentInFiltered(): Int {
        if (currentUnifiedIndex < 0 || currentUnifiedIndex >= unifiedList.size) return -1
        val current = unifiedList[currentUnifiedIndex]
        return filteredUnifiedList.indexOfFirst { it.id == current.id }
    }

    private fun toggleMusicListPanel() {
        if (musicListPanel?.visibility == View.VISIBLE) {
            hideMusicListPanel()
        } else {
            showMusicListPanel()
        }
    }

    private fun showMusicListPanel() {
        musicListPanel?.visibility = View.VISIBLE
        // 添加过滤模式按钮
        ensureFilterButtons()
        applySearchFilter()
        if (filterMode != FilterMode.LOCAL && CloudMusicCache.size == 0) {
            fetchCloudList(replace = true, fromUserSearch = false)
        }
        val pos = findCurrentInFiltered()
        if (pos >= 0) musicListView?.setSelection(pos)
    }

    private fun ensureFilterButtons() {
        val panel = musicListPanel ?: return
        // 移除旧的过滤按钮组
        val oldContainer = panel.findViewWithTag<LinearLayout>("filterContainer")
        oldContainer?.let { panel.removeView(it) }

        val filterContainer = LinearLayout(this).apply {
            tag = "filterContainer"
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(6)
            }
            gravity = android.view.Gravity.CENTER
        }

        // 在搜索框之前插入（index 1 = after header, before search box）
        val searchBoxIndex = (0 until panel.childCount).firstOrNull {
            panel.getChildAt(it) is LinearLayout && (panel.getChildAt(it) as LinearLayout).childCount > 0 &&
            panel.getChildAt(it).findViewById<android.widget.EditText>(R.id.etSearch) != null
        } ?: 1

        val filterBtns = mutableListOf<TextView>()
        FilterMode.entries.forEach { mode ->
            val btn = TextView(this).apply {
                text = mode.label
                textSize = 11f
                setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
                setOnClickListener {
                    filterMode = mode
                    applySearchFilter()
                    updateFilterButtonStyles(filterBtns)
                    if (mode != FilterMode.LOCAL && CloudMusicCache.size == 0) {
                        fetchCloudList(replace = true, fromUserSearch = false)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                gravity = android.view.Gravity.CENTER
            }
            filterBtns.add(btn)
            filterContainer.addView(btn)
        }

        panel.addView(filterContainer, searchBoxIndex)
        updateFilterButtonStyles(filterBtns)
    }

    private fun updateFilterButtonStyles(btns: List<TextView>) {
        btns.forEach { btn ->
            val mode = FilterMode.entries[btns.indexOf(btn)]
            if (mode == filterMode) {
                btn.setBackgroundResource(R.drawable.btn_speed_active_bg)
                btn.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btn.setBackgroundResource(R.drawable.btn_speed_bg)
                btn.setTextColor(0x80FFFFFF.toInt())
            }
        }
    }

    private fun hideMusicListPanel() {
        musicListPanel?.visibility = View.GONE
    }

    private fun selectFilteredMusic(filteredIndex: Int) {
        if (filteredIndex < 0 || filteredIndex >= filteredUnifiedList.size) return
        val music = filteredUnifiedList[filteredIndex]
        currentUnifiedIndex = unifiedList.indexOfFirst { it.id == music.id }
        currentMusicId = music.id
        currentMusicTitle = music.title
        selectedTrackIndex = null
        currentSequence = null
        updateTrackButton()
        tvMusicTitle?.text = music.title
        Log.d(TAG, "Selected music: ${music.title} (${music.source})")
    }

    private fun switchToMusic(newIndex: Int) {
        if (unifiedList.isEmpty()) { Toast.makeText(this, "暂无音乐", Toast.LENGTH_SHORT).show(); return }
        currentUnifiedIndex = newIndex.coerceIn(0, unifiedList.size - 1)
        val music = unifiedList[currentUnifiedIndex]
        currentMusicId = music.id
        currentMusicTitle = music.title
        selectedTrackIndex = null
        currentSequence = null
        updateTrackButton()
        tvMusicTitle?.text = music.title
        // 如果正在播放或暂停中，切换后自动播放新曲
        if (isPlaying || scheduler.isPaused) {
            scheduler.stop()
            isPlaying = false
            stopAnimation()
            stopProgressUpdater()
            updatePlayPauseIcon()
            updateStatus("切换: ${music.title}")
            handler.postDelayed({ startPlayback() }, 300)
        }
        Log.d(TAG, "Switch to: ${music.title} (${music.source})")
    }

    private fun selectPreviousMusic() {
        if (unifiedList.isEmpty()) { Toast.makeText(this, "暂无音乐", Toast.LENGTH_SHORT).show(); return }
        val newIndex = if (currentUnifiedIndex <= 0) unifiedList.size - 1 else currentUnifiedIndex - 1
        switchToMusic(newIndex)
    }

    private fun selectNextMusic() {
        if (unifiedList.isEmpty()) { Toast.makeText(this, "暂无音乐", Toast.LENGTH_SHORT).show(); return }
        val newIndex = if (currentUnifiedIndex >= unifiedList.size - 1) 0 else currentUnifiedIndex + 1
        switchToMusic(newIndex)
    }

    // ==================== 播放控制 ====================

    private fun startPlayback() {
        if (currentUnifiedIndex < 0 || currentUnifiedIndex >= unifiedList.size) {
            Toast.makeText(this, "请先选择音乐", Toast.LENGTH_SHORT).show()
            return
        }

        val pkg = SupportedApps.normalizePackage(
            currentPackageName.ifBlank { targetPackage ?: "" }
        )
        if (pkg.isBlank()) {
            Toast.makeText(this, "未设置目标应用", Toast.LENGTH_SHORT).show()
            return
        }
        currentPackageName = pkg
        targetPackage = pkg

        val unified = unifiedList[currentUnifiedIndex]

        scope.launch {
            try {
                // 0. 播放前调用接口检查 VIP 是否过期
                updateStatus("检查会员状态...")
                if (!ensureVipNotExpired()) {
                    return@launch
                }

                updateStatus("加载中...")

                // 处理云端音乐：先下载
                var filePath = unified.filePath
                if (unified.source == "cloud" && unified.cloudMusic != null) {
                    val cloud = unified.cloudMusic
                    val midiDir = java.io.File("${filesDir.absolutePath}/midi_music")
                    if (!midiDir.exists()) midiDir.mkdirs()
                    val localFile = java.io.File(midiDir, "cloud_${cloud.id}_${cloud.name}")
                    if (!localFile.exists()) {
                        updateStatus("下载中: ${cloud.name}...")
                        try {
                            withContext(Dispatchers.IO) {
                                val url = "http://39.107.213.164:8090${cloud.path}"
                                okhttp3.OkHttpClient().newCall(
                                    okhttp3.Request.Builder().url(url).build()
                                ).execute().use { response ->
                                    response.body?.byteStream()?.use { input ->
                                        localFile.outputStream().use { output -> input.copyTo(output) }
                                    }
                                }
                            }
                            Log.d(TAG, "Downloaded cloud MIDI: ${cloud.name}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Download cloud MIDI error", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@FloatingPlayerService, "下载云端音乐失败: ${e.message}", Toast.LENGTH_LONG).show()
                                updateStatus("下载失败")
                            }
                            return@launch
                        }
                    }
                    filePath = localFile.absolutePath
                }

                if (filePath == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingPlayerService, "音乐文件不存在", Toast.LENGTH_SHORT).show()
                        updateStatus("文件不存在")
                    }
                    return@launch
                }

                // 1. 解析 MIDI
                val sequence = withContext(Dispatchers.IO) {
                    midiParser.parseMidiFile(filePath)
                }
                currentSequence = sequence
                Log.d(TAG, "MIDI: ${sequence.noteCount} notes, ${sequence.duration}ms, tracks=${sequence.tracks.size}")

                // 2. 加载坐标
                val coordEntity = withContext(Dispatchers.IO) {
                    coordinateRepository.getCoordinateByPackage(pkg)
                }
                if (coordEntity == null || !coordinateRepository.isCoordinateEntityValid(coordEntity)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@FloatingPlayerService,
                            "请先标定「${SupportedApps.findByPackage(pkg)?.appName ?: pkg}」琴键坐标",
                            Toast.LENGTH_LONG
                        ).show()
                        updateStatus("无坐标配置")
                        tvCoordScheme?.text = "坐标: 未设置"
                        val appInfo = SupportedApps.findByPackage(pkg) ?: SupportedApps.getDefault()
                        showLayoutPickerDialog()
                    }
                    return@launch
                }

                val app = SupportedApps.findByPackage(pkg) ?: SupportedApps.getDefault()
                val schemeLabel = coordEntity.schemeName.ifBlank {
                    "${app.appName}-${coordEntity.keyCount}键"
                }
                withContext(Dispatchers.Main) {
                    tvCoordScheme?.text = "坐标: $schemeLabel"
                }

                val coordList: List<PianoKeyCoordinate> = gson.fromJson(
                    coordEntity.keyCoordinates,
                    Array<PianoKeyCoordinate>::class.java
                ).toList()
                coordinateManager.setCoordinates(coordList)

                // 3. 无障碍服务检查
                val service = PianoAccessibilityService.instance
                if (service == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingPlayerService, "无障碍服务未启动，请先在Magic Piano中开启", Toast.LENGTH_LONG).show()
                        updateStatus("无障碍服务未连接")
                    }
                    return@launch
                }

                // 4. 设置坐标到无障碍服务
                service.setCoordinates(coordList)
                withContext(Dispatchers.Main) { updateStatus("准备演奏...") }

                // 5. 调度并开始演奏 — 使用已保存的键位方案
                scheduler.setCoordinateManager(coordinateManager)
                val layoutTypeName = coordEntity.layoutType.ifBlank { app.layoutType }
                val lt = com.example.magicpinao.core.calibration.PianoLayoutType.fromName(layoutTypeName)
                val keyCount = coordEntity.keyCount.takeIf { it > 0 } ?: app.keyCount
                scheduler.trackFilter = selectedTrackIndex
                scheduler.shortPressMode = btnPressMode?.text?.toString() == "短按"
                scheduler.schedule(sequence, pkg, app.baseNote, keyCount, app.whiteKeysOnly, lt)
                scheduler.start()

                // 7. 更新状态
                isPlaying = true
                currentMusicTitle = unified.title
                currentMusicId = unified.id
                currentPackageName = pkg
                updatePlayPauseIcon()
                startAnimation()
                startProgressUpdater()
                updateStatus("正在演奏: ${unified.title}")
                tvCoordScheme?.text = "坐标: $schemeLabel"

                Log.d(TAG, "=== Playback started: ${unified.title} layout=$lt keys=$keyCount ===")

            } catch (e: Exception) {
                Log.e(TAG, "Playback error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloatingPlayerService, "播放失败: ${e.message}", Toast.LENGTH_LONG).show()
                    updateStatus("播放失败")
                }
            }
        }
    }

    /** 播放前调用 VIP 过期接口 */
    private suspend fun ensureVipNotExpired(): Boolean {
        val token = com.example.magicpinao.core.auth.AuthManager.token ?: ""
        if (token.isBlank()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@FloatingPlayerService, "请先登录后再播放", Toast.LENGTH_LONG).show()
                updateStatus("未登录")
            }
            return false
        }
        return try {
            val result = withContext(Dispatchers.IO) {
                com.example.magicpinao.core.network.ApiRepository().checkVipExpire(token)
            }
            result.fold(
                onSuccess = { valid ->
                    if (com.example.magicpinao.core.auth.VipValidity.isAllowed(
                            serverVipValid = valid,
                            createdAt = com.example.magicpinao.core.auth.AuthManager.createdAt,
                            cachedStatus = com.example.magicpinao.core.auth.AuthManager.vipStatus
                        )
                    ) {
                        if (valid) com.example.magicpinao.core.auth.AuthManager.updateVipStatus("2")
                        return@fold true
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingPlayerService, "使用时长已到期，请回主界面激活 VIP", Toast.LENGTH_LONG).show()
                        updateStatus("VIP已过期")
                        onVipExpired?.invoke()
                    }
                    false
                },
                onFailure = { e ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingPlayerService, "会员检查失败: ${e.message}", Toast.LENGTH_LONG).show()
                        updateStatus("会员检查失败")
                    }
                    false
                }
            )
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@FloatingPlayerService, "会员检查失败: ${e.message}", Toast.LENGTH_LONG).show()
                updateStatus("会员检查失败")
            }
            false
        }
    }

    private fun refreshCoordinateLabel() {
        val pkg = SupportedApps.normalizePackage(
            currentPackageName.ifBlank { targetPackage ?: "" }
        )
        if (pkg.isBlank()) {
            tvCoordScheme?.text = "坐标: 未选择游戏"
            return
        }
        scope.launch {
            val entity = withContext(Dispatchers.IO) {
                coordinateRepository.getCoordinateByPackage(pkg)
            }
            val app = SupportedApps.findByPackage(pkg)
            withContext(Dispatchers.Main) {
                when {
                    entity != null && coordinateRepository.isCoordinateEntityValid(entity) -> {
                        val label = entity.schemeName.ifBlank {
                            "${app?.appName ?: "未知"}-${entity.keyCount}键"
                        }
                        tvCoordScheme?.text = "坐标: $label"
                    }
                    else -> tvCoordScheme?.text = "坐标: 未设置（${app?.appName ?: pkg}）"
                }
            }
        }
    }

    private fun pausePlayback() {
        scheduler.pause()
        isPlaying = false
        updatePlayPauseIcon()
        stopAnimation()
        updateStatus("已暂停")
        Log.d(TAG, "Playback paused")
    }

    private fun resumePlayback() {
        scheduler.resume()
        isPlaying = true
        updatePlayPauseIcon()
        startAnimation()
        updateStatus("正在演奏: $currentMusicTitle")
        Log.d(TAG, "Playback resumed")
    }

    private fun stopPlayback() {
        scheduler.stop()
        isPlaying = false
        stopAnimation()
        stopProgressUpdater()
        updatePlayPauseIcon()
        updateStatus("已停止")
        updateTimeDisplay(0L, 0L)
        Log.d(TAG, "Playback stopped")
    }

    // ==================== UI 更新 ====================

    private fun updatePlayPauseIcon() {
        btnPauseResumeIcon?.text = if (isPlaying) "⏸" else "▶"
    }

    private fun updateStatus(status: String) {
        tvStatus?.text = status
    }

    private fun updateSpeedLabel(activeSpeed: Float) {
        btnSpeedSelect?.text = String.format("%.1fx ▼", activeSpeed)
    }

    private fun changeSpeed(newSpeed: Float) {
        updateSpeedLabel(newSpeed)
        if (isPlaying || scheduler.isPaused) {
            scheduler.changeSpeed(newSpeed)
            updateStatus("速度: ${String.format("%.1f", newSpeed)}x")
        } else {
            scheduler.setSpeed(newSpeed)
            updateStatus("速度: ${String.format("%.1f", newSpeed)}x")
        }
    }

    private fun showSpeedPicker() {
        val labels = speedOptions.map { String.format("%.1fx", it) }.toTypedArray()
        showOverlayListDialog("选择播放速度", labels) { which ->
            changeSpeed(speedOptions[which])
        }
    }

    private fun togglePlayMode() {
        playMode = if (playMode == PlayMode.SINGLE) PlayMode.SEQUENCE else PlayMode.SINGLE
        updatePlayModeButton()
        updateStatus(if (playMode == PlayMode.SEQUENCE) "顺序播放：播完自动下一首" else "单首播放：仅播放当前曲目")
    }

    private fun updatePlayModeButton() {
        val btn = btnPlayMode ?: return
        if (playMode == PlayMode.SEQUENCE) {
            btn.text = "顺序"
            btn.setBackgroundResource(R.drawable.btn_speed_active_bg)
            btn.setTextColor(0xFFFFFFFF.toInt())
        } else {
            btn.text = "单首"
            btn.setBackgroundResource(R.drawable.btn_speed_bg)
            btn.setTextColor(0x80FFFFFF.toInt())
        }
    }

    private fun togglePressMode() {
        scheduler.shortPressMode = !scheduler.shortPressMode
        updatePressModeButton()
        updateStatus(if (scheduler.shortPressMode) "短按：音符按下时间缩短" else "长按：按 MIDI 正常时长")
    }

    private fun updatePressModeButton() {
        val btn = btnPressMode ?: return
        if (scheduler.shortPressMode) {
            btn.text = "短按"
            btn.setBackgroundResource(R.drawable.btn_speed_bg)
            btn.setTextColor(0x80FFFFFF.toInt())
        } else {
            btn.text = "长按"
            btn.setBackgroundResource(R.drawable.btn_speed_active_bg)
            btn.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun updateTrackButton() {
        btnTrackSelect?.text = when (selectedTrackIndex) {
            null -> "单音"
            else -> "轨${selectedTrackIndex!! + 1}"
        }
    }

    private fun showTrackPicker() {
        scope.launch {
            try {
                val seq = resolveCurrentSequence()
                if (seq == null) {
                    withContext(Dispatchers.Main) {
                        showOverlayMessageDialog("选择演奏音轨", "请先在列表中选择一首 MIDI 音乐。")
                    }
                    return@launch
                }
                currentSequence = seq
                val labels = buildList {
                    add("全部音轨 (${seq.noteCount} 音)")
                    seq.tracks.forEach { t ->
                        add("音轨 ${t.index + 1}（${t.noteCount} 个音符）")
                    }
                }.toTypedArray()
                withContext(Dispatchers.Main) {
                    showOverlayListDialog("选择演奏音轨", labels) { which ->
                        selectedTrackIndex = if (which == 0) null else seq.tracks[which - 1].index
                        scheduler.trackFilter = selectedTrackIndex
                        updateTrackButton()
                        val label = if (selectedTrackIndex == null) "全部音轨" else "音轨 ${selectedTrackIndex!! + 1}"
                        updateStatus("已选 $label")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showOverlayMessageDialog("选择演奏音轨", "读取音轨失败: ${e.message}")
                }
            }
        }
    }

    private suspend fun resolveCurrentSequence(): NoteSequence? {
        currentSequence?.let { return it }
        if (currentUnifiedIndex < 0 || currentUnifiedIndex >= unifiedList.size) return null
        val unified = unifiedList[currentUnifiedIndex]
        var filePath = unified.filePath
        if (unified.source == "cloud" && unified.cloudMusic != null) {
            val cloud = unified.cloudMusic
            val localFile = java.io.File("${filesDir.absolutePath}/midi_music/cloud_${cloud.id}_${cloud.name}")
            if (localFile.exists()) filePath = localFile.absolutePath
        }
        if (filePath.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) { midiParser.parseMidiFile(filePath) }
    }

    private fun showLayoutPickerDialog() {
        val items = PianoLayoutType.entries.map { it.label }.toTypedArray()
        showOverlayListDialog("选择键位方案", items) { which ->
            val type = PianoLayoutType.entries[which]
            val layout = PianoLayouts.getLayout(type)
            FloatingCalibrationService.start(
                this,
                SupportedApps.layoutId(type),
                layout.keyCount,
                type.name,
                openTargetApp = false
            )
            Toast.makeText(
                this,
                "已选择 ${type.label}，请在游戏界面点击「开始标定」",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun overlayDialogContext(): Context =
        ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert)

    private fun applyOverlayWindowFlags(window: Window?) {
        window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            @Suppress("DEPRECATION")
            window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
        }
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
    }

    private fun showOverlayListDialog(title: String, items: Array<String>, onSelect: (Int) -> Unit) {
        requestOverlayFocus(true)
        val dialog = AlertDialog.Builder(overlayDialogContext())
            .setTitle(title)
            .setItems(items) { d, which ->
                onSelect(which)
                d.dismiss()
            }
            .setOnDismissListener { requestOverlayFocus(false) }
            .create()
        dialog.setOnShowListener { applyOverlayWindowFlags(dialog.window) }
        dialog.show()
    }

    private fun showOverlayMessageDialog(title: String, message: String) {
        requestOverlayFocus(true)
        val dialog = AlertDialog.Builder(overlayDialogContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { requestOverlayFocus(false) }
            .create()
        dialog.setOnShowListener { applyOverlayWindowFlags(dialog.window) }
        dialog.show()
    }

    private fun updateTimeDisplay(currentMs: Long, totalMs: Long) {
        tvCurrentTime?.text = formatTime(currentMs)
        tvTotalTime?.text = formatTime(totalMs)
        // 统一用 1000 作为最大值，按比例缩放
        progressBar?.max = 1000
        progressBar?.progress = if (totalMs > 0) ((currentMs.toDouble() / totalMs) * 1000).toInt().coerceIn(0, 1000) else 0
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%02d:%02d".format(min, sec)
    }

    private fun startProgressUpdater() {
        stopProgressUpdater()
        progressRunnable = object : Runnable {
            override fun run() {
                if (isPlaying && scheduler.isPlaying && !isUserSeeking) {
                    val pos = scheduler.getCurrentPosition()
                    val dur = scheduler.getDuration()
                    updateTimeDisplay(pos, dur)
                }
                handler.postDelayed(this, 250)
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun stopProgressUpdater() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    // ==================== 动画 ====================

    private fun startAnimation() {
        stopAnimation()
        animatorRunnable = object : Runnable {
            override fun run() {
                animationPhase = (animationPhase + 1) % 4
                updatePlayingBars()
                handler.postDelayed(this, 200)
            }
        }
        handler.post(animatorRunnable!!)
    }

    private fun stopAnimation() {
        animatorRunnable?.let { handler.removeCallbacks(it) }
        animatorRunnable = null
    }

    private fun updatePlayingBars() {
        // 动画通过 tvPlayingIcon 文字变化实现: 🎵 ↔ 🎶
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // ==================== 拖动 ====================

    private fun handleDrag(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = layoutParams?.x ?: 0
                dragStartY = layoutParams?.y ?: 0
                dragStartTouchX = event.rawX
                dragStartTouchY = event.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                layoutParams?.apply {
                    x = dragStartX + (event.rawX - dragStartTouchX).toInt()
                    y = dragStartY + (event.rawY - dragStartTouchY).toInt()
                }
                floatingView?.let { windowManager?.updateViewLayout(it, layoutParams) }
                true
            }
            MotionEvent.ACTION_UP -> true
            else -> false
        }
    }

    private fun requestOverlayFocus(needFocus: Boolean) {
        val lp = layoutParams ?: return
        val view = floatingView ?: return
        val flags = if (needFocus) {
            (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        }
        if (lp.flags == flags) return
        lp.flags = flags
        try {
            windowManager?.updateViewLayout(view, lp)
        } catch (_: Exception) {
        }
    }

    private fun ensureResizeHandle() {
        val dragHandle = floatingView?.findViewById<LinearLayout>(R.id.dragHandle) ?: return
        if (dragHandle.findViewWithTag<View>("resizeHandle") != null) return

        val handle = TextView(this).apply {
            tag = "resizeHandle"
            text = "◢"
            textSize = 12f
            setTextColor(0x80FFFFFF.toInt())
            gravity = Gravity.CENTER
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(18)).apply {
                marginEnd = dpToPx(6)
            }
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        resizeStartWidth = layoutParams?.width ?: panelWidthPx
                        resizeStartX = event.rawX
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dm = resources.displayMetrics
                        val minW = (220 * dm.density).toInt()
                        val maxW = (dm.widthPixels * 0.9f).toInt()
                        val newW = (resizeStartWidth + (event.rawX - resizeStartX).toInt())
                            .coerceIn(minW, maxW)
                        panelWidthPx = newW
                        layoutParams?.width = newW
                        floatingView?.findViewById<LinearLayout>(R.id.rootLayout)?.let { card ->
                            card.layoutParams = card.layoutParams.apply { width = newW }
                        }
                        floatingView?.let { windowManager?.updateViewLayout(it, layoutParams) }
                        true
                    }
                    else -> false
                }
            }
        }
        dragHandle.addView(handle, 0)
    }

    private fun ensureCalibrateButton() {
        val listBtn = floatingView?.findViewById<TextView>(R.id.btnMusicList) ?: return
        val parent = listBtn.parent as? LinearLayout ?: return
        if (parent.findViewWithTag<View>("btnCalibrate") != null) return

        val btn = TextView(this).apply {
            tag = "btnCalibrate"
            text = "键位"
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(0xFF00CEC9.toInt())
            setBackgroundResource(R.drawable.btn_speed_bg)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dpToPx(4)
            }
            setOnClickListener { showLayoutPickerDialog() }
        }
        // 插在列表按钮后面
        val idx = parent.indexOfChild(listBtn)
        parent.addView(btn, idx + 1)
        // 上一首 / 列表 / 选择键位方案 / 下一首
    }

    // ==================== 收缩/展开 ====================

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        val rootLayout = floatingView?.findViewById<LinearLayout>(R.id.rootLayout) ?: return
        val childCount = rootLayout.childCount
        for (i in 0 until childCount) {
            val child = rootLayout.getChildAt(i)
            if (child.id == R.id.dragHandle) continue
            child.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        }
        floatingView?.findViewById<TextView>(R.id.btnCollapse)?.text = if (isCollapsed) "□" else "—"
        floatingView?.findViewWithTag<View>("resizeHandle")?.visibility =
            if (isCollapsed) View.GONE else View.VISIBLE
        floatingView?.let { windowManager?.updateViewLayout(it, layoutParams) }
    }

    // ==================== 清理 ====================

    private fun removeFloatingView() {
        try {
            floatingView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating view", e)
        }
        // 兼容旧逻辑：同时清理无障碍服务侧残留
        PianoAccessibilityService.instance?.hidePlayerFloatingView()
        floatingView = null
    }

    private fun stopService() {
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
