package fr.luba.poc.auth

/**
 * Refusal of a Mammotion endpoint: [code] is the HTTP status when [http], otherwise the `code` of the API reply (HTTP 200).
 * [endpoint]: path of the request, never its query (credentials of the sign-in) nor its host; [host]: domain name only,
 * for the log. Both are empty when unknown (tests).
 */
internal class CloudResponseException(val code:Int, val endpoint:String="", val host:String="", val http:Boolean=true):
    IllegalStateException("Mammotion HTTP/API $code"+(if(endpoint.isEmpty()) "" else " ($endpoint)")) {
    /** Fields of the log: domain, path, HTTP status and API code (null when not received). */
    val fields:Map<String,Any?> get() = mapOf("host" to host,"endpoint" to endpoint,"httpCode" to (if(http) code else 200),"apiCode" to (if(http) null else code))
}

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
