package com.personal.bookmark_app.data.repository

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class PublicPreviewNetworkTest {
    @Test
    fun rejectsNonPublicIpv4AndIpv6IncludingMappedAndTranslatedAddresses() {
        val blocked = listOf(
            "0.0.0.0", "10.1.2.3", "100.64.0.1", "100.127.255.255", "127.0.0.1",
            "169.254.1.2", "172.16.0.1", "172.31.255.255", "192.168.1.1", "192.0.0.8",
            "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.19.255.255", "198.51.100.1",
            "203.0.113.1", "224.0.0.1", "255.255.255.255", "::", "::1", "fc00::1",
            "fd12::1", "fe80::1", "fec0::1", "ff02::1", "::ffff:192.168.1.1",
            "::127.0.0.1", "64:ff9b::a00:1", "64:ff9b:1::1", "2001::1",
            "2001:db8::1", "2002:7f00:1::1", "3fff::1"
        )
        blocked.forEach { assertFalse(it, isPublicPreviewAddress(InetAddress.getByName(it))) }
    }

    @Test
    fun allowsPublicAddressesAndRangeBoundariesWithoutContactingThem() {
        listOf("8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.0", "172.15.255.255",
            "172.32.0.0", "2001:4860:4860::8888", "2606:4700:4700::1111",
            "::ffff:8.8.8.8", "64:ff9b::808:808").forEach {
            assertTrue(it, isPublicPreviewAddress(InetAddress.getByName(it)))
        }
    }

    @Test
    fun rejectsLocalNamesBeforeDnsLookup() {
        var lookups = 0
        val dns = PublicPreviewDns(fakeDns { lookups++; listOf(publicAddress) })
        listOf("localhost", "LOCALHOST.", "foo.localhost", "nas", "nas.local",
            "foo.internal", "router.home.arpa").forEach {
            assertThrows(BookmarkPrivateNetworkException::class.java) { dns.lookup(it) }
        }
        assertEquals(0, lookups)
    }

    @Test
    fun rejectsMixedDnsAnswersAndRebindingOnLaterLookup() {
        val privateAddress = InetAddress.getByName("10.0.0.1")
        val mixed = PublicPreviewDns(fakeDns { listOf(publicAddress, privateAddress) })
        assertThrows(BookmarkPrivateNetworkException::class.java) { mixed.lookup("mixed.test") }
        var lookups = 0
        val changing = PublicPreviewDns(fakeDns {
            if (lookups++ == 0) listOf(publicAddress) else listOf(privateAddress)
        })
        assertEquals(listOf(publicAddress), changing.lookup("changing.test"))
        assertThrows(BookmarkPrivateNetworkException::class.java) { changing.lookup("changing.test") }
    }

    @Test
    fun socketRejectsUnresolvedDestinationsAndPrivateLiteralBeforeConnecting() {
        PublicPreviewSocketFactory().createSocket().use { socket ->
            assertThrows(BookmarkPrivateNetworkException::class.java) {
                socket.connect(InetSocketAddress.createUnresolved("public.test", 80), 100)
            }
        }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 150
            PublicPreviewSocketFactory().createSocket().use { socket ->
                assertThrows(BookmarkPrivateNetworkException::class.java) {
                    socket.connect(InetSocketAddress("127.0.0.1", listener.localPort), 100)
                }
            }
            assertThrows(SocketTimeoutException::class.java) { listener.accept() }
        }
    }

    @Test
    fun productionParserRejectsInitialPrivateUrls() {
        listOf("http://localhost/", "http://127.0.0.1/", "http://10.0.0.1/",
            "http://[::1]/", "http://[fd00::1]/", "http://[::ffff:127.0.0.1]/").forEach { url ->
            assertThrows(BookmarkPrivateNetworkException::class.java) {
                runBlocking { BookmarkMetadataParser().parse(url) }
            }
        }
    }

    @Test
    fun redirectsToPrivateDnsAndIpv4AndIpv6LiteralsNeverReachSecondServer() {
        listOf("http://private.test/", "http://127.0.0.1/", "http://[::1]/").forEach { target ->
            withIsolatedServer(target) { client, connections ->
                assertThrows(BookmarkPrivateNetworkException::class.java) {
                    runBlocking { BookmarkMetadataParser(client).parse("http://public.test/start") }
                }
                assertEquals(listOf(publicAddress), connections)
            }
        }
    }

    @Test
    fun publicRedirectStillReturnsMetadata() {
        withIsolatedServer("http://other-public.test/final") { client, connections ->
            val result = runBlocking { BookmarkMetadataParser(client).parse("http://public.test/start") }
            assertEquals("http://other-public.test/final", result.url)
            assertEquals("Public article", result.title)
            assertEquals(listOf(publicAddress, publicAddress), connections)
        }
    }

    @Test
    fun productionClientIgnoresSystemProxyAndKeepsResourceLimits() {
        val client = publicPreviewClient()
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertTrue(client.dns is PublicPreviewDns)
        assertTrue(client.socketFactory is PublicPreviewSocketFactory)
        assertEquals(20_000, client.callTimeoutMillis)
        assertEquals(10_000, client.connectTimeoutMillis)
    }

    /** Only this test transport maps validated public destinations onto an isolated loopback server. */
    private fun withIsolatedServer(
        redirect: String,
        test: (okhttp3.OkHttpClient, List<InetAddress>) -> Unit
    ) {
        val listener = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val connections = CopyOnWriteArrayList<InetAddress>()
        val server = thread(isDaemon = true) {
            try {
                var requests = 0
                while (!listener.isClosed) {
                    listener.accept().use { socket ->
                        socket.soTimeout = 2000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { /* Drain request headers. */ }
                        val body = "<title>Public article</title>"
                        val response = if (requests++ == 0) {
                            "HTTP/1.1 302 Found\r\nLocation: $redirect\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        } else {
                            "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        }
                        socket.getOutputStream().write(response.toByteArray())
                    }
                }
            } catch (_: java.net.SocketException) {
                // Closing the test listener ends accept().
            }
        }
        val socketFactory = object : javax.net.SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress?, timeout: Int) {
                    requirePublicPreviewDestination(endpoint)
                    connections.add((endpoint as InetSocketAddress).address)
                    super.connect(InetSocketAddress("127.0.0.1", listener.localPort), timeout)
                }
            }
            override fun createSocket(h: String, p: Int): Socket = error("Unexpected overload")
            override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("Unexpected overload")
            override fun createSocket(h: InetAddress, p: Int): Socket = error("Unexpected overload")
            override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("Unexpected overload")
        }
        val client = publicPreviewClient().newBuilder()
            .dns(PublicPreviewDns(fakeDns { host ->
                listOf(if (host == "private.test") InetAddress.getByName("10.0.0.1") else publicAddress)
            }))
            .socketFactory(socketFactory).build()
        try {
            test(client, connections)
        } finally {
            listener.close()
            server.join(3000)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private val publicAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

    private fun fakeDns(lookup: (String) -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = lookup.invoke(hostname)
    }
}
