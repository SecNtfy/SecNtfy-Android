package com.secntfy.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyProperties
import android.service.autofill.CharSequenceTransformation
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher


class SecNtfy private constructor() {

    private var _publicKey = ""
    private var _privateKey = ""
    private var _apiKey = ""
    private var _apnsToken = ""
    private var _apiUrl = ""
    private var _deviceToken = ""
    private var ntfyDevice: NTFY_Devices? = null
    private var _sharedPref: SharedPreferences? = null

    companion object {
        private var instance: SecNtfy? = null

        fun getInstance(): SecNtfy {
            if (instance != null) {
                println("♻️ - instance init")
                return instance as SecNtfy
            }
            println("♻️ - instance nil, start init process")
            return SecNtfy()
        }
    }

    fun initialize(apiUrl: String = "", bundleGroup: String = "de.sr.SecNtfy", ctx: Context) {
        _sharedPref = ctx.getSharedPreferences(bundleGroup, Context.MODE_PRIVATE)

        _publicKey = _sharedPref!!.getString("NTFY_PUB_KEY", "") ?: ""
        _privateKey = _sharedPref!!.getString("NTFY_PRIV_KEY", "") ?: ""
        _apiUrl = _sharedPref!!.getString("NTFY_API_URL", "") ?: ""
        _deviceToken = _sharedPref!!.getString("NTFY_DEVICE_TOKEN", "") ?: ""

        if (_apiKey.isEmpty() && apiUrl.isNotEmpty()) {
            _apiUrl = apiUrl
            with(_sharedPref!!.edit()) {
                putString("NTFY_API_URL", _apiUrl)
                apply()
            }
        }

        if (_apiUrl.isEmpty() || bundleGroup.isEmpty()) {
            println(NtfyException.NoBundleIdOrApiUrl())
            return
        }

        println("♻️ - API URL $_apiUrl")
        println("♻️ - Bundle Group $bundleGroup")

        if (_publicKey.isEmpty() || _privateKey.isEmpty()) {
            val keyGen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
            keyGen.initialize(2048)
            val keyPair = keyGen.generateKeyPair()

            _privateKey = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
            _publicKey = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

            with(_sharedPref!!.edit()) {
                putString("NTFY_PRIV_KEY", _privateKey)
                putString("NTFY_PUB_KEY", _publicKey)
                apply()
            }
        }

        println("PubKey: $_publicKey")
        println("PrivKey: ${anonymizeString(_privateKey)}")
    }

    fun configure(apiKey: String) {
        _apiKey = apiKey
        val model = Build.PRODUCT
        val osVersion = "SDK: ${Build.VERSION.SDK_INT}"

        ntfyDevice = NTFY_Devices(0, 0, 0, osVersion, model, "", "", _publicKey, "")

        println("configure Model: $model")
        println("configure OS: $osVersion")

        println("configure PubKey: $_publicKey")
        println("configure PrivKey: ${anonymizeString(_privateKey)}")
    }

    suspend fun getNtfyToken(callback: (ntfyToken: String?, error: NtfyException?) -> Unit) {
        if (ntfyDevice == null) {
            callback(null, NtfyException.NoDevice())
        }

        PostDevice(ntfyDevice!!, _apiKey) { ntfyToken, error ->
            if (ntfyToken == null) {
                callback(ntfyToken, error)
            }

            ntfyDevice?.D_NTFY_Token = ntfyToken!!

            if ((_deviceToken.isEmpty() || _deviceToken != ntfyToken)) {
                _deviceToken = ntfyToken ?: ""
                with(_sharedPref!!.edit()) {
                    putString("NTFY_DEVICE_TOKEN", _deviceToken)
                    apply()
                }
            }

            callback(ntfyToken, error)
        }
    }

