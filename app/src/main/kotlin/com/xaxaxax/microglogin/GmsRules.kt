package com.xaxaxax.microglogin

/**
 * 哪些 GMS 目標要改指向替代品。只處理字串，不碰 Android 物件，hook 端負責套用。
 *
 * 替代品是 `<vendor>.android.gms` 形式的 package（例如 MicroG RE 的 `app.revanced.android.gms`），它把 action、provider
 * authority 與憑證類型也改進自己的命名空間，所以這些都要改寫。不是這個形式的替代品沿用 Google 的名稱，只改 package。例外：
 * - 推播：[pushStaysWithGms] 為 true 時（app 的 manifest 保留 Google 的 C2DM 名稱），`com.google.android.c2dm.*`、
 *   `com.google.iid.*`、`com.google.android.gms.cloudmessaging.*` 留給 Google Play 服務，FCM 不比對呼叫端簽章。
 *   為 false 時（patch 工具已把 manifest 的 C2DM 改名），推播與其他呼叫一樣交給替代品，C2DM action 改成
 *   `<vendor>.android.c2dm.*`，與 loader 的改寫相同；loader 的 setter hook 漏掉的 intent 由這裡在出口補上。
 * - `CHOOSE_ACCOUNT` 交給系統帳號選擇器。
 * - `com.google.android.gms.chimera`：替代品沒有 Dynamite／Chimera 模組，導過去會造成 authority 不符。
 * - `com.google.android.gms.fonts`：可下載字型與簽章無關，替代品的字型 provider 回報的 authority 與請求不符而丟例外。
 * - 指向 app 自己的 intent 不改 action：Google 登入程式庫以 `GOOGLE_SIGN_IN` 開啟 app 內的
 *   `SignInHubActivity`，它收到其他 action 就直接結束。
 *
 * 所有規則都是冪等的：已經指向替代品的值不會再被改寫。
 */
