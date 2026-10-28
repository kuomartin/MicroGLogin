package com.xaxaxax.microglogin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsRulesTest {

    private val rules = GmsRules(selfPackage = "com.example.app")

    @Test
    fun `gms and gsf packages go to MicroG RE`() {
        assertEquals(MICROG_RE, rules.redirectPackage("com.google.android.gms"))
        assertEquals(MICROG_RE, rules.redirectPackage("com.google.android.gsf"))
    }

    @Test
    fun `other packages are left alone`() {
        assertNull(rules.redirectPackage(null))
        assertNull(rules.redirectPackage("com.example.app"))
        assertNull(rules.redirectPackage(MICROG_RE))
        assertNull(rules.redirectPackage("com.google.android.gms.policy_sidecar_aps"))
    }

    @Test
    fun `gms and gsf authorities go to MicroG RE`() {
        assertEquals(MICROG_RE, rules.redirectAuthority("com.google.android.gms"))
        assertEquals("$MICROG_RE.phenotype", rules.redirectAuthority("com.google.android.gms.phenotype"))
        assertEquals("$MICROG_RE.gservices", rules.redirectAuthority("com.google.android.gsf.gservices"))
    }

    @Test
    fun `chimera and unrelated authorities are left alone`() {
        assertNull(rules.redirectAuthority(null))
        assertNull(rules.redirectAuthority("com.google.android.gms.chimera"))
        assertNull(rules.redirectAuthority("com.google.android.gms.fonts"))
        assertNull(rules.redirectAuthority("com.example.app.provider"))
        assertNull(rules.redirectAuthority("$MICROG_RE.phenotype"))
    }

    @Test
    fun `gms actions are renamed into the MicroG RE namespace`() {
        assertEquals(
            "$MICROG_RE.auth.GOOGLE_SIGN_IN",
            rules.redirectAction("com.google.android.gms.auth.GOOGLE_SIGN_IN"),
        )
        assertEquals(
            "$MICROG_RE.auth.api.identity.service.signin.START",
            rules.redirectAction("com.google.android.gms.auth.api.identity.service.signin.START"),
        )
    }

    @Test
    fun `push, account picker and already renamed actions are not renamed`() {
        assertNull(rules.redirectAction(null))
        assertNull(rules.redirectAction("com.google.android.c2dm.intent.REGISTER"))
        assertNull(rules.redirectAction("com.google.iid.TOKEN_REQUEST"))
        assertNull(rules.redirectAction("com.google.android.gms.cloudmessaging.service.START"))
        assertNull(rules.redirectAction("com.google.android.gms.common.account.CHOOSE_ACCOUNT"))
        assertNull(rules.redirectAction("$MICROG_RE.auth.GOOGLE_SIGN_IN"))
        assertNull(rules.redirectAction("android.intent.action.VIEW"))
    }

    @Test
    fun `originalAction undoes redirectAction`() {
        val action = "com.google.android.gms.auth.GOOGLE_SIGN_IN"
        assertEquals(action, rules.originalAction(rules.redirectAction(action)))
        assertNull(rules.originalAction(action))
        assertNull(rules.originalAction("$MICROG_RE.common.account.CHOOSE_ACCOUNT"))
        assertNull(rules.originalAction(null))
    }

    @Test
    fun `chimera bound service intents reveal their action`() {
        val inner = "com.google.android.gms.identitycredentials.service.START"
        assertEquals(inner, rules.chimeraAction(CHIMERA, "com.google.android.gms", "chimera-action", inner))
        assertEquals(inner, rules.chimeraAction(CHIMERA, MICROG_RE, "chimera-action", inner))
    }

    @Test
    fun `foreign and plain intents are not chimera intents`() {
        assertNull(rules.chimeraAction(CHIMERA, "com.example.app", "chimera-action", "com.google.android.gms.x.START"))
        assertNull(rules.chimeraAction(CHIMERA, "com.google.android.gms", "content", "com.google.android.gms.x.START"))
        assertNull(rules.chimeraAction("com.google.android.gms.x.START", "com.google.android.gms", null, null))
        assertNull(rules.chimeraAction(CHIMERA, "com.google.android.gms", "chimera-action", ""))
    }

    @Test
    fun `google id credential type round-trips through the replacement name`() {
        val type = "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"
        val microG = "app.revanced.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"
        assertEquals(microG, rules.toTargetCredentialType(type))
        assertEquals(type, rules.fromTargetCredentialType(microG))
        assertNull(rules.toTargetCredentialType(microG))
        assertNull(rules.fromTargetCredentialType(type))
        assertNull(rules.toTargetCredentialType("androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL"))
        assertNull(rules.toTargetCredentialType(null))
    }

    @Test
    fun `push actions are kept with Google Play services`() {
        assertTrue(rules.isKeptPushAction("com.google.android.c2dm.intent.REGISTER"))
        assertTrue(rules.isKeptPushAction("com.google.iid.TOKEN_REQUEST"))
        assertTrue(rules.isKeptPushAction("com.google.android.gms.cloudmessaging.service.START"))
        assertFalse(rules.isKeptPushAction("com.google.android.gms.auth.GOOGLE_SIGN_IN"))
        assertFalse(rules.isKeptPushAction(null))
    }

    @Test
    fun `push goes to the replacement when the loader renamed the C2DM names`() {
        val loader = GmsRules(selfPackage = "com.example.app", pushStaysWithGms = false)
        assertFalse(loader.isKeptPushAction("com.google.android.c2dm.intent.REGISTER"))
        assertFalse(loader.isKeptPushAction("com.google.android.gms.cloudmessaging.service.START"))
        assertEquals(
            "$MICROG_RE.cloudmessaging.service.START",
            loader.redirectAction("com.google.android.gms.cloudmessaging.service.START"),
        )
        assertEquals("app.revanced.android.c2dm.intent.REGISTER", loader.redirectAction("com.google.android.c2dm.intent.REGISTER"))
        assertNull(loader.redirectAction("app.revanced.android.c2dm.intent.REGISTER"))
    }

    @Test
    fun `another vendor gets its own namespace`() {
        val other = GmsRules(selfPackage = "com.example.app", target = "org.example.android.gms")
        assertEquals("org.example.android.gms", other.redirectPackage("com.google.android.gms"))
        assertEquals("org.example.android.gms.phenotype", other.redirectAuthority("com.google.android.gms.phenotype"))
        assertEquals(
            "org.example.android.gms.auth.GOOGLE_SIGN_IN",
            other.redirectAction("com.google.android.gms.auth.GOOGLE_SIGN_IN"),
        )
        assertEquals(
            "org.example.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL",
            other.toTargetCredentialType("com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"),
        )
        assertNull(other.redirectAction("$MICROG_RE.auth.GOOGLE_SIGN_IN"))
    }

    @Test
    fun `account picker action is recognised under both namespaces`() {
        assertTrue(rules.isChooseAccountAction("com.google.android.gms.common.account.CHOOSE_ACCOUNT"))
        assertTrue(rules.isChooseAccountAction("$MICROG_RE.common.account.CHOOSE_ACCOUNT"))
        assertFalse(rules.isChooseAccountAction("com.google.android.gms.auth.GOOGLE_SIGN_IN"))
        assertFalse(rules.isChooseAccountAction(null))
    }

    @Test
    fun `self targeted by package or by component`() {
        assertTrue(rules.isSelfTargeted("com.example.app", null))
        assertTrue(rules.isSelfTargeted(null, "com.example.app"))
        assertFalse(rules.isSelfTargeted(MICROG_RE, MICROG_RE))
        assertFalse(rules.isSelfTargeted(null, null))
    }

    @Test
    fun `a replacement that keeps Google's names only gets the package rewritten`() {
        val dropIn = GmsRules(selfPackage = "com.example.app", target = "org.example.gms")
        assertEquals("org.example.gms", dropIn.redirectPackage("com.google.android.gms"))
        assertNull(dropIn.redirectAuthority("com.google.android.gms.phenotype"))
        assertNull(dropIn.redirectAction("com.google.android.gms.auth.GOOGLE_SIGN_IN"))
        assertNull(dropIn.toTargetCredentialType("com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"))
    }

    @Test
    fun `a renamed C2DM permission names its replacement`() {
        assertEquals(MICROG_RE, GmsRules.c2dmPermissionTarget("app.revanced.android.c2dm.permission.RECEIVE"))
        assertNull(GmsRules.c2dmPermissionTarget("com.google.android.c2dm.permission.RECEIVE"))
        assertNull(GmsRules.c2dmPermissionTarget("android.permission.INTERNET"))
        assertNull(GmsRules.c2dmPermissionTarget(".android.c2dm.permission.RECEIVE"))
    }

    @Test
    fun `actions the replacement only declares under Google's name keep it`() {
        val declared = setOf("$MICROG_RE.auth.GOOGLE_SIGN_IN")
        val partial = GmsRules(selfPackage = "com.example.app", declares = { it in declared })
        assertEquals("$MICROG_RE.auth.GOOGLE_SIGN_IN", partial.redirectAction("com.google.android.gms.auth.GOOGLE_SIGN_IN"))
        assertNull(partial.redirectAction("com.google.android.gms.ads.identifier.service.START"))
    }

    private companion object {
        const val MICROG_RE = GmsRules.MICROG_RE_PACKAGE
        const val CHIMERA = "com.google.android.chimera.BoundService.START"
    }
}
