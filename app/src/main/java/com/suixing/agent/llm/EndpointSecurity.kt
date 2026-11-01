package com.suixing.agent.llm

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** No DNS lookup: names resembling private IPv4 addresses must never acquire TLS exceptions. */
internal fun isPrivateEndpointHost(host: String): Boolean {
    val h = host.trim().lowercase().removeSurrounding("[", "]")
    if (h == "localhost" || h.endsWith(".local") && h.length > 6) return true
    if (':' in h) {
        val address = runCatching { java.net.InetAddress.getByName(h) }.getOrNull() ?: return false
        return address.isLoopbackAddress || address.isLinkLocalAddress ||
            (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
    }
    val parts = h.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return false
    val bytes = parts.map { it.toIntOrNull() ?: return false }
    if (bytes.any { it !in 0..255 }) return false
    return bytes[0] == 127 || bytes[0] == 10 ||
        bytes[0] == 192 && bytes[1] == 168 || bytes[0] == 169 && bytes[1] == 254 ||
        bytes[0] == 172 && bytes[1] in 16..31
}

/** Checked on every network exchange, including redirects; LAN/keyless endpoints remain usable. */
internal class EndpointPolicyException : IOException("公网接口携带凭据时必须使用 HTTPS，请修改接口地址")

internal class PublicCredentialGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.scheme == "http" && !isPrivateEndpointHost(request.url.host) &&
            request.headers.any { (name, value) ->
                value.isNotBlank() && (name.equals("authorization", true) ||
                    name.equals("cookie", true) || name.contains("token", true) ||
                    name.contains("api-key", true) || name.contains("apikey", true))
            }) {
            throw EndpointPolicyException()
        }
        return chain.proceed(request)
    }
}
