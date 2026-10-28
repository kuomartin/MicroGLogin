package com.xaxaxax.microglogin

/**
 * Credential Manager 的 Google ID 選項帶的參數，與回給 app 的憑證欄位。只處理字串，不碰 Android 物件。
 *
 * googleid 程式庫有兩種選項：`GetGoogleIdOption` 與 `GetSignInWithGoogleOption`（SIWG）。兩者的類型都是
 * [TYPE_GOOGLE_ID_TOKEN_CREDENTIAL]，SIWG 另以 subtype 標示，參數放在 `siwg.` 開頭的 key 下。
 */
internal data class GoogleIdRequest(
    val signInWithGoogle: Boolean,
    val serverClientId: String,
    val nonce: String?,
    val hostedDomainFilter: String?,
    val requestVerifiedPhoneNumber: Boolean,
) {
    /** 回給 app 的 `GoogleIdTokenCredential` 欄位，key 與 googleid 程式庫的 `BUNDLE_KEY_*` 相同。 */
    data class Credential(
        val id: String?,
        val idToken: String?,
        val displayName: String?,
        val givenName: String?,
        val familyName: String?,
        val profilePictureUri: String?,
    ) {
        fun fields(): List<Pair<String, String>> = listOfNotNull(
            id?.let { KEY_ID to it },
            idToken?.let { KEY_ID_TOKEN to it },
            displayName?.let { KEY_DISPLAY_NAME to it },
            givenName?.let { KEY_GIVEN_NAME to it },
            familyName?.let { KEY_FAMILY_NAME to it },
        )
    }

    companion object {
        const val TYPE_GOOGLE_ID_TOKEN_CREDENTIAL = "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"
        const val KEY_PROFILE_PICTURE_URI = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_PROFILE_PICTURE_URI"

        private const val PREFIX = "com.google.android.libraries.identity.googleid."
        private const val TYPE_SIWG = PREFIX + "TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL"
        private const val KEY_SUBTYPE = PREFIX + "BUNDLE_KEY_GOOGLE_ID_TOKEN_SUBTYPE"
        private const val KEY_SERVER_CLIENT_ID = PREFIX + "BUNDLE_KEY_SERVER_CLIENT_ID"
        private const val KEY_NONCE = PREFIX + "BUNDLE_KEY_NONCE"
        private const val KEY_REQUEST_VERIFIED_PHONE = PREFIX + "BUNDLE_KEY_REQUEST_VERIFIED_PHONE_NUMBER"
        private const val KEY_SIWG_SERVER_CLIENT_ID = PREFIX + "siwg.BUNDLE_KEY_SERVER_CLIENT_ID"
        private const val KEY_SIWG_NONCE = PREFIX + "siwg.BUNDLE_KEY_NONCE"
        private const val KEY_SIWG_HOSTED_DOMAIN = PREFIX + "siwg.BUNDLE_KEY_HOSTED_DOMAIN_FILTER"
        private const val KEY_ID = PREFIX + "BUNDLE_KEY_ID"
        private const val KEY_ID_TOKEN = PREFIX + "BUNDLE_KEY_ID_TOKEN"
        private const val KEY_DISPLAY_NAME = PREFIX + "BUNDLE_KEY_DISPLAY_NAME"
        private const val KEY_GIVEN_NAME = PREFIX + "BUNDLE_KEY_GIVEN_NAME"
        private const val KEY_FAMILY_NAME = PREFIX + "BUNDLE_KEY_FAMILY_NAME"

        /** 選項類型是否為 Google ID。 */
        fun isGoogleId(type: String?): Boolean = type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL || type == TYPE_SIWG

        /**
         * 從選項的資料讀出參數；[get] 依序查各份資料（retrieval data、candidate query data）。
         * 沒有 server client id 時回傳 null：MicroG RE 沒有它就無法發 ID token。
         */
        fun parse(type: String?, get: (String) -> Any?): GoogleIdRequest? {
            val siwg = type == TYPE_SIWG || get(KEY_SUBTYPE) == TYPE_SIWG
            fun string(siwgKey: String, key: String) =
                ((if (siwg) get(siwgKey) else null) ?: get(key)) as? String
            val serverClientId = string(KEY_SIWG_SERVER_CLIENT_ID, KEY_SERVER_CLIENT_ID)?.takeIf { it.isNotEmpty() } ?: return null
            return GoogleIdRequest(
                signInWithGoogle = siwg,
                serverClientId = serverClientId,
                nonce = string(KEY_SIWG_NONCE, KEY_NONCE),
                hostedDomainFilter = if (siwg) get(KEY_SIWG_HOSTED_DOMAIN) as? String else null,
                requestVerifiedPhoneNumber = get(KEY_REQUEST_VERIFIED_PHONE) == true,
            )
        }
    }
}
