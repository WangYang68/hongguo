package com.example.magicpinao.core.coordinate

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.magicpinao.core.data.PianoKeyCoordinate

/**
 * 坐标管理器
 * 负责琴键坐标的自动识别、标定和管理
 */
class CoordinateManager {
    
    /**
     * 标定模式
     */
    enum class CalibrationMode {
        AUTO_DETECT,      // 自动识别
        BOUNDARY,         // 边界标定
        REGION_SCAN,      // 区域扫描
        MANUAL            // 手动标定
    }
    
    /**
     * 尝试自动识别琴键
     * 
     * @param rootNode 无障碍服务的根节点
     * @param keyCount 琴键数量
     * @return 识别到的琴键坐标列表，如果识别失败返回 null
     */
    fun autoDetectPianoKeys(
        rootNode: AccessibilityNodeInfo?,
        keyCount: Int = 21,
        screenWidth: Int,
        screenHeight: Int
    ): List<PianoKeyCoordinate>? {
        if (rootNode == null) return null
        
        val keys = mutableListOf<PianoKeyCoordinate>()
        
        // 遍历所有可点击节点
        findClickableNodes(rootNode) { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            
            // 判断是否是琴键
            if (isValidPianoKey(rect, node, screenHeight)) {
                keys.add(PianoKeyCoordinate(
                    keyIndex = keys.size,
                    noteNumber = 0, // 后续根据索引计算
                    centerX = rect.centerX(),
                    centerY = rect.centerY(),
                    width = rect.width(),
                    height = rect.height()
                ))
            }
        }
        
        // 按 X 坐标排序（从左到右）
        keys.sortBy { it.centerX }
        
        // 验证数量是否正确
        return if (keys.size == keyCount) {
            // 重新分配索引和音符编号
            keys.mapIndexed { index, key ->
                key.copy(
                    keyIndex = index,
                    noteNumber = 60 + index  // C4 + index
                )
            }
        } else {
            null  // 识别失败
        }
    }
    
    /**
     * 边界标定法（支持多排琴键）
     *
     * 多排琴键（如原神风物琴 3排×7键）：
     *   高音排 C5-B5 → 屏幕上方（Y小）
     *   中音排 C4-B4 → 屏幕中间
     *   低音排 C3-B3 → 屏幕下方（Y大）
     *
     * 标定时左边界通常是高音C5（左上角），右边界是低音B3（右下角）
     * 通过 Y 坐标确定上下关系（Y小=上/高音，Y大=下/低音）
     */
    fun calculateByBoundary(
        leftPoint: Pair<Int, Int>,
        rightPoint: Pair<Int, Int>,
        keyCount: Int = 21,
        keyHeight: Int = 150,
        rowCount: Int = 1
    ): List<PianoKeyCoordinate> {
        val (leftX, leftY) = leftPoint
        val (rightX, rightY) = rightPoint

        val keysPerRow = if (rowCount > 1) keyCount / rowCount else keyCount
        val totalWidth = rightX - leftX
        val keyWidth = totalWidth / (keysPerRow - 1)

        // 根据 Y 坐标判断：Y小=屏幕上方=高音排，Y大=屏幕下方=低音排
        val topY = minOf(leftY, rightY)     // 高音排 (keys 14-20)
        val bottomY = maxOf(leftY, rightY)   // 低音排 (keys 0-6)
        val rowHeight = if (rowCount > 1) (bottomY - topY).toDouble() / (rowCount - 1) else 0.0

        return (0 until keyCount).map { index ->
            val row = if (rowCount > 1) index / keysPerRow else 0
            val col = index % keysPerRow
            val centerX = leftX + col * keyWidth
            val centerY = if (rowCount > 1) {
                // row 0=低音排(bottomY), row 1=中音排, row 2=高音排(topY)
                (bottomY - row * rowHeight).toInt()
            } else {
                (leftY + rightY) / 2
            }

            PianoKeyCoordinate(
                keyIndex = index,
                noteNumber = 60 + index,
                centerX = centerX,
                centerY = centerY,
                width = keyWidth,
                height = keyHeight
            )
        }
    }
    
