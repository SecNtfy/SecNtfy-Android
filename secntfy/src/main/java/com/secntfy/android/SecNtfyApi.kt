package com.secntfy.android

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI

/** Transport is injectable so protocol handling can be tested without Android or a live server. */
internal class SecNtfyApi(client: HttpClient? = null) {
    private val httpClient by lazy {
        client ?: HttpClient(Android) {
            // Never follow a redirect while carrying device or application credentials.
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun registerDevice(apiUrl: String, appKey: String, device: NTFY_Devices): String {
        if (appKey.isBlank()) throw NtfyException.InvalidCredentials()
        val response = post(apiUrl, listOf("App", "RegisterDevice"),
            "X-NTFYME-AccessKey", appKey, json.encodeToString(device))
        if (response.Status !in 200..299) throw NtfyException.ApiError(response.Status)
        return response.Token?.takeIf { it.isNotBlank() } ?: throw NtfyException.InvalidResponse()
    }

    suspend fun messageReceived(apiUrl: String, deviceToken: String, messageId: String): DeliveryReceipt {
        if (messageId.isBlank() || messageId == "." || messageId == "..") {
            throw NtfyException.InvalidMessageId()
        }
        if (deviceToken.isBlank()) throw NtfyException.NoDevice()
        val response = post(apiUrl, listOf("Message", "Receive", messageId),
            "X-NTFYME-DEVICE-KEY", deviceToken)
        return when (response.Status) {
            200 -> DeliveryReceipt.DELIVERED
            201 -> DeliveryReceipt.ALREADY_DELIVERED
            else -> throw NtfyException.ApiError(response.Status)
        }
    }

    suspend fun updateDevice(apiUrl: String, deviceToken: String, device: NTFY_Devices) {
        if (deviceToken.isBlank()) throw NtfyException.NoDevice()
        val response = post(apiUrl, listOf("Device", "Update"),
            "X-NTFYME-DEVICE-KEY", deviceToken, json.encodeToString(device))
        if (response.Status !in 200..299 && response.Message != "Device wurde aktualisiert!") {
            throw NtfyException.ApiError(response.Status)
        }
    }

    private suspend fun post(
        apiUrl: String,
        path: List<String>,
        credentialHeader: String,
        credential: String,
        body: String? = null,
    ): ApiResponse {
        val url = endpointUrl(apiUrl, path)
        try {
            val response = httpClient.post(url) {
                accept(ContentType.Application.Json)
                header(credentialHeader, credential)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            if (response.status.value !in 200..299) {
                throw NtfyException.HttpError(response.status.value)
            }
            // HTTP 200 alone is insufficient: the API also reports failures in JSON Status.
            return json.decodeFromString<ApiResponse>(response.bodyAsText())
        } catch (error: CancellationException) {
            throw error
        } catch (error: NtfyException) {
            throw error
        } catch (error: HttpRequestTimeoutException) {
            throw NtfyException.Timeout()
        } catch (error: SocketTimeoutException) {
            throw NtfyException.Timeout()
        } catch (error: io.ktor.client.network.sockets.ConnectTimeoutException) {
            throw NtfyException.Timeout()
        } catch (error: io.ktor.client.network.sockets.SocketTimeoutException) {
            throw NtfyException.Timeout()
        } catch (error: SerializationException) {
            throw NtfyException.InvalidResponse()
        } catch (error: IOException) {
            throw NtfyException.ConnectionError()
        } catch (error: IllegalArgumentException) {
            throw NtfyException.InvalidRequest()
        }
    }

    companion object {
        internal fun endpointUrl(apiUrl: String, path: List<String> = emptyList()): String {
            try {
                val uri = URI(apiUrl)
                if (!uri.isAbsolute || uri.host.isNullOrBlank()) throw NtfyException.UnsupportedURL()
                val builder = URLBuilder(apiUrl)
                if (apiUrl.isBlank() || builder.host.isBlank() ||
                    builder.protocol !in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) ||
                    builder.user != null || builder.password != null ||
                    builder.parameters.names().isNotEmpty() || builder.fragment.isNotEmpty()
                ) throw NtfyException.UnsupportedURL()
                return builder.appendPathSegments(path, encodeSlash = true).buildString()
            } catch (error: IllegalArgumentException) {
                throw NtfyException.UnsupportedURL()
            } catch (error: java.net.URISyntaxException) {
                throw NtfyException.UnsupportedURL()
            }
        }
    }
}

@Serializable
private data class ApiResponse(val Status: Int, val Token: String? = null, val Message: String? = null)
