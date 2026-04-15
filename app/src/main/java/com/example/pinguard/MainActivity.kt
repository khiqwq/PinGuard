package com.example.pinguard

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

        pongReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                moduleActive.value = true
            }
        }
        registerReceiver(
            pongReceiver,
            IntentFilter("com.example.pinguard.PONG"),
            Context.RECEIVER_EXPORTED
        )
        sendBroadcast(Intent("com.example.pinguard.PING"))

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val isActive by moduleActive
                SettingsScreen(
                    moduleActive = isActive,
                    enabled = prefs.getBoolean("enabled", true),
                    debugLog = prefs.getBoolean("debug_log", false),
                    hideExitToast = prefs.getBoolean("hide_exit_toast", false),
                    onEnabledChange = { prefs.edit().putBoolean("enabled", it).apply(); fixPerms() },
                    onDebugLogChange = { prefs.edit().putBoolean("debug_log", it).apply(); fixPerms() },
                    onHideExitToastChange = { prefs.edit().putBoolean("hide_exit_toast", it).apply(); fixPerms() }
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
    enabled: Boolean,
    debugLog: Boolean,
    hideExitToast: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDebugLogChange: (Boolean) -> Unit,
    onHideExitToastChange: (Boolean) -> Unit
) {
    var isEnabled by remember { mutableStateOf(enabled) }
    var isDebugLog by remember { mutableStateOf(debugLog) }
    var isHideExitToast by remember { mutableStateOf(hideExitToast) }

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

            // Status
            Card(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (moduleActive) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                )
            ) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(
                        Modifier.size(12.dp).clip(CircleShape)
                            .background(if (moduleActive) Color(0xFF4CAF50) else Color(0xFFE53935))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (moduleActive) "模块已激活" else "模块未激活",
                            fontWeight = FontWeight.Medium, fontSize = 16.sp
                        )
                        Text(
                            if (moduleActive) "Hook 已注入 system_server"
                            else "请在 LSPosed 中启用并重启",
                            fontSize = 13.sp, color = Color.Gray
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            ToggleCard("启用保护", "取消应用固定时要求指纹/密码", isEnabled) {
                isEnabled = it; onEnabledChange(it)
            }

            Spacer(Modifier.height(12.dp))

            ToggleCard("隐藏退出提示", "屏蔽「如需取消固定此应用」提示", isHideExitToast) {
                isHideExitToast = it; onHideExitToastChange(it)
            }

            Spacer(Modifier.height(12.dp))

            ToggleCard("调试日志", "输出详细日志到 logcat", isDebugLog) {
                isDebugLog = it; onDebugLogChange(it)
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
                Text(
                    "adb logcat | grep PinGuard",
                    fontSize = 13.sp, color = Color.Gray,
                    modifier = Modifier.padding(20.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
