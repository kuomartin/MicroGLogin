package com.xaxaxax.microglogin

import android.accounts.AccountManager

/** 替代品的 authenticator 註冊的帳號類型（MicroG RE 是 `app.revanced`），查到一次後快取。 */
internal class TargetAccountType(private val target: String) {

    @Volatile
    private var cached: String? = null

    /** 替代品沒有註冊 authenticator 時回傳 null。 */
    fun get(am: AccountManager): String? =
        cached ?: am.authenticatorTypes
            .firstOrNull { it.packageName == target }?.type
            ?.also { cached = it }
}
