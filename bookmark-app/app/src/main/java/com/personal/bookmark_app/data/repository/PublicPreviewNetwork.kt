package com.personal.bookmark_app.data.repository

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

internal class BookmarkPrivateNetworkException : IOException(
    "此網址指向內網或非公開位址，不提供自動預覽"
)

/** Only for untrusted bookmark previews, never for the application's backend client. */
internal fun publicPreviewClient(): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(20, TimeUnit.SECONDS)
    .connectTimeout(10, TimeUnit.SECONDS)
    // A proxy could resolve the origin itself and bypass our DNS/address checks.
    .proxy(Proxy.NO_PROXY)
    .dns(PublicPreviewDns())
    .socketFactory(PublicPreviewSocketFactory())
    // Redirects and retries use the same DNS and socket policy as the initial request.
    .followRedirects(true)
    .build()

internal class PublicPreviewDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trimEnd('.').lowercase()
        if (host == "localhost" || host.endsWith(".localhost") ||
            host == "local" || host.endsWith(".local") ||
            host == "internal" || host.endsWith(".internal") ||
            host == "home.arpa" || host.endsWith(".home.arpa") ||
            (!host.contains('.') && !host.contains(':'))
        ) throw BookmarkPrivateNetworkException()
        // Validate the exact addresses returned to OkHttp, not a separate preflight lookup.
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty()) throw UnknownHostException(hostname)
        if (addresses.any { !isPublicPreviewAddress(it) }) throw BookmarkPrivateNetworkException()
        return addresses
    }
}

internal fun requirePublicPreviewDestination(endpoint: SocketAddress?) {
    val address = (endpoint as? InetSocketAddress)?.address
    // Never allow a socket to resolve the hostname again after validation.
    if (address == null || !isPublicPreviewAddress(address)) throw BookmarkPrivateNetworkException()
}

internal class PublicPreviewSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = object : Socket(Proxy.NO_PROXY) {
        override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            // Also covers numeric URL hosts, which may bypass a custom DNS implementation.
            requirePublicPreviewDestination(endpoint)
            super.connect(endpoint, timeout)
        }
    }

    // OkHttp uses only createSocket(). Fail closed for other callers/implicit resolution.
    override fun createSocket(host: String, port: Int): Socket = throw BookmarkPrivateNetworkException()
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        throw BookmarkPrivateNetworkException()
    override fun createSocket(host: InetAddress, port: Int): Socket = throw BookmarkPrivateNetworkException()
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        throw BookmarkPrivateNetworkException()
}

internal fun isPublicPreviewAddress(address: InetAddress): Boolean {
    val bytes = address.address.map { it.toInt() and 0xff }
    if (bytes.size == 4) return isPublicV4(bytes)
    if (bytes.size != 16) return false
    // IPv4-mapped IPv6 may be represented as either 4 or 16 bytes by the platform.
    if (bytes.take(10).all { it == 0 } && bytes[10] == 255 && bytes[11] == 255) {
        return isPublicV4(bytes.takeLast(4))
    }
    // Standard NAT64: validate the embedded IPv4 destination, including private ranges.
    if (bytes.take(12) == listOf(0, 100, 255, 155, 0, 0, 0, 0, 0, 0, 0, 0)) {
        return isPublicV4(bytes.takeLast(4))
    }
    // Conservative global-unicast policy. Excludes loopback, ULA, link-local, multicast,
    // site-local, unspecified and nonstandard translation prefixes outside 2000::/3.
    if (bytes[0] and 0xe0 != 0x20) return false
    return !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] < 2) && // 2001::/23
        !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) &&
        !(bytes[0] == 0x20 && bytes[1] == 0x02) && // 6to4 embeds IPv4
        !(bytes[0] == 0x3f && bytes[1] == 0xff && bytes[2] < 0x10) // documentation
}

private fun isPublicV4(b: List<Int>): Boolean =
    b[0] != 0 && b[0] != 10 && b[0] != 127 && b[0] < 224 &&
        !(b[0] == 100 && b[1] in 64..127) &&
        !(b[0] == 169 && b[1] == 254) &&
        !(b[0] == 172 && b[1] in 16..31) &&
        !(b[0] == 192 && b[1] == 168) &&
        !(b[0] == 192 && b[1] == 0 && b[2] in listOf(0, 2)) &&
        !(b[0] == 192 && b[1] == 88 && b[2] == 99) &&
        !(b[0] == 198 && b[1] in 18..19) &&
        !(b[0] == 198 && b[1] == 51 && b[2] == 100) &&
        !(b[0] == 203 && b[1] == 0 && b[2] == 113)
