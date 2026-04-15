package com.example.pinguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : IXposedHookLoadPackage {

    private val authPassed = AtomicBoolean(false)
    private var atmsRef: Any? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") return

        val atmsClass = XposedHelpers.findClass(
            "com.android.server.wm.ActivityTaskManagerService",
            lpparam.classLoader
        )

        // Register broadcast receiver on system ready
        XposedHelpers.findAndHookMethod(atmsClass, "onSystemReady",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    atmsRef = param.thisObject
                    val ctx =
                        XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                    registerAuthReceiver(ctx)
                    XposedBridge.log("PinGuard: hooked in system_server")
                }
            }
        )

        // Intercept unpin
        XposedHelpers.findAndHookMethod(atmsClass, "stopSystemLockTaskMode",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (authPassed.compareAndSet(true, false)) return

                    val ctx = try {
                        XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                    } catch (e: Exception) {
                        XposedBridge.log("PinGuard: no context, allow through")
                        return
                    }

                    val intent = Intent().apply {
                        setClassName(
                            "com.example.pinguard",
                            "com.example.pinguard.AuthActivity"
                        )
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        )
                    }
                    try {
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        XposedBridge.log("PinGuard: can't start auth: ${e.message}")
                        return
                    }

                    param.setResult(null) // block unpin
                }
            }
        )
    }

    private fun registerAuthReceiver(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                XposedBridge.log("PinGuard: auth success, unpinning")
                authPassed.set(true)
                handler.post {
                    try {
                        XposedHelpers.callMethod(atmsRef, "stopSystemLockTaskMode")
                    } catch (e: Exception) {
                        XposedBridge.log("PinGuard: unpin call failed: ${e.message}")
                    }
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter("com.example.pinguard.AUTH_SUCCESS"),
            "com.example.pinguard.permission.AUTH",
            handler,
            Context.RECEIVER_EXPORTED
        )
    }
}
