package io.github.khiqwq.pinguard

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "PinGuard"
        const val REQ = 0x5047
        const val ACTION_SHOW_AUTH = "io.github.khiqwq.pinguard.SHOW_AUTH"
        const val ACTION_AUTH_SUCCESS = "io.github.khiqwq.pinguard.AUTH_SUCCESS"
        const val ACTION_REGISTER = "io.github.khiqwq.pinguard.REGISTER_APP"
        const val ACTION_PING = "io.github.khiqwq.pinguard.PING"
        const val ACTION_PONG = "io.github.khiqwq.pinguard.PONG"
        const val EXTRA_TOKEN = "t"
        const val EXTRA_PKG = "pkg"
        const val MAX_PROTECTED = 100
        const val TOAST_SUPPRESS_MS = 3000L
        const val KEYGUARD_SUPPRESS_MS = 5000L
        const val AUTH_TIMEOUT_MS = 60000L
        const val FIELD_PG = "pg"
        const val FIELD_PG_RECEIVER = "pg_r"
        const val FIELD_PG_HANDLED = "pg_handled"
        const val MODULE_VERSION = 7 // MUST match versionCode in build.gradle.kts
        const val SETTINGS_SUPPRESS_KEY = "pg_suppress_reshow_until"
        const val SETTINGS_SYSUI_READY_KEY = "pg_systemui_hooked"
        const val SETTINGS_PREFS_SAVED_KEY = "pg_prefs_saved"
        const val ACTION_PING_SYSUI = "io.github.khiqwq.pinguard.PING_SYSUI"
        const val ACTION_SYNC_SETTINGS = "io.github.khiqwq.pinguard.SYNC_SETTINGS"
        // KEYCODE_ASSIST=219, KEYCODE_VOICE_ASSIST=231, KEYCODE_SEARCH=84
        private val ASSISTANT_KEYCODES = intArrayOf(219, 231, 84)
    }

    // ── state ───────────────────────────────────────────────────────

    private val authPassed = AtomicBoolean(false)
    private val authPending = AtomicBoolean(false) // prevents token overwrite race
    private val suppressToastUntil = AtomicLong(0)
    @Volatile private var atmsRef: Any? = null
    @Volatile private var hookedMethodName: String? = null
    @Volatile private var handler: Handler? = null
    private var currentActivity: WeakReference<Activity>? = null
    // ConcurrentHashMap.newKeySet — lock-free reads on hot path
    private val protectedPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var debugLog = false

    @Volatile private var authToken: String? = null
    private val suppressKeyguardUntil = AtomicLong(0)

    // ── logging ─────────────────────────────────────────────────────

    private fun log(msg: String) {
        if (debugLog) XposedBridge.log("$TAG: $msg")
    }

    private fun logAlways(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }

    // ── prefs ───────────────────────────────────────────────────────

    // In-memory cached settings, kept in sync via ACTION_SYNC_SETTINGS
    // broadcast from MainActivity. XSharedPreferences returns empty in
    // system_server on HyperOS, so at boot we:
    //   1. Read from Settings.Global (populated by persistToSettings on every
    //      SYNC_SETTINGS receive).
    //   2. On first boot where marker is absent, parse the shared_prefs XML
    //      file directly — world-readable via fixPerms in MainActivity.
    @Volatile private var cachedEnabled = true
    @Volatile private var cachedBypass = true
    @Volatile private var cachedBlockScreenshot = false
    @Volatile private var cachedBlockAssistant = false
    @Volatile private var cachedHideExitToast = false
    @Volatile private var cachedDebugLog = false

    private fun loadInitialPrefs(ctx: Context) {
        // Try Settings.Global first — this is what SYNC_SETTINGS persists.
        val cr = ctx.contentResolver
        val globalLoaded = try {
            val marker = Settings.Global.getInt(cr, SETTINGS_PREFS_SAVED_KEY, 0)
            if (marker == 1) {
                cachedEnabled = Settings.Global.getInt(cr, "pg_enabled", 1) == 1
                cachedBypass = Settings.Global.getInt(cr, "pg_bypass_lockscreen", 1) == 1
                cachedBlockScreenshot = Settings.Global.getInt(cr, "pg_block_screenshot", 0) == 1
                cachedBlockAssistant = Settings.Global.getInt(cr, "pg_block_assistant", 0) == 1
                cachedHideExitToast = Settings.Global.getInt(cr, "pg_hide_exit_toast", 0) == 1
                cachedDebugLog = Settings.Global.getInt(cr, "pg_debug_log", 0) == 1
                debugLog = cachedDebugLog
                logAlways("loaded prefs from Settings.Global: blockSs=$cachedBlockScreenshot bypass=$cachedBypass enabled=$cachedEnabled")
                true
            } else false
        } catch (_: Exception) { false }

        if (!globalLoaded) {
            // Try direct XML file read — XSharedPreferences silently returns
            // empty on HyperOS, but system_server can read the world-readable
            // XML file directly (chmod'd by fixPerms in MainActivity).
            if (loadPrefsFromFile()) {
                persistToSettings(ctx) // snapshot into Settings.Global for next boot
            }
        }
    }

    private fun loadPrefsFromFile(): Boolean {
        return try {
            val f = java.io.File(
                "/data/data/io.github.khiqwq.pinguard/shared_prefs/config.xml")
            if (!f.exists() || !f.canRead()) return false
            val xml = f.readText()
            val re = Regex("""<boolean name="([^"]+)" value="(true|false)"""")
            var matched = false
            for (m in re.findAll(xml)) {
                matched = true
                val key = m.groupValues[1]
                val value = m.groupValues[2] == "true"
                when (key) {
                    "enabled" -> cachedEnabled = value
                    "bypass_lockscreen" -> cachedBypass = value
                    "block_screenshot" -> cachedBlockScreenshot = value
                    "block_assistant" -> cachedBlockAssistant = value
                    "hide_exit_toast" -> cachedHideExitToast = value
                    "debug_log" -> { cachedDebugLog = value; debugLog = value }
                }
            }
            if (matched) logAlways(
                "loaded prefs from XML file: blockSs=$cachedBlockScreenshot bypass=$cachedBypass enabled=$cachedEnabled")
            matched
        } catch (e: Exception) {
            log("loadPrefsFromFile fail: ${e.message}"); false
        }
    }

    private fun persistToSettings(ctx: Context) {
        try {
            val cr = ctx.contentResolver
            Settings.Global.putInt(cr, "pg_enabled", if (cachedEnabled) 1 else 0)
            Settings.Global.putInt(cr, "pg_bypass_lockscreen", if (cachedBypass) 1 else 0)
            Settings.Global.putInt(cr, "pg_block_screenshot", if (cachedBlockScreenshot) 1 else 0)
            Settings.Global.putInt(cr, "pg_block_assistant", if (cachedBlockAssistant) 1 else 0)
            Settings.Global.putInt(cr, "pg_hide_exit_toast", if (cachedHideExitToast) 1 else 0)
            Settings.Global.putInt(cr, "pg_debug_log", if (cachedDebugLog) 1 else 0)
            Settings.Global.putInt(cr, SETTINGS_PREFS_SAVED_KEY, 1)
        } catch (e: Exception) { log("persistToSettings fail: ${e.message}") }
    }

    private fun isEnabled(): Boolean = cachedEnabled
    private fun isHideExitToast(): Boolean = cachedHideExitToast
    private fun isBypassLockscreen(): Boolean = cachedBypass
    private fun isBlockScreenshot(): Boolean = cachedBlockScreenshot
    private fun isBlockAssistant(): Boolean = cachedBlockAssistant

    // ── entry ───────────────────────────────────────────────────────

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when {
            lpparam.packageName == "android" && lpparam.processName == "android" ->
                hookSystemServer(lpparam)
            lpparam.packageName == "com.android.systemui" ->
                hookSystemUI(lpparam)
            lpparam.packageName != "io.github.khiqwq.pinguard" ->
                hookTargetApp(lpparam)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SystemUI hooks — suppress KVM reshow after unpin
    // ═══════════════════════════════════════════════════════════════

    private fun hookSystemUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        logAlways("hookSystemUI")

        // Delayed: once Application is ready, register a PING_SYSUI receiver.
        // MainActivity broadcasts PING_SYSUI and checks if the heartbeat
        // timestamp was refreshed within the ping window — reliable even
        // after SysUI-only restarts (without full device reboot).
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val at = Class.forName("android.app.ActivityThread")
                val app = at.getMethod("currentApplication").invoke(null) as? android.app.Application
                    ?: return@postDelayed
                val cr = app.contentResolver
                Settings.Global.putLong(
                    cr, SETTINGS_SYSUI_READY_KEY, System.currentTimeMillis())
                app.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        try {
                            Settings.Global.putLong(
                                c.contentResolver,
                                SETTINGS_SYSUI_READY_KEY,
                                System.currentTimeMillis())
                        } catch (_: Exception) {}
                    }
                }, IntentFilter(ACTION_PING_SYSUI), null, null, Context.RECEIVER_EXPORTED)
                log("SysUI PING receiver registered")
            } catch (e: Exception) { log("SysUI receiver fail: ${e.message}") }
        }, 3000)

        val kvmClass = try {
            XposedHelpers.findClass(
                "com.android.systemui.keyguard.KeyguardViewMediator", lpparam.classLoader)
        } catch (e: Exception) {
            logAlways("KVM class not found: ${e.message}"); return
        }

        // Walk up class hierarchy to collect all methods
        val allMethods = mutableListOf<java.lang.reflect.Method>()
        var c: Class<*>? = kvmClass
        while (c != null && c != Any::class.java) {
            allMethods.addAll(c.declaredMethods)
            c = c.superclass
        }

        // Hook suspect methods that can trigger keyguard show
        // Key strategy: cover all re-enable/show paths on HyperOS KVM
        var hookedCount = 0
        val showBlocker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Outer try/catch: never let an exception propagate into KVM —
                // a crash here can destabilize SystemUI / lockscreen state.
                try {
                    if (!shouldSuppressReshow(param.thisObject)) return
                    if (param.method.name.startsWith("setKeyguardEnabled") &&
                        param.args.isNotEmpty() && param.args[0] == true) {
                        try {
                            XposedHelpers.setBooleanField(
                                param.thisObject, "mNeedToReshowWhenReenabled", false)
                            log("KVM: cleared reshow flag in ${param.method.name}")
                        } catch (_: Exception) {}
                        return
                    }
                    param.setResult(null)
                    log("KVM: skipped ${param.method.name}")
                } catch (e: Throwable) {
                    logAlways("KVM hook fault in ${param.method.name}: ${e.message}")
                }
            }
        }

        val targetNames = setOf(
            "setKeyguardEnabled",
            "doKeyguardLocked",
            "doKeyguardForChildProfilesLocked",
            "showLocked",
            "handleShow",
            "showKeyguard",
            "maybeHandlePendingLock")

        // Diagnostic (debug_log only): list KVM methods matching keyguard/show/lock
        // patterns — useful when HyperOS renames/adds methods in future updates.
        if (debugLog) {
            val suspectNames = allMethods.asSequence()
                .map { it.name }
                .filter { it.contains("eyguard", true) || it.contains("how", true) || it.contains("ock", true) }
                .distinct().sorted().toList()
            log("KVM suspect methods (${suspectNames.size}): ${suspectNames.joinToString(",")}")
        }

        for (method in allMethods) {
            if (method.name !in targetNames) continue
            if (java.lang.reflect.Modifier.isAbstract(method.modifiers)) continue
            try {
                XposedBridge.hookMethod(method, showBlocker)
                logAlways("hooked KVM.${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }}) ✓")
                hookedCount++
            } catch (e: Exception) {
                log("hook ${method.name} fail: ${e.message}")
            }
        }
        logAlways("KVM hooks installed: $hookedCount")
    }

    // Suppress keyguard show/doKeyguardLocked in two cases:
    //   (a) post-unpin 5-sec window (`SETTINGS_SUPPRESS_KEY` timestamp)
    //   (b) pin mode is active AND bypass_lockscreen is enabled — this
    //       prevents mNeedToReshowWhenReenabled from ever being set during
    //       screen-off-while-pinned, which (when cleared on unpin) breaks
    //       the downstream signal chain MIUI clipboard service listens to.
    private fun shouldSuppressReshow(kvm: Any): Boolean {
        return try {
            val ctx = XposedHelpers.getObjectField(kvm, "mContext") as Context
            val cr = ctx.contentResolver
            val until = Settings.Global.getLong(cr, SETTINGS_SUPPRESS_KEY, 0L)
            if (System.currentTimeMillis() < until) return true
            // Case (b): in pin + bypass, keep keyguard fully dormant.
            val bypass = Settings.Global.getInt(cr, "pg_bypass_lockscreen", 1) == 1
            if (!bypass) return false
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am != null && am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
        } catch (_: Exception) { false }
    }

    // ═══════════════════════════════════════════════════════════════
    //  system_server hooks
    // ═══════════════════════════════════════════════════════════════

    private fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        logAlways("=== init ===")

        val atmsClass = try {
            XposedHelpers.findClass(
                "com.android.server.wm.ActivityTaskManagerService", lpparam.classLoader)
        } catch (e: Exception) {
            logAlways("ATMS not found: ${e.message}"); return
        }

        // 1. onSystemReady
        try {
            XposedHelpers.findAndHookMethod(atmsClass, "onSystemReady",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            atmsRef = param.thisObject
                            handler = Handler(Looper.getMainLooper())
                            val ctx = XposedHelpers.getObjectField(
                                param.thisObject, "mContext") as Context
                            // Clear any stale suppress-reshow signal left over from
                            // a crash or reboot during unpin. Also clear the SysUI
                            // heartbeat — if SysUI scope is still enabled, it will
                            // rewrite a fresh timestamp 3s after its hook loads.
                            try {
                                Settings.Global.putLong(
                                    ctx.contentResolver, SETTINGS_SUPPRESS_KEY, 0L)
                                Settings.Global.putLong(
                                    ctx.contentResolver, SETTINGS_SYSUI_READY_KEY, 0L)
                            } catch (_: Exception) {}
                            registerReceivers(ctx)
                            logAlways("ready")
                        } catch (e: Throwable) {
                            logAlways("onSystemReady FAIL: ${e.message}")
                        }
                    }
                })
        } catch (e: Exception) {
            logAlways("onSystemReady fail: ${e.message}")
        }

        // 2. Unpin interception
        val unpinHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                log(">>> ${param.method.name}")

                if (!isEnabled()) { log("disabled"); return }
                if (authPassed.compareAndSet(true, false)) { log("auth OK"); return }

                // If auth is already pending, just block (don't overwrite token)
                if (authPending.get()) {
                    log("auth pending, block")
                    param.setResult(null)
                    return
                }

                val pinnedPkg = getPinnedAppPackage(param.thisObject)
                if (pinnedPkg == null || pinnedPkg !in protectedPackages) {
                    log("not protected ($pinnedPkg)"); return
                }

                hookedMethodName = param.method.name
                if (atmsRef == null) atmsRef = param.thisObject

                val ctx = try {
                    XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                } catch (_: Exception) { return }

                // Generate one-time auth token + set pending flag
                val token = UUID.randomUUID().toString()
                authToken = token
                authPending.set(true)

                val callingId = Binder.clearCallingIdentity()
                try {
                    ctx.sendBroadcast(
                        Intent(ACTION_SHOW_AUTH)
                            .setPackage(pinnedPkg)
                            .putExtra(EXTRA_TOKEN, token)
                    )
                    if (isHideExitToast()) {
                        suppressToastUntil.set(System.currentTimeMillis() + TOAST_SUPPRESS_MS)
                    }
                    log("blocked")
                    param.setResult(null)
                } catch (e: Exception) {
                    authPending.set(false)
                    log("broadcast fail: ${e.message}")
                } finally {
                    Binder.restoreCallingIdentity(callingId)
                }
            }
        }

        for (m in listOf("stopSystemLockTaskMode", "stopLockTaskModeOnCurrent")) {
            try {
                XposedHelpers.findAndHookMethod(atmsClass, m, unpinHook)
                logAlways("hooked $m ✓")
            } catch (_: NoSuchMethodError) {} catch (e: Exception) {
                log("$m fail: ${e.message}")
            }
        }

        // 3. Prevent keyguard RESHOW after unpin.
        //
        // Background: performStopLockTask → setKeyguardState(NONE) → reenableKeyguard
        //   → PhoneWindowManager.enableKeyguard(true) → KeyguardServiceDelegate
        //   → KVM.setKeyguardEnabled(true) → sets mExternallyEnabled = true
        //
        // Old (broken) approach: blocked enableKeyguard(true) at PWM level. This left
        //   mExternallyEnabled = false permanently, stalling KeyguardServiceDelegate's
        //   disabler-token state. HyperOS MIUI services (clipboard/brightness) that
        //   re-latch pinned state on screen-on events then never received a clean
        //   LOCK_TASK_MODE_NONE refresh.
        //
        // New approach: let enableKeyguard(true) flow through for state sync. Block
        //   only the KVM show path (KVM.doKeyguardLocked) in SystemUI scope — OR
        //   dismiss keyguard after it shows. For now, rely on the fact that on a
        //   live/unlocked device, enableKeyguard(true) only sets flags and does NOT
        //   immediately show the keyguard UI; reshow happens on next screen-off.
        //
        // Keep shouldLockKeyguard hook (AOSP fallback path: lockKeyguardIfNeeded → lockNow)
        try {
            val ltcClass = XposedHelpers.findClass(
                "com.android.server.wm.LockTaskController", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(ltcClass, "shouldLockKeyguard",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isBypassLockscreen()) return
                        if (System.currentTimeMillis() < suppressKeyguardUntil.get()) {
                            param.setResult(false)
                            log("shouldLockKeyguard → false")
                        }
                    }
                })
            logAlways("hooked shouldLockKeyguard ✓")
        } catch (e: Exception) {
            log("shouldLockKeyguard hook fail: ${e.message}")
        }

        // 4. Toast suppression (time-window + one-shot)
        try {
            XposedHelpers.findAndHookMethod("android.widget.Toast", lpparam.classLoader,
                "show", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val deadline = suppressToastUntil.get()
                        if (deadline == 0L || System.currentTimeMillis() > deadline) return
                        suppressToastUntil.set(0)
                        log("suppressed toast")
                        param.setResult(null)
                    }
                })
        } catch (_: Exception) {}

        // 5. Screenshot blocking — defeats HyperCeiler "allow FLAG_SECURE" bypass.
        //
        // HyperCeiler (system-framework scope) hooks:
        //   WindowState.isSecureLocked          → returns false (beforeHook replace)
        //   WindowManagerServiceImpl.notAllowCaptureDisplay → returns false
        //   ScreenCapture.nativeCaptureDisplay  → CaptureArgs.mCaptureSecureLayers = true
        //   ScreenCapture.nativeCaptureLayers   → same
        //
        // Strategy:
        //   (A) HIGHEST-priority afterHook on isSecureLocked / notAllowCaptureDisplay
        //       → runs LAST in after chain, wins setResult(true)
        //   (B) LOWEST-priority beforeHook on ScreenCapture.nativeCaptureDisplay/Layers
        //       → runs LAST in before chain, resets mCaptureSecureLayers=false
        //       after HyperCeiler set it to true
        //   (C) HIGHEST-priority beforeHook on WindowManagerService.captureDisplay
        //       → earliest choke point, block outright

        val PRIO_HIGH = 10000   // after chain: runs LAST; before chain: runs FIRST
        val PRIO_LOW = -10000   // after chain: runs FIRST; before chain: runs LAST

        // (A) WindowState.isSecureLocked — override HyperCeiler's false
        try {
            val wsClass = XposedHelpers.findClass(
                "com.android.server.wm.WindowState", lpparam.classLoader)
            for (method in wsClass.declaredMethods) {
                if (method.name == "isSecureLocked") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook(PRIO_HIGH) {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                if (isBlockScreenshot() && isInLockTaskWithProtectedApp()) {
                                    param.setResult(true)
                                }
                            } catch (_: Throwable) {}
                        }
                    })
                    logAlways("hooked WindowState.isSecureLocked (PRIO_HIGH after) ✓")
                }
            }
        } catch (_: Throwable) {}

        // (A) HyperOS-specific notAllowCaptureDisplay
        try {
            val wmsImpl = XposedHelpers.findClass(
                "com.android.server.wm.WindowManagerServiceImpl", lpparam.classLoader)
            for (method in wmsImpl.declaredMethods) {
                if (method.name == "notAllowCaptureDisplay") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook(PRIO_HIGH) {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                if (isBlockScreenshot() && isInLockTaskWithProtectedApp()) {
                                    param.setResult(true)
                                    log("notAllowCaptureDisplay → true")
                                }
                            } catch (_: Throwable) {}
                        }
                    })
                    logAlways("hooked notAllowCaptureDisplay (PRIO_HIGH after) ✓")
                }
            }
        } catch (_: Throwable) {}

        // (C) WindowManagerService.captureDisplay — earliest choke point.
        // Also hook WindowManagerServiceImpl (HyperOS MIUI wrapper) — it may
        // override captureDisplay with different dispatch semantics.
        for (wmsClsName in listOf(
            "com.android.server.wm.WindowManagerService",
            "com.android.server.wm.WindowManagerServiceImpl"
        )) {
            try {
                val wmsClass = XposedHelpers.findClass(wmsClsName, lpparam.classLoader)
                for (method in wmsClass.declaredMethods) {
                    if (method.name == "captureDisplay") {
                        XposedBridge.hookMethod(method, object : XC_MethodHook(PRIO_HIGH) {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    if (isBlockScreenshot() && isInLockTaskWithProtectedApp()) {
                                        param.setResult(null)
                                        log("blocked ${param.method.declaringClass.simpleName}.captureDisplay")
                                    }
                                } catch (e: Throwable) { log("captureDisplay fault: ${e.message}") }
                            }
                        })
                        logAlways("hooked ${wmsClsName.substringAfterLast('.')}.captureDisplay (PRIO_HIGH before) ✓")
                    }
                }
            } catch (_: Throwable) {}
        }

        // (B) ScreenCapture native — LOWEST priority beforeHook so our field
        // write runs AFTER HyperCeiler's. Field writes are sequential; last wins.
        try {
            val scClass = XposedHelpers.findClass(
                "android.window.ScreenCapture", lpparam.classLoader)
            val scSanitizer = object : XC_MethodHook(PRIO_LOW) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!isBlockScreenshot() || !isInLockTaskWithProtectedApp()) return
                        val captureArgs = param.args.firstOrNull() ?: return
                        try {
                            XposedHelpers.setBooleanField(
                                captureArgs, "mCaptureSecureLayers", false)
                        } catch (_: Exception) {}
                        try {
                            XposedHelpers.setIntField(
                                captureArgs, "mSecureContentPolicy", 0)
                        } catch (_: Exception) {}
                        log("ScreenCapture.${param.method.name}: forced secure exclusion")
                    } catch (e: Throwable) { log("SC hook fault: ${e.message}") }
                }
            }
            for (method in scClass.declaredMethods) {
                if (method.name in setOf("nativeCaptureDisplay", "nativeCaptureLayers",
                        "captureDisplay", "captureLayers")) {
                    try {
                        XposedBridge.hookMethod(method, scSanitizer)
                        logAlways("hooked ScreenCapture.${method.name} (PRIO_LOW before) ✓")
                    } catch (e: Throwable) { log("SC ${method.name} fail: ${e.message}") }
                }
            }
        } catch (_: Throwable) {}

        // 5. Voice assistant control (小爱同学) during lock task
        // Hook LockTaskController to allow assistant packages
        try {
            val ltcClass = XposedHelpers.findClass(
                "com.android.server.wm.LockTaskController", lpparam.classLoader)
            for (method in ltcClass.declaredMethods) {
                if (method.name == "isPackageAllowlisted") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val pkg = try { param.args.first() as? String } catch (_: Exception) { null } ?: return
                            if (!isXiaoAiPackage(pkg)) return
                            if (isBlockAssistant()) {
                                log("blocked assistant pkg: $pkg")
                                param.setResult(false)
                            } else {
                                log("allowed assistant pkg: $pkg")
                                param.setResult(true)
                            }
                        }
                    })
                    logAlways("hooked isPackageAllowlisted ✓")
                }
            }
        } catch (e: Exception) { log("allowlist hook: ${e.message}") }

        // Hook MiuiPhoneWindowManager/BaseMiuiPhoneWindowManager for HyperOS
        val miuiPwmNames = listOf(
            "com.android.server.policy.MiuiPhoneWindowManager",
            "com.android.server.policy.BaseMiuiPhoneWindowManager"
        )
        for (clsName in miuiPwmNames) {
            try {
                val cls = XposedHelpers.findClass(clsName, lpparam.classLoader)
                for (method in cls.declaredMethods) {
                    if (method.name == "interceptKeyBeforeDispatching") {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                // Fast-path: check keyCode FIRST (cheap) before any
                                // reflection-heavy isInLockTaskWithProtectedApp check.
                                var event: android.view.KeyEvent? = null
                                for (a in param.args) {
                                    if (a is android.view.KeyEvent) { event = a; break }
                                }
                                val kc = event?.keyCode ?: return
                                var matched = false
                                for (code in ASSISTANT_KEYCODES) {
                                    if (code == kc) { matched = true; break }
                                }
                                if (!matched) return
                                if (!isInLockTaskWithProtectedApp()) return
                                if (isBlockAssistant()) {
                                    log("blocked assistant key $kc")
                                    param.setResult(-1L) // consume = block
                                } else {
                                    log("allowed assistant key $kc")
                                    param.setResult(0L) // don't consume = allow
                                }
                            }
                        })
                        logAlways("hooked $clsName.interceptKeyBeforeDispatching ✓")
                    }
                    // Hook launchAssistAction if it exists
                    if (method.name == "launchAssistAction") {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isInLockTaskWithProtectedApp()) return
                                if (isBlockAssistant()) {
                                    log("blocked launchAssistAction")
                                    param.setResult(null)
                                } else {
                                    log("allowed launchAssistAction")
                                }
                            }
                        })
                    }
                }
            } catch (_: Exception) {} // Class may not exist on non-MIUI
        }

        // Hook ShortCutActionsUtils.triggerFunction for 小白条 double-tap
        try {
            val scaClass = XposedHelpers.findClass(
                "com.miui.server.input.util.ShortCutActionsUtils", lpparam.classLoader)
            for (method in scaClass.declaredMethods) {
                if (method.name == "triggerFunction") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isInLockTaskWithProtectedApp()) return
                            val func = param.args.firstOrNull() as? String ?: return
                            if (func == "launch_voice_assistant" || func == "launch_ai_shortcut") {
                                if (isBlockAssistant()) {
                                    log("blocked $func")
                                    param.setResult(null)
                                } else {
                                    log("allowed $func")
                                }
                            }
                        }
                    })
                    logAlways("hooked ShortCutActionsUtils.triggerFunction ✓")
                }
            }
        } catch (_: Exception) {} // Non-MIUI devices won't have this
    }

    private fun isXiaoAiPackage(pkg: String): Boolean =
        pkg == "com.miui.voiceassist" || pkg == "com.xiaomi.voiceassistant" ||
        pkg.contains("mibrain") || pkg.contains("aiasst") || pkg.contains("voiceassist")

    // ── lock task state check ───────────────────────────────────────

    // Cached reflection Fields — populated lazily on first access. ~5-10x
    // faster than XposedHelpers.getObjectField (which does synchronized
    // HashMap lookups on every call).
    @Volatile private var fldLockTaskController: java.lang.reflect.Field? = null
    @Volatile private var fldLockTaskModeState: java.lang.reflect.Field? = null
    @Volatile private var fldLockTaskModeTasks: java.lang.reflect.Field? = null
    @Volatile private var fldTaskRealActivity: java.lang.reflect.Field? = null
    @Volatile private var fldTaskIntent: java.lang.reflect.Field? = null

    private fun resolveField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        return try {
            var c: Class<*>? = cls
            while (c != null) {
                try {
                    val f = c.getDeclaredField(name)
                    f.isAccessible = true
                    return f
                } catch (_: NoSuchFieldException) { c = c.superclass }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun isInLockTaskWithProtectedApp(): Boolean {
        val atms = atmsRef ?: return false
        return try {
            val ltcField = fldLockTaskController
                ?: resolveField(atms.javaClass, "mLockTaskController")?.also { fldLockTaskController = it }
                ?: return false
            val ltc = ltcField.get(atms) ?: return false
            val stateField = fldLockTaskModeState
                ?: resolveField(ltc.javaClass, "mLockTaskModeState")?.also { fldLockTaskModeState = it }
                ?: return false
            if (stateField.getInt(ltc) == 0) return false
            val pkg = getPinnedAppPackageFast(ltc) ?: return false
            pkg in protectedPackages
        } catch (_: Throwable) { false }
    }

    // ── protected package tracking ──────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun getPinnedAppPackageFast(ltc: Any): String? {
        return try {
            val tasksField = fldLockTaskModeTasks
                ?: resolveField(ltc.javaClass, "mLockTaskModeTasks")?.also { fldLockTaskModeTasks = it }
                ?: return null
            val tasks = tasksField.get(ltc) as? ArrayList<Any>
            if (tasks.isNullOrEmpty()) null else getTaskPackage(tasks.last())
        } catch (_: Throwable) { null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getPinnedAppPackage(atms: Any): String? {
        return try {
            val ltc = XposedHelpers.getObjectField(atms, "mLockTaskController")
            getPinnedAppPackageFast(ltc)
        } catch (_: Exception) { null }
    }

    private fun getTaskPackage(task: Any): String? {
        try {
            val raField = fldTaskRealActivity
                ?: resolveField(task.javaClass, "realActivity")?.also { fldTaskRealActivity = it }
            if (raField != null) {
                val comp = raField.get(task) as? ComponentName
                if (comp != null) return comp.packageName
            }
        } catch (_: Throwable) {}
        return try {
            val iField = fldTaskIntent
                ?: resolveField(task.javaClass, "intent")?.also { fldTaskIntent = it }
            (iField?.get(task) as? Intent)?.component?.packageName
        } catch (_: Throwable) { null }
    }

    // ── broadcast receivers (system_server) ─────────────────────────

    private fun registerReceivers(ctx: Context) {
        val h = handler ?: return
        loadInitialPrefs(ctx)

        // Settings sync — MainActivity broadcasts on startup and on every toggle.
        // Replaces XSharedPreferences (unreliable on HyperOS). Also persists to
        // Settings.Global so the values survive reboot without the user having
        // to reopen the app.
        //
        // NOTE on security: we can't reliably verify the sender UID inside a
        // handler-dispatched BroadcastReceiver (Binder identity is lost by the
        // time onReceive runs on our handler). A malicious app could spoof
        // SYNC_SETTINGS with enabled=false. Mitigation: MainActivity re-sends
        // on every Activity resume, so the state resets whenever the user
        // opens PinGuard. Full fix requires a signature-level custom permission.
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val prev = cachedBlockScreenshot
                cachedEnabled = i.getBooleanExtra("enabled", cachedEnabled)
                cachedBypass = i.getBooleanExtra("bypass_lockscreen", cachedBypass)
                cachedBlockScreenshot = i.getBooleanExtra("block_screenshot", cachedBlockScreenshot)
                cachedBlockAssistant = i.getBooleanExtra("block_assistant", cachedBlockAssistant)
                cachedHideExitToast = i.getBooleanExtra("hide_exit_toast", cachedHideExitToast)
                cachedDebugLog = i.getBooleanExtra("debug_log", cachedDebugLog)
                debugLog = cachedDebugLog
                persistToSettings(c)
                logAlways("SYNC_SETTINGS rcvd: blockSs $prev→$cachedBlockScreenshot bypass=$cachedBypass enabled=$cachedEnabled")
            }
        }, IntentFilter(ACTION_SYNC_SETTINGS), null, h, Context.RECEIVER_EXPORTED)

        // PING/PONG — respond with targeted PONG (safe: just reports module status)
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val sender = i.getStringExtra(EXTRA_PKG)
                val pong = Intent(ACTION_PONG).putExtra("ver", MODULE_VERSION)
                if (sender != null) pong.setPackage(sender)
                c.sendBroadcast(pong)
            }
        }, IntentFilter(ACTION_PING), null, h, Context.RECEIVER_EXPORTED)

        // App registration — capped to prevent DoS. Cannot reliably verify
        // sender UID inside handler-dispatched receiver (see SYNC_SETTINGS
        // note above), so any app can register an arbitrary package. Worst
        // case: protectedPackages fills with junk up to MAX_PROTECTED; users
        // can work around by reinstalling or rebooting.
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (protectedPackages.size >= MAX_PROTECTED) return
                val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
                if (protectedPackages.add(pkg)) log("protected +$pkg")
            }
        }, IntentFilter(ACTION_REGISTER), null, h, Context.RECEIVER_EXPORTED)

        // Auth success / cancelled — validate one-time token
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val t = intent.getStringExtra(EXTRA_TOKEN)
                // Handle cancellation — reset pending so user can retry immediately
                if (t == "cancelled") {
                    authToken = null
                    authPending.set(false)
                    log("AUTH cancelled, reset")
                    return
                }
                val expected = authToken
                if (t == null || expected == null || t != expected) {
                    logAlways("AUTH rejected: bad token")
                    return
                }
                authToken = null
                authPending.set(false)
                log("AUTH verified")
                if (isBypassLockscreen()) {
                    h.post { manualUnpin() }
                } else {
                    // Normal flow — shows lock screen
                    authPassed.set(true)
                    h.post {
                        try { XposedHelpers.callMethod(
                            atmsRef, hookedMethodName ?: "stopSystemLockTaskMode") }
                        catch (_: Exception) {}
                    }
                }
            }
        }, IntentFilter(ACTION_AUTH_SUCCESS), null, h, Context.RECEIVER_EXPORTED)

        // Auth timeout — unconditionally reset pending state every 60s.
        // Worst case: a legitimate in-progress auth is cancelled at T=60s from
        // the poll's last fire. Acceptable for the current UX; a per-token
        // timestamp would be more precise but adds complexity.
        h.postDelayed(object : Runnable {
            override fun run() {
                if (authPending.get() && authToken != null) {
                    authPending.set(false)
                    authToken = null
                    log("auth timeout, reset")
                }
                h.postDelayed(this, AUTH_TIMEOUT_MS)
            }
        }, AUTH_TIMEOUT_MS)
    }

    // ═══════════════════════════════════════════════════════════════
    //  target app hooks
    // ═══════════════════════════════════════════════════════════════

    @Volatile private var pendingAuthToken: String? = null

    private fun hookTargetApp(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("hookTarget ${lpparam.packageName}")

        XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
            "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    currentActivity = WeakReference(activity)

                    activity.sendBroadcast(
                        Intent(ACTION_REGISTER).putExtra(EXTRA_PKG, activity.packageName)
                    )

                    // FLAG_SECURE for screenshot blocking during pin
                    updateFlagSecure(activity)

                    if (XposedHelpers.getAdditionalInstanceField(activity, FIELD_PG) != null) return
                    XposedHelpers.setAdditionalInstanceField(activity, FIELD_PG, true)

                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            pendingAuthToken = intent.getStringExtra(EXTRA_TOKEN)
                            val act = currentActivity?.get()
                            if (act == null) {
                                // Activity GC'd (e.g. after screen off/on) — cancel to reset authPending
                                ctx.sendBroadcast(
                                    Intent(ACTION_AUTH_SUCCESS)
                                        .setPackage("android")
                                        .putExtra(EXTRA_TOKEN, "cancelled"))
                                log("SHOW_AUTH: activity null, cancelled")
                                return
                            }
                            log("SHOW_AUTH → ${act.javaClass.name}")
                            showAuth(act)
                        }
                    }
                    XposedHelpers.setAdditionalInstanceField(activity, FIELD_PG_RECEIVER, receiver)
                    activity.registerReceiver(
                        receiver, IntentFilter(ACTION_SHOW_AUTH), Context.RECEIVER_EXPORTED)
                }
            })

        XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
            "onActivityResult",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] as Int != REQ) return
                    val activity = param.thisObject as Activity
                    val tag = FIELD_PG_HANDLED
                    if (XposedHelpers.getAdditionalInstanceField(activity, tag) != null) {
                        XposedHelpers.removeAdditionalInstanceField(activity, tag)
                        return
                    }
                    XposedHelpers.setAdditionalInstanceField(activity, tag, true)
                    val token = pendingAuthToken
                    pendingAuthToken = null
                    if (param.args[1] as Int == Activity.RESULT_OK && token != null) {
                        activity.sendBroadcast(
                            Intent(ACTION_AUTH_SUCCESS)
                                .setPackage("android")
                                .putExtra(EXTRA_TOKEN, token)
                        )
                        log("AUTH_SUCCESS sent")
                    } else {
                        // Auth cancelled/failed — notify system_server to reset pending
                        activity.sendBroadcast(
                            Intent(ACTION_AUTH_SUCCESS)
                                .setPackage("android")
                                .putExtra(EXTRA_TOKEN, "cancelled")
                        )
                        log("auth cancelled")
                    }
                }
            })

        XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
            "onDestroy", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val r = XposedHelpers.getAdditionalInstanceField(
                        param.thisObject, FIELD_PG_RECEIVER) as? BroadcastReceiver ?: return
                    try { (param.thisObject as Activity).unregisterReceiver(r) }
                    catch (_: Exception) {}
                }
            })
    }

    // After our bypass-lockscreen unpin, IME/clipboard/brightness subsystems
    // on HyperOS can stay stuck in pin-mode restrictions — they listen via
    // ITaskStackListener.onLockTaskModeChanged, and the KVM-reshow suppression
    // we do may skip the notification chain. Fire the callback manually.
    //
    // Must use direct reflection (not XposedHelpers.callMethod) so we can
    // pass an `int` argument without auto-boxing to Integer — the real
    // methods are declared with primitive int.
    private fun invokeIntMethod(obj: Any, methodName: String, value: Int): Boolean {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(methodName, Int::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(obj, value)
                return true
            } catch (_: NoSuchMethodException) { c = c.superclass }
        }
        return false
    }

    private fun kickLockTaskStateChanged(atms: Any) {
        // (1) WindowManager / RootWindowContainer / Policy path
        var wmsKicked = false
        for (fieldName in listOf("mWindowManager", "mRootWindowContainer")) {
            try {
                val obj = XposedHelpers.getObjectField(atms, fieldName) ?: continue
                if (invokeIntMethod(obj, "onLockTaskStateChanged", 0)) {
                    logAlways("kicked $fieldName.onLockTaskStateChanged(0)")
                    wmsKicked = true
                    break
                }
            } catch (_: Throwable) {}
        }
        if (!wmsKicked) {
            try {
                val rwc = XposedHelpers.getObjectField(atms, "mRootWindowContainer")
                XposedHelpers.callMethod(rwc, "forAllDisplayPolicies",
                    java.util.function.Consumer<Any> { dp ->
                        invokeIntMethod(dp, "onLockTaskStateChangedLw", 0)
                    })
                log("kicked forAllDisplayPolicies.onLockTaskStateChangedLw(0)")
            } catch (_: Throwable) {}
        }

        // (2) Find TaskChangeNotificationController and fire notifyLockTaskModeChanged(0).
        //     TCNC can live on mTaskSupervisor, ATMS itself, or be returned
        //     via a getter method.
        val tcnc: Any? = run {
            for ((parent, fieldName) in listOf(
                atms to "mTaskChangeNotificationController",
                atms to "mTaskSupervisor"
            )) {
                try {
                    val v = XposedHelpers.getObjectField(parent, fieldName)
                    if (v != null && v.javaClass.simpleName.contains("NotificationController")) {
                        return@run v
                    }
                } catch (_: Throwable) {}
            }
            for (getter in listOf("getTaskChangeNotificationController")) {
                try {
                    val v = XposedHelpers.callMethod(atms, getter)
                    if (v != null) return@run v
                } catch (_: Throwable) {}
            }
            try {
                val ts = XposedHelpers.getObjectField(atms, "mTaskSupervisor") ?: return@run null
                for (fieldName in listOf(
                    "mTaskChangeNotificationController", "mNotificationController"
                )) {
                    try {
                        val v = XposedHelpers.getObjectField(ts, fieldName)
                        if (v != null && v.javaClass.simpleName.contains("NotificationController")) {
                            return@run v
                        }
                    } catch (_: Throwable) {}
                }
                try {
                    val v = XposedHelpers.callMethod(ts, "getTaskChangeNotificationController")
                    if (v != null) return@run v
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            null
        }
        if (tcnc != null) {
            if (invokeIntMethod(tcnc, "notifyLockTaskModeChanged", 0)) {
                logAlways("kicked TCNC(${tcnc.javaClass.simpleName}).notifyLockTaskModeChanged(0)")
            } else {
                logAlways("TCNC(${tcnc.javaClass.simpleName}) has no notifyLockTaskModeChanged(int)")
            }
        } else {
            logAlways("TCNC not found")
        }

        // (3) Simulate a keyguard show→hide cycle for ScreenObserver listeners.
        // MIUI clipboard restriction latches "pin mode active" on keyguard
        // state changes; suppressing showKeyguard broke this signal chain.
        // ATMS.onKeyguardStateChanged iterates mScreenObservers.
        fun tryKeyguardStateChanged(showing: Boolean) {
            try {
                val m = atms.javaClass.getMethod("onKeyguardStateChanged",
                    Boolean::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(atms, showing)
                logAlways("kicked ATMS.onKeyguardStateChanged($showing)")
            } catch (_: NoSuchMethodException) {
                // Fallback: try 2-arg variant (keyguardShowing, aodShowing)
                try {
                    val m = atms.javaClass.getMethod("onKeyguardStateChanged",
                        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    m.isAccessible = true
                    m.invoke(atms, showing, false)
                    logAlways("kicked ATMS.onKeyguardStateChanged($showing, false)")
                } catch (e: Throwable) {
                    log("onKeyguardStateChanged not found: ${e.message}")
                }
            } catch (e: Throwable) {
                log("onKeyguardStateChanged fail: ${e.message}")
            }
        }
        // Fire the show→hide cycle MIUI clipboard/IME services expect on unpin.
        tryKeyguardStateChanged(true)
        tryKeyguardStateChanged(false)
    }

    private fun updateFlagSecure(activity: Activity) {
        try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val inLockTask = am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
            val shouldBlock = isBlockScreenshot() && inLockTask
            activity.runOnUiThread {
                try {
                    if (shouldBlock) {
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════
    //  manual unpin (bypass keyguard)
    // ═══════════════════════════════════════════════════════════════

    // Run the REAL stopSystemLockTaskMode for full cleanup (IME, WM, brightness,
    // DevicePolicy, TaskChangeNotificationController). Rely on enableKeyguard +
    // shouldLockKeyguard hooks to suppress keyguard during the call.
    //
    // Previous impl manually cleared mLockTaskModeTasks + mLockTaskModeState, but
    // skipped LockTaskController.setLockTaskModeState() internal notifications,
    // leaving IME in ephemeral-keyboard mode (no clipboard) and DisplayPolicy
    // in dimmed brightness — especially after screen off/on events during pin.
    @Suppress("UNCHECKED_CAST")
    private fun manualUnpin() {
        val atms = atmsRef ?: run {
            logAlways("manualUnpin: atmsRef null, skipping")
            return
        }
        val callingId = Binder.clearCallingIdentity()
        try {
            if (isBypassLockscreen()) {
                suppressKeyguardUntil.set(System.currentTimeMillis() + KEYGUARD_SUPPRESS_MS)
                // Signal SystemUI KVM hook to skip reshow during upcoming enableKeyguard(true)
                try {
                    val ctx = XposedHelpers.getObjectField(atms, "mContext") as Context
                    Settings.Global.putLong(ctx.contentResolver, SETTINGS_SUPPRESS_KEY,
                        System.currentTimeMillis() + KEYGUARD_SUPPRESS_MS)
                } catch (e: Exception) { log("suppress signal fail: ${e.message}") }
            }
            authPassed.set(true)
            try {
                XposedHelpers.callMethod(
                    atms, hookedMethodName ?: "stopSystemLockTaskMode")
                log("unpin OK via original")
                // Our KVM-reshow suppression skips showKeyguard, which on
                // HyperOS also short-circuits downstream notifications IME
                // / clipboard / brightness listeners depend on. Re-fire the
                // lock-task-mode-changed callback manually so those listeners
                // see the NONE transition even when screen off/on happened
                // during pin (which otherwise leaves IME clipboard stuck).
                // performStopLockTask is async (mHandler.post), so the native
                // state transition hasn't happened yet. Delay our kicks until
                // after the handler has processed the stop.
                handler?.postDelayed({ kickLockTaskStateChanged(atms) }, 250)
                return
            } catch (e: Throwable) {
                logAlways("original unpin fail: ${e.message}, fallback to manual clear")
                authPassed.set(false)
                suppressKeyguardUntil.set(0)
                try {
                    val ctx = XposedHelpers.getObjectField(atms, "mContext") as Context
                    Settings.Global.putLong(ctx.contentResolver, SETTINGS_SUPPRESS_KEY, 0L)
                } catch (_: Throwable) {}
            }

            // Fallback: manual clear if original call fails. State notifications
            // won't fire, but at least the app exits pin mode.
            val globalLock = XposedHelpers.getObjectField(atms, "mGlobalLock")
            synchronized(globalLock) {
                val ltc = XposedHelpers.getObjectField(atms, "mLockTaskController")
                try {
                    val tasks = XposedHelpers.getObjectField(
                        ltc, "mLockTaskModeTasks") as ArrayList<Any>
                    for (i in tasks.indices.reversed()) {
                        try { XposedHelpers.callMethod(ltc, "clearLockedTask", tasks[i]) }
                        catch (_: Exception) {}
                    }
                    tasks.clear()
                } catch (e: Exception) { log("clearTasks: ${e.message}") }
                try { XposedHelpers.setIntField(ltc, "mLockTaskModeState", 0) }
                catch (_: Exception) {}
                try {
                    val sb = XposedHelpers.callMethod(ltc, "getStatusBarService")
                    sb.javaClass.getMethod(
                        "setLockTaskModeState", Int::class.javaPrimitiveType
                    ).invoke(sb, 0)
                } catch (_: Exception) {}
            }
        } finally {
            Binder.restoreCallingIdentity(callingId)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  credential dialog
    // ═══════════════════════════════════════════════════════════════

    private fun showAuth(activity: Activity) {
        val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardSecure) {
            activity.sendBroadcast(
                Intent(ACTION_AUTH_SUCCESS)
                    .setPackage("android")
                    .putExtra(EXTRA_TOKEN, pendingAuthToken))
            pendingAuthToken = null
            return
        }
        @Suppress("DEPRECATION")
        val intent = km.createConfirmDeviceCredentialIntent("验证身份", "需要验证才能取消固定")
        if (intent != null) {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ)
        } else {
            activity.sendBroadcast(
                Intent(ACTION_AUTH_SUCCESS)
                    .setPackage("android")
                    .putExtra(EXTRA_TOKEN, pendingAuthToken))
            pendingAuthToken = null
        }
    }
}
