from mido import Message, MetaMessage, MidiFile

# 定义调性主音相对于 C 的偏移量（半音数）
KEY_MAIN_NOTE_OFFSET = {
    'C': 0,   # C 调主音是 C大调，偏移量为 0
    'Am': 0,  # Am 调主音是 A小调，相对于 C 偏移 0

    'C#': 1,  # C# 调主音是 C#大调，相对于 C 偏移 +1
    'A#m': 1,  # A#m 调主音是 A#小调，相对于 C 偏移 +1
    'Db': 1,  # Db 调主音是 Db大调，相对于 C 偏移 +1
    'Bbm': 1,  # Bbm 调主音是 Bb小调，相对于 C 偏移 +1

    'D': 2,   # D 调主音是 D大调，相对于 C 偏移 +2
    'Bm': 2,  # Bm 调主音是 B小调，相对于 C 偏移 +2

    'D#': 3,   # D# 调主音是 D#大调，相对于 C 偏移 +3
    'Eb': 3,  # Eb 调主音是 Eb，相对于 C 偏移 +3
    'Cm': 3,  # Cm 调主音是 C小调，相对于 C 偏移 +3

    'E': 4,   # E 调主音是 E大调，相对于 C 偏移 +4
    'C#m': 4,  # C#m 调主音是 C#小调，相对于 C 偏移 +4

    'F': 5,   # F 调主音是 F大调，相对于 C 偏移 +5
    'Dm': 5,  # Dm 调主音是 D小调，相对于 C 偏移 +5

    'F#': -6,  # F# 调主音是 F#大调，相对于 C 偏移 -6
    'D#m': -6,  # D#m 调主音是 D#小调，相对于 C 偏移 -6
    'Gb': -6,  # Gb 调主音是 Gb，相对于 C 偏移 -6
    'Ebm': -6,  # Ebm 调主音是 Eb小调，相对于 C 偏移 -6

    'G': -5,   # G 调主音是 G大调，相对于 C 偏移 -5
    'Em': -5,  # Em 调主音是 G小调，相对于 C 偏移 -5

    'G#': -4,   # G# 调主音是 G#大调，相对于 C 偏移 -4
    'Ab': -4,  # Ab 调主音是 Ab大调，相对于 C 偏移 -4
    'Fm': -4,  # Fm 调主音是 F小调，相对于 C 偏移 -4

    'A': -3,   # A 调主音是 A，相对于 C 偏移 -3
    'F#m': -3,  # F#m 调主音是 F#小调，相对于 C 偏移 -3

    'A#': -2, # A# 调主音是 A#大调，相对于 C 偏移 -2
    'Bb': -2, # Bb 调主音是 Bb大调，相对于 C 偏移 -2
    'Gm': -2,  # Gm 调主音是 G小调，相对于 C 偏移 -2

    'B': -1,  # B 调主音是 B大调，相对于 C 偏移 -1
    'G#m': -1,  # G#m 调主音是 G#小调，相对于 C 偏移 -1
    'Cb': -1,   # Cb 调主音是 Cb大调，相对于 C 偏移 -1
    'Abm': -1,  # Abm 调主音是 Abm小调，相对于 C 偏移 -1
}

def transpose_midi_func(original_midi):
    """
    根据调性规则对 MIDI 文件进行调整。
    - 将当前调性的主音视为新的「中央 C」。
    - 其他音符一并调整，保持相对位置不变。
    - 支持处理包含多个调性的 MIDI 文件。
    """
    # 初始化变量
    current_key = 'C'  # 默认调性为 C 调
    current_offset = 0 # 当前调性主音相对于 C 的偏移量

    for track in original_midi.tracks:
        for msg in track:
            if isinstance(msg, MetaMessage) and msg.type == 'key_signature':
                # 更新当前调性及主音偏移量
                current_key = msg.key
                current_offset = KEY_MAIN_NOTE_OFFSET.get(current_key, 0)

            elif isinstance(msg, Message) and msg.type in ('note_on', 'note_off'):
                # 调整音符位置，使其相对于当前调性的主音
                msg.note -= current_offset

    return original_midi
