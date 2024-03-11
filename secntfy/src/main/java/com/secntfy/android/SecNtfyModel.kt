package com.secntfy.android

import kotlinx.serialization.Serializable

@Serializable
data class NTFY_Devices (

    var D_ID: Int,
    /// <summary>
    /// APP ID
    /// </summary>
    var D_APP_ID: Int,
    /// <summary>
    /// Device OS
    /// </summary>
    var D_OS: Int,
    /// <summary>
    /// Device OS Version
    /// </summary>
    var D_OS_Version: String,
    /// <summary>
    /// Device Model
    /// </summary>
    var D_Model: String,
    /// <summary>
    /// Device APN ID
    /// </summary>
    var D_APN_ID: String,
    /// <summary>
    /// Device Android ID
    /// </summary>
    var D_Android_ID: String,
    /// <summary>
    /// Public Key from Device
    /// </summary>
    var D_PublicKey: String,
    /// <summary>
    /// NTFY-DeviceToken
    /// </summary>
    var D_NTFY_Token: String
)

data class Response (
    var Message: String,
    var Token: String,
    var Status: Int
)

sealed class NtfyException(message: String) : Exception(message) {
    class UnknownError : NtfyException("Unknown error occurred")
    class ConnectionError : NtfyException("Connection error occurred")
    class InvalidCredentials : NtfyException("Invalid credentials error occurred")
    class InvalidRequest : NtfyException("Invalid request error occurred")
    class NotFound : NtfyException("Resource not found error occurred")
    class InvalidResponse : NtfyException("Invalid response error occurred")
    class ServerError : NtfyException("Server error occurred")
    class ServerUnavailable : NtfyException("Server unavailable error occurred")
    class Timeout : NtfyException("Timeout error occurred")
    class UnsupportedURL : NtfyException("Unsupported URL error occurred")
    class NoDevice : NtfyException("No device error occurred")
    class NoActivity : NtfyException("No activity error occurred")
    class NoBundleIdOrApiUrl: NtfyException("🔥 - The API URL or Bundle Group is empty")
}