package com.xaxaxax.microglogin

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier

/**
 * 把 app 對 Google Play 服務的 intent 與 provider 呼叫改指向 MicroG RE，規則見 [GmsRules]。
 *
 * app 以 patch 工具的金鑰重新簽名，Google Play 服務在比對呼叫端簽章的地方（最明顯的是 Google 登入）會拒絕它；
 * MicroG RE 以自己的 package 回應同樣的請求，並依 patch 時寫入的 `SPOOFED_PACKAGE_SIGNATURE` 向 Google 回報原始簽章。
 *
 * intent 的 package、component 與 action 可以用任何順序設定，所以每個 setter 都套用改寫，
 * 再由最後完成 intent 的那個 setter 修正例外（self-targeted、推播、帳號選擇器）。
 * 修正時直接寫欄位，因為 setter 都被 hook 了，透過 setter 會再被改寫一次。
 */
internal class GmsRedirector(private val xposed: XposedInterface, private val rules: GmsRules) {

    private val actionField = Intent::class.java.getDeclaredField("mAction").apply { isAccessible = true }
    private val packageField = Intent::class.java.getDeclaredField("mPackage").apply { isAccessible = true }
    private val componentField = Intent::class.java.getDeclaredField("mComponent").apply { isAccessible = true }
    private val dataField = Intent::class.java.getDeclaredField("mData").apply { isAccessible = true }

    fun install() {
        hookSetPackage()
        hookSetAction()
        hookSetComponent()
        hookSetClass()
        hookConstructors()
        hookActivityStart()
        hookEgress()
        hookContentResolver()
    }

    private fun hookSetPackage() = hookAll(Intent::class.java, "setPackage") { chain ->
        val intent = chain.thisObject as Intent
        val args = chain.args.toTypedArray()
        // 先設 CHOOSE_ACCOUNT 再設 package 的情況；反過來的順序由 setAction 與 startActivity 處理
        args[0] = if (rules.isChooseAccountAction(intent.action)) null else rules.redirectPackage(args[0] as String?) ?: args[0]
        val result = chain.proceed(args)
        fixUp(intent)
        result
    }

    private fun hookSetAction() = hookAll(Intent::class.java, "setAction") { chain ->
        val intent = chain.thisObject as Intent
        val args = chain.args.toTypedArray()
        if (!rules.isSelfTargeted(intent.`package`, intent.component?.packageName)) {
            val action = args[0] as String?
            rules.redirectAction(action)?.let {
                Log.d("action $action -> $it")
                args[0] = it
            }
        }
        val result = chain.proceed(args)
        keepPushWithGms(intent)
        routeChooseAccountToSystem(intent)
        result
    }

    private fun hookSetComponent() = hookAll(Intent::class.java, "setComponent") { chain ->
        val intent = chain.thisObject as Intent
        val args = chain.args.toTypedArray()
        (args[0] as ComponentName?)?.let { cn ->
            rules.redirectPackage(cn.packageName)?.let { args[0] = ComponentName(it, cn.className) }
        }
        val result = chain.proceed(args)
        fixUp(intent)
        result
    }

    /**
     * `setClass`、`setClassName` 不經過 `setComponent` 就設定 component；`readFromParcel`（activity 收到自己的
     * 啟動 intent 時走這裡）先經 `setAction` 讀 action，之後才讀 component。
     */
    private fun hookSetClass() {
        for (name in listOf("setClass", "setClassName", "readFromParcel")) {
            hookAll(Intent::class.java, name) { chain ->
                val result = chain.proceed()
                val intent = chain.thisObject as Intent
                restoreSelfTargetedAction(intent)
                keepPushWithGms(intent)
                result
            }
        }
    }

    private fun hookConstructors() {
        for (ctor in Intent::class.java.declaredConstructors) {
            xposed.hook(ctor).intercept { chain ->
                val result = chain.proceed()
                val intent = chain.thisObject as Intent
                intent.component?.let { cn ->
                    rules.redirectPackage(cn.packageName)?.let { intent.component = ComponentName(it, cn.className) }
                }
                rules.redirectPackage(intent.`package`)?.let { intent.`package` = it }
                rules.redirectAction(intent.action)?.let { intent.action = it }
                fixUp(intent)
                result
            }
        }
    }

    /** 直接呼叫 `startActivity`／`startActivityForResult` 前的最後檢查；包在 PendingIntent 裡的靠前面的 setter hook。 */
    private fun hookActivityStart() {
        val hooker = XposedInterface.Hooker { chain ->
            (chain.args.firstOrNull { it is Intent } as Intent?)?.let(::fixUp)
            chain.proceed()
        }
        hookAll(ContextWrapper::class.java, "startActivity", hooker)
        hookAll(Activity::class.java, "startActivityForResult", hooker)
    }

    /**
     * 框架內部送出 intent 的地方。app 經 AOT 編譯後，`Intent` 的建構子與 setter 可能被 inline 進 app 的程式碼，
     * 上面的 hook 就攔不到（LINE 實測：identitycredentials、phenotype 直接綁到 Google Play 服務）。
     * `bindService`、`startService`、`startActivity` 最後都經過這裡，在送出前再完整改寫一次。
     * 新版 Play 服務函式庫綁定的 chimera component 也在這裡拆回原本的 action（見 [GmsRules.unwrapChimeraAction]）。
     */
    private fun hookEgress() {
        val hooker = XposedInterface.Hooker { chain ->
            for (arg in chain.args) {
                when (arg) {
                    is Intent -> redirect(arg)
                    is Array<*> -> arg.forEach { if (it is Intent) redirect(it) }
                }
            }
            chain.proceed()
        }
        val contextImpl = Class.forName("android.app.ContextImpl")
        hookAll(contextImpl, "bindServiceCommon", hooker)
        hookAll(contextImpl, "startServiceCommon", hooker)
        hookAll(Instrumentation::class.java, "execStartActivity", hooker)
        hookAll(Instrumentation::class.java, "execStartActivities", hooker)
    }

