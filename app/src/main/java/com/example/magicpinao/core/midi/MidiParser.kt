package com.example.magicpinao.core.midi

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.roundToLong

/**
 * MIDI 文件解析器
 * 支持 Type 0/1 MIDI 文件，正确解析 Tempo 并转换为毫秒时间戳
 */
class MidiParser {

    fun parseMidiFile(filePath: String): NoteSequence {
        val file = File(filePath)
        if (!file.exists()) throw IllegalArgumentException("MIDI file not found: $filePath")
        return FileInputStream(file).use { parseMidiStream(it) }
    }

    fun parseMidiFile(file: File): NoteSequence {
        return FileInputStream(file).use { parseMidiStream(it) }
    }

    private fun parseMidiStream(inputStream: InputStream): NoteSequence {
        try {
            val header = ByteArray(14)
            inputStream.read(header)

            if (String(header.sliceArray(0..3)) != "MThd") {
                throw IllegalArgumentException("Invalid MIDI file: missing MThd header")
            }

            val format = readShort(header, 8)
            val numTracks = readShort(header, 10)
            val resolution = readShort(header, 12)

            // 收集所有轨道的原始事件（tick单位）
            val rawEvents = mutableListOf<RawMidiEvent>()
            for (i in 0 until numTracks) {
                rawEvents.addAll(parseTrack(inputStream))
            }

            // 用实际tempo将tick转换为毫秒时间戳
            val converted = convertTicksToMs(rawEvents, resolution)
            val sorted = converted.sortedBy { it.timestamp }
            val duration = sorted.maxByOrNull { it.timestamp }?.timestamp ?: 0L
            val noteCount = sorted.count { it is NoteOnEvent }
            val bpm = computeAverageBpm(rawEvents)

            return NoteSequence(
                events = sorted,
                duration = duration,
                bpm = bpm,
                timeSignature = "4/4",
                noteCount = noteCount
            )
        } catch (e: Exception) {
            throw RuntimeException("Error parsing MIDI file", e)
        }
    }

    /**
     * 解析单个音轨——保持所有事件为tick单位
     */
    private fun parseTrack(inputStream: InputStream): List<RawMidiEvent> {
        val events = mutableListOf<RawMidiEvent>()

        val trackHeader = ByteArray(8)
        if (inputStream.read(trackHeader) < 8) return events
        if (String(trackHeader.sliceArray(0..3)) != "MTrk") return events

        val trackLength = readInt(trackHeader, 4)
        val trackData = ByteArray(trackLength)
        if (inputStream.read(trackData) < trackLength) return events

        var offset = 0
        var absoluteTick = 0L
        var runningStatus = 0

        while (offset < trackData.size - 1) {
            val (delta, bytesRead) = readVariableLength(trackData, offset)
            offset += bytesRead
            absoluteTick += delta.toLong()

            var status = trackData[offset].toInt() and 0xFF
            offset++

            if (status < 0x80) {
                status = runningStatus
                offset-- // 回退，这不是状态字节
            } else {
                runningStatus = status
            }

            val eventType = status and 0xF0
            val channel = status and 0x0F

            when (eventType) {
                0x90 -> { // Note On
                    if (offset + 1 < trackData.size) {
                        val pitch = trackData[offset].toInt() and 0xFF
                        val velocity = trackData[offset + 1].toInt() and 0xFF
                        offset += 2
                        val type = if (velocity > 0) RawEventType.NOTE_ON else RawEventType.NOTE_OFF
                        events.add(RawMidiEvent(absoluteTick, type, channel, pitch, velocity))
                    }
                }
                0x80 -> { // Note Off
                    if (offset + 1 < trackData.size) {
                        val pitch = trackData[offset].toInt() and 0xFF
                        val velocity = trackData[offset + 1].toInt() and 0xFF
                        offset += 2
                        events.add(RawMidiEvent(absoluteTick, RawEventType.NOTE_OFF, channel, pitch, velocity))
                    }
                }
                0xFF -> { // Meta Event
                    if (offset < trackData.size) {
                        val metaType = trackData[offset].toInt() and 0xFF
                        offset++
                        val (length, lenBytes) = readVariableLength(trackData, offset)
                        offset += lenBytes

                        when (metaType) {
                            0x51 -> { // Set Tempo (微秒/拍)
                                if (length >= 3 && offset + 2 < trackData.size) {
                                    val microsPerBeat = ((trackData[offset].toInt() and 0xFF) shl 16) or
                                            ((trackData[offset + 1].toInt() and 0xFF) shl 8) or
                                            (trackData[offset + 2].toInt() and 0xFF)
                                    events.add(RawMidiEvent(absoluteTick, RawEventType.TEMPO, 0, microsPerBeat, 0))
                                }
                                offset += length
                            }
                            else -> offset += length
                        }
                    }
                }
                0xA0, 0xB0, 0xE0 -> offset += 2
                0xC0, 0xD0 -> offset += 1
                else -> { /* skip unknown */ }
            }
        }

        return events
    }

