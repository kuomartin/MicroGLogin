package com.xaxaxax.microglogin

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 讓 `CHOOSE_ACCOUNT` 的系統帳號選擇器（由 [GmsRedirector] 導過去）只列替代品的帳號。
 *
 * Play 服務函式庫的 `AccountPicker` 只允許 `com.google`，系統選擇器就列出 Google Play 服務的帳號。
 * 這些帳號在重新簽名的 app 拿不到 token（Google Play 服務比對簽章），[AccountManagerHooks] 會改用替代品的同名帳號，
 * 替代品沒有那個帳號就失敗；沒有 Google Play 服務的裝置上，清單只剩新增 `com.google` 帳號。
 * 所以送出前把允許的類型與帳號換成替代品的，結果回到 app 時再把類型改回 `com.google`：
 * app 與 Play 服務函式庫只認得 `com.google`，之後要 token 時由 [AccountManagerHooks] 換回替代品。
 *
 * extra 在框架出口改寫：app 通常在設定 action 之後才放 extra。
 */
internal class AccountPickerHooks(private val rules: GmsRules, private val accountType: TargetAccountType) {

    /** 送出 `CHOOSE_ACCOUNT` 的 request code 對應的替代品帳號類型，等結果回來時改回。 */
    private val pending = ConcurrentHashMap<Int, String>()

    fun install(xposed: XposedInterface) {
        // execStartActivity 的參數：Context、IBinder、IBinder、目標、Intent、requestCode、…
        for (m in Instrumentation::class.java.declaredMethods.filter { it.name == "execStartActivity" && !Modifier.isAbstract(it.modifiers) }) {
            xposed.hook(m).intercept { chain ->
                val index = chain.args.indexOfFirst { it is Intent }
                val intent = chain.args.getOrNull(index) as Intent?
                val context = chain.args.firstOrNull() as? Context
                if (intent != null && context != null && rules.isChooseAccountAction(intent.action)) {
                    accountType.get(AccountManager.get(context))?.let { type ->
                        restrictToTarget(intent, type)
                        val requestCode = chain.args.getOrNull(index + 1) as? Int
                        if (requestCode != null && requestCode >= 0) pending[requestCode] = type
                    }
                }
                chain.proceed()
            }
        }
        for (m in Activity::class.java.declaredMethods.filter { it.name == "dispatchActivityResult" }) {
            xposed.hook(m).intercept { chain ->
                val type = (chain.args.getOrNull(1) as? Int)?.let { pending.remove(it) }
                val data = chain.args.getOrNull(3) as Intent?
                if (type != null && data != null && data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) == type) {
                    Log.d("account picker result: $type -> ${GmsRules.GOOGLE_ACCOUNT_TYPE}")
                    data.putExtra(AccountManager.KEY_ACCOUNT_TYPE, GmsRules.GOOGLE_ACCOUNT_TYPE)
                }
                chain.proceed()
            }
        }
    }

    private fun restrictToTarget(intent: Intent, type: String) {
        val types = GmsRules.pickerAccountTypes(intent.getStringArrayExtra(EXTRA_ALLOWABLE_ACCOUNT_TYPES), type)
        Log.d("account picker limited to ${types.joinToString()}")
        intent.putExtra(EXTRA_ALLOWABLE_ACCOUNT_TYPES, types)
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Account>(EXTRA_ALLOWABLE_ACCOUNTS)?.let { accounts ->
            intent.putParcelableArrayListExtra(EXTRA_ALLOWABLE_ACCOUNTS, ArrayList(accounts.map { toTarget(it, type) }))
        }
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Account>(EXTRA_SELECTED_ACCOUNT)?.let {
            intent.putExtra(EXTRA_SELECTED_ACCOUNT, toTarget(it, type))
        }
    }

    private fun toTarget(account: Account, type: String) =
        if (account.type == GmsRules.GOOGLE_ACCOUNT_TYPE) Account(account.name, type) else account

    private companion object {
        // ChooseTypeAndAccountActivity 讀的 extra，與 AccountManager.newChooseAccountIntent 相同
        const val EXTRA_ALLOWABLE_ACCOUNT_TYPES = "allowableAccountTypes"
        const val EXTRA_ALLOWABLE_ACCOUNTS = "allowableAccounts"
        const val EXTRA_SELECTED_ACCOUNT = "selectedAccount"
    }
}
