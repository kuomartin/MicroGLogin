package com.xaxaxax.microglogin

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * [target] 是否有元件宣告 [action]（activity、service 或 receiver 任一）。結果快取：每個 action 只查一次。
     * 查詢用的 intent 會經過本模組的 hook，但 action 已是替代品的名稱、package 已是替代品，不會再被改寫。
     */
    fun declarer(target: String): (String) -> Boolean {
        val cache = ConcurrentHashMap<String, Boolean>()
        return { action -> cache.getOrPut(action) { resolves(target, action) } }
    }

    private fun resolves(target: String, action: String): Boolean = runCatching {
        val pm = packageManager()
        val intent = Intent(action).setPackage(target)
        QUERIES.any { name ->
            val query = pm.javaClass.methods.first { it.name == name && it.parameterTypes.size == 4 }
            val flags: Any = if (query.parameterTypes[2] == Long::class.javaPrimitiveType) 0L else 0
            val slice = query.invoke(pm, intent, null, flags, userId()) ?: return@any false
            (slice.javaClass.getMethod("getList").invoke(slice) as List<*>).isNotEmpty()
        }
    }.onFailure { Log.e("failed to resolve $action in $target", it) }.getOrDefault(true)

    private fun packageManager(): Any =
        Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null)!!

    // Android 13 起 flags 是 long，之前是 int
    private fun zeroFlags(m: Method): Any = if (m.parameterTypes[1] == Long::class.javaPrimitiveType) 0L else 0

    private fun userId() = Process.myUid() / PER_USER_RANGE

    private const val GET_PERMISSIONS = PackageManager.GET_PERMISSIONS
    private val QUERIES = listOf("queryIntentActivities", "queryIntentServices", "queryIntentReceivers")
    private const val PER_USER_RANGE = 100000
}
