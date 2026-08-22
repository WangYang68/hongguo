package com.example.magicpinao.core.accessibility

import com.example.magicpinao.core.calibration.PianoLayoutType

data class TargetApp(
    val packageName: String,
    val appName: String,
    val keyCount: Int = 21,
    val baseNote: Int = 48,
    val description: String = "",
    val whiteKeysOnly: Boolean = false,
    val rowCount: Int = 3,
    val layoutType: String = "LAYOUT_21"
)

object SupportedApps {

    val apps = listOf(
        TargetApp("layout.LAYOUT_15", "15键", 15, 60, "光遇、和平精英 · 上C4-G4", true, 3, "LAYOUT_15"),
        TargetApp("layout.LAYOUT_21", "21键·下排低音", 21, 48, "原神风物琴 · 下排C3", true, 3, "LAYOUT_21"),
        TargetApp("layout.LAYOUT_21_GUITAR", "21键·上排低音", 21, 48, "原神吉他类 · 上排C3", true, 3, "LAYOUT_21_GUITAR"),
        TargetApp("layout.LAYOUT_22", "22键", 22, 48, "七日世界吉他 · 上排到C6", true, 3, "LAYOUT_22"),
        TargetApp("layout.LAYOUT_36", "36键·通用横排", 36, 48, "异环、永劫无间 · 每排12半音", false, 3, "LAYOUT_36"),
        TargetApp("layout.LAYOUT_36_SHAPED", "36键·异形", 36, 48, "第五人格、心动小镇 · 图2横排12", false, 3, "LAYOUT_36_SHAPED"),
        TargetApp("layout.LAYOUT_37", "37键·通用", 37, 48, "直线排列 · 上排到C6", false, 3, "LAYOUT_37"),
        TargetApp("layout.LAYOUT_37_SHAPED", "37键·异形", 37, 48, "心动小镇等 · 上排到C6", false, 3, "LAYOUT_37_SHAPED"),
        TargetApp("layout.LAYOUT_61", "61键", 61, 36, "C2–C7 · 键盘字符标注", false, 1, "LAYOUT_61"),
        TargetApp("layout.LAYOUT_88", "88键", 84, 24, "C1–B7 · 标准音名标注", false, 1, "LAYOUT_88")
    )

    private val packageAliases = mapOf(
        "com.miHoYo.GenshinImpact" to "layout.LAYOUT_21",
        "com.PA.GenshinPiano" to "layout.LAYOUT_21",
        "com.any.piano" to "layout.LAYOUT_21",
        "com.sky.xlzq" to "layout.LAYOUT_15",
        "com.tgc.sky.android" to "layout.LAYOUT_15",
        "com.netease.sky" to "layout.LAYOUT_15",
        "layout.LAYOUT_36_PIANO" to "layout.LAYOUT_36_SHAPED"
    )

    fun layoutId(type: PianoLayoutType): String = "layout.${type.name}"

    fun normalizePackage(packageName: String): String {
        if (packageName == "layout.LAYOUT_36_PIANO") return "layout.LAYOUT_36_SHAPED"
        if (packageName.startsWith("layout.")) return packageName
        return packageAliases[packageName] ?: packageName
    }

    fun findByPackage(packageName: String): TargetApp? {
        val normalized = normalizePackage(packageName)
        return apps.find { it.packageName == normalized || it.packageName == packageName }
    }

    fun findByLayout(type: PianoLayoutType): TargetApp =
        apps.find { it.layoutType == type.name } ?: getDefault()

    fun getDefault(): TargetApp = apps[1]

    fun getCurrentForeground(): TargetApp? = null

    fun getForegroundPackage(): String? {
        val service = PianoAccessibilityService.instance ?: return null
        val root = service.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        root.recycle()
        return if (pkg == "com.example.magicpinao") null else pkg
    }
}
