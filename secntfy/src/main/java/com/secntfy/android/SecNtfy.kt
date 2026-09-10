package com.secntfy.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

class SecNtfy internal constructor(
    private val api: SecNtfyApi,
    initialState: SecNtfyState = SecNtfyState(),
) {
    private val stateLock = Any()
    private val registrationMutex = Mutex()
    private var state = initialState

    companion object {
        private val sharedInstance: SecNtfy by lazy { SecNtfy(SecNtfyApi()) }

        fun getInstance(): SecNtfy = sharedInstance
    }

    /** Restores the persisted device identity, including when started by a notification service. */
    fun initialize(apiUrl: String = "", bundleGroup: String = "de.sr.SecNtfy", ctx: Context) {
        if (bundleGroup.isBlank()) throw NtfyException.NoBundleIdOrApiUrl()
        synchronized(stateLock) {
            val preferences = ctx.applicationContext.getSharedPreferences(bundleGroup, Context.MODE_PRIVATE)
            val savedUrl = preferences.getString("NTFY_API_URL", "").orEmpty()
            val effectiveUrl = SecNtfyApi.endpointUrl(apiUrl.ifBlank { savedUrl }).trimEnd('/')
            var privateKey = preferences.getString("NTFY_PRIV_KEY", "").orEmpty()
            var publicKey = preferences.getString("NTFY_PUB_KEY", "").orEmpty()
            if (publicKey.isBlank() || privateKey.isBlank()) {
                val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                privateKey = Base64.getEncoder().encodeToString(keyPair.private.encoded)
                publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
            }
            // A token belongs to its server and must not be sent to a newly configured host.
            val sameServer = savedUrl.trimEnd('/') == effectiveUrl
            val deviceToken = if (sameServer) preferences.getString("NTFY_DEVICE_TOKEN", "").orEmpty() else ""
            preferences.edit()
                .putString("NTFY_API_URL", effectiveUrl)
                .putString("NTFY_PRIV_KEY", privateKey)
                .putString("NTFY_PUB_KEY", publicKey)
                .putString("NTFY_DEVICE_TOKEN", deviceToken)
                .apply()
            state = SecNtfyState(
                apiUrl = effectiveUrl,
                privateKey = privateKey,
                publicKey = publicKey,
                deviceToken = deviceToken,
                preferences = preferences,
            )
        }
    }

    fun configure(apiKey: String) {
        if (apiKey.isBlank()) throw NtfyException.InvalidCredentials()
        synchronized(stateLock) {
            if (state.apiUrl.isBlank()) throw NtfyException.NotInitialized()
            state = state.copy(
                apiKey = apiKey,
                device = NTFY_Devices(
                    0, 0, 0, "SDK: ${Build.VERSION.SDK_INT}", Build.PRODUCT,
                    "", state.device?.D_Android_ID.orEmpty(), state.publicKey, state.deviceToken,
                ),
            )
        }
    }

    /** Registers or updates this device, returning a non-empty token or throwing NtfyException. */
    suspend fun registerDevice(): String = registrationMutex.withLock {
        val snapshot = synchronized(stateLock) { state }
        val device = snapshot.device ?: throw NtfyException.NoDevice()
        val token = api.registerDevice(snapshot.apiUrl, snapshot.apiKey, device.copy())
        synchronized(stateLock) {
            // Do not let an old request overwrite a new server, app, or FCM configuration.
            if (state !== snapshot) throw NtfyException.ConfigurationChanged()
            snapshot.preferences?.edit()?.putString("NTFY_DEVICE_TOKEN", token)?.apply()
            state = snapshot.copy(deviceToken = token, device = device.copy(D_NTFY_Token = token))
        }
        token
    }

    /** Compatibility callback, invoked once on the caller's coroutine context. Cancellation propagates. */
    suspend fun getNtfyToken(callback: (ntfyToken: String?, error: NtfyException?) -> Unit) {
        val token = try {
            registerDevice()
        } catch (error: NtfyException) {
            callback(null, error)
            return
        }
        // Keep callback execution outside the request's catch block.
        callback(token, null)
    }

    @Deprecated("Use configure, setFCMToken and registerDevice for persisted device registration")
    suspend fun PostDevice(
        dev: NTFY_Devices,
        appKey: String,
        callback: (ntfyToken: String?, error: NtfyException?) -> Unit,
    ) {
        val apiUrl = synchronized(stateLock) { state.apiUrl }
        val token = try {
            api.registerDevice(apiUrl, appKey, dev.copy())
        } catch (error: NtfyException) {
            callback(null, error)
            return
        }
        callback(token, null)
    }

    /** Call registerDevice afterwards to send a refreshed FCM token to the server. */
    fun setFCMToken(fcmToken: String) {
        if (fcmToken.isBlank()) throw NtfyException.InvalidRequest()
        synchronized(stateLock) {
            val device = state.device ?: throw NtfyException.NoDevice()
            state = state.copy(device = device.copy(D_Android_ID = fcmToken))
        }
    }

    /** Decrypts using the service's existing RSA/PKCS1 wire format. Never returns an error as plaintext. */
    fun decryptMessage(msg: String): String {
        val privateKey = synchronized(stateLock) { state.privateKey }
        if (privateKey.isBlank()) throw NtfyException.NotInitialized()
        try {
            val keySpec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey))
            val key = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, key)
            return String(cipher.doFinal(Base64.getDecoder().decode(msg)), Charsets.UTF_8)
        } catch (error: Exception) {
            throw NtfyException.DecryptionFailed()
        }
    }

    @Deprecated("Use decryptMessage; decryption failures now throw NtfyException")
    fun DecryptMessage(msg: String): String = decryptMessage(msg)

    /**
     * Sends the actual received message ID to the server, using the persisted device token.
     * Both DELIVERED and ALREADY_DELIVERED acknowledge receipt. On failure, throws NtfyException;
     * coroutine cancellation propagates. Callers needing offline delivery must persist and retry IDs.
     */
    suspend fun messageReceived(msgId: String): DeliveryReceipt {
        val snapshot = synchronized(stateLock) { state }
        if (snapshot.apiUrl.isBlank()) throw NtfyException.NotInitialized()
        return api.messageReceived(snapshot.apiUrl, snapshot.deviceToken, msgId)
    }

    @Deprecated("Use messageReceived to distinguish delivered from already delivered")
    suspend fun MessageReceived(msgId: String) {
        messageReceived(msgId)
    }
}

internal data class SecNtfyState(
    val apiUrl: String = "",
    val apiKey: String = "",
    val publicKey: String = "",
    val privateKey: String = "",
    val deviceToken: String = "",
    val device: NTFY_Devices? = null,
    val preferences: SharedPreferences? = null,
)
