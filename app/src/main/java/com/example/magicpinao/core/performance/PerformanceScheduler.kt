package com.example.magicpinao.core.performance

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.magicpinao.core.accessibility.GestureDispatcher
import com.example.magicpinao.core.accessibility.PianoAccessibilityService
import com.example.magicpinao.core.coordinate.CoordinateManager
import com.example.magicpinao.core.midi.NoteOnEvent
import com.example.magicpinao.core.midi.NoteSequence

/**
 * 高精度演奏调度器
 * 后台线程精确计时 + 和弦并行分发
 */
class PerformanceScheduler {

    interface PlaybackStateListener {
        fun onPlaybackStarted()
        fun onPlaybackPaused()
        fun onPlaybackResumed()
        fun onPlaybackStopped()
        fun onPlaybackFinished()  // 自然播放结束（非手动停止）
    }

    companion object {
        private const val TAG = "PerfScheduler"

        var instance: PerformanceScheduler? = null
            private set

        fun getOrCreate(): PerformanceScheduler {
            if (instance == null) {
                instance = PerformanceScheduler()
            }
            return instance!!
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("perf-worker").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    private var coordinateManager: CoordinateManager? = null
    private var currentPackageName: String = ""

    init {
        instance = this
    }

    var isPlaying: Boolean = false
        private set

    /** 是否处于暂停状态（区别于完全停止） */
    var isPaused: Boolean = false
        private set

    /** 播放速度倍率：1.0=正常，0.5=半速，2.0=双速 */
    var speed: Float = 1.0f
        private set

    /** false=长按(正常时值)，true=短按(缩短按键时长) */
    var shortPressMode: Boolean = false

    /** null=全部音轨；否则只演奏指定音轨 */
    var trackFilter: Int? = null

    private val stateListeners = mutableListOf<PlaybackStateListener>()

    fun addStateListener(listener: PlaybackStateListener) {
        if (!stateListeners.contains(listener)) {
            stateListeners.add(listener)
        }
    }

    fun removeStateListener(listener: PlaybackStateListener) {
        stateListeners.remove(listener)
    }

    fun removeAllStateListeners() {
        stateListeners.clear()
    }

    private fun notifyListeners(action: (PlaybackStateListener) -> Unit) {
        mainHandler.post {
            stateListeners.toList().forEach { action(it) }
        }
    }

    private var eventQueue: List<ScheduledEvent> = emptyList()
    private var eventIndex: Int = 0
    private var startTime: Long = 0L
    private var totalEvents: Int = 0

    // 统计
    private var successCount = 0
    private var failCount = 0

    fun setCoordinateManager(manager: CoordinateManager) {
        this.coordinateManager = manager
    }

    fun schedule(
        sequence: NoteSequence,
        packageName: String,
        baseNote: Int = 60,
        keyCount: Int = 21,
        whiteKeysOnly: Boolean = false,
        layoutType: com.example.magicpinao.core.calibration.PianoLayoutType = com.example.magicpinao.core.calibration.PianoLayoutType.LAYOUT_21
    ) {
        val coordMgr = coordinateManager ?: run {
            Log.e(TAG, "CoordinateManager not set")
            return
        }
        if (isPlaying || isPaused) stop()
        currentPackageName = packageName

        val keyChanges = sequence.events.filterIsInstance<com.example.magicpinao.core.midi.KeySignatureEvent>()
            .sortedBy { it.timestamp }
        var keyIdx = 0
        var keyOffset = 0

        val events = sequence.events
            .filterIsInstance<NoteOnEvent>()
            .filter { trackFilter == null || it.track == trackFilter }
            .mapNotNull { e ->
                while (keyIdx < keyChanges.size && keyChanges[keyIdx].timestamp <= e.timestamp) {
                    keyOffset = keyChanges[keyIdx].offset
                    keyIdx++
                }
                coordMgr.mapMidiNoteToKeyIndex(
                    e.pitch, baseNote, keyCount, whiteKeysOnly, layoutType, keyOffset
                )?.let {
                    ScheduledEvent(
                        originalTimestamp = e.timestamp,
                        scaledTimestamp = (e.timestamp / speed).toLong(),
                        keyIndex = it,
                        velocity = e.velocity,
                        originalDurationMs = e.durationMs
                    )
                }
            }
            .sortedBy { it.scaledTimestamp }

        eventQueue = events
        eventIndex = 0
        totalEvents = events.size
        successCount = 0
        failCount = 0

        val keyIndexDist = events.map { it.keyIndex }.groupBy { it / 7 }.mapValues { it.value.size }
        Log.i(TAG, "Scheduled: $totalEvents notes, range=${events.firstOrNull()?.scaledTimestamp}-${events.lastOrNull()?.scaledTimestamp}ms")
        Log.i(TAG, "Key distribution: low(0-6)=${keyIndexDist[0] ?: 0}, mid(7-13)=${keyIndexDist[1] ?: 0}, high(14-20)=${keyIndexDist[2] ?: 0}")
        Log.i(TAG, "First 20 keys: ${events.take(20).joinToString { "${it.keyIndex}@${it.scaledTimestamp}ms" }}")
    }

    fun start() {
        if (eventQueue.isEmpty()) { Log.w(TAG, "No events"); return }

        val service = PianoAccessibilityService.instance
        if (service == null) { Log.e(TAG, "No AccessibilityService"); return }

        val coords = coordinateManager?.getCurrentCoordinates()
        if (coords.isNullOrEmpty()) { Log.e(TAG, "No coordinates"); return }

        isPlaying = true
        isPaused = false
        startTime = SystemClock.uptimeMillis()
        eventIndex = 0
        successCount = 0
        failCount = 0

        Log.d(TAG, "=== START: $totalEvents notes, ${coords.size} keys ===")
        notifyListeners { it.onPlaybackStarted() }
        workerHandler.post { scheduleNext() }
    }

    fun pause() {
        if (!isPlaying) return
        isPlaying = false
        isPaused = true
        workerHandler.removeCallbacksAndMessages(null)
        notifyListeners { it.onPlaybackPaused() }
        Log.d(TAG, "Paused @ $eventIndex/$totalEvents")
    }

    fun resume() {
        if (isPlaying || !isPaused || eventQueue.isEmpty()) return
        if (PianoAccessibilityService.instance == null) return
        isPlaying = true
        isPaused = false
        val resumePos = eventQueue[eventIndex.coerceIn(0, totalEvents - 1)].scaledTimestamp
        startTime = SystemClock.uptimeMillis() - resumePos
        notifyListeners { it.onPlaybackResumed() }
        workerHandler.post { scheduleNext() }
        Log.d(TAG, "Resumed @ $eventIndex")
    }

    fun stop() {
        isPlaying = false
        isPaused = false
        workerHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        notifyListeners { it.onPlaybackStopped() }
        Log.d(TAG, "Stopped: $successCount ok, $failCount fail out of $totalEvents")
        eventQueue = emptyList()
        eventIndex = 0
    }

    fun setSpeed(newSpeed: Float) {
        speed = newSpeed.coerceIn(0.25f, 4.0f)
    }

    /**
     * 跳转到指定位置（毫秒）
     * @param positionMs 目标位置（缩放后的时间戳）
     */
    fun seekTo(positionMs: Long) {
        if (eventQueue.isEmpty()) return
        val wasPlaying = isPlaying

        // 暂停当前调度
        workerHandler.removeCallbacksAndMessages(null)

        // 找到目标位置之后的第一个事件
        eventIndex = eventQueue.indexOfFirst { it.scaledTimestamp >= positionMs }
            .let { if (it < 0) eventQueue.size else it }

        // 重设 startTime
        val currentScaled = if (eventIndex < eventQueue.size) eventQueue[eventIndex].scaledTimestamp else 0L
        startTime = SystemClock.uptimeMillis() - currentScaled - 50 // 小偏移确保立即触发

        Log.i(TAG, "Seek to ${positionMs}ms → idx=$eventIndex/$totalEvents")

        // 恢复播放
        if (wasPlaying) {
            isPlaying = true
            isPaused = false
            workerHandler.post { scheduleNext() }
        } else {
            isPaused = false
        }
    }

    fun getCurrentPosition(): Long = eventQueue.getOrNull(eventIndex)?.scaledTimestamp ?: 0L
    fun getDuration(): Long {
        val last = eventQueue.lastOrNull() ?: return 0L
        return last.scaledTimestamp + last.scaledDurationMs(speed)
    }

    // ==================== private ====================

    /**
     * 动态调整播放速度（无需停止从头开始）
     */
    fun changeSpeed(newSpeed: Float) {
        if (newSpeed == speed) return
        if (eventQueue.isEmpty()) {
            speed = newSpeed.coerceIn(0.25f, 4.0f)
            return
        }

        val oldSpeed = speed
        speed = newSpeed.coerceIn(0.25f, 4.0f)
        val wasPlaying = isPlaying

        // 暂停当前调度
        workerHandler.removeCallbacksAndMessages(null)

        // 计算已播放的原始MIDI时长
        val realElapsed = SystemClock.uptimeMillis() - startTime
        val originalPlayedMs = realElapsed * oldSpeed

        // 重新缩放所有时间戳
        eventQueue = eventQueue.map { evt ->
            val originalTs = evt.originalTimestamp
            ScheduledEvent(
                originalTs,
                (originalTs / speed).toLong(),
                evt.keyIndex,
                evt.velocity,
                evt.originalDurationMs
            )
        }.sortedBy { it.scaledTimestamp }

        // 定位当前位置
        val newScaledElapsed = (originalPlayedMs / speed).toLong()
        eventIndex = eventQueue.indexOfFirst { it.scaledTimestamp > newScaledElapsed }
            .let { if (it < 0) eventQueue.size else it }
        totalEvents = eventQueue.size

        // 重设 startTime 以匹配当前位置
        val currentScaled = if (eventIndex < eventQueue.size) eventQueue[eventIndex].scaledTimestamp else 0L
        startTime = SystemClock.uptimeMillis() - currentScaled

        Log.i(TAG, "Speed: ${oldSpeed}x → ${speed}x, pos=$eventIndex/$totalEvents, played=${originalPlayedMs.toLong()}ms")

        // 如果之前在播放，恢复
        if (wasPlaying) {
            isPlaying = true
            isPaused = false
            workerHandler.post { scheduleNext() }
        }
    }

    private fun scheduleNext() {
        if (!isPlaying) return

        while (eventIndex < eventQueue.size) {
            val elapsed = SystemClock.uptimeMillis() - startTime
            val targetTs = eventQueue[eventIndex].scaledTimestamp
            val delay = targetTs - elapsed

            if (delay > 3) {
                workerHandler.postDelayed({ scheduleNext() }, delay.coerceIn(1, 500))
                return
            }

            // 收集同一时间戳的所有音符（和弦）
            val batchTs = targetTs
            val batch = mutableListOf<ScheduledEvent>()
            while (eventIndex < eventQueue.size && eventQueue[eventIndex].scaledTimestamp == batchTs) {
                batch.add(eventQueue[eventIndex])
                eventIndex++
            }

            // 分发音符：按时值点击（受下一音间隔限制，避免手势互相取消）
            val service = PianoAccessibilityService.instance
            val coords = coordinateManager?.getCurrentCoordinates()
            val nextTs = eventQueue.getOrNull(eventIndex)?.scaledTimestamp
            val gap = if (nextTs != null) (nextTs - batchTs) else Long.MAX_VALUE

            if (service != null && coords != null) {
                val validTaps = batch.mapNotNull { evt ->
                    if (evt.keyIndex in coords.indices) {
                        val c = coords[evt.keyIndex]
                        val midiDur = evt.scaledDurationMs(speed)
                        val tapDur = if (shortPressMode) {
                            35L
                        } else {
                            minOf(midiDur, (gap - 8L).coerceAtLeast(20L)).coerceIn(20L, 320L)
                        }
                        GestureDispatcher.TapPoint(c.centerX, c.centerY, tapDur)
                    } else {
                        failCount++
                        Log.w(TAG, "Key idx=${evt.keyIndex} out of range")
                        null
                    }
                }
                if (validTaps.isNotEmpty()) {
                    GestureDispatcher.dispatchTaps(service, validTaps)
                    successCount += validTaps.size
                    if (validTaps.size > 1) {
                        Log.d(TAG, "Chord: ${validTaps.size} notes @ ${batchTs}ms")
                    }
                }
            } else {
                failCount += batch.size
                if (service == null) Log.e(TAG, "Service lost during playback!")
            }
        }

        // 播放完毕
        isPlaying = false
        isPaused = false
        val totalElapsed = SystemClock.uptimeMillis() - startTime
        val diag = GestureDispatcher.getDiagnostics(PianoAccessibilityService.instance)
        Log.d(TAG, "=== FINISHED: $successCount/$totalEvents notes OK, ${failCount} fail, ${totalElapsed}ms, gesture: $diag ===")
        mainHandler.post {
            PianoAccessibilityService.isPlaying = false
            notifyListeners { it.onPlaybackFinished() }
        }
    }

    private data class ScheduledEvent(
        val originalTimestamp: Long,
        val scaledTimestamp: Long,
        val keyIndex: Int,
        val velocity: Int,
        val originalDurationMs: Long
    ) {
        fun scaledDurationMs(playbackSpeed: Float): Long =
            (originalDurationMs / playbackSpeed).toLong().coerceAtLeast(20L)
    }
}
