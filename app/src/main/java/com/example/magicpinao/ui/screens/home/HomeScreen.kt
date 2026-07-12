package com.example.magicpinao.ui.screens.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.magicpinao.core.accessibility.SupportedApps
import com.example.magicpinao.core.auth.AuthManager
import com.example.magicpinao.core.data.MusicEntity
import com.example.magicpinao.core.network.MusicItem
import com.example.magicpinao.ui.theme.*
import com.example.magicpinao.ui.viewmodel.*

// Tab枚举
enum class HomeTab(val label: String, val icon: String) {
    LOCAL("本地音乐", "📁"),
    CLOUD("云音乐", "☁️"),
    PROFILE("我的", "👤")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    onMusicClick: (MusicEntity) -> Unit = {},
    onCloudMusicClick: (MusicItem) -> Unit = {},
    onCalibrationClick: () -> Unit,
    onNavigateToProfile: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val musicList by viewModel.musicList.collectAsState()
    val cloudMusicList by viewModel.cloudMusicList.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedTag by viewModel.selectedTag.collectAsState()
    val cloudSearchQuery by viewModel.cloudSearchQuery.collectAsState()
    val isCloudLoading by viewModel.isCloudLoading.collectAsState()

    var selectedTab by remember { mutableStateOf(HomeTab.LOCAL) }

    var showPermissionDialog by remember { mutableStateOf(false) }
    var permissionDialogType by remember { mutableStateOf(PermissionType.NONE) }
    var pendingCalibrationPackage by remember { mutableStateOf<String?>(null) }
    var pendingCalibrationKeyCount by remember { mutableIntStateOf(21) }
    var showVipExpiredDialog by remember { mutableStateOf(false) }
    var pendingCloudMusic by remember { mutableStateOf<MusicItem?>(null) }

    val tags = remember { listOf("全部", "流行", "古典", "动漫", "游戏", "钢琴", "爵士") }

    // 进入主页时强制检查无障碍 + 悬浮窗（写入 ViewModel，由下方 Dialog 显示）
    LaunchedEffect(Unit) {
        viewModel.loadCloudMusic()
        viewModel.ensurePlayPermissions()
    }

