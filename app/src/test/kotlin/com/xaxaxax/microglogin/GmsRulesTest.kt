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
    fun `push actions are recognised`() {
        assertTrue(rules.isPushAction("com.google.android.c2dm.intent.REGISTER"))
        assertTrue(rules.isPushAction("com.google.iid.TOKEN_REQUEST"))
        assertFalse(rules.isPushAction("com.google.android.gms.auth.GOOGLE_SIGN_IN"))
        assertFalse(rules.isPushAction(null))
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

    private companion object {
        const val MICROG_RE = GmsRules.MICROG_RE_PACKAGE
    }
}
