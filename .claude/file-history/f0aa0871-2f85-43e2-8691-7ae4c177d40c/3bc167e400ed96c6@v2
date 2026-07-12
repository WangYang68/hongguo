package com.example.magicpinao.core.auth

import android.content.Context
import android.content.SharedPreferences
import com.example.magicpinao.core.network.LoginResponseData
import com.google.gson.Gson

/**
 * 认证状态管理器
 * 使用 SharedPreferences 持久化存储 token 和用户信息
 */
object AuthManager {

    private const val PREFS_NAME = "magic_piano_auth"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_USER_INFO = "user_info"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    private var _userInfo: LoginResponseData? = null
    private var _token: String? = null

    /**
     * 初始化（在 Application 或 MainActivity 中调用）
     */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _token = prefs.getString(KEY_TOKEN, null)
        _isLoggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false)

        // 恢复用户信息
        val userJson = prefs.getString(KEY_USER_INFO, null)
        if (userJson != null) {
            try {
                _userInfo = gson.fromJson(userJson, LoginResponseData::class.java)
            } catch (e: Exception) {
                _userInfo = null
            }
        }
    }

    private var _isLoggedIn: Boolean = false

    /** 当前 token */
    val token: String?
        get() = _token

    /** 用户信息 */
    val userInfo: LoginResponseData?
        get() = _userInfo

    /** 是否已登录 */
    val isLoggedIn: Boolean
        get() = _isLoggedIn && !_token.isNullOrBlank()

    /** 用户昵称 */
    val nickname: String
        get() = _userInfo?.nickname ?: ""

    /** 用户账号 */
    val account: String
        get() = _userInfo?.account ?: ""

    /** 头像URL */
    val avatar: String
        get() = _userInfo?.avatar ?: ""

    /** VIP状态 "1"=已过期, "2"=未过期 */
    val vipStatus: String
        get() = _userInfo?.status ?: "2"

    /** 推广码 */
    val pushCode: String
        get() = _userInfo?.pushCode ?: ""

    /** 邀请人 */
    val pushUser: String
        get() = _userInfo?.pushUser ?: ""

    /** 注册时间 */
    val createdAt: String
        get() = _userInfo?.createdAt ?: ""

    /** 用户ID */
    val userId: Int
        get() = _userInfo?.userId ?: 0

    /**
     * 保存登录信息
     */
    fun saveLogin(user: LoginResponseData) {
        _token = user.token
        _userInfo = user
        _isLoggedIn = true

        prefs.edit()
            .putString(KEY_TOKEN, user.token)
            .putString(KEY_USER_INFO, gson.toJson(user))
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
    }

    /**
     * 更新头像URL
     */
    fun updateAvatar(url: String) {
        _userInfo = _userInfo?.copy(avatar = url)
        _userInfo?.let {
            prefs.edit().putString(KEY_USER_INFO, gson.toJson(it)).apply()
        }
    }

    /**
     * 更新VIP状态
     */
    fun updateVipStatus(status: String) {
        _userInfo = _userInfo?.copy(status = status)
        _userInfo?.let {
            prefs.edit().putString(KEY_USER_INFO, gson.toJson(it)).apply()
        }
    }

    /**
     * 清除登录信息
     */
    fun clearLogin() {
        _token = null
        _userInfo = null
        _isLoggedIn = false

        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_INFO)
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .apply()
    }
}
