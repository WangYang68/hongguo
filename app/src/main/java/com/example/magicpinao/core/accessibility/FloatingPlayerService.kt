package com.example.magicpinao.core.accessibility

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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
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
import com.example.magicpinao.core.midi.MidiParser
import com.example.magicpinao.core.network.CloudMusicCache
import com.example.magicpinao.core.network.MusicItem
import com.example.magicpinao.core.performance.PerformanceScheduler
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private var filterMode = FilterMode.ALL

    // 音乐列表
    private var fullMusicList: List<MusicEntity> = emptyList()
    private var cloudMusicItems: List<MusicItem> = emptyList()
    private var unifiedList: List<UnifiedMusic> = emptyList()
    private var filteredUnifiedList: List<UnifiedMusic> = emptyList()
    private var currentMusicIndex: Int = -1
    private var currentUnifiedIndex: Int = -1
    private var musicListAdapter: ArrayAdapter<String>? = null
    private var searchQuery = ""

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
    private var btnSpeed05: TextView? = null
    private var btnSpeed1: TextView? = null
    private var btnSpeed15: TextView? = null
    private var btnSpeed2: TextView? = null

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
        removeFloatingView()
        instance = null
        scope.cancel()
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
        // 自动播放下一首，没有则循环到第一首
        handler.postDelayed({
            autoPlayNext()
        }, 800)
    }

    /** 自动播放下一首 */
    private fun autoPlayNext() {
        if (unifiedList.isEmpty()) return
        val nextIndex = if (currentUnifiedIndex >= unifiedList.size - 1) 0 else currentUnifiedIndex + 1
        currentUnifiedIndex = nextIndex
        val music = unifiedList[currentUnifiedIndex]
        currentMusicId = music.id
        currentMusicTitle = music.title
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
            x = (dm.widthPixels - panelWidthPx - 24).coerceAtLeast(0)
            y = dm.heightPixels / 5
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_player_view, null)

        // 缓存 UI 引用
        tvMusicTitle = floatingView?.findViewById(R.id.tvMusicTitle)
        tvStatus = floatingView?.findViewById(R.id.tvStatus)
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
        btnSpeed05 = floatingView?.findViewById(R.id.btnSpeed05)
        btnSpeed1 = floatingView?.findViewById(R.id.btnSpeed1)
        btnSpeed15 = floatingView?.findViewById(R.id.btnSpeed15)
        btnSpeed2 = floatingView?.findViewById(R.id.btnSpeed2)

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

        // 关闭
        floatingView?.findViewById<TextView>(R.id.btnClose)?.setOnClickListener {
            stopPlayback()
            removeFloatingView()
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

        // 速度按钮
        btnSpeed05?.setOnClickListener { changeSpeed(0.5f) }
        btnSpeed1?.setOnClickListener { changeSpeed(1.0f) }
        btnSpeed15?.setOnClickListener { changeSpeed(1.5f) }
        btnSpeed2?.setOnClickListener { changeSpeed(2.0f) }

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
        updateSpeedButtons(1.0f)
        updateTimeDisplay(0L, 0L)

        try {
            windowManager?.addView(floatingView, layoutParams)
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
                // 同时加载云端音乐缓存
                cloudMusicItems = CloudMusicCache.getAll()
                Log.d(TAG, "Loaded ${fullMusicList.size} local + ${cloudMusicItems.size} cloud music items")

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

        tvResultCount?.text = "${filteredUnifiedList.size}/${unifiedList.size}"
        tvEmptyMusic?.visibility = if (filteredUnifiedList.isEmpty()) View.VISIBLE else View.GONE

        // 更新当前标题
        if (currentUnifiedIndex >= 0 && currentUnifiedIndex < unifiedList.size) {
            currentMusicTitle = unifiedList[currentUnifiedIndex].title
            tvMusicTitle?.text = currentMusicTitle
        } else if (unifiedList.isNotEmpty()) {
            tvMusicTitle?.text = "选择音乐 (${unifiedList.size}首)"
        } else {
            tvMusicTitle?.text = "无音乐文件"
        }

        updateStatus(if (unifiedList.isEmpty()) "请先加载音乐"
            else if (filteredUnifiedList.size != unifiedList.size) "搜索: ${filteredUnifiedList.size}首"
            else "就绪 (本地${fullMusicList.size}+云端${cloudMusicItems.size})")
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
        tvMusicTitle?.text = music.title
        Log.d(TAG, "Selected music: ${music.title} (${music.source})")
    }

    private fun switchToMusic(newIndex: Int) {
        if (unifiedList.isEmpty()) { Toast.makeText(this, "暂无音乐", Toast.LENGTH_SHORT).show(); return }
        currentUnifiedIndex = newIndex.coerceIn(0, unifiedList.size - 1)
        val music = unifiedList[currentUnifiedIndex]
        currentMusicId = music.id
        currentMusicTitle = music.title
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

        val pkg = currentPackageName.ifBlank { targetPackage ?: "" }
        if (pkg.isBlank()) {
            Toast.makeText(this, "未设置目标应用", Toast.LENGTH_SHORT).show()
            return
        }

        val unified = unifiedList[currentUnifiedIndex]

        scope.launch {
            try {
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
                Log.d(TAG, "MIDI: ${sequence.noteCount} notes, ${sequence.duration}ms")

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
                        val appInfo = SupportedApps.findByPackage(pkg) ?: SupportedApps.getDefault()
                        FloatingCalibrationService.start(
                            this@FloatingPlayerService, pkg, appInfo.keyCount, appInfo.layoutType, openTargetApp = true
                        )
                    }
                    return@launch
                }

                val app = SupportedApps.findByPackage(pkg) ?: SupportedApps.getDefault()
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

                // 5. 确保目标应用在前台（如果已在则跳过等待）
                val root = service.rootInActiveWindow
                val fgPkg = root?.packageName?.toString()
                root?.recycle()
                if (fgPkg != pkg) {
                    service.openTargetApp(pkg)
                    withContext(Dispatchers.Main) { updateStatus("正在打开 ${app.appName}...") }
                    kotlinx.coroutines.delay(1500) // 等待应用切换
                } else {
                    withContext(Dispatchers.Main) { updateStatus("准备演奏...") }
                    kotlinx.coroutines.delay(300) // 短暂等待确保焦点
                }
                Log.d(TAG, "Foreground: $fgPkg")

                // 6. 调度并开始演奏 — 优先使用标定方案的 layoutType
                scheduler.setCoordinateManager(coordinateManager)
                val layoutTypeName = coordEntity.layoutType.ifBlank { app.layoutType }
                val lt = try {
                    com.example.magicpinao.core.calibration.PianoLayoutType.valueOf(layoutTypeName)
                } catch (_: Exception) {
                    com.example.magicpinao.core.calibration.PianoLayoutType.LAYOUT_21
                }
                val keyCount = coordEntity.keyCount.takeIf { it > 0 } ?: app.keyCount
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

    private fun updateSpeedButtons(activeSpeed: Float) {
        val speeds = mapOf(btnSpeed05 to 0.5f, btnSpeed1 to 1.0f, btnSpeed15 to 1.5f, btnSpeed2 to 2.0f)
        for ((btn, spd) in speeds) {
            if (spd == activeSpeed) {
                btn?.setBackgroundResource(R.drawable.btn_speed_active_bg)
                btn?.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btn?.setBackgroundResource(R.drawable.btn_speed_bg)
                btn?.setTextColor(0x80FFFFFF.toInt())
            }
        }
    }

    private fun changeSpeed(newSpeed: Float) {
        updateSpeedButtons(newSpeed)
        if (isPlaying || scheduler.isPaused) {
            // 动态调整：不停止，从当前位置以新速度继续
            scheduler.changeSpeed(newSpeed)
            updateStatus("速度: ${newSpeed}x (动态调整)")
        } else {
            scheduler.setSpeed(newSpeed)
            updateStatus("速度: ${newSpeed}x")
        }
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
        val root = floatingView as? android.widget.FrameLayout ?: return
        if (root.findViewWithTag<View>("resizeHandle") != null) return

        val handle = TextView(this).apply {
            tag = "resizeHandle"
            text = "◢"
            textSize = 14f
            setTextColor(0x80FFFFFF.toInt())
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                dpToPx(36), dpToPx(36), Gravity.BOTTOM or Gravity.END
            )
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
        root.addView(handle)
    }

    private fun ensureCalibrateButton() {
        val listBtn = floatingView?.findViewById<TextView>(R.id.btnMusicList) ?: return
        val parent = listBtn.parent as? LinearLayout ?: return
        if (parent.findViewWithTag<View>("btnCalibrate") != null) return

        val btn = TextView(this).apply {
            tag = "btnCalibrate"
            text = "📍标定"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF00CEC9.toInt())
            setBackgroundResource(R.drawable.btn_speed_bg)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dpToPx(4)
            }
            setOnClickListener {
                val pkg = currentPackageName.ifBlank { targetPackage ?: "" }
                if (pkg.isBlank()) {
                    Toast.makeText(this@FloatingPlayerService, "未设置目标应用", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                stopPlayback()
                val app = SupportedApps.findByPackage(pkg) ?: SupportedApps.getDefault()
                FloatingCalibrationService.start(
                    this@FloatingPlayerService, pkg, app.keyCount, app.layoutType, openTargetApp = true
                )
                Toast.makeText(this@FloatingPlayerService, "进入坐标标定", Toast.LENGTH_SHORT).show()
            }
        }
        // 插在列表按钮后面
        val idx = parent.indexOfChild(listBtn)
        parent.addView(btn, idx + 1)
        // 重新平衡权重：上一首 / 列表 / 标定 / 下一首
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            lp.weight = 1f
            child.layoutParams = lp
        }
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
