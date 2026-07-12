package com.example.magicpinao.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 手势分发器
 * 关键：dispatchGesture 必须在主线程调用，因此所有手势分发都通过 mainHandler 投递
 * 和弦：多个触摸点合并到一个 GestureDescription 中，一次 dispatchGesture 完成
 */
object GestureDispatcher {

    private const val TAG = "GestureDispatcher"

    enum class Strategy { ACCESSIBILITY_GESTURE, NONE }

    var lastStrategy: Strategy = Strategy.NONE
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 统计数据 */
    var successCount = 0
        private set
    var failCount = 0
        private set

    /**
     * 分发触摸（单点或多点），所有操作在主线程执行
     */
    fun dispatchTaps(
        service: AccessibilityService?,
        points: List<Pair<Int, Int>>,
        durationMs: Long = 15L
    ) {
        if (service == null || points.isEmpty()) {
            Log.e(TAG, "dispatchTaps: service=$service, points=${points.size}")
            return
        }

        // 必须在主线程调用 dispatchGesture
        mainHandler.post {
            try {
                val builder = GestureDescription.Builder()
                for ((x, y) in points) {
                    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                    builder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                }

                val dispatched = service.dispatchGesture(
                    builder.build(),
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            successCount++
                            lastStrategy = Strategy.ACCESSIBILITY_GESTURE
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            failCount++
                            Log.e(TAG, "Gesture CANCELLED at ${points.map { "(${it.first},${it.second})" }}")
                        }
                    },
                    null // callback on main thread
                )

                if (!dispatched) {
                    failCount++
                    Log.e(TAG, "dispatchGesture returned FALSE at ${points.map { "(${it.first},${it.second})" }}")
                }
            } catch (e: Exception) {
                failCount++
                Log.e(TAG, "dispatchGesture exception: ${e.message}", e)
            }
        }
    }

    /** 单点触摸（便捷方法） */
    fun dispatchTap(service: AccessibilityService?, x: Int, y: Int, durationMs: Long = 30L) {
        dispatchTaps(service, listOf(Pair(x, y)), durationMs)
    }

    fun getDiagnostics(service: AccessibilityService?): String {
        return "Gesture: svc=${service != null}, last=$lastStrategy, ok=$successCount, fail=$failCount"
    }
}
