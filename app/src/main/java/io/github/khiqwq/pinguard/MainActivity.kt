package io.github.khiqwq.pinguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private val moduleActive = mutableStateOf(false)
    private val needsReboot = mutableStateOf(false)
    private var pongReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val prefs = try {
            @Suppress("DEPRECATION")
            getSharedPreferences("config", Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            getSharedPreferences("config", Context.MODE_PRIVATE)
        }

        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        } catch (_: Exception) { -1 }
        pongReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                moduleActive.value = true
                val hookVersion = intent.getIntExtra("ver", -1)
                needsReboot.value = hookVersion != appVersion
            }
        }
        registerReceiver(
            pongReceiver,
            IntentFilter("io.github.khiqwq.pinguard.PONG"),
            Context.RECEIVER_EXPORTED
        )
        // Write sentinel for prefs readability check
        prefs.edit().putBoolean("_sentinel", true).commit()
        fixPerms()
        sendBroadcast(Intent("io.github.khiqwq.pinguard.PING").putExtra("pkg", packageName))

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val isActive by moduleActive
                val reboot by needsReboot
                SettingsScreen(
                    moduleActive = isActive,
                    needsReboot = reboot,
                    enabled = prefs.getBoolean("enabled", true),
                    bypassLockscreen = prefs.getBoolean("bypass_lockscreen", true),
                    blockScreenshot = prefs.getBoolean("block_screenshot", false),
                    blockAssistant = prefs.getBoolean("block_assistant", false),
                    hideExitToast = prefs.getBoolean("hide_exit_toast", false),
                    debugLog = prefs.getBoolean("debug_log", false),
                    onEnabledChange = { prefs.edit().putBoolean("enabled", it).commit(); fixPerms() },
                    onBypassLockscreenChange = { prefs.edit().putBoolean("bypass_lockscreen", it).commit(); fixPerms() },
                    onBlockScreenshotChange = { prefs.edit().putBoolean("block_screenshot", it).commit(); fixPerms() },
                    onAllowAssistantChange = { prefs.edit().putBoolean("block_assistant", it).commit(); fixPerms() },
                    onHideExitToastChange = { prefs.edit().putBoolean("hide_exit_toast", it).commit(); fixPerms() },
                    onDebugLogChange = { prefs.edit().putBoolean("debug_log", it).commit(); fixPerms() }
                )
            }
        }
    }

    private fun fixPerms() {
        try {
            val dir = File(applicationInfo.dataDir + "/shared_prefs")
            dir.setExecutable(true, false)
            dir.setReadable(true, false)
            File(dir, "config.xml").setReadable(true, false)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        pongReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }
}

@Composable
private fun ToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Text(subtitle, fontSize = 13.sp, color = Color.Gray)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    moduleActive: Boolean,
    needsReboot: Boolean = false,
    enabled: Boolean,
    bypassLockscreen: Boolean,
    blockScreenshot: Boolean,
    blockAssistant: Boolean,
    hideExitToast: Boolean,
    debugLog: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onBypassLockscreenChange: (Boolean) -> Unit,
    onBlockScreenshotChange: (Boolean) -> Unit,
    onAllowAssistantChange: (Boolean) -> Unit,
    onHideExitToastChange: (Boolean) -> Unit,
    onDebugLogChange: (Boolean) -> Unit
) {
    var isEnabled by remember { mutableStateOf(enabled) }
    var isBypassLockscreen by remember { mutableStateOf(bypassLockscreen) }
    var isBlockScreenshot by remember { mutableStateOf(blockScreenshot) }
    var isBlockAssistant by remember { mutableStateOf(blockAssistant) }
    var isHideExitToast by remember { mutableStateOf(hideExitToast) }
    var isDebugLog by remember { mutableStateOf(debugLog) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("PinGuard") }) },
        containerColor = Color(0xFFF8F8F8)
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))

            // Status — 3 states: active / needs reboot / inactive
            val statusBg: Color
            val statusDot: Color
            val statusTitle: String
            val statusDesc: String
            when {
                moduleActive && !needsReboot -> {
                    statusBg = Color(0xFFE8F5E9); statusDot = Color(0xFF4CAF50)
                    statusTitle = "模块已激活"; statusDesc = "Hook 已注入 system_server"
                }
                moduleActive && needsReboot -> {
                    statusBg = Color(0xFFFFF3E0); statusDot = Color(0xFFFF9800)
                    statusTitle = "需要重启"; statusDesc = "检测到注入的 Hook 版本不一致，请重启设备"
                }
                else -> {
                    statusBg = Color(0xFFFFEBEE); statusDot = Color(0xFFE53935)
                    statusTitle = "模块未激活"; statusDesc = "请在 LSPosed 中启用，作用域选「系统框架」，然后重启"
                }
            }
            Card(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = statusBg)
            ) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(12.dp).clip(CircleShape).background(statusDot))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(statusTitle, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        Text(statusDesc, fontSize = 13.sp, color = Color.Gray, lineHeight = 18.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            ToggleCard("启用保护", "取消应用固定时要求指纹/密码", isEnabled) {
                isEnabled = it; onEnabledChange(it)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "以下所有功能仅在作用域内的应用生效",
                fontSize = 12.sp, color = Color(0xFF999999),
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(Modifier.height(10.dp))

            ToggleCard("解锁不回锁屏", "验证后直接回桌面，跳过锁屏", isBypassLockscreen) {
                isBypassLockscreen = it; onBypassLockscreenChange(it)
            }

            Spacer(Modifier.height(12.dp))

            ToggleCard("禁用截图", "应用固定期间禁止截图（优先级高于 HyperCeiler 允许截屏）", isBlockScreenshot) {
                isBlockScreenshot = it; onBlockScreenshotChange(it)
            }

            Spacer(Modifier.height(12.dp))

            ToggleCard("禁用小白条召唤语音助手", "应用固定期间禁止通过小白条唤醒小爱同学（仅限小爱同学测试）", isBlockAssistant) {
                isBlockAssistant = it; onAllowAssistantChange(it)
            }

            Spacer(Modifier.height(12.dp))

            ToggleCard("隐藏退出提示", "屏蔽「如需取消固定此应用」提示", isHideExitToast) {
                isHideExitToast = it; onHideExitToastChange(it)
            }

            Spacer(Modifier.height(24.dp))

            Text("使用说明", fontWeight = FontWeight.Medium, fontSize = 15.sp,
                modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Text(
                    "1. 在 LSPosed 中启用本模块\n" +
                        "2. 作用域勾选「系统框架」+ 需要保护的应用\n" +
                        "3. 重启设备\n" +
                        "4. 回到本页确认绿灯亮起\n" +
                        "5. 固定受保护的应用后，取消固定需验证",
                    fontSize = 14.sp, lineHeight = 24.sp, color = Color(0xFF555555),
                    modifier = Modifier.padding(20.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "adb logcat | grep PinGuard",
                            fontSize = 13.sp, color = Color(0xFF777777)
                        )
                    }
                    Switch(checked = isDebugLog, onCheckedChange = {
                        isDebugLog = it; onDebugLogChange(it)
                    })
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
