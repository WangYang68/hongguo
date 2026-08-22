package com.example.magicpinao.ui.screens.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.magicpinao.core.accessibility.SupportedApps
import com.example.magicpinao.core.auth.AuthManager
import com.example.magicpinao.core.data.MusicEntity
import com.example.magicpinao.core.data.PlaylistItemEntity
import com.example.magicpinao.core.network.MusicItem
import com.example.magicpinao.ui.theme.*
import com.example.magicpinao.ui.viewmodel.*

// Tab枚举
enum class HomeTab(val label: String, val icon: String) {
    LOCAL("本地音乐", "📁"),
    CLOUD("云音乐", "☁️"),
    PLAYLIST("播放列表", "🎵")
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
    val uiState by viewModel.uiState.collectAsState()
    val musicList by viewModel.musicList.collectAsState()
    val cloudMusicList by viewModel.cloudMusicList.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedTag by viewModel.selectedTag.collectAsState()
    val cloudSearchQuery by viewModel.cloudSearchQuery.collectAsState()
    val isCloudLoading by viewModel.isCloudLoading.collectAsState()
    val playlist by viewModel.playlist.collectAsState()

    var selectedTab by remember { mutableStateOf(HomeTab.LOCAL) }

    var showPermissionDialog by remember { mutableStateOf(false) }
    var permissionDialogType by remember { mutableStateOf(PermissionType.NONE) }
    var pendingCalibrationPackage by remember { mutableStateOf<String?>(null) }
    var pendingCalibrationKeyCount by remember { mutableIntStateOf(21) }
    var showVipExpiredDialog by remember { mutableStateOf(false) }
    var pendingCloudMusic by remember { mutableStateOf<MusicItem?>(null) }
    var pendingDeleteMusic by remember { mutableStateOf<MusicEntity?>(null) }

