package com.example.magicpinao.core.auth

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 与桌面端一致的 VIP / 试用判断：
 * 允许使用：服务器 VIP 有效 或 仍在注册后 10 分钟试用期内。
 */
object VipValidity {

    private const val TRIAL_MS = 10 * 60 * 1000L

    private val formats = arrayOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd"
    )

    fun parseDateTime(raw: String?): Date? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return null
        val normalized = s.replace('T', ' ').removeSuffix("Z").take(19)
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.isLenient = false
                sdf.timeZone = TimeZone.getDefault()
                val d = sdf.parse(if (pattern.contains("'T'")) s else normalized)
                if (d != null) return d
            } catch (_: Exception) {
            }
        }
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            sdf.parse(normalized)
        } catch (_: Exception) {
            null
        }
    }

    fun trialEnd(createdAt: String?): Date? {
        val start = parseDateTime(createdAt) ?: return null
        return Date(start.time + TRIAL_MS)
    }

    fun isInTrial(createdAt: String?, now: Date = Date()): Boolean {
        val end = trialEnd(createdAt) ?: return false
        return !now.after(end)
    }

    fun isExpirePassed(expireAt: String?, now: Date = Date()): Boolean {
        val expire = parseDateTime(expireAt) ?: return true
        return now.after(expire)
    }

    /**
     * @param serverVipValid 接口 /api/user/check/vip/expire 的结果；null 表示未查出或失败
     */
    fun isAllowed(
        serverVipValid: Boolean?,
        createdAt: String?,
        expireAts: List<String> = emptyList(),
        cachedStatus: String? = null,
        now: Date = Date()
    ): Boolean {
        if (serverVipValid == true) return true
        if (isInTrial(createdAt, now)) return true
        if (expireAts.any { !isExpirePassed(it, now) }) return true
        if (serverVipValid == false) return false
        return cachedStatus == "2"
    }

    fun format(date: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(date)
    }
}
