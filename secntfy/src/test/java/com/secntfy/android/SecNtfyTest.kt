package com.secntfy.android

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher

class SecNtfyTest {
    @Test
    fun `singleton preserves device identity across callers`() {
        assertSame(SecNtfy.getInstance(), SecNtfy.getInstance())
    }

    @Test
    fun `receipt transmits exact ID and device header and distinguishes duplicate`() = runBlocking {
        var requests = 0
        withClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/Message/Receive/id%2Fwith%3Fquery%23hash%25", request.url.encodedPath)
            assertTrue(request.url.parameters.names().isEmpty())
            assertEquals("device-secret", request.headers["X-NTFYME-DEVICE-KEY"])
            assertNull(request.headers["X-NTFYME-AccessKey"])
            requests++
            respond("""{"Status":${if (requests == 1) 200 else 201},"Token":null,"Message":"received"}""")
        }) { client ->
            val secntfy = service(client)
            assertEquals(DeliveryReceipt.DELIVERED, secntfy.messageReceived("id/with?query#hash%"))
            assertEquals(DeliveryReceipt.ALREADY_DELIVERED, secntfy.messageReceived("id/with?query#hash%"))
            assertEquals(2, requests)
        }
    }

    @Test
    fun `HTTP errors never count as delivered even with successful JSON`() = runBlocking {
        for (status in listOf(302, 400, 401, 403, 404, 429, 500, 503)) {
            withClient(MockEngine { respond("""{"Status":200}""", HttpStatusCode.fromValue(status)) }) { client ->
                val error = expectFailure<NtfyException.HttpError> { service(client).messageReceived("id") }
                assertEquals(status, error.statusCode)
            }
        }
    }

    @Test
    fun `API errors inside HTTP 200 never count as delivered`() = runBlocking {
        for (status in listOf(0, 202, 204, 400, 401, 404, 500)) {
            withClient(MockEngine { respond("""{"Status":$status}""") }) { client ->
                val error = expectFailure<NtfyException.ApiError> { service(client).messageReceived("id") }
                assertEquals(status, error.statusCode)
            }
        }
    }

    @Test
    fun `invalid or empty response cannot confirm delivery`() = runBlocking {
        for (body in listOf("", "not JSON", "{}", "null", "[]", "{\"Status\":null}")) {
            withClient(MockEngine { respond(body) }) { client ->
                expectFailure<NtfyException.InvalidResponse> { service(client).messageReceived("id") }
            }
        }
    }

    @Test
    fun `invalid inputs fail before network access`() = runBlocking {
        withClient(MockEngine { error("Must not make a request") }) { client ->
            for (id in listOf("", "   ", ".", "..")) {
                expectFailure<NtfyException.InvalidMessageId> { service(client).messageReceived(id) }
            }
            expectFailure<NtfyException.NoDevice> {
                service(client, state().copy(deviceToken = "")).messageReceived("id")
            }
            expectFailure<NtfyException.NotInitialized> { service(client, SecNtfyState()).messageReceived("id") }
            for (url in listOf("example.com", "/relative", "ftp://example.com", "https://user:pw@example.com", "https://example.com?key=x", "https://example.com#part")) {
                expectFailure<NtfyException.UnsupportedURL> {
                    service(client, state().copy(apiUrl = url)).messageReceived("id")
                }
            }
        }
    }

    @Test
    fun `transport failures and timeouts remain failures`() = runBlocking {
        withClient(MockEngine { throw IOException("offline") }) { client ->
            expectFailure<NtfyException.ConnectionError> { service(client).messageReceived("id") }
        }
        withClient(MockEngine { throw SocketTimeoutException("timed out") }) { client ->
            expectFailure<NtfyException.Timeout> { service(client).messageReceived("id") }
        }
    }

    @Test
    fun `request timeout prevents hanging receipt`() = runBlocking {
        withClient(MockEngine { awaitCancellation() }, timeoutMillis = 100) { client ->
            expectFailure<NtfyException.Timeout> { service(client).messageReceived("id") }
        }
    }

    @Test
    fun `cancellation is propagated instead of converted to success or API failure`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        withClient(MockEngine { entered.complete(Unit); awaitCancellation() }) { client ->
            var completedNormally = false
            var cancellationPropagated = false
            val job = launch {
                try {
                    service(client).messageReceived("id")
                    completedNormally = true
                } catch (error: CancellationException) {
                    cancellationPropagated = true
                    throw error
                }
            }
            entered.await()
            job.cancelAndJoin()
            assertFalse(completedNormally)
            assertTrue(cancellationPropagated)
        }
    }

    @Test
    fun `registration persists new token in state used for receipts`() = runBlocking {
        var requests = 0
        withClient(MockEngine { request ->
            requests++
            if (requests == 1) {
                assertEquals("/api/App/RegisterDevice", request.url.encodedPath)
                assertEquals("app-secret", request.headers["X-NTFYME-AccessKey"])
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("fcm-token", body.getValue("D_Android_ID").jsonPrimitive.content)
                respond("""{"Status":200,"Token":"new-device-token"}""")
            } else {
                assertEquals("new-device-token", request.headers["X-NTFYME-DEVICE-KEY"])
                respond("""{"Status":200}""")
            }
        }) { client ->
            val secntfy = service(client)
            assertEquals("new-device-token", secntfy.registerDevice())
            assertEquals(DeliveryReceipt.DELIVERED, secntfy.messageReceived("id"))
        }
    }

    @Test
    fun `registration error calls callback once without null crash`() = runBlocking {
        for (body in listOf("""{"Status":500,"Token":""}""", """{"Status":200,"Token":null}""", """{"Status":200,"Token":" "}""")) {
            withClient(MockEngine { respond(body) }) { client ->
                var calls = 0
                service(client).getNtfyToken { token, error ->
                    calls++
                    assertNull(token)
                    assertNotNull(error)
                }
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun `missing configured device calls callback once without making request`() = runBlocking {
        withClient(MockEngine { error("Must not make a request") }) { client ->
            var calls = 0
            service(client, state().copy(device = null)).getNtfyToken { token, error ->
                calls++
                assertNull(token)
                assertTrue(error is NtfyException.NoDevice)
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `callback exception is not caught and callback is not invoked twice`() = runBlocking {
        withClient(MockEngine { respond("""{"Status":200,"Token":"new-token"}""") }) { client ->
            var calls = 0
            expectFailure<NtfyException.InvalidRequest> {
                service(client).getNtfyToken { _, _ ->
                    calls++
                    throw NtfyException.InvalidRequest()
                }
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `FCM update during registration prevents stale response overwriting state`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        withClient(MockEngine {
            entered.complete(Unit)
            resume.await()
            respond("""{"Status":200,"Token":"stale-token"}""")
        }) { client ->
            val secntfy = service(client)
            val request = async {
                expectFailure<NtfyException.ConfigurationChanged> { secntfy.registerDevice() }
            }
            entered.await()
            secntfy.setFCMToken("refreshed-token")
            resume.complete(Unit)
            request.await()
        }
    }

    @Test
    fun `decryption uses the existing wire format and fails explicitly`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.public)
        val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal("Test ä notification".toByteArray(Charsets.UTF_8)))
        val secntfy = SecNtfy(SecNtfyApi(), state().copy(privateKey = Base64.getEncoder().encodeToString(keyPair.private.encoded)))
        assertEquals("Test ä notification", secntfy.decryptMessage(encrypted))
        try {
            secntfy.decryptMessage("invalid ciphertext")
            fail("Expected decryption failure")
        } catch (expected: NtfyException.DecryptionFailed) {
            assertEquals("Message could not be decrypted", expected.message)
        }
    }

    private fun state() = SecNtfyState(
        apiUrl = "https://example.com/api/",
        apiKey = "app-secret",
        deviceToken = "device-secret",
        device = NTFY_Devices(0, 0, 0, "SDK: 35", "test", "", "fcm-token", "public-key", "device-secret"),
    )

    private fun service(client: HttpClient, state: SecNtfyState = state()) = SecNtfy(SecNtfyApi(client), state)

    private suspend fun withClient(engine: MockEngine, timeoutMillis: Long = 15_000, block: suspend (HttpClient) -> Unit) {
        val client = HttpClient(engine) {
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = timeoutMillis }
        }
        try {
            block(client)
        } finally {
            client.close()
            engine.close()
        }
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.simpleName}, got $error", error)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
