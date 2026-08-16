package com.example.magicpinao.core.calibration

/**
 * 钢琴/游戏键位布局。
 *
 * 无黑键（15/21/22）：转 C 调 + 半音降半音 + 越界升降八度。
 * 有黑键（36/37）：不转调、不处理半音，仅越界升降八度。
 */
enum class PianoLayoutType(val label: String, val keyCount: Int, val shortLabel: String) {
    LAYOUT_15("15键", 15, "15"),
    LAYOUT_21("21键·下排低音", 21, "21"),
    LAYOUT_21_GUITAR("21键·上排低音", 21, "21吉他"),
    LAYOUT_22("22键", 22, "22"),
    LAYOUT_36("36键·通用横排", 36, "36横"),
    LAYOUT_36_SHAPED("36键·异形", 36, "36异"),
    LAYOUT_37("37键·通用", 37, "37横"),
    LAYOUT_37_SHAPED("37键·异形", 37, "37异");

    companion object {
        fun fromName(name: String?): PianoLayoutType {
            return when (name) {
                "LAYOUT_36_PIANO" -> LAYOUT_36_SHAPED
                else -> try {
                    valueOf(name ?: "")
                } catch (_: Exception) {
                    LAYOUT_21
                }
            }
        }
    }
}

data class KeyPosition(
    val row: Int,           // 0=下排, 1=中排, 2=上排
    val col: Int,
    val isBlack: Boolean,
    val midiNote: Int,
    val label: String,
    val visualCol: Float = col.toFloat()
)

data class PianoLayout(
    val type: PianoLayoutType,
    val keyCount: Int,
    val rows: Int,
    val hasBlackKeys: Boolean,
    val keys: List<KeyPosition>,
    val rowLabels: List<String>,
    /** 黑键是否像钢琴那样抬高半排；横排 12 键时为 false */
    val staggerBlackKeys: Boolean = false
) {
    fun getRowKeys(row: Int): List<KeyPosition> = keys.filter { it.row == row }

    fun getOrderedKeys(): List<KeyPosition> {
        return (0 until rows).flatMap { row ->
            getRowKeys(row).sortedBy { it.visualCol }
        }
    }

    /** 三排共用的列数：以上排最右键为准，避免上排多一键时压缩前面间距。 */
    fun sharedColumnCount(): Float = (keys.maxOfOrNull { it.visualCol } ?: 0f) + 1f
}

object PianoLayouts {

    private val CHROMATIC_LABELS = listOf("1", "#1", "2", "b3", "3", "4", "#4", "5", "#5", "6", "b7", "7")
    private val WHITE_LABELS = listOf("C", "D", "E", "F", "G", "A", "B")
    private val WHITE_SEMI = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    private fun whiteNote(octave: Int, whiteIdx: Int): Pair<Int, String> {
        val midi = octave * 12 + WHITE_SEMI[whiteIdx]
        return midi to "${WHITE_LABELS[whiteIdx]}$octave"
    }

    private fun addWhiteOctave(target: MutableList<KeyPosition>, row: Int, octave: Int, extraCNext: Boolean = false) {
        WHITE_LABELS.forEachIndexed { col, _ ->
            val (midi, label) = whiteNote(octave, col)
            target.add(KeyPosition(row, col, false, midi, label, col.toFloat()))
        }
        if (extraCNext) {
            val midi = (octave + 1) * 12
            target.add(KeyPosition(row, 7, false, midi, "C${octave + 1}", 7f))
        }
    }

    private fun addChromaticOctave(
        target: MutableList<KeyPosition>,
        row: Int,
        octave: Int,
        throughCNext: Boolean = false
    ) {
        val count = if (throughCNext) 13 else 12
        for (col in 0 until count) {
            val midi = octave * 12 + col
            val label = if (col == 12) "1'" else CHROMATIC_LABELS[col]
            target.add(KeyPosition(row, col, col % 12 in listOf(1, 3, 6, 8, 10), midi, label, col.toFloat()))
        }
    }

