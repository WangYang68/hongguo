# 如何添加 MIDI 文件到应用

## 当前实现方式

应用使用 **assets** 目录来打包 MIDI 文件，这样文件会直接包含在 APK 中。

## 添加新的 MIDI 文件

### 步骤：

1. **将 MIDI 文件复制到以下目录：**
   ```
   app/src/main/assets/midi_music/
   ```

2. **重新构建并安装应用：**
   ```bash
   ./gradlew installDebug
   ```

3. **首次启动应用时会自动：**
   - 从 assets 目录复制所有 MIDI 文件到应用内部存储
   - 扫描并导入到数据库
   - 在首页显示音乐列表

## 目录结构

```
app/
├── midi_music/                    # 开发时的源文件目录（仅供参考）
│   ├── xxx.mid
│   └── yyy.midi
└── src/main/
    └── assets/
        └── midi_music/           # ✓ 实际使用的目录（打包到APK）
            ├── xxx.mid
            └── yyy.midi
```

## 注意事项

- ✅ assets/midi_music 中的文件会被打包到 APK
- ✅ 首次运行时自动复制到应用内部存储
- ✅ 已存在的文件不会重复复制
- ❌ 不要在运行时修改 assets 目录（只读）

## 如果需要从外部存储加载

未来可以扩展支持从手机公共目录（如 Download）加载 MIDI 文件，需要：
1. 添加文件选择器功能
2. 将选择的文件复制到应用存储
3. 导入到数据库
