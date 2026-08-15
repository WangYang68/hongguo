package com.example.magicpinao.core.midi

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.roundToLong

/**
 * MIDI 文件解析器
 * 支持 Type 0/1，正确识别 Meta Tempo / SysEx / Running Status，并把 tick 转成毫秒。
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
            if (inputStream.read(header) < 14) {
                throw IllegalArgumentException("Invalid MIDI file: truncated header")
            }
            if (String(header.sliceArray(0..3)) != "MThd") {
                throw IllegalArgumentException("Invalid MIDI file: missing MThd header")
            }

            readShort(header, 8) // format
            val numTracks = readShort(header, 10)
            val division = readShort(header, 12)

            val rawEvents = mutableListOf<RawMidiEvent>()
            for (i in 0 until numTracks) {
                rawEvents.addAll(parseTrack(inputStream))
            }

            val converted = convertTicksToMs(rawEvents, division)
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

    private fun parseTrack(inputStream: InputStream): List<RawMidiEvent> {
        val events = mutableListOf<RawMidiEvent>()

        val trackHeader = ByteArray(8)
        if (readFully(inputStream, trackHeader) < 8) return events
        if (String(trackHeader.sliceArray(0..3)) != "MTrk") return events

        val trackLength = readInt(trackHeader, 4).coerceAtLeast(0)
        val trackData = ByteArray(trackLength)
        if (readFully(inputStream, trackData) < trackLength) return events

        var offset = 0
        var absoluteTick = 0L
        var runningStatus = 0

        while (offset < trackData.size) {
            if (offset >= trackData.size) break
            val (delta, bytesRead) = readVariableLength(trackData, offset)
            offset += bytesRead
            absoluteTick += delta.toLong()
            if (offset >= trackData.size) break

            var status = trackData[offset].toInt() and 0xFF
            if (status < 0x80) {
                if (runningStatus < 0x80) break
                status = runningStatus
            } else {
                offset++
                if (status < 0xF0) {
                    runningStatus = status
                } else if (status != 0xF8 && status != 0xFA && status != 0xFB && status != 0xFC && status != 0xFE) {
                    // 系统消息取消 running status（实时时钟除外）
                    if (status < 0xF8) runningStatus = 0
                }
            }

            when {
                status == 0xFF -> { // Meta
                    if (offset >= trackData.size) break
                    val metaType = trackData[offset].toInt() and 0xFF
                    offset++
                    val (length, lenBytes) = readVariableLength(trackData, offset)
                    offset += lenBytes
                    if (metaType == 0x51 && length >= 3 && offset + 2 < trackData.size) {
                        val microsPerBeat = ((trackData[offset].toInt() and 0xFF) shl 16) or
                                ((trackData[offset + 1].toInt() and 0xFF) shl 8) or
                                (trackData[offset + 2].toInt() and 0xFF)
                        events.add(RawMidiEvent(absoluteTick, RawEventType.TEMPO, 0, microsPerBeat, 0))
                    }
                    offset += length
                }
                status == 0xF0 || status == 0xF7 -> { // SysEx
                    val (length, lenBytes) = readVariableLength(trackData, offset)
                    offset += lenBytes + length
                }
                status == 0xF1 || status == 0xF3 -> offset += 1
                status == 0xF2 -> offset += 2
                status >= 0xF4 -> { /* 无数据字节的系统消息 */ }
                (status and 0xF0) == 0x90 -> {
                    if (offset + 1 >= trackData.size) break
                    val pitch = trackData[offset].toInt() and 0xFF
                    val velocity = trackData[offset + 1].toInt() and 0xFF
                    offset += 2
                    val type = if (velocity > 0) RawEventType.NOTE_ON else RawEventType.NOTE_OFF
                    events.add(RawMidiEvent(absoluteTick, type, status and 0x0F, pitch, velocity))
                }
                (status and 0xF0) == 0x80 -> {
                    if (offset + 1 >= trackData.size) break
                    val pitch = trackData[offset].toInt() and 0xFF
                    val velocity = trackData[offset + 1].toInt() and 0xFF
                    offset += 2
                    events.add(RawMidiEvent(absoluteTick, RawEventType.NOTE_OFF, status and 0x0F, pitch, velocity))
                }
                (status and 0xF0) == 0xA0 || (status and 0xF0) == 0xB0 || (status and 0xF0) == 0xE0 -> offset += 2
                (status and 0xF0) == 0xC0 || (status and 0xF0) == 0xD0 -> offset += 1
                else -> break
            }
        }

        return events
    }

    private fun convertTicksToMs(rawEvents: List<RawMidiEvent>, division: Int): List<MidiEvent> {
        if (division and 0x8000 != 0) {
            return convertSmpteToMs(rawEvents, division)
        }
        val ticksPerBeat = division.coerceAtLeast(1).toDouble()
        // 同一 tick 先应用 tempo，再出音符
        val sorted = rawEvents.sortedWith(
            compareBy<RawMidiEvent> { it.tick }.thenBy { if (it.type == RawEventType.TEMPO) 0 else 1 }
        )

        var currentTempo = 500000.0 // 默认 120 BPM
        var lastTick = 0L
        var lastMs = 0.0
        val result = mutableListOf<MidiEvent>()

        for (event in sorted) {
            if (event.tick > lastTick) {
                lastMs += (event.tick - lastTick) * currentTempo / 1000.0 / ticksPerBeat
                lastTick = event.tick
            }
            when (event.type) {
                RawEventType.TEMPO -> {
                    if (event.pitch > 0) currentTempo = event.pitch.toDouble()
                }
                RawEventType.NOTE_ON -> result.add(
                    NoteOnEvent(event.tick, lastMs.roundToLong(), event.channel, event.pitch, event.velocity)
                )
                RawEventType.NOTE_OFF -> result.add(
                    NoteOffEvent(event.tick, lastMs.roundToLong(), event.channel, event.pitch, event.velocity)
                )
            }
        }
        return result
    }

    /** SMPTE 时基：tick 直接对应真实时间，不走 tempo */
    private fun convertSmpteToMs(rawEvents: List<RawMidiEvent>, division: Int): List<MidiEvent> {
        val fps = -((division shr 8).toByte().toInt()).coerceAtLeast(1)
        val tpf = (division and 0xFF).coerceAtLeast(1)
        val ticksPerSecond = (fps * tpf).toDouble()
        val result = mutableListOf<MidiEvent>()
        for (event in rawEvents) {
            val ms = (event.tick * 1000.0 / ticksPerSecond).roundToLong()
            when (event.type) {
                RawEventType.NOTE_ON -> result.add(
                    NoteOnEvent(event.tick, ms, event.channel, event.pitch, event.velocity)
                )
                RawEventType.NOTE_OFF -> result.add(
                    NoteOffEvent(event.tick, ms, event.channel, event.pitch, event.velocity)
                )
                else -> {}
            }
        }
        return result
    }

    private fun computeAverageBpm(rawEvents: List<RawMidiEvent>): Double {
        val tempos = rawEvents.filter { it.type == RawEventType.TEMPO }.map { it.pitch }.filter { it > 0 }
        if (tempos.isEmpty()) return 120.0
        return 60_000_000.0 / tempos.average()
    }

    private fun readVariableLength(data: ByteArray, offset: Int): Pair<Int, Int> {
        var value = 0
        var bytesRead = 0
        do {
            if (offset + bytesRead >= data.size) break
            val b = data[offset + bytesRead].toInt() and 0xFF
            bytesRead++
            value = (value shl 7) or (b and 0x7F)
        } while (bytesRead > 0 && (data[offset + bytesRead - 1].toInt() and 0x80) != 0 && bytesRead < 4)
        return Pair(value, bytesRead)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n < 0) break
            off += n
        }
        return off
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