    val importMidiLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importMidiFiles(uris)
    }

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
                viewModel.dismissVipExpired()
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
                            viewModel.dismissVipExpired()
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
                                    viewModel.retryPendingLocalPlayback()
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
                    viewModel.dismissVipExpired()
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
                } ?: "选择键位",
                hasCoordinates = uiState.hasCoordinates,
                coordinateHint = when {
                    uiState.selectedAppPackage.isNullOrBlank() -> "请选择游戏"
                    uiState.hasCoordinates -> {
                        val app = uiState.selectedAppPackage?.let { SupportedApps.findByPackage(it)?.appName }
                        val scheme = uiState.coordinateSchemeName
                        when {
                            !scheme.isNullOrBlank() -> "● $scheme"
                            app != null -> "● $app 坐标已就绪"
                            else -> "● 坐标已就绪"
                        }
                    }
                    else -> "未设置音符坐标"
                },
                onSettingsClick = {
                    val pkg = uiState.selectedAppPackage
                    if (pkg.isNullOrBlank()) {
                        viewModel.toggleAppSelector()
                    } else {
                        viewModel.startCalibration(pkg)
                    }
                },
                onResetCoordinates = {
                    viewModel.resetCoordinatesAndCalibrate()
                },
                onAppSelectorClick = { viewModel.toggleAppSelector() },
                onRefreshClick = {
                    if (selectedTab == HomeTab.CLOUD) viewModel.loadCloudMusic(refresh = true)
                    else viewModel.scanLocalMidiFiles()
                    viewModel.refreshCoordinateStatus()
                },
                onOpenFloatingClick = { viewModel.openFloatingPlayer() },
                onProfileClick = onNavigateToProfile
            )

            // 根据当前Tab显示不同内容
            when (selectedTab) {
                HomeTab.LOCAL -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SearchBar(
                            query = searchQuery,
                            onQueryChange = viewModel::searchMusic,
                            modifier = Modifier.weight(1f),
                            placeholder = "搜索本地音乐..."
                        )
                        Surface(
                            onClick = {
                                importMidiLauncher.launch(
                                    arrayOf("audio/midi", "audio/mid", "audio/*", "*/*")
                                )
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = PrimaryPurple
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "导入",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text("导入", color = Color.White, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    TagFilterRow(
                        tags = tags,
                        selectedTag = selectedTag,
                        onTagClick = viewModel::selectTag
                    )
                    if (musicList.isEmpty()) {
                        EmptyStateView(
                            message = if (searchQuery.isBlank()) "暂无本地音乐" else "未找到匹配的音乐",
                            hint = if (searchQuery.isBlank()) "点击「导入」选择 MIDI 文件，数据保存在本机" else "试试其他关键词"
                        )
                    } else {
                        LocalMusicList(
                            musicList = musicList,
                            uiState = uiState,
                            onMusicClick = onMusicClick,
                            onDeleteClick = { pendingDeleteMusic = it },
                            onAddClick = { viewModel.addLocalToPlaylist(it) }
                        )
                    }
                }

                HomeTab.CLOUD -> {
                    // 云音乐搜索框
                    SearchBar(
                        query = cloudSearchQuery,
                        onQueryChange = { viewModel.searchCloudMusic(it) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = "搜索云音乐..."
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
                                pendingCloudMusic = music
                                onCloudMusicClick(music)
                            },
                            onAddClick = { viewModel.addCloudToPlaylist(it) },
                            onLoadMore = { viewModel.loadMoreCloudMusic() }
                        )
                    }
                }

                HomeTab.PLAYLIST -> {
                    if (playlist.isEmpty()) {
                        EmptyStateView(
                            message = "播放列表为空",
                            hint = "在本地音乐或云音乐中点击 + 添加到播放列表"
                        )
                    } else {
                        PlaylistList(
                            items = playlist,
                            onRemoveClick = { viewModel.removeFromPlaylist(it) }
                        )
                    }
                }
            }
        }

        // 播放控制条（本地音乐）
        AnimatedVisibility(
            visible = uiState.currentMusicId != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PlaybackFloatingBar(
                musicTitle = uiState.currentMusicTitle ?: "未知曲目",
                coordinateHint = uiState.coordinateSchemeName,
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
                            .clickable { selectedTab = tab }
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
                calibratedLayouts = uiState.calibratedLayoutTypes,
                onAppSelected = { app ->
                    viewModel.selectTargetApp(app.packageName)
                },
                onDismiss = { viewModel.toggleAppSelector() }
            )
        }

        // 未标定坐标引导
        if (uiState.showCalibrationPrompt) {
            val pkg = uiState.calibratingAppPackage ?: uiState.selectedAppPackage
            val app = pkg?.let { SupportedApps.findByPackage(it) }
            val appName = app?.appName ?: "当前键位"
            AlertDialog(
                onDismissRequest = { viewModel.dismissCalibrationPrompt() },
                title = { Text("需要先设置音符坐标", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "「$appName」还没有可用的音符坐标，无法播放。\n\n请先在游戏钢琴界面完成悬浮标定（${app?.keyCount ?: 21}键）后再播放。",
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
                    ) { Text("去设置坐标") }
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

        // 旧「请打开某软件」弹窗：直接进入标定
        if (uiState.showTargetAppPrompt) {
            LaunchedEffect(uiState.showTargetAppPrompt) {
                viewModel.confirmOpenTargetAppAndCalibrate()
            }
        }

        // VIP 过期（ViewModel 统一触发：本地/云端播放共用）
        LaunchedEffect(uiState.showVipExpired) {
            if (uiState.showVipExpired) {
                showVipExpiredDialog = true
            }
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

        pendingDeleteMusic?.let { music ->
            AlertDialog(
                onDismissRequest = { pendingDeleteMusic = null },
                title = { Text("删除本地音乐", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "确定删除「${music.title}」？文件会从本机资料库移除。",
                        color = TextHint
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteLocalMusic(music)
                            pendingDeleteMusic = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                    ) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteMusic = null }) {
                        Text("取消", color = TextHint)
                    }
                },
                containerColor = SurfaceDark,
                shape = RoundedCornerShape(20.dp)
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
    onMusicClick: (MusicEntity) -> Unit,
    onDeleteClick: (MusicEntity) -> Unit,
    onAddClick: (MusicEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(musicList, key = { it.id }) { music ->
            MusicCard(
                music = music,
                isCurrentlyPlaying = uiState.currentMusicId == music.id,
                isPaused = uiState.currentMusicId == music.id && !uiState.isPlaying,
                onClick = { onMusicClick(music) },
                onDeleteClick = { onDeleteClick(music) },
                onAddClick = { onAddClick(music) }
            )
        }
    }
}

@Composable
private fun PlaylistList(
    items: List<PlaylistItemEntity>,
    onRemoveClick: (PlaylistItemEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items, key = { it.id }) { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (item.source == "cloud") "☁️ 云音乐" else "📁 本地音乐",
                            style = MaterialTheme.typography.labelSmall,
                            color = PrimaryPurpleLight
                        )
                    }
                    IconButton(onClick = { onRemoveClick(item) }) {
                        Text("−", color = ErrorRed, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudMusicList(
    musicList: List<MusicItem>,
    isLoading: Boolean,
    onMusicClick: (MusicItem) -> Unit,
    onAddClick: (MusicItem) -> Unit,
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
                onClick = { onMusicClick(music) },
                onAddClick = { onAddClick(music) }
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
private fun CloudMusicCard(music: MusicItem, onClick: () -> Unit, onAddClick: () -> Unit) {
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

            IconButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "加入播放列表", tint = SecondaryCyan, modifier = Modifier.size(22.dp))
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
    onResetCoordinates: () -> Unit = {},
    onAppSelectorClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onOpenFloatingClick: () -> Unit,
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

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = onOpenFloatingClick,
                        shape = RoundedCornerShape(20.dp),
                        color = SecondaryCyan.copy(alpha = 0.18f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SecondaryCyan.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = null,
                                tint = SecondaryCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Text("悬浮窗", style = MaterialTheme.typography.labelMedium, color = SecondaryCyan)
                        }
                    }
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
            // 坐标状态条：显示当前是原神/光遇等，可重置
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
                if (hasCoordinates) {
                    Text(
                        text = "重置坐标",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningOrange,
                        modifier = Modifier.clickable(onClick = onResetCoordinates)
                    )
                } else if (selectedAppName != "选择键位") {
                    Text(
                        text = "去标定",
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
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索音乐..."
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
        placeholder = { Text(placeholder, color = TextHint) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextHint) },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "清除", tint = TextHint)
                }
            }
        },
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
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onAddClick: () -> Unit
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

            IconButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "加入播放列表", tint = SecondaryCyan, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = TextHint,
                    modifier = Modifier.size(20.dp)
                )
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
    coordinateHint: String? = null,
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
                            Text(
                                if (isPlaying) "正在演奏" else "已暂停",
                                style = MaterialTheme.typography.labelMedium,
                                color = PrimaryPurpleLight,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(musicTitle, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!coordinateHint.isNullOrBlank()) {
                            Text(
                                "坐标: $coordinateHint",
                                style = MaterialTheme.typography.labelSmall,
                                color = SecondaryCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
    calibratedLayouts: Set<String>,
    onAppSelected: (com.example.magicpinao.core.accessibility.TargetApp) -> Unit,
    onDismiss: () -> Unit
) {
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择键位方案", color = TextPrimary) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxH),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "按游戏钢琴键位选择 15 / 21 / 22 / 36 / 37 / 61 / 88 键。只需标定过对应键位坐标即可播放。下滑可查看全部方案。",
                        color = TextHint,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(apps, key = { it.packageName }) { app ->
                    val calibrated = app.layoutType in calibratedLayouts
                    Surface(
                        onClick = { onAppSelected(app) },
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceLight,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(PrimaryPurple, SecondaryCyan))),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🎵", fontSize = 16.sp)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        app.appName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (calibrated) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "✓已标定",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SuccessGreen
                                        )
                                    }
                                }
                                Text(app.description, style = MaterialTheme.typography.bodySmall, color = TextHint)
                                Text(
                                    "${app.keyCount} 键",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SecondaryCyan
                                )
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
            "Magic Piano 必须开启无障碍服务才能模拟点击琴键。\n\n点击「去设置」将尽量直接打开本应用的无障碍开关页，请打开「Magic Piano」。\n若未跳到本应用，请在列表中找到「Magic Piano」并打开。\n已开启但仍提示时，请先关闭再重新打开一次。"
        )
        PermissionType.OVERLAY -> Pair(
            "需要悬浮窗权限",
            "Magic Piano 需要悬浮窗权限，才能在游戏上层显示标定与播放控件。\n\n请点击「去设置」并允许「显示在其他应用上层」。\n\n若使用 MuMu 模拟器，还需关闭「应用后台保活」后重启模拟器。"
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
