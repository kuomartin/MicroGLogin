package com.xaxaxax.microglogin

import io.github.libxposed.api.XposedInterface

/** 經由框架記錄，同時出現在框架日誌與 logcat。 */
internal object Log {
    private const val TAG = "MicroGLogin"

    private lateinit var xposed: XposedInterface

    fun attach(xposed: XposedInterface) {
        this.xposed = xposed
    }

    fun d(msg: String) = xposed.log(android.util.Log.DEBUG, TAG, msg)

    fun i(msg: String) = xposed.log(android.util.Log.INFO, TAG, msg)

    fun w(msg: String) = xposed.log(android.util.Log.WARN, TAG, msg)

    fun e(msg: String, t: Throwable? = null) = xposed.log(android.util.Log.ERROR, TAG, msg, t)
}