internal class GmsRules(
    private val selfPackage: String,
    val target: String = MICROG_RE_PACKAGE,
    val pushStaysWithGms: Boolean = true,
) {
    private val renamesNamespace = target.endsWith(VENDOR_NAMESPACE_SUFFIX)
    private val targetActionPrefix = "$target."
    private val targetC2dmPrefix = target.removeSuffix(VENDOR_NAMESPACE_SUFFIX) + ".android.c2dm."
    private val targetGoogleIdTokenCredential =
        target.removeSuffix(VENDOR_NAMESPACE_SUFFIX) + ".android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"

    fun redirectPackage(pkg: String?): String? =
        if (pkg == GMS_PACKAGE || pkg == GSF_PACKAGE) target else null

    fun redirectAuthority(authority: String?): String? = when {
        authority == null || !renamesNamespace || authority in KEPT_AUTHORITIES -> null
        authority == GMS_PACKAGE -> target
        authority.startsWith("$GMS_PACKAGE.") -> target + authority.substring(GMS_PACKAGE.length)
        authority.startsWith(GSF_PACKAGE) -> target + authority.substring(GSF_PACKAGE.length)
        else -> null
    }

    fun redirectAction(action: String?): String? = when {
        action == null || !renamesNamespace || isChooseAccountAction(action) || isKeptPushAction(action) -> null
        action.startsWith(C2DM_PREFIX) -> targetC2dmPrefix + action.substring(C2DM_PREFIX.length)
        action.startsWith(GMS_ACTION_PREFIX) -> targetActionPrefix + action.substring(GMS_ACTION_PREFIX.length)
        else -> null
    }

    /** [redirectAction] 改寫前的 action；不是改寫過的 action 則回傳 null。 */
    fun originalAction(action: String?): String? {
        if (action == null || !renamesNamespace || isChooseAccountAction(action) || !action.startsWith(targetActionPrefix)) return null
        return GMS_ACTION_PREFIX + action.substring(targetActionPrefix.length)
    }

    /**
     * 新版 Play 服務函式庫先向 Google Play 服務的 chimera provider 查到實作的 component，再以
     * `com.google.android.chimera.BoundService.START` 加上 `chimera-action:<原本的 action>` 直接綁定它。
     * 回傳包在裡面的原本 action；不是這種 intent 則回傳 null。component 可能已被 setter hook 改成替代品。
     * 替代品沒有這些 component，所以呼叫端要拆回原本的 action；留在 Google Play 服務的推播則照原樣綁定。
     */
    fun chimeraAction(action: String?, componentPackage: String?, scheme: String?, ssp: String?): String? {
        if (action != CHIMERA_BOUND_SERVICE || scheme != CHIMERA_SCHEME) return null
        if (componentPackage != GMS_PACKAGE && componentPackage != target) return null
        return ssp?.takeIf { it.isNotEmpty() }
    }

    /**
     * 替代品把 Google ID 憑證類型改進自己的命名空間（`<vendor>.android.libraries...`，配合 ReVanced 改寫 app 字串的
     * patch），其他 bundle key 維持原名。沒經過那種 patch 的 app 送出原名，送出前換成替代品的名稱，回傳時換回來。
     */
    fun toTargetCredentialType(value: String?): String? =
        if (renamesNamespace && value == GOOGLE_ID_TOKEN_CREDENTIAL) targetGoogleIdTokenCredential else null

    fun fromTargetCredentialType(value: String?): String? =
        if (renamesNamespace && value == targetGoogleIdTokenCredential) GOOGLE_ID_TOKEN_CREDENTIAL else null

    /** 要留給 Google Play 服務的推播 action；推播交給替代品時一律為 false。 */
    fun isKeptPushAction(action: String?): Boolean =
        pushStaysWithGms && action != null && PUSH_ACTION_PREFIXES.any { action.startsWith(it) }

    fun isChooseAccountAction(action: String?): Boolean =
        action == GMS_ACTION_PREFIX + CHOOSE_ACCOUNT || action == targetActionPrefix + CHOOSE_ACCOUNT

    fun isSelfTargeted(pkg: String?, componentPackage: String?): Boolean =
        pkg == selfPackage || componentPackage == selfPackage

    companion object {
        const val GMS_PACKAGE = "com.google.android.gms"

        /** `<vendor>.android.c2dm.permission.RECEIVE` 對應的替代品 `<vendor>.android.gms`；Google 原名或其他權限回傳 null。 */
        fun c2dmPermissionTarget(permission: String): String? {
            if (!permission.endsWith(C2DM_RECEIVE_PERMISSION_SUFFIX)) return null
            val vendor = permission.removeSuffix(C2DM_RECEIVE_PERMISSION_SUFFIX)
            return if (vendor.isEmpty() || vendor == "com.google") null else vendor + VENDOR_NAMESPACE_SUFFIX
        }

        const val MICROG_RE_PACKAGE = "app.revanced.android.gms"
        private const val GSF_PACKAGE = "com.google.android.gsf"
        private const val VENDOR_NAMESPACE_SUFFIX = ".android.gms"
        private val KEPT_AUTHORITIES = setOf("$GMS_PACKAGE.chimera", "$GMS_PACKAGE.fonts")
        private const val GMS_ACTION_PREFIX = "$GMS_PACKAGE."
        private const val C2DM_PREFIX = "com.google.android.c2dm."
        private const val C2DM_RECEIVE_PERMISSION_SUFFIX = ".android.c2dm.permission.RECEIVE"
        private const val CHIMERA_BOUND_SERVICE = "com.google.android.chimera.BoundService.START"
        private const val CHIMERA_SCHEME = "chimera-action"
        private const val GOOGLE_ID_TOKEN_CREDENTIAL = "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"
        private const val CHOOSE_ACCOUNT = "common.account.CHOOSE_ACCOUNT"
        private val PUSH_ACTION_PREFIXES = listOf(C2DM_PREFIX, "com.google.iid.", "$GMS_PACKAGE.cloudmessaging.")
    }
}
