package com.xaxaxax.microglogin

import android.accounts.Account
import android.accounts.AccountManager
import io.github.libxposed.api.XposedInterface

/**
 * 把 Google 帳號的 token 請求交給替代品（例如 MicroG RE）的同名帳號。
 *
 * 登入後，app（例如 Cordova 的 Google 登入外掛）以 `com.google` 類型的帳號呼叫 `AccountManager.getAuthToken`。
 * AccountManager 把它交給 Google Play 服務的 authenticator，Google Play 服務比對到重新簽名的簽章，
 * 回傳 `UNREGISTERED_ON_API_CONSOLE`。替代品以自己的帳號類型註冊 authenticator（MicroG RE 是 `app.revanced`），
 * 把帳號類型換成它的，請求就交給替代品，由它依 `SPOOFED_PACKAGE_SIGNATURE` 回報原始簽章。
 *
 * `getAuthToken` 的每個多載與 `blockingGetAuthToken` 第一個參數都是帳號。
 */
internal class AccountManagerHooks(private val target: String) {

    @Volatile
    private var targetAccountType: String? = null

    fun install(xposed: XposedInterface) {
        val hooker = XposedInterface.Hooker { chain ->
            val account = chain.args.firstOrNull() as? Account
            val type = if (account?.type == GOOGLE_ACCOUNT_TYPE) targetAccountType(chain.thisObject as AccountManager) else null
            if (account == null || type == null) return@Hooker chain.proceed()
            Log.d("asking $target account type $type for an auth token")
            val args = chain.args.toTypedArray()
            args[0] = Account(account.name, type)
            chain.proceed(args)
        }
        for (name in listOf("getAuthToken", "blockingGetAuthToken")) {
            for (m in AccountManager::class.java.declaredMethods.filter { it.name == name }) {
                xposed.hook(m).intercept(hooker)
            }
        }
    }

    /** 替代品的 authenticator 註冊的帳號類型；沒有註冊則回傳 null。 */
    private fun targetAccountType(am: AccountManager): String? =
        targetAccountType ?: am.authenticatorTypes
            .firstOrNull { it.packageName == target }?.type
            ?.also { targetAccountType = it }

    private companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}
