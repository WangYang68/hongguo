package com.example.magicpinao.core.accessibility

import com.example.magicpinao.core.calibration.PianoLayoutType

/**
 * 演奏/标定用的键位方案（不再绑定具体游戏包名）。
 * packageName 作为坐标存储键，实际为 layout.xxx。
 */
data class TargetApp(
    val packageName: String,
    val appName: String,
    val keyCount: Int = 21,
    val baseNote: Int = 60,
    val description: String = "",
    val whiteKeysOnly: Boolean = false,
    val rowCount: Int = 3,
    val layoutType: String = "LAYOUT_21"
)

object SupportedApps {

    val apps = listOf(
        TargetApp(
            packageName = "layout.LAYOUT_21",
            appName = "21键",
            keyCount = 21, baseNote = 48,
            description = "3排×7白键，适用于原神风物琴等同款界面",
            whiteKeysOnly = true,
            layoutType = "LAYOUT_21"
        ),
        TargetApp(
            packageName = "layout.LAYOUT_15",
            appName = "15键",
            keyCount = 15, baseNote = 48,
            description = "3排×5白键，适用于光遇等同款界面",
            whiteKeysOnly = true,
            layoutType = "LAYOUT_15"
        ),
        TargetApp(
            packageName = "layout.LAYOUT_36",
            appName = "36键·横排",
            keyCount = 36, baseNote = 48,
            description = "3排×12半音横排（图2）",
            whiteKeysOnly = false,
            layoutType = "LAYOUT_36"
        ),
        TargetApp(
            packageName = "layout.LAYOUT_36_PIANO",
            appName = "36键·钢琴式",
            keyCount = 36, baseNote = 48,
            description = "每排7白键+5黑键（图1）",
            whiteKeysOnly = false,
            layoutType = "LAYOUT_36_PIANO"
        )
    )

    /** 旧游戏包名 → 键位方案 */
    private val packageAliases = mapOf(
        "com.miHoYo.GenshinImpact" to "layout.LAYOUT_21",
        "com.PA.GenshinPiano" to "layout.LAYOUT_21",
        "com.any.piano" to "layout.LAYOUT_21",
        "com.sky.xlzq" to "layout.LAYOUT_15",
        "com.tgc.sky.android" to "layout.LAYOUT_15",
        "com.netease.sky" to "layout.LAYOUT_15"
    )

    fun layoutId(type: PianoLayoutType): String = "layout.${type.name}"

    fun normalizePackage(packageName: String): String {
        if (packageName.startsWith("layout.")) return packageName
        return packageAliases[packageName] ?: packageName
    }

    fun findByPackage(packageName: String): TargetApp? {
        val normalized = normalizePackage(packageName)
        return apps.find { it.packageName == normalized || it.packageName == packageName }
    }

    fun findByLayout(type: PianoLayoutType): TargetApp =
        apps.find { it.layoutType == type.name } ?: getDefault()

    fun getDefault(): TargetApp = apps.first()

    fun getCurrentForeground(): TargetApp? = null

    fun getForegroundPackage(): String? {
        val service = PianoAccessibilityService.instance ?: return null
        val root = service.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        root.recycle()
        return if (pkg == "com.example.magicpinao") null else pkg
    }
}
