package com.example.magicpinao.core.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import com.example.magicpinao.core.accessibility.PianoAccessibilityService

/**
 * 权限管理工具类
 */
class PermissionManager(private val context: Context) {
    
    /**
     * 检查无障碍服务是否已启用
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        val serviceId = "${context.packageName}/${PianoAccessibilityService::class.java.name}"
        
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(serviceId, ignoreCase = true)) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * 检查悬浮窗权限
     */
    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
    
    /**
     * 尽量直接打开本应用的无障碍开关页；失败则退回系统无障碍列表并尝试定位条目。
     */
    fun openAccessibilitySettings() {
        val component = ComponentName(context, PianoAccessibilityService::class.java)
        val flatten = component.flattenToString()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                // 该 SDK 的 android.jar 未导出此常量，直接使用 action 字符串
                val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                    putExtra("android.intent.extra.COMPONENT_NAME", flatten)
                }
                context.startActivity(details)
                return
            } catch (_: Exception) {
            }
        }

        try {
            val highlight = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val args = Bundle()
                args.putString(":settings:fragment_args_key", flatten)
                putExtra(":settings:fragment_args_key", flatten)
                putExtra(":settings:show_fragment_args", args)
                putExtra("extra_fragment_arg_key", flatten)
                putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            }
            context.startActivity(highlight)
            return
        } catch (_: Exception) {
        }

        val fallback = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(fallback)
    }
    
    /**
     * 打开悬浮窗权限设置页面
     */
    fun openOverlaySettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
        } else {
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
    
    /**
     * 打开应用详情页面
     */
    fun openAppDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.data = android.net.Uri.fromParts("package", context.packageName, null)
        context.startActivity(intent)
    }
}