    /**
     * 钢琴八度视觉列：白键占 0–6，黑键落在缝隙（E–F 之间无黑键），
     * 多出的高音 C 另起第 7 列。
     */
    private val PIANO_VISUAL_COL = floatArrayOf(
        0f, 0.5f, 1f, 1.5f, 2f, 3f, 3.5f, 4f, 4.5f, 5f, 5.5f, 6f
    )

    private fun addPianoOctave(
        target: MutableList<KeyPosition>,
        row: Int,
        octave: Int,
        throughCNext: Boolean = false
    ) {
        val count = if (throughCNext) 13 else 12
        for (col in 0 until count) {
            val midi = octave * 12 + col
            val isBlack = col % 12 in listOf(1, 3, 6, 8, 10)
            val visual = if (col == 12) 7f else PIANO_VISUAL_COL[col]
            val label = if (col == 12) "1'" else CHROMATIC_LABELS[col]
            target.add(KeyPosition(row, col, isBlack, midi, label, visual))
        }
    }

    /** 15键：光遇、和平精英。上 C4-G4 / 中 A4-E5 / 下 F5-C6 */
    val LAYOUT_15 = PianoLayout(
        type = PianoLayoutType.LAYOUT_15,
        keyCount = 15,
        rows = 3,
        hasBlackKeys = false,
        rowLabels = listOf("下排 F5-C6", "中排 A4-E5", "上排 C4-G4"),
        keys = buildList {
            listOf(60 to "C4", 62 to "D4", 64 to "E4", 65 to "F4", 67 to "G4").forEachIndexed { col, (m, l) ->
                add(KeyPosition(2, col, false, m, l, col.toFloat()))
            }
            listOf(69 to "A4", 71 to "B4", 72 to "C5", 74 to "D5", 76 to "E5").forEachIndexed { col, (m, l) ->
                add(KeyPosition(1, col, false, m, l, col.toFloat()))
            }
            listOf(77 to "F5", 79 to "G5", 81 to "A5", 83 to "B5", 84 to "C6").forEachIndexed { col, (m, l) ->
                add(KeyPosition(0, col, false, m, l, col.toFloat()))
            }
        }
    )

    /** 21键：原神等，下排低音。上 C5-B5 / 中 C4-B4 / 下 C3-B3 */
    val LAYOUT_21 = PianoLayout(
        type = PianoLayoutType.LAYOUT_21,
        keyCount = 21,
        rows = 3,
        hasBlackKeys = false,
        rowLabels = listOf("下排 C3-B3", "中排 C4-B4", "上排 C5-B5"),
        keys = buildList {
            addWhiteOctave(this, 0, 3)
            addWhiteOctave(this, 1, 4)
            addWhiteOctave(this, 2, 5)
        }
    )

    /** 21键吉他类：上排低音。上 C3-B3 / 中 C5-B5 / 下 C4-B4 */
    val LAYOUT_21_GUITAR = PianoLayout(
        type = PianoLayoutType.LAYOUT_21_GUITAR,
        keyCount = 21,
        rows = 3,
        hasBlackKeys = false,
        rowLabels = listOf("下排 C4-B4", "中排 C5-B5", "上排 C3-B3"),
        keys = buildList {
            addWhiteOctave(this, 0, 4)
            addWhiteOctave(this, 1, 5)
            addWhiteOctave(this, 2, 3)
        }
    )

    /** 22键：七日世界吉他。上 C5-C6(8) / 中 C4-B4 / 下 C3-B3 */
    val LAYOUT_22 = PianoLayout(
        type = PianoLayoutType.LAYOUT_22,
        keyCount = 22,
        rows = 3,
        hasBlackKeys = false,
        rowLabels = listOf("下排 C3-B3", "中排 C4-B4", "上排 C5-C6"),
        keys = buildList {
            addWhiteOctave(this, 0, 3)
            addWhiteOctave(this, 1, 4)
            addWhiteOctave(this, 2, 5, extraCNext = true)
        }
    )

