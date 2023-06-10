package com.lagradost

import android.content.SharedPreferences
import android.net.Uri
 import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.text.MessageFormat
import java.text.SimpleDateFormat
import java.util.Locale


class AccessTokenInterceptor(
        private val crUrl: String,
        private val preferences: SharedPreferences
    ) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val accessTokenN = getAccessToken()

        val request = newRequestWithAccessToken(chain.request(), accessTokenN)
        val response = chain.proceed(request)
        when (response.code) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> {
                synchronized(this) {
                    response.close()
                    // Access token is refreshed in another thread. Check if it has changed.
                    val newAccessToken = getAccessToken()
                    if (accessTokenN != newAccessToken) {
                        return chain.proceed(newRequestWithAccessToken(request, newAccessToken))
                    }
                    val refreshedToken = getAccessToken(true)
                    // Retry the request
                    return chain.proceed(
                        newRequestWithAccessToken(chain.request(), refreshedToken),
                    )
                }
            }
            else -> return response
        }
    }

    private fun newRequestWithAccessToken(request: Request, tokenData: AccessToken): Request {
        return request.newBuilder().let {
            it.header("authorization", "${tokenData.token_type} ${tokenData.access_token}")
            val requestUrl = Uri.decode(request.url.toString())
            if (requestUrl.contains("/cms/v2")) {
                it.url(
                    MessageFormat.format(
                        requestUrl,
                        tokenData.bucket,
                        tokenData.policy,
                        tokenData.signature,
                        tokenData.key_pair_id,
                    ),
                )
            }
            it.build()
        }
    }

    fun getAccessToken(force: Boolean = false): AccessToken {
        val token = preferences.getString(TOKEN_PREF_KEY, null)
        return if (!force && token != null) {
            token.toAccessToken()
        } else {
            synchronized(this) {
                refreshAccessToken(true)
            }
        }
    }

    fun removeToken() {
        preferences.edit().putString(TOKEN_PREF_KEY, null).apply()
    }

    private fun refreshAccessToken(useProxy: Boolean = true): AccessToken {
        removeToken()
        val client = OkHttpClient().newBuilder().let {
            if (useProxy) {
                Authenticator.setDefault(
                    object : Authenticator() {
                        override fun getPasswordAuthentication(): PasswordAuthentication {
                            return PasswordAuthentication("crunblocker", "crunblocker".toCharArray())
                        }
                    },
                )
                it.proxy(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress("cr-unblocker.us.to", 1080),
                    ),
                )
                    .build()
            } else {
                it.build()
            }
        }
        val response = client.newCall(getRequest()).execute()
        val parsedJson = parseJson<AccessToken>(response.body.string())
        //json.decodeFromString<AccessToken>(response.body.string())


        val getRequest = Request.Builder()
            .url("$crUrl/index/v2")
            .build() //GET("$crUrl/index/v2")
        val policy = client.newCall(newRequestWithAccessToken(getRequest, parsedJson)).execute()
        val policyJson = parseJson<Policy>(policy.body.string())
            //json.decodeFromString<Policy>(policy.body.string())
        val allTokens = AccessToken(
            parsedJson.access_token,
            parsedJson.token_type,
            policyJson.cms.policy,
            policyJson.cms.signature,
            policyJson.cms.key_pair_id,
            policyJson.cms.bucket,
            DateFormatter.parse(policyJson.cms.expires)?.time,
        )

        preferences.edit().putString(TOKEN_PREF_KEY, allTokens.toJsonString()).apply()
        return allTokens
    }

    private fun AccessToken.toJsonString(): String {
        return this.toJson()
    }

    private fun String.toAccessToken(): AccessToken {
        return parseJson<AccessToken>(this)
    }

    private fun getRequest(): Request {
        val client = OkHttpClient().newBuilder().build()
        val getRequest = Request.Builder()
            .url("https://raw.githubusercontent.com/Samfun75/File-host/main/aniyomi/refreshToken.txt")
            .build()
        val refreshTokenResp = client.newCall(getRequest).execute()
        val refreshToken = refreshTokenResp.body.string().replace("[\n\r]".toRegex(), "")
        val headers = Headers.Builder()
            .add("Content-Type", "application/x-www-form-urlencoded")
            .add(
                "Authorization",
                "Basic a3ZvcGlzdXZ6Yy0teG96Y21kMXk6R21JSTExenVPVnRnTjdlSWZrSlpibzVuLTRHTlZ0cU8=",
            )
            .build()
        val postBody = "grant_type=refresh_token&refresh_token=$refreshToken&scope=offline_access".toRequestBody(
            "application/x-www-form-urlencoded".toMediaType(),
        )
        val postRequest = Request.Builder()
            .url("$crUrl/auth/v1/token")
            .headers(headers)
            .post(postBody)
            .build()

        return postRequest
        //return client.newCall(postRequest).execute().request
        // POST("$crUrl/auth/v1/token", headers, postBody)
    }


    companion object {
        private const val TOKEN_PREF_KEY = "access_token_data"
        private val DateFormatter by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
        }
    }
}
