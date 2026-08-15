package com.example.magicpinao.ui.screens.profile

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import android.util.Log
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.example.magicpinao.core.auth.AuthManager
import com.example.magicpinao.core.auth.VipValidity
import com.example.magicpinao.core.network.ApiRepository
import com.example.magicpinao.core.network.VipRecord
import com.example.magicpinao.core.utils.EmailSender
import com.example.magicpinao.ui.screens.auth.textFieldColors
import com.example.magicpinao.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * 个人中心页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateBack: () -> Unit,
    onLogout: suspend () -> Result<String>,
    onUploadAvatar: suspend (File) -> Result<String>,
    onActivateVip: suspend (String) -> Result<String>,
    onResetPassword: suspend (String, String) -> Result<String>,
    vipRecords: List<VipRecord> = emptyList(),
    loadVipRecords: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // 用户信息
    val userInfo = AuthManager.userInfo
    var serverVipValid by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        val token = AuthManager.token
        if (!token.isNullOrBlank()) {
            ApiRepository().checkVipExpire(token).fold(
                onSuccess = { serverVipValid = it },
                onFailure = { serverVipValid = null }
            )
        }
        loadVipRecords()
    }
    val (trialEndStr, isInTrial) = remember(userInfo?.createdAt) {
        calculateTrialInfo(userInfo?.createdAt ?: "")
    }
    val hasValidRecord = vipRecords.any { !VipValidity.isExpirePassed(it.expireAt) }
    val isVipValid = VipValidity.isAllowed(
        serverVipValid = serverVipValid,
        createdAt = userInfo?.createdAt,
        expireAts = vipRecords.map { it.expireAt },
        cachedStatus = userInfo?.status
    )

    // 头像选择
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var isUploadingAvatar by remember { mutableStateOf(false) }
    var avatarMessage by remember { mutableStateOf<String?>(null) }

    // VIP激活
    var showActivateDialog by remember { mutableStateOf(false) }
    var activateCode by remember { mutableStateOf("") }
    var isActivating by remember { mutableStateOf(false) }
    var activateMessage by remember { mutableStateOf<String?>(null) }

    // 重置密码对话框
    var showResetPwdDialog by remember { mutableStateOf(false) }
    var resetEmail by remember { mutableStateOf("") }
    var resetCode by remember { mutableStateOf("") }
    var resetNewPwd by remember { mutableStateOf("") }
    var resetPwdVisible by remember { mutableStateOf(false) }
    var generatedResetCode by remember { mutableStateOf<String?>(null) }
    var isSendingResetCode by remember { mutableStateOf(false) }
    var isResetting by remember { mutableStateOf(false) }
    var resetMessage by remember { mutableStateOf<String?>(null) }
    var resetEmailWasFocused by remember { mutableStateOf(false) }

    // 退出确认
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var isLoggingOut by remember { mutableStateOf(false) }

    // 图片选择器
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            isUploadingAvatar = true
            scope.launch {
                try {
                    // 复制到临时文件
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val tempFile = File(context.cacheDir, "avatar_upload.jpg")
                    inputStream?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    val result = onUploadAvatar(tempFile)
                    isUploadingAvatar = false
                    result.fold(
                        onSuccess = { url ->
                            AuthManager.updateAvatar(url)
                            avatarMessage = "头像更新成功"
                        },
                        onFailure = { e ->
                            avatarMessage = e.message ?: "头像上传失败"
                        }
                    )
                } catch (e: Exception) {
                    isUploadingAvatar = false
                    avatarMessage = "头像上传失败: ${e.message}"
                }
            }
        }
    }

    // 处理系统返回键
    BackHandler(onBack = onNavigateBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // 顶部渐变Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                PrimaryPurpleDark,
                                Color(0xFF1A1040),
                                BackgroundDark
                            )
                        )
                    )
            ) {
                // 返回按钮
                IconButton(
                    onClick = {
                        android.util.Log.d("ProfileScreen", "Back button clicked, calling onNavigateBack")
                        onNavigateBack()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 16.dp, top = 8.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // 头像和用户信息
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 头像
                    Box(
                        modifier = Modifier.size(90.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(PrimaryPurpleLight, SecondaryCyan))),
                        contentAlignment = Alignment.Center
                    ) {
                        if (userInfo?.avatar.isNullOrBlank()) {
                            Text("🎹", fontSize = 40.sp)
                        } else {
                            AsyncImage(
                                model = userInfo?.avatar,
                                contentDescription = "头像",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        // 上传中遮罩
                        if (isUploadingAvatar) {
                            Box(
                                modifier = Modifier.fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 修改头像文字
                    Text(
                        text = if (isUploadingAvatar) "上传中..." else "点击修改头像",
                        color = SecondaryCyanLight,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable(enabled = !isUploadingAvatar) {
                            imagePickerLauncher.launch("image/*")
                        }
                    )

                    // 头像消息
                    avatarMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = if (msg.contains("成功")) SuccessGreen else ErrorRed,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 昵称
                    Text(
                        text = userInfo?.nickname ?: "未设置昵称",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )

                    // 账号
                    Text(
                        text = userInfo?.account ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextHint
                    )

                    // VIP 徽章
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isVipValid) SuccessGreen.copy(alpha = 0.15f) else ErrorRed.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (isInTrial && !hasValidRecord && serverVipValid != true)
                                "✨ 试用中" else if (isVipValid) "✨ VIP 会员" else "⚠ VIP 已过期",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = if (isVipValid) SuccessGreen else ErrorRed,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            // 用户信息卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "账号信息",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = PrimaryPurpleLight
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // 注册时间 + 试用期（合成一行）
                    val createdAt = userInfo?.createdAt ?: ""
                    val regLine = if (isInTrial && trialEndStr.isNotBlank()) {
                        "📅 注册时间: $createdAt  -赠送vip过期时间: $trialEndStr"
                    } else {
                        "📅 注册时间: ${createdAt.ifBlank { "-" }}"
                    }
                    Text(
                        text = regLine,
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )

                    // 账号名称
                    Text(
                        text = "👤 账号名称: ${userInfo?.account ?: "-"}",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )

                    // 邀请人
                    val pushUser = userInfo?.pushUser?.takeIf { it.isNotBlank() }
                    if (pushUser != null) {
                        Text(
                            text = "🤝 邀请人: $pushUser",
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }

                    // 推广码
                    Text(
                        text = "🔗 推广码: ${userInfo?.pushCode ?: "-"}",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )

                    // VIP状态：服务器有效 / 试用期内 / 未过期激活记录，任一即可
                    val statusText = when {
                        isInTrial && !hasValidRecord && serverVipValid != true ->
                            "✨ 试用中（至 $trialEndStr）"
                        isVipValid -> "✨ VIP有效"
                        else -> "⚠ VIP已过期"
                    }
                    val statusColor = if (isVipValid) SuccessGreen else ErrorRed
                    Text(
                        text = statusText,
                        color = statusColor,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }

            // ===== 激活记录Tab卡片（参照桌面端 ActiveVipPage） =====
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 标题行 + 操作按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "激活记录",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = PrimaryPurpleLight
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 刷新按钮
                            IconButton(
                                onClick = { loadVipRecords() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Refresh, "刷新", tint = TextHint, modifier = Modifier.size(20.dp))
                            }
                            // 激活按钮
                            Button(
                                onClick = { showActivateDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("激活", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (vipRecords.isEmpty()) {
                        // 空状态
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无激活记录", color = TextHint, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        // 表头
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceLight, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("ID", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = TextHint, modifier = Modifier.width(30.dp))
                            Text("激活码", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = TextHint, modifier = Modifier.weight(1f))
                            Text("天数", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = TextHint, modifier = Modifier.width(40.dp))
                            Text("到期时间", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = TextHint, modifier = Modifier.width(120.dp))
                        }

                        // 记录行
                        vipRecords.forEachIndexed { index, record ->
                            val isExpired = VipValidity.isExpirePassed(record.expireAt)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (index % 2 == 0) SurfaceDark else SurfaceLight.copy(alpha = 0.3f),
                                        if (index == vipRecords.lastIndex) RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                                        else RoundedCornerShape(0.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${record.id}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    modifier = Modifier.width(30.dp)
                                )
                                Text(
                                    record.code.ifBlank { "-" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${record.vipDay}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PrimaryPurpleLight,
                                    modifier = Modifier.width(40.dp)
                                )
                                Text(
                                    if (isExpired) "(已过期)${record.expireAt.take(10)}" else record.expireAt.take(16),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isExpired) ErrorRed else SuccessGreen,
                                    modifier = Modifier.width(120.dp),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // 账号操作卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "账号操作",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = PrimaryPurpleLight
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // 重置密码
                    Surface(
                        onClick = { showResetPwdDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceLight
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = WarningOrange)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("重置密码", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                                    Text("通过邮箱验证码重置", color = TextHint, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextHint)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 退出登录
                    Surface(
                        onClick = { showLogoutConfirm = true },
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceLight
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = ErrorRed)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("退出登录", color = ErrorRed, style = MaterialTheme.typography.bodyLarge)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextHint)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // VIP激活对话框
    if (showActivateDialog) {
        AlertDialog(
            onDismissRequest = { showActivateDialog = false; activateMessage = null },
            title = {
                Text("激活VIP", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "输入激活码以激活VIP会员",
                        color = TextHint,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = activateCode,
                        onValueChange = { activateCode = it; activateMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("激活码") },
                        placeholder = { Text("请输入激活码", color = TextHint) },
                        singleLine = true,
                        colors = textFieldColors(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    activateMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = msg,
                            color = if (msg.contains("成功")) SuccessGreen else ErrorRed,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isActivating = true
                        scope.launch {
                            val result = onActivateVip(activateCode)
                            isActivating = false
                            result.fold(
                                onSuccess = { msg ->
                                    activateMessage = msg
                                    AuthManager.updateVipStatus("2")
                                    serverVipValid = true
                                    loadVipRecords()
                                },
                                onFailure = { e ->
                                    activateMessage = e.message ?: "激活失败"
                                }
                            )
                        }
                    },
                    enabled = activateCode.isNotBlank() && !isActivating,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
                ) {
                    if (isActivating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("激活")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showActivateDialog = false; activateMessage = null }) {
                    Text("取消", color = TextHint)
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // 重置密码对话框
    if (showResetPwdDialog) {
        AlertDialog(
            onDismissRequest = { showResetPwdDialog = false; resetMessage = null },
            title = {
                Text("重置密码", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    // 邮箱
                    OutlinedTextField(
                        value = resetEmail,
                        onValueChange = { resetEmail = it; resetMessage = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (resetEmailWasFocused && !focusState.isFocused && resetEmail.isNotBlank()) {
                                    isSendingResetCode = true
                                    scope.launch {
                                        val result = EmailSender.sendResetPasswordCode(resetEmail)
                                        isSendingResetCode = false
                                        result.fold(
                                            onSuccess = { code ->
                                                generatedResetCode = code
                                                resetMessage = "验证码已发送"
                                            },
                                            onFailure = { e ->
                                                resetMessage = e.message ?: "验证码发送失败"
                                            }
                                        )
                                    }
                                }
                                resetEmailWasFocused = focusState.isFocused
                            },
                        label = { Text("邮箱") },
                        singleLine = true,
                        colors = textFieldColors(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 验证码
                    OutlinedTextField(
                        value = resetCode,
                        onValueChange = { if (it.length <= 6) resetCode = it; resetMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("验证码") },
                        trailingIcon = {
                            if (isSendingResetCode) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = PrimaryPurpleLight,
                                    strokeWidth = 2.dp
                                )
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = textFieldColors(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 新密码
                    OutlinedTextField(
                        value = resetNewPwd,
                        onValueChange = { resetNewPwd = it; resetMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("新密码") },
                        singleLine = true,
                        visualTransformation = if (resetPwdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { resetPwdVisible = !resetPwdVisible }) {
                                Text(
                                    text = if (resetPwdVisible) "🙈" else "👁",
                                    fontSize = 18.sp
                                )
                            }
                        },
                        colors = textFieldColors(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    resetMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = msg,
                            color = if (msg.contains("成功") || msg.contains("已发送")) SuccessGreen else ErrorRed,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            resetEmail.isBlank() -> resetMessage = "请输入邮箱"
                            resetCode.isBlank() -> resetMessage = "请输入验证码"
                            generatedResetCode == null -> resetMessage = "请先获取验证码"
                            resetCode != generatedResetCode -> resetMessage = "验证码错误"
                            resetNewPwd.length < 6 -> resetMessage = "密码至少6位"
                            else -> {
                                isResetting = true
                                scope.launch {
                                    val result = onResetPassword(resetEmail, resetNewPwd)
                                    isResetting = false
                                    result.fold(
                                        onSuccess = {
                                            resetMessage = "密码重置成功"
                                            showResetPwdDialog = false
                                        },
                                        onFailure = { e ->
                                            resetMessage = e.message ?: "重置失败"
                                        }
                                    )
                                }
                            }
                        }
                    },
                    enabled = !isResetting,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
                ) {
                    if (isResetting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("确认重置")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetPwdDialog = false; resetMessage = null }) {
                    Text("取消", color = TextHint)
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // 退出确认对话框
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("确认退出", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("确定要退出登录吗？退出后需要重新登录才能使用。", color = TextHint) },
            confirmButton = {
                Button(
                    onClick = {
                        isLoggingOut = true
                        scope.launch {
                            val result = onLogout()
                            isLoggingOut = false
                            result.fold(
                                onSuccess = {
                                    AuthManager.clearLogin()
                                    showLogoutConfirm = false
                                    onNavigateBack()
                                },
                                onFailure = {}
                            )
                        }
                    },
                    enabled = !isLoggingOut,
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) {
                    if (isLoggingOut) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("退出")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("取消", color = TextHint)
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun ProfileInfoRow(label: String, value: String, valueColor: Color = TextPrimary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = TextHint,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
            textAlign = TextAlign.End
        )
    }
}

/**
 * 计算试用期信息
 * @return Pair(试用期到期时间字符串, 是否仍在试用期内)
 */
private fun calculateTrialInfo(createdAt: String): Pair<String, Boolean> {
    if (createdAt.isBlank()) return "" to false
    val trialEnd = VipValidity.trialEnd(createdAt) ?: return "" to false
    return VipValidity.format(trialEnd) to VipValidity.isInTrial(createdAt)
}
