package com.xaxaxax.microglogin

import android.os.Process

/** 查詢 MicroG RE 對 app 是否可見。`onPackageLoaded` 時還沒有 `Context`，所以直接問 `IPackageManager`。 */
internal object MicroGRe {

    /** `IPackageManager.getPackageInfo` 依呼叫端套用 package 可見性，結果與 app 自己查詢相同。 */
    fun isVisible(): Boolean {
        val pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null)!!
        // Android 13 起 flags 是 long，之前是 int
        val getPackageInfo = pm.javaClass.methods.first { it.name == "getPackageInfo" && it.parameterTypes.size == 3 }
        val flags: Any = if (getPackageInfo.parameterTypes[1] == Long::class.javaPrimitiveType) 0L else 0
        return getPackageInfo.invoke(pm, GmsRules.MICROG_RE_PACKAGE, flags, Process.myUid() / PER_USER_RANGE) != null
    }

    private const val PER_USER_RANGE = 100000
}
