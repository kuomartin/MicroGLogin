package com.xaxaxax.microglogin

/**
 * 哪些 GMS 目標要改指向 MicroG RE。只處理字串，不碰 Android 物件，hook 端負責套用。
 *
 * MicroG RE 把 package 改名為 `app.revanced.android.gms`，action 也跟著改名，所以 package、
 * component、provider authority 與 action 都要改寫。例外：
 * - 推播（`com.google.android.c2dm.*`、`com.google.iid.*`）留給 Google Play 服務：FCM 不比對呼叫端簽章，
 *   app 的 manifest 也保留 Google Play 服務送推播用的名稱。
 * - `CHOOSE_ACCOUNT` 交給系統帳號選擇器。
 * - `com.google.android.gms.chimera`：MicroG 沒有 Dynamite／Chimera 模組，導過去會造成 authority 不符。
 * - 指向 app 自己的 intent 不改 action：Google 登入程式庫以 `GOOGLE_SIGN_IN` 開啟 app 內的
 *   `SignInHubActivity`，它收到其他 action 就直接結束。
 *
 * 所有規則都是冪等的：已經指向 MicroG RE 的值不會再被改寫。
 */
internal class GmsRules(private val selfPackage: String) {

    fun redirectPackage(pkg: String?): String? =
        if (pkg == GMS_PACKAGE || pkg == GSF_PACKAGE) MICROG_RE_PACKAGE else null

    fun redirectAuthority(authority: String?): String? = when {
        authority == null || authority == CHIMERA_AUTHORITY -> null
        authority == GMS_PACKAGE -> MICROG_RE_PACKAGE
        authority.startsWith("$GMS_PACKAGE.") -> MICROG_RE_PACKAGE + authority.substring(GMS_PACKAGE.length)
        authority.startsWith(GSF_PACKAGE) -> MICROG_RE_PACKAGE + authority.substring(GSF_PACKAGE.length)
        else -> null
    }

    fun redirectAction(action: String?): String? {
        if (action == null || isChooseAccountAction(action) || !action.startsWith(GMS_ACTION_PREFIX)) return null
        return MICROG_RE_ACTION_PREFIX + action.substring(GMS_ACTION_PREFIX.length)
    }

    /** [redirectAction] 改寫前的 action；不是改寫過的 action 則回傳 null。 */
    fun originalAction(action: String?): String? {
        if (action == null || isChooseAccountAction(action) || !action.startsWith(MICROG_RE_ACTION_PREFIX)) return null
        return GMS_ACTION_PREFIX + action.substring(MICROG_RE_ACTION_PREFIX.length)
    }

    /**
     * 新版 Play 服務函式庫先向 Google Play 服務的 chimera provider 查到實作的 component，再以
     * `com.google.android.chimera.BoundService.START` 加上 `chimera-action:<原本的 action>` 直接綁定它。
     * MicroG RE 沒有這些 component，所以要拆回原本的 action；推播與非 GMS 的 intent 回傳 null，維持原樣。
     */
    fun unwrapChimeraAction(action: String?, componentPackage: String?, scheme: String?, ssp: String?): String? {
        if (action != CHIMERA_BOUND_SERVICE || componentPackage != GMS_PACKAGE || scheme != CHIMERA_SCHEME) return null
        return ssp?.takeIf { it.isNotEmpty() && !isPushAction(it) }
    }

    fun isPushAction(action: String?): Boolean =
        action != null && PUSH_ACTION_PREFIXES.any { action.startsWith(it) }

    fun isChooseAccountAction(action: String?): Boolean =
        action == GMS_ACTION_PREFIX + CHOOSE_ACCOUNT || action == MICROG_RE_ACTION_PREFIX + CHOOSE_ACCOUNT

    fun isSelfTargeted(pkg: String?, componentPackage: String?): Boolean =
        pkg == selfPackage || componentPackage == selfPackage

    companion object {
        const val GMS_PACKAGE = "com.google.android.gms"
        const val MICROG_RE_PACKAGE = "app.revanced.android.gms"
        private const val GSF_PACKAGE = "com.google.android.gsf"
        private const val CHIMERA_AUTHORITY = "$GMS_PACKAGE.chimera"
        private const val GMS_ACTION_PREFIX = "$GMS_PACKAGE."
        private const val MICROG_RE_ACTION_PREFIX = "$MICROG_RE_PACKAGE."
        private const val CHIMERA_BOUND_SERVICE = "com.google.android.chimera.BoundService.START"
        private const val CHIMERA_SCHEME = "chimera-action"
        private const val CHOOSE_ACCOUNT = "common.account.CHOOSE_ACCOUNT"
        private val PUSH_ACTION_PREFIXES = listOf("com.google.android.c2dm.", "com.google.iid.")
    }
}