    suspend fun PostDevice(
        dev: NTFY_Devices,
        appKey: String,
        callback: (ntfyToken: String?, error: NtfyException?) -> Unit
    ) {
        val urlString = "$_apiUrl/App/RegisterDevice"
        withContext(Dispatchers.IO) {
            try {
                val client = HttpClient(Android) {
                    install(Logging) {
                        level = LogLevel.ALL
                    }
                    install(ContentNegotiation) {
                        json(Json {
                            prettyPrint = true
                            isLenient = true
                        })
                    }
                }
                val response: HttpResponse = client.post(urlString) {
                    header(HttpHeaders.ContentType, "application/json; charset=utf-8")
                    header(HttpHeaders.Accept, "application/json; charset=utf-8")
                    header("X-NTFYME-AccessKey", appKey)
                    setBody(dev)
                }
                client.close()
                val responseText = response.readBytes().toString(Charsets.UTF_8)
                val itemType = object : TypeToken<Response>() {}.type
                val result = Gson().fromJson<Response>(responseText, itemType)
                println("♻️ - ${result.Message} ${result.Token}")
                callback(result.Token, null)
            } catch (e: NtfyException) {
                println("🔥 - Failed to PostDevice ${e.localizedMessage}")
                callback(null, e)
            } catch (e: Exception) {
                println("💥 - Unexpected error: ${e.localizedMessage}")
                callback(null, NtfyException.UnknownError())
            }
        }
    }

    fun setFCMToken(fcmToken: String) {
        if (ntfyDevice == null) {
            return
        }

        println(anonymizeString(fcmToken))
        ntfyDevice?.D_Android_ID = fcmToken
        println(ntfyDevice)
    }

    fun DecryptMessage(msg: String): String {
        var decryptedMsg = ""
        try {
            val privateKeyBytes: ByteArray = Base64.decode(_privateKey, Base64.NO_WRAP)
            val keySpecPriv = PKCS8EncodedKeySpec(privateKeyBytes)
            val keyFactory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
            val privKey = keyFactory.generatePrivate(keySpecPriv)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding") //or try with "RSA"

            cipher.init(Cipher.PRIVATE_KEY, privKey)
            val byteMsg = Base64.decode(msg.toByteArray(), Base64.NO_WRAP)
            decryptedMsg = cipher.doFinal(byteMsg).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            println("🔥 - Failed to DecryptMessage ${e.localizedMessage}")
            return "🔥 - Failed to DecryptMessage ${e.localizedMessage}"
        }
        return decryptedMsg
    }

    suspend fun MessageReceived(msgId: String) {
        val urlString = "$_apiUrl/Message/Receive/$msgId"

        if (_deviceToken.isEmpty()) {
            println("🔥 - Device Token is Empty")
            return
        }

        if (msgId.isEmpty()) {
            println("🔥 - MessageId is Empty")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val client = HttpClient(Android) {
                    install(Logging) {
                        level = LogLevel.ALL
                    }
                    install(ContentNegotiation) {
                        json(Json {
                            prettyPrint = true
                            isLenient = true
                        })
                    }
                }
                val response: HttpResponse = client.post(urlString) {
                    header(HttpHeaders.ContentType, "application/json; charset=utf-8")
                    header(HttpHeaders.Accept, "application/json; charset=utf-8")
                    header("X-NTFYME-DEVICE-KEY", _deviceToken)
                }
                client.close()
                val responseText = response.readBytes().toString(Charsets.UTF_8)
                val itemType = object : TypeToken<Response>() {}.type
                val result = Gson().fromJson<Response>(responseText, itemType)
                println("♻️ - ${result.Message} ${result.Token}")
            } catch (e: NtfyException) {
                println("🔥 - Failed to PostDevice ${e.localizedMessage}")
            } catch (e: Exception) {
                println("💥 - Unexpected error: ${e.localizedMessage}")
            }
        }
    }

    private fun anonymizeString(input: String): String {
        if (input.length <= 10) {
            return input
        }

        // Extract the first three characters
        val firstThree = input.substring(0, 5)

        // Extract the last five characters
        val lastFive = input.substring(input.length - 5, input.length)

        // Create the processed string

        return "$firstThree*****$lastFive"
    }
}