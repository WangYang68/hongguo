package com.example.magicpinao.core.network

import android.util.Log
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * API 仓库 - 封装所有API调用
 */
class ApiRepository {

    private val TAG = "ApiRepository"

    // 预定义常用 TypeToken
    private val loginResponseType = object : TypeToken<ApiResponse<LoginResponseData>>() {}.type
    private val stringResponseType = object : TypeToken<ApiResponse<Any>>() {}.type
    private val musicListResponseType = object : TypeToken<ApiResponse<MusicListData>>() {}.type
    private val playHistoryResponseType = object : TypeToken<ApiResponse<PagedData<PlayHistoryItem>>>() {}.type
    private val avatarResponseType = object : TypeToken<ApiResponse<AvatarUploadData>>() {}.type
    private val vipRecordResponseType = object : TypeToken<ApiResponse<PagedData<VipRecord>>>() {}.type
    private val userListResponseType = object : TypeToken<ApiResponse<PagedData<UserListItem>>>() {}.type

    /**
     * 用户登录
     */
    suspend fun login(account: String, password: String): Result<LoginResponseData> {
        return withContext(Dispatchers.IO) {
            try {
                val request = LoginRequest(account, password)
                val json = ApiClient.doPost("/api/login", request)
                val response: ApiResponse<LoginResponseData> = ApiClient.gson.fromJson(json, loginResponseType)

                if (response.code == 200 && response.data != null) {
                    Result.success(response.data)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 用户注册
     */
    suspend fun register(
        account: String,
        nickname: String,
        password: String,
        inviteCode: String? = null
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val request = RegisterRequest(
                    account = account,
                    nickname = nickname,
                    password = password,
                    code = inviteCode
                )
                val json = ApiClient.doPost("/api/register", request)
                val response: ApiResponse<Any> = ApiClient.gson.fromJson(json, stringResponseType)

                if (response.code == 200) {
                    Result.success(response.message)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Register error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 重置密码
     */
    suspend fun resetPassword(account: String, newPassword: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val request = ResetPwdRequest(account, newPassword)
                val json = ApiClient.doPost("/api/reset/pwd", request)
                val response: ApiResponse<Any> = ApiClient.gson.fromJson(json, stringResponseType)

                if (response.code == 200) {
                    Result.success(response.message)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reset password error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 退出登录
     */
    suspend fun logout(token: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val json = ApiClient.doPost("/api/logout", token = token)
                val response: ApiResponse<Any> = ApiClient.gson.fromJson(json, stringResponseType)

                if (response.code == 200) {
                    Result.success(response.message)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Logout error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 检查VIP到期状态
     * 返回: true = VIP未过期, false = VIP已过期
     */
    suspend fun checkVipExpire(token: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val json = ApiClient.doGet("/api/user/check/vip/expire", token = token)
                val response: ApiResponse<Any> = ApiClient.gson.fromJson(json, stringResponseType)

                if (response.code == 200) {
                    Result.success(true)  // VIP未过期
                } else {
                    Result.success(false) // VIP已过期
                }
            } catch (e: Exception) {
                Log.e(TAG, "Check VIP expire error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 获取云端音乐列表
     */
    suspend fun getMusicList(
        token: String,
        page: Int = 1,
        size: Int = 10,
        search: String? = null
    ): Result<MusicListData> {
        return withContext(Dispatchers.IO) {
            try {
                val params = mutableMapOf(
                    "page" to page.toString(),
                    "size" to size.toString()
                )
                if (!search.isNullOrBlank()) {
                    params["search"] = search
                }

                val json = ApiClient.doGet("/api/musics", params, token)
                val response: ApiResponse<MusicListData> = ApiClient.gson.fromJson(json, musicListResponseType)

                if (response.code == 200 && response.data != null) {
                    Result.success(response.data)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Get music list error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 添加最近播放记录
     */
    suspend fun addRecentPlay(token: String, musicId: Int): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val body = mapOf("id" to musicId)
                val json = ApiClient.doPost("/api/user/recent/play", body, token)
                val response: ApiResponse<Any> = ApiClient.gson.fromJson(json, stringResponseType)

                if (response.code == 200) {
                    Result.success(response.message)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Add recent play error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 获取最近播放列表
     */
    suspend fun getRecentPlayList(
        token: String,
        page: Int = 1,
        size: Int = 10
    ): Result<PagedData<PlayHistoryItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val params = mapOf("page" to page.toString(), "size" to size.toString())
                val json = ApiClient.doGet("/api/user/play/list", params, token)
                val response: ApiResponse<PagedData<PlayHistoryItem>> = ApiClient.gson.fromJson(json, playHistoryResponseType)

                if (response.code == 200 && response.data != null) {
                    Result.success(response.data)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Get recent play list error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 上传头像
     */
    suspend fun uploadAvatar(token: String, file: File): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val json = ApiClient.doPostMultipart(
                    "/api/user/avatar/upload",
                    file,
                    "file",
                    token
                )
                val response: ApiResponse<AvatarUploadData> = ApiClient.gson.fromJson(json, avatarResponseType)

                if (response.code == 200 && response.data != null) {
                    Result.success(response.data.url)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload avatar error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 激活VIP码
     */
    suspend fun activateVip(token: String, activationCode: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val request = ActivateVipRequest(activationCode)
                val json = ApiClient.doPost("/api/user/activate/code", request, token)
                val response: ApiResponse<Any> = ApiClient.gson.fromJson(json, stringResponseType)

                if (response.code == 200) {
                    Result.success(response.message)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Activate VIP error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 获取VIP激活记录列表
     */
    suspend fun getVipRecords(
        token: String,
        page: Int = 1,
        size: Int = 10
    ): Result<PagedData<VipRecord>> {
        return withContext(Dispatchers.IO) {
            try {
                val params = mapOf("page" to page.toString(), "size" to size.toString())
                val json = ApiClient.doGet("/api/user/code/list", params, token)
                val response: ApiResponse<PagedData<VipRecord>> = ApiClient.gson.fromJson(json, vipRecordResponseType)

                if (response.code == 200 && response.data != null) {
                    Result.success(response.data)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Get VIP records error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 收藏/取消收藏
     */
    suspend fun toggleCollect(token: String, musicId: Int): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val body = mapOf("id" to musicId)
                val json = ApiClient.doPost("/api/user/collect", body, token)
                val response: ApiResponse<Any> = ApiClient.gson.fromJson(json, stringResponseType)

                if (response.code == 200) {
                    Result.success(response.message)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Toggle collect error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 获取收藏列表
     */
    suspend fun getCollectList(
        token: String,
        page: Int = 1,
        size: Int = 10
    ): Result<PagedData<PlayHistoryItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val params = mapOf("page" to page.toString(), "size" to size.toString())
                val json = ApiClient.doGet("/api/user/collect/list", params, token)
                val response: ApiResponse<PagedData<PlayHistoryItem>> = ApiClient.gson.fromJson(json, playHistoryResponseType)

                if (response.code == 200 && response.data != null) {
                    Result.success(response.data)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Get collect list error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 获取用户列表
     */
    suspend fun getUserList(
        token: String,
        page: Int = 1,
        size: Int = 10,
        search: String? = null
    ): Result<PagedData<UserListItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val params = mutableMapOf(
                    "page" to page.toString(),
                    "size" to size.toString()
                )
                if (!search.isNullOrBlank()) {
                    params["search"] = search
                }
                val json = ApiClient.doGet("/api/user/list", params, token)
                val response: ApiResponse<PagedData<UserListItem>> = ApiClient.gson.fromJson(json, userListResponseType)

                if (response.code == 200 && response.data != null) {
                    Result.success(response.data)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Get user list error", e)
                Result.failure(e)
            }
        }
    }
}
