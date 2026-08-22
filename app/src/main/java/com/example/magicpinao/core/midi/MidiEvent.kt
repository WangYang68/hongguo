package com.example.magicpinao.core.midi

/**
 * MIDI 事件基类
 */
sealed class MidiEvent {
    abstract val tick: Long       // MIDI tick 时间戳
    abstract val timestamp: Long  // 毫秒时间戳（计算后）
}

/**
 * 音符开启事件
 */
data class NoteOnEvent(
    override val tick: Long,
    override val timestamp: Long,
    val channel: Int,      // MIDI 通道 (0-15)
    val pitch: Int,        // 音高 (0-127)
    val velocity: Int,     // 力度 (0-127)
    val durationMs: Long = 80L,  // note_on 到 note_off 的时长
    val track: Int = 0     // MIDI 音轨序号 (0-based)
) : MidiEvent()

/**
 * 音符关闭事件
 */
data class NoteOffEvent(
    override val tick: Long,
    override val timestamp: Long,
    val channel: Int,
    val pitch: Int,
    val velocity: Int
) : MidiEvent()

/**
 *  tempo 事件
 */
data class TempoEvent(
    override val tick: Long,
    override val timestamp: Long,
    val bpm: Double        // 每分钟节拍数
) : MidiEvent()

/**
 * 时间签名事件
 */
data class TimeSignatureEvent(
    override val tick: Long,
    override val timestamp: Long,
    val numerator: Int,    // 分子（如4/4拍的4）
    val denominator: Int   // 分母（如4/4拍的4）
) : MidiEvent()

/**
 * 调号事件：用于 15/21/22 键把乐曲转到 C 调。
 * offset = 主音相对 C 的半音数，演奏时 pitch -= offset。
 */
data class KeySignatureEvent(
    override val tick: Long,
    override val timestamp: Long,
    val sharpsFlats: Int,
    val offset: Int
) : MidiEvent()
