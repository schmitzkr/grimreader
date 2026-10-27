package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.api.ServerSecurity
import com.schmitzkr.grimreader.core.api.classifyServerUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerAddressTest {
    private fun c(url: String) = classifyServerUrl(url)

    @Test
    fun `https is always secure`() {
        assertEquals(ServerSecurity.Secure, c("https://books.example.com"))
        assertEquals(ServerSecurity.Secure, c("https://192.168.1.5:8443"))
    }

    @Test
    fun `http to a local-network host needs confirmation`() {
        listOf(
            "http://localhost:6060", "http://10.0.0.2", "http://172.16.0.1", "http://172.31.255.1",
            "http://192.168.1.10:6060", "http://169.254.1.1", "http://books.local", "http://nas.lan:80",
            "http://books.home", "http://books.internal", "http://books.home.arpa", "http://BOOKS.LAN/path",
        ).forEach { assertEquals(it, ServerSecurity.InsecureLocal, c(it)) }
    }

    @Test
    fun `http over Tailscale needs confirmation`() {
        listOf(
            "http://100.64.0.1:6060", "http://100.100.5.5", "http://100.127.255.254",
            "http://host.tailnet-name.ts.net:6060", "http://HOST.Tailnet.TS.NET",
            "http://[fd7a:115c:a1e0::1]:6060", "http://[fd7a:115c:a1e0:ab12:4843:cd96:6265:1234]",
            "http://[FD7A:115C:A1E0:1::5]/path",
        ).forEach { assertEquals(it, ServerSecurity.InsecureLocal, c(it)) }
    }

    @Test
    fun `look-alikes of Tailscale addresses are refused`() {
        listOf(
            "http://100.128.0.1", "http://100.63.255.1", "http://evil-ts.net.example.com", "http://ts.net",
            "http://evil.ts.net.example.com", "http://100.64.0.1.evil.com", "http://100.64.0.1@evil.com",
            "http://[fd7a:115c:a1e1::1]", "http://[fd7b:115c:a1e0::1]", "http://[2001:db8::1]",
            "http://[fd7a:115c:a1e0::1", "http://[fd7a:115c:a1e0::1]x", "http://[fd7a:115c:a1e0:::1]",
            "http://[fd7a:115c:a1e0::1::2]", "http://[fd7a:115c:a1e0:1:2:3:4:5:6]", "http://[]", "http://tailhost:6060",
        ).forEach { assertEquals(it, ServerSecurity.Refused, c(it)) }
    }

    @Test
    fun `http to a public host is refused`() {
        listOf(
            "http://books.example.com", "http://8.8.8.8", "http://172.32.0.1", "http://172.15.0.1",
            "http://192.169.1.1", "http://11.0.0.1", "http://evil-local.com", "http://locallan.example.com",
            "http://192.168.1.5@evil.com", "http://192.168.1.5.evil.com", "http://10.0.0.256",
        ).forEach { assertEquals(it, ServerSecurity.Refused, c(it)) }
    }

    @Test
    fun `other schemes are refused`() {
        assertEquals(ServerSecurity.Refused, c("ftp://192.168.1.5"))
        assertEquals(ServerSecurity.Refused, c("192.168.1.5"))
    }
}
