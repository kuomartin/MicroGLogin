package com.xaxaxax.microglogin

import io.github.libxposed.api.XposedInterface

/**
 * Android 14 以上讓 androidx Credential Manager 改走 Google Play 服務實作（實驗）。
 *
 * androidx 在 Android 14 以上優先使用系統 `CredentialManager`：請求經 system_server 交給 Google Play 服務的
 * provider，呼叫端身分由系統提供，不經 app 自己的 intent，[GmsRedirector] 攔不到，Google Play 服務比對簽章失敗。
 * 拿不到系統 service 時，androidx 退回 `CredentialProviderPlayServicesImpl`，它在 app process 內 bind GMS，
 * 就會被導向 MicroG RE。副作用：app 的 passkey 也改走 MicroG RE。
 *
 * `Context.getSystemService(Class)` 先轉成 service 名稱再呼叫 `getSystemService(String)`，所以只 hook 後者。
 */
internal object CredentialManagerHooks {

    private const val CREDENTIAL_SERVICE = "credential"

    fun install(xposed: XposedInterface) {
        val method = Class.forName("android.app.ContextImpl").getDeclaredMethod("getSystemService", String::class.java)
        xposed.hook(method).intercept { chain ->
            if (chain.getArg(0) == CREDENTIAL_SERVICE) {
                Log.d("hiding the system credential service")
                null
            } else {
                chain.proceed()
            }
        }
    }
}
