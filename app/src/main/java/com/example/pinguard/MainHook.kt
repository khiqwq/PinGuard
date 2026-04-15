package com.example.pinguard

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
        const val ACTION_SHOW_AUTH = "com.example.pinguard.SHOW_AUTH"
        const val ACTION_AUTH_SUCCESS = "com.example.pinguard.AUTH_SUCCESS"
        const val ACTION_REGISTER = "com.example.pinguard.REGISTER_APP"
        const val ACTION_PING = "com.example.pinguard.PING"
        const val ACTION_PONG = "com.example.pinguard.PONG"
        const val EXTRA_TOKEN = "t"
        const val EXTRA_PKG = "pkg"
    }

    // ── state ───────────────────────────────────────────────────────

    private val authPassed = AtomicBoolean(false)
    private val suppressToastUntil = AtomicLong(0)
    private var atmsRef: Any? = null
    private var hookedMethodName: String? = null
    private var handler: Handler? = null
    private var currentActivity: WeakReference<Activity>? = null
    private val protectedPackages: MutableSet<String> =
        Collections.synchronizedSet(HashSet())
    private var debugLog = false

    /** One-time token generated per auth session to prevent broadcast spoofing */
    @Volatile private var authToken: String? = null
    /** Secret shared between system_server and hooked apps at hook load time */
    private val sessionSecret = UUID.randomUUID().toString()

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
            val p = XSharedPreferences("com.example.pinguard", "config")
            p.reload()
            debugLog = p.getBoolean("debug_log", false)
            p
        } catch (_: Exception) { null }
    }

    private fun isEnabled(): Boolean =
        loadPrefs()?.getBoolean("enabled", true) ?: true

    private fun isHideExitToast(): Boolean =
        loadPrefs()?.getBoolean("hide_exit_toast", false) ?: false

    // ── entry ───────────────────────────────────────────────────────

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when {
            lpparam.packageName == "android" && lpparam.processName == "android" ->
                hookSystemServer(lpparam)
            lpparam.packageName != "com.example.pinguard" ->
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
                        logAlways("ready (secret=${sessionSecret.take(8)}...)")
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
                if (!isPinnedAppProtected(param.thisObject)) { log("not protected"); return }

                hookedMethodName = param.method.name
                if (atmsRef == null) atmsRef = param.thisObject

                val ctx = try {
                    XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                } catch (_: Exception) { return }

                // Generate one-time auth token
                val token = UUID.randomUUID().toString()
                authToken = token

                val callingId = Binder.clearCallingIdentity()
                try {
                    ctx.sendBroadcast(Intent(ACTION_SHOW_AUTH).putExtra(EXTRA_TOKEN, token))
                    if (isHideExitToast()) {
                        suppressToastUntil.set(System.currentTimeMillis() + 3000)
                    }
                    log("blocked, token=${token.take(8)}")
                    param.setResult(null)
                } catch (e: Exception) {
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
    }

    // ── protected package tracking ──────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun isPinnedAppProtected(atms: Any): Boolean {
        if (protectedPackages.isEmpty()) return false
        return try {
            val ltc = XposedHelpers.getObjectField(atms, "mLockTaskController")
            val tasks = XposedHelpers.getObjectField(ltc, "mLockTaskModeTasks") as? ArrayList<Any>
            if (tasks.isNullOrEmpty()) return false
            val pkg = getTaskPackage(tasks.last())
            log("pinned=$pkg scope=$protectedPackages")
            pkg != null && pkg in protectedPackages
        } catch (e: Exception) {
            log("check err: ${e.message}")
            false
        }
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

        // PING/PONG — include secret so only our module responds
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getStringExtra(EXTRA_TOKEN) != sessionSecret) return
                c.sendBroadcast(Intent(ACTION_PONG))
            }
        }, IntentFilter(ACTION_PING), null, h, Context.RECEIVER_EXPORTED)

        // App registration — validate secret
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getStringExtra(EXTRA_TOKEN) != sessionSecret) return
                val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
                if (protectedPackages.add(pkg)) log("protected +$pkg")
            }
        }, IntentFilter(ACTION_REGISTER), null, h, Context.RECEIVER_EXPORTED)

        // Auth success — validate one-time token
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val t = intent.getStringExtra(EXTRA_TOKEN)
                val expected = authToken
                if (t == null || expected == null || t != expected) {
                    logAlways("AUTH_SUCCESS rejected: bad token")
                    return
                }
                authToken = null // consume token
                log("AUTH_SUCCESS verified")
                h.post { manualUnpin() }
            }
        }, IntentFilter(ACTION_AUTH_SUCCESS), null, h, Context.RECEIVER_EXPORTED)
    }

    // ═══════════════════════════════════════════════════════════════
    //  target app hooks
    // ═══════════════════════════════════════════════════════════════

    /** Session secret injected by Xposed — shared in-memory, not broadcast */
    private var appSecret: String? = null

    private fun hookTargetApp(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("hookTarget ${lpparam.packageName}")

        XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
            "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    currentActivity = WeakReference(activity)

                    // Register package with secret
                    activity.sendBroadcast(
                        Intent(ACTION_REGISTER)
                            .putExtra(EXTRA_PKG, activity.packageName)
                            .putExtra(EXTRA_TOKEN, sessionSecret)
                    )

                    if (XposedHelpers.getAdditionalInstanceField(activity, "pg") != null) return
                    XposedHelpers.setAdditionalInstanceField(activity, "pg", true)

                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            // Save the auth token from system_server
                            appSecret = intent.getStringExtra(EXTRA_TOKEN)
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
                    if (param.args[1] as Int == Activity.RESULT_OK) {
                        // Send AUTH_SUCCESS with the token from system_server
                        activity.sendBroadcast(
                            Intent(ACTION_AUTH_SUCCESS).putExtra(EXTRA_TOKEN, appSecret)
                        )
                        appSecret = null
                        log("AUTH_SUCCESS (verified)")
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
                Intent(ACTION_AUTH_SUCCESS).putExtra(EXTRA_TOKEN, appSecret))
            appSecret = null
            return
        }
        @Suppress("DEPRECATION")
        val intent = km.createConfirmDeviceCredentialIntent("验证身份", "需要验证才能取消固定")
        if (intent != null) {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ)
        } else {
            activity.sendBroadcast(
                Intent(ACTION_AUTH_SUCCESS).putExtra(EXTRA_TOKEN, appSecret))
            appSecret = null
        }
    }
}