    // VIP到期对话框
    if (showVipExpiredDialog) {
        var activateCode by remember { mutableStateOf("") }
        var isActivating by remember { mutableStateOf(false) }
        var activateError by remember { mutableStateOf<String?>(null) }
        var activateSuccess by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                showVipExpiredDialog = false
                pendingCloudMusic = null
                activateCode = ""
                activateError = null
                activateSuccess = false
            },
            title = {
                Text(
                    "使用时长已到期",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    val pushUser = AuthManager.pushUser
                    if (pushUser.isNotBlank()) {
                        Text(
                            "您的使用时长已经到期\n\n请联系您的邀请人：$pushUser",
                            color = TextHint,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            "您的使用时长已经到期\n\n请联系您的邀请人！",
                            color = TextHint,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "或者输入VIP激活码立即激活：",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = activateCode,
                        onValueChange = { activateCode = it; activateError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("激活码") },
                        placeholder = { Text("请输入VIP激活码", color = TextHint) },
                        singleLine = true,
                        enabled = !activateSuccess,
                        colors = com.example.magicpinao.ui.screens.auth.textFieldColors(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    if (activateSuccess) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("激活成功！现在可以播放音乐了", color = SuccessGreen, style = MaterialTheme.typography.bodySmall)
                    }
                    activateError?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(it, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (activateSuccess) {
                    Button(
                        onClick = {
                            showVipExpiredDialog = false
                            pendingCloudMusic = null
                            activateCode = ""
                            activateError = null
                            activateSuccess = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                    ) {
                        Text("确定")
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.activateVipCode(activateCode) { success, message ->
                                if (success) {
                                    activateSuccess = true
                                    AuthManager.updateVipStatus("2")
                                    // 重新尝试播放
                                    pendingCloudMusic?.let { viewModel.playCloudMusic(it) }
                                } else {
                                    activateError = message
                                }
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
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showVipExpiredDialog = false
                    pendingCloudMusic = null
                    activateCode = ""
                    activateError = null
                    activateSuccess = false
                }) {
                    Text("取消", color = TextHint)
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(20.dp)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部标题栏
            HomeTopBar(
                selectedAppName = uiState.selectedAppPackage?.let {
                    SupportedApps.findByPackage(it)?.appName
                } ?: "选择目标应用",
                hasCoordinates = uiState.hasCoordinates,
                coordinateHint = when {
                    uiState.selectedAppPackage.isNullOrBlank() -> "请选择游戏"
                    uiState.hasCoordinates -> uiState.coordinateSchemeName ?: "坐标已就绪"
                    else -> "未标定坐标"
                },
                onSettingsClick = {
                    val pkg = uiState.selectedAppPackage
                    if (pkg.isNullOrBlank()) {
                        viewModel.toggleAppSelector()
                    } else {
                        viewModel.startCalibration(pkg)
                    }
                },
                onAppSelectorClick = { viewModel.toggleAppSelector() },
                onRefreshClick = {
                    if (selectedTab == HomeTab.CLOUD) viewModel.loadCloudMusic(refresh = true)
                    else viewModel.scanLocalMidiFiles()
                    viewModel.refreshCoordinateStatus()
                },
                onProfileClick = onNavigateToProfile
            )

            // 根据当前Tab显示不同内容
            when (selectedTab) {
                HomeTab.LOCAL -> {
                    // 搜索框
                    SearchBar(
                        query = searchQuery,
                        onQueryChange = viewModel::searchMusic,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    // 标签筛选行
                    TagFilterRow(
                        tags = tags,
                        selectedTag = selectedTag,
                        onTagClick = viewModel::selectTag
                    )
                    // 本地音乐列表
                    if (musicList.isEmpty()) {
                        EmptyStateView(message = "暂无本地音乐", hint = "请将 MIDI 文件放入 assets/midi_music 目录")
                    } else {
                        LocalMusicList(
                            musicList = musicList,
                            uiState = uiState,
                            viewModel = viewModel,
                            context = context,
                            onMusicClick = onMusicClick
                        )
                    }
                }

                HomeTab.CLOUD -> {
                    // 云音乐搜索框
                    SearchBar(
                        query = cloudSearchQuery,
                        onQueryChange = { viewModel.searchCloudMusic(it) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    // 云音乐列表
                    if (isCloudLoading && cloudMusicList.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = PrimaryPurple)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("加载云音乐列表...", color = TextHint)
                            }
                        }
                    } else if (cloudMusicList.isEmpty()) {
                        EmptyStateView(message = "暂无云音乐", hint = "云端还没有上传音乐")
                    } else {
                        CloudMusicList(
                            musicList = cloudMusicList,
                            isLoading = isCloudLoading,
                            onMusicClick = { music ->
                                // VIP检查
                                viewModel.checkVipAndPlay(music) { canPlay, message ->
                                    if (canPlay) {
                                        onCloudMusicClick(music)
                                    } else {
                                        pendingCloudMusic = music
                                        showVipExpiredDialog = true
                                    }
                                }
                            },
                            onLoadMore = { viewModel.loadMoreCloudMusic() }
                        )
                    }
                }

                HomeTab.PROFILE -> {
                    // 点击"我的"导航到Profile页面
                    LaunchedEffect(selectedTab) {
                        onNavigateToProfile()
                        selectedTab = HomeTab.LOCAL
                    }
                }
            }
        }

        // 播放控制条（本地音乐）
        AnimatedVisibility(
            visible = uiState.currentMusicId != null && selectedTab != HomeTab.PROFILE,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PlaybackFloatingBar(
                musicTitle = uiState.currentMusicTitle ?: "未知曲目",
                isPlaying = uiState.isPlaying,
                onPauseClick = viewModel::pausePlayback,
                onResumeClick = viewModel::resumePlayback,
                onStopClick = viewModel::stopPlayback
            )
        }

        // 底部导航栏
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter),
            color = SurfaceDark.copy(alpha = 0.95f),
            shadowElevation = 16.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                HomeTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable {
                                if (tab == HomeTab.PROFILE) {
                                    onNavigateToProfile()
                                } else {
                                    selectedTab = tab
                                }
                            }
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                    ) {
                        Text(tab.icon, fontSize = 24.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) PrimaryPurpleLight else TextHint,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .width(20.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(PrimaryPurpleLight)
                            )
                        }
                    }
                }
            }
        }

        // 应用选择器弹窗
        if (uiState.showAppSelector) {
            AppSelectorDialog(
                apps = SupportedApps.apps,
                onAppSelected = { app ->
                    viewModel.selectTargetApp(app.packageName)
                },
                onDismiss = { viewModel.toggleAppSelector() }
            )
        }

        // 未标定坐标引导
        if (uiState.showCalibrationPrompt) {
            val pkg = uiState.calibratingAppPackage ?: uiState.selectedAppPackage
            val appName = pkg?.let { SupportedApps.findByPackage(it)?.appName } ?: "目标应用"
            AlertDialog(
                onDismissRequest = { viewModel.dismissCalibrationPrompt() },
                title = { Text("需要标定琴键坐标", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "「$appName」还没有可用的音符坐标。\n\n请打开该应用的钢琴界面，用悬浮标定把标记对齐到每个琴键后保存。不同游戏键位数量不同（如原神21键、光遇15键）。",
                        color = TextHint
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.dismissCalibrationPrompt()
                            pkg?.let { viewModel.startCalibration(it) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
                    ) { Text("开始标定") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissCalibrationPrompt() }) {
                        Text("稍后", color = TextHint)
                    }
                },
                containerColor = SurfaceDark,
                shape = RoundedCornerShape(20.dp)
            )
        }

        // 权限对话框（必须使用 ViewModel 状态，否则播放/标定时的权限请求不会显示）
        if (uiState.showPermissionDialog) {
            PermissionDialog(
                permissionType = uiState.permissionType,
                onDismiss = { viewModel.dismissPermissionDialog() },
                onOpenSettings = {
                    when (uiState.permissionType) {
                        PermissionType.ACCESSIBILITY -> viewModel.openAccessibilitySettings()
                        PermissionType.OVERLAY -> viewModel.openOverlaySettings()
                        else -> {}
                    }
                    viewModel.dismissPermissionDialog()
                }
            )
        } else if (showPermissionDialog) {
            // 兼容旧本地状态
            PermissionDialog(
                permissionType = permissionDialogType,
                onDismiss = { showPermissionDialog = false },
                onOpenSettings = {
                    when (permissionDialogType) {
                        PermissionType.ACCESSIBILITY -> viewModel.openAccessibilitySettings()
                        PermissionType.OVERLAY -> viewModel.openOverlaySettings()
                        else -> {}
                    }
                    showPermissionDialog = false
                }
            )
        }

        // 错误提示
        uiState.errorMessage?.let { error ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).padding(bottom = 64.dp),
                actionContentColor = ErrorRed,
                containerColor = SurfaceDark
            ) { Text(error, color = TextPrimary) }
            LaunchedEffect(error) {
                kotlinx.coroutines.delay(3000)
                viewModel.clearError()
            }
        }
    }
}