    /** 不經 setter，直接改寫欄位，套用所有規則。 */
    private fun redirect(intent: Intent) {
        val before = intent.toUri(0)
        val data = intent.data
        rules.unwrapChimeraAction(intent.action, intent.component?.packageName, data?.scheme, data?.schemeSpecificPart)?.let {
            actionField.set(intent, it)
            dataField.set(intent, null)
            componentField.set(intent, null)
            packageField.set(intent, GmsRules.GMS_PACKAGE)
        }
        intent.component?.let { cn ->
            rules.redirectPackage(cn.packageName)?.let { componentField.set(intent, ComponentName(it, cn.className)) }
        }
        rules.redirectPackage(intent.`package`)?.let { packageField.set(intent, it) }
        if (!rules.isSelfTargeted(intent.`package`, intent.component?.packageName)) {
            rules.redirectAction(intent.action)?.let { actionField.set(intent, it) }
        }
        fixUp(intent)
        val after = intent.toUri(0)
        if (after != before) Log.d("egress $before -> $after")
    }

    private fun hookContentResolver() {
        val acquire = XposedInterface.Hooker { chain ->
            val args = chain.args.toTypedArray()
            args[0] = redirectProviderArg(args[0])
            chain.proceed(args)
        }
        for (name in listOf(
            "acquireProvider", "acquireContentProviderClient",
            "acquireUnstableProvider", "acquireUnstableContentProviderClient",
        )) {
            hookAll(ContentResolver::class.java, name, acquire)
        }
        // call(Uri, ...) 與 call(String authority, ...)；第一個以外的字串參數是 method 與 arg，不能改
        hookAll(ContentResolver::class.java, "call") { chain ->
            val args = chain.args.toTypedArray()
            for (i in args.indices) {
                if (args[i] is Uri || (i == 0 && args[i] is String)) args[i] = redirectProviderArg(args[i])
            }
            chain.proceed(args)
        }
    }

    private fun redirectProviderArg(arg: Any?): Any? = when (arg) {
        is Uri -> rules.redirectAuthority(arg.authority)?.let { arg.buildUpon().authority(it).build() } ?: arg
        is String -> rules.redirectAuthority(arg) ?: arg
        else -> arg
    }

    private fun fixUp(intent: Intent) {
        restoreSelfTargetedAction(intent)
        keepPushWithGms(intent)
        routeChooseAccountToSystem(intent)
    }

    /** 指向 app 自己的 intent，還原已被改寫的 action。action 通常比目標先設定，例如 `Intent(action).setClass(...)`。 */
    private fun restoreSelfTargetedAction(intent: Intent) {
        if (!rules.isSelfTargeted(intent.`package`, intent.component?.packageName)) return
        val action = actionField.get(intent) as String?
        rules.originalAction(action)?.let {
            Log.d("app-internal action $action -> $it")
            actionField.set(intent, it)
        }
    }

    /** 已被導向 MicroG RE 的推播 intent，改回 Google Play 服務。 */
    private fun keepPushWithGms(intent: Intent) {
        if (!rules.isPushAction(intent.action)) return
        if (intent.`package` == GmsRules.MICROG_RE_PACKAGE) {
            Log.d("keeping push intent with ${GmsRules.GMS_PACKAGE}: ${intent.action}")
            packageField.set(intent, GmsRules.GMS_PACKAGE)
        }
        val cn = intent.component
        if (cn != null && cn.packageName == GmsRules.MICROG_RE_PACKAGE) {
            componentField.set(intent, ComponentName(GmsRules.GMS_PACKAGE, cn.className))
        }
    }

    private fun routeChooseAccountToSystem(intent: Intent) {
        if (!rules.isChooseAccountAction(intent.action)) return
        if (intent.component != SYSTEM_ACCOUNT_PICKER || intent.`package` != null) {
            Log.d("routing CHOOSE_ACCOUNT to the system account picker")
            componentField.set(intent, SYSTEM_ACCOUNT_PICKER)
            packageField.set(intent, null)
        }
    }

    private fun hookAll(cls: Class<*>, name: String, hooker: XposedInterface.Hooker) {
        // 個別方法在某些 Android 版本不存在時只記錄，不影響其他 hook；abstract 方法由具體實作的多載轉呼叫，不需要 hook
        val methods = cls.declaredMethods.filter { it.name == name && !Modifier.isAbstract(it.modifiers) }
        if (methods.isEmpty()) Log.w("${cls.name}.$name not found")
        for (m in methods) {
            try {
                xposed.hook(m).intercept(hooker)
            } catch (t: Throwable) {
                Log.e("failed to hook $m", t)
            }
        }
    }

    private companion object {
        val SYSTEM_ACCOUNT_PICKER = ComponentName("android", "android.accounts.ChooseTypeAndAccountActivity")
    }
}