    /**
     * 区域扫描法
     * 用户画一个框圈住所有琴键，系统自动等分
     * 
     * @param left 矩形左边
     * @param top 矩形上边
     * @param right 矩形右边
     * @param bottom 矩形下边
     * @param keyCount 琴键数量
     */
    fun calculateByRegion(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        keyCount: Int = 21
    ): List<PianoKeyCoordinate> {
        val totalWidth = right - left
        val keyWidth = totalWidth / keyCount
        val keyHeight = bottom - top
        val centerY = (top + bottom) / 2
        
        return (0 until keyCount).map { index ->
            PianoKeyCoordinate(
                keyIndex = index,
                noteNumber = 60 + index,
                centerX = left + index * keyWidth + keyWidth / 2,
                centerY = centerY,
                width = keyWidth,
                height = keyHeight
            )
        }
    }
    
    // 白键在八度内的半音偏移：C=0, D=2, E=4, F=5, G=7, A=9, B=11
    private val whiteKeyOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    // 白键偏移 → 白键序号 (0-6)
    private val whiteKeyIndexMap = mapOf(0 to 0, 2 to 1, 4 to 2, 5 to 3, 7 to 4, 9 to 5, 11 to 6)
    // 黑键映射到下方白键：C#→C, D#→D, F#→F, G#→G, A#→A
    private val blackToWhite = mapOf(1 to 0, 3 to 2, 6 to 5, 8 to 7, 10 to 9)

    /**
     * MIDI音符映射到琴键索引（使用 Transposer 引擎）
     */
    fun mapMidiNoteToKeyIndex(
        midiNote: Int,
        baseNote: Int = 60,
        keyCount: Int = 21,
        whiteKeysOnly: Boolean = false,
        layoutType: com.example.magicpinao.core.calibration.PianoLayoutType = com.example.magicpinao.core.calibration.PianoLayoutType.LAYOUT_21
    ): Int? {
        val layout = com.example.magicpinao.core.calibration.PianoLayouts.getLayout(layoutType)
        return com.example.magicpinao.core.calibration.Transposer.mapNoteToKey(midiNote, layout)
    }
    
    /**
     * 琴键索引映射到 MIDI 音符
     */
    fun mapKeyIndexToMidiNote(
        keyIndex: Int,
        baseNote: Int = 60
    ): Int {
        return baseNote + keyIndex
    }
    
    // ==================== 坐标存储 ====================
    
    // 存储当前应用的琴键坐标
    private var currentCoordinates: List<PianoKeyCoordinate> = emptyList()
    
    /**
     * 设置琴键坐标（由 PerformanceScheduler 调用）
     */
    fun setCoordinates(coordinates: List<PianoKeyCoordinate>) {
        currentCoordinates = coordinates
    }
    
    /**
     * 设置琴键坐标（从仓库加载）
     */
    fun setCoordinatesFromJson(coordinatesJson: String) {
        val type = com.google.gson.reflect.TypeToken.getParameterized(
            List::class.java,
            PianoKeyCoordinate::class.java
        ).type
        currentCoordinates = com.google.gson.Gson().fromJson(coordinatesJson, type) as List<PianoKeyCoordinate>
    }
    
    /**
     * 获取当前设置的琴键坐标
     */
    fun getCurrentCoordinates(): List<PianoKeyCoordinate> = currentCoordinates
    
    // ==================== 私有方法 ====================
    
    /**
     * 递归查找所有可点击节点
     */
    private fun findClickableNodes(
        node: AccessibilityNodeInfo,
        action: (AccessibilityNodeInfo) -> Unit
    ) {
        if (node.isClickable) {
            action(node)
        }
        
        // 递归子节点
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findClickableNodes(child, action)
            }
        }
    }
    
    /**
     * 判断是否是有效的琴键
     */
    private fun isValidPianoKey(
        rect: Rect,
        node: AccessibilityNodeInfo,
        screenHeight: Int
    ): Boolean {
        // 琴键特征：
        // 1. 可点击
        // 2. 宽度在合理范围内（50-200px）
        // 3. 宽高比合理（0.3-0.8）
        // 4. 在屏幕下半部分（钢琴通常在下方）
        
        if (!node.isClickable) return false
        
        val aspectRatio = rect.width().toFloat() / rect.height()
        
        return rect.width() in 50..200 &&
               aspectRatio in 0.3f..0.8f &&
               rect.top > screenHeight / 2
    }
}
