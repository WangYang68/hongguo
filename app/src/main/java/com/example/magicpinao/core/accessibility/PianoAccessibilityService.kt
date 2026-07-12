package com.example.magicpinao.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.magicpinao.core.coordinate.CoordinateManager
import com.example.magicpinao.core.data.PianoKeyCoordinate

/**
 * 钢琴自动演奏无障碍服务
 * 负责发送触摸事件到目标钢琴应用
 */
class PianoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PianoAccessibility"

        var instance: PianoAccessibilityService? = null
            private set

        var isPlaying: Boolean = false

        var targetPackageName: String? = null

        private var touchCaptureListener: ((Int, Int) -> Unit)? = null
    }

    private val coordinateManager = CoordinateManager()
    private val handler = Handler(Looper.getMainLooper())
    private var currentCoordinates: List<PianoKeyCoordinate> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "=== Accessibility Service Connected ===")
        Log.d(TAG, "Gesture dispatch supported: true")
        Log.d(TAG, GestureDispatcher.getDiagnostics(this))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()

            if (packageName != null && packageName == targetPackageForOverlay && overlayDisplayId <= 0) {
                try {
                    val newDisplayId = findDisplayForPackage(packageName)
                    if (newDisplayId > 0) {
                        Log.d(TAG, "Target app detected on display $newDisplayId")
                        overlayDisplayId = newDisplayId
                        overlayWindowContext = null
                        if (calTriggerView != null && calTriggerOnClick != null) {
                            showCalibrationTrigger(calTriggerLayoutLabel ?: "", calTriggerTotalKeys, calTriggerOnClick!!)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Display detection in onAccessibilityEvent failed", e)
                }
            }

            if (isPlaying && packageName != null && packageName != targetPackageName) {
                Log.d(TAG, "Window switched from $targetPackageName to $packageName, stopping")
                stopPerformance()
            }
        }

        // 触摸捕获（用于坐标标定）
        // 支持多种事件类型以提高可靠性
        val isTouchEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                event.eventType == AccessibilityEvent.TYPE_GESTURE_DETECTION_START

        if (touchCaptureListener != null && isTouchEvent) {
            val source = event.source
            if (source != null) {
                val rect = Rect()
                source.getBoundsInScreen(rect)
                val cx = rect.centerX()
                val cy = rect.centerY()
                Log.d(TAG, "Calibration touch captured: ($cx, $cy) from event type ${event.eventType}")
                touchCaptureListener?.invoke(cx, cy)
                touchCaptureListener = null
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
        stopPerformance()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopPerformance()
    }

    /**
     * 设置琴键坐标配置
     */
    fun setCoordinates(coordinates: List<PianoKeyCoordinate>) {
        currentCoordinates = coordinates
        Log.d(TAG, "Coordinates set: ${coordinates.size} keys")
    }

    /**
     * 开始演奏
     */
    fun startPerformance(
        packageName: String,
        noteEvents: List<Pair<Long, Int>>
    ) {
        if (currentCoordinates.isEmpty()) {
            Log.e(TAG, "No coordinates set")
            return
        }

        targetPackageName = packageName
        isPlaying = true

        Log.d(TAG, "=== Starting Performance ===")
        Log.d(TAG, "Target: $packageName, Events: ${noteEvents.size}, Keys: ${currentCoordinates.size}")
        Log.d(TAG, GestureDispatcher.getDiagnostics(this))

        noteEvents.forEach { (timestamp, keyIndex) ->
            val delay = timestamp - System.currentTimeMillis()
            if (delay <= 0) {
                pressKey(keyIndex)
            } else {
                handler.postDelayed({
                    if (isPlaying) pressKey(keyIndex)
                }, delay)
            }
        }
    }

    /**
     * 开始演奏（带坐标）
     */
    fun startPerformanceWithCoordinates(
        packageName: String,
        noteEvents: List<Pair<Long, Int>>,
        coordinates: List<PianoKeyCoordinate>
    ) {
        currentCoordinates = coordinates
        startPerformance(packageName, noteEvents)
    }

    /**
     * 停止演奏
     */
    fun stopPerformance() {
        isPlaying = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Performance stopped")
    }

    /**
     * 按下指定索引的琴键（使用多层分发策略）
     */
    fun pressKey(keyIndex: Int) {
        if (keyIndex !in currentCoordinates.indices) return
        val coord = currentCoordinates[keyIndex]
        if (coord.centerX <= 0 || coord.centerY <= 0) return
        GestureDispatcher.dispatchTap(this, coord.centerX, coord.centerY, 30L)
    }

    /**
     * 测试点击（同步等待结果，用于诊断）
     */
    fun testTap(x: Int, y: Int): Pair<Boolean, String> {
        return try {
            val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 30)
            val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
            val latch = java.util.concurrent.CountDownLatch(1)
            var result = "unknown"
            var success = false
            val dispatched = this.dispatchGesture(gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(desc: android.accessibilityservice.GestureDescription?) {
                        success = true; result = "completed"; latch.countDown()
                    }
                    override fun onCancelled(desc: android.accessibilityservice.GestureDescription?) {
                        success = false; result = "cancelled"; latch.countDown()
                    }
                }, null)
            if (dispatched) {
                val timedOut = !latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (timedOut) { result = "timeout (2s)"; success = false }
            } else { result = "dispatchGesture returned false"; success = false }
            Pair(success, result)
        } catch (e: Exception) {
            Pair(false, "Exception: ${e.message}")
        }
    }

    /**
     * 自动识别当前应用的琴键
     */
    fun autoDetectKeys(keyCount: Int = 21): List<PianoKeyCoordinate>? {
        val root = rootInActiveWindow ?: return null
        val dm = resources.displayMetrics
        return coordinateManager.autoDetectPianoKeys(root, keyCount, dm.widthPixels, dm.heightPixels)
    }

    /**
     * 打开目标应用
     */
    fun openTargetApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.d(TAG, "Opening app: $packageName")
        } else {
            Log.e(TAG, "App not found: $packageName")
        }
    }

    fun setTouchCaptureListener(listener: (Int, Int) -> Unit) {
        touchCaptureListener = listener
    }

    fun clearTouchCaptureListener() {
        touchCaptureListener = null
    }

    // ===== 标定触发按钮（由无障碍服务直接创建，保证在所有应用之上） =====

    /**
     * 通过无障碍服务创建标定触发按钮（TYPE_ACCESSIBILITY_OVERLAY 最高优先级）
     */
    fun showCalibrationTrigger(
        layoutLabel: String,
        totalKeys: Int,
        onClick: () -> Unit
    ) {
        Log.d(TAG, "showCalibrationTrigger called: label=$layoutLabel keys=$totalKeys pkg=$targetPackageForOverlay displayId=$overlayDisplayId")
        hideCalibrationTrigger()

        calTriggerLayoutLabel = layoutLabel
        calTriggerTotalKeys = totalKeys
        calTriggerOnClick = onClick

        if (targetPackageForOverlay != null && overlayDisplayId <= 0) {
            try {
                val detected = findDisplayForPackage(targetPackageForOverlay!!)
                if (detected > 0) {
                    overlayDisplayId = detected
                    overlayWindowContext = null
                    Log.d(TAG, "Detected overlay display: $overlayDisplayId")
                } else {
                    Log.d(TAG, "Target app not found on any display (detected=$detected), using default")
                }
            } catch (e: Exception) {
                Log.w(TAG, "findDisplayForPackage failed in showCalibrationTrigger", e)
            }
        }

        try {
            val wm = getOverlayWindowManager()
            calTriggerWm = wm
            val dm = getOverlayDisplayMetrics()
            val density = dm.density
            Log.d(TAG, "Creating trigger button: displayMetrics=${dm.widthPixels}x${dm.heightPixels} density=$density")

        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 20f * density
            setColor(android.graphics.Color.parseColor("#F01A1A2E"))
            setStroke((2f * density).toInt(), android.graphics.Color.parseColor("#FF6C5CE7"))
        }

        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            background = bg
            setPadding((18 * density).toInt(), (14 * density).toInt(), (18 * density).toInt(), (14 * density).toInt())
            tag = "acc_cal_trigger"

            addView(android.widget.TextView(context).apply {
                text = "📍"; textSize = 28f; gravity = android.view.Gravity.CENTER
            })
            addView(android.widget.TextView(context).apply {
                text = "开始标定"; textSize = 15f
                setTextColor(android.graphics.Color.WHITE); gravity = android.view.Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(android.widget.TextView(context).apply {
                text = "$layoutLabel | ${totalKeys}键"; textSize = 11f
                setTextColor(0xFFA29BFE.toInt()); gravity = android.view.Gravity.CENTER
            })
        }

        val lp = android.view.WindowManager.LayoutParams().apply {
            type = android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            format = android.graphics.PixelFormat.TRANSLUCENT
            width = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            gravity = android.view.Gravity.CENTER
            x = 0
            y = 0
        }

        var isDrag = false
        var sx = 0f; var sy = 0f
        var wx = 0; var wy = 0
        card.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    sx = event.rawX; sy = event.rawY
                    wx = lp.x; wy = lp.y; isDrag = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - sx).toInt()
                    val dy = (event.rawY - sy).toInt()
                    if (!isDrag && (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12)) isDrag = true
                    if (isDrag) { lp.x = wx + dx; lp.y = wy + dy; wm.updateViewLayout(card, lp) }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!isDrag) {
                        hideCalibrationTrigger()
                        onClick()
                    }
                    isDrag = false
                    true
                }
                else -> false
            }
        }

        // 点击由 ACTION_UP 处理
        card.isClickable = true
        card.isFocusable = true

        calTriggerView = card
            wm.addView(card, lp)
            Log.d(TAG, "Calibration trigger shown via accessibility service (addView succeeded)")
        } catch (e: Exception) {
            Log.e(TAG, "FAILED to show calibration trigger", e)
            calTriggerView = null
            calTriggerWm = null
        }
    }

    fun hideCalibrationTrigger() {
        try {
            calTriggerView?.let { calTriggerWm?.removeView(it) }
        } catch (_: Exception) {}
        calTriggerView = null
        calTriggerWm = null
    }

    private var calTriggerView: android.view.View? = null

    // ===== 完整标定覆盖层（由无障碍服务创建） =====

    private var calOverlayView: android.view.View? = null
    private var calPanelView: android.view.View? = null

    private var overlayDisplayId: Int = -1
    private var overlayWindowContext: android.content.Context? = null
    private var targetPackageForOverlay: String? = null
    private var calTriggerLayoutLabel: String? = null
    private var calTriggerTotalKeys: Int = 0
    private var calTriggerOnClick: (() -> Unit)? = null
    private var calTriggerWm: android.view.WindowManager? = null
    private var calOverlayWm: android.view.WindowManager? = null

    /**
     * 显示标定覆盖层（标记+外框）- 在目标应用上层
     */
    fun showCalibrationOverlay(
        markers: List<FloatingCalibrationService.KeyMarker>,
        layoutLabel: String,
        onMarkersChanged: ((List<FloatingCalibrationService.KeyMarker>) -> Unit)? = null
    ) {
        Log.d(TAG, "showCalibrationOverlay called: ${markers.size} markers displayId=$overlayDisplayId")
        hideCalibrationOverlay()
        try {
            val wm = getOverlayWindowManager()
            calOverlayWm = wm
            val dm = getOverlayDisplayMetrics()

        val view = CalibrationOverlayView(this)
        view.setMarkers(markers)
        view.onMarkersChanged = onMarkersChanged

        val lp = android.view.WindowManager.LayoutParams().apply {
            type = android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            // 不设置 FLAG_NOT_FOCUSABLE，避免隐式开启 FLAG_NOT_TOUCH_MODAL 导致触摸穿透
            flags = android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = android.graphics.PixelFormat.TRANSLUCENT
            width = dm.widthPixels; height = dm.heightPixels
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }

        calOverlayView = view
            wm.addView(view, lp)
            Log.d(TAG, "Calibration overlay shown via accessibility service (addView succeeded)")
            bringCalibrationControlPanelToFront()
        } catch (e: Exception) {
            Log.e(TAG, "FAILED to show calibration overlay", e)
            calOverlayView = null
            calOverlayWm = null
        }
    }

    fun hideCalibrationOverlay() {
        try {
            calOverlayView?.let { calOverlayWm?.removeView(it) }
        } catch (_: Exception) {}
        calOverlayView = null
        calOverlayWm = null
    }


    private var calPanelWm: android.view.WindowManager? = null
    private var calPanelLp: android.view.WindowManager.LayoutParams? = null

    fun showCalibrationControlPanel(panelView: android.view.View) {
        hideCalibrationControlPanel()
        try {
            val wm = getOverlayWindowManager()
            calPanelWm = wm
            val dm = getOverlayDisplayMetrics()
            val density = dm.density

            val lp = android.view.WindowManager.LayoutParams().apply {
                type = android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                format = android.graphics.PixelFormat.TRANSLUCENT
                width = android.view.WindowManager.LayoutParams.WRAP_CONTENT
                height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                y = (120 * density).toInt()
            }
            calPanelLp = lp
            calPanelView = panelView
            wm.addView(panelView, lp)
            Log.d(TAG, "Calibration control panel shown via accessibility service (addView succeeded)")
        } catch (e: Exception) {
            Log.e(TAG, "FAILED to show calibration control panel", e)
            calPanelView = null
            calPanelWm = null
            calPanelLp = null
        }
    }

    fun updateCalibrationControlPanel() {
        try {
            calPanelView?.let { calPanelWm?.updateViewLayout(it, calPanelLp) }
        } catch (_: Exception) {}
    }

    fun bringCalibrationControlPanelToFront() {
        val view = calPanelView ?: return
        val wm = calPanelWm ?: return
        val lp = calPanelLp ?: return
        try {
            wm.removeView(view)
            wm.addView(view, lp)
        } catch (_: Exception) {}
    }

    fun getCalPanelLp(): android.view.WindowManager.LayoutParams? = calPanelLp

    fun hideCalibrationControlPanel() {
        try {
            calPanelView?.let { calPanelWm?.removeView(it) }
        } catch (_: Exception) {}
        calPanelView = null
        calPanelWm = null
        calPanelLp = null
    }

    // ===== 播放器悬浮窗（由无障碍服务创建） =====

    private var playerView: android.view.View? = null
    private var playerWm: android.view.WindowManager? = null
    private var playerLp: android.view.WindowManager.LayoutParams? = null

    fun showPlayerFloatingView(view: android.view.View, lp: android.view.WindowManager.LayoutParams) {
        hidePlayerFloatingView()
        try {
            val wm = getOverlayWindowManager()
            playerWm = wm
            lp.type = android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            playerLp = lp
            playerView = view
            wm.addView(view, lp)
            Log.d(TAG, "Player floating view shown via accessibility service")
        } catch (e: Exception) {
            Log.e(TAG, "FAILED to show player floating view", e)
            playerView = null
            playerWm = null
            playerLp = null
        }
    }

    fun updatePlayerFloatingView() {
        try {
            playerView?.let { playerWm?.updateViewLayout(it, playerLp) }
        } catch (_: Exception) {}
    }

    fun getPlayerLp(): android.view.WindowManager.LayoutParams? = playerLp

    fun hidePlayerFloatingView() {
        try {
            playerView?.let { playerWm?.removeView(it) }
        } catch (_: Exception) {}
        playerView = null
        playerWm = null
        playerLp = null
    }

    fun getOverlayDisplayId(): Int = overlayDisplayId

    fun setTargetPackageForOverlay(pkg: String) {
        targetPackageForOverlay = pkg
    }

    fun getOverlayDisplayMetrics(): android.util.DisplayMetrics {
        if (overlayDisplayId > 0) {
            try {
                val ctx = overlayWindowContext ?: createWindowContext(overlayDisplayId, null).also {
                    overlayWindowContext = it
                }
                return ctx.resources.displayMetrics
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get display metrics for display $overlayDisplayId", e)
            }
        }
        return resources.displayMetrics
    }

    private fun getOverlayWindowManager(): android.view.WindowManager {
        if (overlayDisplayId > 0) {
            try {
                val ctx = overlayWindowContext ?: createWindowContext(overlayDisplayId, null).also {
                    overlayWindowContext = it
                }
                return ctx.getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create WindowManager for display $overlayDisplayId", e)
            }
        }
        return getSystemService(WINDOW_SERVICE) as android.view.WindowManager
    }

    private fun findDisplayForPackage(pkg: String): Int {
        try {
            val windowList = windows
            for (window in windowList) {
                val root = window.root
                val windowPkg = root?.packageName?.toString()
                root?.recycle()
                if (windowPkg == pkg) {
                    val did = window.displayId
                    Log.d(TAG, "Found display $did for $pkg via getWindows() (total ${windowList.size} windows)")
                    return did
                }
            }
            Log.d(TAG, "Package $pkg not found in ${windowList.size} windows, listing all:")
            for (w in windowList) {
                val r = w.root
                Log.d(TAG, "  window: pkg=${r?.packageName} displayId=${w.displayId} type=${w.type}")
                r?.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "findDisplayForPackage failed", e)
        }
        return -1
    }
}
