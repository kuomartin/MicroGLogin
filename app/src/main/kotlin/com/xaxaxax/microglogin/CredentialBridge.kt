package com.xaxaxax.microglogin

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.credentials.Credential
import android.credentials.CredentialManager
import android.credentials.GetCredentialException
import android.credentials.GetCredentialRequest
import android.credentials.GetCredentialResponse
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.Executor

/**
 * Android 14 以上，在 app 內代接 Credential Manager 的 Google 登入，交給替代品的帳號對話框。
 *
 * 系統只把 Google ID 請求交給裝在 `/system` 的系統 provider（googleid 的選項要求系統 provider），一般安裝的
 * MicroG RE 接不到，請求會到 Google Play 服務而簽章不符（microg/GmsCore#3694）。框架的
 * `CredentialManager.getCredential` 在 app process 內執行，請求含 Google ID 選項時在這裡接手：以呼叫端
 * Activity 開啟替代品的 `ASSISTED_SIGNIN`，帶 `GetSignInIntentRequest`，替代品就顯示帳號對話框
 * （`AuthSignInActivity`）；結果在 `Activity.dispatchActivityResult` 收回，組成 `GetCredentialResponse`。
 * 同一個請求裡的 passkey、密碼選項捨棄；不含 Google ID 的請求照常交給系統。
 */
