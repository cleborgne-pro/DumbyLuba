package fr.luba.poc.auth

internal class CloudResponseException(val code:Int): IllegalStateException("Mammotion HTTP/API $code")

/** A single retry after renewal (401, 460 "invalid token" like PyMammotion, 40101/40102); network failures do not invalidate the session. */
internal object AuthorizedRequest {
    fun <T> run(request:()->T, refresh:()->Unit, canRefresh:Boolean=true):T {
        return try { request() } catch(e:CloudResponseException) {
            if(!canRefresh || e.code !in listOf(401,460,40101,40102)) throw e
            refresh()
            request()
        }
    }
}
