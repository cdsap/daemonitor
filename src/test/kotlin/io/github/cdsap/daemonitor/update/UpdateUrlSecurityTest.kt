package io.github.cdsap.daemonitor.update

import io.github.cdsap.daemonitor.infrastructure.platform.DesktopUrlOpener
import java.io.ByteArrayInputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class UpdateUrlSecurityTest {
    @Test
    fun `desktop rejects non HTTPS URLs before desktop integration`() {
        for (url in listOf("http://github.com", "file:///tmp/app", "javascript:alert(1)", "https:relative", "https://user@github.com")) {
            assertFailsWith<IllegalArgumentException>(url) { DesktopUrlOpener().open(url) }
        }
    }

    @Test
    fun `metadata rejects untrusted URLs without fetching them`() = runTest {
        for (url in listOf(
            "http://github.com/update.json", "https://github.com.evil.test/update.json",
            "https://github.com@evil.test/update.json", "https://evil.test/update.json",
            "https://github.com:444/update.json", "file:///tmp/update.json",
        )) {
            val client = AssetClient(metadataUrl = url)
            val result = source(client).check("1.2.2")
            assertTrue(assertIs<UpdateCheckResult.Failed>(result).reason.contains("GitHub HTTPS"))
            assertEquals(listOf(URI("https://api.github.com/repos/cdsap/daemonitor/releases/latest")), client.requests)
        }
    }

    @Test
    fun `rejects redirect outside GitHub before sending another request`() = runTest {
        for (location in listOf("http://github.com/update.json", "https://evil.test/update.json")) {
            val client = AssetClient(location)
            assertIs<UpdateCheckResult.Failed>(source(client).check("1.2.2"))
            assertEquals(listOf(
                URI("https://api.github.com/repos/cdsap/daemonitor/releases/latest"),
                URI("https://github.com/update.json"),
            ), client.requests)
        }
    }

    @Test
    fun `accepts GitHub asset CDN redirect and parses metadata`() = runTest {
        val client = AssetClient("https://release-assets.githubusercontent.com/update.json")
        val result = assertIs<UpdateCheckResult.Available>(source(client).check("1.2.2"))
        assertEquals("1.2.3", result.candidate.version)
        assertEquals(3, client.requests.size)
    }

    private fun source(client: HttpClient) = GitHubReleaseUpdateSource(
        platform = DesktopPlatform.LINUX,
        architecture = CpuArchitecture.X64,
        client = client,
    )

    private class AssetClient(
        private val redirect: String? = null,
        private val metadataUrl: String = "https://github.com/update.json",
    ) : HttpClient() {
        private val delegate = newHttpClient()
        val requests = mutableListOf<URI>()
        override fun <T : Any?> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            requests += request.uri()
            val location = redirect.takeIf { requests.size == 2 }
            val status = if (location == null) 200 else 302
            val headers = HttpHeaders.of(location?.let { mapOf("Location" to listOf(it)) } ?: emptyMap()) { _, _ -> true }
            val body = if (request.uri().host == "api.github.com") {
                """{"tag_name":"v1.2.3","html_url":"https://github.com/cdsap/daemonitor/releases/tag/v1.2.3","assets":[{"name":"update.json","browser_download_url":"$metadataUrl"}]}"""
            } else {
                """{"schemaVersion":2,"version":"1.2.3","tag":"v1.2.3","assets":[{"platform":"linux","arch":"x64","role":"installer","fileName":"Daemonitor-1.2.3-linux-x64.deb","url":"https://github.com/cdsap/daemonitor/releases/download/v1.2.3/app.deb","sha256":"${"a".repeat(64)}","size":128}]}"""
            }
            val input = ByteArrayInputStream(body.toByteArray())
            @Suppress("UNCHECKED_CAST")
            return object : HttpResponse<T> {
                override fun statusCode() = status
                override fun request() = request
                override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()
                override fun headers() = headers
                override fun body(): T = (if (request.uri().host == "api.github.com") body else input) as T
                override fun sslSession(): Optional<SSLSession> = Optional.empty()
                override fun uri() = request.uri()
                override fun version() = Version.HTTP_1_1
            }
        }
        override fun cookieHandler(): Optional<CookieHandler> = delegate.cookieHandler()
        override fun connectTimeout(): Optional<Duration> = delegate.connectTimeout()
        override fun followRedirects() = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = delegate.proxy()
        override fun sslContext(): SSLContext = delegate.sslContext()
        override fun sslParameters(): SSLParameters = delegate.sslParameters()
        override fun authenticator(): Optional<Authenticator> = delegate.authenticator()
        override fun version() = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = delegate.executor()
        override fun <T : Any?> sendAsync(r: HttpRequest, h: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> = error("unused")
        override fun <T : Any?> sendAsync(r: HttpRequest, h: HttpResponse.BodyHandler<T>, p: HttpResponse.PushPromiseHandler<T>): CompletableFuture<HttpResponse<T>> = error("unused")
    }
}
