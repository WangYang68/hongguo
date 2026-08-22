package com.example.magicpinao.core.midi

/**
 * 音符序列
 * 包含解析后的所有音符事件，按时间排序
 */
data class NoteSequence(
    val events: List<MidiEvent>,
    val duration: Long,           // 总时长（毫秒）
    val bpm: Double,              // 速度
    val timeSignature: String,    // 拍号（如 "4/4"）
    val noteCount: Int,           // 音符总数
    val tracks: List<TrackInfo> = emptyList()
) {
    /**
     * 获取所有 NoteOn 事件（用于演奏）
     */
    fun getNoteOnEvents(): List<NoteOnEvent> {
        return events.filterIsInstance<NoteOnEvent>()
            .sortedBy { it.timestamp }
    }
    
    /**
     * 获取指定时间范围内的音符
     */
    fun getNotesInRange(startTime: Long, endTime: Long): List<NoteOnEvent> {
        return getNoteOnEvents().filter { 
            it.timestamp in startTime..endTime 
        }
    }
    
    /**
     * 按时间分组的音符（用于处理和弦）
     * key: 时间戳, value: 该时间点的所有音符
     */
    fun getNotesGroupedByTime(): Map<Long, List<NoteOnEvent>> {
        return getNoteOnEvents().groupBy { it.timestamp }
    }
}

/** 单条 MIDI 音轨统计 */
data class TrackInfo(
    val index: Int,
    val noteCount: Int
)