@Composable
private fun LocalMusicList(
    musicList: List<MusicEntity>,
    uiState: MainUiState,
    viewModel: MainViewModel,
    context: android.content.Context,
    onMusicClick: (MusicEntity) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index) {
        val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems > 0 && lastVisibleIndex >= totalItems - 3) {
            viewModel.loadMore()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(musicList, key = { it.id }) { music ->
            MusicCard(
                music = music,
                isCurrentlyPlaying = uiState.currentMusicId == music.id,
                isPaused = uiState.currentMusicId == music.id && !uiState.isPlaying,
                onClick = { onMusicClick(music) }
            )
        }
    }
}

@Composable
private fun CloudMusicList(
    musicList: List<MusicItem>,
    isLoading: Boolean,
    onMusicClick: (MusicItem) -> Unit,
    onLoadMore: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index) {
        val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems > 0 && lastVisibleIndex >= totalItems - 3 && !isLoading) {
            onLoadMore()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(musicList, key = { "cloud_${it.id}" }) { music ->
            CloudMusicCard(
                music = music,
                onClick = { onMusicClick(music) }
            )
        }
        if (isLoading) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = PrimaryPurple,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudMusicCard(music: MusicItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                // 音乐图标
                Box(
                    modifier = Modifier.size(50.dp).clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(PrimaryPurple, SecondaryCyan))),
                    contentAlignment = Alignment.Center
                ) {
                    if (music.image != null) {
                        AsyncImage(
                            model = music.image,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text("🎵", fontSize = 22.sp)
                    }
                }

                Column(modifier = Modifier.fillMaxWidth(0.75f)) {
                    Text(
                        text = music.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (music.singer.isNotBlank()) {
                        Text(
                            text = "🎤 ${music.singer}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (music.category.isNotBlank()) {
                        Text(
                            text = "🏷 ${music.category}",
                            style = MaterialTheme.typography.labelSmall,
                            color = PrimaryPurpleLight,
                            maxLines = 1
                        )
                    }
                }
            }

            Surface(
                onClick = onClick, shape = CircleShape,
                color = PrimaryPurple.copy(alpha = 0.3f)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    tint = PrimaryPurpleLight,
                    modifier = Modifier.padding(10.dp).size(22.dp)
                )
            }
        }
    }
}

// ============ 以下为复用组件（与之前基本一致）============

