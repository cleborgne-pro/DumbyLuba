package fr.luba.poc.auth

import org.junit.Assert.*
import org.junit.Test

class AccountEncodingTest {
    @Test fun preservesPasswordExactlyAndNormalizesEmail() {
        val saved=AccountEncoding.decode(AccountEncoding.encode(SavedAccount(" robot@example.test "," mot\"\\é\n ")))!!
        assertEquals("robot@example.test",saved.email)
        assertEquals(" mot\"\\é\n ",saved.password)
    }
    @Test fun malformedOrIncompleteDataCannotCreateAccount() {
        for(value in listOf("invalid","{}","{\"email\":\"x\"}","{\"email\":\" \",\"password\":\"secret\"}"))
            assertNull(AccountEncoding.decode(value))
    }
    @Test fun logsDoNotRevealCredentials() {
        val saved=SavedAccount("private@example.test","very-secret")
        assertFalse(saved.toString().contains(saved.email))
        assertFalse(saved.toString().contains(saved.password))
    }
}
