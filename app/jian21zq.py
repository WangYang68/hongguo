import threading
import logging
import time

from myapp.libs.keyboard import KeyBoard
from myapp.libs.native_key_scheduler import NativeKeyReleaseScheduler

_logger = logging.getLogger(__name__)

# 键盘表
keys = [
  'z', 'z', 'x', 'x', 'c', 'v', 'v', 'b', 'b', 'n', 'n', 'm',  # C3~B3
  'a', 'a', 's', 's', 'd', 'f', 'f', 'g', 'g', 'h', 'h', 'j',  # C4~B4 (中央)
  'q', 'q', 'w', 'w', 'e', 'r', 'r', 't', 't', 'y', 'y', 'u',  # C5~B5
]

# 音节表
notes = [
  48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, # C3~B3
  60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, # C4~B4 (中央)
  72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, # C5~B5
]

# 键盘-音节 匹配
keysNotes = {}
for i in range(len(notes)):
  keysNotes[notes[i]] = keys[i]

class LyreJianzq():
  isWaitForSignal = False
  shouldPlayBegin = False
  playingNotes = set()
  pressedKeys = set()
  press_mode = "long"
  _scheduler = None
  RECOGNIZABLE_GAP_MS = 50

  keyBoard = KeyBoard()

  def __init__(self):
    self._recently_released_tracker = {}
    self._delayed_press_tracker = {}
    self._tracker_lock = threading.Lock()

  def getKey(self, note):
    key = keysNotes.get(note)
    return key
  def _on_delayed_pressed(self, note, key):
    with self._tracker_lock:
      self._delayed_press_tracker.pop(note, None)
      self._recently_released_tracker.pop(note, None)

  def playNote(self, note):
    key = keysNotes.get(note)

    if self.press_mode == "short":
      try:
        self.keyBoard.pressKey(key)
        self._scheduler.schedule_release(note=note, key=key, delay_ms=35)
      except Exception as e:
        print(f"单键操作错误: {key}, 错误信息: {e}")
      return

    if note in self.playingNotes:
      try:
        self.keyBoard.releaseKey(key)
      except:
        pass
      if self._scheduler is not None:
        with self._tracker_lock:
          self._delayed_press_tracker[note] = key
        self._scheduler.schedule_press(note=note, key=key, delay_ms=self.RECOGNIZABLE_GAP_MS, on_pressed=self._on_delayed_pressed)
      else:
        _logger.warning("[LyreJianzq] _scheduler未注入，降级为立即按下")
        self.keyBoard.pressKey(key)
      return

    with self._tracker_lock:
      released_info = self._recently_released_tracker.pop(note, None)

    if released_info is not None:
      elapsed_ms = (time.time() - released_info["release_time"]) * 1000
      remaining_delay = max(0, self.RECOGNIZABLE_GAP_MS - elapsed_ms)
      self.playingNotes.add(note)
      if remaining_delay > 0:
        if self._scheduler is not None:
          with self._tracker_lock:
            self._delayed_press_tracker[note] = key
          self._scheduler.schedule_press(note=note, key=key, delay_ms=int(remaining_delay), on_pressed=self._on_delayed_pressed)
        else:
          _logger.warning("[LyreJianzq] _scheduler未注入，降级为立即按下")
          self.keyBoard.pressKey(key)
      else:
        self.keyBoard.pressKey(key)
      return

    self.playingNotes.add(note)
    self.keyBoard.pressKey(key)
    self.pressedKeys.add(key)

  def offNote(self, note):
    self.playingNotes.discard(note)

    if self.press_mode == "short":
      return

    key = keysNotes.get(note)
    if self.press_mode == "long":
      with self._tracker_lock:
        if note in self._delayed_press_tracker:
          if self._scheduler is not None:
            self._scheduler.cancel_schedule(note)
          self._delayed_press_tracker.pop(note, None)
        else:
          try:
            self.keyBoard.releaseKey(key)
          except:
            pass
        self._recently_released_tracker[note] = {"key": key, "release_time": time.time()}

  # 获取音节
  def getNote(self, note):
    noteList = []
    # 低于最低八度，升高至最低八度
    while note < notes[0]:
      note += 12
    # 高于最高八度，降低至最高八度
    while note > notes[-1]:
      note -= 12

    if note in notes:
      noteList.append(note)
    return noteList

  def reset(self):
    if self._scheduler is not None:
        self._scheduler.cancel_all()

    with self._tracker_lock:
      self._recently_released_tracker.clear()
      self._delayed_press_tracker.clear()

    playingNotes = self.playingNotes.copy()
    for note in playingNotes:
      self.offNote(note)

    for key in list(self.pressedKeys):
      try:
        self.keyBoard.releaseKey(key)
      except:
        pass

    self.isWaitForSignal = False
    self.shouldPlayBegin = False
    self.playingNotes = set()
    self.pressedKeys = set()