@Composable
private fun HomeTopBar(
    selectedAppName: String,
    hasCoordinates: Boolean,
    coordinateHint: String,
    onSettingsClick: () -> Unit,
    onAppSelectorClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onProfileClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = SurfaceDark, shadowElevation = 4.dp) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🎹", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Magic Piano",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            brush = Brush.horizontalGradient(listOf(PrimaryPurpleLight, SecondaryCyan))
                        )
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefreshClick) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = TextPrimary, modifier = Modifier.size(22.dp))
                    }

                    Surface(
                        onClick = onAppSelectorClick,
                        shape = RoundedCornerShape(20.dp),
                        color = PrimaryPurple.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("🎵", style = MaterialTheme.typography.bodyLarge)
                            Text(selectedAppName, style = MaterialTheme.typography.labelMedium, color = PrimaryPurpleLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = PrimaryPurpleLight, modifier = Modifier.size(16.dp))
                        }
                    }

                    IconButton(onClick = onProfileClick) {
                        val avatar = AuthManager.avatar
                        if (avatar.isNotBlank()) {
                            AsyncImage(
                                model = avatar,
                                contentDescription = "个人中心",
                                modifier = Modifier.size(30.dp).clip(CircleShape)
                            )
                        } else {
                            Icon(Icons.Default.AccountCircle, contentDescription = "个人中心", tint = PrimaryPurpleLight, modifier = Modifier.size(28.dp))
                        }
                    }

                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "标定坐标",
                            tint = if (hasCoordinates) SuccessGreen else ErrorRed,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            // 坐标状态条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (hasCoordinates) SuccessGreen else ErrorRed)
                )
                Text(
                    text = coordinateHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasCoordinates) SuccessGreen else TextHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!hasCoordinates && selectedAppName != "选择目标应用") {
                    Text(
                        text = "点击齿轮标定",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondaryCyan,
                        modifier = Modifier.clickable(onClick = onSettingsClick)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
        placeholder = { Text("搜索音乐...", color = TextHint) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextHint) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = SurfaceLight,
            unfocusedContainerColor = SurfaceLight,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = PrimaryPurple,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        singleLine = true
    )
}

@Composable
private fun TagFilterRow(tags: List<String>, selectedTag: String, onTagClick: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tags) { tag ->
            FilterChip(
                selected = tag == selectedTag,
                onClick = { onTagClick(tag) },
                label = { Text(tag, style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryPurple,
                    selectedLabelColor = Color.White,
                    containerColor = SurfaceLight,
                    labelColor = TextSecondary
                ),
                shape = RoundedCornerShape(20.dp),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = Color.Transparent,
                    selectedBorderColor = PrimaryPurpleLight,
                    enabled = true,
                    selected = tag == selectedTag
                )
            )
        }
    }
}