@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class CredentialBridge(private val xposed: XposedInterface, private val target: String) {

    private class Pending(
        val executor: Executor,
        val callback: OutcomeReceiver<GetCredentialResponse, GetCredentialException>,
        val cancel: CancellationSignal?,
    )

    @Volatile
    private var pending: Pending? = null

    fun install() {
        val getCredential = CredentialManager::class.java.getMethod(
            "getCredential", Context::class.java, GetCredentialRequest::class.java,
            CancellationSignal::class.java, Executor::class.java, OutcomeReceiver::class.java,
        )
        xposed.hook(getCredential).intercept { chain ->
            val context = chain.getArg(0) as Context
            val request = chain.getArg(1) as GetCredentialRequest
            val option = request.credentialOptions.firstOrNull { GoogleIdRequest.isGoogleId(it.type) }
                ?: return@intercept chain.proceed()
            val activity = context as? Activity
            if (activity == null) {
                Log.w("getCredential from a non-Activity context, left to the system")
                return@intercept chain.proceed()
            }
            val params = GoogleIdRequest.parse(option.type) { key ->
                option.credentialRetrievalData.get(key) ?: option.candidateQueryData.get(key)
            }
            @Suppress("UNCHECKED_CAST")
            val callback = chain.getArg(4) as OutcomeReceiver<GetCredentialResponse, GetCredentialException>
            val executor = chain.getArg(3) as Executor
            if (params == null) {
                executor.execute { callback.onError(GetCredentialException(GetCredentialException.TYPE_UNKNOWN, "Google ID option without a server client id")) }
                return@intercept null
            }
            start(activity, params, Pending(executor, callback, chain.getArg(2) as CancellationSignal?))
            null
        }

        // 呼叫端 Activity 收到替代品的結果時接手，不交給 app：app 不認得這個 request code
        for (m in Activity::class.java.declaredMethods.filter { it.name == "dispatchActivityResult" }) {
            xposed.hook(m).intercept { chain ->
                val requestCode = chain.args.getOrNull(1) as? Int
                if (requestCode != REQUEST_CODE || pending == null) return@intercept chain.proceed()
                finish(chain.args[2] as Int, chain.args[3] as Intent?)
                null
            }
        }
    }

    private fun start(activity: Activity, params: GoogleIdRequest, request: Pending) {
        pending?.let { deliverError(it, GetCredentialException.TYPE_UNKNOWN, "superseded by a newer request") }
        pending = request
        request.cancel?.setOnCancelListener {
            Log.d("Google ID request canceled by the app")
            if (pending === request) pending = null
        }
        val intent = Intent("$target.auth.api.credentials.ASSISTED_SIGNIN").setPackage(target)
            .putExtra(EXTRA_CLIENT_PACKAGE, activity.packageName)
            .putExtra(EXTRA_SIGN_IN_OPTIONS, signInOptions(params))
            .putExtra(EXTRA_SIGN_IN_INTENT_REQUEST, signInIntentRequest(params))
        Log.i("Google ID request (${if (params.signInWithGoogle) "Sign in with Google" else "Google ID"}) handed to $target")
        try {
            activity.startActivityForResult(intent, REQUEST_CODE)
        } catch (t: Throwable) {
            Log.e("failed to open $target's sign-in", t)
            pending = null
            deliverError(request, GetCredentialException.TYPE_UNKNOWN, t.message)
        }
    }

    private fun finish(resultCode: Int, data: Intent?) {
        val request = pending ?: return
        pending = null
        val credential = data?.getByteArrayExtra(EXTRA_SIGN_IN_CREDENTIAL)
        if (resultCode != Activity.RESULT_OK || credential == null) {
            val status = data?.getByteArrayExtra(EXTRA_STATUS)?.let { runCatching { SafeParcel.unmarshall(it, STATUS_FIELDS) }.getOrNull() }
            val message = status?.get(2) as String?
            val canceled = status == null || status[1] == STATUS_CANCELED
            Log.i("$target returned no credential: ${status?.get(1)} $message")
            return deliverError(
                request,
                if (canceled) GetCredentialException.TYPE_USER_CANCELED else GetCredentialException.TYPE_UNKNOWN,
                message,
            )
        }
        val response = try {
            response(SafeParcel.unmarshall(credential, CREDENTIAL_FIELDS))
        } catch (t: Throwable) {
            Log.e("failed to read $target's credential", t)
            return deliverError(request, GetCredentialException.TYPE_UNKNOWN, t.message)
        }
        Log.i("Google ID credential delivered to the app")
        request.executor.execute { request.callback.onResult(response) }
    }

    private fun response(fields: Map<Int, Any?>): GetCredentialResponse {
        val credential = GoogleIdRequest.Credential(
            id = fields[1] as String?,
            displayName = fields[2] as String?,
            givenName = fields[3] as String?,
            familyName = fields[4] as String?,
            profilePictureUri = (fields[5] as Uri?)?.toString(),
            idToken = fields[7] as String?,
        )
        val data = Bundle()
        for ((key, value) in credential.fields()) data.putString(key, value)
        credential.profilePictureUri?.let { data.putParcelable(GoogleIdRequest.KEY_PROFILE_PICTURE_URI, Uri.parse(it)) }
        return GetCredentialResponse(Credential(GoogleIdRequest.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, data))
    }

    private fun deliverError(request: Pending, type: String, message: String?) {
        if (request.cancel?.isCanceled == true) return
        request.executor.execute { request.callback.onError(GetCredentialException(type, message)) }
    }

    /**
     * `GoogleSignInOptions`：1 版本、2 scopes、4 要 ID token、7 server client id、8 hosted domain、9 extensions。
     * extensions 要寫成空清單：替代品轉交 `AuthSignInActivity` 時重新序列化，null 會在 `getExtensions` 拋例外。
     */
    private fun signInOptions(params: GoogleIdRequest) = SafeParcel.marshall {
        int(1, 3)
        objects(2, SCOPES.map { scope -> { int(1, 1); string(2, scope) } })
        boolean(4, true)
        boolean(5, false)
        boolean(6, false)
        string(7, params.serverClientId)
        string(8, params.hostedDomainFilter)
        objects(9, emptyList())
    }

    /** `GetSignInIntentRequest`：1 server client id、2 hosted domain、4 nonce、5 要驗證過的電話、6 主題。 */
    private fun signInIntentRequest(params: GoogleIdRequest) = SafeParcel.marshall {
        string(1, params.serverClientId)
        string(2, params.hostedDomainFilter)
        string(4, params.nonce)
        boolean(5, params.requestVerifiedPhoneNumber)
        int(6, 0)
    }

    private companion object {
        /** 模組專用的 request code；FragmentActivity 只接受 16 位元以內的值。 */
        const val REQUEST_CODE = 0x4D47
        const val EXTRA_CLIENT_PACKAGE = "client_package_name"
        const val EXTRA_SIGN_IN_OPTIONS = "google_sign_in_options"
        const val EXTRA_SIGN_IN_INTENT_REQUEST = "get_sign_in_intent_request"
        const val EXTRA_SIGN_IN_CREDENTIAL = "sign_in_credential"
        const val EXTRA_STATUS = "status"
        const val STATUS_CANCELED = 16
        val SCOPES = listOf("openid", "profile", "email")

        /** `SignInCredential`：1 id、2–4 名稱、5 頭像、7 ID token。 */
        val CREDENTIAL_FIELDS = mapOf(
            1 to SafeParcel.Type.STRING, 2 to SafeParcel.Type.STRING, 3 to SafeParcel.Type.STRING,
            4 to SafeParcel.Type.STRING, 5 to SafeParcel.Type.URI, 7 to SafeParcel.Type.STRING,
        )

        /** `Status`：1 狀態碼、2 訊息。 */
        val STATUS_FIELDS = mapOf(1 to SafeParcel.Type.INT, 2 to SafeParcel.Type.STRING)
    }
}
