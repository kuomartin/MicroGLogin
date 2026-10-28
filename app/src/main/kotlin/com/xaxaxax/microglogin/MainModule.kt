package com.xaxaxax.microglogin

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class MainModule : XposedModule() {

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
        install("GmsRedirect") { GmsRedirector(this, GmsRules(param.packageName)).install() }
        install("AccountManager") { AccountManagerHooks.install(this) }
        install("CredentialManager") { CredentialManagerHooks.install(this) }
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
