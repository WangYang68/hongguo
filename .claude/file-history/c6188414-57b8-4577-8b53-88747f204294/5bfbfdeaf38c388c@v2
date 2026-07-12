package com.example.magicpinao.core.calibration

/**
 * 钢琴键盘布局定义
 *
 * 支持三种布局：
 * - LAYOUT_21: 通用21键（3排×7白键，无黑键），C3-B5
 * - LAYOUT_36: 通用36键（3排×12半音），C3-B5
 * - LAYOUT_15: 光遇15键（3排×5键，无黑键），C3-C5
 */
enum class PianoLayoutType(val label: String) {
    LAYOUT_21("通用21键"),
    LAYOUT_36("通用36键"),
    LAYOUT_15("光遇15键")
}

/**
 * 单个琴键的布局定义
 */
data class KeyPosition(
    val row: Int,           // 0=下排(低音), 1=中排(中音), 2=上排(高音)
    val col: Int,           // 该排中的列号
    val isBlack: Boolean,   // 是否是黑键
    val midiNote: Int,      // 该键对应的MIDI音符编号(C4=60)
    val label: String       // 显示标签，如 "C4", "C#4"
)

/**
 * 钢琴布局定义
 */
data class PianoLayout(
    val type: PianoLayoutType,
    val keyCount: Int,
    val rows: Int,
    val hasBlackKeys: Boolean,
    val keys: List<KeyPosition>,
    /** 每排的说明（用于UI显示） */
    val rowLabels: List<String>
) {
    /** 获取某排的按键 */
    fun getRowKeys(row: Int): List<KeyPosition> = keys.filter { it.row == row }

    /** 获取有序的所有按键（按行排列） */
    fun getOrderedKeys(): List<KeyPosition> {
        return (0 until rows).flatMap { row ->
            getRowKeys(row).sortedBy { it.col }
        }
    }
}

/**
 * 预定义的钢琴布局
 */
object PianoLayouts {

    /**
     * 通用21键布局（原神风物琴等）
     * 下排: C3-B3 (7白键)
     * 中排: C4-B4 (7白键)
     * 上排: C5-B5 (7白键)
     * 无黑键，仅白键
     */
    val LAYOUT_21 = PianoLayout(
        type = PianoLayoutType.LAYOUT_21,
        keyCount = 21,
        rows = 3,
        hasBlackKeys = false,
        rowLabels = listOf("下排(低音) C3-B3", "中排(中音) C4-B4", "上排(高音) C5-B5"),
        keys = buildList {
            val whiteLabels = listOf("C", "D", "E", "F", "G", "A", "B")
            for (row in 0..2) {
                val octave = row + 3  // row0→C3, row1→C4, row2→C5
                whiteLabels.forEachIndexed { col, label ->
                    val midiNote = octave * 12 + when (label) {
                        "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5; "G" -> 7; "A" -> 9; "B" -> 11
                        else -> 0
                    }
                    add(KeyPosition(row, col, false, midiNote, "$label$octave"))
                }
            }
        }
    )

    /**
     * 通用36键布局（标准钢琴布局）
     * 下排: C3-B3 (7白+5黑=12键)
     * 中排: C4-B4 (12键)
     * 上排: C5-B5 (12键)
     */
    val LAYOUT_36 = PianoLayout(
        type = PianoLayoutType.LAYOUT_36,
        keyCount = 36,
        rows = 3,
        hasBlackKeys = true,
        rowLabels = listOf("下排(低音) C3-B3", "中排(中音) C4-B4", "上排(高音) C5-B5"),
        keys = buildList {
            val semitoneLabels = listOf(
                "C" to false, "C#" to true, "D" to false, "D#" to true,
                "E" to false, "F" to false, "F#" to true, "G" to false,
                "G#" to true, "A" to false, "A#" to true, "B" to false
            )
            for (row in 0..2) {
                val octave = row + 3
                semitoneLabels.forEachIndexed { col, (label, isBlack) ->
                    val midiNote = octave * 12 + col
                    add(KeyPosition(row, col, isBlack, midiNote, "$label$octave"))
                }
            }
        }
    )

    /**
     * 光遇15键布局
     * 上排: C3-G3 (5键)
     * 中排: A3-E4 (5键)
     * 下排: F4-C5 (5键)
     * 无黑键
     */
    val LAYOUT_15 = PianoLayout(
        type = PianoLayoutType.LAYOUT_15,
        keyCount = 15,
        rows = 3,
        hasBlackKeys = false,
        rowLabels = listOf("下排 F4-C5", "中排 A3-E4", "上排 C3-G3"),
        keys = buildList {
            // 上排: C3, D3, E3, F3, G3
            val topNotes = listOf(
                48 to "C3", 50 to "D3", 52 to "E3", 53 to "F3", 55 to "G3"
            )
            topNotes.forEachIndexed { col, (midi, label) ->
                add(KeyPosition(2, col, false, midi, label))
            }
            // 中排: A3, B3, C4, D4, E4
            val midNotes = listOf(
                57 to "A3", 59 to "B3", 60 to "C4", 62 to "D4", 64 to "E4"
            )
            midNotes.forEachIndexed { col, (midi, label) ->
                add(KeyPosition(1, col, false, midi, label))
            }
            // 下排: F4, G4, A4, B4, C5
            val botNotes = listOf(
                65 to "F4", 67 to "G4", 69 to "A4", 71 to "B4", 72 to "C5"
            )
            botNotes.forEachIndexed { col, (midi, label) ->
                add(KeyPosition(0, col, false, midi, label))
            }
        }
    )

    /** 获取指定类型布局 */
    fun getLayout(type: PianoLayoutType): PianoLayout = when (type) {
        PianoLayoutType.LAYOUT_21 -> LAYOUT_21
        PianoLayoutType.LAYOUT_36 -> LAYOUT_36
        PianoLayoutType.LAYOUT_15 -> LAYOUT_15
    }

    /** 根据键数推断布局类型 */
    fun inferLayout(keyCount: Int, appName: String): PianoLayout {
        return when {
            appName.contains("光遇", ignoreCase = true) || keyCount == 15 -> LAYOUT_15
            keyCount >= 36 -> LAYOUT_36
            else -> LAYOUT_21
        }
    }
}
