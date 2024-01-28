package com.lagradost

import android.net.Uri
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.requestCreator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.*
import java.text.MessageFormat
import java.text.SimpleDateFormat
import java.util.Locale

class TokenUtils : Interceptor {
    private val crUrl = "https://beta-api.crunchyroll.com"
    private val crBasicToken = "b2VkYXJteHN0bGgxanZhd2ltbnE6OWxFaHZIWkpEMzJqdVY1ZFc5Vk9TNTdkb3BkSnBnbzE="

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

    fun getAccessToken(): AccessToken {
        synchronized(this) {
            return refreshAccessToken()
        }
    }

    fun getCrunchyrollToken(): Map<String, String> {
        synchronized(this) {
            val response = refreshAccessToken()
            return mapOf("Authorization" to "${response?.token_type} ${response?.access_token}")
        }
    }

    private fun refreshAccessToken(): AccessToken {
        val client = app.baseClient.newBuilder().let {
            Authenticator.setDefault(
                object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication("crunblocker", "crunblocker".toCharArray())
                    }
                },
            )
            it.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("cr-unblocker.us.to", 1080))).build()
        }

        val response = client.newCall(getRequest()).execute()
        val parsedJson = tryParseJson<AccessToken>(response.body.string())!!
        val policy = client.newCall(newRequestWithAccessToken(requestCreator("GET", "$crUrl/index/v2"), parsedJson)).execute()
        val policyJson = tryParseJson<Policy>(policy.body.string())!!

        return AccessToken(
            parsedJson.access_token,
            parsedJson.token_type,
            policyJson.cms.policy,
            policyJson.cms.signature,
            policyJson.cms.key_pair_id,
            policyJson.cms.bucket,
            DATE_FORMATTER.parse(policyJson.cms.expires)?.time,
        )
    }

    private fun getRequest(): Request {
        val client = app.baseClient.newBuilder().build()
        val refreshTokenResp = client.newCall(
            requestCreator(
                "GET",
                "https://raw.githubusercontent.com/Samfun75/File-host/main/aniyomi/refreshToken.txt"
            )
        ).execute()
        val refreshToken = refreshTokenResp.body.string().replace("[\n\r]".toRegex(), "")
        return requestCreator(
            method = "POST",
            url = "$crUrl/auth/v1/token",
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Authorization" to "Basic $crBasicToken"
            ),
            data = mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "scope" to "offline_access"
            )
        )
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(this) {
            val refreshedToken = getAccessToken()
            // Retry the request
            return chain.proceed(
                newRequestWithAccessToken(chain.request(), refreshedToken),
            )
        }
    }
}