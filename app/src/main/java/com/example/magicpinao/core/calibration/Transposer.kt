package com.example.magicpinao.core.calibration

/**
 * MIDI → 按键映射。
 * 无黑键：按 MIDI 调号转 C，半音降到左侧白键，越界升降八度。
 * 有黑键：不转调、不改半音，只做八度折叠。
 */
object Transposer {

    private val blackToWhite = mapOf(
        1 to 0, 3 to 2, 6 to 5, 8 to 7, 10 to 9
    )

    /**
     * 调号升号/降号数（sf，-7..7）→ 主音相对 C 的半音偏移（取最短距离）。
     * 与 transpose_midi_module.py 的 KEY_MAIN_NOTE_OFFSET 一致。
     */
    fun keySignatureOffset(sharpsFlats: Int): Int {
        var o = Math.floorMod(sharpsFlats * 7, 12)
        if (o > 6) o -= 12
        if (o == 6) o = -6
        return o
    }

    fun mapNoteToKey(midiNote: Int, layout: PianoLayout, keyOffset: Int = 0): Int? {
        val keys = layout.getOrderedKeys()
        if (keys.isEmpty()) return null
        return if (layout.hasBlackKeys) {
            mapChromatic(midiNote, keys)
        } else {
            mapDiatonic(midiNote, keys, keyOffset)
        }
    }

    /** 36/37：不转调，不处理半音，只把音高折进音域 */
    private fun mapChromatic(midiNote: Int, keys: List<KeyPosition>): Int? {
        val minNote = keys.minOf { it.midiNote }
        val maxNote = keys.maxOf { it.midiNote }
        var note = midiNote
        while (note < minNote) note += 12
        while (note > maxNote) note -= 12
        val exact = keys.indexOfFirst { it.midiNote == note }
        if (exact >= 0) return exact
        return keys.minByOrNull { kotlin.math.abs(it.midiNote - note) }?.let { keys.indexOf(it) }
    }

    /**
     * 15/21/22：
     * 1) 调号平移（主音变 C）
     * 2) C#→C 等降半音
     * 3) 超出音域升降八度，直到落在布局键上
     */
    private fun mapDiatonic(midiNote: Int, keys: List<KeyPosition>, keyOffset: Int): Int? {
        val minNote = keys.minOf { it.midiNote }
        val maxNote = keys.maxOf { it.midiNote }
        val noteSet = keys.map { it.midiNote }.toHashSet()

        var note = midiNote - keyOffset
        val semi = Math.floorMod(note, 12)
        val whiteSemi = blackToWhite[semi] ?: semi
        note = note - semi + whiteSemi

        while (note < minNote) note += 12
        while (note > maxNote) note -= 12

        keys.indexOfFirst { it.midiNote == note }.takeIf { it >= 0 }?.let { return it }

        // 折完仍不在键上（例如 15 键空洞）时，再在音域内找最近白键
        if (note !in noteSet) {
            val nearest = keys.minByOrNull { kotlin.math.abs(it.midiNote - note) }
            if (nearest != null) return keys.indexOf(nearest)
        }
        return null
    }

    fun mapSequence(notes: List<Int>, layout: PianoLayout, keyOffset: Int = 0): List<Int> {
        return notes.mapNotNull { mapNoteToKey(it, layout, keyOffset) }
    }
}
