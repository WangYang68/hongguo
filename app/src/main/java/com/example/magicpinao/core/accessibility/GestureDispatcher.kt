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

    data class TapPoint(val x: Int, val y: Int, val durationMs: Long = 30L)

    enum class Strategy { ACCESSIBILITY_GESTURE, NONE }

    var lastStrategy: Strategy = Strategy.NONE
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    var successCount = 0
        private set
    var failCount = 0
        private set

    fun dispatchTaps(
        service: AccessibilityService?,
        points: List<TapPoint>
    ) {
        if (service == null || points.isEmpty()) {
            Log.e(TAG, "dispatchTaps: service=$service, points=${points.size}")
            return
        }

        val maxDur = try {
            GestureDescription.getMaxGestureDuration()
        } catch (_: Throwable) {
            60_000L
        }
        // getMinStrokeDuration 不是公开 API，最小笔画时长固定为 1ms
        val minDur = 1L

        mainHandler.post {
            try {
                val builder = GestureDescription.Builder()
                for (p in points) {
                    val path = Path().apply { moveTo(p.x.toFloat(), p.y.toFloat()) }
                    val dur = p.durationMs.coerceIn(minDur, maxDur)
                    builder.addStroke(GestureDescription.StrokeDescription(path, 0, dur))
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
                            Log.e(TAG, "Gesture CANCELLED at ${points.map { "(${it.x},${it.y})" }}")
                        }
                    },
                    null
                )

                if (!dispatched) {
                    failCount++
                    Log.e(TAG, "dispatchGesture returned FALSE at ${points.map { "(${it.x},${it.y})" }}")
                }
            } catch (e: Exception) {
                failCount++
                Log.e(TAG, "dispatchGesture exception: ${e.message}", e)
            }
        }
    }

    fun dispatchTap(service: AccessibilityService?, x: Int, y: Int, durationMs: Long = 30L) {
        dispatchTaps(service, listOf(TapPoint(x, y, durationMs)))
    }

    fun getDiagnostics(service: AccessibilityService?): String {
        return "Gesture: svc=${service != null}, last=$lastStrategy, ok=$successCount, fail=$failCount"
    }
}
