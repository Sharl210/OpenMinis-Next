package com.openminis.app.web

import java.net.InetAddress
import java.net.URI

/** Shared trust-boundary checks for agent search/fetch requests. */
object WebAccessPolicy {
    const val MAX_URL_LENGTH = 8_192

    fun validateUrl(raw: String): Result<URI> = runCatching {
        require(raw.length in 1..MAX_URL_LENGTH) { "URL is empty or too long" }
        val uri = URI(raw.trim())
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Only HTTP(S) URLs are allowed"
        }
        require(!uri.userInfo.orEmpty().contains("@")) { "URL credentials are not allowed" }
        val host = uri.host?.trim()?.lowercase() ?: error("URL host is missing")
        require(host.isNotBlank()) { "URL host is missing" }
        require(!host.equals("localhost") && !host.endsWith(".localhost")) {
            "Local hosts are not allowed"
        }
        uri
    }

    /** Re-check every resolved address to reduce DNS rebinding/SSRF risk. */
    fun validateResolvedAddresses(host: String, addresses: List<InetAddress>): Result<Unit> = runCatching {
        require(addresses.isNotEmpty()) { "Host did not resolve" }
        addresses.forEach { address ->
            require(!address.isAnyLocalAddress) { "Unspecified address is not allowed" }
            require(!address.isLoopbackAddress) { "Loopback address is not allowed" }
            require(!address.isLinkLocalAddress) { "Link-local address is not allowed" }
            require(!address.isSiteLocalAddress) { "Private address is not allowed" }
            require(!address.isMulticastAddress) { "Multicast address is not allowed" }
        }
    }
}
