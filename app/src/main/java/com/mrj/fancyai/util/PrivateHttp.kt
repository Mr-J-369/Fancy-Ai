package com.mrj.fancyai.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/** Shared routing for HTTPS providers and explicitly addressed private HTTP servers. */
internal object PrivateHttp {
    fun requiredBaseUrl(baseUrl: String): String {
        val value = baseUrl.trim().trimEnd('/')
        val parsed = value.toHttpUrlOrNull()
        require(
            (parsed != null) &&
                ((parsed.scheme == "https") ||
                    ((parsed.scheme == "http") && (privateAddress(parsed.host) != null))),
        ) {
            "Use HTTPS, or HTTP with localhost or a private-network IP address"
        }
        return parsed.toString().trimEnd('/')
    }

    fun routePrivateHttp(url: String): RoutedEndpoint {
        val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid provider URL")
        if (parsed.scheme != "http") return RoutedEndpoint(parsed.toString(), null)
        val address = checkNotNull(privateAddress(parsed.host)) {
            "Public custom endpoints must use HTTPS"
        }
        val routeHost = address.address.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        } + ".$LOCAL_ROUTE_DOMAIN"
        val originalHost = parsed.host.let { if (':' in it) "[$it]" else it }
        val hostHeader = originalHost + if (parsed.port == 80) "" else ":${parsed.port}"
        return RoutedEndpoint(
            url = parsed.newBuilder().host(routeHost).build().toString(),
            hostHeader = hostHeader,
        )
    }

    private fun privateAddress(host: String): InetAddress? {
        val candidate = when {
            host.equals("localhost", ignoreCase = true) -> InetAddress.getLoopbackAddress()
            IPV4_LITERAL.matches(host) || (':' in host) ->
                runCatching { InetAddress.getByName(host) }.getOrNull()
            else -> null
        } ?: return null
        val bytes = candidate.address
        val carrierGradeNat = (bytes.size == 4) &&
            ((bytes[0].toInt() and 0xff) == 100) &&
            ((bytes[1].toInt() and 0xc0) == 64)
        val uniqueLocalV6 = (bytes.size == 16) && ((bytes[0].toInt() and 0xfe) == 0xfc)
        return candidate.takeIf {
            it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress ||
                carrierGradeNat || uniqueLocalV6
        }
    }

    fun decodeLocalRoute(host: String): InetAddress? {
        val hex = host.removeSuffix(".$LOCAL_ROUTE_DOMAIN")
            .takeIf { (it != host) && ((it.length == 8) || (it.length == 32)) }
            ?: return null
        val bytes = runCatching {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.getOrNull() ?: return null
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }

    data class RoutedEndpoint(val url: String, val hostHeader: String?)

    private const val LOCAL_ROUTE_DOMAIN = "local.fancyai.invalid"
    private val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
}
