package com.example.magicpinao.core.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 邮箱验证码发送（注册 / 重置密码共用）
 */
object VerificationCodeHelper {

    fun send(
        scope: CoroutineScope,
        email: String,
        isValidEmail: (String) -> Boolean,
        action: suspend (String) -> Result<String>,
        onSending: (Boolean) -> Unit,
        onSuccess: (String, String) -> Unit,
        onFailure: (String) -> Unit
    ): Job {
        if (!isValidEmail(email)) {
            onFailure("请输入正确的邮箱格式")
            return scope.launch { }
        }
        onSending(true)
        return scope.launch {
            val result = action(email)
            onSending(false)
            result.fold(
                onSuccess = { code ->
                    onSuccess(code, "验证码已发送至 $email，请查收（1-2分钟内到达，含垃圾箱）")
                },
                onFailure = { e ->
                    onFailure(e.message ?: "验证码发送失败，请稍后重试")
                }
            )
        }
    }

    /** 发送成功后启动倒计时（秒） */
    fun startCooldown(
        scope: CoroutineScope,
        seconds: Int,
        onTick: (Int) -> Unit,
        onDone: () -> Unit
    ): Job = scope.launch {
        for (i in seconds downTo 1) {
            onTick(i)
            delay(1000)
        }
        onDone()
    }
}

fun isValidEmail(email: String): Boolean =
    Regex("[^@]+@[^@]+\\.[^@]+").matches(email)
