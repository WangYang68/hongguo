package com.example.magicpinao.core.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * 邮件发送器 - 注册 / 重置密码验证码（QQ 邮箱 SMTP）
 */
object EmailSender {

    private const val TAG = "EmailSender"
    private const val SMTP_HOST = "smtp.qq.com"
    private const val SMTP_PORT = "465"
    private const val SENDER_EMAIL = "magicpianoplayer@foxmail.com"
    private const val SENDER_AUTH_CODE = "nllxdevzlpdscaji"
    private const val SENDER_NAME = "MagicPiano魔琴"
    private const val MAX_RETRIES = 2

    suspend fun sendVerificationCode(recipientEmail: String): Result<String> =
        sendCodeEmail(recipientEmail, "注册")

    suspend fun sendResetPasswordCode(recipientEmail: String): Result<String> =
        sendCodeEmail(recipientEmail, "重置密码")

    private suspend fun sendCodeEmail(recipientEmail: String, action: String): Result<String> {
        return withContext(Dispatchers.IO) {
            val code = generateCode()
            val subject = "MagicPiano魔琴 - ${action}验证码"
            val bodyText = buildPlainBody(code, action)
            val sent = sendEmailWithRetry(
                recipientEmail = recipientEmail,
                subject = subject,
                bodyText = bodyText,
                code = code,
                action = action
            )
            if (sent) {
                Log.d(TAG, "Verification email sent to $recipientEmail ($action)")
                Result.success(code)
            } else {
                Result.failure(Exception("验证码发送失败，请检查网络或稍后重试"))
            }
        }
    }

    private fun generateCode(): String = (100000..999999).random().toString()

    private fun buildPlainBody(code: String, action: String): String {
        return "您正在进行 $action 操作，验证码为：$code\n" +
            "验证码有效期为 5 分钟，请尽快使用。\n" +
            "如非本人操作，请忽略此邮件。"
    }

    private suspend fun sendEmailWithRetry(
        recipientEmail: String,
        subject: String,
        bodyText: String,
        code: String,
        action: String
    ): Boolean {
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                sendEmailOnce(recipientEmail, subject, bodyText, code, action)
                Log.d(TAG, "Email sent (attempt ${attempt + 1}/$MAX_RETRIES)")
                return true
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "Send failed (attempt ${attempt + 1}/$MAX_RETRIES): ${getErrorDetail(e)}", e)
                if (attempt < MAX_RETRIES - 1) {
                    delay(2000L * (attempt + 1))
                }
            }
        }
        lastError?.let { Log.e(TAG, "All retries failed: ${getErrorDetail(it)}") }
        return false
    }

    @Throws(Exception::class)
    private fun sendEmailOnce(
        recipientEmail: String,
        subject: String,
        bodyText: String,
        code: String,
        action: String
    ) {
        val props = Properties().apply {
            put("mail.smtp.host", SMTP_HOST)
            put("mail.smtp.port", SMTP_PORT)
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.ssl.trust", SMTP_HOST)
            put("mail.smtp.connectiontimeout", "30000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", "30000")
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(SENDER_EMAIL, SENDER_AUTH_CODE)
            }
        })

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(SENDER_EMAIL, SENDER_NAME))
            setRecipient(Message.RecipientType.TO, InternetAddress(recipientEmail))
            this.subject = subject
            setContent(buildHtmlContent(bodyText, code, action), "text/html; charset=UTF-8")
        }

        Transport.send(message)
    }

    private fun buildHtmlContent(bodyText: String, code: String, action: String): String {
        val sentAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val escapedBody = bodyText.replace("\n", "<br>")
        return """
            <html>
            <body style="font-family: Arial, sans-serif; padding: 20px;">
                <div style="max-width: 600px; margin: 0 auto; background-color: #f9f9f9; padding: 20px; border-radius: 10px;">
                    <h2 style="color: #333; text-align: center;">MagicPiano 魔琴</h2>
                    <div style="background-color: white; padding: 20px; border-radius: 5px; margin-top: 20px;">
                        <p style="font-size: 16px; color: #555; text-align: center; margin-bottom: 16px;">$action 验证码</p>
                        <p style="font-size: 32px; font-weight: bold; color: #6C5CE7; text-align: center; letter-spacing: 8px; margin: 16px 0;">$code</p>
                        <p style="font-size: 16px; color: #555;">$escapedBody</p>
                        <hr style="border: none; border-top: 1px solid #eee; margin: 20px 0;">
                        <p style="font-size: 12px; color: #999; text-align: center;">
                            此邮件由 MagicPiano 系统自动发送，请勿回复。<br>
                            发送时间: $sentAt
                        </p>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getErrorDetail(e: Exception): String {
        val messages = mutableListOf<String>()
        var current: Throwable? = e
        while (current != null) {
            current.message?.takeIf { it !in messages }?.let { messages.add(it) }
            current = current.cause
        }
        return messages.joinToString(" → ").take(200).ifBlank { e.javaClass.simpleName }
    }
}
