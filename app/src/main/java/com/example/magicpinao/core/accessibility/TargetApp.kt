package com.example.magicpinao.core.accessibility

data class TargetApp(
    val packageName: String,
    val appName: String,
    val keyCount: Int = 21,
    val baseNote: Int = 60,
    val description: String = "",
    val whiteKeysOnly: Boolean = false,
    val rowCount: Int = 1,
    val layoutType: String = "LAYOUT_21",
    /**
     * 标定/播放时实际需要打开并检测前台的包名。
     * 例如「原神」21键对应独立 App Genshin Piano，而不是原神本体。
     */
    val openPackageName: String = packageName,
    /** 提示用户打开的软件名称 */
    val openAppLabel: String = appName
) {
    fun resolveOpenPackage(): String = openPackageName.ifBlank { packageName }
    fun resolveOpenLabel(): String = openAppLabel.ifBlank { appName }
}

object SupportedApps {

    val apps = listOf(
        TargetApp(
            packageName = "com.PA.GenshinPiano",
            appName = "原神",
            keyCount = 21, baseNote = 48,
            description = "请打开 Genshin Piano · 21键",
            whiteKeysOnly = true, rowCount = 3,
            layoutType = "LAYOUT_21",
            openPackageName = "com.PA.GenshinPiano",
            openAppLabel = "原神钢琴(Genshin Piano)"
        ),
        TargetApp(
            packageName = "com.sky.xlzq",
            appName = "光遇",
            keyCount = 15, baseNote = 60,
            description = "请打开光遇 · 15键",
            whiteKeysOnly = true, rowCount = 3,
            layoutType = "LAYOUT_15",
            openAppLabel = "光遇"
        ),
        TargetApp(
            packageName = "com.tgc.sky.android",
            appName = "光遇(国际)",
            keyCount = 15, baseNote = 60,
            description = "请打开 Sky 国际服 · 15键",
            whiteKeysOnly = true, rowCount = 3,
            layoutType = "LAYOUT_15",
            openAppLabel = "光遇(国际)"
        ),
        TargetApp(
            packageName = "com.netease.sky",
            appName = "光遇(网易)",
            keyCount = 15, baseNote = 60,
            description = "请打开光遇(网易) · 15键",
            whiteKeysOnly = true, rowCount = 3,
            layoutType = "LAYOUT_15",
            openAppLabel = "光遇(网易)"
        ),
        TargetApp(
            packageName = "com.any.piano",
            appName = "通用钢琴",
            keyCount = 21, baseNote = 60,
            description = "请打开对应钢琴 App · 21键",
            whiteKeysOnly = true, rowCount = 3,
            layoutType = "LAYOUT_21",
            openAppLabel = "通用钢琴"
        )
    )

    /** 旧版「原神」曾用原神本体包名存坐标，这里做兼容映射 */
    private val packageAliases = mapOf(
        "com.miHoYo.GenshinImpact" to "com.PA.GenshinPiano",
        "com.PA.GenshinPiano" to "com.PA.GenshinPiano"
    )

    fun normalizePackage(packageName: String): String =
        packageAliases[packageName] ?: packageName

    fun findByPackage(packageName: String): TargetApp? {
        val normalized = normalizePackage(packageName)
        return apps.find { it.packageName == normalized || it.packageName == packageName }
            ?: apps.find { it.openPackageName == packageName }
    }

    fun getDefault(): TargetApp = apps.first()

    /**
     * 获取当前前台运行的钢琴应用
     */
    fun getCurrentForeground(): TargetApp? {
        val service = PianoAccessibilityService.instance ?: return null
        val root = service.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        root.recycle()
        if (pkg == null) return null
        if (pkg == "com.example.magicpinao") return null
        return findByPackage(pkg)
            ?: TargetApp(pkg, "当前应用 ($pkg)", 21, 48, "自动检测到的应用", whiteKeysOnly = true, rowCount = 3)
    }

    /**
     * 获取前台应用包名
     */
    fun getForegroundPackage(): String? {
        val service = PianoAccessibilityService.instance ?: return null
        val root = service.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        root.recycle()
        return if (pkg == "com.example.magicpinao") null else pkg
    }
}
