package com.example.pinguard

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
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

class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "PinGuard"
        const val REQ = 0x5047
    }

    private val authPassed = AtomicBoolean(false)
    private var atmsRef: Any? = null
    private var hookedMethodName: String? = null
    private var handler: Handler? = null
    private var currentActivity: WeakReference<Activity>? = null
    private val protectedPackages: MutableSet<String> =
        Collections.synchronizedSet(HashSet())

    private var debugLog = true

    private fun log(msg: String) {
        if (debugLog) XposedBridge.log("$TAG: $msg")
    }

    private fun reloadPrefs(): XSharedPreferences? {
        return try {
            val p = XSharedPreferences("com.example.pinguard", "config")
            p.reload()
            debugLog = p.getBoolean("debug_log", false)
            p
        } catch (_: Exception) { null }
    }

    private fun isEnabled(): Boolean {
        val p = reloadPrefs() ?: return true
        return p.getBoolean("enabled", true)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android" && lpparam.processName == "android") {
            hookSystemServer(lpparam)
            return
        }
        if (lpparam.packageName != "com.example.pinguard") {
            hookTargetApp(lpparam)
        }
    }

    // ── system_server ───────────────────────────────────────────────

    private fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("=== system_server init ===")

        val atmsClass = try {
            XposedHelpers.findClass(
                "com.android.server.wm.ActivityTaskManagerService", lpparam.classLoader
            )
        } catch (e: Exception) {
            log("ATMS not found: ${e.message}"); return
        }

        // onSystemReady → register receivers
        try {
            XposedHelpers.findAndHookMethod(atmsClass, "onSystemReady",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        atmsRef = param.thisObject
                        handler = Handler(Looper.getMainLooper())
                        val ctx = XposedHelpers.getObjectField(
                            param.thisObject, "mContext"
                        ) as Context
                        registerAuthReceiver(ctx)
                        registerPingReceiver(ctx)
                        registerAppReceiver(ctx)
                        log("onSystemReady done")
                    }
                })
        } catch (e: Exception) {
            log("onSystemReady fail: ${e.message}")
        }

        // Hook unpin methods
        val unpinHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                log(">>> ${param.method.name} <<<")

                if (!isEnabled()) { log("disabled"); return }

                if (authPassed.compareAndSet(true, false)) {
                    log("auth passed, allow"); return
                }

                // Check if pinned app is protected
                if (!isPinnedAppProtected(param.thisObject)) {
                    log("app not in scope, allowing"); return
                }

                hookedMethodName = param.method.name
                if (atmsRef == null) atmsRef = param.thisObject

                val ctx = try {
                    XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                } catch (_: Exception) { return }

                val token = Binder.clearCallingIdentity()
                try {
                    ctx.sendBroadcast(Intent("com.example.pinguard.SHOW_AUTH"))
                    log("SHOW_AUTH sent, BLOCKING")
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
                log("hooked $m ✓")
            } catch (_: NoSuchMethodError) {
                log("$m not found")
            } catch (e: Exception) {
                log("$m fail: ${e.message}")
            }
        }

        // Optionally suppress exit hint toast
        try {
            val ltcClass = XposedHelpers.findClass(
                "com.android.server.wm.LockTaskController", lpparam.classLoader
            )
            for (method in ltcClass.declaredMethods) {
                if (method.name == "showLockTaskToast") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val p = reloadPrefs()
                            if (p?.getBoolean("hide_exit_toast", false) == true) {
                                param.setResult(null)
                            }
                        }
                    })
                    log("hooked showLockTaskToast ✓")
                }
            }
        } catch (_: Exception) {}
    }

    @Suppress("UNCHECKED_CAST")
    private fun isPinnedAppProtected(atms: Any): Boolean {
        if (protectedPackages.isEmpty()) return false
        return try {
            val ltc = XposedHelpers.getObjectField(atms, "mLockTaskController")
            val tasks = XposedHelpers.getObjectField(ltc, "mLockTaskModeTasks") as? ArrayList<Any>
            if (tasks.isNullOrEmpty()) return false
            val top = tasks.last()
            val pkg = try {
                val cn = XposedHelpers.getObjectField(top, "realActivity")
                (cn as? android.content.ComponentName)?.packageName
            } catch (_: Exception) {
                try {
                    val intent = XposedHelpers.getObjectField(top, "intent") as? Intent
                    intent?.component?.packageName
                } catch (_: Exception) { null }
            }
            log("pinned app: $pkg, protected: ${protectedPackages}")
            pkg != null && pkg in protectedPackages
        } catch (e: Exception) {
            log("isPinnedAppProtected: ${e.message}")
            false
        }
    }

    private fun registerPingReceiver(ctx: Context) {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                c.sendBroadcast(Intent("com.example.pinguard.PONG"))
            }
        }, IntentFilter("com.example.pinguard.PING"), null, handler!!, Context.RECEIVER_EXPORTED)
    }

    private fun registerAppReceiver(ctx: Context) {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val pkg = intent.getStringExtra("pkg") ?: return
                protectedPackages.add(pkg)
                log("protected: +$pkg (total: ${protectedPackages.size})")
            }
        }, IntentFilter("com.example.pinguard.REGISTER_APP"),
            null, handler!!, Context.RECEIVER_EXPORTED)
    }

    private fun registerAuthReceiver(ctx: Context) {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                log("AUTH_SUCCESS")
                handler!!.post { manualUnpin() }
            }
        }, IntentFilter("com.example.pinguard.AUTH_SUCCESS"),
            null, handler!!, Context.RECEIVER_EXPORTED)
    }

    // ── target app ──────────────────────────────────────────────────

    private fun hookTargetApp(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("hooking ${lpparam.packageName}")

        val pkgName = lpparam.packageName

        XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
            "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    currentActivity = WeakReference(activity)

                    if (XposedHelpers.getAdditionalInstanceField(activity, "pg") != null) return
                    XposedHelpers.setAdditionalInstanceField(activity, "pg", true)

                    // Notify system_server this package is protected
                    activity.sendBroadcast(
                        Intent("com.example.pinguard.REGISTER_APP").putExtra("pkg", pkgName)
                    )

                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            val act = currentActivity?.get() ?: return
                            log("SHOW_AUTH in ${act.javaClass.name}")
                            showAuth(act)
                        }
                    }
                    XposedHelpers.setAdditionalInstanceField(activity, "pg_r", receiver)
                    activity.registerReceiver(
                        receiver, IntentFilter("com.example.pinguard.SHOW_AUTH"),
                        Context.RECEIVER_EXPORTED
                    )
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
                        activity.sendBroadcast(Intent("com.example.pinguard.AUTH_SUCCESS"))
                        log("AUTH_SUCCESS from ${pkgName}")
                    }
                }
            })

        XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
            "onDestroy", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val r = XposedHelpers.getAdditionalInstanceField(
                        param.thisObject, "pg_r"
                    ) as? BroadcastReceiver ?: return
                    try {
                        (param.thisObject as Activity).unregisterReceiver(r)
                    } catch (_: Exception) {}
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

                try {
                    val tasks = XposedHelpers.getObjectField(ltc, "mLockTaskModeTasks") as ArrayList<Any>
                    for (i in tasks.indices.reversed()) {
                        try { XposedHelpers.callMethod(ltc, "clearLockedTask", tasks[i]) } catch (_: Exception) {}
                    }
                    tasks.clear()
                } catch (e: Exception) { log("clear tasks: ${e.message}") }

                try { XposedHelpers.setIntField(ltc, "mLockTaskModeState", 0) } catch (_: Exception) {}

                try {
                    val sb = XposedHelpers.callMethod(ltc, "getStatusBarService")
                    sb.javaClass.getMethod("setLockTaskModeState", Int::class.javaPrimitiveType)
                        .invoke(sb, 0)
                } catch (_: Exception) {}
            }
            log("manualUnpin OK")
        } catch (e: Exception) {
            log("manualUnpin FAIL: ${e.message}, fallback")
            authPassed.set(true)
            try { XposedHelpers.callMethod(atmsRef, hookedMethodName ?: "stopSystemLockTaskMode") } catch (_: Exception) {}
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun showAuth(activity: Activity) {
        val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardSecure) {
            activity.sendBroadcast(Intent("com.example.pinguard.AUTH_SUCCESS"))
            return
        }
        @Suppress("DEPRECATION")
        val intent = km.createConfirmDeviceCredentialIntent("验证身份", "需要验证才能取消固定")
        if (intent != null) {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ)
        } else {
            activity.sendBroadcast(Intent("com.example.pinguard.AUTH_SUCCESS"))
        }
    }
}
