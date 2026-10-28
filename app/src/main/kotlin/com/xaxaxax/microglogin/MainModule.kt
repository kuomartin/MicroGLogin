package com.xaxaxax.microglogin

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class MainModule : XposedModule() {

    /** MicroG RE 可見時才建立；[onPackageReady] 依此決定是否安裝需要 app class 的 hook。 */
    private var rules: GmsRules? = null

    /** 在 `AppComponentFactory` 建立前啟動，app 在 `<clinit>` 就建立的 GMS intent 也攔得到。 */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        Log.attach(this)
        Log.i("framework: $frameworkName $frameworkVersion ($frameworkVersionCode), package: ${param.packageName}")
        val visible = try {
            MicroGRe.isVisible()
        } catch (t: Throwable) {
            Log.e("failed to look up MicroG RE", t)
            false
        }
        if (!visible) {
            Log.w("MicroG RE is not installed or not visible to the app (add QUERY_ALL_PACKAGES), GMS redirect disabled")
            return
        }
        // 每組 hook 各自隔離：其中一組失敗時，其他組照常生效
        val rules = GmsRules(param.packageName).also { this.rules = it }
        install("GmsRedirect") { GmsRedirector(this, rules).install() }
        install("AccountManager") { AccountManagerHooks.install(this) }
        install("CredentialManager") { CredentialManagerHooks.install(this) }
    }

    /** 需要 app class 的 hook：app 的 class loader 這時才建立。 */
    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        val rules = rules ?: return
        install("CredentialType") {
            if (!CredentialTypeHooks(this, rules).install(param.classLoader)) Log.i("app has no identitycredentials client")
        }
    }

    private fun install(name: String, block: () -> Unit) {
        try {
            block()
            Log.i("$name hooks installed")
        } catch (t: Throwable) {
            Log.e("$name hooks failed", t)
        }
    }
}