    private fun linear36(type: PianoLayoutType, labelPrefix: String) = PianoLayout(
        type = type,
        keyCount = 36,
        rows = 3,
        hasBlackKeys = true,
        staggerBlackKeys = false,
        rowLabels = listOf("$labelPrefix 低音 C3-B3", "中音 C4-B4", "高音 C5-B5"),
        keys = buildList {
            addChromaticOctave(this, 0, 3)
            addChromaticOctave(this, 1, 4)
            addChromaticOctave(this, 2, 5)
        }
    )

    private fun linear37(type: PianoLayoutType, labelPrefix: String) = PianoLayout(
        type = type,
        keyCount = 37,
        rows = 3,
        hasBlackKeys = true,
        staggerBlackKeys = false,
        rowLabels = listOf("$labelPrefix 低音 C3-B3", "中音 C4-B4", "高音 C5-C6"),
        keys = buildList {
            addChromaticOctave(this, 0, 3)
            addChromaticOctave(this, 1, 4)
            addChromaticOctave(this, 2, 5, throughCNext = true)
        }
    )

    private fun shaped36() = PianoLayout(
        type = PianoLayoutType.LAYOUT_36_SHAPED,
        keyCount = 36,
        rows = 3,
        hasBlackKeys = true,
        staggerBlackKeys = true,
        rowLabels = listOf("异形36 低音 C3-B3", "中音 C4-B4", "高音 C5-B5"),
        keys = buildList {
            addPianoOctave(this, 0, 3)
            addPianoOctave(this, 1, 4)
            addPianoOctave(this, 2, 5)
        }
    )

    private fun shaped37() = PianoLayout(
        type = PianoLayoutType.LAYOUT_37_SHAPED,
        keyCount = 37,
        rows = 3,
        hasBlackKeys = true,
        staggerBlackKeys = true,
        rowLabels = listOf("异形37 低音 C3-B3", "中音 C4-B4", "高音 C5-C6"),
        keys = buildList {
            addPianoOctave(this, 0, 3)
            addPianoOctave(this, 1, 4)
            addPianoOctave(this, 2, 5, throughCNext = true)
        }
    )

    val LAYOUT_36 = linear36(PianoLayoutType.LAYOUT_36, "通用36")
    val LAYOUT_36_SHAPED = shaped36()
    val LAYOUT_37 = linear37(PianoLayoutType.LAYOUT_37, "通用37")
    val LAYOUT_37_SHAPED = shaped37()

    fun getLayout(type: PianoLayoutType): PianoLayout = when (type) {
        PianoLayoutType.LAYOUT_15 -> LAYOUT_15
        PianoLayoutType.LAYOUT_21 -> LAYOUT_21
        PianoLayoutType.LAYOUT_21_GUITAR -> LAYOUT_21_GUITAR
        PianoLayoutType.LAYOUT_22 -> LAYOUT_22
        PianoLayoutType.LAYOUT_36 -> LAYOUT_36
        PianoLayoutType.LAYOUT_36_SHAPED -> LAYOUT_36_SHAPED
        PianoLayoutType.LAYOUT_37 -> LAYOUT_37
        PianoLayoutType.LAYOUT_37_SHAPED -> LAYOUT_37_SHAPED
    }

    fun inferLayout(keyCount: Int, appName: String): PianoLayout {
        return when {
            keyCount == 15 -> LAYOUT_15
            keyCount == 22 -> LAYOUT_22
            keyCount == 37 && appName.contains("异") -> LAYOUT_37_SHAPED
            keyCount == 37 -> LAYOUT_37
            keyCount >= 36 && appName.contains("异") -> LAYOUT_36_SHAPED
            keyCount >= 36 -> LAYOUT_36
            appName.contains("吉他") -> LAYOUT_21_GUITAR
            else -> LAYOUT_21
        }
    }
}
