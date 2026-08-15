package com.example.magicpinao.core.calibration

/**
 * 钢琴键盘布局定义
 *
 * - LAYOUT_21: 3排×7白键，C3-B5
 * - LAYOUT_15: 光遇等同款 3排×5白键
 * - LAYOUT_36: 横排36键，3排×12半音（图2）
 * - LAYOUT_36_PIANO: 钢琴式36键，每排7白+5黑（图1）
 */
enum class PianoLayoutType(val label: String, val keyCount: Int) {
    LAYOUT_21("21键", 21),
    LAYOUT_15("15键", 15),
    LAYOUT_36("36键·横排", 36),
    LAYOUT_36_PIANO("36键·钢琴式", 36);

    companion object {
        fun fromName(name: String?): PianoLayoutType {
            return try {
                valueOf(name ?: "")
            } catch (_: Exception) {
                LAYOUT_21
            }
        }
    }
}

/**
 * 单个琴键的布局定义
 */
data class KeyPosition(
    val row: Int,           // 0=下排(低音), 1=中排(中音), 2=上排(高音)
    val col: Int,           // 逻辑列（保存/映射顺序）
    val isBlack: Boolean,   // 是否是黑键
    val midiNote: Int,      // MIDI 音符编号 (C4=60)
    val label: String,      // 标定显示标签
    val visualCol: Float = col.toFloat()
)

data class PianoLayout(
    val type: PianoLayoutType,
    val keyCount: Int,
    val rows: Int,
    val hasBlackKeys: Boolean,
    val keys: List<KeyPosition>,
    val rowLabels: List<String>
) {
    fun getRowKeys(row: Int): List<KeyPosition> = keys.filter { it.row == row }

    fun getOrderedKeys(): List<KeyPosition> {
        return (0 until rows).flatMap { row ->
            getRowKeys(row).sortedBy { it.midiNote }
        }
    }
}

object PianoLayouts {

    val LAYOUT_21 = PianoLayout(
        type = PianoLayoutType.LAYOUT_21,
        keyCount = 21,
        rows = 3,
        hasBlackKeys = false,
        rowLabels = listOf("下排(低音) C3-B3", "中排(中音) C4-B4", "上排(高音) C5-B5"),
        keys = buildList {
            val whiteLabels = listOf("C", "D", "E", "F", "G", "A", "B")
            for (row in 0..2) {
                val octave = row + 3
                whiteLabels.forEachIndexed { col, label ->
                    val midiNote = octave * 12 + when (label) {
                        "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5; "G" -> 7; "A" -> 9; "B" -> 11
                        else -> 0
                    }
                    add(KeyPosition(row, col, false, midiNote, "$label$octave", col.toFloat()))
                }
            }
        }
    )

    /**
     * 图2：每排 12 个半音键，均匀横排
     * 1 #1 2 b3 3 4 #4 5 #5 6 b7 7 × 高/中/低音
     */
    val LAYOUT_36 = PianoLayout(
        type = PianoLayoutType.LAYOUT_36,
        keyCount = 36,
        rows = 3,
        hasBlackKeys = true,
        rowLabels = listOf("低音 C3-B3", "中音 C4-B4", "高音 C5-B5"),
        keys = buildList {
            val labels = listOf("1", "#1", "2", "b3", "3", "4", "#4", "5", "#5", "6", "b7", "7")
            val black = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)
            for (row in 0..2) {
                val octave = row + 3
                for (col in 0 until 12) {
                    add(
                        KeyPosition(
                            row = row,
                            col = col,
                            isBlack = black[col],
                            midiNote = octave * 12 + col,
                            label = labels[col],
                            visualCol = col.toFloat()
                        )
                    )
                }
            }
        }
    )

    /**
     * 图1：钢琴式 36 键
     * 每排 7 个白键 + 上方 5 个黑键（1 2 空隙 4 5 6 的升号位）
     */
    val LAYOUT_36_PIANO = PianoLayout(
        type = PianoLayoutType.LAYOUT_36_PIANO,
        keyCount = 36,
        rows = 3,
        hasBlackKeys = true,
        rowLabels = listOf("低音", "中音", "高音"),
        keys = buildList {
            val white = listOf(
                0 to "1", 2 to "2", 4 to "3", 5 to "4", 7 to "5", 9 to "6", 11 to "7"
            )
            val black = listOf(
                1 to ("#1" to 0.5f),
                3 to ("#2" to 1.5f),
                6 to ("#4" to 3.5f),
                8 to ("#5" to 4.5f),
                10 to ("#6" to 5.5f)
            )
            for (row in 0..2) {
                val octave = row + 3
                white.forEachIndexed { col, (semi, label) ->
                    add(KeyPosition(row, col, false, octave * 12 + semi, label, col.toFloat()))
                }
                black.forEach { (semi, pair) ->
                    add(KeyPosition(row, 7, true, octave * 12 + semi, pair.first, pair.second))
                }
            }
        }
    )

    val LAYOUT_15 = PianoLayout(
        type = PianoLayoutType.LAYOUT_15,
        keyCount = 15,
        rows = 3,
        hasBlackKeys = false,
        rowLabels = listOf("下排 F4-C5", "中排 A3-E4", "上排 C3-G3"),
        keys = buildList {
            listOf(48 to "C3", 50 to "D3", 52 to "E3", 53 to "F3", 55 to "G3").forEachIndexed { col, (midi, label) ->
                add(KeyPosition(2, col, false, midi, label, col.toFloat()))
            }
            listOf(57 to "A3", 59 to "B3", 60 to "C4", 62 to "D4", 64 to "E4").forEachIndexed { col, (midi, label) ->
                add(KeyPosition(1, col, false, midi, label, col.toFloat()))
            }
            listOf(65 to "F4", 67 to "G4", 69 to "A4", 71 to "B4", 72 to "C5").forEachIndexed { col, (midi, label) ->
                add(KeyPosition(0, col, false, midi, label, col.toFloat()))
            }
        }
    )

    fun getLayout(type: PianoLayoutType): PianoLayout = when (type) {
        PianoLayoutType.LAYOUT_21 -> LAYOUT_21
        PianoLayoutType.LAYOUT_15 -> LAYOUT_15
        PianoLayoutType.LAYOUT_36 -> LAYOUT_36
        PianoLayoutType.LAYOUT_36_PIANO -> LAYOUT_36_PIANO
    }

    fun inferLayout(keyCount: Int, appName: String): PianoLayout {
        return when {
            appName.contains("钢琴式") || appName.contains("piano", ignoreCase = true) && keyCount == 36 -> LAYOUT_36_PIANO
            keyCount >= 36 -> LAYOUT_36
            appName.contains("光遇", ignoreCase = true) || keyCount == 15 -> LAYOUT_15
            else -> LAYOUT_21
        }
    }
}
