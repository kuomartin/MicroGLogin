package com.xaxaxax.microglogin

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import java.lang.reflect.Method

/** 替代品的查詢。`onPackageLoaded` 時還沒有 `Context`，所以直接問 `IPackageManager`。 */
internal object GmsReplacement {

    /**
     * patch 工具已把 manifest 的 C2DM 改名時，改名所指的替代品 package；沒改名則回傳 null。有值表示推播要交給替代品。
     *
     * LSPatch 的「GMS 替代品」選項把 `com.google.android.c2dm.permission.RECEIVE` 改成
     * `<vendor>.android.c2dm.permission.RECEIVE`，替代品就是 `<vendor>.android.gms`。向系統查 app 要求的權限：
     * LSPatch 在 app process 內把 patch 版 apk 的讀取轉到原始 apk，讀不到它的 patch 設定，系統的紀錄則不受影響。
     */
    fun renamedC2dmTarget(packageName: String): String? {
        val pm = packageManager()
        val getPackageInfo = pm.javaClass.methods.first { it.name == "getPackageInfo" && it.parameterTypes.size == 3 }
        val flags: Any = if (getPackageInfo.parameterTypes[1] == Long::class.javaPrimitiveType) GET_PERMISSIONS.toLong() else GET_PERMISSIONS
        val info = getPackageInfo.invoke(pm, packageName, flags, userId()) as PackageInfo? ?: return null
        return info.requestedPermissions.orEmpty().firstNotNullOfOrNull { GmsRules.c2dmPermissionTarget(it) }
    }

    /** `IPackageManager.getPackageInfo` 依呼叫端套用 package 可見性，結果與 app 自己查詢相同。 */
    fun isVisible(pkg: String): Boolean {
        val pm = packageManager()
        val getPackageInfo = pm.javaClass.methods.first { it.name == "getPackageInfo" && it.parameterTypes.size == 3 }
        return getPackageInfo.invoke(pm, pkg, zeroFlags(getPackageInfo), userId()) != null
    }

    private fun packageManager(): Any =
        Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null)!!

    // Android 13 起 flags 是 long，之前是 int
    private fun zeroFlags(m: Method): Any = if (m.parameterTypes[1] == Long::class.javaPrimitiveType) 0L else 0

    private fun userId() = Process.myUid() / PER_USER_RANGE

    private const val GET_PERMISSIONS = PackageManager.GET_PERMISSIONS
    private const val PER_USER_RANGE = 100000
}
