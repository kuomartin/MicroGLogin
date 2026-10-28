package com.xaxaxax.microglogin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleIdRequestTest {

    private fun bundle(vararg pairs: Pair<String, Any?>): (String) -> Any? = pairs.toMap()::get

    @Test
    fun `sign in with google options use the siwg keys`() {
        val request = GoogleIdRequest.parse(
            TYPE,
            bundle(
                "$P.BUNDLE_KEY_GOOGLE_ID_TOKEN_SUBTYPE" to "$P.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL",
                "$P.siwg.BUNDLE_KEY_SERVER_CLIENT_ID" to "client.apps.googleusercontent.com",
                "$P.siwg.BUNDLE_KEY_NONCE" to "n",
                "$P.siwg.BUNDLE_KEY_HOSTED_DOMAIN_FILTER" to "example.com",
            ),
        )!!
        assertTrue(request.signInWithGoogle)
        assertEquals("client.apps.googleusercontent.com", request.serverClientId)
        assertEquals("n", request.nonce)
        assertEquals("example.com", request.hostedDomainFilter)
    }

    @Test
    fun `google id options use the plain keys`() {
        val request = GoogleIdRequest.parse(
            TYPE,
            bundle(
                "$P.BUNDLE_KEY_SERVER_CLIENT_ID" to "client",
                "$P.BUNDLE_KEY_NONCE" to "n",
                "$P.BUNDLE_KEY_REQUEST_VERIFIED_PHONE_NUMBER" to true,
            ),
        )!!
        assertFalse(request.signInWithGoogle)
        assertEquals("client", request.serverClientId)
        assertEquals("n", request.nonce)
        assertNull(request.hostedDomainFilter)
        assertTrue(request.requestVerifiedPhoneNumber)
    }

    @Test
    fun `an option without a server client id is not usable`() {
        assertNull(GoogleIdRequest.parse(TYPE, bundle()))
        assertNull(GoogleIdRequest.parse(TYPE, bundle("$P.BUNDLE_KEY_SERVER_CLIENT_ID" to "")))
    }

    @Test
    fun `only google id types are recognised`() {
        assertTrue(GoogleIdRequest.isGoogleId(TYPE))
        assertTrue(GoogleIdRequest.isGoogleId("$P.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL"))
        assertFalse(GoogleIdRequest.isGoogleId("android.credentials.TYPE_PASSWORD_CREDENTIAL"))
        assertFalse(GoogleIdRequest.isGoogleId(null))
    }

    @Test
    fun `credential fields use the googleid bundle keys and skip nulls`() {
        val fields = GoogleIdRequest.Credential("a@b.c", "token", "Name", null, null, null).fields().toMap()
        assertEquals("a@b.c", fields["$P.BUNDLE_KEY_ID"])
        assertEquals("token", fields["$P.BUNDLE_KEY_ID_TOKEN"])
        assertEquals("Name", fields["$P.BUNDLE_KEY_DISPLAY_NAME"])
        assertEquals(3, fields.size)
    }

    private companion object {
        const val P = "com.google.android.libraries.identity.googleid"
        const val TYPE = "$P.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"
    }
}
