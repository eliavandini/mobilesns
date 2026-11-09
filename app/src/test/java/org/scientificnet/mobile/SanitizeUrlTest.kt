package org.scientificnet.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.scientificnet.mobile.auth.sanitizeUrl

class SanitizeUrlTest {
    @Test fun stripsQueryAndFragment() {
        assertEquals(
            "https://login.microsoftonline.com/scientificnet.onmicrosoft.com/oauth2/v2.0/authorize",
            sanitizeUrl("https://login.microsoftonline.com/scientificnet.onmicrosoft.com/oauth2/v2.0/authorize?client_id=x&code_challenge=secret#frag"),
        )
        val redirect = sanitizeUrl("https://my.scientificnet.org/exam-enrolment/en/#code=1.SECRETCODE&state=abc")
        assertEquals("https://my.scientificnet.org/exam-enrolment/en/", redirect)
        assertFalse(redirect.contains("SECRET"))
    }

    @Test fun handlesOddInput() {
        assertEquals("-", sanitizeUrl(null))
        assertEquals("-", sanitizeUrl(""))
        assertFalse(sanitizeUrl("not a url ?code=SECRET").contains("SECRET"))
    }
}
