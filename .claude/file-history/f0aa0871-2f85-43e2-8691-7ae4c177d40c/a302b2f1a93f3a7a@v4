package com.example.magicpinao.core.network

import com.google.gson.annotations.SerializedName

/**
 * 通用API响应格式
 */
data class ApiResponse<T>(
    val code: Int = 200,
    val message: String = "success",
    val data: T? = null
)

/**
 * 登录请求
 */
data class LoginRequest(
    val account: String,
    val password: String
)

/**
 * 登录响应中的用户数据
 */
data class LoginResponseData(
    @SerializedName("user_id") val userId: Int = 0,
    val account: String = "",
    val nickname: String = "",
    val token: String = "",
    val avatar: String = "",
    @SerializedName("push_code") val pushCode: String = "",
    val money: Int = 0,
    val status: String = "2",  // "1"=VIP已过期, "2"=VIP未过期
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("pushUser") val pushUser: String = ""
)

/**
 * 注册请求
 */
data class RegisterRequest(
    val account: String,
    val nickname: String,
    val password: String,
    val ip: String? = null,
    val code: String? = null  // 邀请码
)

/**
 * 重置密码请求
 */
data class ResetPwdRequest(
    val account: String,
    val password: String
)

/**
 * 音乐列表项（云端）
 * 注意：服务器返回的字段名首字母大写 (Id, Name, NameEn 等)
 */
data class MusicItem(
    @SerializedName("Id") val id: Int = 0,
    @SerializedName("Name") val name: String = "",
    @SerializedName("NameEn") val nameEn: String = "",
    @SerializedName("NameFt") val nameFt: String = "",
    @SerializedName("Singer") val singer: String = "",
    @SerializedName("Path") val path: String = "",
    @SerializedName("Category") val category: String = "",
    @SerializedName("Image") val image: String? = null,
    @SerializedName("Type") val type: Int? = null,
    @SerializedName("CreatedAt") val createdAt: String = ""
)

/**
 * 音乐列表响应
 */
data class MusicListData(
    val total: Int = 0,
    val data: List<MusicItem> = emptyList()
)

/**
 * 激活VIP请求
 */
data class ActivateVipRequest(
    val code: String
)

/**
 * VIP激活记录
 */
data class VipRecord(
    @SerializedName("ID") val id: Int = 0,
    @SerializedName("Code") val code: String = "",
    @SerializedName("VIPDay") val vipDay: Int = 0,
    @SerializedName("CreatedTime") val createdAt: String = "",
    @SerializedName("Expire") val expireAt: String = "",
    @SerializedName("user_id") val userId: Int = 0
)

/**
 * 分页数据
 */
data class PagedData<T>(
    val total: Int = 0,
    val data: List<T> = emptyList()
)

/**
 * 收藏/最近播放项
 */
data class PlayHistoryItem(
    val name: String = "",
    val path: String = "",
    @SerializedName("created_at") val createdAt: String = "",
    val id: Int = 0
)

/**
 * 头像上传响应
 */
data class AvatarUploadData(
    val url: String = ""
)

/**
 * 用户列表项
 */
data class UserListItem(
    val id: Int = 0,
    val account: String = "",
    val money: Int = 0,
    val nickname: String = "",
    val status: Int = 1,
    val avatar: String = "",
    val ip: String = "",
    val count: Int = 1,
    val code: String = "",
    @SerializedName("push_code") val pushCode: String = "",
    @SerializedName("created_at") val createdAt: String = ""
)
