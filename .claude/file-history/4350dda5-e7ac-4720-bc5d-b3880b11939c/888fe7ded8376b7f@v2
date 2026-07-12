package com.example.magicpinao.core.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * API 客户端单例
 * 基础URL: http://39.107.213.164:8090
 */
object ApiClient {

    private const val BASE_URL = "http://39.107.213.164:8090"
    private const val TAG = "ApiClient"
    private const val CLIENT_HEADER = "midi_client"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    val gson = Gson()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 构建带认证头的 Request.Builder
     */
    private fun baseBuilder(token: String? = null): Request.Builder {
        val builder = Request.Builder()
            .addHeader("Client", CLIENT_HEADER)
        if (!token.isNullOrBlank()) {
            builder.addHeader("Authorization", token)
        }
        return builder
    }

    /**
     * GET 请求
     */
    suspend fun doGet(path: String, params: Map<String, String>? = null, token: String? = null): String {
        return withContext(Dispatchers.IO) {
            val urlBuilder = StringBuilder("$BASE_URL$path")
            if (!params.isNullOrEmpty()) {
                urlBuilder.append("?")
                params.entries.forEachIndexed { index, entry ->
                    if (index > 0) urlBuilder.append("&")
                    urlBuilder.append("${entry.key}=${entry.value}")
                }
            }

            val request = baseBuilder(token)
                .url(urlBuilder.toString())
                .get()
                .build()

            Log.d(TAG, "GET ${request.url}")
            executeRequest(request)
        }
    }

    /**
     * POST JSON 请求
     */
    suspend fun doPost(path: String, body: Any? = null, token: String? = null): String {
        return withContext(Dispatchers.IO) {
            val jsonBody = if (body != null) {
                gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE)
            } else {
                "{}".toRequestBody(JSON_MEDIA_TYPE)
            }

            val request = baseBuilder(token)
                .url("$BASE_URL$path")
                .post(jsonBody)
                .build()

            Log.d(TAG, "POST ${request.url} body=${if (body != null) gson.toJson(body) else "{}"}")
            executeRequest(request)
        }
    }

    /**
     * 文件上传 (multipart/form-data)
     */
    suspend fun doPostMultipart(
        path: String,
        file: File,
        fileFieldName: String = "file",
        token: String? = null
    ): String {
        return withContext(Dispatchers.IO) {
            val mediaType = when {
                file.extension.lowercase() in listOf("png") -> "image/png".toMediaType()
                file.extension.lowercase() in listOf("jpg", "jpeg") -> "image/jpeg".toMediaType()
                file.extension.lowercase() in listOf("mid", "midi") -> "audio/midi".toMediaType()
                else -> "application/octet-stream".toMediaType()
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    fileFieldName,
                    file.name,
                    file.asRequestBody(mediaType)
                )
                .build()

            val request = baseBuilder(token)
                .url("$BASE_URL$path")
                .post(requestBody)
                .build()

            Log.d(TAG, "POST MULTIPART ${request.url} file=${file.name}")
            executeRequest(request)
        }
    }

    /**
     * 执行请求并返回字符串
     */
    private fun executeRequest(request: Request): String {
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            Log.d(TAG, "Response [${response.code}]: ${body.take(500)}")
            body
        } catch (e: IOException) {
            Log.e(TAG, "Request failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * 解析API响应为指定类型（使用 TypeToken 避免 inline/reified 问题）
     */
    fun <T> parseResponse(json: String, type: java.lang.reflect.Type): ApiResponse<T> {
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            ApiResponse(code = 500, message = "解析错误: ${e.message}", data = null)
        }
    }
}
