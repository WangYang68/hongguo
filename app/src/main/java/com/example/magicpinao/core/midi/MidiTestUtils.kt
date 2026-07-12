package com.example.magicpinao.core.midi

import android.util.Log
import java.io.File

/**
 * MIDI 解析工具类
 * 用于测试和调试 MIDI 解析功能
 */
object MidiTestUtils {
    
    private const val TAG = "MidiTestUtils"
    
    /**
     * 测试解析 MIDI 文件
     */
    fun testParseMidiFile(filePath: String): NoteSequence? {
        return try {
            val parser = MidiParser()
            val sequence = parser.parseMidiFile(filePath)
            
            Log.d(TAG, "=== MIDI File Info ===")
            Log.d(TAG, "File: ${File(filePath).name}")
            Log.d(TAG, "Duration: ${sequence.duration} ms")
            Log.d(TAG, "BPM: ${sequence.bpm}")
            Log.d(TAG, "Time Signature: ${sequence.timeSignature}")
            Log.d(TAG, "Note Count: ${sequence.noteCount}")
            Log.d(TAG, "Event Count: ${sequence.events.size}")
            
            // 打印前10个事件
            sequence.events.take(10).forEachIndexed { index, event ->
                when (event) {
                    is NoteOnEvent -> {
                        Log.d(TAG, "Event $index: NoteOn pitch=${event.pitch}, velocity=${event.velocity}, time=${event.timestamp}ms")
                    }
                    is NoteOffEvent -> {
                        Log.d(TAG, "Event $index: NoteOff pitch=${event.pitch}, velocity=${event.velocity}, time=${event.timestamp}ms")
                    }
                    else -> {
                        Log.d(TAG, "Event $index: $event")
                    }
                }
            }
            
            sequence
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing MIDI file", e)
            null
        }
    }
    
    /**
     * 打印音符统计信息
     */
    fun printNoteStatistics(sequence: NoteSequence) {
        val noteOnEvents = sequence.getNoteOnEvents()
        
        if (noteOnEvents.isEmpty()) {
            Log.w(TAG, "No notes found")
            return
        }
        
        // 统计音高范围
        val minPitch = noteOnEvents.minOf { it.pitch }
        val maxPitch = noteOnEvents.maxOf { it.pitch }
        
        // 统计同时按下的最大音符数（和弦）
        val notesByTime = sequence.getNotesGroupedByTime()
        val maxChordSize = notesByTime.values.maxOfOrNull { it.size } ?: 0
        
        Log.d(TAG, "=== Note Statistics ===")
        Log.d(TAG, "Total Notes: ${noteOnEvents.size}")
        Log.d(TAG, "Pitch Range: $minPitch - $maxPitch")
        Log.d(TAG, "Max Chord Size: $maxChordSize")
        Log.d(TAG, "Duration: ${sequence.duration}ms (${sequence.duration / 1000.0}s)")
        
        // 显示前5个和弦
        notesByTime.entries
            .filter { it.value.size > 1 }
            .take(5)
            .forEach { (time, notes) ->
                val pitches = notes.joinToString(", ") { it.pitch.toString() }
                Log.d(TAG, "Chord at ${time}ms: [$pitches]")
            }
    }
}
