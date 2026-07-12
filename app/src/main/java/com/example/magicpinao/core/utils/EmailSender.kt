package com.example.magicpinao.core.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * 邮件发送器 - 用于发送注册/重置密码验证码
 * 使用 QQ邮箱/Foxmail SMTP 服务
 */
object EmailSender {

    private const val TAG = "EmailSender"

    // QQ邮箱 SMTP 配置 (Foxmail 使用同一套 SMTP)
    private const val SMTP_HOST = "smtp.qq.com"
    private const val SMTP_PORT = "465"
    private const val SENDER_EMAIL = "magicpianoplayer@foxmail.com"
    // QQ邮箱SMTP授权码 (在QQ邮箱设置→账户→POP3/SMTP服务中生成)
    private const val SENDER_AUTH_CODE = "xzwdpufqrbribhgd"
    private const val SENDER_NAME = "MagicPiano魔琴"

    /**
     * 发送验证码邮件
     * @param recipientEmail 收件人邮箱
     * @return Result包装的6位验证码，失败时包含错误详情
     */
    suspend fun sendVerificationCode(recipientEmail: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val code = generateCode()
                sendEmail(recipientEmail, code, "注册")
                Log.d(TAG, "Verification code sent to $recipientEmail, code=$code")
                Result.success(code)
            } catch (e: Exception) {
                val detail = getErrorDetail(e)
                Log.e(TAG, "Failed to send verification code: $detail", e)
                Result.failure(Exception("验证码发送失败: $detail"))
            }
        }
    }

    /**
     * 发送重置密码验证码
     */
    suspend fun sendResetPasswordCode(recipientEmail: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val code = generateCode()
                sendEmail(recipientEmail, code, "重置密码")
                Log.d(TAG, "Reset password code sent to $recipientEmail")
                Result.success(code)
            } catch (e: Exception) {
                val detail = getErrorDetail(e)
                Log.e(TAG, "Failed to send reset code: $detail", e)
                Result.failure(Exception("邮件发送失败: $detail"))
            }
        }
    }

    /**
     * 生成6位随机数字验证码
     */
    private fun generateCode(): String {
        return (100000..999999).random().toString()
    }

    /**
     * 发送邮件
     */
    @Throws(Exception::class)
    private fun sendEmail(recipientEmail: String, code: String, action: String) {
        val props = Properties().apply {
            put("mail.smtp.host", SMTP_HOST)
            put("mail.smtp.port", SMTP_PORT)
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.ssl.trust", SMTP_HOST)
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
            put("mail.smtp.writetimeout", "15000")
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(SENDER_EMAIL, SENDER_AUTH_CODE)
            }
        })
        session.debug = true

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(SENDER_EMAIL, SENDER_NAME))
            setRecipient(Message.RecipientType.TO, InternetAddress(recipientEmail))
            subject = "MagicPiano魔琴 - ${action}验证码"
            setContent(buildHtmlContent(code, action), "text/html; charset=UTF-8")
        }

        Transport.send(message)
    }

    /**
     * 提取异常详情（包括嵌套的cause message）
     */
    private fun getErrorDetail(e: Exception): String {
        val messages = mutableListOf<String>()
        var current: Throwable? = e
        while (current != null) {
            val msg = current.message
            if (msg != null && msg !in messages) {
                messages.add(msg)
            }
            current = current.cause
        }
        return if (messages.isNotEmpty()) {
            messages.joinToString(" → ").take(200)
        } else {
            e.javaClass.simpleName
        }
    }

    /**
     * 构建HTML邮件内容
     */
    private fun buildHtmlContent(code: String, action: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="font-family: Arial, sans-serif; background: #0F0F1E; padding: 20px;">
                <div style="max-width: 480px; margin: 0 auto; background: #1A1A2E; border-radius: 16px; padding: 32px; border: 1px solid #6C5CE7;">
                    <div style="text-align: center; margin-bottom: 24px;">
                        <h1 style="color: #6C5CE7; margin: 0; font-size: 28px;">🎹 MagicPiano 魔琴</h1>
                        <p style="color: #A29BFE; font-size: 14px; margin-top: 8px;">$action 验证码</p>
                    </div>
                    <div style="text-align: center; background: linear-gradient(135deg, #6C5CE7, #00CEC9); border-radius: 12px; padding: 24px; margin: 20px 0;">
                        <span style="font-size: 48px; font-weight: bold; color: white; letter-spacing: 12px;">$code</span>
                    </div>
                    <p style="color: #E0E0E0; font-size: 14px; text-align: center; line-height: 1.6;">
                        您正在进行 $action 操作<br>
                        验证码有效期为 <span style="color: #FD79A8; font-weight: bold;">5分钟</span>，请尽快使用<br>
                        如非本人操作，请忽略此邮件
                    </p>
                    <hr style="border: none; border-top: 1px solid #252542; margin: 20px 0;">
                    <p style="color: #A0A0A0; font-size: 12px; text-align: center;">
                        MagicPiano 魔琴自动演奏系统
                    </p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
