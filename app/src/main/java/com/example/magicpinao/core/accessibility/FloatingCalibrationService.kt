package com.example.magicpinao.core.accessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.magicpinao.R
import com.example.magicpinao.core.calibration.PianoLayout
import com.example.magicpinao.core.calibration.PianoLayoutType
import com.example.magicpinao.core.calibration.PianoLayouts
import com.example.magicpinao.core.data.CoordinateEntity
import com.example.magicpinao.core.data.CoordinateRepository
import com.example.magicpinao.core.data.PianoKeyCoordinate
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 坐标标定服务（TYPE_APPLICATION_OVERLAY）
 *
 * 阶段1：显示可拖动的「开始标定」触发按钮
 * 阶段2：全屏覆盖层 + 控制面板，拖动/缩放标记对齐琴键后保存
 */
class FloatingCalibrationService : Service() {

    companion object {
        private const val TAG = "FloatingCalibration"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "calibration_channel"

        const val ACTION_START_CALIBRATION = "ACTION_START_CALIBRATION"
        const val ACTION_STOP_CALIBRATION = "ACTION_STOP_CALIBRATION"
        const val EXTRA_PACKAGE_NAME = "EXTRA_PACKAGE_NAME"
        const val EXTRA_KEY_COUNT = "EXTRA_KEY_COUNT"
        const val EXTRA_LAYOUT_TYPE = "EXTRA_LAYOUT_TYPE"
        const val EXTRA_OPEN_TARGET = "EXTRA_OPEN_TARGET"

        var instance: FloatingCalibrationService? = null
            private set

        fun start(
            context: Context,
            packageName: String,
            keyCount: Int = 21,
            layoutType: String = "LAYOUT_21",
            openTargetApp: Boolean = false
        ) {
            val intent = Intent(context, FloatingCalibrationService::class.java).apply {
                action = ACTION_START_CALIBRATION
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_KEY_COUNT, keyCount)
                putExtra(EXTRA_LAYOUT_TYPE, layoutType)
                putExtra(EXTRA_OPEN_TARGET, openTargetApp)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingCalibrationService::class.java).apply {
                action = ACTION_STOP_CALIBRATION
            }
            context.startService(intent)
        }
    }

    private enum class Phase { TRIGGER, CALIBRATE }

    private var phase = Phase.TRIGGER
    private var wm: WindowManager? = null
    private var triggerView: View? = null
    private var triggerLp: WindowManager.LayoutParams? = null
    private var overlayView: CalibrationOverlayView? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var controlPanel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var pianoLayout: PianoLayout = PianoLayouts.LAYOUT_21
    private var layoutType = PianoLayoutType.LAYOUT_21
    private var totalKeys = 21
    private var currentAppPackage = ""
    private var openTargetOnStart = true

    data class KeyMarker(
        val keyIndex: Int,
        var x: Float = 0f,
        var y: Float = 0f,
        val label: String = "",
        val isBlack: Boolean = false
    )

    private val markers = mutableListOf<KeyMarker>()

    private var tvStep: TextView? = null
    private var etSchemeName: EditText? = null
    private var layoutBtnRow: LinearLayout? = null

    private var panelStartX = 0
    private var panelStartY = 0
    private var panelTouchStartX = 0f
    private var panelTouchStartY = 0f

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALIBRATION -> {
                currentAppPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                totalKeys = intent.getIntExtra(EXTRA_KEY_COUNT, 21)
                val lt = intent.getStringExtra(EXTRA_LAYOUT_TYPE) ?: "LAYOUT_21"
                layoutType = PianoLayoutType.fromName(lt)
                pianoLayout = PianoLayouts.getLayout(layoutType)
                totalKeys = pianoLayout.keyCount
                openTargetOnStart = intent.getBooleanExtra(EXTRA_OPEN_TARGET, true)
                startForegroundNotification()
                scope.launch { prepareAndShowCalibration() }
            }
            ACTION_STOP_CALIBRATION -> {
                cleanup()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun cleanup() {
        phase = Phase.TRIGGER
        removeTriggerButton()
        removeOverlayView()
        removeControlPanel()
        // 同步清理无障碍服务侧可能残留的旧窗口
        try {
            PianoAccessibilityService.instance?.hideCalibrationTrigger()
            PianoAccessibilityService.instance?.hideCalibrationOverlay()
            PianoAccessibilityService.instance?.hideCalibrationControlPanel()
        } catch (e: Exception) {
            Log.e(TAG, "acc cleanup failed", e)
        }
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "坐标标定", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            startForeground(
                NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("坐标标定 - ${layoutType.label}")
                    .setContentText("打开目标钢琴界面后，点击「开始标定」")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setOngoing(true)
                    .build()
            )
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun baseOverlayFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    // ===== 启动前检查 =====

    private suspend fun prepareAndShowCalibration() {
        if (!hasOverlayPermission()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@FloatingCalibrationService, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                stopCalibrationService()
            }
            return
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(
                this@FloatingCalibrationService,
                "请先手动打开游戏钢琴界面，再点「开始标定」对齐 ${layoutType.label}",
                Toast.LENGTH_LONG
            ).show()
            showTriggerButton()
        }
    }

    /** 彻底关闭标定（取消 / 失败 / 完成共用） */
    private fun stopCalibrationService() {
        try {
            cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "cleanup error", e)
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            try {
                packageManager.getLaunchIntentForPackage(packageName) != null
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 仅当目标 App 是「当前活动窗口」才算在前台。
     * 不用 windows 列表，避免后台残留窗口误判。
     */
    private fun isTargetAppForeground(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val selfPkg = applicationContext.packageName
        if (packageName == selfPkg) return false
        val acc = PianoAccessibilityService.instance ?: return false
        return try {
            val root = acc.rootInActiveWindow ?: return false
            val pkg = root.packageName?.toString()
            root.recycle()
            pkg != null && pkg == packageName
        } catch (_: Exception) {
            false
        }
    }

    // ===== 阶段1：触发按钮 =====

    private fun showTriggerButton() {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        phase = Phase.TRIGGER
        initDefaultMarkers()
        removeTriggerButton()
        PianoAccessibilityService.instance?.hideCalibrationTrigger()
        val acc = PianoAccessibilityService.instance
        if (acc != null) {
            mirrorTriggerViaAccessibility()
        } else {
            createTriggerButton()
        }
        Toast.makeText(
            this,
            "请打开游戏钢琴界面，点击「开始标定」对齐 ${layoutType.label}",
            Toast.LENGTH_LONG
        ).show()
    }

    /** 无障碍 Overlay 镜像触发按钮（提高跨应用可见性） */
    private fun mirrorTriggerViaAccessibility() {
        val acc = PianoAccessibilityService.instance ?: return
        try {
            acc.showCalibrationTrigger(layoutType.label, totalKeys) {
                phase = Phase.CALIBRATE
                removeTriggerButton()
                acc.hideCalibrationTrigger()
                showCalibrationUI()
            }
            Log.d(TAG, "Mirrored trigger via accessibility overlay")
        } catch (e: Exception) {
            Log.w(TAG, "mirrorTriggerViaAccessibility failed", e)
        }
    }

    private fun createTriggerButton() {
        val density = resources.displayMetrics.density
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 20f * density
            setColor(Color.parseColor("#F01A1A2E"))
            setStroke((2f * density).toInt(), Color.parseColor("#FF6C5CE7"))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg
            setPadding(
                (18 * density).toInt(),
                (14 * density).toInt(),
                (18 * density).toInt(),
                (14 * density).toInt()
            )
            addView(TextView(context).apply {
                text = "📍"
                textSize = 28f
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "开始标定"
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "${layoutType.label} | ${totalKeys}键"
                textSize = 11f
                setTextColor(0xFFA29BFE.toInt())
                gravity = Gravity.CENTER
            })
        }

        val lp = WindowManager.LayoutParams().apply {
            type = overlayType()
            flags = baseOverlayFlags()
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        var isDrag = false
        var sx = 0f
        var sy = 0f
        var wx = 0
        var wy = 0
        card.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = event.rawX
                    sy = event.rawY
                    wx = lp.x
                    wy = lp.y
                    isDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - sx).toInt()
                    val dy = (event.rawY - sy).toInt()
                    if (!isDrag && (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12)) {
                        isDrag = true
                    }
                    if (isDrag) {
                        lp.x = wx + dx
                        lp.y = wy + dy
                        try {
                            wm?.updateViewLayout(card, lp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDrag) {
                        phase = Phase.CALIBRATE
                        removeTriggerButton()
                        PianoAccessibilityService.instance?.hideCalibrationTrigger()
                        showCalibrationUI()
                    }
                    isDrag = false
                    true
                }
                else -> false
            }
        }

        try {
            wm?.addView(card, lp)
            triggerView = card
            triggerLp = lp
            Log.d(TAG, "Trigger button shown via APPLICATION_OVERLAY")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show trigger button", e)
            Toast.makeText(this, "无法显示标定按钮: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun removeTriggerButton() {
        try {
            triggerView?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        triggerView = null
        triggerLp = null
    }

    // ===== 阶段2：标定 UI =====

    private fun showCalibrationUI() {
        initDefaultMarkers()
        // 只用一层覆盖层，避免双层抢触摸导致无法拖动/缩放
        val acc = PianoAccessibilityService.instance
        if (acc != null) {
            removeOverlayView() // 清掉可能残留的 APPLICATION 层
            try {
            acc.showCalibrationOverlay(markers.toList(), layoutType.label) { updated ->
                    markers.clear()
                    markers.addAll(updated)
                    updatePanelText()
                }
                createControlPanel(useAccessibility = true)
                // 确保控制面板在覆盖层之上，取消按钮可点
                PianoAccessibilityService.instance?.bringCalibrationControlPanelToFront()
                Log.d(TAG, "Calibration UI via accessibility overlay only")
            } catch (e: Exception) {
                Log.w(TAG, "acc overlay failed, fallback APPLICATION_OVERLAY", e)
                createOverlayView()
                createControlPanel(useAccessibility = false)
            }
        } else {
            createOverlayView()
            createControlPanel(useAccessibility = false)
        }
    }

    private fun createOverlayView() {
        removeOverlayView()
        val dm = resources.displayMetrics
        val view = CalibrationOverlayView(this)
        view.setMarkers(markers.toList())
        view.onMarkersChanged = { updated ->
            markers.clear()
            markers.addAll(updated)
            updatePanelText()
        }

        val lp = WindowManager.LayoutParams().apply {
            type = overlayType()
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            width = dm.widthPixels
            height = dm.heightPixels
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            wm?.addView(view, lp)
            overlayView = view
            overlayLp = lp
            Log.d(TAG, "Calibration overlay shown ${dm.widthPixels}x${dm.heightPixels}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
            Toast.makeText(this, "无法显示标定层: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeOverlayView() {
        try {
            overlayView?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        overlayView = null
        overlayLp = null
        PianoAccessibilityService.instance?.hideCalibrationOverlay()
    }

    private fun createControlPanel(useAccessibility: Boolean = false) {
        removeControlPanel()
        val density = resources.displayMetrics.density
        val dm = resources.displayMetrics
        controlPanel = LayoutInflater.from(this).inflate(R.layout.floating_calibration_view, null)
        tvStep = controlPanel?.findViewById(R.id.tvStep)
        val btnReset = controlPanel?.findViewById<LinearLayout>(R.id.btnCalibrate)
        val btnFinish = controlPanel?.findViewById<LinearLayout>(R.id.btnFinish)
        val btnCancel = controlPanel?.findViewById<LinearLayout>(R.id.btnCancel)
        val dragHandle = controlPanel?.findViewById<LinearLayout>(R.id.dragHandle)
        val badge = controlPanel?.findViewById<TextView>(R.id.tvProgressBadge)
        badge?.text = "${totalKeys}键"

        updatePanelText()
        addLayoutButtons()
        addSchemeInput()

        controlPanel?.findViewById<TextView>(R.id.btn_calibrate_text)?.text = "重置"
        btnReset?.setOnClickListener {
            initDefaultMarkers()
            pushMarkersToOverlay()
            updatePanelText()
            Toast.makeText(this, "已重置标记位置", Toast.LENGTH_SHORT).show()
        }
        btnFinish?.setOnClickListener { saveCalibration() }
        btnCancel?.setOnClickListener {
            Toast.makeText(this, "已取消标定", Toast.LENGTH_SHORT).show()
            stopCalibrationService()
        }
        // 同时给内部文字绑定，避免只点到 TextView 时无响应
        controlPanel?.findViewById<TextView>(R.id.btn_calibrate_text)
        (btnCancel?.getChildAt(0) as? TextView)?.setOnClickListener {
            Toast.makeText(this, "已取消标定", Toast.LENGTH_SHORT).show()
            stopCalibrationService()
        }
        (btnFinish?.getChildAt(0) as? TextView)?.setOnClickListener { saveCalibration() }

        // 整块面板可拖动（标题栏/拖动条）
        val dragListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    panelTouchStartX = event.rawX
                    panelTouchStartY = event.rawY
                    panelStartX = panelLp?.x ?: 0
                    panelStartY = panelLp?.y ?: 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - panelTouchStartX).toInt()
                    val dy = (event.rawY - panelTouchStartY).toInt()
                    panelLp?.let { lp ->
                        lp.x = (panelStartX + dx).coerceIn(0, dm.widthPixels - dp2px(120))
                        lp.y = (panelStartY + dy).coerceIn(0, dm.heightPixels - dp2px(80))
                        updateControlPanelLayout()
                    }
                    true
                }
                else -> false
            }
        }
        dragHandle?.setOnTouchListener(dragListener)
        // 标题行也可拖
        (controlPanel as? LinearLayout)?.getChildAt(1)?.setOnTouchListener(dragListener)

        val panelW = (280 * density).toInt()
        val lp = WindowManager.LayoutParams().apply {
            type = if (useAccessibility) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                overlayType()
            }
            flags = baseOverlayFlags()
            format = PixelFormat.TRANSLUCENT
            width = panelW
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - panelW - 16 * density).toInt().coerceAtLeast(0)
            y = (60 * density).toInt()
        }

        try {
            if (useAccessibility && PianoAccessibilityService.instance != null) {
                // 通过无障碍服务挂载，保证在标定覆盖层之上
                PianoAccessibilityService.instance!!.showCalibrationControlPanel(controlPanel!!)
                // 同步我们的 lp 引用，便于拖动更新
                panelLp = PianoAccessibilityService.instance!!.getCalPanelLp() ?: lp
                // 若无障碍侧 lp 是 CENTER，改成 START 坐标
                panelLp?.let { plp ->
                    plp.gravity = Gravity.TOP or Gravity.START
                    plp.x = lp.x
                    plp.y = lp.y
                    plp.width = panelW
                    PianoAccessibilityService.instance?.updateCalibrationControlPanel()
                }
            } else {
                wm?.addView(controlPanel, lp)
                panelLp = lp
            }
            Log.d(TAG, "Control panel shown useAcc=$useAccessibility")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show control panel", e)
            try {
                wm?.addView(controlPanel, lp.apply { type = overlayType() })
                panelLp = lp
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback panel also failed", e2)
            }
        }
    }

    private fun updateControlPanelLayout() {
        val acc = PianoAccessibilityService.instance
        if (acc != null && acc.getCalPanelLp() != null) {
            // 把坐标写回无障碍侧 lp
            acc.getCalPanelLp()?.let { alp ->
                panelLp?.let { p ->
                    alp.x = p.x
                    alp.y = p.y
                    alp.gravity = Gravity.TOP or Gravity.START
                }
            }
            acc.updateCalibrationControlPanel()
        } else {
            try {
                controlPanel?.let { wm?.updateViewLayout(it, panelLp) }
            } catch (_: Exception) {
            }
        }
    }

    private fun pushMarkersToOverlay() {
        overlayView?.setMarkers(markers.toList())
        PianoAccessibilityService.instance?.showCalibrationOverlay(markers.toList(), layoutType.label) { updated ->
            markers.clear()
            markers.addAll(updated)
            updatePanelText()
        }
    }

    private fun removeControlPanel() {
        try {
            controlPanel?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        PianoAccessibilityService.instance?.hideCalibrationControlPanel()
        controlPanel = null
        panelLp = null
        layoutBtnRow = null
        etSchemeName = null
    }

    private fun refreshOverlayMarkers() {
        pushMarkersToOverlay()
        updatePanelText()
    }

    // ===== UI 辅助 =====

    private fun addLayoutButtons() {
        val panel = controlPanel?.findViewById<LinearLayout>(R.id.rootLayout) ?: return
        val stepIdx = (0 until panel.childCount).firstOrNull {
            panel.getChildAt(it).id == R.id.tvStep
        } ?: return

        layoutBtnRow?.let { panel.removeView(it) }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp2px(8) }
        }
        PianoLayoutType.entries.toList().chunked(4).forEach { chunk ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp2px(4) }
            }
            chunk.forEach { t ->
                val btn = TextView(this).apply {
                    text = t.shortLabel
                    tag = t
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setPadding(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        layoutType = t
                        pianoLayout = PianoLayouts.getLayout(t)
                        totalKeys = pianoLayout.keyCount
                        currentAppPackage = SupportedApps.layoutId(t)
                        initDefaultMarkers()
                        refreshOverlayMarkers()
                        updateLayoutBtnStyles(wrap)
                        startForegroundNotification()
                        controlPanel?.findViewById<TextView>(R.id.tvProgressBadge)?.text = "${totalKeys}键"
                        Toast.makeText(this@FloatingCalibrationService, "已切换: ${t.label}", Toast.LENGTH_SHORT).show()
                        updatePanelText()
                    }
                }
                row.addView(btn)
            }
            repeat(4 - chunk.size) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            wrap.addView(row)
        }
        panel.addView(wrap, stepIdx + 1)
        layoutBtnRow = wrap
        updateLayoutBtnStyles(wrap)
    }

    private fun updateLayoutBtnStyles(row: LinearLayout) {
        fun styleBtn(btn: TextView) {
            val t = btn.tag as? PianoLayoutType
            val active = t == layoutType
            btn.setBackgroundResource(if (active) R.drawable.btn_speed_active_bg else R.drawable.btn_speed_bg)
            btn.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt())
        }
        for (i in 0 until row.childCount) {
            when (val child = row.getChildAt(i)) {
                is TextView -> styleBtn(child)
                is LinearLayout -> {
                    for (j in 0 until child.childCount) {
                        (child.getChildAt(j) as? TextView)?.let { styleBtn(it) }
                    }
                }
            }
        }
    }

    private fun addSchemeInput() {
        val panel = controlPanel?.findViewById<LinearLayout>(R.id.rootLayout) ?: return
        etSchemeName?.let { panel.removeView(it) }
        val idx = (0 until panel.childCount).firstOrNull {
            panel.getChildAt(it).id == R.id.tvStep
        } ?: return
        etSchemeName = EditText(this).apply {
            hint = "方案名称（可选）"
            setHintTextColor(0x60FFFFFF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setPadding(dp2px(8), dp2px(6), dp2px(8), dp2px(6))
            setBackgroundResource(R.drawable.btn_speed_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp2px(8) }
            setOnTouchListener { v, _ ->
                // 临时允许焦点以便输入方案名
                panelLp?.let { lp ->
                    lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    try {
                        controlPanel?.let { wm?.updateViewLayout(it, lp) }
                    } catch (_: Exception) {
                    }
                }
                v.requestFocus()
                false
            }
        }
        // 插在布局按钮之后
        panel.addView(etSchemeName, (idx + 2).coerceAtMost(panel.childCount))
    }

    private fun updatePanelText() {
        tvStep?.text = "${layoutType.label} | ${markers.size}键 | 拖动外框对齐琴键后点完成"
    }

    // ===== 保存 =====

    private fun saveCalibration() {
        if (markers.isEmpty()) {
            Toast.makeText(this, "没有可保存的标记", Toast.LENGTH_SHORT).show()
            return
        }
        val dm = resources.displayMetrics
        val coords = markers.mapIndexed { i, m ->
            val key = pianoLayout.getOrderedKeys().getOrNull(i)
            PianoKeyCoordinate(
                i,
                key?.midiNote ?: (48 + i),
                m.x.toInt().coerceIn(0, dm.widthPixels),
                m.y.toInt().coerceIn(0, dm.heightPixels),
                dp2px(36),
                dp2px(36)
            )
        }
        // 基本有效性：坐标不能全挤在一起
        val xs = coords.map { it.centerX }
        val ys = coords.map { it.centerY }
        if ((xs.maxOrNull() ?: 0) - (xs.minOrNull() ?: 0) < 50 ||
            (ys.maxOrNull() ?: 0) - (ys.minOrNull() ?: 0) < 50
        ) {
            Toast.makeText(this, "请先拖动外框对齐实际琴键位置", Toast.LENGTH_LONG).show()
            return
        }

        scope.launch {
            try {
                val layoutPkg = SupportedApps.layoutId(layoutType)
                currentAppPackage = layoutPkg
                val app = SupportedApps.findByLayout(layoutType)
                val repo = CoordinateRepository(applicationContext)
                val name = etSchemeName?.text?.toString()?.trim()?.ifBlank {
                    val sdf = java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.getDefault())
                    "${layoutType.label}-${sdf.format(java.util.Date())}"
                } ?: layoutType.label

                repo.saveCoordinate(
                    CoordinateEntity(
                        deviceId = repo.generateDeviceId(
                            layoutPkg,
                            dm.widthPixels,
                            dm.heightPixels
                        ),
                        packageName = layoutPkg,
                        appName = app.appName,
                        screenWidth = dm.widthPixels,
                        screenHeight = dm.heightPixels,
                        keyCount = totalKeys,
                        calibrationMode = "DRAG_MARKERS",
                        schemeName = name,
                        layoutType = layoutType.name,
                        keyCoordinates = Gson().toJson(coords)
                    )
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "方案「$name」已保存！", Toast.LENGTH_LONG).show()
                }
                delay(600)
                withContext(Dispatchers.Main) {
                    FloatingPlayerService.start(applicationContext, currentAppPackage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Save failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { stopCalibrationService() }
            }
        }
    }

    // ===== 工具 =====

    private fun initDefaultMarkers() {
        markers.clear()
        val dm = resources.displayMetrics
        val kt = dm.heightPixels * 0.38f
        val kb = dm.heightPixels * 0.88f
        val kl = dm.widthPixels * 0.06f
        val kr = dm.widthPixels * 0.94f
        val keys = pianoLayout.getOrderedKeys()
        val rh = (kb - kt) / pianoLayout.rows
        keys.forEachIndexed { i, k ->
            val yBase = kb - rh * (k.row + 0.5f)
            val blackLift = blackKeyLiftFor(k, rh)
            val y = if (blackLift > 0f) yBase - blackLift else yBase
            val x = kl + (kr - kl) * pianoLayout.xRatio(k.visualCol)
            val staggered = blackLift > 0f
            markers.add(KeyMarker(i, x, y, k.label, k.isBlack && staggered))
        }
    }

    private fun blackKeyLiftFor(key: com.example.magicpinao.core.calibration.KeyPosition, rh: Float): Float {
        if (!key.isBlack) return 0f
        return when (pianoLayout.type) {
            PianoLayoutType.LAYOUT_37_SHAPED -> if (key.row == 2) rh * pianoLayout.blackKeyLiftRatio else 0f
            else -> if (pianoLayout.staggerBlackKeys) rh * pianoLayout.blackKeyLiftRatio else 0f
        }
    }

    private fun openTargetApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else {
                Toast.makeText(this, "未安装「${getAppName()}」，无法打开", Toast.LENGTH_LONG).show()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "openTargetApp failed", e)
            Toast.makeText(this, "打开失败: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun getAppName() =
        SupportedApps.findByPackage(currentAppPackage)?.appName ?: layoutType.label

    private fun dp2px(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}

// ===== CalibrationOverlayView =====

class CalibrationOverlayView(context: Context) : View(context) {

    private val paintFrame = android.graphics.Paint().apply {
        color = Color.parseColor("#AA00CEC9")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(16f, 8f), 0f)
    }
    private val paintFrameFill = android.graphics.Paint().apply {
        color = Color.parseColor("#0800CEC9")
        style = android.graphics.Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintHandle = android.graphics.Paint().apply {
        color = Color.parseColor("#CC00CEC9")
        style = android.graphics.Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintHandleStroke = android.graphics.Paint().apply {
        color = Color.parseColor("#FFFFFFFF")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val paintMarker = android.graphics.Paint().apply {
        color = Color.parseColor("#E06C5CE7")
        style = android.graphics.Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintMarkerStroke = android.graphics.Paint().apply {
        color = Color.parseColor("#FFA29BFE")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val paintSel = android.graphics.Paint().apply {
        color = Color.parseColor("#FF00CEC9")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }
    private val paintText = android.graphics.Paint().apply {
        color = Color.WHITE
        textSize = 22f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    private val paintLine = android.graphics.Paint().apply {
        color = Color.parseColor("#306C5CE7")
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    private val paintHint = android.graphics.Paint().apply {
        color = Color.parseColor("#CCFFFFFF")
        textSize = 28f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }

    private var markers: List<FloatingCalibrationService.KeyMarker> = emptyList()
    private val markerR = 28f
    private val blackMarkerR = 16f
    private val handleS = 24f

    var onMarkersChanged: ((List<FloatingCalibrationService.KeyMarker>) -> Unit)? = null

    private enum class Drag { NONE, MARKER, FRAME, H_TL, H_TR, H_BL, H_BR, H_ML, H_MR, H_MT, H_MB }

    private var drag = Drag.NONE
    private var dragIdx = -1
    private var tsX = 0f
    private var tsY = 0f
    private var startMarkers: List<Pair<Float, Float>> = emptyList()

    fun setMarkers(list: List<FloatingCalibrationService.KeyMarker>) {
        markers = list.map { it.copy() }
        invalidate()
    }

    private fun frame(): android.graphics.RectF {
        if (markers.isEmpty()) return android.graphics.RectF(100f, 300f, 900f, 1500f)
        val p = 70f
        return android.graphics.RectF(
            markers.minOf { it.x } - p,
            markers.minOf { it.y } - p,
            markers.maxOf { it.x } + p,
            markers.maxOf { it.y } + p
        )
    }

    private fun hitHandle(x: Float, y: Float, f: android.graphics.RectF): Drag {
        // 加大手柄热区，优先于标记点
        val h = handleS + 28f
        val mx = f.centerX()
        val my = f.centerY()
        if (android.graphics.RectF(f.left - h, f.top - h, f.left + h, f.top + h).contains(x, y)) return Drag.H_TL
        if (android.graphics.RectF(f.right - h, f.top - h, f.right + h, f.top + h).contains(x, y)) return Drag.H_TR
        if (android.graphics.RectF(f.left - h, f.bottom - h, f.left + h, f.bottom + h).contains(x, y)) return Drag.H_BL
        if (android.graphics.RectF(f.right - h, f.bottom - h, f.right + h, f.bottom + h).contains(x, y)) return Drag.H_BR
        if (android.graphics.RectF(f.left - h, my - h, f.left + h, my + h).contains(x, y)) return Drag.H_ML
        if (android.graphics.RectF(f.right - h, my - h, f.right + h, my + h).contains(x, y)) return Drag.H_MR
        if (android.graphics.RectF(mx - h, f.top - h, mx + h, f.top + h).contains(x, y)) return Drag.H_MT
        if (android.graphics.RectF(mx - h, f.bottom - h, mx + h, f.bottom + h).contains(x, y)) return Drag.H_MB
        // 框内空白（非标记）用于整体拖动
        if (f.contains(x, y)) return Drag.FRAME
        return Drag.NONE
    }

    private fun markerRadius(m: FloatingCalibrationService.KeyMarker) =
        if (m.isBlack) blackMarkerR else markerR

    private fun hitMarker(x: Float, y: Float): Int {
        for (i in markers.indices.reversed()) {
            val d = kotlin.math.sqrt(
                ((x - markers[i].x) * (x - markers[i].x) + (y - markers[i].y) * (y - markers[i].y)).toDouble()
            )
            if (d < markerRadius(markers[i]) + 16f) return i
        }
        return -1
    }

    override fun onDraw(c: android.graphics.Canvas) {
        super.onDraw(c)
        c.drawColor(Color.parseColor("#10000000"))
        val f = frame()
        c.drawRect(f, paintFrameFill)
        c.drawRect(f, paintFrame)
        listOf(
            f.left to f.top, f.right to f.top, f.left to f.bottom, f.right to f.bottom,
            f.left to f.centerY(), f.right to f.centerY(), f.centerX() to f.top, f.centerX() to f.bottom
        ).forEach { (hx, hy) ->
            c.drawRect(hx - handleS, hy - handleS, hx + handleS, hy + handleS, paintHandle)
            c.drawRect(hx - handleS, hy - handleS, hx + handleS, hy + handleS, paintHandleStroke)
        }

        val pr = (markers.size / 3).coerceAtLeast(1)
        for (r in 0..2) {
            val rm = markers.filter { it.keyIndex / pr == r || (r == 2 && it.keyIndex / pr >= 2) }
            listOf(rm.filter { !it.isBlack }, rm.filter { it.isBlack }).forEach { group ->
                val sorted = group.sortedBy { it.x }
                for (i in 0 until sorted.size - 1) {
                    c.drawLine(sorted[i].x, sorted[i].y, sorted[i + 1].x, sorted[i + 1].y, paintLine)
                }
            }
        }
        markers.forEachIndexed { i, m ->
            val baseR = markerRadius(m)
            val rr = if (drag == Drag.MARKER && dragIdx == i) baseR + 8f else baseR
            c.drawCircle(m.x, m.y, rr, paintMarker)
            c.drawCircle(m.x, m.y, rr, paintMarkerStroke)
            if (drag == Drag.MARKER && dragIdx == i) c.drawCircle(m.x, m.y, rr + 4f, paintSel)
            c.drawText(m.label, m.x, m.y - rr - 10f, paintText)
        }
        c.drawText("拖动边框手柄缩放 | 拖动框内空白平移 | 拖动圆点微调", width / 2f, height * 0.06f, paintHint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // 使用 raw 相对 view 的坐标更稳：getX/getY
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // 手柄优先于标记，否则角落标记会挡住缩放
                val h = hitHandle(x, y, frame())
                if (h != Drag.NONE && h != Drag.FRAME) {
                    drag = h
                    tsX = x
                    tsY = y
                    startMarkers = markers.map { it.x to it.y }
                    invalidate()
                    return true
                }
                val mi = hitMarker(x, y)
                if (mi >= 0) {
                    drag = Drag.MARKER
                    dragIdx = mi
                    tsX = x
                    tsY = y
                    startMarkers = listOf(markers[mi].x to markers[mi].y)
                    invalidate()
                    return true
                }
                if (h == Drag.FRAME) {
                    drag = Drag.FRAME
                    tsX = x
                    tsY = y
                    startMarkers = markers.map { it.x to it.y }
                    invalidate()
                    return true
                }
                // 框外空白：开始整体拖动（以当前标记包围盒为准）
                if (markers.isNotEmpty()) {
                    drag = Drag.FRAME
                    tsX = x
                    tsY = y
                    startMarkers = markers.map { it.x to it.y }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.NONE) return true
                val dx = x - tsX
                val dy = y - tsY
                when (drag) {
                    Drag.MARKER -> {
                        if (dragIdx in markers.indices && startMarkers.isNotEmpty()) {
                            markers = markers.toMutableList().apply {
                                set(dragIdx, get(dragIdx).copy(
                                    x = startMarkers[0].first + dx,
                                    y = startMarkers[0].second + dy
                                ))
                            }
                        }
                    }
                    Drag.FRAME -> {
                        markers = startMarkers.mapIndexed { i, (sx, sy) ->
                            markers[i].copy(x = sx + dx, y = sy + dy)
                        }
                    }
                    Drag.H_TL -> applyHandle(0f, 0f, dx, dy)
                    Drag.H_TR -> applyHandle(1f, 0f, dx, dy)
                    Drag.H_BL -> applyHandle(0f, 1f, dx, dy)
                    Drag.H_BR -> applyHandle(1f, 1f, dx, dy)
                    Drag.H_ML -> applyHandle(0f, 0.5f, dx, 0f)
                    Drag.H_MR -> applyHandle(1f, 0.5f, dx, 0f)
                    Drag.H_MT -> applyHandle(0.5f, 0f, 0f, dy)
                    Drag.H_MB -> applyHandle(0.5f, 1f, 0f, dy)
                    else -> {}
                }
                invalidate()
                // 拖动过程中也回调，保证外部状态同步
                onMarkersChanged?.invoke(markers)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag != Drag.NONE) {
                    drag = Drag.NONE
                    dragIdx = -1
                    onMarkersChanged?.invoke(markers)
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun applyHandle(hx: Float, hy: Float, dx: Float, dy: Float) {
        if (markers.size < 2 || startMarkers.size < 2) return
        val xs = startMarkers.map { it.first }
        val ys = startMarkers.map { it.second }
        val ol = xs.min()
        val or = xs.max()
        val ot = ys.min()
        val ob = ys.max()
        val ow = (or - ol).coerceAtLeast(1f)
        val oh = (ob - ot).coerceAtLeast(1f)
        val nl = if (hx == 0f) ol + dx else ol
        val nr = if (hx == 1f) or + dx else or
        val nt = if (hy == 0f) ot + dy else ot
        val nb = if (hy == 1f) ob + dy else ob
        val nL = minOf(nl, nr - 80f)
        val nR = maxOf(nr, nl + 80f)
        val nT = minOf(nt, nb - 80f)
        val nB = maxOf(nb, nt + 80f)
        val nw = nR - nL
        val nh = nB - nT
        markers = startMarkers.mapIndexed { i, (sx, sy) ->
            markers[i].copy(
                x = nL + ((sx - ol) / ow).coerceIn(0f, 1f) * nw,
                y = nT + ((sy - ot) / oh).coerceIn(0f, 1f) * nh
            )
        }
    }
}
