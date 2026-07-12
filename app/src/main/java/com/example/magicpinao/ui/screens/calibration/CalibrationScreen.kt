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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.magicpinao.core.accessibility.FloatingCalibrationService
import com.example.magicpinao.core.accessibility.SupportedApps
import com.example.magicpinao.core.accessibility.TargetApp
import com.example.magicpinao.core.calibration.PianoLayoutType
import com.example.magicpinao.core.calibration.PianoLayouts
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
    var selectedLayout by remember { mutableStateOf(PianoLayoutType.LAYOUT_21) }
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
                "1. 选择钢琴布局（原神等为 21 键，光遇为 15 键）\n" +
                    "2. 选择目标应用，将自动打开并显示「开始标定」悬浮按钮\n" +
                    "3. 进入该应用的钢琴界面后点击按钮\n" +
                    "4. 拖动外框对齐全部琴键，必要时微调单个标记后点「完成」",
                color = TextHint,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "选择钢琴布局",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PianoLayoutType.entries.forEach { lt ->
                    val sel = lt == selectedLayout
                    Card(
                        Modifier
                            .weight(1f)
                            .clickable { selectedLayout = lt },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (sel) PrimaryPurple else SurfaceDark
                        ),
                        border = if (sel) BorderStroke(2.dp, PrimaryPurpleLight) else null
                    ) {
                        Column(
                            Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                when (lt) {
                                    PianoLayoutType.LAYOUT_21 -> "21键"
                                    PianoLayoutType.LAYOUT_36 -> "36键"
                                    PianoLayoutType.LAYOUT_15 -> "15键"
                                },
                                color = if (sel) Color.White else TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                lt.label,
                                fontSize = 11.sp,
                                color = if (sel) Color.White.copy(alpha = 0.7f) else TextHint
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "选择目标应用",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            SupportedApps.apps.forEach { app ->
                val cnt = schemesCount[app.packageName] ?: 0
                val layout = PianoLayouts.getLayout(selectedLayout)
                AppCalibrateCard(
                    app = app,
                    schemeCount = cnt,
                    onClick = {
                        FloatingCalibrationService.start(
                            context,
                            app.packageName,
                            layout.keyCount,
                            selectedLayout.name,
                            openTargetApp = true
                        )
                        Toast.makeText(
                            context,
                            "已启动标定：请进入「${app.appName}」钢琴界面点击「开始标定」",
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
