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

    // ── prefs ───────────────────────────────────────────────────────

    private fun log(msg: String) {
        if (debugLog) XposedBridge.log("$TAG: $msg")
    }

    /** Always log regardless of debug toggle — for critical events only */
    private fun logAlways(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }

    private fun loadPrefs(): XSharedPreferences? {
        return try {
            val p = XSharedPreferences("com.example.pinguard", "config")
            p.reload()
            debugLog = p.getBoolean("debug_log", false)
            p
        } catch (_: Exception) { null }
    }

    private fun isEnabled(): Boolean {
        return loadPrefs()?.getBoolean("enabled", true) ?: true
    }

    private fun isHideExitToast(): Boolean {
        return loadPrefs()?.getBoolean("hide_exit_toast", false) ?: false
    }

    // ── entry ───────────────────────────────────────────────────────

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when {
            lpparam.packageName == "android" && lpparam.processName == "android" ->
                hookSystemServer(lpparam)
            lpparam.packageName != "com.example.pinguard" ->
                hookTargetApp(lpparam)
        }
    }

    // ── system_server hooks ─────────────────────────────────────────

    private fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        logAlways("=== system_server init ===")

        val atmsClass = try {
            XposedHelpers.findClass(
                "com.android.server.wm.ActivityTaskManagerService", lpparam.classLoader
            )
        } catch (e: Exception) {
            logAlways("ATMS not found: ${e.message}"); return
        }

        // 1. onSystemReady → register broadcast receivers
        try {
            XposedHelpers.findAndHookMethod(atmsClass, "onSystemReady",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        atmsRef = param.thisObject
                        handler = Handler(Looper.getMainLooper())
                        val ctx = XposedHelpers.getObjectField(
                            param.thisObject, "mContext") as Context
                        registerReceivers(ctx)
                        logAlways("onSystemReady: receivers registered")
                    }
                })
        } catch (e: Exception) {
            logAlways("onSystemReady hook fail: ${e.message}")
        }

        // 2. Hook unpin methods
        val unpinHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                log(">>> ${param.method.name}")

                if (!isEnabled()) { log("disabled, pass"); return }

                if (authPassed.compareAndSet(true, false)) {
                    log("auth OK, pass"); return
                }

                if (!isPinnedAppProtected(param.thisObject)) {
                    log("not protected, pass"); return
                }

                hookedMethodName = param.method.name
                if (atmsRef == null) atmsRef = param.thisObject

                val ctx = try {
                    XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                } catch (_: Exception) { return }

                val token = Binder.clearCallingIdentity()
                try {
                    ctx.sendBroadcast(Intent(ACTION_SHOW_AUTH))
                    // Set toast suppression window only if pref is ON
                    if (isHideExitToast()) {
                        suppressToastUntil.set(System.currentTimeMillis() + 3000)
                    }
                    log("SHOW_AUTH sent, blocked")
                    param.setResult(null)
                } catch (e: Exception) {
                    log("broadcast fail: ${e.message}")
                } finally {
                    Binder.restoreCallingIdentity(token)
                }
            }
        }

        for (m in listOf("stopSystemLockTaskMode", "stopLockTaskModeOnCurrent")) {
            try {
                XposedHelpers.findAndHookMethod(atmsClass, m, unpinHook)
                logAlways("hooked $m ✓")
            } catch (_: NoSuchMethodError) {
                log("$m not found")
            } catch (e: Exception) {
                log("$m fail: ${e.message}")
            }
        }

        // 3. Toast suppression — time-window based, only when hide_exit_toast ON
        try {
            XposedHelpers.findAndHookMethod("android.widget.Toast", lpparam.classLoader,
                "show", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val deadline = suppressToastUntil.get()
                        if (deadline == 0L || System.currentTimeMillis() > deadline) return
                        // Within window — suppress this toast
                        suppressToastUntil.set(0) // one-shot: only suppress one toast
                        log("suppressed exit toast")
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
            val top = tasks.last()
            val pkg = getTaskPackage(top)
            log("pinned=$pkg protected=$protectedPackages")
            pkg != null && pkg in protectedPackages
        } catch (e: Exception) {
            log("isPinnedAppProtected err: ${e.message}")
            false // fail-open: don't block if we can't determine
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

    // ── broadcast receivers (system_server side) ────────────────────

    private fun registerReceivers(ctx: Context) {
        val h = handler!!

        // PING/PONG for module status check
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                c.sendBroadcast(Intent(ACTION_PONG))
            }
        }, IntentFilter(ACTION_PING), null, h, Context.RECEIVER_EXPORTED)

        // App registration
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val pkg = intent.getStringExtra("pkg") ?: return
                if (protectedPackages.add(pkg)) {
                    log("protected +$pkg (${protectedPackages.size})")
                }
            }
        }, IntentFilter(ACTION_REGISTER), null, h, Context.RECEIVER_EXPORTED)

        // Auth success → manual unpin
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                log("AUTH_SUCCESS → manualUnpin")
                h.post { manualUnpin() }
            }
        }, IntentFilter(ACTION_AUTH_SUCCESS), null, h, Context.RECEIVER_EXPORTED)
    }

    // ── target app hooks ────────────────────────────────────────────

    private fun hookTargetApp(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("hookTargetApp ${lpparam.packageName}")

        // onResume: register package + SHOW_AUTH receiver
        XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
            "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    currentActivity = WeakReference(activity)

                    // Always register actual package name
                    activity.sendBroadcast(
                        Intent(ACTION_REGISTER).putExtra("pkg", activity.packageName)
                    )

                    // Only register SHOW_AUTH receiver once per instance
                    if (XposedHelpers.getAdditionalInstanceField(activity, "pg") != null) return
                    XposedHelpers.setAdditionalInstanceField(activity, "pg", true)

                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            val act = currentActivity?.get() ?: return
                            log("SHOW_AUTH → ${act.javaClass.name}")
                            showAuth(act)
                        }
                    }
                    XposedHelpers.setAdditionalInstanceField(activity, "pg_r", receiver)
                    activity.registerReceiver(
                        receiver, IntentFilter(ACTION_SHOW_AUTH), Context.RECEIVER_EXPORTED
                    )
                }
            })

        // onActivityResult: credential verification result
        XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
            "onActivityResult",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] as Int != REQ) return
                    val activity = param.thisObject as Activity
                    // Deduplicate (base class + subclass both fire)
                    val tag = "pg_handled"
                    if (XposedHelpers.getAdditionalInstanceField(activity, tag) != null) {
                        XposedHelpers.removeAdditionalInstanceField(activity, tag)
                        return
                    }
                    XposedHelpers.setAdditionalInstanceField(activity, tag, true)
                    if (param.args[1] as Int == Activity.RESULT_OK) {
                        activity.sendBroadcast(Intent(ACTION_AUTH_SUCCESS))
                        log("AUTH_SUCCESS from ${activity.packageName}")
                    }
                }
            })

        // onDestroy: cleanup receiver
        XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
            "onDestroy", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val r = XposedHelpers.getAdditionalInstanceField(
                        param.thisObject, "pg_r") as? BroadcastReceiver ?: return
                    try { (param.thisObject as Activity).unregisterReceiver(r) } catch (_: Exception) {}
                }
            })
    }

    // ── manual unpin (bypass keyguard) ──────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun manualUnpin() {
        val token = Binder.clearCallingIdentity()
        try {
            val globalLock = XposedHelpers.getObjectField(atmsRef, "mGlobalLock")
            synchronized(globalLock) {
                val ltc = XposedHelpers.getObjectField(atmsRef, "mLockTaskController")

                // 1. Clear locked tasks
                try {
                    val tasks = XposedHelpers.getObjectField(ltc, "mLockTaskModeTasks") as ArrayList<Any>
                    for (i in tasks.indices.reversed()) {
                        try { XposedHelpers.callMethod(ltc, "clearLockedTask", tasks[i]) }
                        catch (_: Exception) {}
                    }
                    tasks.clear()
                } catch (e: Exception) { log("clearTasks: ${e.message}") }

                // 2. Set mode NONE
                try { XposedHelpers.setIntField(ltc, "mLockTaskModeState", 0) }
                catch (_: Exception) {}

                // 3. Update status bar (explicit int to avoid autoboxing crash)
                try {
                    val sb = XposedHelpers.callMethod(ltc, "getStatusBarService")
                    sb.javaClass.getMethod("setLockTaskModeState", Int::class.javaPrimitiveType)
                        .invoke(sb, 0)
                } catch (_: Exception) {}
            }
            log("manualUnpin OK")
        } catch (e: Exception) {
            logAlways("manualUnpin FAIL: ${e.message}")
            // Fallback: normal unpin (will show keyguard but won't crash)
            authPassed.set(true)
            try { XposedHelpers.callMethod(atmsRef, hookedMethodName ?: "stopSystemLockTaskMode") }
            catch (_: Exception) {}
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    // ── show credential dialog ──────────────────────────────────────

    private fun showAuth(activity: Activity) {
        val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardSecure) {
            log("no secure lock, auto-pass")
            activity.sendBroadcast(Intent(ACTION_AUTH_SUCCESS))
            return
        }
        @Suppress("DEPRECATION")
        val intent = km.createConfirmDeviceCredentialIntent("验证身份", "需要验证才能取消固定")
        if (intent != null) {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ)
            log("credential launched")
        } else {
            log("createConfirmDeviceCredentialIntent null, auto-pass")
            activity.sendBroadcast(Intent(ACTION_AUTH_SUCCESS))
        }
    }
}