@Composable
private fun MusicCard(
    music: MusicEntity,
    isCurrentlyPlaying: Boolean,
    isPaused: Boolean = false,
    onClick: () -> Unit
) {
    val tags = remember(music.tags) { music.getTagList() }
    val active = isCurrentlyPlaying

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (active) PrimaryPurple.copy(alpha = 0.15f) else SurfaceDark
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (active) 8.dp else 2.dp),
        border = if (active) androidx.compose.foundation.BorderStroke(1.dp, PrimaryPurpleLight) else null
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier.size(50.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (active) Brush.linearGradient(listOf(PrimaryPurpleLight, SecondaryCyan)) else Brush.linearGradient(listOf(PrimaryPurple, SecondaryCyan))),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        active && !isPaused -> PlayingAnimation()
                        else -> Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }

                Column(modifier = Modifier.fillMaxWidth(0.75f)) {
                    Text(
                        text = music.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (active) PrimaryPurpleLight else TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    if (tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.take(3).forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = PrimaryPurple.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PrimaryPurpleLight,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            if (tags.size > 3) {
                                Text("+${tags.size - 3}", style = MaterialTheme.typography.labelSmall, color = TextHint)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = music.fileName, style = MaterialTheme.typography.bodySmall, color = TextHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Surface(
                onClick = onClick, shape = CircleShape,
                color = if (active) PrimaryPurpleLight else PrimaryPurple.copy(alpha = 0.3f)
            ) {
                Text(
                    text = if (active && !isPaused) "⏸" else "▶",
                    color = if (active) BackgroundDark else PrimaryPurpleLight,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun PlayingAnimation() {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(20.dp)) {
        repeat(3) { index ->
            val transition = rememberInfiniteTransition(label = "bar$index")
            val height by transition.animateFloat(
                initialValue = 4f, targetValue = 18f,
                animationSpec = infiniteRepeatable(tween(400 + index * 100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "h$index"
            )
            Box(modifier = Modifier.width(3.dp).height(height.dp).clip(RoundedCornerShape(1.5.dp)).background(Color.White))
        }
    }
}

@Composable
private fun EmptyStateView(message: String = "暂无音乐", hint: String = "") {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(32.dp)) {
            Text("🎵", style = MaterialTheme.typography.displayLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.titleLarge, color = TextSecondary)
            if (hint.isNotBlank()) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = TextHint, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun PlaybackFloatingBar(
    musicTitle: String,
    isPlaying: Boolean,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onStopClick: () -> Unit
) {
    var animatedProgress by remember { mutableStateOf(0f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                animatedProgress = (animatedProgress + 0.005f) % 1f
                kotlinx.coroutines.delay(50)
            }
        }
    }

    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).padding(bottom = 56.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().shadow(24.dp, RoundedCornerShape(20.dp), spotColor = PrimaryPurple.copy(alpha = 0.3f))
                .border(1.dp, PrimaryPurple.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xE61A1A2E)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    val transition = rememberInfiniteTransition(label = "spin")
                    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "rot")
                    Box(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(PrimaryPurple, SecondaryCyan))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🎵", modifier = Modifier.graphicsLayer { rotationZ = rotation }, style = MaterialTheme.typography.headlineSmall)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(PrimaryPurpleLight))
                            Text("正在演奏", style = MaterialTheme.typography.labelMedium, color = PrimaryPurpleLight, fontWeight = FontWeight.SemiBold)
                        }
                        Text(musicTitle, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = if (isPlaying) onPauseClick else onResumeClick,
                            shape = CircleShape,
                            color = PrimaryPurple.copy(alpha = if (isPlaying) 0.3f else 0.2f)
                        ) {
                            Text(
                                text = if (isPlaying) "⏸" else "▶",
                                style = MaterialTheme.typography.titleLarge,
                                color = PrimaryPurpleLight,
                                modifier = Modifier.padding(6.dp)
                            )
                        }

                        Surface(
                            onClick = onStopClick,
                            shape = CircleShape,
                            color = ErrorRed.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed.copy(alpha = 0.4f))
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "停止", tint = ErrorRed, modifier = Modifier.padding(10.dp).size(20.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(SurfaceLight)) {
                    Box(
                        modifier = Modifier.fillMaxWidth(animatedProgress).height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(Brush.horizontalGradient(listOf(PrimaryPurple, SecondaryCyan)))
                    )
                }
            }
        }
    }
}

@Composable
private fun AppSelectorDialog(
    apps: List<com.example.magicpinao.core.accessibility.TargetApp>,
    onAppSelected: (com.example.magicpinao.core.accessibility.TargetApp) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择目标钢琴应用（将启动悬浮标定）", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "使用独立「Genshin Piano」请选「原神钢琴」。选中后会自动弹出标定悬浮窗。",
                    color = TextHint,
                    style = MaterialTheme.typography.bodySmall
                )
                apps.forEach { app ->
                    Surface(
                        onClick = { onAppSelected(app) },
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceLight,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(PrimaryPurple, SecondaryCyan))), contentAlignment = Alignment.Center) {
                                Text("🎵", fontSize = 20.sp)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.appName, style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text(app.description, style = MaterialTheme.typography.bodySmall, color = TextHint)
                                Text("${app.keyCount} 键 · 点击开始标定", style = MaterialTheme.typography.labelSmall, color = SecondaryCyan)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } },
        containerColor = SurfaceDark,
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun PermissionDialog(permissionType: PermissionType, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val (title, message) = when (permissionType) {
        PermissionType.ACCESSIBILITY -> Pair(
            "需要开启无障碍服务",
            "Magic Piano 必须开启无障碍服务才能模拟点击琴键。\n\n请点击「去设置」→ 找到「Magic Piano」→ 打开开关。\n若已开启但仍提示，请先关闭再重新打开一次。"
        )
        PermissionType.OVERLAY -> Pair(
            "需要悬浮窗权限",
            "Magic Piano 需要悬浮窗权限，才能在「Genshin Piano / 原神 / 光遇」上层显示标定与播放控件。\n\n请点击「去设置」并允许「显示在其他应用上层」。\n\n若使用 MuMu 模拟器，还需关闭「应用后台保活」后重启模拟器。"
        )
        else -> Pair("需要权限", "请授予相关权限以使用完整功能。")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextPrimary) },
        text = { Text(message, color = TextSecondary) },
        confirmButton = { Button(onClick = onOpenSettings) { Text("去设置") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } },
        containerColor = SurfaceDark,
        tonalElevation = 8.dp
    )
}
