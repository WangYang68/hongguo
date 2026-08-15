package com.example.magicpinao.ui.screens.calibration

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.magicpinao.core.accessibility.FloatingCalibrationService
import com.example.magicpinao.core.accessibility.SupportedApps
import com.example.magicpinao.core.accessibility.TargetApp
import com.example.magicpinao.core.calibration.PianoLayoutType
import com.example.magicpinao.core.data.CoordinateRepository
import com.example.magicpinao.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 标定入口页：选择目标应用与布局，然后启动悬浮标定。
 * 真实屏幕坐标必须在目标钢琴界面上通过悬浮覆盖层完成，不能在本页用归一化坐标冒充像素。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    var schemesCount by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val repo = CoordinateRepository(context)
            val map = mutableMapOf<String, Int>()
            SupportedApps.apps.forEach { app ->
                val cnt = repo.getSchemesForPackage(app.packageName).size
                if (cnt > 0) map[app.packageName] = cnt
            }
            schemesCount = map
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("标定琴键坐标", color = TextPrimary, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        containerColor = BackgroundDark
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "使用说明",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "1. 选择键位方案（15 / 21 / 22 / 36 / 37）\n" +
                    "2. 手动打开游戏钢琴界面\n" +
                    "3. 点击方案卡片启动悬浮标定\n" +
                    "4. 拖动外框对齐全部琴键，必要时微调单个标记后点「完成」",
                color = TextHint,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "选择键位方案",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            SupportedApps.apps.forEach { app ->
                val cnt = schemesCount[app.packageName] ?: 0
                val lt = PianoLayoutType.fromName(app.layoutType)
                AppCalibrateCard(
                    app = app,
                    schemeCount = cnt,
                    onClick = {
                        FloatingCalibrationService.start(
                            context,
                            app.packageName,
                            app.keyCount,
                            lt.name,
                            openTargetApp = false
                        )
                        Toast.makeText(
                            context,
                            "已启动标定：请打开游戏钢琴界面后点击「开始标定」对齐 ${lt.label}",
                            Toast.LENGTH_LONG
                        ).show()
                        onBackClick()
                    }
                )
            }
        }
    }
}

@Composable
private fun AppCalibrateCard(app: TargetApp, schemeCount: Int, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(PrimaryPurple, SecondaryCyan))),
                contentAlignment = Alignment.Center
            ) {
                Text("🎹", fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.appName, color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(
                    "${app.keyCount}键 · ${app.description}",
                    color = TextHint,
                    fontSize = 13.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (schemeCount > 0) {
                    Text("$schemeCount 方案", color = SuccessGreen, fontSize = 11.sp)
                } else {
                    Text("未标定", color = ErrorRed, fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text("📍 悬浮标定", color = SecondaryCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
