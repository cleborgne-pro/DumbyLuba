package fr.luba.poc.auth

import org.junit.Assert.*
import org.junit.Test

class AuthorizedRequestTest {
    @Test fun expiredAccessRefreshesAndRetriesOnce() {
        var calls=0;var refreshes=0
        val result=AuthorizedRequest.run(request={if(++calls==1) throw CloudResponseException(401);"ok"},refresh={refreshes++})
        assertEquals("ok",result);assertEquals(2,calls);assertEquals(1,refreshes)
    }
    @Test fun repeatedUnauthorizedDoesNotLoop() {
        var calls=0;var refreshes=0
        assertThrows(CloudResponseException::class.java) {
            AuthorizedRequest.run(request={calls++;throw CloudResponseException(401)},refresh={refreshes++})
        }
        assertEquals(2,calls);assertEquals(1,refreshes)
    }
    @Test fun serverFailureAndTokenEndpointDoNotRefresh() {
        var refreshes=0
        for((code,allowed) in listOf(503 to true,401 to false)) {
            assertThrows(CloudResponseException::class.java) {
                AuthorizedRequest.run(request={throw CloudResponseException(code)},refresh={refreshes++},canRefresh=allowed)
            }
        }
        assertEquals(0,refreshes)
    }
    @Test fun invalidTokenCode460RefreshesLikePyMammotion() {
        var calls=0;var refreshes=0
        val result=AuthorizedRequest.run(request={if(++calls==1) throw CloudResponseException(460);"ok"},refresh={refreshes++})
        assertEquals("ok",result);assertEquals(1,refreshes)
    }
}