    /**
     * 将tick转换为毫秒——使用实际的tempo变化
     */
    private fun convertTicksToMs(rawEvents: List<RawMidiEvent>, resolution: Int): List<MidiEvent> {
        val result = mutableListOf<MidiEvent>()

        val tempoChanges = rawEvents
            .filter { it.type == RawEventType.TEMPO }
            .sortedBy { it.tick }

        var currentTempo = 500000.0 // microseconds per beat (default 120 BPM)
        var lastTick = 0L
        var lastMsDouble = 0.0 // 用 Double 累加避免 toLong() 截断误差

        val noteEvents = rawEvents.filter { it.type == RawEventType.NOTE_ON || it.type == RawEventType.NOTE_OFF }

        for (event in noteEvents.sortedBy { it.tick }) {
            // 应用此事件之前的所有tempo变化
            for (tc in tempoChanges) {
                if (tc.tick in (lastTick + 1)..event.tick) {
                    val ticksInSegment = tc.tick - lastTick
                    lastMsDouble += ticksInSegment * currentTempo / 1000.0 / resolution
                    lastTick = tc.tick
                    currentTempo = tc.pitch.toDouble()
                }
            }

            val ticksInSegment = event.tick - lastTick
            // 用 kotlin.math.roundToLong 四舍五入，避免截断
            val eventMs = (lastMsDouble + ticksInSegment * currentTempo / 1000.0 / resolution).roundToLong()

            when (event.type) {
                RawEventType.NOTE_ON -> {
                    result.add(NoteOnEvent(
                        tick = event.tick,
                        timestamp = eventMs,
                        channel = event.channel,
                        pitch = event.pitch,
                        velocity = event.velocity
                    ))
                }
                RawEventType.NOTE_OFF -> {
                    result.add(NoteOffEvent(
                        tick = event.tick,
                        timestamp = eventMs,
                        channel = event.channel,
                        pitch = event.pitch,
                        velocity = event.velocity
                    ))
                }
                else -> {}
            }
        }

        return result
    }

    private fun computeAverageBpm(rawEvents: List<RawMidiEvent>): Double {
        val tempos = rawEvents.filter { it.type == RawEventType.TEMPO }.map { it.pitch }
        if (tempos.isEmpty()) return 120.0
        val avgMicros = tempos.average()
        return 60000000.0 / avgMicros
    }

    // ==================== 工具方法 ====================

    private fun readVariableLength(data: ByteArray, offset: Int): Pair<Int, Int> {
        var value = 0
        var bytesRead = 0
        do {
            if (offset + bytesRead >= data.size) break
            val b = data[offset + bytesRead].toInt() and 0xFF
            bytesRead++
            value = (value shl 7) or (b and 0x7F)
        } while ((data[offset + bytesRead - 1].toInt() and 0x80) != 0 && bytesRead < 4)
        return Pair(value, bytesRead)
    }

    private fun readShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

    fun getMidiInfo(filePath: String): MidiFileInfo {
        val file = File(filePath)
        val sequence = parseMidiFile(file)
        return MidiFileInfo(file.name, file.length(), sequence.duration, 0, sequence.noteCount)
    }
}

// ==================== 原始事件（tick单位） ====================

private enum class RawEventType { NOTE_ON, NOTE_OFF, TEMPO }

private data class RawMidiEvent(
    val tick: Long,
    val type: RawEventType,
    val channel: Int,
    val pitch: Int,
    val velocity: Int
)

data class MidiFileInfo(
    val fileName: String,
    val fileSize: Long,
    val duration: Long,
    val trackCount: Int,
    val noteCount: Int
)
