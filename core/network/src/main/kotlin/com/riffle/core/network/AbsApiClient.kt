package com.riffle.core.network

import com.google.gson.Gson
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.network.model.AbsLoginRequest
import com.riffle.core.network.model.AbsLoginResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed class NetworkLoginResult {
    data class Success(val userId: String, val token: String) : NetworkLoginResult()
    data class WrongCredentials(val message: String) : NetworkLoginResult()
    data class NetworkError(val cause: Throwable) : NetworkLoginResult()
    data class InsecureConnection(val type: InsecureConnectionType) : NetworkLoginResult()
}

class AbsApiClient(private val httpClient: OkHttpClient) : AbsApi {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): NetworkLoginResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val body = gson.toJson(AbsLoginRequest(username, password)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/login")
            .post(body)
            .build()
        try {
            val response = client.newCall(request).execute()
            when (response.code) {
                200 -> {
                    val raw = response.body?.string() ?: return@withContext NetworkLoginResult.NetworkError(
                        IOException("Empty response body")
                    )
                    val parsed = gson.fromJson(raw, AbsLoginResponse::class.java)
                    NetworkLoginResult.Success(userId = parsed.user.id, token = parsed.user.token)
                }
                401 -> NetworkLoginResult.WrongCredentials("Invalid username or password")
                else -> NetworkLoginResult.NetworkError(IOException("Unexpected HTTP ${response.code}"))
            }
        } catch (e: SSLHandshakeException) {
            NetworkLoginResult.InsecureConnection(InsecureConnectionType.SELF_SIGNED)
        } catch (e: IOException) {
            NetworkLoginResult.NetworkError(e)
        }
    }

    private fun OkHttpClient.trustAllCerts(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        return newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
