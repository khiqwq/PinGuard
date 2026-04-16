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
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.Collections
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
    }

    // ── state ───────────────────────────────────────────────────────

    private val authPassed = AtomicBoolean(false)
    private val authPending = AtomicBoolean(false) // prevents token overwrite race
    private val suppressToastUntil = AtomicLong(0)
    @Volatile private var atmsRef: Any? = null
    @Volatile private var hookedMethodName: String? = null
    @Volatile private var handler: Handler? = null
    private var currentActivity: WeakReference<Activity>? = null
    private val protectedPackages: MutableSet<String> =
        Collections.synchronizedSet(HashSet())
    private var debugLog = false

    @Volatile private var authToken: String? = null

    // ── logging ─────────────────────────────────────────────────────

    private fun log(msg: String) {
        if (debugLog) XposedBridge.log("$TAG: $msg")
    }

    private fun logAlways(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }

    // ── prefs ───────────────────────────────────────────────────────

    private fun loadPrefs(): XSharedPreferences? {
        return try {
            val p = XSharedPreferences("io.github.khiqwq.pinguard", "config")
            p.reload()
            debugLog = p.getBoolean("debug_log", false)
            p
        } catch (_: Exception) { null }
    }

    private fun isEnabled(): Boolean =
        loadPrefs()?.getBoolean("enabled", true) ?: true

    private fun isHideExitToast(): Boolean =
        loadPrefs()?.getBoolean("hide_exit_toast", false) ?: false

    private fun isBypassLockscreen(): Boolean =
        loadPrefs()?.getBoolean("bypass_lockscreen", true) ?: true

    private fun isBlockScreenshot(): Boolean =
        loadPrefs()?.getBoolean("block_screenshot", false) ?: false

    private fun isAllowAssistant(): Boolean =
        loadPrefs()?.getBoolean("allow_assistant", false) ?: false

    // ── entry ───────────────────────────────────────────────────────

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when {
            lpparam.packageName == "android" && lpparam.processName == "android" ->
                hookSystemServer(lpparam)
            lpparam.packageName != "io.github.khiqwq.pinguard" ->
                hookTargetApp(lpparam)
        }
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
                        atmsRef = param.thisObject
                        handler = Handler(Looper.getMainLooper())
                        val ctx = XposedHelpers.getObjectField(
                            param.thisObject, "mContext") as Context
                        registerReceivers(ctx)
                        logAlways("ready")
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
                        suppressToastUntil.set(System.currentTimeMillis() + 3000)
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

        // 3. Toast suppression (time-window + one-shot)
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

        // 4. Screenshot blocking during lock task
        try {
            val pwmClass = XposedHelpers.findClass(
                "com.android.server.policy.PhoneWindowManager", lpparam.classLoader)

            // Hook handleScreenShot — blocks key combo screenshots
            for (method in pwmClass.declaredMethods) {
                if (method.name == "handleScreenShot") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isBlockScreenshot() && isInLockTaskWithProtectedApp()) {
                                log("blocked screenshot")
                                param.setResult(null)
                            }
                        }
                    })
                    logAlways("hooked handleScreenShot ✓")
                }
            }

            // Also hook interceptScreenshotChord for earlier interception
            for (method in pwmClass.declaredMethods) {
                if (method.name == "interceptScreenshotChord") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isBlockScreenshot() && isInLockTaskWithProtectedApp()) {
                                log("blocked screenshot chord")
                                param.setResult(null)
                            }
                        }
                    })
                }
            }
        } catch (e: Exception) {
            log("screenshot hook fail: ${e.message}")
        }

        // 5. Allow voice assistant (小爱同学) during lock task
        try {
            val pwmClass = XposedHelpers.findClass(
                "com.android.server.policy.PhoneWindowManager", lpparam.classLoader)

            // Hook interceptKeyBeforeDispatching to allow assistant key through
            for (method in pwmClass.declaredMethods) {
                if (method.name == "interceptKeyBeforeDispatching") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isAllowAssistant() || !isInLockTaskWithProtectedApp()) return
                            val event = param.args.firstOrNull { it is android.view.KeyEvent } as? android.view.KeyEvent ?: return
                            // KEYCODE_ASSIST=219, KEYCODE_VOICE_ASSIST=231
                            if (event.keyCode == 219 || event.keyCode == 231) {
                                log("allowing assistant key through lock task")
                                // Don't block — let it pass to the system
                            }
                        }
                    })
                }
            }

            // Hook LockTaskController to allow assistant intent
            val ltcClass = XposedHelpers.findClass(
                "com.android.server.wm.LockTaskController", lpparam.classLoader)
            for (method in ltcClass.declaredMethods) {
                if (method.name == "isPackageAllowlisted") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isAllowAssistant()) return
                            val userId = try { param.args.last() as Int } catch (_: Exception) { return }
                            val pkg = try { param.args.first() as String } catch (_: Exception) { return }
                            // Allow Xiaomi voice assistant packages
                            if (pkg.contains("voiceassist") || pkg.contains("mibrain") ||
                                pkg.contains("aiasst") || pkg == "com.miui.voiceassist") {
                                log("allowing assistant package: $pkg")
                                param.setResult(true)
                            }
                        }
                    })
                    logAlways("hooked isPackageAllowlisted ✓")
                }
            }
        } catch (e: Exception) {
            log("assistant hook fail: ${e.message}")
        }
    }

    // ── lock task state check ───────────────────────────────────────

    private fun isInLockTaskWithProtectedApp(): Boolean {
        val atms = atmsRef ?: return false
        return try {
            val ltc = XposedHelpers.getObjectField(atms, "mLockTaskController")
            val state = XposedHelpers.getIntField(ltc, "mLockTaskModeState")
            if (state == 0) return false
            val pkg = getPinnedAppPackage(atms)
            pkg != null && pkg in protectedPackages
        } catch (_: Exception) { false }
    }

    // ── protected package tracking ──────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun getPinnedAppPackage(atms: Any): String? {
        return try {
            val ltc = XposedHelpers.getObjectField(atms, "mLockTaskController")
            val tasks = XposedHelpers.getObjectField(ltc, "mLockTaskModeTasks") as? ArrayList<Any>
            if (tasks.isNullOrEmpty()) null else getTaskPackage(tasks.last())
        } catch (_: Exception) { null }
    }

    private fun getTaskPackage(task: Any): String? {
        return try {
            (XposedHelpers.getObjectField(task, "realActivity") as? ComponentName)?.packageName
        } catch (_: Exception) {
            try {
                (XposedHelpers.getObjectField(task, "intent") as? Intent)?.component?.packageName
            } catch (_: Exception) { null }
        }
    }

    // ── broadcast receivers (system_server) ─────────────────────────

    private fun registerReceivers(ctx: Context) {
        val h = handler ?: return

        // PING/PONG — respond with targeted PONG
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val sender = i.getStringExtra(EXTRA_PKG)
                val pong = Intent(ACTION_PONG)
                if (sender != null) pong.setPackage(sender)
                c.sendBroadcast(pong)
            }
        }, IntentFilter(ACTION_PING), null, h, Context.RECEIVER_EXPORTED)

        // App registration — capped to prevent DoS
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
                h.post { manualUnpin() }
            }
        }, IntentFilter(ACTION_AUTH_SUCCESS), null, h, Context.RECEIVER_EXPORTED)

        // Auth timeout — reset pending state after 60s
        h.postDelayed(object : Runnable {
            override fun run() {
                if (authPending.get() && authToken != null) {
                    // Check if token is stale (set > 60s ago)
                    authPending.set(false)
                    authToken = null
                    log("auth timeout, reset")
                }
                h.postDelayed(this, 60_000)
            }
        }, 60_000)
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

                    if (XposedHelpers.getAdditionalInstanceField(activity, "pg") != null) return
                    XposedHelpers.setAdditionalInstanceField(activity, "pg", true)

                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            pendingAuthToken = intent.getStringExtra(EXTRA_TOKEN)
                            val act = currentActivity?.get() ?: return
                            log("SHOW_AUTH → ${act.javaClass.name}")
                            showAuth(act)
                        }
                    }
                    XposedHelpers.setAdditionalInstanceField(activity, "pg_r", receiver)
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
                    val tag = "pg_handled"
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
                        param.thisObject, "pg_r") as? BroadcastReceiver ?: return
                    try { (param.thisObject as Activity).unregisterReceiver(r) }
                    catch (_: Exception) {}
                }
            })
    }

    // ═══════════════════════════════════════════════════════════════
    //  manual unpin (bypass keyguard)
    // ═══════════════════════════════════════════════════════════════

    @Suppress("UNCHECKED_CAST")
    private fun manualUnpin() {
        val callingId = Binder.clearCallingIdentity()
        try {
            val globalLock = XposedHelpers.getObjectField(atmsRef, "mGlobalLock")
            synchronized(globalLock) {
                val ltc = XposedHelpers.getObjectField(atmsRef, "mLockTaskController")

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
            log("unpin OK")

            // Dismiss keyguard if bypass_lockscreen is on
            if (isBypassLockscreen()) {
                try {
                    val wms = Class.forName("android.view.WindowManagerGlobal")
                        .getMethod("getWindowManagerService").invoke(null)
                    wms.javaClass.getMethod(
                        "dismissKeyguard",
                        Class.forName("com.android.internal.policy.IKeyguardDismissCallback"),
                        CharSequence::class.java
                    ).invoke(wms, null, null)
                    log("keyguard dismissed")
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logAlways("unpin FAIL: ${e.message}")
            authPassed.set(true)
            try { XposedHelpers.callMethod(
                atmsRef, hookedMethodName ?: "stopSystemLockTaskMode") }
            catch (_: Exception) {}
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
