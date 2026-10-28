package com.xaxaxax.microglogin

import android.content.Intent
import android.credentials.Credential
import android.credentials.GetCredentialException
import android.credentials.GetCredentialResponse
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * Google ID 憑證類型在 app 與 MicroG RE 之間的改名，規則見 [GmsRules.toMicroGCredentialType]。
 *
 * - 送出：`com.google.android.gms.identitycredentials.GetCredentialRequest` 建好後，改寫其中 option 的類型。
 *   option 的 class 在 app 裡常被混淆，所以走訪欄位找出值相符的字串。
 * - 回傳：MicroG RE 的結果以 Bundle 放在 intent extra，androidx 的 `HiddenActivity` 經 `ResultReceiver.send`
 *   交回 app，在這裡把類型換回原名。MicroG RE 用的是 Android 13 以下的 Bundle 格式，Android 14 以上的 androidx
 *   要讀框架的 `GetCredentialResponse`／`GetCredentialException`，讀到 Bundle 會當成 null 而登入失敗，所以一併轉換。
 */
internal class CredentialTypeHooks(private val xposed: XposedInterface, private val rules: GmsRules) {

    private val extrasField = Intent::class.java.getDeclaredField("mExtras").apply { isAccessible = true }

    /** 回傳 false 表示 app 沒有用到 identitycredentials，不需要 hook。 */
    fun install(cl: ClassLoader): Boolean {
        val request = try {
            cl.loadClass("com.google.android.gms.identitycredentials.GetCredentialRequest")
        } catch (_: ClassNotFoundException) {
            return false
        }
        for (ctor in request.declaredConstructors) {
            xposed.hook(ctor).intercept { chain ->
                val result = chain.proceed()
                rewrite(chain.thisObject, rules::toMicroGCredentialType, cl)
                result
            }
        }
        val send = ResultReceiver::class.java.getDeclaredMethod("send", Int::class.javaPrimitiveType, Bundle::class.java)
        xposed.hook(send).intercept { chain ->
            val data = chain.getArg(1) as Bundle?
            rewrite(data, rules::fromMicroGCredentialType, cl)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) data?.let(::convertResults)
            chain.proceed()
        }
        return true
    }

    /** 把結果 intent 裡 Bundle 格式的回應與錯誤換成框架物件。 */
    @android.annotation.TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun convertResults(data: Bundle) {
        for (key in data.keySet()) {
            val extras = (data.get(key) as? Intent)?.let { extrasField.get(it) as Bundle? } ?: continue
            (extras.get(EXTRA_GET_RESPONSE) as? Bundle)?.let { response ->
                val type = response.getString(PROVIDER_CREDENTIAL_TYPE) ?: return@let
                val credentialData = response.getBundle(PROVIDER_CREDENTIAL_DATA) ?: Bundle()
                // MicroG RE 把頭像放成字串，googleid 函式庫讀的是 Uri
                credentialData.getString(GOOGLE_ID_PROFILE_PICTURE_URI)?.let {
                    credentialData.putParcelable(GOOGLE_ID_PROFILE_PICTURE_URI, Uri.parse(it))
                }
                extras.putParcelable(EXTRA_GET_RESPONSE, GetCredentialResponse(Credential(type, credentialData)))
                Log.d("converted credential response of type $type")
            }
            (extras.get(EXTRA_GET_EXCEPTION) as? Bundle)?.let { error ->
                val type = error.getString(PROVIDER_EXCEPTION_TYPE) ?: return@let
                val message = error.getString(PROVIDER_EXCEPTION_MESSAGE)
                extras.putSerializable(EXTRA_GET_EXCEPTION, GetCredentialException(type, message))
                Log.d("converted credential exception $type: $message")
            }
        }
    }

    private fun rewrite(root: Any?, map: (String?) -> String?, cl: ClassLoader) = Walker(map, cl).visit(root, 0)

    /** 走訪 Bundle、intent extra、List 與 app 自己的物件，改寫值相符的字串。 */
    private inner class Walker(private val map: (String?) -> String?, private val cl: ClassLoader) {
        private val seen = IdentityHashMap<Any, Unit>()

        fun visit(o: Any?, depth: Int) {
            if (o == null || depth > MAX_DEPTH || seen.put(o, Unit) != null) return
            when (o) {
                is Bundle -> for (key in o.keySet().toList()) {
                    val v = o.get(key)
                    val mapped = mapped(v)
                    if (mapped != null) o.putString(key, mapped) else visit(v, depth + 1)
                }
                is Intent -> visit(extrasField.get(o), depth + 1)
                is List<*> -> o.forEach { visit(it, depth + 1) }
                else -> visitFields(o, depth)
            }
        }

        private fun visitFields(o: Any, depth: Int) {
            var cls: Class<*>? = o.javaClass
            while (cls != null && cls.classLoader === cl) {
                for (f in cls.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    val v = f.get(o)
                    val mapped = mapped(v)
                    if (mapped != null) f.set(o, mapped) else visit(v, depth + 1)
                }
                cls = cls.superclass
            }
        }

        private fun mapped(v: Any?): String? =
            (v as? String)?.let(map)?.also { Log.d("credential type $v -> $it") }
    }

    private companion object {
        const val MAX_DEPTH = 5
        const val EXTRA_GET_RESPONSE = "android.service.credentials.extra.GET_CREDENTIAL_RESPONSE"
        const val EXTRA_GET_EXCEPTION = "android.service.credentials.extra.GET_CREDENTIAL_EXCEPTION"
        const val PROVIDER_CREDENTIAL_TYPE = "androidx.credentials.provider.extra.EXTRA_CREDENTIAL_TYPE"
        const val PROVIDER_CREDENTIAL_DATA = "androidx.credentials.provider.extra.EXTRA_CREDENTIAL_DATA"
        const val GOOGLE_ID_PROFILE_PICTURE_URI = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_PROFILE_PICTURE_URI"
        // MicroG RE 的取得錯誤也沿用 androidx 建立錯誤的 key
        const val PROVIDER_EXCEPTION_TYPE = "androidx.credentials.provider.extra.CREATE_CREDENTIAL_EXCEPTION_TYPE"
        const val PROVIDER_EXCEPTION_MESSAGE = "androidx.credentials.provider.extra.CREATE_CREDENTIAL_EXCEPTION_MESSAGE"
    }
}
