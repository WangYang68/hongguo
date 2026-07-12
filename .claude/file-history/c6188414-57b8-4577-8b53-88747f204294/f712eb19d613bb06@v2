package com.example.magicpinao.core.calibration

import android.util.Log

/**
 * MIDI转调引擎
 *
 * 负责将MIDI音符映射到目标钢琴的物理按键上：
 * 1. 非半音乐器（21键/15键）→ 自动转C大调
 * 2. 半音音符 → 取左侧白键
 * 3. 超出音域 → 八度移位适配
 */
object Transposer {

    private const val TAG = "Transposer"

    // C大调音阶半音偏移：C=0, D=2, E=4, F=5, G=7, A=9, B=11
    private val cMajorSemitones = setOf(0, 2, 4, 5, 7, 9, 11)

    // 黑键→下方白键映射 (半音→白键半音)
    private val blackToWhite = mapOf(
        1 to 0,   // C# → C
        3 to 2,   // D# → D
        6 to 5,   // F# → F
        8 to 7,   // G# → G
        10 to 9   // A# → A
    )

    // 白键半音→C大调白键序号 (0-6)
    private val whiteToIndex = mapOf(
        0 to 0,   // C
        2 to 1,   // D
        4 to 2,   // E
        5 to 3,   // F
        7 to 4,   // G
        9 to 5,   // A
        11 to 6   // B
    )

    /**
     * 将MIDI音符映射到钢琴按键索引
     *
     * @param midiNote 原始MIDI音符编号 (0-127, C4=60)
     * @param layout 目标钢琴布局
     * @return 映射后的按键索引 (0-based)，如果无法映射返回null
     */
    fun mapNoteToKey(midiNote: Int, layout: PianoLayout): Int? {
        val keys = layout.getOrderedKeys()
        if (keys.isEmpty()) return null

        // 如果有黑键(36键)，直接匹配
        if (layout.hasBlackKeys) {
            return mapToChromaticKeys(midiNote, keys)
        }

        // 无黑键(21键/15键) → 转C大调
        return mapToDiatonicKeys(midiNote, layout, keys)
    }

    /**
     * 半音乐器映射（36键）：直接音符匹配+八度适配
     */
    private fun mapToChromaticKeys(midiNote: Int, keys: List<KeyPosition>): Int? {
        val minNote = keys.minOf { it.midiNote }
        val maxNote = keys.maxOf { it.midiNote }
        val range = maxNote - minNote + 1

        var note = midiNote
        // 八度适配
        while (note < minNote) note += 12
        while (note > maxNote) note -= 12

        val idx = keys.indexOfFirst { it.midiNote == note }
        return if (idx >= 0) idx else {
            // 如果精确匹配失败，尝试就近匹配
            keys.indexOfFirst { it.midiNote % 12 == note % 12 }
                .takeIf { it >= 0 }
        }
    }

    /**
     * 全音键映射（21键/15键）：转C大调+半音处理+八度适配
     */
    private fun mapToDiatonicKeys(
        midiNote: Int,
        layout: PianoLayout,
        keys: List<KeyPosition>
    ): Int? {
        // Step 1: 半音→下方白键
        val semitone = midiNote % 12
        val whiteSemitone = blackToWhite[semitone] ?: semitone

        // Step 2: 获取白键在八度内的序号(0-6)
        val whiteIdx = whiteToIndex[whiteSemitone] ?: return null

        // Step 3: 确定目标八度
        val originalOctave = midiNote / 12
        val whiteOctaveNote = originalOctave * 12 + whiteSemitone

        // 根据布局确定音域范围
        val minMidi = keys.minOf { it.midiNote }
        val maxMidi = keys.maxOf { it.midiNote }
        val minOctave = minMidi / 12
        val maxOctave = maxMidi / 12

        var targetOctave = originalOctave
        // 低音超出 → 升高八度
        while (targetOctave < minOctave) targetOctave++
        // 高音超出 → 降低八度
        while (targetOctave > maxOctave) targetOctave--

        val targetNote = targetOctave * 12 + whiteSemitone

        // Step 4: 在布局中查找匹配的键
        // 先尝试精确匹配
        val exactIdx = keys.indexOfFirst { it.midiNote == targetNote }
        if (exactIdx >= 0) return exactIdx

        // 如果不匹配，尝试在同八度查找
        val sameOctaveKeys = keys.filter { it.midiNote / 12 == targetOctave }
        val closest = sameOctaveKeys.minByOrNull {
            kotlin.math.abs(it.midiNote % 12 - whiteSemitone)
        }
        if (closest != null) return keys.indexOf(closest)

        // 最后的fallback：按行和列推断
        return inferKeyByPosition(targetNote, layout, keys)
    }

    /**
     * 根据音符推断按键位置（fallback）
     */
    private fun inferKeyByPosition(
        midiNote: Int,
        layout: PianoLayout,
        keys: List<KeyPosition>
    ): Int? {
        val octave = midiNote / 12
        val semitone = midiNote % 12

        // 根据八度决定行
        val row = when (layout.type) {
            PianoLayoutType.LAYOUT_15 -> {
                // 光遇15键的映射逻辑不同
                when {
                    midiNote in 48..55 -> 2  // C3-G3 → 上排
                    midiNote in 57..64 -> 1  // A3-E4 → 中排
                    midiNote in 65..72 -> 0  // F4-C5 → 下排
                    midiNote < 48 -> 2       // 低于C3 → 上排
                    else -> 0                 // 高于C5 → 下排
                }
            }
            else -> {
                when (octave) {
                    in 0..3 -> 0   // 低八度 → 下排
                    4 -> 1          // 中八度 → 中排
                    else -> 2       // 高八度 → 上排
                }
            }
        }

        val rowKeys = layout.getRowKeys(row).sortedBy { it.col }
        if (rowKeys.isEmpty()) return null

        // 在白键列表中找最接近的列
        val whiteIdx = whiteToIndex[blackToWhite[semitone] ?: semitone] ?: 0
        val col = whiteIdx.coerceIn(0, rowKeys.size - 1)

        return keys.indexOf(rowKeys[col])
    }

    /**
     * 批量映射音符序列
     */
    fun mapSequence(
        notes: List<Int>,
        layout: PianoLayout
    ): List<Int> {
        return notes.mapNotNull { note ->
            mapNoteToKey(note, layout)
        }
    }
}
